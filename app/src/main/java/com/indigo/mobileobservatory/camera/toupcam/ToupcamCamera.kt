package com.indigo.mobileobservatory.camera.toupcam

import android.util.Log
import com.indigo.mobileobservatory.camera.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.pow

class ToupcamCamera : Camera, NativeEventCallback, CoolingCapable {

    companion object {
        private const val TAG = "ToupcamCamera"
    }

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private val bufferPool = ConcurrentLinkedQueue<ByteArray>()
    private var frameCallback: FrameCallback? = null
    private val hasNewFrame = AtomicBoolean(false)

    private val _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    override val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    override var cameraInfo: CameraInfo? = null
        private set

    override var exposureRange: FloatRange = FloatRange(100f, 1_000_000f, 10_000f)
        private set

    override var gainRange: FloatRange = FloatRange(0f, 24f, 0f)
        private set

    override var currentExposureUs: Float = 10_000f
        private set

    override var currentGain: Float = 0f
        private set

    override var currentPixelFormat: PixelFormat = PixelFormat.MONO8
        private set

    override var currentRoi: Roi = Roi(0, 0, 1920, 1200)
        private set

    @Volatile override var cropInfo: CropInfo = CropInfo(0, 0, 1920, 1200)
        private set

    override var hwExposureMaxUs: Float = 1_000_000f
        private set

    @Volatile override var longExposureEnabled: Boolean = false

    private var isMono = true
    private var sensorW = 0
    private var sensorH = 0
    private var rawBits = 8
    private var baseBitDepth = 12
    private var modelFlag = 0L

    override var supportedPixelFormats: List<PixelFormat> = listOf(PixelFormat.MONO8)
        private set

    override var currentReadoutMode: ReadoutMode = ReadoutMode.NORMAL
        private set

    override var supportedReadoutModes: List<ReadoutMode> = listOf(ReadoutMode.NORMAL)
        private set

    // Cooling / TEC
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

    // Ramp state
    @Volatile private var rampThread: Thread? = null
    private val rampRunning = AtomicBoolean(false)
    private var tecVoltageMax = 0

