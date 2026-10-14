package com.indigo.mobileobservatory.camera

import android.content.Context
import android.app.ActivityManager
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
import android.util.Range
import android.util.Size
import android.view.Surface
import com.indigo.mobileobservatory.pointing.CameraLensCalibration
import com.indigo.mobileobservatory.pointing.LensCalibrationCoordinateDomain
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    val fov: CameraFovEstimate?,
    val observationStamp: com.indigo.mobileobservatory.pointing.ObservationStamp? = null,
    /** Camera2 crop above is unchanged; this is the exact rectangle retained by reduction. */
    val solveFootprint: CameraPixelFootprint? = null,
    val solveReduction: PhoneMonoReduction? = null
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
        require(maxLongSide in 1..8192)
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
        val reduction = PhoneMonoFrameConverter.plan(outputSize.width, outputSize.height, maxLongSide, useRaw)
        val monoBpp = if (useRaw) 2 else 1
        PhoneSkyResourceBudget.requireCaptureHeap(reduction, monoBpp, frameCount)
        val memoryInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
        if (memoryInfo.lowMemory) throw PhoneSkyResourceException(PhoneSkyResourceLimit.CAMERA_BUFFERS)
        PhoneSkyResourceBudget.requireNativeCapture(outputSize.width, outputSize.height, useRaw, memoryInfo.availMem)
        val solveStorage = context.getExternalFilesDir(null) ?: context.filesDir
        val solveBytes = PhoneSkyResourceBudget.frameBytes(reduction.outputWidth, reduction.outputHeight, monoBpp) * 2 + 1_048_576
        PhoneSkyResourceBudget.requireStorage(solveStorage.usableSpace, outputSize.width, outputSize.height, 0, solveBytes)
        if (useRaw && dngOutputDirectory != null) {
            if (!dngOutputDirectory.isDirectory && !dngOutputDirectory.mkdirs()) throw PhoneSkyResourceException(PhoneSkyResourceLimit.STORAGE)
            PhoneSkyResourceBudget.requireStorage(dngOutputDirectory.usableSpace, outputSize.width, outputSize.height, frameCount, solveBytes)
        }

        val exposureRange = openChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: openCapability.exposureTimeRangeNs
            ?: throw IllegalStateException("No exposure time range")
        val isoRange = openChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: openCapability.isoRange
            ?: throw IllegalStateException("No ISO range")
        val maxFrameDuration = openChars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            ?: openCapability.maxFrameDurationNs ?: 0L
        val maxNs = PhoneManualExposure.usableMaxExposureNs(exposureRange.upper, maxFrameDuration)
        val exposureNs = (exposureSeconds * 1_000_000_000.0).toLong()
            .coerceIn(exposureRange.lower, maxNs)
        val autoIso = iso == PhoneManualExposure.ISO_AUTO

        val thread = HandlerThread("PhoneSkyCapture").also { it.start() }
        val handler = Handler(thread.looper)
        val format = if (useRaw) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
        val reader = try { ImageReader.newInstance(outputSize.width, outputSize.height, format, 2) }
        catch (_: OutOfMemoryError) { thread.quitSafely(); throw PhoneSkyResourceException(PhoneSkyResourceLimit.CAMERA_BUFFERS) }
        catch (failure: Throwable) { thread.quitSafely(); throw failure }
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        try {
            val openStart = System.nanoTime()
            device = openCamera(openId, handler)
            val physicalId = capability.logicalParentId?.let { capability.cameraId }
            session = createSession(device, reader.surface, physicalId, handler)
            val sessionOpenLatencyMs = (System.nanoTime() - openStart) / 1_000_000

            val captures = ArrayList<PhoneSkyCaptureResult>(frameCount)
            val sensitivity = if (!autoIso) {
                iso.coerceIn(isoRange.lower, isoRange.upper)
            } else {
                meterSensitivity(
                    device = device,
                    session = session,
                    reader = reader,
                    handler = handler,
                    targetExposureNs = exposureNs,
                    isoRange = isoRange,
                    chars = openChars
                )
            }
            repeat(frameCount) { index ->
                currentCoroutineContext().ensureActive()
                PhoneSkyResourceBudget.requireCaptureHeap(reduction, monoBpp, frameCount - index)
                PhoneSkyResourceBudget.requireStorage(solveStorage.usableSpace, outputSize.width, outputSize.height, 0, solveBytes)
                if (useRaw && dngOutputDirectory != null) PhoneSkyResourceBudget.requireStorage(
                    dngOutputDirectory.usableSpace, outputSize.width, outputSize.height, frameCount - index, solveBytes)
                val captureStart = System.nanoTime()
                val (image, result) = stillCapture(
                    device = device,
                    session = session,
                    reader = reader,
                    handler = handler,
                    exposureNs = exposureNs,
                    iso = sensitivity,
                    autoIso = false,
                    chars = openChars
                )
                val captureLatencyMs = (System.nanoTime() - captureStart) / 1_000_000
                var dngPath: String? = null
                var dngError: String? = null
                val captureContext = currentCoroutineContext()
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
                        rawToMono16Frame(image, frameId = System.currentTimeMillis(), maxLongSide = maxLongSide,
                            checkCancelled = { captureContext.ensureActive() })
                    } else {
                        yuvToMono8Frame(image, frameId = System.currentTimeMillis(), maxLongSide = maxLongSide,
                            checkCancelled = { captureContext.ensureActive() })
                    }
                    val metadata = captureMetadata(
                        capability, openId, physicalId, result, frame.width, frame.height, useRaw,
                        PhoneMonoFrameConverter.plan(image.width, image.height, maxLongSide, useRaw)
                    )
                    val actualExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
                    val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: sensitivity
                    captures += PhoneSkyCaptureResult(
                        frame = frame,
                        capability = capability,
                        usedRaw = useRaw,
                        exposureNs = actualExposureNs,
                        iso = if (actualIso > 0) actualIso else sensitivity,
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
        usedRaw: Boolean,
        reduction: PhoneMonoReduction
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
        val correctionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lensResult?.get(CaptureResult.DISTORTION_CORRECTION_MODE)
        } else null
        val domain = when {
            usedRaw && preCorrection != null && rawCrop != null -> LensCalibrationCoordinateDomain.PRE_CORRECTION
            preCorrection != null && correctionMode == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF ->
                LensCalibrationCoordinateDomain.PRE_CORRECTION
            correctionMode == CaptureRequest.DISTORTION_CORRECTION_MODE_FAST ||
                correctionMode == CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY ->
                LensCalibrationCoordinateDomain.POST_CORRECTION
            else -> LensCalibrationCoordinateDomain.UNKNOWN
        }
        val crop = when (domain) {
            LensCalibrationCoordinateDomain.PRE_CORRECTION -> rawCrop ?: postCrop
            else -> postCrop
        }
        val coordinateArray = when (domain) {
            LensCalibrationCoordinateDomain.PRE_CORRECTION -> preCorrection
            LensCalibrationCoordinateDomain.POST_CORRECTION, LensCalibrationCoordinateDomain.UNKNOWN -> active
        }
        val focal = lensResult?.get(CaptureResult.LENS_FOCAL_LENGTH)?.toDouble()
            ?: capability.focalLengthMm?.toDouble()
        val footprint = crop?.let { reduction.footprint(it.left.toDouble(), it.top.toDouble(), it.width().toDouble(), it.height().toDouble(), usedRaw) }
        val fov = if (lensResult != null && coordinateArray != null && crop != null && focal != null &&
            (!usedRaw || domain != LensCalibrationCoordinateDomain.UNKNOWN)
        ) {
            CameraFovCalculator.estimate(CameraFovInput(
                focalLengthMm = focal,
                sensorWidthMm = capability.sensorWidthMm?.toDouble() ?: 0.0,
                sensorHeightMm = capability.sensorHeightMm?.toDouble() ?: 0.0,
                activeWidthPx = coordinateArray.width(), activeHeightPx = coordinateArray.height(),
                cropLeftPx = crop.left, cropTopPx = crop.top,
                cropWidthPx = crop.width(), cropHeightPx = crop.height(),
                outputWidthPx = outputWidth, outputHeightPx = outputHeight,
                sampledFootprint = footprint
            ))
        } else null
        val timestampNs = lensResult?.get(CaptureResult.SENSOR_TIMESTAMP)
        val clockBefore = android.os.SystemClock.elapsedRealtimeNanos()
        val clockUtc = System.currentTimeMillis()
        val clockAfter = android.os.SystemClock.elapsedRealtimeNanos()
        val stamp = com.indigo.mobileobservatory.pointing.ObservationClock.fromRealtimeSensor(
            timestampNs, lensResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            capability.timestampIsRealtime, clockUtc, clockBefore + (clockAfter - clockBefore) / 2,
            (clockAfter - clockBefore + 1_999_999L) / 2_000_000L + 1)
        val frameCalibration = if (lensResult != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            calibrationFrom(
                lensResult.get(CaptureResult.LENS_INTRINSIC_CALIBRATION),
                lensResult.get(CaptureResult.LENS_DISTORTION)
            )
        } else null
        val calibration = frameCalibration ?: capability.lensCalibration
        return PhoneCaptureMetadata(
            logicalCameraId = openCameraId,
            physicalCameraId = physicalCameraId ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null,
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
            distortionCorrectionMode = correctionMode,
            calibrationCoordinateDomain = domain,
            lensCalibration = calibration,
            exposureMidpointEpochMs = stamp?.utcMidpointMs,
            fov = fov,
            observationStamp = stamp,
            solveFootprint = footprint,
            solveReduction = reduction
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
        return PhoneMonoFrameConverter.chooseStreamIndex(sizes.map { it.width to it.height }, maxLongSide)?.let(sizes::get)
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

    private suspend fun meterSensitivity(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        handler: Handler,
        targetExposureNs: Long,
        isoRange: Range<Int>,
        chars: CameraCharacteristics
    ): Int {
        val (image, result) = stillCapture(
            device = device,
            session = session,
            reader = reader,
            handler = handler,
            exposureNs = targetExposureNs,
            iso = isoRange.upper,
            autoIso = true,
            chars = chars
        )
        try {
            val meteredIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: isoRange.upper
            val meteredExp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            if (meteredExp <= 0L) return meteredIso.coerceIn(isoRange.lower, isoRange.upper)
            val scaled = (meteredIso.toLong() * meteredExp / targetExposureNs)
                .toInt()
                .coerceIn(isoRange.lower, isoRange.upper)
            return scaled
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun stillCapture(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        handler: Handler,
        exposureNs: Long,
        iso: Int,
        chars: CameraCharacteristics,
        autoIso: Boolean = false
    ): Pair<Image, TotalCaptureResult> = suspendCancellableCoroutine { cont ->
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(reader.surface)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        if (autoIso) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        }
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
        /** Average complete even Bayer blocks into a bounded solve frame; DNG stays untouched. */
        fun rawToMono16Frame(image: Image, frameId: Long, maxLongSide: Int = 2048, checkCancelled: () -> Unit = {}): FrameData {
            val plane = image.planes[0]
            return PhoneMonoFrameConverter.convert(plane.buffer, plane.rowStride, plane.pixelStride,
                PhoneMonoFrameConverter.plan(image.width, image.height, maxLongSide, true), true, frameId, System.nanoTime(), checkCancelled)
        }

        fun yuvToMono8Frame(image: Image, frameId: Long, maxLongSide: Int = 2048, checkCancelled: () -> Unit = {}): FrameData {
            val yPlane = image.planes[0]
            return PhoneMonoFrameConverter.convert(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride,
                PhoneMonoFrameConverter.plan(image.width, image.height, maxLongSide, false), false, frameId, System.nanoTime(), checkCancelled)
        }
    }
}
