package com.indigo.mobileobservatory.camera.playerone

import com.indigo.mobileobservatory.camera.Camera
import com.indigo.mobileobservatory.camera.CameraInfo
import com.indigo.mobileobservatory.camera.CameraOffsetCapable
import com.indigo.mobileobservatory.camera.CameraUsbBandwidthCapable
import com.indigo.mobileobservatory.camera.CoolingCapable
import com.indigo.mobileobservatory.camera.CoolingInfo
import com.indigo.mobileobservatory.camera.CropInfo
import com.indigo.mobileobservatory.camera.FloatRange
import com.indigo.mobileobservatory.camera.FrameCallback
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.GainCapability
import com.indigo.mobileobservatory.camera.GainPreset
import com.indigo.mobileobservatory.camera.GainValueNormalizer
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.ReadoutMode
import com.indigo.mobileobservatory.camera.ReusableByteArrayPool
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.camera.TempHistoryPoint
import com.indigo.mobileobservatory.util.FileLogger
import com.playeroneastronomy.camera.ConfigValue
import com.playeroneastronomy.camera.GainOffsetPreset
import com.playeroneastronomy.camera.PlayerOneCamera as PoaCamera
import com.playeroneastronomy.camera.PlayerOneCameraSdk
import com.playeroneastronomy.camera.PoaBayerPattern
import com.playeroneastronomy.camera.PoaConfig
import com.playeroneastronomy.camera.PoaException
import com.playeroneastronomy.camera.PoaImageFormat
import com.playeroneastronomy.camera.PoaValueType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class PlayerOneCamera : Camera, CameraOffsetCapable, CoolingCapable, CameraUsbBandwidthCapable {

    companion object {
        private const val TAG = "PlayerOneCam"
        private const val GRAB_TIMEOUT_MS = 5000
        private const val DIRECT_BUF_COUNT = 3
        private const val FRAME_BUFFER_POOL_SIZE = 8
        private const val STREAM_STATS_INTERVAL_NS = 5_000_000_000L
    }

    private var poa: PoaCamera? = null
    private var cameraId: Int = -1
    private var claimed = false
    private var disconnected = false

    private var captureThread: Thread? = null
    private var deliveryThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val acceptRecycledBuffers = AtomicBoolean(true)
    private val bufferPool = ReusableByteArrayPool(FRAME_BUFFER_POOL_SIZE)
    private var directBuffers: Array<ByteBuffer>? = null
    private var frameCallback: FrameCallback? = null

    private class FrameSlot(val buffer: ByteBuffer) {
        var width: Int = 0
        var height: Int = 0
        var expectedSize: Int = 0
        var pixelFormat: PixelFormat = PixelFormat.MONO8
        var frameId: Long = 0
        var timestamp: Long = 0
    }

    private val _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    override val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    override var cameraInfo: CameraInfo? = null; private set
    override var exposureRange: FloatRange = FloatRange(32f, 2_000_000_000f, 10_000f); private set
    override var gainRange: FloatRange = FloatRange(0f, 50f, 0f); private set
    override var gainCapability = GainCapability(min = 0f, max = 500f, defaultValue = 0f); private set
    override var currentExposureUs: Float = 10_000f; private set
    override var currentGain: Float = 0f; private set
    override var offsetSupported: Boolean = false; private set
    override val offsetLabel: String = "Offset"
    override var offsetRange: FloatRange = FloatRange(0f, 0f, 0f); private set
    override val offsetStep: Float = 1f
    override var currentOffset: Float = 0f; private set
    override var usbBandwidthRange: IntRange? = null; private set
    override var currentUsbBandwidth: Int? = null; private set
    override var currentPixelFormat: PixelFormat = PixelFormat.MONO8; private set
    override var supportedPixelFormats: List<PixelFormat> = listOf(PixelFormat.MONO8); private set
    override var currentReadoutMode: ReadoutMode = ReadoutMode.NORMAL; private set
    override var supportedReadoutModes: List<ReadoutMode> = listOf(ReadoutMode.NORMAL); private set
    override var usbBandwidthRange: IntRange? = null; private set
    override var currentUsbBandwidth: Int? = null; private set
    override var currentRoi: Roi = Roi(0, 0, 1920, 1080); private set
    @Volatile override var cropInfo: CropInfo = CropInfo(0, 0, 1920, 1080); private set
    override var hwExposureMaxUs: Float = 2_000_000_000f; private set
    override var roiMinWidth = PoaMapping.ROI_MIN; private set
    override var roiMinHeight = PoaMapping.ROI_MIN; private set
    @Volatile override var longExposureEnabled: Boolean = false

    private var sensorW = 0
    private var sensorH = 0
    private var bayer = PoaBayerPattern.MONO
    private var currentPoaFormat = PoaImageFormat.RAW8
    private var currentBin = 1
    private var supportedBins: List<Int> = listOf(1)
    private var sensorModeIndices: Map<ReadoutMode, Int> = emptyMap()
    var gainOffsetPreset: GainOffsetPreset? = null; private set

    // Cooling
    private val _coolingInfo = MutableStateFlow<CoolingInfo?>(null)
    override val coolingInfo: StateFlow<CoolingInfo?> = _coolingInfo.asStateFlow()
    private val _coolerOn = MutableStateFlow(false)
    override val coolerOn: StateFlow<Boolean> = _coolerOn.asStateFlow()
    private val _targetTempTenths = MutableStateFlow(0)
    override val targetTempTenths: StateFlow<Int> = _targetTempTenths.asStateFlow()
    private val _sensorTempTenths = MutableStateFlow(0)
    override val sensorTempTenths: StateFlow<Int> = _sensorTempTenths.asStateFlow()
    private val _tecVoltageTenths = MutableStateFlow(0)
    override val tecVoltageTenths: StateFlow<Int> = _tecVoltageTenths.asStateFlow()
    private val _coolingPowerPct = MutableStateFlow(0f)
    override val coolingPowerPct: StateFlow<Float> = _coolingPowerPct.asStateFlow()
    private val _tempHistory = MutableStateFlow<List<TempHistoryPoint>>(emptyList())
    override val tempHistory: StateFlow<List<TempHistoryPoint>> = _tempHistory.asStateFlow()
    private val _rampStatus = MutableStateFlow("")
    override val rampStatus: StateFlow<String> = _rampStatus.asStateFlow()

    private var tempPollThread: Thread? = null
    private val tempPollRunning = AtomicBoolean(false)
    @Volatile private var rampThread: Thread? = null
    private val rampRunning = AtomicBoolean(false)
    private var targetIsFloatCelsius = true
    private var coolerWritable = false

    fun open(cameraId: Int): Boolean {
        try {
            if (!PlayerOneSdkHost.claim(cameraId)) {
                FileLogger.e(TAG, "Camera $cameraId already claimed by another session")
                return false
            }
            claimed = true
            this.cameraId = cameraId

            val cam = PlayerOneCameraSdk.getCameraById(cameraId)
            cam.open()
            cam.initialize()
            poa = cam

            val props = cam.properties
            sensorW = props.maxWidth
            sensorH = props.maxHeight
            bayer = props.bayerPattern
            supportedBins = props.bins.ifEmpty { listOf(1) }
            currentBin = 1

            cameraInfo = CameraInfo(
                name = props.cameraModelName ?: "Player One Camera",
                serialNumber = props.stableIdentity(),
                sensorWidth = sensorW,
                sensorHeight = sensorH,
                maxBitDepth = props.bitDepth,
                sensorName = props.sensorModelName,
                pixelSizeUm = props.pixelSizeMicrometers.toFloat().takeIf { it > 0f }
            )

            readExposureRange(cam)
            readGainRange(cam)
            readOffsetRange(cam)
            configureFormats(cam, props.imageFormats)
            configureUsb3Transport(cam, props.isUsb3Speed)
            logTransportSettings(cam, props.isUsb3Speed)
            readSensorModes(cam)
            readGainOffsetPresets(cam)

            val maxW = (sensorW / currentBin / PoaMapping.ROI_WIDTH_ALIGN) * PoaMapping.ROI_WIDTH_ALIGN
            val maxH = (sensorH / currentBin / PoaMapping.ROI_HEIGHT_ALIGN) * PoaMapping.ROI_HEIGHT_ALIGN
            try {
                cam.setImageBin(currentBin)
                cam.setImageStartPosition(0, 0)
                cam.setImageSize(maxW.coerceAtLeast(PoaMapping.ROI_MIN), maxH.coerceAtLeast(PoaMapping.ROI_MIN))
            } catch (e: PoaException) {
                FileLogger.w(TAG, "Initial ROI setup: ${e.error}")
            }
            syncRoiFromCamera(cam)

            if (props.hasCooler()) {
                initCooling(cam)
            }

            _isOpen.value = true
            acceptRecycledBuffers.set(true)
            FileLogger.i(
                TAG,
                "Opened ${cameraInfo?.name} SN=${cameraInfo?.serialNumber} " +
                    "${sensorW}x${sensorH} bit=${props.bitDepth} cooler=${props.hasCooler()}"
            )
            return true
        } catch (e: PoaException) {
            FileLogger.e(TAG, "open failed ${e.error}: ${e.message}", e)
            cleanupFailedOpen()
            return false
        } catch (e: Throwable) {
            FileLogger.e(TAG, "open failed: ${e.message}", e)
            cleanupFailedOpen()
            return false
        }
    }

    private fun cleanupFailedOpen() {
        try { poa?.close() } catch (_: Throwable) {}
        poa = null
        if (claimed) {
            PlayerOneSdkHost.release(cameraId)
            claimed = false
        }
        _isOpen.value = false
    }

    /**
     * USB gone — skip further native I/O; the SDK already tore down its registry.
     * Runs on the USB detach broadcast (main thread), so it only signals the worker
     * threads and never joins them.
     */
    fun markDisconnected() {
        disconnected = true
        running.set(false)
        tempPollRunning.set(false)
        rampRunning.set(false)
        val capture = captureThread
        captureThread = null
        capture?.interrupt()
        val delivery = deliveryThread
        deliveryThread = null
        delivery?.interrupt()
        tempPollThread?.interrupt()
        tempPollThread = null
        rampThread?.interrupt()
        rampThread = null
        _rampStatus.value = ""
        _isCapturing.value = false
        _isOpen.value = false
        frameCallback = null
        poa = null
        acceptRecycledBuffers.set(false)
        bufferPool.clear()
        directBuffers = null
        if (claimed) {
            PlayerOneSdkHost.release(cameraId)
            claimed = false
        }
        FileLogger.i(TAG, "markDisconnected cameraId=$cameraId")
    }

    override fun close() {
        if (disconnected) {
            _isOpen.value = false
            return
        }
        stopCapture()
        stopTempPolling()
        stopRamp()
        try {
            poa?.close()
        } catch (e: PoaException) {
            FileLogger.w(TAG, "close: ${e.error}")
        } catch (e: Throwable) {
            FileLogger.w(TAG, "close: ${e.message}")
        }
        poa = null
        _isOpen.value = false
        cameraInfo = null
        acceptRecycledBuffers.set(false)
        bufferPool.clear()
        directBuffers = null
        if (claimed) {
            PlayerOneSdkHost.release(cameraId)
            claimed = false
        }
        FileLogger.i(TAG, "Camera closed")
    }

    override fun startCapture(callback: FrameCallback) {
        val cam = poa ?: return
        if (!_isOpen.value || disconnected) return
        frameCallback = callback

        try {
            cam.startExposure(false)
        } catch (e: PoaException) {
            FileLogger.e(TAG, "startExposure failed ${e.error}: ${e.message}", e)
            frameCallback = null
            return
        }

        ensureDirectBuffers()
        running.set(true)
        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            captureLoop()
        }, "PlayerOneCapture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        _isCapturing.value = true
        FileLogger.i(TAG, "Capture started")
    }

    override fun stopCapture() {
        if (!_isCapturing.value && !running.get()) return
        running.set(false)
        captureThread?.interrupt()
        captureThread?.join(3000)
        captureThread = null
        deliveryThread?.interrupt()
        deliveryThread?.join(3000)
        deliveryThread = null

        if (!disconnected) {
            try {
                poa?.stopExposure()
            } catch (e: PoaException) {
                FileLogger.w(TAG, "stopExposure: ${e.error}")
            } catch (_: Throwable) {}
        }
        _isCapturing.value = false
        frameCallback = null
        FileLogger.i(TAG, "Capture stopped")
    }

    override fun setExposureTime(us: Float) {
        val cam = poa ?: return
        if (disconnected) return
        val clamped = us.coerceIn(exposureRange.min, hwExposureMaxUs)
        currentExposureUs = clamped
        try {
            cam.setConfig(PoaConfig.EXPOSURE_MICROSECONDS, ConfigValue.ofInteger(clamped.toLong()), false)
        } catch (e: PoaException) {
            FileLogger.w(TAG, "setExposure ${e.error}: ${e.message}")
        }
    }

    override fun setGain(value: Float) {
        val cam = poa ?: return
        if (disconnected) return
        val gain = GainValueNormalizer.normalize(gainCapability, value).roundToInt()
        try {
            cam.setConfig(PoaConfig.GAIN, ConfigValue.ofInteger(gain.toLong()), false)
            currentGain = GainValueNormalizer.normalize(
                gainCapability,
                cam.getConfig(PoaConfig.GAIN).value.asInteger().toFloat()
            )
        } catch (e: PoaException) {
            FileLogger.w(TAG, "setGain ${e.error}: ${e.message}")
        }
    }

    override fun gainDbEquivalent(value: Float): Float? =
        GainValueNormalizer.normalize(gainCapability, value) * 0.1f

    override fun adjustGainForExposure(stops: Float): Float =
        GainValueNormalizer.normalize(gainCapability, currentGain + stops * 60.206f)

    override fun setOffset(value: Float) {
        val cam = poa ?: return
        if (disconnected || !offsetSupported) return
        val clamped = value.roundToInt().coerceIn(offsetRange.min.toInt(), offsetRange.max.toInt())
        try {
            cam.setConfig(PoaConfig.OFFSET, ConfigValue.ofInteger(clamped.toLong()), false)
            currentOffset = clamped.toFloat()
        } catch (e: PoaException) {
            FileLogger.w(TAG, "setOffset ${e.error}: ${e.message}")
        }
    }

    override fun setPixelFormat(format: PixelFormat) {
        if (format == currentPixelFormat) return
        val cam = poa ?: return
        if (disconnected) return
        if (format !in supportedPixelFormats) {
            FileLogger.w(TAG, "Unsupported pixel format ${format.name}")
            return
        }
        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()

        val poaFmt = when {
            format == PixelFormat.MONO8 && PoaImageFormat.MONO8 in (poa?.properties?.imageFormats ?: emptyList()) ->
                PoaImageFormat.MONO8
            format.is8bit -> PoaImageFormat.RAW8
            else -> PoaImageFormat.RAW16
        }
        try {
            cam.setImageFormat(poaFmt)
            currentPoaFormat = poaFmt
            currentPixelFormat = format
            directBuffers = null
            FileLogger.i(TAG, "PixelFormat -> ${format.name} ($poaFmt)")
        } catch (e: PoaException) {
            FileLogger.w(TAG, "setImageFormat ${e.error}: ${e.message}")
        }

        if (wasCapturing && cb != null) startCapture(cb)
    }

    override fun setReadoutMode(mode: ReadoutMode) {
        val cam = poa ?: return
        if (disconnected) return
        if (mode == currentReadoutMode) return
        val index = sensorModeIndices[mode] ?: run {
            if (mode == ReadoutMode.NORMAL) return
            FileLogger.w(TAG, "No sensor mode for $mode")
            return
        }
        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()
        val previousExposure = currentExposureUs
        val previousGain = currentGain
        val previousOffset = currentOffset
        val previousFormat = currentPoaFormat
        try {
            cam.setSensorMode(index)
            currentReadoutMode = mode
            try {
                cam.setImageFormat(previousFormat)
            } catch (e: PoaException) {
                FileLogger.w(TAG, "restore image format ${e.error}: ${e.message}")
            }
            setExposureTime(previousExposure)
            setGain(previousGain)
            if (offsetSupported) setOffset(previousOffset)
            readCurrentImagingSettings(cam)
            directBuffers = null
            FileLogger.i(
                TAG,
                "SensorMode -> $mode (index=$index), restored exposure=${currentExposureUs.toInt()}us " +
                    "gain=$currentGain native offset=$currentOffset format=$currentPoaFormat"
            )
        } catch (e: PoaException) {
            FileLogger.w(TAG, "setSensorMode ${e.error}: ${e.message}")
        }
        if (wasCapturing && cb != null) startCapture(cb)
    }

    fun setBin(bin: Int) {
        val cam = poa ?: return
        if (disconnected) return
        val b = if (bin in supportedBins) bin else supportedBins.firstOrNull() ?: 1
        if (b == currentBin) return
        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()
        try {
            cam.setImageBin(b)
            currentBin = b
            syncRoiFromCamera(cam)
            FileLogger.i(TAG, "Bin -> $b, imageSize=${currentRoi.width}x${currentRoi.height}")
        } catch (e: PoaException) {
            FileLogger.w(TAG, "setImageBin ${e.error}: ${e.message}")
        }
        if (wasCapturing && cb != null) startCapture(cb)
    }

    fun getBin(): Int = currentBin
    fun getSupportedBins(): List<Int> = supportedBins

    override fun setRoi(roi: Roi) {
        val cam = poa ?: return
        if (disconnected) return
        val maxW = sensorW / currentBin
        val maxH = sensorH / currentBin
        val aligned = PoaMapping.alignRoi(roi, maxW, maxH)

        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()

        try {
            cam.setImageStartPosition(aligned.x, aligned.y)
            cam.setImageSize(aligned.width, aligned.height)
            syncRoiFromCamera(cam)
            directBuffers = null
            FileLogger.i(TAG, "ROI set: ${currentRoi.width}x${currentRoi.height}@(${currentRoi.x},${currentRoi.y})")
        } catch (e: PoaException) {
            FileLogger.w(TAG, "setRoi ${e.error}: ${e.message}")
        }

        if (wasCapturing && cb != null) startCapture(cb)
    }

    override fun resetRoi() {
        val maxW = sensorW / currentBin
        val maxH = sensorH / currentBin
        setRoi(Roi(0, 0, maxW, maxH))
    }

    override fun recycleBuffer(buf: ByteArray) {
        if (acceptRecycledBuffers.get()) bufferPool.release(buf)
    }

    // --- CoolingCapable ---

    override fun setCoolerOn(on: Boolean) {
        val cam = poa ?: return
        _coolingInfo.value ?: return
        if (!coolerWritable) return
        try {
            cam.setConfig(PoaConfig.COOLER, ConfigValue.ofBoolean(on), false)
            _coolerOn.value = on
            FileLogger.i(TAG, "Cooler ${if (on) "ON" else "OFF"}")
        } catch (e: PoaException) {
            FileLogger.w(TAG, "setCoolerOn ${e.error}: ${e.message}")
        }
    }

    override fun setTargetTemperature(tenthsDegC: Int) {
        val cam = poa ?: return
        val ci = _coolingInfo.value ?: return
        if (!ci.canSetTarget) return
        val clamped = tenthsDegC.coerceIn(ci.targetMinTenths, ci.targetMaxTenths)
        try {
            writeTargetTemperature(cam, clamped)
            _targetTempTenths.value = clamped
            FileLogger.i(TAG, "Target temp ${clamped / 10.0}C")
        } catch (e: PoaException) {
            FileLogger.w(TAG, "setTargetTemperature ${e.error}: ${e.message}")
        }
    }

    override fun startCoolDown(targetTenths: Int, durationMinutes: Int) {
        stopRamp()
        val ci = _coolingInfo.value ?: return
        if (!ci.canSetTarget) return
        if (!_coolerOn.value) setCoolerOn(true)
        val clamped = targetTenths.coerceIn(ci.targetMinTenths, ci.targetMaxTenths)
        if (durationMinutes <= 0) {
            setTargetTemperature(clamped)
            return
        }
        val startTemp = _sensorTempTenths.value
        val totalSteps = (durationMinutes * 60 / 5).coerceAtLeast(1)
        val stepSize = (clamped - startTemp).toFloat() / totalSteps
        rampRunning.set(true)
        rampThread = Thread({
            for (step in 1..totalSteps) {
                if (!rampRunning.get()) break
                val intermediate = (startTemp + stepSize * step).toInt()
                    .coerceIn(ci.targetMinTenths, ci.targetMaxTenths)
                setTargetTemperature(intermediate)
                val remaining = durationMinutes * 60 - step * 5
                _rampStatus.value =
                    "Cooling: ${"%.1f".format(intermediate / 10.0)}°C (${remaining / 60}m${remaining % 60}s)"
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (rampRunning.get()) {
                setTargetTemperature(clamped)
                _rampStatus.value = ""
            }
            rampRunning.set(false)
        }, "POA-TEC-Ramp").apply { isDaemon = true; start() }
    }

    override fun startWarmUp(durationMinutes: Int) {
        stopRamp()
        val ci = _coolingInfo.value ?: return
        if (!ci.canSetTarget) return
        if (durationMinutes <= 0) {
            setCoolerOn(false)
            return
        }
        val startTemp = _sensorTempTenths.value
        val ambientTarget = ci.targetMaxTenths.coerceAtMost(200)
        val totalSteps = (durationMinutes * 60 / 5).coerceAtLeast(1)
        val stepSize = (ambientTarget - startTemp).toFloat() / totalSteps
        rampRunning.set(true)
        rampThread = Thread({
            for (step in 1..totalSteps) {
                if (!rampRunning.get()) break
                val intermediate = (startTemp + stepSize * step).toInt()
                    .coerceIn(ci.targetMinTenths, ci.targetMaxTenths)
                setTargetTemperature(intermediate)
                val remaining = durationMinutes * 60 - step * 5
                _rampStatus.value =
                    "Warming: ${"%.1f".format(intermediate / 10.0)}°C (${remaining / 60}m${remaining % 60}s)"
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (rampRunning.get()) {
                setCoolerOn(false)
                _rampStatus.value = ""
            }
            rampRunning.set(false)
        }, "POA-TEC-Warmup").apply { isDaemon = true; start() }
    }

    override fun stopRamp() {
        rampRunning.set(false)
        rampThread?.interrupt()
        rampThread?.join(3000)
        rampThread = null
        _rampStatus.value = ""
    }

    // --- internals ---

    private fun captureLoop() {
        val cam = poa ?: return
        val buffers = directBuffers ?: return
        val freeSlots = ArrayBlockingQueue<FrameSlot>(buffers.size)
        val pendingFrames = ArrayBlockingQueue<FrameSlot>(buffers.size)
        buffers.forEach { buffer ->
            buffer.clear()
            freeSlots.offer(FrameSlot(buffer))
        }

        val delivery = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            deliveryLoop(pendingFrames, freeSlots)
        }, "PlayerOneDelivery").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        deliveryThread = delivery

        var frameSeq = 0L
        var statsFrameCount = 0L
        var statsStartedNs = System.nanoTime()
        while (running.get() && !disconnected) {
            var slot: FrameSlot? = null
            try {
                val w = currentRoi.width
                val h = currentRoi.height
                val bpp = currentPixelFormat.bytesPerPixel
                val expectedSize = w * h * bpp
                slot = freeSlots.take()
                slot.buffer.clear()
                cam.getImageData(slot.buffer, GRAB_TIMEOUT_MS)

                frameSeq++
                if (frameSeq == 1L) {
                    FileLogger.i(TAG, "First frame: ${w}x${h} fmt=${currentPixelFormat.name} bpp=$bpp")
                }
                val completedSlot = slot
                completedSlot.width = w
                completedSlot.height = h
                completedSlot.expectedSize = expectedSize
                completedSlot.pixelFormat = currentPixelFormat
                completedSlot.frameId = frameSeq
                completedSlot.timestamp = System.currentTimeMillis()
                pendingFrames.put(completedSlot)
                slot = null
                statsFrameCount++
                val statsNowNs = System.nanoTime()
                val statsElapsedNs = statsNowNs - statsStartedNs
                if (statsElapsedNs >= STREAM_STATS_INTERVAL_NS) {
                    val sourceFps = statsFrameCount * 1_000_000_000.0 / statsElapsedNs
                    val dropped = try {
                        cam.droppedImageCount
                    } catch (_: PoaException) {
                        -1
                    }
                    FileLogger.i(
                        TAG,
                        "Stream sourceFps=${"%.1f".format(java.util.Locale.US, sourceFps)} " +
                            "dropped=$dropped exposureUs=${currentExposureUs.toInt()} " +
                            "roi=${w}x$h format=${currentPixelFormat.name}"
                    )
                    statsFrameCount = 0
                    statsStartedNs = statsNowNs
                }
            } catch (e: PoaException) {
                slot?.let(freeSlots::offer)
                if (e.error == com.playeroneastronomy.camera.PoaError.TIMEOUT) continue
                FileLogger.w(TAG, "capture ${e.error}: ${e.message}")
                if (!running.get() || disconnected) break
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            } catch (e: InterruptedException) {
                slot?.let(freeSlots::offer)
                break
            } catch (e: Exception) {
                slot?.let(freeSlots::offer)
                FileLogger.e(TAG, "Capture error: ${e.message}")
                if (!running.get() || disconnected) break
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
        }

        try {
            delivery.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (delivery.isAlive) {
            delivery.interrupt()
            try {
                delivery.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (deliveryThread === delivery) deliveryThread = null
        FileLogger.i(TAG, "Capture loop ended, $frameSeq frames")
    }

    private fun deliveryLoop(
        pendingFrames: ArrayBlockingQueue<FrameSlot>,
        freeSlots: ArrayBlockingQueue<FrameSlot>
    ) {
        while (running.get() || pendingFrames.isNotEmpty()) {
            val pending = try {
                pendingFrames.poll(50, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue

            var outData: ByteArray? = null
            var handedOff = false
            try {
                pending.buffer.position(0)
                outData = bufferPool.acquire(pending.expectedSize)
                pending.buffer.get(
                    outData,
                    0,
                    pending.expectedSize.coerceAtMost(outData.size)
                )

                val callback = frameCallback
                if (callback != null) {
                    callback.onFrame(
                        FrameData(
                            data = outData,
                            width = pending.width,
                            height = pending.height,
                            pixelFormat = pending.pixelFormat,
                            frameId = pending.frameId,
                            timestamp = pending.timestamp
                        )
                    )
                    handedOff = true
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Frame delivery error: ${e.message}")
            } finally {
                if (!handedOff) outData?.let(::recycleBuffer)
                pending.buffer.clear()
                freeSlots.offer(pending)
            }
        }
    }

    private fun ensureDirectBuffers() {
        val w = currentRoi.width.coerceAtLeast(PoaMapping.ROI_MIN)
        val h = currentRoi.height.coerceAtLeast(PoaMapping.ROI_MIN)
        val need = w * h * currentPixelFormat.bytesPerPixel.coerceAtLeast(1)
        val existing = directBuffers
        if (existing != null && existing.isNotEmpty() && existing[0].capacity() >= need) return
        directBuffers = Array(DIRECT_BUF_COUNT) { ByteBuffer.allocateDirect(need) }
    }

    private fun syncRoiFromCamera(cam: PoaCamera) {
        try {
            val size = cam.imageSize
            val pos = cam.imageStartPosition
            currentRoi = Roi(pos.x, pos.y, size.width, size.height)
            cropInfo = CropInfo(pos.x, pos.y, size.width, size.height)
            currentBin = cam.imageBin
        } catch (e: PoaException) {
            FileLogger.w(TAG, "syncRoi ${e.error}")
        }
    }

    private fun readExposureRange(cam: PoaCamera) {
        try {
            val attrs = cam.getConfigAttributes(PoaConfig.EXPOSURE_MICROSECONDS)
            val min = attrs.minimum.asInteger().toFloat().coerceAtLeast(1f)
            val max = attrs.maximum.asInteger().toFloat()
            val def = attrs.defaultValue.asInteger().toFloat().coerceIn(min, max)
            hwExposureMaxUs = max
            exposureRange = FloatRange(min, max, def)
            currentExposureUs = def
            FileLogger.i(TAG, "Exposure ${min.toInt()}-${max.toInt()} us")
        } catch (e: PoaException) {
            FileLogger.w(TAG, "EXPOSURE attrs ${e.error}")
        }
    }

    private fun readGainRange(cam: PoaCamera) {
        try {
            val attrs = cam.getConfigAttributes(PoaConfig.GAIN)
            val minGain = attrs.minimum.asInteger().toInt().toFloat()
            val maxGain = attrs.maximum.asInteger().toInt().toFloat()
            val defaultGain = attrs.defaultValue.asInteger().toInt().toFloat().coerceIn(minGain, maxGain)
            val currentGain = cam.getConfig(PoaConfig.GAIN).value.asInteger().toFloat().coerceIn(minGain, maxGain)
            gainRange = FloatRange(minGain, maxGain, currentGain)
            gainCapability = GainCapability(min = minGain, max = maxGain, step = 1f, defaultValue = defaultGain)
            this.currentGain = GainValueNormalizer.normalize(gainCapability, currentGain)
            FileLogger.i(TAG, "Gain $minGain-$maxGain native (current=${this.currentGain}, 1 gain = 0.1 dB)")
        } catch (e: PoaException) {
            FileLogger.w(TAG, "GAIN attrs ${e.error}")
        }
    }

    private fun readOffsetRange(cam: PoaCamera) {
        try {
            val attrs = cam.getConfigAttributes(PoaConfig.OFFSET)
            if (!attrs.isWritable || !attrs.isReadable) return
            val min = attrs.minimum.asInteger().toFloat()
            val max = attrs.maximum.asInteger().toFloat()
            val current = cam.getConfig(PoaConfig.OFFSET).value.asInteger().toFloat().coerceIn(min, max)
            offsetSupported = true
            currentOffset = current
            offsetRange = FloatRange(min, max, current)
            FileLogger.i(TAG, "Offset $min-$max (current=$current)")
        } catch (e: PoaException) {
            FileLogger.w(TAG, "OFFSET attrs ${e.error}")
        }
    }

    private fun configureFormats(cam: PoaCamera, formats: List<PoaImageFormat>) {
        val mapped = formats.mapNotNull { PoaMapping.toPixelFormat(it, bayer) }.distinct()
        supportedPixelFormats = mapped.ifEmpty {
            listOf(if (bayer == PoaBayerPattern.MONO) PixelFormat.MONO8 else PixelFormat.BAYER_RG8)
        }
        val initial = PoaMapping.pickInitialFormat(formats, bayer)
        if (initial != null) {
            try {
                cam.setImageFormat(initial.first)
                currentPoaFormat = initial.first
                currentPixelFormat = initial.second
            } catch (e: PoaException) {
                FileLogger.w(TAG, "setImageFormat initial ${e.error}")
                currentPixelFormat = supportedPixelFormats.first()
            }
        } else {
            currentPixelFormat = supportedPixelFormats.first()
        }
        FileLogger.i(TAG, "Formats: ${supportedPixelFormats.joinToString { it.name }}, current=${currentPixelFormat.name}")
    }

    private fun logTransportSettings(cam: PoaCamera, usb3Speed: Boolean) {
        FileLogger.i(
            TAG,
            "Transport usb3=$usb3Speed " +
                "frameLimit=${readIntegerSetting(cam, PoaConfig.FRAME_LIMIT)} " +
                "usbBandwidth=${readIntegerSetting(cam, PoaConfig.USB_BANDWIDTH_LIMIT)} " +
                "highQuality=${readBooleanSetting(cam, PoaConfig.HIGH_QUALITY_IMAGE)}"
        )
    }

    private fun configureUsb3Transport(cam: PoaCamera, usb3Speed: Boolean) {
        try {
            val attrs = cam.getConfigAttributes(PoaConfig.USB_BANDWIDTH_LIMIT)
            if (!attrs.isWritable || !attrs.isReadable) {
                usbBandwidthRange = null
                currentUsbBandwidth = null
            } else {
                val min = attrs.minimum.asInteger().toInt()
                val max = attrs.maximum.asInteger().toInt()
                if (min <= max) {
                    usbBandwidthRange = min..max
                    currentUsbBandwidth = cam.getConfig(PoaConfig.USB_BANDWIDTH_LIMIT)
                        .value.asInteger().toInt().coerceIn(min, max)
                    if (usb3Speed) setUsbBandwidth(max)
                }
            }
        } catch (e: PoaException) {
            usbBandwidthRange = null
            currentUsbBandwidth = null
            FileLogger.w(TAG, "USB bandwidth configuration failed: ${e.error}")
        }
        configureUnlimitedFrameRate(cam)
    }

    override fun setUsbBandwidth(value: Int): Boolean {
        val cam = poa ?: return false
        val range = usbBandwidthRange ?: return false
        val target = value.coerceIn(range.first, range.last)
        return try {
            cam.setConfig(PoaConfig.USB_BANDWIDTH_LIMIT, ConfigValue.ofInteger(target.toLong()), false)
            currentUsbBandwidth = cam.getConfig(PoaConfig.USB_BANDWIDTH_LIMIT)
                .value.asInteger().toInt().coerceIn(range.first, range.last)
            FileLogger.i(TAG, "USB bandwidth set: requested=$target current=$currentUsbBandwidth")
            true
        } catch (e: PoaException) {
            FileLogger.w(TAG, "Set USB bandwidth failed: ${e.error}")
            false
        }
    }

    private fun configureUnlimitedFrameRate(cam: PoaCamera) {
        try {
            val attrs = cam.getConfigAttributes(PoaConfig.FRAME_LIMIT)
            if (!attrs.isWritable || !attrs.isReadable) return
            cam.setConfig(PoaConfig.FRAME_LIMIT, ConfigValue.ofInteger(0), false)
        } catch (e: PoaException) {
            FileLogger.w(TAG, "Frame limit configuration failed: ${e.error}")
        }
    }

    private fun readIntegerSetting(cam: PoaCamera, config: PoaConfig): String {
        return try {
            val attrs = cam.getConfigAttributes(config)
            val current = cam.getConfig(config)
            "${current.value.asInteger()} auto=${current.isAuto} " +
                "range=${attrs.minimum.asInteger()}..${attrs.maximum.asInteger()} " +
                "default=${attrs.defaultValue.asInteger()} writable=${attrs.isWritable} " +
                "supportsAuto=${attrs.supportsAuto()}"
        } catch (e: PoaException) {
            "unavailable(${e.error})"
        }
    }

    private fun readCurrentImagingSettings(cam: PoaCamera) {
        try {
            currentExposureUs = cam.getConfig(PoaConfig.EXPOSURE_MICROSECONDS)
                .value.asInteger().toFloat().coerceIn(exposureRange.min, hwExposureMaxUs)
        } catch (e: PoaException) {
            FileLogger.w(TAG, "read exposure after sensor mode ${e.error}")
        }
        try {
            currentGain = GainValueNormalizer.normalize(
                gainCapability,
                cam.getConfig(PoaConfig.GAIN).value.asInteger().toFloat()
            )
        } catch (e: PoaException) {
            FileLogger.w(TAG, "read gain after sensor mode ${e.error}")
        }
        if (offsetSupported) {
            try {
                currentOffset = cam.getConfig(PoaConfig.OFFSET).value.asInteger().toFloat()
                    .coerceIn(offsetRange.min, offsetRange.max)
            } catch (e: PoaException) {
                FileLogger.w(TAG, "read offset after sensor mode ${e.error}")
            }
        }
    }

    private fun readBooleanSetting(cam: PoaCamera, config: PoaConfig): String {
        return try {
            val attrs = cam.getConfigAttributes(config)
            val current = cam.getConfig(config)
            "${current.value.asBoolean()} auto=${current.isAuto} " +
                "default=${attrs.defaultValue.asBoolean()} writable=${attrs.isWritable} " +
                "supportsAuto=${attrs.supportsAuto()}"
        } catch (e: PoaException) {
            "unavailable(${e.error})"
        }
    }

    private fun readSensorModes(cam: PoaCamera) {
        try {
            val count = cam.sensorModeCount
            if (count <= 0) {
                supportedReadoutModes = listOf(ReadoutMode.NORMAL)
                currentReadoutMode = ReadoutMode.NORMAL
                return
            }
            val map = linkedMapOf<ReadoutMode, Int>()
            for (i in 0 until count) {
                val info = cam.getSensorModeInfo(i)
                val mode = PoaMapping.mapSensorModeName(info.name)
                if (mode !in map) map[mode] = i
                FileLogger.i(TAG, "SensorMode[$i]=${info.name} -> $mode")
            }
            if (ReadoutMode.NORMAL !in map) {
                map[ReadoutMode.NORMAL] = map.values.firstOrNull() ?: 0
            }
            sensorModeIndices = map
            supportedReadoutModes = map.keys.toList()
            val cur = try { cam.sensorMode } catch (_: Exception) { map[ReadoutMode.NORMAL] ?: 0 }
            currentReadoutMode = map.entries.firstOrNull { it.value == cur }?.key
                ?: ReadoutMode.NORMAL
        } catch (e: PoaException) {
            FileLogger.w(TAG, "sensor modes ${e.error}")
            supportedReadoutModes = listOf(ReadoutMode.NORMAL)
        }
    }

    private fun readGainOffsetPresets(cam: PoaCamera) {
        try {
            val preset = cam.gainsAndOffsets
            gainOffsetPreset = preset
            gainCapability = gainCapability.copy(
                presets = listOf(
                    GainPreset(preset.gainHighestDynamicRange.toFloat(), "HDR"),
                    GainPreset(preset.highConversionGain.toFloat(), "HCG"),
                    GainPreset(preset.unityGain.toFloat(), "Unity"),
                    GainPreset(preset.gainLowestReadNoise.toFloat(), "Lowest noise")
                )
            )
            FileLogger.i(
                TAG,
                "Gain presets: HDR=${preset.gainHighestDynamicRange} HCG=${preset.highConversionGain} " +
                    "unity=${preset.unityGain} LRN=${preset.gainLowestReadNoise}"
            )
        } catch (e: PoaException) {
            FileLogger.w(TAG, "getGainsAndOffsets ${e.error}")
        }
    }

    private fun initCooling(cam: PoaCamera) {
        var canSetTarget = false
        var targetMin = -500
        var targetMax = 400

        coolerWritable = try {
            cam.getConfigAttributes(PoaConfig.COOLER).isWritable
        } catch (e: PoaException) {
            FileLogger.w(TAG, "COOLER attrs ${e.error}")
            false
        }

        try {
            val targetAttrs = cam.getConfigAttributes(PoaConfig.TARGET_TEMPERATURE)
            canSetTarget = targetAttrs.isWritable
            targetIsFloatCelsius = targetAttrs.valueType == PoaValueType.FLOAT
            targetMin = toTenths(targetAttrs.minimum)
            targetMax = toTenths(targetAttrs.maximum)
        } catch (e: PoaException) {
            FileLogger.w(TAG, "TARGET_TEMPERATURE attrs ${e.error}")
        }

        _coolingInfo.value = CoolingInfo(
            hasTec = true,
            canSetTarget = canSetTarget,
            targetMinTenths = targetMin,
            targetMaxTenths = targetMax,
            tecVoltageMaxTenths = 0
        )

        try {
            _coolerOn.value = cam.getConfig(PoaConfig.COOLER).value.asBoolean()
        } catch (_: PoaException) {}

        try {
            _targetTempTenths.value = toTenths(cam.getConfig(PoaConfig.TARGET_TEMPERATURE).value)
        } catch (_: PoaException) {}

        try {
            _sensorTempTenths.value = toTenths(cam.getConfig(PoaConfig.TEMPERATURE).value)
        } catch (_: PoaException) {}

        startTempPolling()
        FileLogger.i(
            TAG,
            "Cooling init: coolerWritable=$coolerWritable canSetTarget=$canSetTarget " +
                "range=[${targetMin / 10.0}..${targetMax / 10.0}]C"
        )
    }

    /** Player One reports temperatures in whole degrees Celsius (int or float); the app uses tenths. */
    private fun toTenths(value: ConfigValue): Int = when (value.type) {
        PoaValueType.FLOAT -> (value.asFloat() * 10).roundToInt()
        else -> value.asInteger().toInt() * 10
    }

    private fun writeTargetTemperature(cam: PoaCamera, tenths: Int) {
        if (targetIsFloatCelsius) {
            cam.setConfig(PoaConfig.TARGET_TEMPERATURE, ConfigValue.ofFloat(tenths / 10.0), false)
        } else {
            cam.setConfig(
                PoaConfig.TARGET_TEMPERATURE,
                ConfigValue.ofInteger((tenths / 10.0).roundToInt().toLong()),
                false
            )
        }
    }

    private fun startTempPolling() {
        if (_coolingInfo.value == null) return
        tempPollRunning.set(true)
        tempPollThread = Thread({
            while (tempPollRunning.get() && !disconnected) {
                try {
                    val cam = poa
                    if (cam != null) {
                        try {
                            _sensorTempTenths.value = toTenths(cam.getConfig(PoaConfig.TEMPERATURE).value)
                        } catch (_: PoaException) {}
                        try {
                            val power = cam.getConfig(PoaConfig.COOLER_POWER)
                            val pct = when (power.value.type) {
                                PoaValueType.FLOAT -> power.value.asFloat().toFloat()
                                else -> power.value.asInteger().toFloat()
                            }
                            _coolingPowerPct.value = pct.coerceIn(0f, 100f)
                        } catch (_: PoaException) {}

                        val history = _tempHistory.value.toMutableList()
                        history += TempHistoryPoint(
                            System.currentTimeMillis(),
                            _sensorTempTenths.value,
                            _coolingPowerPct.value
                        )
                        while (history.size > 180) history.removeAt(0)
                        _tempHistory.value = history
                    }
                } catch (_: Exception) {}
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "POA-TEC-Poll").apply { isDaemon = true; start() }
    }

    private fun stopTempPolling() {
        tempPollRunning.set(false)
        tempPollThread?.interrupt()
        tempPollThread?.join(2000)
        tempPollThread = null
    }
}