    fun open(fd: Int, vendorId: Int, productId: Int, modelName: String): Boolean {
        try {
            if (!ToupcamJni.open(fd, vendorId, productId)) {
                Log.e(TAG, "ToupcamJni.open failed")
                return false
            }

            modelFlag = ToupcamJni.getModelFlag(vendorId, productId)
            isMono = (modelFlag and ToupcamJni.FLAG_MONO) != 0L

            val maxRes = ToupcamJni.getModelMaxResolution(vendorId, productId)
            sensorW = maxRes[0].coerceAtLeast(1)
            sensorH = maxRes[1].coerceAtLeast(1)

            rawBits = ToupcamJni.getMaxBitDepth()
            if (rawBits < 8) rawBits = 8
            baseBitDepth = rawBits

            ToupcamJni.putOption(ToupcamJni.OPTION_RAW, 1)

            supportedPixelFormats = buildSupportedFormats()
            Log.i(TAG, "Supported formats: ${supportedPixelFormats.joinToString { it.name }} rawBits=$rawBits flag=0x${modelFlag.toString(16)}")

            val highBitFormat = when {
                isMono -> when {
                    rawBits >= 16 -> PixelFormat.MONO16
                    rawBits >= 14 -> PixelFormat.MONO14
                    rawBits >= 12 -> PixelFormat.MONO12
                    else -> PixelFormat.MONO10
                }
                else -> when {
                    rawBits >= 16 -> PixelFormat.BAYER_RG16
                    rawBits >= 12 -> PixelFormat.BAYER_RG12
                    else -> PixelFormat.BAYER_RG10
                }
            }

            if (rawBits > 8) {
                ToupcamJni.putOption(ToupcamJni.OPTION_BITDEPTH, 1)
                currentPixelFormat = highBitFormat
            } else {
                ToupcamJni.putOption(ToupcamJni.OPTION_BITDEPTH, 0)
                currentPixelFormat = if (isMono) PixelFormat.MONO8 else PixelFormat.BAYER_RG8
            }

            initReadoutModes()

            // Disable SDK auto-exposure so manual control works
            try { ToupcamJni.putAutoExpoEnable(0) } catch (_: Exception) {}

            // Flush stale frames for lower latency in planetary imaging
            try { ToupcamJni.putOption(ToupcamJni.OPTION_FLUSH, 3) } catch (_: Exception) {}

            val pixelSize = ToupcamJni.getModelPixelSize(vendorId, productId)

            cameraInfo = CameraInfo(
                name = modelName,
                serialNumber = "TC-${vendorId.toString(16)}-${productId.toString(16)}",
                sensorWidth = sensorW,
                sensorHeight = sensorH,
                maxBitDepth = rawBits,
                sensorName = CameraInfo.lookupSensor(modelName)
            )

            readExposureRange()
            readGainRange()
            try { initCooling() } catch (e: Exception) {
                Log.w(TAG, "initCooling failed: ${e.message}")
            }

            currentRoi = Roi(0, 0, sensorW, sensorH)
            cropInfo = CropInfo(0, 0, sensorW, sensorH)
            _isOpen.value = true
            Log.i(TAG, "Opened: $modelName ${sensorW}x${sensorH} ${rawBits}bit mono=$isMono px=${pixelSize}um flag=0x${modelFlag.toString(16)}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "open failed: ${e.message}", e)
            return false
        }
    }

    private fun buildSupportedFormats(): List<PixelFormat> {
        val formats = mutableListOf<PixelFormat>()
        Log.i(TAG, "buildSupportedFormats: rawBits=$rawBits isMono=$isMono flag=0x${modelFlag.toString(16)}" +
            " RAW10=${(modelFlag and ToupcamJni.FLAG_RAW10) != 0L}" +
            " RAW12=${(modelFlag and ToupcamJni.FLAG_RAW12) != 0L}" +
            " RAW14=${(modelFlag and ToupcamJni.FLAG_RAW14) != 0L}" +
            " RAW16=${(modelFlag and ToupcamJni.FLAG_RAW16) != 0L}")
        if (isMono) {
            formats.add(PixelFormat.MONO8)
            val highBitFormat = when {
                rawBits >= 16 -> PixelFormat.MONO16
                rawBits >= 14 -> PixelFormat.MONO14
                rawBits >= 12 -> PixelFormat.MONO12
                rawBits >= 10 -> PixelFormat.MONO10
                else -> null
            }
            if (highBitFormat != null) formats.add(highBitFormat)
        } else {
            formats.add(PixelFormat.BAYER_RG8)
            val highBitFormat = when {
                rawBits >= 16 -> PixelFormat.BAYER_RG16
                rawBits >= 12 -> PixelFormat.BAYER_RG12
                rawBits >= 10 -> PixelFormat.BAYER_RG10
                else -> null
            }
            if (highBitFormat != null) formats.add(highBitFormat)
        }
        return formats
    }

    override fun close() {
        try { stopRamp() } catch (_: Exception) {}
        try { stopTempPolling() } catch (_: Exception) {}
        stopCapture()
        try {
            if (_coolerOn.value) {
                ToupcamJni.putOption(ToupcamJni.OPTION_TEC, 0)
            }
        } catch (_: Exception) {}
        ToupcamJni.close()
        _isOpen.value = false
        _coolingInfo.value = null
        _coolerOn.value = false
        _tempHistory.value = emptyList()
        cameraInfo = null
        Log.i(TAG, "Camera closed")
    }

    override fun startCapture(callback: FrameCallback) {
        if (!_isOpen.value) return
        frameCallback = callback

        if (!ToupcamJni.startPull(this)) {
            Log.e(TAG, "startPull failed")
            return
        }

        running.set(true)
        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            captureLoop()
        }, "ToupcamCapture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        _isCapturing.value = true
        Log.i(TAG, "Capture started (${currentRoi.width}x${currentRoi.height})")
    }

