package com.indigo.mobileobservatory.camera.zwo

import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.util.FileLogger
import com.zwo.ASIConstants
import com.zwo.ASIControlCap
import com.zwo.ASIImageBuffer
import com.zwo.ASIReturnType
import com.zwo.ZwoCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

class ZwoAsiCamera : Camera, CameraOffsetCapable, CameraUsbBandwidthCapable,
    CameraEnvironmentControlCapable, CameraBinningCapable, CoolingCapable {

    companion object {
        private const val TAG = "ZwoAsiCam"

        const val ASI_IMG_RAW8 = 0
        const val ASI_IMG_RGB24 = 1
        const val ASI_IMG_RAW16 = 2
        const val ASI_IMG_Y8 = 3

        const val ASI_BAYER_RG = 0
        const val ASI_BAYER_BG = 1
        const val ASI_BAYER_GR = 2
        const val ASI_BAYER_GB = 3

        const val ASI_GAIN = 0
        const val ASI_EXPOSURE = 1
        const val ASI_BRIGHTNESS = 5
        const val ASI_BANDWIDTH_OVERLOAD = 6
        const val ASI_HARDWARE_BIN = 13
        const val ASI_HIGH_SPEED_MODE = 14
        const val ASI_TEMPERATURE = 8
        const val ASI_COOLER_POWER_PERC = 15
        const val ASI_TARGET_TEMP = 16
        const val ASI_COOLER_ON = 17
        // Verified against ASISDK_ANDROID CameraSDK/include/ASICamera2.h:
        // 15 = ASI_COOLER_POWER_PERC, 16 = ASI_TARGET_TEMP, 17 = ASI_COOLER_ON,
        // 18 = ASI_MONO_BIN, 19 = ASI_FAN_ON, 20 = ASI_PATTERN_ADJUST, 21 = ASI_ANTI_DEW_HEATER.
        const val ASI_ANTI_DEW_HEATER = 21
        const val ASI_FAN_ON = 19

        var sdkAvailable: Boolean = false
            private set

        fun initSdk(): Boolean {
            sdkAvailable = try {
                val preloads = listOf("usb-1.0", "c++_shared")
                for (lib in preloads) {
                    try {
                        System.loadLibrary(lib)
                        FileLogger.i(TAG, "Preloaded lib$lib.so OK")
                    } catch (e: UnsatisfiedLinkError) {
                        FileLogger.w(TAG, "lib$lib.so skip: ${e.message}")
                    }
                }
                try {
                    System.loadLibrary("ASICamera2")
                    FileLogger.i(TAG, "Loaded libASICamera2.so OK")
                } catch (e: UnsatisfiedLinkError) {
                    FileLogger.e(TAG, "FAILED libASICamera2.so: ${e.message}")
                    throw e
                }
                try {
                    System.loadLibrary("zwo_camera")
                    FileLogger.i(TAG, "Loaded libzwo_camera.so OK")
                } catch (e: UnsatisfiedLinkError) {
                    FileLogger.e(TAG, "FAILED libzwo_camera.so: ${e.message}")
                    throw e
                }
                true
            } catch (e: UnsatisfiedLinkError) {
                FileLogger.e(TAG, "ZWO SDK init failed: ${e.message}")
                false
            }
            return sdkAvailable
        }
    }

    private var zwoCamera: ZwoCamera? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val bufferPool = ConcurrentLinkedQueue<ByteArray>()

    private val _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    override val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    override var cameraInfo: CameraInfo? = null; private set
    override var exposureRange: FloatRange = FloatRange(32f, 2_000_000_000f, 10_000f); private set
    override var gainRange: FloatRange = FloatRange(0f, 500f, 0f); private set
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

    private val _heaterLevel = MutableStateFlow(0)
    override val heaterLevel: StateFlow<Int> = _heaterLevel.asStateFlow()
    override var heaterSupported: Boolean = false; private set
    override var heaterMaxLevel: Int = 0; private set

    private val _fanLevel = MutableStateFlow(0)
    override val fanLevel: StateFlow<Int> = _fanLevel.asStateFlow()
    override var fanSupported: Boolean = false; private set
    override var fanMaxLevel: Int = 0; private set
    override var currentPixelFormat: PixelFormat = PixelFormat.MONO8; private set
    override var supportedPixelFormats: List<PixelFormat> = listOf(PixelFormat.MONO8); private set
    override var currentRoi: Roi = Roi(0, 0, 1920, 1080); private set
    @Volatile override var cropInfo: CropInfo = CropInfo(0, 0, 1920, 1080); private set
    override var hwExposureMaxUs: Float = 2_000_000_000f; private set
    override var roiMinWidth = 8; private set
    override var roiMinHeight = 2; private set
    @Volatile override var longExposureEnabled: Boolean = false

    private var frameCallback: FrameCallback? = null
    private var isColor = false
    private var bayerPattern = ASI_BAYER_RG
    private var sensorW = 0
    private var sensorH = 0
    private var maxBitDepth = 8
    private var cameraID = -1
    var lastOpenError: String? = null
        private set
    private var currentBin = 1
    override val currentHardwareBin: Int get() = currentBin
    override var supportedHardwareBins: List<Int> = listOf(1); private set
    private var hardwareBinControlAvailable = false
    private var currentImgType = ASI_IMG_RAW8

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
    private var targetTempWholeCelsius = true
    private val tempPollRunning = AtomicBoolean(false)
    private var tempPollThread: Thread? = null
    private val rampRunning = AtomicBoolean(false)
    private var rampThread: Thread? = null

    fun open(cameraIndex: Int): Boolean {
        lastOpenError = null
        var opened: ZwoCamera? = null
        try {
            val propRet = ZwoCamera.getCameraProperty(cameraIndex)
            if (!asiOk(propRet)) {
                return failOpen("getCameraProperty", asiCode(propRet))
            }

            val prop = propRet?.obj as? com.zwo.ASICameraProperty
                ?: return failOpen("getCameraProperty", -1)
            cameraID = ZwoSdk.openIndex(cameraIndex)
            val modelName = prop.name ?: "ZWO ASI Camera"
            sensorW = prop.maxWidth.toInt()
            sensorH = prop.maxHeight.toInt()
            isColor = prop.isColorCam != 0
            bayerPattern = prop.bayerPattern
            val pixelSize = prop.pixelSize
            val transferBitDepth = prop.run {
                val fmts = supportedVideoFormat
                if (fmts != null && fmts.any { it == ASI_IMG_RAW16 }) 16 else 8
            }
            val bitDepth = effectiveBitDepth(modelName, transferBitDepth)
            maxBitDepth = bitDepth

            FileLogger.i(
                TAG,
                "Opening ZWO camera: $modelName sdkIndex=$cameraID propertyId=${prop.cameraID} " +
                    "${sensorW}x${sensorH} color=$isColor bayer=$bayerPattern pixel=${pixelSize}um bitDepth=$bitDepth"
            )

            val cam = ZwoCamera(cameraID)
            val openRet = cam.openCamera()
            if (openRet?.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                return failOpen("openCamera", openRet?.intVal ?: -1)
            }
            opened = cam

            val initRet = cam.initCamera()
            if (initRet?.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                cam.closeCamera()
                return failOpen("initCamera", initRet?.intVal ?: -1)
            }
            zwoCamera = cam
            ignoreJni("refreshSensorSizeFromRoi") { refreshSensorSizeFromRoi(cam, modelName) }

            ignoreJni("readExposureRange") { readExposureRange(cam) }
            ignoreJni("readGainRange") { readGainRange(cam) }
            ignoreJni("readOffsetRange") { readOffsetRange(cam) }
            ignoreJni("configureUsbBandwidth") { configureUsbBandwidth(cam, currentImgType) }
            ignoreJni("readSupportedFormats") { readSupportedFormats(prop) }
            ignoreJni("readSupportedBins") { readSupportedBins(prop, cam) }
            ignoreJni("configureInitialFormat") { configureInitialFormat(cam) }
            ignoreJni("readEnvironmentControls") { readEnvironmentControls(cam) }
            ignoreJni("initCooling") { initCooling(cam) }
            ignoreJni("applyInitialControls") {
                cam.setControlValue(ASI_EXPOSURE, currentExposureUs.toLong().coerceAtLeast(1), 0)
                cam.setControlValue(ASI_GAIN, currentGain.roundToLong(), 0)
            }

            val serialNumber = readSerialNumber(cam)

            cameraInfo = CameraInfo(
                name = modelName,
                serialNumber = serialNumber,
                sensorWidth = sensorW,
                sensorHeight = sensorH,
                maxBitDepth = maxBitDepth,
                sensorName = CameraInfo.lookupSensor(modelName),
                pixelSizeUm = pixelSize.toFloat().takeIf { it > 0f }
            )

            _isOpen.value = true
            startTempPolling()
            FileLogger.i(
                TAG,
                "Camera opened: $modelName (SN=$serialNumber) ${sensorW}x${sensorH} " +
                    "image=${currentRoi.width}x${currentRoi.height} bin=$currentBin maxBit=$maxBitDepth color=$isColor"
            )
            return true
        } catch (e: Throwable) {
            lastOpenError = e.message ?: e.javaClass.simpleName
            FileLogger.e(TAG, "Failed to open ZWO camera: ${e.message}", e)
            try {
                (opened ?: zwoCamera)?.closeCamera()
            } catch (_: Throwable) {
            }
            zwoCamera = null
            return false
        }
    }

    override fun close() {
        stopCapture()
        stopRamp()
        stopTempPolling()
        zwoCamera?.closeCamera()
        zwoCamera = null
        _coolingInfo.value = null
        _isOpen.value = false
        cameraInfo = null
        FileLogger.i(TAG, "Camera closed")
    }

    override fun startCapture(callback: FrameCallback) {
        val cam = zwoCamera ?: return
        if (!_isOpen.value) return
        frameCallback = callback

        val ret = cam.startVideoCapture()
        if (ret?.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            FileLogger.e(TAG, "startVideoCapture failed: ${ret?.intVal}")
            return
        }
        FileLogger.i(
            TAG,
            "startVideoCapture OK roi=${currentRoi.width}x${currentRoi.height} bin=$currentBin " +
                "imgType=$currentImgType exp=${currentExposureUs.toInt()}us"
        )

        running.set(true)
        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            captureLoop()
        }, "ZwoAsiCapture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        _isCapturing.value = true
        FileLogger.i(TAG, "Capture started")
    }

    override fun stopCapture() {
        if (!_isCapturing.value) return
        running.set(false)
        captureThread?.join(3000)
        captureThread = null

        zwoCamera?.stopVideoCapture()
        bufferPool.clear()
        _isCapturing.value = false
        frameCallback = null
        FileLogger.i(TAG, "Capture stopped")
    }

    override fun setExposureTime(us: Float) {
        val cam = zwoCamera ?: return
        val clamped = us.coerceIn(exposureRange.min, hwExposureMaxUs)
        currentExposureUs = clamped
        cam.setControlValue(ASI_EXPOSURE, clamped.toLong(), 0)
        FileLogger.i(TAG, "SetExposure: ${clamped.toInt()} us")
    }

    override fun setGain(value: Float) {
        val cam = zwoCamera ?: return
        val gain = GainValueNormalizer.normalize(gainCapability, value)
        val result = cam.setControlValue(ASI_GAIN, gain.roundToLong(), 0)
        if (result == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            val readBack = cam.getControlValue(ASI_GAIN)
            currentGain = GainValueNormalizer.normalize(
                gainCapability,
                asiLong(readBack)?.toFloat() ?: gain
            )
        }
    }

    override fun gainDbEquivalent(value: Float): Float? =
        GainConversions.dbEquivalent(
            gainCapability.unit,
            GainConversions.zwoNativeToDb(GainValueNormalizer.normalize(gainCapability, value))
        )

    override fun adjustGainForExposure(stops: Float): Float =
        GainValueNormalizer.normalize(
            gainCapability,
            currentGain + GainConversions.zwoStopsToNative(stops)
        )

    override fun setOffset(value: Float) {
        val cam = zwoCamera ?: return
        if (!offsetSupported) return
        val clamped = value.roundToLong().coerceIn(offsetRange.min.toLong(), offsetRange.max.toLong())
        val result = cam.setControlValue(ASI_BRIGHTNESS, clamped, 0)
        if (result == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            currentOffset = clamped.toFloat()
        } else {
            FileLogger.w(TAG, "Set offset failed: $result")
        }
    }

    override fun setUsbBandwidth(value: Int): Boolean {
        val cam = zwoCamera ?: return false
        val range = usbBandwidthRange ?: return false
        val target = value.coerceIn(range.first, range.last)
        val result = cam.setControlValue(ASI_BANDWIDTH_OVERLOAD, target.toLong(), 0)
        if (result != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            FileLogger.w(TAG, "Set USB bandwidth failed: target=$target result=$result")
            return false
        }

        val readBack = cam.getControlValue(ASI_BANDWIDTH_OVERLOAD)
        currentUsbBandwidth = asiLong(readBack)?.toInt()?.coerceIn(range.first, range.last) ?: target
        FileLogger.i(TAG, "USB bandwidth set: requested=$target current=$currentUsbBandwidth")
        return true
    }

    /** Anti-dew heater and cooling fan are on/off controls in the ZWO SDK. */
    private fun readEnvironmentControls(cam: ZwoCamera) {
        heaterSupported = false
        heaterMaxLevel = 0
        _heaterLevel.value = 0
        fanSupported = false
        fanMaxLevel = 0
        _fanLevel.value = 0

        try {
            val heaterCap = findControlCap(cam, ASI_ANTI_DEW_HEATER)
            if (heaterCap != null && heaterCap.isWritable != 0 && heaterCap.maxValue.toInt() >= 1) {
                heaterSupported = true
                heaterMaxLevel = 1
                val current = cam.getControlValue(ASI_ANTI_DEW_HEATER)
                _heaterLevel.value = asiLong(current)?.toInt()?.coerceIn(0, 1) ?: 0
            }

            val fanCap = findControlCap(cam, ASI_FAN_ON)
            if (fanCap != null && fanCap.isWritable != 0 && fanCap.maxValue.toInt() >= 1) {
                fanSupported = true
                fanMaxLevel = 1
                val current = cam.getControlValue(ASI_FAN_ON)
                _fanLevel.value = asiLong(current)?.toInt()?.coerceIn(0, 1) ?: 0
            }
        } catch (e: Throwable) {
            FileLogger.w(TAG, "Environment controls unavailable: ${e.message}")
        }
        FileLogger.i(
            TAG,
            "Environment controls: heater=$heaterSupported fan=$fanSupported heaterLevel=${_heaterLevel.value} fanLevel=${_fanLevel.value}"
        )
    }

    private fun initCooling(cam: ZwoCamera) {
        stopTempPolling()
        _coolingInfo.value = null
        _coolerOn.value = false
        _coolingPowerPct.value = 0f
        _tempHistory.value = emptyList()

        val coolerCap = findControlCap(cam, ASI_COOLER_ON)
        val targetCap = findControlCap(cam, ASI_TARGET_TEMP)
        val hasTec = coolerCap != null && coolerCap.isWritable != 0
        val canSetTarget = targetCap != null && targetCap.isWritable != 0
        if (!hasTec && findControlCap(cam, ASI_TEMPERATURE) == null) {
            FileLogger.i(TAG, "Cooling: no TEC or temperature control")
            return
        }

        targetTempWholeCelsius = if (targetCap != null) {
            ZwoSdk.targetTempIsWholeCelsius(targetCap.minValue, targetCap.maxValue)
        } else {
            true
        }
        val targetMin = if (targetCap != null) {
            ZwoSdk.nativeTargetToTenths(targetCap.minValue, targetTempWholeCelsius)
        } else {
            -400
        }
        val targetMax = if (targetCap != null) {
            ZwoSdk.nativeTargetToTenths(targetCap.maxValue, targetTempWholeCelsius)
        } else {
            300
        }

        _coolingInfo.value = CoolingInfo(
            hasTec = hasTec,
            canSetTarget = canSetTarget,
            targetMinTenths = targetMin,
            targetMaxTenths = targetMax,
            tecVoltageMaxTenths = 0
        )
        _coolerOn.value = (asiLong(cam.getControlValue(ASI_COOLER_ON)) ?: 0L) != 0L
        if (targetCap != null) {
            _targetTempTenths.value = ZwoSdk.nativeTargetToTenths(
                asiLong(cam.getControlValue(ASI_TARGET_TEMP)) ?: targetCap.defaultValue,
                targetTempWholeCelsius
            )
        }
        _sensorTempTenths.value = (asiLong(cam.getControlValue(ASI_TEMPERATURE)) ?: 0L).toInt()
        _coolingPowerPct.value = (asiLong(cam.getControlValue(ASI_COOLER_POWER_PERC)) ?: 0L)
            .toFloat().coerceIn(0f, 100f)
        FileLogger.i(
            TAG,
            "Cooling init: hasTec=$hasTec canSetTarget=$canSetTarget " +
                "range=[${targetMin / 10.0}..${targetMax / 10.0}]C wholeCelsius=$targetTempWholeCelsius " +
                "sensor=${_sensorTempTenths.value / 10.0}C coolerOn=${_coolerOn.value}"
        )
    }

    private fun startTempPolling() {
        if (_coolingInfo.value == null) return
        tempPollRunning.set(true)
        tempPollThread = Thread({
            while (tempPollRunning.get() && zwoCamera != null) {
                try {
                    val cam = zwoCamera
                    if (cam != null) {
                        asiLong(cam.getControlValue(ASI_TEMPERATURE))?.let {
                            _sensorTempTenths.value = it.toInt()
                        }
                        asiLong(cam.getControlValue(ASI_COOLER_POWER_PERC))?.let {
                            _coolingPowerPct.value = it.toFloat().coerceIn(0f, 100f)
                        }
                        asiLong(cam.getControlValue(ASI_COOLER_ON))?.let {
                            _coolerOn.value = it != 0L
                        }
                        val history = _tempHistory.value.toMutableList()
                        history += TempHistoryPoint(
                            System.currentTimeMillis(),
                            _sensorTempTenths.value,
                            _coolingPowerPct.value
                        )
                        while (history.size > 180) history.removeAt(0)
                        _tempHistory.value = history
                    }
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ZWO-TEC-Poll").apply { isDaemon = true; start() }
    }

    private fun stopTempPolling() {
        tempPollRunning.set(false)
        tempPollThread?.interrupt()
        tempPollThread?.join(2000)
        tempPollThread = null
    }

    override fun setHeaterLevel(level: Int) {
        val cam = zwoCamera ?: return
        if (!heaterSupported) return
        val target = level.coerceIn(0, heaterMaxLevel)
        val result = cam.setControlValue(ASI_ANTI_DEW_HEATER, target.toLong(), 0)
        if (result == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            _heaterLevel.value = target
        } else {
            FileLogger.w(TAG, "Set anti-dew heater failed: $result")
        }
    }

    override fun setFanLevel(level: Int) {
        val cam = zwoCamera ?: return
        if (!fanSupported) return
        val target = level.coerceIn(0, fanMaxLevel)
        val result = cam.setControlValue(ASI_FAN_ON, target.toLong(), 0)
        if (result == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            _fanLevel.value = target
        } else {
            FileLogger.w(TAG, "Set fan failed: $result")
        }
    }

    override fun setPixelFormat(format: PixelFormat) {
        if (format == currentPixelFormat) return
        val cam = zwoCamera ?: return
        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()

        val imgType = pixelFormatToAsiImgType(format)
        val roiFmt = cam.getROIFormat()
        if (asiOk(roiFmt)) {
            val roi = roiFmt?.obj as? com.zwo.ASIROIFormat
            if (roi != null) {
                val ret = cam.setRoiFormat(roi.imgWidth, roi.imgHeight, roi.getiBin(), imgType)
                if (ret?.intVal == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                    currentImgType = imgType
                    currentPixelFormat = format
                    configureUsbBandwidth(cam, imgType)
                    readGainRange(cam)
                    FileLogger.i(TAG, "PixelFormat set to ${format.name} (asiType=$imgType)")
                } else {
                    FileLogger.w(TAG, "setRoiFormat failed for ${format.name}: ${ret?.intVal}")
                }
            }
        }

        if (wasCapturing && cb != null) startCapture(cb)
    }

    override fun setRoi(roi: Roi) {
        val cam = zwoCamera ?: return
        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()

        val reqW = roi.width.coerceIn(roiMinWidth, sensorW / currentBin)
        val reqH = roi.height.coerceIn(roiMinHeight, sensorH / currentBin)
        val w = (reqW / 8) * 8
        val h = (reqH / 2) * 2

        val ret = cam.setRoiFormat(w, h, currentBin, currentImgType)
        if (ret?.intVal == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            val startX = roi.x.coerceIn(0, (sensorW / currentBin) - w)
            val startY = roi.y.coerceIn(0, (sensorH / currentBin) - h)
            cam.setStartPos(startX, startY)

            currentRoi = Roi(startX, startY, w, h)
            cropInfo = CropInfo(0, 0, w, h)
            FileLogger.i(TAG, "ROI set: ${w}x${h}@($startX,$startY) bin=$currentBin")
        } else {
            FileLogger.w(TAG, "setRoiFormat failed: ${ret?.intVal}")
        }

        if (wasCapturing && cb != null) startCapture(cb)
    }

    override fun resetRoi() {
        val maxW = sensorW / currentBin
        val maxH = sensorH / currentBin
        setRoi(Roi(0, 0, maxW, maxH))
    }

    override fun setHardwareBin(bin: Int): Boolean {
        val cam = zwoCamera ?: return false
        val b = if (bin in supportedHardwareBins) bin else return false
        if (b == currentBin) return true
        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()
        val previous = currentBin
        val applied = try {
            enableHardwareBinControl(cam, b > 1)
            currentBin = b
            val maxW = (sensorW / b / 8) * 8
            val maxH = (sensorH / b / 2) * 2
            val ret = cam.setRoiFormat(maxW.coerceAtLeast(roiMinWidth), maxH.coerceAtLeast(roiMinHeight), b, currentImgType)
            if (ret?.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                FileLogger.w(TAG, "setHardwareBin($b) setRoiFormat failed: ${ret?.intVal}")
                currentBin = previous
                enableHardwareBinControl(cam, previous > 1)
                false
            } else {
                cam.setStartPos(0, 0)
                currentRoi = Roi(0, 0, maxW.coerceAtLeast(roiMinWidth), maxH.coerceAtLeast(roiMinHeight))
                cropInfo = CropInfo(0, 0, currentRoi.width, currentRoi.height)
                FileLogger.i(TAG, "Bin -> $b, imageSize=${currentRoi.width}x${currentRoi.height}")
                true
            }
        } catch (e: Throwable) {
            FileLogger.w(TAG, "setHardwareBin($b) failed: ${e.message}")
            currentBin = previous
            false
        }
        if (wasCapturing && cb != null) startCapture(cb)
        return applied
    }

    override fun setCoolerOn(on: Boolean) {
        val cam = zwoCamera ?: return
        if (_coolingInfo.value?.hasTec != true) return
        val result = cam.setControlValue(ASI_COOLER_ON, if (on) 1L else 0L, 0)
        if (result == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            _coolerOn.value = on
            FileLogger.i(TAG, "Cooler ${if (on) "ON" else "OFF"}")
        } else {
            FileLogger.w(TAG, "setCoolerOn failed: $result")
        }
    }

    override fun setTargetTemperature(tenthsDegC: Int) {
        val cam = zwoCamera ?: return
        val ci = _coolingInfo.value ?: return
        if (!ci.canSetTarget) return
        val clamped = tenthsDegC.coerceIn(ci.targetMinTenths, ci.targetMaxTenths)
        val native = ZwoSdk.tenthsToNativeTarget(clamped, targetTempWholeCelsius)
        val result = cam.setControlValue(ASI_TARGET_TEMP, native, 0)
        if (result == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            _targetTempTenths.value = clamped
            FileLogger.i(TAG, "Target temp ${clamped / 10.0}C native=$native")
        } else {
            FileLogger.w(TAG, "setTargetTemperature failed: $result")
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
        }, "ZWO-TEC-Ramp").apply { isDaemon = true; start() }
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
        }, "ZWO-TEC-Warmup").apply { isDaemon = true; start() }
    }

    override fun stopRamp() {
        rampRunning.set(false)
        rampThread?.interrupt()
        rampThread?.join(3000)
        rampThread = null
        _rampStatus.value = ""
    }

    override fun recycleBuffer(buf: ByteArray) {
        val cap = ZwoSdk.maxPooledFrameBuffers(buf.size)
        while (bufferPool.size >= cap) {
            bufferPool.poll()
        }
        if (bufferPool.size < cap) bufferPool.offer(buf)
    }

    private fun getBuffer(size: Int): ByteArray {
        val pooled = bufferPool.poll()
        return if (pooled != null && pooled.size == size) pooled else ByteArray(size)
    }

    private fun captureLoop() {
        val cam = zwoCamera ?: return
        var frameSeq = 0L
        var bufCapacity = 0
        var imgBuf: ASIImageBuffer? = null
        var timeouts = 0
        var emptyReturns = 0
        var lastTimeoutLogMs = 0L
        var lastEmptyLogMs = 0L

        while (running.get()) {
            try {
                val w = currentRoi.width
                val h = currentRoi.height
                val frameBpp = currentPixelFormat.bytesPerPixel
                val imageBytes = ZwoSdk.captureDirectBufferBytes(w, h, frameBpp)
                val grabBytes = ZwoSdk.captureGrabBufferBytes(w, h, frameBpp)
                if (imgBuf == null || bufCapacity < grabBytes) {
                    imgBuf = ASIImageBuffer.allocate(grabBytes)
                    bufCapacity = grabBytes
                    FileLogger.i(TAG, "Grab buffer allocated: image=$imageBytes grab=$grabBytes ${w}x${h} bpp=$frameBpp")
                }
                val grabBuf = imgBuf ?: continue
                val bb = grabBuf.getmByteBuffer()
                bb.clear()

                val timeoutMs = ZwoSdk.grabTimeoutMs(currentExposureUs, imageBytes)
                val ret = cam.getVideoData(grabBuf, imageBytes, timeoutMs)
                if (ret == null) {
                    emptyReturns++
                    val now = System.currentTimeMillis()
                    if (now - lastEmptyLogMs >= 2000L) {
                        lastEmptyLogMs = now
                        FileLogger.w(
                            TAG,
                            "getVideoData null x$emptyReturns wait=${timeoutMs}ms " +
                                "roi=${w}x${h} bytes=$imageBytes exp=${currentExposureUs.toInt()}us"
                        )
                    }
                    Thread.sleep(20)
                    continue
                }
                if (ret.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                    if (ret.intVal == ASIConstants.ASI_ERROR_CODE.ASI_ERROR_TIMEOUT) {
                        timeouts++
                        val now = System.currentTimeMillis()
                        if (now - lastTimeoutLogMs >= 2000L) {
                            lastTimeoutLogMs = now
                            FileLogger.w(
                                TAG,
                                "getVideoData timeout x$timeouts wait=${timeoutMs}ms " +
                                    "roi=${w}x${h} bytes=$imageBytes exp=${currentExposureUs.toInt()}us"
                            )
                        }
                        Thread.sleep(50)
                    } else {
                        FileLogger.w(TAG, "getVideoData error: ${ret.intVal}")
                        Thread.sleep(20)
                    }
                    continue
                }

                bb.position(0)
                val outData = getBuffer(imageBytes)
                bb.get(outData, 0, imageBytes.coerceAtMost(outData.size).coerceAtMost(bb.remaining()))

                val pixFmt = asiImgTypeToPixelFormat(currentImgType)

                if (frameSeq == 0L) {
                    FileLogger.i(TAG, "First frame: ${w}x${h} imgType=$currentImgType fmt=${pixFmt.name} bpp=$frameBpp")
                }

                frameSeq++
                val frame = FrameData(
                    data = outData,
                    width = w,
                    height = h,
                    pixelFormat = pixFmt,
                    frameId = frameSeq,
                    timestamp = System.currentTimeMillis()
                )
                frameCallback?.onFrame(frame)

            } catch (e: OutOfMemoryError) {
                FileLogger.e(TAG, "Capture OOM roi=${currentRoi.width}x${currentRoi.height}", e)
                bufferPool.clear()
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            } catch (e: Exception) {
                FileLogger.e(TAG, "Capture error: ${e.message}")
                if (!running.get()) break
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
            }
        }
        FileLogger.i(TAG, "Capture loop ended, $frameSeq frames captured, timeouts=$timeouts empty=$emptyReturns")
    }

    private fun readExposureRange(cam: ZwoCamera) {
        val cap = findControlCap(cam, ASI_EXPOSURE) ?: return
        val min = cap.minValue.toFloat().coerceAtLeast(1f)
        val max = cap.maxValue.toFloat()
        val def = cap.defaultValue.toFloat().coerceIn(min, max)
        hwExposureMaxUs = max
        exposureRange = FloatRange(min, max, def)
        currentExposureUs = def
        FileLogger.i(TAG, "Exposure range: ${min.toInt()}-${max.toInt()} us")
    }

    private fun readGainRange(cam: ZwoCamera) {
        val cap = findControlCap(cam, ASI_GAIN) ?: return
        val minGain = cap.minValue.toFloat()
        val maxGain = cap.maxValue.toFloat()
        val defaultGain = cap.defaultValue.toFloat().coerceIn(minGain, maxGain)
        val current = asiLong(cam.getControlValue(ASI_GAIN))?.toFloat()?.coerceIn(minGain, maxGain)
            ?: defaultGain
        gainRange = FloatRange(minGain, maxGain, current)
        gainCapability = GainCapability(
            min = minGain,
            max = maxGain,
            step = 1f,
            defaultValue = defaultGain,
            decimalPlaces = 0
        )
        currentGain = GainValueNormalizer.normalize(gainCapability, current)
        FileLogger.i(TAG, "Gain range: $minGain-$maxGain native (current=$currentGain, default=$defaultGain)")
    }

    private fun readOffsetRange(cam: ZwoCamera) {
        val cap = findControlCap(cam, ASI_BRIGHTNESS) ?: return
        if (cap.isWritable == 0) return

        val min = cap.minValue.toFloat()
        val max = cap.maxValue.toFloat()
        val value = asiLong(cam.getControlValue(ASI_BRIGHTNESS)) ?: return

        offsetSupported = true
        currentOffset = value.toFloat().coerceIn(min, max)
        offsetRange = FloatRange(min, max, currentOffset)
        FileLogger.i(TAG, "Offset range: $min-$max (current=$currentOffset)")
    }

    private fun configureUsbBandwidth(cam: ZwoCamera, imgType: Int) {
        val cap = findControlCap(cam, ASI_BANDWIDTH_OVERLOAD)
        if (cap == null) {
            usbBandwidthRange = null
            currentUsbBandwidth = null
            FileLogger.i(TAG, "USB bandwidth control unavailable")
            return
        }

        if (cap.isWritable == 0) {
            usbBandwidthRange = null
            currentUsbBandwidth = null
            FileLogger.i(TAG, "USB bandwidth control is read-only")
            return
        }

        val min = cap.minValue.toInt()
        val max = cap.maxValue.toInt()
        if (min > max) return
        usbBandwidthRange = min..max

        val current = asiLong(cam.getControlValue(ASI_BANDWIDTH_OVERLOAD)) ?: cap.defaultValue
        val target = if (imgType == ASI_IMG_RAW16) {
            50.coerceIn(min, max)
        } else {
            80.coerceIn(min, max)
        }
        val result = setUsbBandwidth(target)
        FileLogger.i(
            TAG,
            "USB bandwidth: range=${cap.minValue}-${cap.maxValue} default=${cap.defaultValue} " +
                "current=$current target=$target result=$result"
        )
    }

    private fun readSupportedFormats(prop: com.zwo.ASICameraProperty) {
        val formats = mutableListOf<PixelFormat>()
        val supported = prop.supportedVideoFormat
        if (supported != null) {
            for (fmt in supported) {
                if (fmt == -1) break
                val pf = asiImgTypeToPixelFormat(fmt)
                if (pf !in formats) formats.add(pf)
            }
        }
        if (formats.isEmpty()) {
            formats.add(if (!isColor) PixelFormat.MONO8 else PixelFormat.BAYER_RG8)
        }
        supportedPixelFormats = formats
        FileLogger.i(TAG, "Supported formats: ${formats.joinToString { it.name }}")
    }

    private fun readSupportedBins(prop: com.zwo.ASICameraProperty, cam: ZwoCamera) {
        val parsed = try {
            prop.getSupportBins()?.filter { it > 0 }.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }.distinct().sorted()
        supportedHardwareBins = parsed.ifEmpty { listOf(1, 2) }
        hardwareBinControlAvailable = findControlCap(cam, ASI_HARDWARE_BIN)?.isWritable != 0
        FileLogger.i(
            TAG,
            "Supported bins: ${supportedHardwareBins.joinToString()} hardwareControl=$hardwareBinControlAvailable"
        )
    }

    private fun enableHardwareBinControl(cam: ZwoCamera, enable: Boolean) {
        if (!hardwareBinControlAvailable) return
        val result = cam.setControlValue(ASI_HARDWARE_BIN, if (enable) 1L else 0L, 0)
        if (result != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            FileLogger.w(TAG, "ASI_HARDWARE_BIN=${if (enable) 1 else 0} failed: $result")
        }
    }

    private fun ignoreJni(step: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            FileLogger.w(TAG, "$step failed: ${e.message}")
        }
    }

    private fun failOpen(step: String, code: Int): Boolean {
        val name = try {
            ASIConstants.ASI_ERROR_CODE.getErrorString(code)
        } catch (_: Throwable) {
            "error"
        }
        lastOpenError = "$step: $name ($code)"
        FileLogger.e(TAG, lastOpenError!!)
        return false
    }

    private fun asiOk(ret: ASIReturnType?): Boolean =
        ZwoSdk.returnSucceeded(ret?.errorCode?.intVal)

    private fun asiCode(ret: ASIReturnType?): Int =
        ret?.errorCode?.intVal ?: -1

    private fun asiLong(ret: ASIReturnType?): Long? =
        ret?.takeIf { asiOk(it) }?.extraLongVal1

    private fun findControlCap(cam: ZwoCamera, controlType: Int): ASIControlCap? {
        return try {
            val countRet = cam.getNumOfControls()
            if (!asiOk(countRet)) return null
            val controlCount = (countRet?.obj as? Number)?.toInt() ?: return null
            for (index in 0 until controlCount) {
                val capRet = cam.getControlCapsByIndex(index) ?: continue
                if (!asiOk(capRet)) continue
                val cap = capRet.obj as? ASIControlCap ?: continue
                if (cap.controlType == controlType) return cap
            }
            null
        } catch (e: Throwable) {
            FileLogger.w(TAG, "findControlCap($controlType) failed: ${e.message}")
            null
        }
    }

    private fun refreshSensorSizeFromRoi(cam: ZwoCamera, modelName: String) {
        if (ZwoSdk.sensorSizePlausible(sensorW, sensorH)) return
        val roiFmt = try {
            cam.getROIFormat()
        } catch (e: Throwable) {
            FileLogger.w(TAG, "getROIFormat failed: ${e.message}")
            null
        }
        if (asiOk(roiFmt)) {
            val roi = roiFmt?.obj as? com.zwo.ASIROIFormat
            if (roi != null) {
                val bin = roi.getiBin().coerceAtLeast(1)
                val width = roi.imgWidth * bin
                val height = roi.imgHeight * bin
                if (ZwoSdk.sensorSizePlausible(width, height)) {
                    FileLogger.w(TAG, "Correcting sensor size from ROI $sensorW x $sensorH -> $width x $height")
                    sensorW = width
                    sensorH = height
                    return
                }
            }
        }
        val fallback = ZwoSdk.fallbackSensorSize(modelName)
        if (fallback != null) {
            FileLogger.w(
                TAG,
                "Using fallback sensor size for $modelName: ${fallback.first}x${fallback.second} " +
                    "(JNI reported ${sensorW}x${sensorH})"
            )
            sensorW = fallback.first
            sensorH = fallback.second
        }
    }

    private fun syncRoiFromCamera(cam: ZwoCamera) {
        val roiFmt = try {
            cam.getROIFormat()
        } catch (_: Throwable) {
            null
        }
        if (!asiOk(roiFmt)) return
        val roi = roiFmt?.obj as? com.zwo.ASIROIFormat ?: return
        val width = roi.imgWidth
        val height = roi.imgHeight
        val bin = roi.getiBin().coerceAtLeast(1)
        if (width < roiMinWidth || height < roiMinHeight) return
        currentBin = bin
        currentRoi = Roi(0, 0, width, height)
        FileLogger.i(TAG, "Camera ROI readout: ${width}x${height} bin=$bin imgType=${roi.imgType}")
    }

    private fun configureInitialFormat(cam: ZwoCamera) {
        currentImgType = if (!isColor) ASI_IMG_RAW8 else ASI_IMG_RAW8
        currentPixelFormat = if (!isColor) PixelFormat.MONO8 else defaultBayer8()

        val bin = ZwoSdk.defaultPreviewBin(sensorW, sensorH, supportedHardwareBins)
        enableHardwareBinControl(cam, bin > 1)
        val w = ((sensorW / bin) / 8) * 8
        val h = ((sensorH / bin) / 2) * 2
        currentBin = bin
        currentRoi = Roi(0, 0, w.coerceAtLeast(roiMinWidth), h.coerceAtLeast(roiMinHeight))
        cropInfo = CropInfo(0, 0, currentRoi.width, currentRoi.height)
        val ret = cam.setRoiFormat(
            w.coerceAtLeast(roiMinWidth),
            h.coerceAtLeast(roiMinHeight),
            bin,
            currentImgType
        )
        if (ret?.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            FileLogger.w(TAG, "Initial setRoiFormat bin=$bin failed: ${ret?.intVal}, falling back to 1x")
            enableHardwareBinControl(cam, false)
            currentBin = 1
            val fullW = (sensorW / 8) * 8
            val fullH = (sensorH / 2) * 2
            cam.setRoiFormat(fullW, fullH, 1, currentImgType)
            currentRoi = Roi(0, 0, fullW, fullH)
        } else {
            currentBin = bin
            currentRoi = Roi(0, 0, w.coerceAtLeast(roiMinWidth), h.coerceAtLeast(roiMinHeight))
        }
        syncRoiFromCamera(cam)
        val highSpeedCap = findControlCap(cam, ASI_HIGH_SPEED_MODE)
        if (highSpeedCap != null && highSpeedCap.isWritable != 0) {
            val hs = cam.setControlValue(ASI_HIGH_SPEED_MODE, 0L, 0)
            FileLogger.i(TAG, "ASI_HIGH_SPEED_MODE=0 result=$hs")
        }
        cropInfo = CropInfo(0, 0, currentRoi.width, currentRoi.height)
        FileLogger.i(
            TAG,
            "Initial format: ${currentPixelFormat.name} (imgType=$currentImgType) " +
                "${currentRoi.width}x${currentRoi.height} bin=$currentBin"
        )
    }

    private fun readSerialNumber(cam: ZwoCamera): String {
        return try {
            val idRet = cam.getID()
            if (asiOk(idRet)) {
                val idBytes = idRet?.obj
                if (idBytes is CharArray) {
                    idBytes.map { String.format("%02x", it.code) }.joinToString("")
                } else if (idBytes is ByteArray) {
                    idBytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
                } else {
                    "ZWO-$cameraID"
                }
            } else {
                "ZWO-$cameraID"
            }
        } catch (e: Throwable) {
            "ZWO-$cameraID"
        }
    }

    private fun defaultBayer8(): PixelFormat = when (bayerPattern) {
        ASI_BAYER_RG -> PixelFormat.BAYER_RG8
        ASI_BAYER_BG -> PixelFormat.BAYER_BG8
        ASI_BAYER_GR -> PixelFormat.BAYER_GR8
        ASI_BAYER_GB -> PixelFormat.BAYER_GB8
        else -> PixelFormat.BAYER_RG8
    }

    private fun asiImgTypeToPixelFormat(imgType: Int): PixelFormat = when {
        !isColor && imgType == ASI_IMG_RAW8 -> PixelFormat.MONO8
        !isColor && imgType == ASI_IMG_RAW16 -> monoHighBitFormat()
        !isColor && imgType == ASI_IMG_Y8 -> PixelFormat.MONO8
        isColor && imgType == ASI_IMG_RAW8 -> defaultBayer8()
        isColor && imgType == ASI_IMG_RAW16 -> bayerHighBitFormat()
        else -> if (!isColor) PixelFormat.MONO8 else defaultBayer8()
    }

    private fun pixelFormatToAsiImgType(format: PixelFormat): Int = when (format) {
        PixelFormat.MONO8 -> ASI_IMG_RAW8
        PixelFormat.MONO10, PixelFormat.MONO12, PixelFormat.MONO14, PixelFormat.MONO16 -> ASI_IMG_RAW16
        PixelFormat.BAYER_RG8, PixelFormat.BAYER_GR8, PixelFormat.BAYER_GB8, PixelFormat.BAYER_BG8 -> ASI_IMG_RAW8
        PixelFormat.BAYER_RG10, PixelFormat.BAYER_GR10, PixelFormat.BAYER_GB10, PixelFormat.BAYER_BG10,
        PixelFormat.BAYER_RG12, PixelFormat.BAYER_GR12, PixelFormat.BAYER_GB12, PixelFormat.BAYER_BG12,
        PixelFormat.BAYER_RG14, PixelFormat.BAYER_GR14, PixelFormat.BAYER_GB14, PixelFormat.BAYER_BG14,
        PixelFormat.BAYER_RG16, PixelFormat.BAYER_GR16, PixelFormat.BAYER_GB16, PixelFormat.BAYER_BG16 -> ASI_IMG_RAW16
        else -> ASI_IMG_RAW8
    }

    private fun effectiveBitDepth(modelName: String, transferBitDepth: Int): Int = when {
        modelName.contains("533", ignoreCase = true) -> 14
        else -> transferBitDepth
    }

    private fun monoHighBitFormat(): PixelFormat = when (maxBitDepth) {
        10 -> PixelFormat.MONO10
        12 -> PixelFormat.MONO12
        14 -> PixelFormat.MONO14
        else -> PixelFormat.MONO16
    }

    private fun bayerHighBitFormat(): PixelFormat = when (bayerPattern) {
        ASI_BAYER_RG -> when (maxBitDepth) {
            10 -> PixelFormat.BAYER_RG10
            12 -> PixelFormat.BAYER_RG12
            14 -> PixelFormat.BAYER_RG14
            else -> PixelFormat.BAYER_RG16
        }
        ASI_BAYER_BG -> when (maxBitDepth) {
            10 -> PixelFormat.BAYER_BG10
            12 -> PixelFormat.BAYER_BG12
            14 -> PixelFormat.BAYER_BG14
            else -> PixelFormat.BAYER_BG16
        }
        ASI_BAYER_GR -> when (maxBitDepth) {
            10 -> PixelFormat.BAYER_GR10
            12 -> PixelFormat.BAYER_GR12
            14 -> PixelFormat.BAYER_GR14
            else -> PixelFormat.BAYER_GR16
        }
        ASI_BAYER_GB -> when (maxBitDepth) {
            10 -> PixelFormat.BAYER_GB10
            12 -> PixelFormat.BAYER_GB12
            14 -> PixelFormat.BAYER_GB14
            else -> PixelFormat.BAYER_GB16
        }
        else -> PixelFormat.BAYER_RG16
    }
}
