package com.indigo.mobileobservatory.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.indigo.mobileobservatory.pointing.CameraLensCalibration
import com.indigo.mobileobservatory.pointing.LensCalibrationCoordinateDomain
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

data class PhoneSkyCaptureResult(
    val frame: FrameData,
    val capability: PhoneCameraCapability,
    val usedRaw: Boolean,
    val exposureNs: Long,
    val iso: Int,
    val outputSize: Size,
    val fovWidthDeg: Double?,
    val fovHeightDeg: Double?,
    val sessionOpenLatencyMs: Long,
    val captureLatencyMs: Long,
    val metadata: PhoneCaptureMetadata,
    /** Written during capture, while the RAW [Image] is still valid. */
    val dngPath: String? = null,
    val dngError: String? = null
)

/** Actual Camera2 values attached to an individual still frame for solve diagnostics. */
data class PhoneCaptureMetadata(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val focalLengthMm: Double?,
    val sensorWidthMm: Double?,
    val sensorHeightMm: Double?,
    val activeArrayLeftPx: Int?,
    val activeArrayTopPx: Int?,
    val activeArrayWidthPx: Int?,
    val activeArrayHeightPx: Int?,
    val preCorrectionActiveArrayLeftPx: Int?,
    val preCorrectionActiveArrayTopPx: Int?,
    val preCorrectionActiveArrayWidthPx: Int?,
    val preCorrectionActiveArrayHeightPx: Int?,
    val cropLeftPx: Int?,
    val cropTopPx: Int?,
    val cropWidthPx: Int?,
    val cropHeightPx: Int?,
    val sensorOrientation: Int,
    val distortionCorrectionMode: Int?,
    val calibrationCoordinateDomain: LensCalibrationCoordinateDomain,
    val lensCalibration: CameraLensCalibration?,
    val exposureMidpointEpochMs: Long?,
    val fov: CameraFovEstimate?
)

/** A short-exposure sequence captured while one Camera2 session remains open. */
data class PhoneSkyBurstCaptureResult(
    val captures: List<PhoneSkyCaptureResult>,
    val requestedFrameCount: Int,
    val sessionOpenLatencyMs: Long
) {
    init {
        require(captures.isNotEmpty()) { "A burst must contain at least one frame" }
    }

    val first: PhoneSkyCaptureResult get() = captures.first()
}

/**
 * One-shot Camera2 full-manual capture for M0 sky feasibility.
 * Prefer RAW_SENSOR; fall back to YUV_420_888 luma. Closes the camera after each shot
 * so the debug loop exercises real session open/close latency.
 */