    override fun stopCapture() {
        if (!_isCapturing.value) return
        running.set(false)
        captureThread?.join(2000)
        captureThread = null
        ToupcamJni.stop()
        _isCapturing.value = false
        frameCallback = null
        Log.i(TAG, "Capture stopped")
    }

    override fun setExposureTime(us: Float) {
        val clamped = us.coerceIn(exposureRange.min, exposureRange.max)
        if (ToupcamJni.putExpoTime(clamped.toInt())) {
            currentExposureUs = clamped
        }
    }

    override fun setGain(db: Float) {
        val clamped = db.coerceIn(gainRange.min, gainRange.max)
        val pct = (10.0.pow(clamped / 20.0) * 100.0).toInt().coerceAtLeast(100)
        if (ToupcamJni.putExpoAGain(pct)) {
            currentGain = clamped
        }
    }

    override fun setPixelFormat(format: PixelFormat) {
        if (format == currentPixelFormat) return

        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()

        try { Thread.sleep(50) } catch (_: InterruptedException) {}

        val bitDepth = if (format.is8bit) 0 else 1
        ToupcamJni.putOption(ToupcamJni.OPTION_BITDEPTH, bitDepth)

        val pixFmtVal = when (format.nativeBits) {
            8 -> ToupcamJni.PIXELFORMAT_RAW8
            10 -> ToupcamJni.PIXELFORMAT_RAW10
            12 -> ToupcamJni.PIXELFORMAT_RAW12
            14 -> ToupcamJni.PIXELFORMAT_RAW14
            16 -> ToupcamJni.PIXELFORMAT_RAW16
            else -> ToupcamJni.PIXELFORMAT_RAW8
        }
        val ok = ToupcamJni.putOption(ToupcamJni.OPTION_PIXEL_FORMAT, pixFmtVal)
        Log.i(TAG, "PixelFormat set to ${format.name} (BITDEPTH=$bitDepth, PIXEL_FORMAT=$pixFmtVal, ok=$ok)")

        currentPixelFormat = format

        try { Thread.sleep(50) } catch (_: InterruptedException) {}

        if (wasCapturing && cb != null) startCapture(cb)
    }

    private fun initReadoutModes() {
        val hasCg = (modelFlag and ToupcamJni.FLAG_CG) != 0L
        val hasCgHdr = (modelFlag and ToupcamJni.FLAG_CGHDR) != 0L
        val hasLowNoise = (modelFlag and ToupcamJni.FLAG_LOW_NOISE) != 0L

        val modes = mutableListOf<ReadoutMode>()
        if (hasCg || hasCgHdr) {
            modes.add(ReadoutMode.LCG)
            modes.add(ReadoutMode.HCG)
            if (hasCgHdr) modes.add(ReadoutMode.HDR)
        }
        if (hasLowNoise) modes.add(ReadoutMode.LOW_NOISE)

        if (modes.isEmpty()) {
            supportedReadoutModes = listOf(ReadoutMode.NORMAL)
            currentReadoutMode = ReadoutMode.NORMAL
        } else {
            supportedReadoutModes = modes
            try {
                val cgVal = ToupcamJni.getOption(ToupcamJni.OPTION_CG)
                currentReadoutMode = when (cgVal) {
                    0 -> ReadoutMode.LCG
                    1 -> ReadoutMode.HCG
                    2 -> ReadoutMode.HDR
                    else -> modes.first()
                }
            } catch (e: Exception) {
                Log.w(TAG, "initReadoutModes: failed to read CG: ${e.message}")
                currentReadoutMode = modes.first()
            }
        }
        Log.i(TAG, "Readout modes: ${supportedReadoutModes.joinToString { it.name }}, current=${currentReadoutMode.name}" +
            " (CG=$hasCg CGHDR=$hasCgHdr LowNoise=$hasLowNoise)")
    }

