package com.indigo.mobileobservatory.camera.qhyccd

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.util.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.round
import kotlin.math.roundToInt

class QhyCamera : Camera, CameraOffsetCapable, CameraNativeReadoutModeCapable,
    CameraUsbBandwidthCapable {

    companion object {
        private const val TAG = "QhyCamera"

        /** Transfer depths that map onto a [PixelFormat]; probed against the SDK range. */
        private val CANDIDATE_TRANSFER_BITS = listOf(8, 12, 16)
    }

    private val _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    override val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    override var cameraInfo: CameraInfo? = null; private set
    override var exposureRange = FloatRange(1f, 3600_000_000f, 20_000f); private set
    override var gainRange = FloatRange(0f, 80f, 0f); private set
    override var currentExposureUs = 20_000f; private set
    override var currentGain = 0f; private set
    override var offsetSupported = false; private set
    override var offsetRange = FloatRange(0f, 0f, 0f); private set
    override var offsetStep = 1f; private set
    override var currentOffset = 0f; private set
    override var supportedNativeReadoutModes: List<CameraNativeReadoutMode> = emptyList(); private set
    override var currentNativeReadoutModeId: String = "0"; private set
    override var usbBandwidthRange: IntRange? = null; private set
    override var currentUsbBandwidth: Int? = null; private set
    override var currentPixelFormat = PixelFormat.MONO16; private set
    override var supportedPixelFormats = listOf(PixelFormat.MONO16, PixelFormat.MONO8); private set
    override var currentRoi = Roi(0, 0, 1, 1); private set
    override var cropInfo = CropInfo(0, 0, 1, 1); private set
    override var hwExposureMaxUs = 3600_000_000f; private set
    override var longExposureEnabled = false

    private var sensorWidth = 0
    private var sensorHeight = 0
    private var maxBpp = 16
    private var bayerType = -1
    private var supportedTransferBits = listOf(8)

    private var frameCallback: FrameCallback? = null
    private var captureThread: Thread? = null
    private val frameCounter = AtomicLong(0)
    private val bufferPool = ConcurrentLinkedQueue<ByteArray>()

    @Volatile private var useSingleFrameMode = false
    private var savedCameraId: String? = null
    private var usbVid = 0
    private var usbPid = 0
    private var usbFd = 0
    private var usbDevice: UsbDevice? = null
    private var appContext: Context? = null
    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null

    fun setUsbInfo(vid: Int, pid: Int, fd: Int) {
        usbVid = vid
        usbPid = pid
        usbFd = fd
    }

    fun setUsbContext(context: Context, device: UsbDevice, connection: android.hardware.usb.UsbDeviceConnection) {
        appContext = context.applicationContext
        usbDevice = device
        usbConnection = connection
    }

    fun open(cameraId: String): Boolean {
        return openInternal(cameraId, singleFrame = false)
    }

    private fun openInternal(cameraId: String, singleFrame: Boolean, desiredBits: Int = 8): Boolean {
        try {
            FileLogger.i(TAG, "openInternal: id=$cameraId singleFrame=$singleFrame usbFd=$usbFd")

            // Retry scan instead of a fixed sleep + single attempt
            var numCams = 0
            val scanDeadline = System.currentTimeMillis() + 5000
            while (true) {
                numCams = QhyccdJni.scan()
                if (numCams > 0 || System.currentTimeMillis() >= scanDeadline) break
                Thread.sleep(200)
            }
            FileLogger.i(TAG, "Scan found $numCams camera(s)")
            if (numCams <= 0) {
                Log.e(TAG, "No cameras found after scan")
                return false
            }

            val resolvedId = QhyccdJni.getId(0)
            FileLogger.i(TAG, "Scanned ID: $resolvedId")

            val openRet = QhyccdJni.open(resolvedId)
            FileLogger.i(TAG, "OpenQHYCCD result: $openRet")
            if (!openRet) {
                Log.e(TAG, "OpenQHYCCD failed for $resolvedId")
                return false
            }

            savedCameraId = resolvedId
            val streamMode = if (singleFrame) 0 else 1
            QhyccdJni.setReadMode(0)
            initNativeReadoutModes()
            val smRet = QhyccdJni.setStreamMode(streamMode)
            FileLogger.i(TAG, "StreamMode=$streamMode ret=$smRet (${if (singleFrame) "single-frame" else "live"})")

            val initRet = QhyccdJni.initCamera()
            if (initRet != QhyccdJni.QHYCCD_SUCCESS) {
                Log.e(TAG, "InitQHYCCD failed: $initRet")
                QhyccdJni.close()
                return false
            }

            val chipInfo = QhyccdJni.getChipInfo()
            if (chipInfo == null) {
                Log.e(TAG, "GetChipInfo failed")
                QhyccdJni.close()
                return false
            }

            sensorWidth = chipInfo[0]
            sensorHeight = chipInfo[1]
            maxBpp = chipInfo[2]
            val pixelSizeUm = chipInfo[3] / 1000.0f

            bayerType = QhyccdJni.getBayerType()

            if (bayerType < 0) {
                val model = cameraId.split("-").firstOrNull()?.uppercase() ?: ""
                val isColor = model.endsWith("C") || model.contains("COLOR")
                if (isColor) {
                    bayerType = QhyccdJni.BAYER_RG
                    Log.i(TAG, "Bayer fallback: detected color camera from ID '$cameraId', using BAYER_RG")
                }
            }

            currentRoi = Roi(0, 0, sensorWidth, sensorHeight)
            cropInfo = CropInfo(0, 0, sensorWidth, sensorHeight)

            QhyccdJni.setBinMode(1, 1)
            QhyccdJni.setResolution(0, 0, sensorWidth, sensorHeight)
            QhyccdJni.setDebayerOnOff(false)

            useSingleFrameMode = singleFrame
            initPixelFormats(desiredBits)

            val modelName = extractModelName(cameraId)
            cameraInfo = CameraInfo(
                name = modelName,
                serialNumber = extractSerialNumber(cameraId),
                sensorWidth = sensorWidth,
                sensorHeight = sensorHeight,
                maxBitDepth = maxBpp,
                sensorName = null,
                pixelSizeUm = pixelSizeUm.takeIf { it > 0f }
            )
            initExposureRange()
            initGainRange()
            initOffsetRange()

            initUsbBandwidth()
            setExposureTime(20_000f)
            setGain(20f)

            _isOpen.value = true
            Log.i(TAG, "Opened: $modelName ${sensorWidth}x${sensorHeight} ${maxBpp}bit bayer=$bayerType pixSize=${pixelSizeUm}um singleFrame=$singleFrame")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "open failed", e)
            try { QhyccdJni.close() } catch (_: Throwable) {}
            return false
        }
    }

    override fun setUsbBandwidth(value: Int): Boolean {
        val range = usbBandwidthRange ?: return false
        val target = value.coerceIn(range.first, range.last)
        val result = QhyccdJni.setParam(QhyccdJni.CONTROL_USBTRAFFIC, target.toDouble())
        if (result != QhyccdJni.QHYCCD_SUCCESS) {
            FileLogger.w(TAG, "Set USB traffic failed: target=$target result=$result")
            return false
        }
        val readBack = QhyccdJni.getParam(QhyccdJni.CONTROL_USBTRAFFIC)
        currentUsbBandwidth = readBack.takeIf { it.isFinite() }
            ?.roundToInt()?.takeIf { it in range } ?: target
        FileLogger.i(TAG, "USB traffic set: requested=$target current=$currentUsbBandwidth")
        return true
    }

    private fun initUsbBandwidth() {
        val sdkRange = QhyccdJni.getParamRange(QhyccdJni.CONTROL_USBTRAFFIC)
        if (sdkRange == null || sdkRange.size < 2) {
            usbBandwidthRange = null
            currentUsbBandwidth = null
            return
        }
        val min = sdkRange[0].roundToInt()
        val max = sdkRange[1].roundToInt()
        if (min > max) {
            usbBandwidthRange = null
            currentUsbBandwidth = null
            return
        }
        usbBandwidthRange = min..max
        setUsbBandwidth(0.coerceIn(min, max))
    }

    private fun initPixelFormats(desiredBits: Int) {
        supportedTransferBits = probeTransferBits(chipBpp = maxBpp)
        val formats = supportedTransferBits.map { pixelFormatForBits(it) }.distinct()
        supportedPixelFormats = formats
        maxBpp = formats.maxOf { it.nativeBits }

        val bits = if (desiredBits in supportedTransferBits) desiredBits else supportedTransferBits.first()
        applyTransferBit(bits)
        currentPixelFormat = pixelFormatForBits(bits)
        Log.i(TAG, "Transfer bits $supportedTransferBits -> formats ${formats.joinToString { it.name }}, active=${currentPixelFormat.name}")
    }

    /**
     * CONTROL_TRANSFERBIT is the USB transfer container (8 or 16 on nearly every
     * model), not the sensor's native depth. Offering a value the SDK silently
     * rejects leaves the camera streaming at a depth the UI does not expect, so
     * derive the list from the SDK range and confirm each value by read-back.
     */
    private fun probeTransferBits(chipBpp: Int): List<Int> {
        val range = QhyccdJni.getParamRange(QhyccdJni.CONTROL_TRANSFERBIT)
        if (range == null) {
            Log.w(TAG, "TRANSFERBIT range unavailable, assuming 8-bit only")
            return listOf(8)
        }
        val min = range[0].toInt()
        val max = (range[1].toInt()).coerceAtMost(maxOf(chipBpp, range[0].toInt()))
        val step = range[2].toInt()
        Log.i(TAG, "TRANSFERBIT range: ${range[0].toInt()}..${range[1].toInt()} step=$step chipInfo bpp=$chipBpp")
        if (min <= 0 || max < min) return listOf(8)
        if (min == max) return listOf(min)

        val candidates = CANDIDATE_TRANSFER_BITS.filter {
            it in min..max && (step <= 0 || (it - min) % step == 0)
        }
        if (candidates.isEmpty()) return listOf(min, max)

        // GetQHYCCDParam does not report TRANSFERBIT on every model. Without a
        // read-back an accepted value is indistinguishable from an ignored one,
        // so fall back to the two endpoints the SDK range guarantees.
        QhyccdJni.setParam(QhyccdJni.CONTROL_TRANSFERBIT, min.toDouble())
        if (QhyccdJni.getParam(QhyccdJni.CONTROL_TRANSFERBIT).toInt() != min) {
            Log.i(TAG, "TRANSFERBIT read-back unsupported, trusting SDK range endpoints")
            return listOf(min, max)
        }

        val accepted = candidates.filter { bits ->
            QhyccdJni.setParam(QhyccdJni.CONTROL_TRANSFERBIT, bits.toDouble()) == QhyccdJni.QHYCCD_SUCCESS &&
                QhyccdJni.getParam(QhyccdJni.CONTROL_TRANSFERBIT).toInt() == bits
        }
        return accepted.ifEmpty { listOf(min) }
    }

    private fun applyTransferBit(bits: Int): Boolean {
        val ret = QhyccdJni.setParam(QhyccdJni.CONTROL_TRANSFERBIT, bits.toDouble())
        if (ret != QhyccdJni.QHYCCD_SUCCESS) {
            FileLogger.e(TAG, "SetTransferBit($bits) failed: ret=$ret")
            return false
        }
        val readback = QhyccdJni.getParam(QhyccdJni.CONTROL_TRANSFERBIT).toInt()
        if (readback > 0 && readback != bits) {
            FileLogger.e(TAG, "SetTransferBit($bits) ignored, camera reports ${readback}bit")
            return false
        }
        return true
    }

    private fun pixelFormatForBits(bits: Int): PixelFormat {
        return when (bayerType) {
            QhyccdJni.BAYER_RG -> when { bits <= 8 -> PixelFormat.BAYER_RG8; bits <= 12 -> PixelFormat.BAYER_RG12; else -> PixelFormat.BAYER_RG16 }
            QhyccdJni.BAYER_GR -> when { bits <= 8 -> PixelFormat.BAYER_GR8; bits <= 12 -> PixelFormat.BAYER_GR12; else -> PixelFormat.BAYER_GR16 }
            QhyccdJni.BAYER_GB -> when { bits <= 8 -> PixelFormat.BAYER_GB8; bits <= 12 -> PixelFormat.BAYER_GB12; else -> PixelFormat.BAYER_GB16 }
            QhyccdJni.BAYER_BG -> when { bits <= 8 -> PixelFormat.BAYER_BG8; bits <= 12 -> PixelFormat.BAYER_BG12; else -> PixelFormat.BAYER_BG16 }
            else -> when { bits <= 8 -> PixelFormat.MONO8; bits <= 12 -> PixelFormat.MONO12; else -> PixelFormat.MONO16 }
        }
    }

    private fun initExposureRange() {
        val range = QhyccdJni.getParamRange(QhyccdJni.CONTROL_EXPOSURE)
        if (range != null) {
            exposureRange = FloatRange(range[0].toFloat(), range[1].toFloat(), currentExposureUs)
            hwExposureMaxUs = range[1].toFloat()
        }
    }

    private fun initGainRange() {
        val range = QhyccdJni.getParamRange(QhyccdJni.CONTROL_GAIN)
        if (range != null) {
            gainRange = FloatRange(range[0].toFloat(), range[1].toFloat(), currentGain)
        }
    }

    private fun initOffsetRange() {
        offsetSupported = false
        offsetRange = FloatRange(0f, 0f, 0f)
        val range = QhyccdJni.getParamRange(QhyccdJni.CONTROL_OFFSET) ?: return
        if (range.size < 2 || range[1] < range[0]) return
        val current = QhyccdJni.getParam(QhyccdJni.CONTROL_OFFSET).toFloat()
        offsetSupported = true
        currentOffset = current.coerceIn(range[0].toFloat(), range[1].toFloat())
        offsetStep = range.getOrNull(2)?.toFloat()?.takeIf { it > 0f } ?: 1f
        offsetRange = FloatRange(range[0].toFloat(), range[1].toFloat(), currentOffset)
    }

    private fun initNativeReadoutModes() {
        val count = QhyccdJni.getNumberOfReadModes().coerceIn(0, 32)
        supportedNativeReadoutModes = if (count > 0) {
            (0 until count).map { index ->
                val name = QhyccdJni.getReadModeName(index).trim()
                CameraNativeReadoutMode(index.toString(), name.ifBlank { "Read mode ${index + 1}" })
            }
        } else {
            listOf(CameraNativeReadoutMode("0", "Read mode 1"))
        }
        currentNativeReadoutModeId = "0"
        FileLogger.i(TAG, "QHY readout modes: ${supportedNativeReadoutModes.joinToString { it.displayName }}")
    }

    override fun close() {
        stopCapture()
        if (_isOpen.value) {
            try { QhyccdJni.close() } catch (e: Throwable) {
                Log.e(TAG, "close error", e)
            }
            _isOpen.value = false
        }
        bufferPool.clear()
        savedCameraId = null
        useSingleFrameMode = false
        try { usbConnection?.close() } catch (_: Throwable) {}
        usbConnection = null
    }

    /**
     * Tear down after the USB device was physically unplugged.
     * Unlike [close], this performs NO USB I/O (StopLive / CancelExposing /
     * CloseQHYCCD would all issue transfers to a dead fd and can leave the
     * SDK's global state corrupted, causing "connected but no frames" on the
     * next plug-in). It only stops the capture thread and drops the handle.
     */
    fun markDisconnected() {
        FileLogger.i(TAG, "markDisconnected: USB device gone, skipping SDK teardown I/O")
        _isCapturing.value = false
        val thread = captureThread
        captureThread = null
        thread?.interrupt()
        try { thread?.join(2000) } catch (_: InterruptedException) {}
        frameCallback = null

        try { QhyccdJni.markDisconnected() } catch (_: Throwable) {}
        _isOpen.value = false
        bufferPool.clear()
        savedCameraId = null
        useSingleFrameMode = false
        try { usbConnection?.close() } catch (_: Throwable) {}
        usbConnection = null
    }

    override fun startCapture(callback: FrameCallback) {
        if (!_isOpen.value || _isCapturing.value) return
        frameCallback = callback

        if (useSingleFrameMode) {
            _isCapturing.value = true
            captureThread = Thread({
                singleFrameLoop()
            }, "QHY-SingleFrame").apply { isDaemon = true; start() }
        } else {
            val ret = QhyccdJni.beginLive()
            if (ret != QhyccdJni.QHYCCD_SUCCESS) {
                Log.e(TAG, "BeginQHYCCDLive failed: $ret")
                return
            }

            _isCapturing.value = true
            captureThread = Thread({
                liveFrameLoop()
            }, "QHY-CaptureThread").apply { isDaemon = true; start() }
        }
    }

    private fun liveFrameLoop() {
        val roi = currentRoi
        val bpp = currentPixelFormat.bytesPerPixel
        val frameSize = roi.width.toLong() * roi.height * bpp
        val memLen = QhyccdJni.getMemLength().toLong().coerceAtLeast(frameSize)
        if (memLen > Int.MAX_VALUE || memLen <= 0) {
            Log.e(TAG, "Invalid memLen: $memLen")
            return
        }
        val outInfo = IntArray(4)

        Log.i(TAG, "Capture loop started: ${roi.width}x${roi.height} bpp=$bpp frameSize=$frameSize memLen=$memLen")
        FileLogger.i(TAG, "Capture loop: ${roi.width}x${roi.height} bpp=$bpp fmt=${currentPixelFormat.name} frameSize=$frameSize memLen=$memLen")

        val sdkBuf: ByteArray
        var displayBufA: ByteArray
        var displayBufB: ByteArray
        try {
            sdkBuf = ByteArray(memLen.toInt())
            displayBufA = ByteArray(memLen.toInt())
            displayBufB = ByteArray(memLen.toInt())
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Failed to allocate frame buffers ($memLen bytes each)", e)
            return
        }
        var useA = true

        while (_isCapturing.value && !Thread.currentThread().isInterrupted) {
            try {
                val ret = QhyccdJni.getLiveFrame(sdkBuf, outInfo)

                if (ret == QhyccdJni.QHYCCD_SUCCESS) {
                    val w = outInfo[0]
                    val h = outInfo[1]
                    val bits = outInfo[2]
                    val channels = outInfo[3]

                    if (w <= 0 || h <= 0 || w > 20000 || h > 20000) {
                        Log.w(TAG, "Invalid frame dimensions: ${w}x${h}")
                        Thread.sleep(10)
                        continue
                    }

                    val fmt = resolvePixelFormat(bits, channels)
                    val actualDataLen = w.toLong() * h * fmt.bytesPerPixel
                    if (frameCounter.get() < 3) {
                        FileLogger.i(TAG, "LiveFrame: ${w}x${h} bits=$bits ch=$channels fmt=${fmt.name} bpp=${fmt.bytesPerPixel} dataLen=$actualDataLen bufSize=${sdkBuf.size}")
                    }
                    if (actualDataLen > sdkBuf.size) {
                        Log.w(TAG, "Frame data exceeds buffer: $actualDataLen > ${sdkBuf.size}")
                        Thread.sleep(10)
                        continue
                    }

                    val displayBuf = if (useA) displayBufA else displayBufB
                    useA = !useA
                    System.arraycopy(sdkBuf, 0, displayBuf, 0, actualDataLen.toInt())

                    val frame = FrameData(
                        data = displayBuf,
                        width = w,
                        height = h,
                        pixelFormat = fmt,
                        frameId = frameCounter.incrementAndGet(),
                        timestamp = System.nanoTime()
                    )
                    try {
                        frameCallback?.onFrame(frame)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Frame callback error", e)
                    }
                } else {
                    if (frameCounter.get() < 3) {
                        Log.w(TAG, "GetLiveFrame failed (ret=$ret), fmt=${currentPixelFormat.name}")
                    }
                    Thread.sleep(1)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Throwable) {
                Log.e(TAG, "Capture loop error", e)
                Thread.sleep(10)
            }
        }
        Log.i(TAG, "Capture loop ended")
    }

    private fun singleFrameLoop() {
        val roi = currentRoi
        val bpp = currentPixelFormat.bytesPerPixel
        val frameSize = roi.width.toLong() * roi.height * bpp
        val memLen = QhyccdJni.getMemLength().toLong().coerceAtLeast(frameSize)
        if (memLen > Int.MAX_VALUE || memLen <= 0) {
            Log.e(TAG, "Invalid memLen: $memLen")
            return
        }

        val sdkBuf: ByteArray
        var displayBufA: ByteArray
        var displayBufB: ByteArray
        try {
            sdkBuf = ByteArray(memLen.toInt())
            displayBufA = ByteArray(memLen.toInt())
            displayBufB = ByteArray(memLen.toInt())
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Failed to allocate frame buffers ($memLen bytes each)", e)
            return
        }
        val outInfo = IntArray(4)
        var useA = true

        FileLogger.i(TAG, "SingleFrame loop: ${roi.width}x${roi.height} bpp=$bpp fmt=${currentPixelFormat.name}")

        while (_isCapturing.value && !Thread.currentThread().isInterrupted) {
            try {
                val expRet = QhyccdJni.expSingleFrame()
                if (expRet != QhyccdJni.QHYCCD_SUCCESS) {
                    Log.e(TAG, "ExpSingleFrame failed: $expRet")
                    Thread.sleep(100)
                    continue
                }

                val getRet = QhyccdJni.getSingleFrame(sdkBuf, outInfo)
                if (getRet != QhyccdJni.QHYCCD_SUCCESS) {
                    Log.e(TAG, "GetSingleFrame failed: $getRet")
                    Thread.sleep(100)
                    continue
                }

                val w = outInfo[0]
                val h = outInfo[1]
                val bits = outInfo[2]
                val channels = outInfo[3]

                if (w <= 0 || h <= 0 || w > 20000 || h > 20000) {
                    Log.w(TAG, "Invalid single frame dimensions: ${w}x${h}")
                    Thread.sleep(100)
                    continue
                }

                val fmt = resolvePixelFormat(bits, channels)
                val actualDataLen = w.toLong() * h * fmt.bytesPerPixel

                if (frameCounter.get() < 5) {
                    FileLogger.i(TAG, "SingleFrame: ${w}x${h} bits=$bits ch=$channels fmt=${fmt.name} bpp=${fmt.bytesPerPixel} dataLen=$actualDataLen")
                }

                if (actualDataLen > sdkBuf.size) {
                    Log.w(TAG, "Single frame data exceeds buffer: $actualDataLen > ${sdkBuf.size}")
                    Thread.sleep(100)
                    continue
                }

                val displayBuf = if (useA) displayBufA else displayBufB
                useA = !useA
                System.arraycopy(sdkBuf, 0, displayBuf, 0, actualDataLen.toInt())

                val frame = FrameData(
                    data = displayBuf,
                    width = w,
                    height = h,
                    pixelFormat = fmt,
                    frameId = frameCounter.incrementAndGet(),
                    timestamp = System.nanoTime()
                )
                try {
                    frameCallback?.onFrame(frame)
                } catch (e: Throwable) {
                    Log.e(TAG, "Frame callback error", e)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Throwable) {
                Log.e(TAG, "SingleFrame loop error", e)
                Thread.sleep(100)
            }
        }
        FileLogger.i(TAG, "SingleFrame loop ended")
    }

    private fun resolvePixelFormat(bits: Int, channels: Int): PixelFormat {
        if (channels >= 3) return PixelFormat.RGB48
        if (bayerType > 0) return pixelFormatForBits(bits)
        return when (bits) {
            8 -> PixelFormat.MONO8
            10 -> PixelFormat.MONO10
            12 -> PixelFormat.MONO12
            14 -> PixelFormat.MONO14
            else -> PixelFormat.MONO16
        }
    }

    override fun stopCapture() {
        if (!_isCapturing.value) return
        _isCapturing.value = false

        if (useSingleFrameMode) {
            try { QhyccdJni.cancelExposing() } catch (_: Throwable) {}
        }

        val thread = captureThread
        captureThread = null
        thread?.interrupt()
        try {
            thread?.join(5000)
        } catch (_: InterruptedException) {}

        if (!useSingleFrameMode) {
            try { QhyccdJni.stopLive() } catch (e: Throwable) {
                Log.e(TAG, "StopLive error", e)
            }
        }
        frameCallback = null
    }

    override fun setExposureTime(us: Float) {
        currentExposureUs = us.coerceIn(exposureRange.min, exposureRange.max)
        QhyccdJni.setParam(QhyccdJni.CONTROL_EXPOSURE, currentExposureUs.toDouble())
    }

    override fun setGain(db: Float) {
        currentGain = db.coerceIn(gainRange.min, gainRange.max)
        QhyccdJni.setParam(QhyccdJni.CONTROL_GAIN, currentGain.toDouble())
    }

    override fun setOffset(value: Float) {
        if (!offsetSupported || offsetRange.max < offsetRange.min) return
        val clamped = value.coerceIn(offsetRange.min, offsetRange.max)
        currentOffset = if (offsetStep > 0f) {
            (offsetRange.min + round((clamped - offsetRange.min) / offsetStep) * offsetStep)
                .coerceIn(offsetRange.min, offsetRange.max)
        } else {
            clamped
        }
        QhyccdJni.setParam(QhyccdJni.CONTROL_OFFSET, currentOffset.toDouble())
    }

    override fun setNativeReadoutMode(id: String): Boolean {
        val mode = id.toIntOrNull() ?: return false
        if (supportedNativeReadoutModes.none { it.id == id }) return false
        if (id == currentNativeReadoutModeId) return true

        val wasCapturing = _isCapturing.value
        val callback = frameCallback
        if (wasCapturing) stopCapture()

        val previousExposure = currentExposureUs
        val previousGain = currentGain
        val previousOffset = currentOffset
        return try {
            if (QhyccdJni.setReadMode(mode) != QhyccdJni.QHYCCD_SUCCESS) return false
            if (QhyccdJni.initCamera() != QhyccdJni.QHYCCD_SUCCESS) return false

            val chipInfo = QhyccdJni.getChipInfo() ?: return false
            sensorWidth = chipInfo[0]
            sensorHeight = chipInfo[1]
            maxBpp = chipInfo[2]
            QhyccdJni.setBinMode(1, 1)
            QhyccdJni.setResolution(0, 0, sensorWidth, sensorHeight)
            currentRoi = Roi(0, 0, sensorWidth, sensorHeight)
            cropInfo = CropInfo(0, 0, sensorWidth, sensorHeight)
            initPixelFormats(currentPixelFormat.nativeBits)
            initExposureRange()
            initGainRange()
            initOffsetRange()
            setExposureTime(previousExposure)
            setGain(previousGain)
            setOffset(previousOffset)
            currentNativeReadoutModeId = id
            true
        } catch (error: Throwable) {
            FileLogger.e(TAG, "QHY readout mode switch failed: ${error.message}", error)
            false
        } finally {
            if (wasCapturing && callback != null) startCapture(callback)
        }
    }

    private val formatLock = Any()

    override fun setPixelFormat(format: PixelFormat) {
        synchronized(formatLock) {
            if (format !in supportedPixelFormats) return
            if (format == currentPixelFormat) return
            if (!_isOpen.value) return

            val previous = currentPixelFormat
            val wasCapturing = _isCapturing.value
            val cb = frameCallback
            try {
                if (wasCapturing) stopCapture()

                val bits = format.nativeBits
                val needSingleFrame = bits > 8
                Log.i(TAG, "setPixelFormat: ${previous.name} -> ${format.name} (${bits}bit) singleFrame=$needSingleFrame")

                val applied = if (needSingleFrame != useSingleFrameMode) {
                    switchStreamMode(needSingleFrame, bits)
                } else {
                    applyTransferBit(bits)
                }

                if (applied) {
                    currentPixelFormat = format
                } else {
                    FileLogger.e(TAG, "Switch to ${format.name} failed, reverting to ${previous.name}")
                    revertTo(previous)
                }
            } catch (e: Throwable) {
                FileLogger.e(TAG, "setPixelFormat(${format.name}) failed", e)
                runCatching { revertTo(previous) }
            }

            if (wasCapturing && cb != null) startCapture(cb)
        }
    }

    /**
     * Moves between live and single-frame streaming, then restores everything
     * the SDK drops on re-init (resolution, exposure, gain, transfer bit).
     */
    private fun switchStreamMode(singleFrame: Boolean, bits: Int): Boolean {
        val camId = savedCameraId ?: return false
        val roi = currentRoi
        val exposure = currentExposureUs
        val gain = currentGain

        if (!reinitStreamMode(singleFrame) && !reopenWithUsbReset(camId, singleFrame, bits)) {
            return false
        }

        QhyccdJni.setResolution(roi.x, roi.y, roi.width, roi.height)
        currentRoi = roi
        cropInfo = CropInfo(roi.x, roi.y, roi.width, roi.height)
        setExposureTime(exposure)
        setGain(gain)
        if (!applyTransferBit(bits)) return false

        FileLogger.i(
            TAG,
            "Stream mode -> ${if (singleFrame) "single-frame" else "live"} @${bits}bit, " +
                "ROI=${roi.width}x${roi.height} exp=${exposure.toInt()}us gain=$gain"
        )
        return true
    }

    /**
     * SetQHYCCDStreamMode followed by InitQHYCCD on the open handle is the SDK's
     * own way to change streaming mode. Trying it before [reopenWithUsbReset]
     * avoids a USB re-enumeration, which regularly fails to bring the camera
     * back and then leaves it unusable.
     */
    private fun reinitStreamMode(singleFrame: Boolean): Boolean {
        val mode = if (singleFrame) 0 else 1
        val smRet = QhyccdJni.setStreamMode(mode)
        if (smRet != QhyccdJni.QHYCCD_SUCCESS) {
            Log.w(TAG, "SetStreamMode($mode) failed: $smRet, falling back to USB reset")
            return false
        }
        val initRet = QhyccdJni.initCamera()
        if (initRet != QhyccdJni.QHYCCD_SUCCESS) {
            Log.w(TAG, "InitQHYCCD after stream mode change failed: $initRet, falling back to USB reset")
            return false
        }
        useSingleFrameMode = singleFrame
        QhyccdJni.setBinMode(1, 1)
        QhyccdJni.setDebayerOnOff(false)
        return true
    }

    /**
     * Best-effort return to the depth that was already working, so a rejected
     * format cannot leave the camera closed or streaming a depth nobody asked for.
     */
    private fun revertTo(format: PixelFormat) {
        val bits = format.nativeBits
        val restored = if ((bits > 8) != useSingleFrameMode) {
            switchStreamMode(bits > 8, bits)
        } else {
            applyTransferBit(bits)
        }
        if (!restored) {
            FileLogger.e(TAG, "Could not restore ${format.name}; camera needs a reconnect")
        }
        currentPixelFormat = format
    }

    private fun reopenWithUsbReset(cameraId: String, singleFrame: Boolean, desiredBits: Int): Boolean {
        val ctx = appContext ?: run { Log.e(TAG, "No app context for USB reset"); return false }
        val dev = usbDevice ?: run { Log.e(TAG, "No USB device for reset"); return false }

        Log.i(TAG, "reopenWithUsbReset: closing camera + SDK + USB")

        try { QhyccdJni.close() } catch (_: Throwable) {}
        _isOpen.value = false

        try { QhyccdJni.releaseResource() } catch (_: Throwable) {}

        try { usbConnection?.close() } catch (_: Throwable) {}
        usbConnection = null
        usbFd = 0

        Thread.sleep(300)

        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val newConn = usbManager.openDevice(dev)
        if (newConn == null) {
            FileLogger.e(TAG, "reopenWithUsbReset: UsbManager.openDevice returned null")
            return false
        }
        usbConnection = newConn
        for (i in 0 until dev.interfaceCount) {
            if (!newConn.claimInterface(dev.getInterface(i), true)) {
                FileLogger.e(TAG, "reopenWithUsbReset: claimInterface($i) failed")
                newConn.close()
                usbConnection = null
                return false
            }
        }
        val newFd = newConn.fileDescriptor
        usbFd = newFd
        Log.i(TAG, "reopenWithUsbReset: new fd=$newFd")

        val resourceRet = QhyccdJni.initResource()
        if (resourceRet != QhyccdJni.QHYCCD_SUCCESS) {
            FileLogger.e(TAG, "reopenWithUsbReset: InitQHYCCDResource failed: $resourceRet")
            return false
        }
        QhyccdJni.initFirmware(usbVid, usbPid, newFd)
        Thread.sleep(300)

        return openInternal(cameraId, singleFrame, desiredBits)
    }

    override fun setRoi(roi: Roi) {
        try {
            val x = roi.x.coerceIn(0, sensorWidth - roiMinWidth)
            val y = roi.y.coerceIn(0, sensorHeight - roiMinHeight)
            val w = roi.width.coerceIn(roiMinWidth, sensorWidth - x)
            val h = roi.height.coerceIn(roiMinHeight, sensorHeight - y)

            val wasCapturing = _isCapturing.value
            val cb = frameCallback
            if (wasCapturing) stopCapture()

            Log.i(TAG, "setRoi: ${x},${y} ${w}x${h}")
            QhyccdJni.setResolution(x, y, w, h)
            currentRoi = Roi(x, y, w, h)
            cropInfo = CropInfo(x, y, w, h)

            if (wasCapturing && cb != null) startCapture(cb)
        } catch (e: Throwable) {
            Log.e(TAG, "setRoi failed", e)
        }
    }

    override fun resetRoi() {
        setRoi(Roi(0, 0, sensorWidth, sensorHeight))
    }

    override fun recycleBuffer(buf: ByteArray) {
        if (bufferPool.size < 8) {
            bufferPool.offer(buf)
        }
    }

    private fun extractModelName(cameraId: String): String {
        val dashIndex = cameraId.lastIndexOf('-')
        return if (dashIndex > 0) cameraId.substring(0, dashIndex) else cameraId
    }

    private fun extractSerialNumber(cameraId: String): String {
        val dashIndex = cameraId.lastIndexOf('-')
        return if (dashIndex >= 0 && dashIndex + 1 < cameraId.length)
            cameraId.substring(dashIndex + 1)
        else cameraId
    }

    fun getTemperature(): Double {
        return QhyccdJni.getParam(QhyccdJni.CONTROL_CURTEMP)
    }

    fun hasCooling(): Boolean {
        return QhyccdJni.isControlAvailable(QhyccdJni.CONTROL_COOLER) == QhyccdJni.QHYCCD_SUCCESS
    }

    fun setCoolerPwm(pwm: Double) {
        QhyccdJni.setParam(QhyccdJni.CONTROL_COOLER, pwm)
    }
}
