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

class QhyCamera : Camera {

    companion object {
        private const val TAG = "QhyCamera"
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

    private fun openInternal(cameraId: String, singleFrame: Boolean): Boolean {
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
            useSingleFrameMode = singleFrame
            val streamMode = if (singleFrame) 0 else 1
            QhyccdJni.setReadMode(0)
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

            val transferBitRange = QhyccdJni.getParamRange(QhyccdJni.CONTROL_TRANSFERBIT)
            if (transferBitRange != null) {
                val sdkMin = transferBitRange[0].toInt()
                val sdkMax = transferBitRange[1].toInt()
                Log.i(TAG, "TRANSFERBIT range: $sdkMin..$sdkMax, chipInfo bpp=$maxBpp")
                if (sdkMax < maxBpp) {
                    maxBpp = sdkMax
                }
            }

            currentRoi = Roi(0, 0, sensorWidth, sensorHeight)
            cropInfo = CropInfo(0, 0, sensorWidth, sensorHeight)

            QhyccdJni.setBinMode(1, 1)
            QhyccdJni.setResolution(0, 0, sensorWidth, sensorHeight)
            QhyccdJni.setDebayerOnOff(false)

            initPixelFormats()

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

            QhyccdJni.setParam(QhyccdJni.CONTROL_USBTRAFFIC, 0.0)
            val usbTrafficRange = QhyccdJni.getParamRange(QhyccdJni.CONTROL_USBTRAFFIC)
            Log.i(TAG, "USB traffic set to 0 (range: ${usbTrafficRange?.get(0)}..${usbTrafficRange?.get(1)})")
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

    private fun initPixelFormats() {
        val formats = mutableListOf<PixelFormat>()
        val base8 = if (bayerType > 0) bayerPixelFormat(8) else PixelFormat.MONO8

        formats.add(base8)

        // Check transfer bit range from SDK to determine supported bit depths
        val chipBpp = maxBpp
        val transferBitRange = QhyccdJni.getParamRange(QhyccdJni.CONTROL_TRANSFERBIT)
        if (transferBitRange != null) {
            val sdkMax = transferBitRange[1].toInt()
            Log.i(TAG, "TRANSFERBIT range: ${transferBitRange[0]}..${transferBitRange[1]}, chipInfo bpp=$chipBpp")
            maxBpp = sdkMax
        }

        // Add 12-bit format if supported
        if (maxBpp >= 12) {
            val base12 = if (bayerType > 0) bayerPixelFormat(12) else PixelFormat.MONO12
            formats.add(base12)
        }

        // Only add 16-bit if BOTH chip and SDK report 16-bit capability
        if (maxBpp >= 16 && chipBpp >= 16) {
            val base16 = if (bayerType > 0) bayerPixelFormat(16) else PixelFormat.MONO16
            formats.add(base16)
        }

        QhyccdJni.setParam(QhyccdJni.CONTROL_TRANSFERBIT, 8.0)
        currentPixelFormat = base8

        supportedPixelFormats = formats

        // Update maxBpp to reflect the highest supported format
        maxBpp = formats.maxOf { it.nativeBits }
        Log.i(TAG, "Supported formats: ${formats.joinToString { it.name }}, maxBpp=$maxBpp")
    }

    private fun bayerPixelFormat(bits: Int): PixelFormat {
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
        if (bayerType > 0) return bayerPixelFormat(bits)
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

    @Volatile private var modeSwitching = false

    override fun setPixelFormat(format: PixelFormat) {
        try {
            if (format !in supportedPixelFormats) return
            if (format == currentPixelFormat) return
            if (modeSwitching) return

            val wasCapturing = _isCapturing.value
            val cb = frameCallback
            if (wasCapturing) stopCapture()

            val bits = format.nativeBits
            val needSingleFrame = bits > 8
            val modeChanged = needSingleFrame != useSingleFrameMode
            Log.i(TAG, "setPixelFormat: ${format.name} (${bits}bit) needSingleFrame=$needSingleFrame modeChanged=$modeChanged")

            if (modeChanged) {
                modeSwitching = true
                try {
                    val prevRoi = currentRoi
                    val prevExposure = currentExposureUs
                    val prevGain = currentGain
                    val camId = savedCameraId ?: return

                    if (!reopenWithUsbReset(camId, needSingleFrame)) {
                        Log.e(TAG, "reopenWithUsbReset failed")
                        return
                    }

                    QhyccdJni.setResolution(prevRoi.x, prevRoi.y, prevRoi.width, prevRoi.height)
                    currentRoi = prevRoi
                    cropInfo = CropInfo(prevRoi.x, prevRoi.y, prevRoi.width, prevRoi.height)
                    setExposureTime(prevExposure)
                    setGain(prevGain)
                    FileLogger.i(TAG, "Restored: ROI=${prevRoi.width}x${prevRoi.height} exp=${prevExposure.toInt()}us gain=${prevGain}")
                } finally {
                    modeSwitching = false
                }
            } else {
                val ret = QhyccdJni.setParam(QhyccdJni.CONTROL_TRANSFERBIT, bits.toDouble())
                Log.i(TAG, "SetTransferBit($bits) ret=$ret")
            }

            currentPixelFormat = format
            if (wasCapturing && cb != null) startCapture(cb)
        } catch (e: Throwable) {
            Log.e(TAG, "setPixelFormat failed", e)
        }
    }

    private fun reopenWithUsbReset(cameraId: String, singleFrame: Boolean): Boolean {
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
            Log.e(TAG, "reopenWithUsbReset: UsbManager.openDevice returned null")
            return false
        }
        usbConnection = newConn
        for (i in 0 until dev.interfaceCount) {
            newConn.claimInterface(dev.getInterface(i), true)
        }
        val newFd = newConn.fileDescriptor
        usbFd = newFd
        Log.i(TAG, "reopenWithUsbReset: new fd=$newFd")

        QhyccdJni.initResource()
        QhyccdJni.initFirmware(usbVid, usbPid, newFd)
        Thread.sleep(300)

        return openInternal(cameraId, singleFrame)
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