    override fun setReadoutMode(mode: ReadoutMode) {
        if (mode == currentReadoutMode) return
        if (mode !in supportedReadoutModes) return

        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()

        try { Thread.sleep(50) } catch (_: InterruptedException) {}

        when (mode) {
            ReadoutMode.LOW_NOISE -> {
                ToupcamJni.putOption(ToupcamJni.OPTION_LOW_NOISE, 1)
                Log.i(TAG, "Readout mode -> LOW_NOISE")
            }
            ReadoutMode.LCG, ReadoutMode.HCG, ReadoutMode.HDR -> {
                if (currentReadoutMode == ReadoutMode.LOW_NOISE) {
                    ToupcamJni.putOption(ToupcamJni.OPTION_LOW_NOISE, 0)
                }

                val cgVal = when (mode) {
                    ReadoutMode.LCG -> 0
                    ReadoutMode.HCG -> 1
                    ReadoutMode.HDR -> 2
                    else -> 0
                }
                val ok = ToupcamJni.putOption(ToupcamJni.OPTION_CG, cgVal)
                Log.i(TAG, "Readout mode -> ${mode.name} (CG=$cgVal, ok=$ok)")

                val isHdr = mode == ReadoutMode.HDR && (modelFlag and ToupcamJni.FLAG_CGHDR) != 0L
                val wasHdr = currentReadoutMode == ReadoutMode.HDR
                if (isHdr && !wasHdr) {
                    ToupcamJni.putOption(ToupcamJni.OPTION_RAW, 0)
                    ToupcamJni.putOption(ToupcamJni.OPTION_RGB, 1)
                    ToupcamJni.putOption(ToupcamJni.OPTION_BITDEPTH, 1)
                    Log.i(TAG, "Switched to RGB48 mode for HDR")
                } else if (wasHdr && !isHdr) {
                    ToupcamJni.putOption(ToupcamJni.OPTION_RAW, 1)
                    ToupcamJni.putOption(ToupcamJni.OPTION_BITDEPTH, 1)
                    Log.i(TAG, "Restored RAW mode from HDR")
                }
            }
            ReadoutMode.NORMAL -> {
                if (currentReadoutMode == ReadoutMode.LOW_NOISE) {
                    ToupcamJni.putOption(ToupcamJni.OPTION_LOW_NOISE, 0)
                }
            }
        }

        currentReadoutMode = mode

        try { Thread.sleep(50) } catch (_: InterruptedException) {}

        refreshBitDepthAndFormat()

        if (wasCapturing && cb != null) startCapture(cb)
    }

    private fun refreshBitDepthAndFormat() {
        val isHdr = currentReadoutMode == ReadoutMode.HDR && (modelFlag and ToupcamJni.FLAG_CGHDR) != 0L

        if (isHdr) {
            rawBits = 16
            currentPixelFormat = PixelFormat.RGB48
            supportedPixelFormats = listOf(PixelFormat.RGB48)
            Log.i(TAG, "HDR mode: RGB48 16-bit")
        } else {
            rawBits = baseBitDepth
            supportedPixelFormats = buildSupportedFormats()

            val highBitFormat = when {
                isMono -> when {
                    rawBits >= 16 -> PixelFormat.MONO16
                    rawBits >= 14 -> PixelFormat.MONO14
                    rawBits >= 12 -> PixelFormat.MONO12
                    else -> PixelFormat.MONO10
                }
                else -> when {
                    rawBits >= 16 -> PixelFormat.BAYER_RG16
                    rawBits >= 12 -> PixelFormat.BAYER_RG12
                    else -> PixelFormat.BAYER_RG10
                }
            }

            if (rawBits > 8) {
                currentPixelFormat = highBitFormat
            } else {
                currentPixelFormat = if (isMono) PixelFormat.MONO8 else PixelFormat.BAYER_RG8
            }
        }
        Log.i(TAG, "Format updated: ${currentPixelFormat.name} rawBits=$rawBits supported=${supportedPixelFormats.joinToString { it.name }}")
    }