class PhoneSkyCapture(private val context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    suspend fun capture(
        exposureSeconds: Double,
        iso: Int,
        preferRaw: Boolean = true,
        maxLongSide: Int = 2048,
        cameraId: String? = null,
        dngOutputFile: File? = null
    ): PhoneSkyCaptureResult {
        return captureBurst(
            exposureSeconds = exposureSeconds,
            iso = iso,
            frameCount = 1,
            preferRaw = preferRaw,
            maxLongSide = maxLongSide,
            cameraId = cameraId,
            dngOutputDirectory = dngOutputFile?.parentFile
        ).first
    }

    /**
     * Captures [frameCount] frames without reopening the camera between frames. This is the
     * phone-main-camera path for devices whose manual exposure is limited to roughly 0.5 s.
     */
    suspend fun captureBurst(
        exposureSeconds: Double,
        iso: Int,
        frameCount: Int,
        preferRaw: Boolean = true,
        maxLongSide: Int = 2048,
        cameraId: String? = null,
        dngOutputDirectory: File? = null,
        onFrameCaptured: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): PhoneSkyBurstCaptureResult {
        require(frameCount in 1..16) { "frameCount must be in 1..16" }
        val capability = if (cameraId != null) {
            resolveCapability(cameraId)
        } else {
            PhoneCameraCapability.probeBackCamera(context)
                ?: throw IllegalStateException("No camera available")
        }
        if (capability.isPhysicalSubCamera && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw IllegalStateException(
                "Physical lens ${capability.displayLabel} needs Android 9+; pick a logical camera"
            )
        }
        // Manual sensor / stream config are governed by the device we actually open.
        val openId = capability.openableCameraId
        val openChars = manager.getCameraCharacteristics(openId)
        val openCapability = if (openId == capability.cameraId) {
            capability
        } else {
            PhoneCameraCapability.probe(context, openId)
        }
        if (!openCapability.supportsManualSensor) {
            throw IllegalStateException(
                "Camera ${capability.displayLabel} lacks MANUAL_SENSOR; try another lens"
            )
        }

        val useRaw = preferRaw && openCapability.supportsRaw && openCapability.rawOutputSizes.isNotEmpty()
        val outputSize = chooseSize(
            if (useRaw) openCapability.rawOutputSizes else openCapability.yuvOutputSizes,
            maxLongSide
        ) ?: throw IllegalStateException("No suitable output size")

        val exposureRange = openCapability.exposureTimeRangeNs
            ?: throw IllegalStateException("No exposure time range")
        val isoRange = openCapability.isoRange
            ?: throw IllegalStateException("No ISO range")
        val exposureNs = (exposureSeconds * 1_000_000_000.0).toLong()
            .coerceIn(exposureRange.lower, exposureRange.upper)
        val sensitivity = iso.coerceIn(isoRange.lower, isoRange.upper)

        val thread = HandlerThread("PhoneSkyCapture").also { it.start() }
        val handler = Handler(thread.looper)
        val format = if (useRaw) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
        val reader = ImageReader.newInstance(outputSize.width, outputSize.height, format, 2)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        try {
            val openStart = System.nanoTime()
            device = openCamera(openId, handler)
            val physicalId = capability.logicalParentId?.let { capability.cameraId }
            session = createSession(device, reader.surface, physicalId, handler)
            val sessionOpenLatencyMs = (System.nanoTime() - openStart) / 1_000_000

            val captures = ArrayList<PhoneSkyCaptureResult>(frameCount)
            repeat(frameCount) { index ->
                val captureStart = System.nanoTime()
                val (image, result) = stillCapture(
                    device = device,
                    session = session,
                    reader = reader,
                    handler = handler,
                    exposureNs = exposureNs,
                    iso = sensitivity,
                    chars = openChars
                )
                val captureLatencyMs = (System.nanoTime() - captureStart) / 1_000_000
                var dngPath: String? = null
                var dngError: String? = null
                try {
                    val frame = if (useRaw) {
                        val dngFile = dngOutputDirectory?.let { directory ->
                            File(directory, "phone_sky_${System.currentTimeMillis()}_${index + 1}.dng")
                        }
                        if (dngFile != null) {
                            try {
                                writeDng(openChars, result, image, dngFile)
                                dngPath = dngFile.absolutePath
                            } catch (t: Throwable) {
                                dngError = t.message ?: t.javaClass.simpleName
                            }
                        }
                        rawToMono16Frame(image, frameId = System.currentTimeMillis())
                    } else {
                        yuvToMono8Frame(image, frameId = System.currentTimeMillis())
                    }
                    val metadata = captureMetadata(
                        capability, openId, physicalId, result, frame.width, frame.height, useRaw
                    )
                    captures += PhoneSkyCaptureResult(
                        frame = frame,
                        capability = capability,
                        usedRaw = useRaw,
                        exposureNs = exposureNs,
                        iso = sensitivity,
                        outputSize = outputSize,
                        fovWidthDeg = metadata.fov?.widthDeg,
                        fovHeightDeg = metadata.fov?.heightDeg,
                        sessionOpenLatencyMs = sessionOpenLatencyMs,
                        captureLatencyMs = captureLatencyMs,
                        metadata = metadata,
                        dngPath = dngPath,
                        dngError = dngError
                    )
                    onFrameCaptured(index + 1, frameCount)
                } finally {
                    try {
                        image.close()
                    } catch (_: Exception) {
                    }
                }
            }
            return PhoneSkyBurstCaptureResult(captures, frameCount, sessionOpenLatencyMs)
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {
            }
            try {
                device?.close()
            } catch (_: Exception) {
            }
            reader.close()
            thread.quitSafely()
        }
    }

    private fun resolveCapability(cameraId: String): PhoneCameraCapability {
        val enumerated = runCatching { PhoneCameraCapability.enumerateBackCameras(context) }
            .getOrDefault(emptyList())
        return enumerated.firstOrNull { it.cameraId == cameraId }
            ?: PhoneCameraCapability.probe(context, cameraId)
    }

    private fun captureMetadata(
        capability: PhoneCameraCapability,
        openCameraId: String,
        physicalCameraId: String?,
        result: TotalCaptureResult,
        outputWidth: Int,
        outputHeight: Int,
        usedRaw: Boolean
    ): PhoneCaptureMetadata {
        val physicalResult = if (physicalCameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            result.physicalCameraResults[physicalCameraId]
        } else null
        // A logical multi-camera result can describe a different active lens. Lens-specific
        // metadata is only trusted when Camera2 returned the requested physical result.
        val lensResult: CaptureResult? = physicalResult ?: result.takeIf { physicalCameraId == null }
        val active = capability.activeArraySize
        val preCorrection = capability.preCorrectionActiveArraySize
        val postCrop = lensResult?.get(CaptureResult.SCALER_CROP_REGION) ?: active
        val rawCrop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            lensResult?.get(CaptureResult.SCALER_RAW_CROP_REGION)
        } else null
        val domain = when {
            usedRaw && preCorrection != null && rawCrop != null -> LensCalibrationCoordinateDomain.PRE_CORRECTION
            else -> LensCalibrationCoordinateDomain.UNKNOWN
        }
        val crop = when (domain) {
            LensCalibrationCoordinateDomain.PRE_CORRECTION -> rawCrop
            else -> postCrop
        }
        val focal = lensResult?.get(CaptureResult.LENS_FOCAL_LENGTH)?.toDouble()
            ?: capability.focalLengthMm?.toDouble()
        val fov = if (lensResult != null && active != null && crop != null && focal != null) {
            CameraFovCalculator.estimate(CameraFovInput(
                focalLengthMm = focal,
                sensorWidthMm = capability.sensorWidthMm?.toDouble() ?: 0.0,
                sensorHeightMm = capability.sensorHeightMm?.toDouble() ?: 0.0,
                activeWidthPx = active.width(), activeHeightPx = active.height(),
                cropLeftPx = crop.left, cropTopPx = crop.top,
                cropWidthPx = crop.width(), cropHeightPx = crop.height(),
                outputWidthPx = outputWidth, outputHeightPx = outputHeight
            ))
        } else null
        val timestampNs = lensResult?.get(CaptureResult.SENSOR_TIMESTAMP)
        val midpointMs = timestampNs?.let {
            System.currentTimeMillis() - (System.nanoTime() - it) / 1_000_000L +
                (lensResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 2_000_000L
        }
        val frameCalibration = if (lensResult != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            calibrationFrom(
                lensResult.get(CaptureResult.LENS_INTRINSIC_CALIBRATION),
                lensResult.get(CaptureResult.LENS_DISTORTION)
            )
        } else null
        val calibration = frameCalibration ?: capability.lensCalibration
        return PhoneCaptureMetadata(
            logicalCameraId = openCameraId,
            physicalCameraId = physicalCameraId,
            focalLengthMm = focal,
            sensorWidthMm = capability.sensorWidthMm?.toDouble(), sensorHeightMm = capability.sensorHeightMm?.toDouble(),
            activeArrayLeftPx = active?.left, activeArrayTopPx = active?.top,
            activeArrayWidthPx = active?.width(), activeArrayHeightPx = active?.height(),
            preCorrectionActiveArrayLeftPx = preCorrection?.left,
            preCorrectionActiveArrayTopPx = preCorrection?.top,
            preCorrectionActiveArrayWidthPx = preCorrection?.width(),
            preCorrectionActiveArrayHeightPx = preCorrection?.height(),
            cropLeftPx = crop?.left, cropTopPx = crop?.top, cropWidthPx = crop?.width(), cropHeightPx = crop?.height(),
            sensorOrientation = capability.sensorOrientation,
            distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lensResult?.get(CaptureResult.DISTORTION_CORRECTION_MODE)
            } else null,
            calibrationCoordinateDomain = domain,
            lensCalibration = calibration,
            exposureMidpointEpochMs = midpointMs,
            fov = fov
        )
    }

    private fun calibrationFrom(intrinsic: FloatArray?, distortion: FloatArray?): CameraLensCalibration? {
        if (intrinsic?.size != 5 || distortion?.size != 5) return null
        return runCatching {
            CameraLensCalibration(
                focalX = intrinsic[0].toDouble(), focalY = intrinsic[1].toDouble(),
                principalX = intrinsic[2].toDouble(), principalY = intrinsic[3].toDouble(),
                skew = intrinsic[4].toDouble(), radialK1 = distortion[0].toDouble(),
                radialK2 = distortion[1].toDouble(), radialK3 = distortion[2].toDouble(),
                tangentialP1 = distortion[3].toDouble(), tangentialP2 = distortion[4].toDouble()
            )
        }.getOrNull()
    }

    private fun writeDng(
        chars: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        file: File
    ) {
        FileOutputStream(file).use { out ->
            DngCreator(chars, result).use { dng ->
                dng.writeImage(out, image)
            }
        }
    }

    private fun chooseSize(sizes: List<Size>, maxLongSide: Int): Size? {
        if (sizes.isEmpty()) return null
        val capped = sizes.filter { maxOf(it.width, it.height) <= maxLongSide }
        return (capped.ifEmpty { sizes }).maxByOrNull { it.width.toLong() * it.height }
    }

    private suspend fun openCamera(cameraId: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            try {
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (cont.isActive) cont.resume(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("Camera disconnected"))
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("Camera error $error"))
                        }
                    }
                }, handler)
            } catch (e: SecurityException) {
                cont.resumeWithException(e)
            } catch (e: CameraAccessException) {
                cont.resumeWithException(e)
            }
            cont.invokeOnCancellation { /* device closed in outer finally */ }
        }

    private suspend fun createSession(
        device: CameraDevice,
        surface: Surface,
        physicalCameraId: String?,
        handler: Handler
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resume(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("Capture session configure failed"))
                }
            }
        }
        try {
            if (physicalCameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val output = OutputConfiguration(surface).apply {
                    setPhysicalCameraId(physicalCameraId)
                }
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(output),
                        HandlerExecutor(handler),
                        callback
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface), callback, handler)
            }
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        } catch (e: IllegalArgumentException) {
            cont.resumeWithException(e)
        }
    }

    private class HandlerExecutor(private val handler: Handler) : Executor {
        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }

    private suspend fun stillCapture(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        handler: Handler,
        exposureNs: Long,
        iso: Int,
        chars: CameraCharacteristics
    ): Pair<Image, TotalCaptureResult> = suspendCancellableCoroutine { cont ->
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(reader.surface)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        // Frame duration must be ≥ exposure; 0 lets some OEM HALs clamp long exposures.
        val maxFrame = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L
        val frameDuration = if (maxFrame > 0L) {
            exposureNs.coerceAtMost(maxFrame)
        } else {
            exposureNs
        }
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
        // Infinity focus: 0.0f diopters.
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                ?.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) == true
        ) {
            builder.set(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
            )
        }

        val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        if (nrModes != null && nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF)) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        }
        val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        if (edgeModes != null && edgeModes.contains(CaptureRequest.EDGE_MODE_OFF)) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (ois != null && ois.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        var image: Image? = null
        var result: TotalCaptureResult? = null
        fun tryComplete() {
            val img = image
            val res = result
            if (img != null && res != null && cont.isActive) {
                cont.resume(img to res)
            }
        }

        reader.setOnImageAvailableListener({ r ->
            try {
                image = r.acquireNextImage()
                tryComplete()
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }, handler)

        try {
            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        resultIn: TotalCaptureResult
                    ) {
                        result = resultIn
                        tryComplete()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("Capture failed reason=${failure.reason}")
                            )
                        }
                    }
                },
                handler
            )
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        }
    }

    companion object {
        /** Average 2×2 Bayer neighborhood into MONO16 at half resolution. */
        fun rawToMono16Frame(image: Image, frameId: Long): FrameData {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val outW = width / 2
            val outH = height / 2
            val out = ByteArray(outW * outH * 2)
            val row = ByteArray(rowStride)
            for (y in 0 until outH) {
                val y0 = y * 2
                buffer.position(y0 * rowStride)
                buffer.get(row, 0, min(rowStride, buffer.remaining()))
                val row1 = ByteArray(rowStride)
                if (y0 + 1 < height) {
                    buffer.position((y0 + 1) * rowStride)
                    buffer.get(row1, 0, min(rowStride, buffer.remaining()))
                }
                for (x in 0 until outW) {
                    val x0 = x * 2
                    fun sample(r: ByteArray, xx: Int): Int {
                        val i = xx * pixelStride
                        if (i + 1 >= r.size) return 0
                        return (r[i].toInt() and 0xFF) or ((r[i + 1].toInt() and 0xFF) shl 8)
                    }
                    val a = sample(row, x0)
                    val b = sample(row, x0 + 1)
                    val c = sample(row1, x0)
                    val d = sample(row1, x0 + 1)
                    val avg = ((a + b + c + d) / 4).coerceIn(0, 65535)
                    val o = (y * outW + x) * 2
                    out[o] = (avg and 0xFF).toByte()
                    out[o + 1] = ((avg shr 8) and 0xFF).toByte()
                }
            }
            return FrameData(out, outW, outH, PixelFormat.MONO16, frameId, System.nanoTime())
        }

        fun yuvToMono8Frame(image: Image, frameId: Long): FrameData {
            val yPlane = image.planes[0]
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val buffer = yPlane.buffer
            val width = image.width
            val height = image.height
            val out = ByteArray(width * height)
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                val toRead = min(rowStride, buffer.remaining())
                buffer.get(row, 0, toRead)
                if (pixelStride == 1) {
                    System.arraycopy(row, 0, out, y * width, width)
                } else {
                    var o = y * width
                    var i = 0
                    repeat(width) {
                        out[o++] = row[i]
                        i += pixelStride
                    }
                }
            }
            return FrameData(out, width, height, PixelFormat.MONO8, frameId, System.nanoTime())
        }
    }
}
