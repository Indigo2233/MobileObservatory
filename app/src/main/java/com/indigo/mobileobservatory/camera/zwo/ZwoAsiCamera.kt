package com.indigo.mobileobservatory.camera.zwo

import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.util.FileLogger
import com.zwo.ASIConstants
import com.zwo.ASIControlCap
import com.zwo.ASIImageBuffer
import com.zwo.ZwoCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

class ZwoAsiCamera : Camera, CameraOffsetCapable {

    companion object {
        private const val TAG = "ZwoAsiCam"
        private const val GRAB_TIMEOUT_MS = 2000

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
        const val ASI_HARDWARE_BIN = 13
        const val ASI_HIGH_SPEED_MODE = 14

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
    override var currentExposureUs: Float = 10_000f; private set
    override var currentGain: Float = 0f; private set
    override var offsetSupported: Boolean = false; private set
    override val offsetLabel: String = "Offset"
    override var offsetRange: FloatRange = FloatRange(0f, 0f, 0f); private set
    override val offsetStep: Float = 1f
    override var currentOffset: Float = 0f; private set
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
    private var currentBin = 1
    private var currentImgType = ASI_IMG_RAW8

    fun open(cameraIndex: Int): Boolean {
        try {
            val propRet = ZwoCamera.getCameraProperty(cameraIndex)
            if (propRet.errorCode.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                FileLogger.e(TAG, "getCameraProperty failed: ${propRet.errorCode.intVal}")
                return false
            }

            val prop = propRet.obj as com.zwo.ASICameraProperty
            cameraID = prop.cameraID
            val modelName = prop.name ?: "ZWO ASI Camera"
            sensorW = prop.maxWidth.toInt()
            sensorH = prop.maxHeight.toInt()
            isColor = prop.isColorCam != 0
            bayerPattern = prop.bayerPattern
            val pixelSize = prop.pixelSize
            val bitDepth = prop.run {
                val fmts = supportedVideoFormat
                if (fmts != null && fmts.any { it == ASI_IMG_RAW16 }) 16 else 8
            }
            maxBitDepth = bitDepth

            FileLogger.i(TAG, "Opening ZWO camera: $modelName ID=$cameraID ${sensorW}x${sensorH} color=$isColor bayer=$bayerPattern pixel=${pixelSize}um bitDepth=$bitDepth")

            val cam = ZwoCamera(cameraID)
            val openRet = cam.openCamera()
            if (openRet.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                FileLogger.e(TAG, "openCamera failed: ${openRet.intVal}")
                return false
            }

            val initRet = cam.initCamera()
            if (initRet.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                FileLogger.e(TAG, "initCamera failed: ${initRet.intVal}")
                cam.closeCamera()
                return false
            }
            zwoCamera = cam

            readExposureRange(cam)
            readGainRange(cam)
            readOffsetRange(cam)
            readSupportedFormats(prop)
            configureInitialFormat(cam)

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

            currentRoi = Roi(0, 0, sensorW, sensorH)
            cropInfo = CropInfo(0, 0, sensorW, sensorH)
            _isOpen.value = true
            FileLogger.i(TAG, "Camera opened: $modelName (SN=$serialNumber) ${sensorW}x${sensorH} maxBit=$maxBitDepth color=$isColor")
            return true
        } catch (e: Throwable) {
            FileLogger.e(TAG, "Failed to open ZWO camera: ${e.message}", e)
            return false
        }
    }

    override fun close() {
        stopCapture()
        zwoCamera?.closeCamera()
        zwoCamera = null
        _isOpen.value = false
        cameraInfo = null
        FileLogger.i(TAG, "Camera closed")
    }

    override fun startCapture(callback: FrameCallback) {
        val cam = zwoCamera ?: return
        if (!_isOpen.value) return
        frameCallback = callback

        val ret = cam.startVideoCapture()
        if (ret.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            FileLogger.e(TAG, "startVideoCapture failed: ${ret.intVal}")
            return
        }

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

    override fun setGain(db: Float) {
        val cam = zwoCamera ?: return
        val clamped = db.coerceIn(gainRange.min, gainRange.max)
        cam.setControlValue(ASI_GAIN, clamped.toLong(), 0)
        currentGain = clamped
    }

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

    override fun setPixelFormat(format: PixelFormat) {
        if (format == currentPixelFormat) return
        val cam = zwoCamera ?: return
        val wasCapturing = _isCapturing.value
        val cb = frameCallback
        if (wasCapturing) stopCapture()

        val imgType = pixelFormatToAsiImgType(format)
        val roiFmt = cam.getROIFormat()
        if (roiFmt.errorCode.intVal == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            val roi = roiFmt.obj as com.zwo.ASIROIFormat
            val ret = cam.setRoiFormat(roi.imgWidth, roi.imgHeight, roi.getiBin(), imgType)
            if (ret.intVal == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                currentImgType = imgType
                currentPixelFormat = format
                FileLogger.i(TAG, "PixelFormat set to ${format.name} (asiType=$imgType)")
            } else {
                FileLogger.w(TAG, "setRoiFormat failed for ${format.name}: ${ret.intVal}")
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
        if (ret.intVal == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            val startX = roi.x.coerceIn(0, (sensorW / currentBin) - w)
            val startY = roi.y.coerceIn(0, (sensorH / currentBin) - h)
            cam.setStartPos(startX, startY)

            currentRoi = Roi(startX, startY, w, h)
            cropInfo = CropInfo(0, 0, w, h)
            FileLogger.i(TAG, "ROI set: ${w}x${h}@($startX,$startY) bin=$currentBin")
        } else {
            FileLogger.w(TAG, "setRoiFormat failed: ${ret.intVal}")
        }

        if (wasCapturing && cb != null) startCapture(cb)
    }

    override fun resetRoi() {
        val maxW = sensorW / currentBin
        val maxH = sensorH / currentBin
        setRoi(Roi(0, 0, maxW, maxH))
    }

    override fun recycleBuffer(buf: ByteArray) {
        if (bufferPool.size < 8) bufferPool.offer(buf)
    }

    private fun getBuffer(size: Int): ByteArray {
        val pooled = bufferPool.poll()
        return if (pooled != null && pooled.size == size) pooled else ByteArray(size)
    }

    private fun captureLoop() {
        val cam = zwoCamera ?: return
        var frameSeq = 0L
        val bpp = currentPixelFormat.bytesPerPixel
        val maxBufSize = sensorW * sensorH * 3
        val imgBuf = ASIImageBuffer.allocate(maxBufSize)

        while (running.get()) {
            try {
                val w = currentRoi.width
                val h = currentRoi.height
                val frameBpp = currentPixelFormat.bytesPerPixel
                val expectedSize = w * h * frameBpp
                val bufSize = expectedSize.coerceAtLeast(w * h * 2)

                val ret = cam.getVideoData(imgBuf, bufSize, GRAB_TIMEOUT_MS)
                if (ret.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                    if (ret.intVal != ASIConstants.ASI_ERROR_CODE.ASI_ERROR_TIMEOUT) {
                        FileLogger.w(TAG, "getVideoData error: ${ret.intVal}")
                    }
                    continue
                }

                val bb = imgBuf.getmByteBuffer()
                bb.position(0)
                val outData = getBuffer(expectedSize)
                bb.get(outData, 0, expectedSize.coerceAtMost(outData.size))

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

            } catch (e: Exception) {
                FileLogger.e(TAG, "Capture error: ${e.message}")
                if (!running.get()) break
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
            }
        }
        FileLogger.i(TAG, "Capture loop ended, $frameSeq frames captured")
    }

    private fun readExposureRange(cam: ZwoCamera) {
        val capRet = cam.getControlCaps(ASI_EXPOSURE)
        if (capRet.errorCode.intVal == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            val cap = capRet.obj as ASIControlCap
            val min = cap.minValue.toFloat().coerceAtLeast(1f)
            val max = cap.maxValue.toFloat()
            val def = cap.defaultValue.toFloat().coerceIn(min, max)
            hwExposureMaxUs = max
            exposureRange = FloatRange(min, max, def)
            currentExposureUs = def
            FileLogger.i(TAG, "Exposure range: ${min.toInt()}-${max.toInt()} us")
        }
    }

    private fun readGainRange(cam: ZwoCamera) {
        val capRet = cam.getControlCaps(ASI_GAIN)
        if (capRet.errorCode.intVal == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
            val cap = capRet.obj as ASIControlCap
            val min = cap.minValue.toFloat()
            val max = cap.maxValue.toFloat()
            val def = cap.defaultValue.toFloat().coerceIn(min, max)
            gainRange = FloatRange(min, max, def)
            currentGain = def
            FileLogger.i(TAG, "Gain range: $min-$max (default=$def)")
        }
    }

    private fun readOffsetRange(cam: ZwoCamera) {
        val capRet = cam.getControlCaps(ASI_BRIGHTNESS)
        if (capRet.errorCode.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) return

        val cap = capRet.obj as ASIControlCap
        if (cap.isWritable == 0) return

        val min = cap.minValue.toFloat()
        val max = cap.maxValue.toFloat()
        val valueRet = cam.getControlValue(ASI_BRIGHTNESS)
        if (valueRet.errorCode.intVal != ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) return

        offsetSupported = true
        currentOffset = valueRet.extraLongVal1.toFloat().coerceIn(min, max)
        offsetRange = FloatRange(min, max, currentOffset)
        FileLogger.i(TAG, "Offset range: $min-$max (current=$currentOffset)")
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

    private fun configureInitialFormat(cam: ZwoCamera) {
        currentImgType = if (!isColor) ASI_IMG_RAW8 else ASI_IMG_RAW8
        currentPixelFormat = if (!isColor) PixelFormat.MONO8 else defaultBayer8()

        val w = (sensorW / 8) * 8
        val h = (sensorH / 2) * 2
        cam.setRoiFormat(w, h, 1, currentImgType)
        currentBin = 1
        FileLogger.i(TAG, "Initial format: ${currentPixelFormat.name} (imgType=$currentImgType) ${w}x${h}")
    }

    private fun readSerialNumber(cam: ZwoCamera): String {
        return try {
            val idRet = cam.getID()
            if (idRet.errorCode.intVal == ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS) {
                val idBytes = idRet.obj
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
        !isColor && imgType == ASI_IMG_RAW16 -> PixelFormat.MONO16
        !isColor && imgType == ASI_IMG_Y8 -> PixelFormat.MONO8
        isColor && imgType == ASI_IMG_RAW8 -> defaultBayer8()
        isColor && imgType == ASI_IMG_RAW16 -> when (bayerPattern) {
            ASI_BAYER_RG -> PixelFormat.BAYER_RG16
            ASI_BAYER_BG -> PixelFormat.BAYER_BG16
            ASI_BAYER_GR -> PixelFormat.BAYER_GR16
            ASI_BAYER_GB -> PixelFormat.BAYER_GB16
            else -> PixelFormat.BAYER_RG16
        }
        else -> if (!isColor) PixelFormat.MONO8 else defaultBayer8()
    }

    private fun pixelFormatToAsiImgType(format: PixelFormat): Int = when (format) {
        PixelFormat.MONO8 -> ASI_IMG_RAW8
        PixelFormat.MONO16 -> ASI_IMG_RAW16
        PixelFormat.BAYER_RG8, PixelFormat.BAYER_GR8, PixelFormat.BAYER_GB8, PixelFormat.BAYER_BG8 -> ASI_IMG_RAW8
        PixelFormat.BAYER_RG16, PixelFormat.BAYER_GR16, PixelFormat.BAYER_GB16, PixelFormat.BAYER_BG16 -> ASI_IMG_RAW16
        else -> ASI_IMG_RAW8
    }
}