    override fun setRoi(roi: Roi) {
        val x = roi.x.coerceIn(0, sensorW - 1)
        val y = roi.y.coerceIn(0, sensorH - 1)
        val w = roi.width.coerceIn(1, sensorW - x)
        val h = roi.height.coerceIn(1, sensorH - y)

        Log.i(TAG, "ROI request: ${roi.width}x${roi.height}@(${roi.x},${roi.y}) -> clamped: ${w}x${h}@($x,$y)")
        if (ToupcamJni.putRoi(x, y, w, h)) {
            val actualRoi = ToupcamJni.getRoi()
            val roiW = actualRoi[2]
            val roiH = actualRoi[3]
            Log.i(TAG, "ROI applied: ${roiW}x${roiH}@(${actualRoi[0]},${actualRoi[1]})")
            currentRoi = Roi(actualRoi[0], actualRoi[1], roiW, roiH)
            cropInfo = CropInfo(0, 0, roiW, roiH)
        } else {
            Log.e(TAG, "putRoi failed for ${w}x${h}@($x,$y)")
        }
    }

    override fun resetRoi() {
        setRoi(Roi(0, 0, sensorW, sensorH))
    }

    override fun recycleBuffer(buf: ByteArray) {
        if (bufferPool.size < 8) bufferPool.offer(buf)
    }

    override fun onNativeEvent(event: Int) {
        when (event) {
            ToupcamJni.EVENT_IMAGE -> hasNewFrame.set(true)
            ToupcamJni.EVENT_DISCONNECTED -> {
                Log.w(TAG, "Camera disconnected event")
                running.set(false)
            }
            ToupcamJni.EVENT_ERROR -> Log.e(TAG, "Camera error event")
        }
    }

    private fun captureLoop() {
        var frameSeq = 0L
        var lastLoggedW = 0
        var lastLoggedH = 0
        var lastFrameTimeNs = System.nanoTime()
        val stallTimeoutNs = 2_000_000_000L

        while (running.get()) {
            val hasFrame = hasNewFrame.getAndSet(false)
            if (!hasFrame) {
                val elapsed = System.nanoTime() - lastFrameTimeNs
                if (elapsed > stallTimeoutNs) {
                    Log.w(TAG, "No frame for ${elapsed / 1_000_000}ms, polling SDK")
                    lastFrameTimeNs = System.nanoTime()
                } else {
                    Thread.yield()
                    continue
                }
            }

            try {
                val fmt = currentPixelFormat
                val bpp = fmt.bytesPerPixel
                val bits = when (fmt) {
                    PixelFormat.RGB48 -> 48
                    else -> if (fmt.is8bit) 8 else 16
                }

                val sz = ToupcamJni.getSize()
                val sdkW = sz[0]
                val sdkH = sz[1]
                if (sdkW <= 0 || sdkH <= 0) continue

                val roiW = currentRoi.width
                val roiH = currentRoi.height
                val frameW = if (roiW in 1 until sdkW) roiW else sdkW
                val frameH = if (roiH in 1 until sdkH) roiH else sdkH

                if (frameW != lastLoggedW || frameH != lastLoggedH) {
                    Log.i(TAG, "Capture frame: ${frameW}x${frameH} (sdk=${sdkW}x${sdkH}) bpp=$bpp fmt=${fmt.name}")
                    lastLoggedW = frameW
                    lastLoggedH = frameH
                }

                val pullBufSize = sdkW * sdkH * bpp
                val buf = getBuffer(pullBufSize)
                val hr = ToupcamJni.pullImageRaw(buf, bits)
                if (hr != 0) {
                    recycleBuffer(buf)
                    continue
                }

                lastFrameTimeNs = System.nanoTime()
                frameSeq++
                val frame = FrameData(
                    data = buf,
                    width = frameW,
                    height = frameH,
                    pixelFormat = fmt,
                    frameId = frameSeq,
                    timestamp = System.currentTimeMillis()
                )
                frameCallback?.onFrame(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}")
                if (!running.get()) break
                try { Thread.sleep(5) } catch (_: InterruptedException) {}
            }
        }
        Log.i(TAG, "Capture loop ended")
    }

    private fun getBuffer(size: Int): ByteArray {
        val pooled = bufferPool.poll()
        return if (pooled != null && pooled.size == size) pooled else ByteArray(size)
    }

    private fun readExposureRange() {
        try {
            val range = ToupcamJni.getExpoTimeRange()
            val min = range[0].toFloat().coerceAtLeast(1f)
            val max = range[1].toFloat().coerceAtLeast(min)
            val cur = ToupcamJni.getExpoTime().toFloat()
            hwExposureMaxUs = max
            exposureRange = FloatRange(min, max, cur)
            currentExposureUs = cur
            Log.i(TAG, "Exposure range: ${range[0]}-${range[1]} us (float: ${min.toLong()}-${max.toLong()})")
        } catch (e: Exception) {
            Log.w(TAG, "readExposureRange: ${e.message}")
        }
    }

    private fun readGainRange() {
        try {
            val range = ToupcamJni.getExpoAGainRange()
            val minPct = range[0].toFloat()
            val maxPct = range[1].toFloat()
            val curPct = ToupcamJni.getExpoAGain().toFloat()
            val minDb = (20.0 * log10(minPct / 100.0)).toFloat()
            val maxDb = (20.0 * log10(maxPct / 100.0)).toFloat()
            val curDb = (20.0 * log10(curPct / 100.0)).toFloat()
            gainRange = FloatRange(minDb, maxDb, curDb)
            currentGain = curDb
        } catch (e: Exception) {
            Log.w(TAG, "readGainRange: ${e.message}")
        }
    }

    private fun initCooling() {
        val hasTec = (modelFlag and ToupcamJni.FLAG_TEC) != 0L
        val canGetTemp = (modelFlag and ToupcamJni.FLAG_GETTEMPERATURE) != 0L
        val canSetTarget = (modelFlag and ToupcamJni.FLAG_TEC_ONOFF) != 0L

        Log.i(TAG, "initCooling: hasTec=$hasTec canGetTemp=$canGetTemp canSetTarget=$canSetTarget flag=0x${modelFlag.toString(16)}")

        if (!hasTec && !canGetTemp) {
            Log.i(TAG, "No TEC/temperature support")
            return
        }

        var targetMin = -500
        var targetMax = 500
        if (canSetTarget) {
            try {
                Log.i(TAG, "initCooling: reading TECTARGET_RANGE...")
                val range = ToupcamJni.getOption(ToupcamJni.OPTION_TECTARGET_RANGE)
                Log.i(TAG, "initCooling: TECTARGET_RANGE=0x${range.toString(16)}")
                if (range != 0) {
                    targetMin = (range and 0xFFFF).toShort().toInt()
                    targetMax = ((range shr 16) and 0xFFFF).toShort().toInt()
                }
            } catch (e: Exception) {
                Log.w(TAG, "initCooling: TECTARGET_RANGE failed: ${e.message}")
            }
        }

        tecVoltageMax = 0
        if (hasTec) {
            try {
                Log.i(TAG, "initCooling: reading TEC_VOLTAGE_MAX...")
                val vmaxRange = ToupcamJni.getOption(ToupcamJni.OPTION_TEC_VOLTAGE_MAX)
                Log.i(TAG, "initCooling: TEC_VOLTAGE_MAX=$vmaxRange")
                if (vmaxRange > 0) tecVoltageMax = vmaxRange
            } catch (e: Exception) {
                Log.w(TAG, "initCooling: TEC_VOLTAGE_MAX failed: ${e.message}")
            }
            if (tecVoltageMax <= 0) {
                try {
                    Log.i(TAG, "initCooling: reading TEC_VOLTAGE_MAX_RANGE (0x54)...")
                    val range = ToupcamJni.getOption(0x54)
                    Log.i(TAG, "initCooling: TEC_VOLTAGE_MAX_RANGE=0x${range.toString(16)}")
                    val maxHigh = ((range shr 16) and 0xFFFF).toShort().toInt()
                    if (maxHigh > 0) tecVoltageMax = maxHigh
                } catch (e: Exception) {
                    Log.w(TAG, "initCooling: TEC_VOLTAGE_MAX_RANGE failed: ${e.message}")
                }
            }
        }

        _coolingInfo.value = CoolingInfo(
            hasTec = hasTec,
            canSetTarget = canSetTarget,
            targetMinTenths = targetMin,
            targetMaxTenths = targetMax,
            tecVoltageMaxTenths = tecVoltageMax
        )

        if (canSetTarget) {
            try {
                Log.i(TAG, "initCooling: reading TECTARGET...")
                val curTarget = ToupcamJni.getOption(ToupcamJni.OPTION_TECTARGET)
                _targetTempTenths.value = curTarget
                Log.i(TAG, "initCooling: reading TEC state...")
                val tecState = ToupcamJni.getOption(ToupcamJni.OPTION_TEC)
                _coolerOn.value = tecState != 0
                Log.i(TAG, "initCooling: TEC target=${curTarget/10.0}C on=${tecState != 0}")
            } catch (e: Exception) {
                Log.w(TAG, "initCooling: TEC state read failed: ${e.message}")
            }
        }

        if (canGetTemp || hasTec) {
            try {
                Log.i(TAG, "initCooling: reading temperature...")
                val temp = ToupcamJni.getTemperature()
                Log.i(TAG, "initCooling: temperature=${temp/10.0}C (raw=$temp)")
                if (temp > -2730) _sensorTempTenths.value = temp
            } catch (e: Exception) {
                Log.w(TAG, "initCooling: temperature read failed: ${e.message}")
            }
        }

        Log.i(TAG, "initCooling: starting temp polling...")
        startTempPolling()
        Log.i(TAG, "Cooling init done: hasTec=$hasTec canSetTarget=$canSetTarget range=[${targetMin/10.0}..${targetMax/10.0}]C tecVMax=${tecVoltageMax/10.0}V")
    }

    override fun setCoolerOn(on: Boolean) {
        val ci = _coolingInfo.value ?: return
        if (!ci.canSetTarget) return
        if (ToupcamJni.putOption(ToupcamJni.OPTION_TEC, if (on) 1 else 0)) {
            _coolerOn.value = on
            Log.i(TAG, "Cooler ${if (on) "ON" else "OFF"}")
        }
    }

    override fun setTargetTemperature(tenthsDegC: Int) {
        val ci = _coolingInfo.value ?: return
        if (!ci.canSetTarget) return
        val clamped = tenthsDegC.coerceIn(ci.targetMinTenths, ci.targetMaxTenths)
        if (ToupcamJni.putTemperature(clamped.toShort().toInt())) {
            _targetTempTenths.value = clamped
            Log.i(TAG, "Target temp set to ${clamped / 10.0}C")
        }
    }

    /**
     * Gradually cool down from current sensor temp to [targetTenths] over [durationMinutes].
     * Steps linearly, updating the TEC target every few seconds.
     */
    override fun startCoolDown(targetTenths: Int, durationMinutes: Int) {
        stopRamp()
        val ci = _coolingInfo.value ?: return
        if (!ci.canSetTarget) return

        if (!_coolerOn.value) setCoolerOn(true)

        val clamped = targetTenths.coerceIn(ci.targetMinTenths, ci.targetMaxTenths)
        if (durationMinutes <= 0) {
            ToupcamJni.putTemperature(clamped.toShort().toInt())
            _targetTempTenths.value = clamped
            Log.i(TAG, "Cool-down immediate to ${clamped/10.0}C")
            return
        }

        val startTemp = _sensorTempTenths.value
        val totalSteps = (durationMinutes * 60 / 5).coerceAtLeast(1)
        val stepSize = (clamped - startTemp).toFloat() / totalSteps

        rampRunning.set(true)
        rampThread = Thread({
            Log.i(TAG, "Cool-down ramp: ${startTemp/10.0}C -> ${clamped/10.0}C over ${durationMinutes}min ($totalSteps steps)")
            for (step in 1..totalSteps) {
                if (!rampRunning.get()) break
                val intermediate = (startTemp + stepSize * step).toInt()
                    .coerceIn(ci.targetMinTenths, ci.targetMaxTenths)
                ToupcamJni.putTemperature(intermediate.toShort().toInt())
                _targetTempTenths.value = intermediate
                val elapsed = step * 5
                val remaining = durationMinutes * 60 - elapsed
                _rampStatus.value = "Cooling: ${"%.1f".format(intermediate / 10.0)}掳C (${remaining/60}m${remaining%60}s)"
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
            if (rampRunning.get()) {
                ToupcamJni.putTemperature(clamped.toShort().toInt())
                _targetTempTenths.value = clamped
                _rampStatus.value = ""
            }
            rampRunning.set(false)
            Log.i(TAG, "Cool-down ramp finished")
        }, "TEC-Ramp").apply { isDaemon = true; start() }
    }

    /**
     * Gradually warm up from current sensor temp to ambient over [durationMinutes],
     * then turn off TEC.
     */
    override fun startWarmUp(durationMinutes: Int) {
        stopRamp()
        val ci = _coolingInfo.value ?: return
        if (!ci.canSetTarget) return

        if (durationMinutes <= 0) {
            setCoolerOn(false)
            Log.i(TAG, "Warm-up immediate, TEC off")
            return
        }

        val startTemp = _sensorTempTenths.value
        val ambientTarget = ci.targetMaxTenths.coerceAtMost(200)
        val totalSteps = (durationMinutes * 60 / 5).coerceAtLeast(1)
        val stepSize = (ambientTarget - startTemp).toFloat() / totalSteps

        rampRunning.set(true)
        rampThread = Thread({
            Log.i(TAG, "Warm-up ramp: ${startTemp/10.0}C -> ${ambientTarget/10.0}C over ${durationMinutes}min")
            for (step in 1..totalSteps) {
                if (!rampRunning.get()) break
                val intermediate = (startTemp + stepSize * step).toInt()
                    .coerceIn(ci.targetMinTenths, ci.targetMaxTenths)
                ToupcamJni.putTemperature(intermediate.toShort().toInt())
                _targetTempTenths.value = intermediate
                val elapsed = step * 5
                val remaining = durationMinutes * 60 - elapsed
                _rampStatus.value = "Warming: ${"%.1f".format(intermediate / 10.0)}掳C (${remaining/60}m${remaining%60}s)"
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
            if (rampRunning.get()) {
                setCoolerOn(false)
                _rampStatus.value = ""
            }
            rampRunning.set(false)
            Log.i(TAG, "Warm-up ramp finished, TEC off")
        }, "TEC-Warmup").apply { isDaemon = true; start() }
    }

    override fun stopRamp() {
        rampRunning.set(false)
        rampThread?.interrupt()
        rampThread?.join(3000)
        rampThread = null
        _rampStatus.value = ""
    }

    val isRamping: Boolean get() = rampRunning.get()

    private fun startTempPolling() {
        tempPollRunning.set(true)
        tempPollThread = Thread({
            while (tempPollRunning.get()) {
                try {
                    val temp = ToupcamJni.getTemperature()
                    if (temp > -2730) _sensorTempTenths.value = temp

                    val ci = _coolingInfo.value
                    if (ci != null && ci.hasTec) {
                        try {
                            val v = ToupcamJni.getOption(ToupcamJni.OPTION_TEC_VOLTAGE)
                            _tecVoltageTenths.value = v
                            // Calculate power percentage
                            val maxV = if (tecVoltageMax > 0) tecVoltageMax else 120 // fallback 12V
                            _coolingPowerPct.value = (v.toFloat() / maxV * 100f).coerceIn(0f, 100f)
                        } catch (_: Exception) {}
                    }

                    // Record history point
                    val point = TempHistoryPoint(
                        timestampMs = System.currentTimeMillis(),
                        sensorTenths = _sensorTempTenths.value,
                        powerPct = _coolingPowerPct.value
                    )
                    val history = _tempHistory.value.toMutableList()
                    history.add(point)
                    while (history.size > 60) history.removeAt(0)
                    _tempHistory.value = history

                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "tempPoll error: ${e.message}")
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                }
            }
        }, "TEC-Poll").apply { isDaemon = true; start() }
    }

    private fun stopTempPolling() {
        tempPollRunning.set(false)
        tempPollThread?.interrupt()
        tempPollThread?.join(3000)
        tempPollThread = null
    }
}
