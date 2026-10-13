package com.indigo.mobileobservatory.pointing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.camera.PhoneSkyCapture
import com.indigo.mobileobservatory.camera.PhoneSkyCaptureStore
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.recording.FITSWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class PhoneSkySolveResult(
    val success: Boolean,
    val message: String,
    val fix: SkyAttitudeFix? = null,
    /** Photographic solution, available even when the handset has no usable IMU sample. */
    val skySolution: WideFieldSolveResult? = null,
    val fitsPath: String? = null,
    val frame: FrameData? = null,
    val extraction: StarExtractionResult? = null,
    val cameraLabel: String? = null,
    val inputFrameCount: Int = 1,
    val observation: OpticalObservation? = null,
    val exposureMotionDeg: Double? = null,
    val handEyeSample: HandEyeSample? = null
) {
    init {
        require(inputFrameCount >= 0)
        require(inputFrameCount == 1 || (observation == null && handEyeSample == null)) {
            "Only a single contributing exposure can be published as timed optical evidence"
        }
    }
    /** A multi-exposure image has no single timed pose until celestial-time reprojection exists. */
    val requiresSingleFrameVerification get() = success && inputFrameCount > 1
}

enum class PhoneSkySolveStage {
    CAPTURING,
    EXTRACTING_STARS,
    SOLVING,
    COMPLETE
}

/**
 * Live phone pointing source. The back-camera optical axis is read from the Android rotation
 * vector, then a successful phone-camera plate solve aligns that axis to the solved sky direction.
 */
class PhoneSkyAttitudeSource(context: Context) : SkyAttitudeSource, SensorEventListener {
    override val id: String = "phone"
    override val displayName: String = "Phone camera + IMU"

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val alignment = PhonePoseAlignment()
    private var observerSite: ObserverSite? = null
    private var activeLensKey: String? = null
    private var sensorGapInvalidated = false
    private var anchorHandEyeUncertaintyDeg = 0.0
    private val sensorStreamIdentity = UUID.randomUUID().toString()
    @Volatile private var sensorSessionRevision = 0L
    var currentPose: SkyPose? = null
        private set
    val latestObservation: OpticalObservation? get() = alignment.observation

    @Volatile
    private var rawDirection: Direction3? = null

    @Volatile
    private var lastRawDirection: Direction3? = null

    @Volatile
    private var lastRotationMatrix: FloatArray? = null

    @Volatile
    private var lastSensorTimestampNs: Long = 0L

    @Volatile
    private var fix: SkyAttitudeFix? = null

    val motionGate = MotionGate()
    private val attitudeTimeline = AttitudeTimeline(capacity = 12_000)

    @Volatile
    private var lastSensorOrientationDeg: Int = 90

    var onFix: ((SkyAttitudeFix) -> Unit)? = null
    var onMotion: ((MotionPhase) -> Unit)? = null

    val available: Boolean get() = rotationSensor != null
    val plateSolved: Boolean get() = alignment.observation != null && currentPose != null

    private fun clearOpticalAnchor() {
        sensorSessionRevision++
        alignment.clear(); currentPose = null; fix = null; attitudeTimeline.clear()
        anchorHandEyeUncertaintyDeg = 0.0
    }

    fun prepareLens(cameraId: String, sensorOrientationDeg: Int) {
        if (activeLensKey != cameraId || lastSensorOrientationDeg != sensorOrientationDeg) {
            clearOpticalAnchor()
            lastRawDirection = null; lastSensorTimestampNs = 0
            rawDirection = null; lastRotationMatrix = null
            activeLensKey = cameraId; lastSensorOrientationDeg = sensorOrientationDeg
        }
    }

    fun start(): Boolean {
        val sensor = rotationSensor ?: return false
        clearOpticalAnchor()
        motionGate.invalidateForDiscontinuity(SystemClock.elapsedRealtime())
        onMotion?.invoke(motionGate.phase)
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        clearOpticalAnchor()
        lastRawDirection = null
        lastSensorTimestampNs = 0
        rawDirection = null
        lastRotationMatrix = null
    }

    override fun latestFix(): SkyAttitudeFix? = fix

    fun refreshCurrentPose(utcNowMs: Long = System.currentTimeMillis()) {
        if (lastSensorTimestampNs > 0 && SystemClock.elapsedRealtimeNanos() - lastSensorTimestampNs > 500_000_000L && !sensorGapInvalidated) {
            sensorGapInvalidated = true
            clearOpticalAnchor()
            lastRawDirection = null; lastSensorTimestampNs = 0
            rawDirection = null; lastRotationMatrix = null
            motionGate.invalidateForDiscontinuity(SystemClock.elapsedRealtime())
            onMotion?.invoke(motionGate.phase)
        }
        rawDirection?.let { publish(it, utcNowMs) }
    }

    fun motionBetween(startElapsedMs: Long, endElapsedMs: Long): Double? =
        attitudeTimeline.motionDuring(startElapsedMs, endElapsedMs)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        sensorGapInvalidated = false
        val previous = lastRawDirection
        val previousNs = lastSensorTimestampNs
        val dtSec = if (previousNs > 0) (event.timestamp - previousNs) / 1_000_000_000.0 else null
        if (dtSec != null && (dtSec > .5 || dtSec <= 0)) {
            clearOpticalAnchor()
            motionGate.invalidateForDiscontinuity(SystemClock.elapsedRealtime())
            onMotion?.invoke(motionGate.phase)
        }
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)

        // Android's rotation matrix maps device axes to east/north/up. The rear camera looks
        // along device -Z, so its world direction is the negated third matrix column.
        val raw = Direction3(
            east = -matrix[2].toDouble(),
            north = -matrix[5].toDouble(),
            up = -matrix[8].toDouble()
        ).unit()
        rawDirection = raw
        lastRotationMatrix = matrix.copyOf()
        val imageUp = CameraAttitudePrior.imageAxesEnu(matrix, lastSensorOrientationDeg).imageUp
        val previousPose = attitudeTimeline.latest()?.imuPose()
        val sensorSnapshot = AttitudeSnapshot(
                timestampMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L,
                opticalAxis = raw,
                imageUp = imageUp,
                elapsedMs = event.timestamp / 1_000_000L
            )
        attitudeTimeline.add(sensorSnapshot)
        lastRawDirection = raw
        lastSensorTimestampNs = event.timestamp
        if (previous != null && dtSec != null) {
            if (dtSec in 0.001..0.5) {
                val currentSensorPose = sensorSnapshot.imuPose()
                if (previousPose != null && currentSensorPose != null) {
                    val rate = rotationDistanceDeg(previousPose, currentSensorPose) / dtSec
                    val phase = motionGate.ingest(rate, SystemClock.elapsedRealtime())
                    onMotion?.invoke(phase)
                } else {
                    clearOpticalAnchor()
                    motionGate.invalidateForDiscontinuity(SystemClock.elapsedRealtime())
                    onMotion?.invoke(motionGate.phase)
                }
            }
        }
        publish(raw, System.currentTimeMillis())
    }

    suspend fun captureAndSolve(
        site: ObserverSite,
        exposureSeconds: Double = 2.0,
        iso: Int = 1600,
        cameraId: String? = null,
        preferRaw: Boolean = true,
        burstFrameCount: Int = 1,
        onProgress: (PhoneSkySolveStage) -> Unit = {},
        onCapture: (FrameData, StarExtractionResult, Int) -> Unit = { _, _, _ -> },
        onBurstProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        mirrored: Boolean = false,
        installationRevision: Long = 0,
        opticsRevision: Long = 0,
        handEyeCalibration: HandEyeCalibration? = null
    ): PhoneSkySolveResult {
        // Plate solving remains available without a rotation-vector sensor. In that case it
        // returns the photographic solution while live push-to orientation awaits an IMU sample.
        val captureStartedAt = System.currentTimeMillis()

        return try {
            observerSite = site
            val requestedLens = cameraId ?: "default"
            cameraId?.let { lens ->
                runCatching { com.indigo.mobileobservatory.camera.PhoneCameraCapability.enumerateBackCameras(appContext)
                    .firstOrNull { it.cameraId == lens } }.getOrNull()?.let { prepareLens(requestedLens, it.sensorOrientation) }
            }
            val rawAtCapture = rawDirection
            val matrixAtCapture = lastRotationMatrix
            val capturedSensorSession = sensorSessionRevision
            onProgress(PhoneSkySolveStage.CAPTURING)
            val directory = PhoneSkyCaptureStore.directory(appContext)
            val fitsFile = File(directory, "${PhoneSkyCaptureStore.newBaseName()}_push_to.fits")
            val burst = withContext(Dispatchers.Default) {
                PhoneSkyCapture(appContext).captureBurst(
                    exposureSeconds = exposureSeconds,
                    iso = iso,
                    preferRaw = preferRaw,
                    cameraId = cameraId,
                    frameCount = if (mirrored && handEyeCalibration?.valid != true) 1 else burstFrameCount,
                    onFrameCaptured = onBurstProgress
                )
            }
            val capture = burst.first
            val captureOrientationMatches = lastSensorOrientationDeg == capture.metadata.sensorOrientation
            if (!captureOrientationMatches) prepareLens(requestedLens, capture.metadata.sensorOrientation)
            val captureIdentity = capture.metadata.pointingIdentity(installationRevision, opticsRevision)
            val captureGeometry = capture.metadata.pointingGeometry(capture.frame.width, capture.frame.height, capture.usedRaw, mirrored)
            require(burst.captures.all { item ->
                item.metadata.pointingIdentity(installationRevision, opticsRevision) == captureIdentity &&
                    item.metadata.pointingGeometry(item.frame.width, item.frame.height, item.usedRaw, mirrored) == captureGeometry &&
                    item.frame.pixelFormat == capture.frame.pixelFormat &&
                    item.fovWidthDeg == capture.fovWidthDeg && item.fovHeightDeg == capture.fovHeightDeg
            }) { "Physical lens or image geometry changed during the burst" }
            val matchedHandEye = handEyeCalibration?.takeIf {
                it.valid && it.source == captureIdentity && it.geometry == captureGeometry
            }
            val extrinsic = matchedHandEye?.opticalInImu
            val firstStamp = capture.metadata.observationStamp
            val poses = burst.captures.map { item ->
                val stamp = item.metadata.observationStamp ?: return@map null
                if (firstStamp == null || !stamp.compatibleClock(firstStamp)) return@map null
                attitudeTimeline.sampleElapsed(stamp.elapsedMidpointMs)?.opticalPoseForCapture(mirrored, extrinsic)?.let { pose ->
                    BurstAttitude(Direction3(pose.forward.x, pose.forward.y, pose.forward.z),
                        Direction3(pose.up.x, pose.up.y, pose.up.z), mirrored)
                }
            }
            // A mirror image needs both measured X and timed poses to align multiple exposures.
            // Before calibration, or across a clock/sensor gap, solve the first photograph alone.
            val canAlignMirrorBurst = !mirrored || (extrinsic != null && poses.all { it != null } &&
                capture.fovWidthDeg?.let { it > 1.0 } == true && capture.fovHeightDeg?.let { it > 1.0 } == true)
            val includedCaptures = if (canAlignMirrorBurst) burst.captures else listOf(capture)
            val lastStamp = includedCaptures.last().metadata.observationStamp
            val exposureMotion = if (firstStamp != null && lastStamp != null) attitudeTimeline.motionDuring(
                firstStamp.elapsedMidpointMs - firstStamp.exposureDurationMs / 2,
                lastStamp.elapsedMidpointMs + lastStamp.exposureDurationMs / 2) else null
            val exposurePose = firstStamp?.let { attitudeTimeline.sampleElapsed(it.elapsedMidpointMs) }
            lastSensorOrientationDeg = capture.metadata.sensorOrientation
            val stacked = withContext(Dispatchers.Default) {
                ShortExposureStacker.stack(
                    frames = includedCaptures.map { it.frame },
                    attitudes = if (canAlignMirrorBurst) poses else poses.take(1),
                    fovWidthDeg = capture.fovWidthDeg,
                    fovHeightDeg = capture.fovHeightDeg
                )
            }
            onProgress(PhoneSkySolveStage.EXTRACTING_STARS)
            val extractionStarted = System.nanoTime()
            val extraction = withContext(Dispatchers.Default) {
                WideFieldStarExtractor.extractFromFrame(
                    frame = stacked.frame,
                    maxStars = 200,
                    fovWidthDeg = capture.fovWidthDeg,
                    fovHeightDeg = capture.fovHeightDeg
                )
            }
            val correctedExtraction = StarCoordinateUndistorter.correct(
                extraction = extraction,
                calibration = capture.metadata.lensCalibration,
                cropLeftPx = capture.metadata.cropLeftPx,
                cropTopPx = capture.metadata.cropTopPx,
                cropWidthPx = capture.metadata.cropWidthPx,
                cropHeightPx = capture.metadata.cropHeightPx,
                frameWidth = stacked.frame.width,
                frameHeight = stacked.frame.height,
                alreadyCorrectedByCamera = !capture.usedRaw && (capture.metadata.distortionCorrectionMode ==
                    android.hardware.camera2.CaptureRequest.DISTORTION_CORRECTION_MODE_FAST ||
                    capture.metadata.distortionCorrectionMode ==
                    android.hardware.camera2.CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY),
                coordinateDomain = capture.metadata.calibrationCoordinateDomain,
                sampledFootprint = capture.metadata.solveFootprint
            )
            val solveExtraction = if (!mirrored) correctedExtraction else correctedExtraction.copy(
                stars = correctedExtraction.stars.map { it.copy(x = stacked.frame.width - it.x) })
            val extractionNanos = System.nanoTime() - extractionStarted
            onCapture(stacked.frame, extraction, stacked.inputFrameCount)
            if (stacked.rejectedMotionFrames >= (burst.captures.size + 1) / 2 &&
                extraction.stars.size < 8
            ) {
                val moved = WideFieldSolveResult(
                    success = false,
                    message = "Burst frames moved during capture",
                    failure = WideFieldSolveFailure.DEVICE_MOTION
                )
                writeSolveDiagnostics(fitsFile, capture, moved, null)
                return PhoneSkySolveResult(
                    success = false,
                    message = moved.message,
                    skySolution = moved,
                    fitsPath = fitsFile.absolutePath,
                    frame = stacked.frame,
                    extraction = extraction,
                    cameraLabel = capture.capability.displayLabel,
                    inputFrameCount = stacked.inputFrameCount
                )
            }
            withContext(Dispatchers.IO) {
                FITSWriter().write(
                    file = fitsFile,
                    frame = stacked.frame,
                    exposureSeconds = (capture.exposureNs / 1e9 * stacked.inputFrameCount).toFloat(),
                    gain = capture.iso.toFloat(),
                    cameraName = "PhoneCamera/${capture.capability.displayLabel}"
                )
                PhoneSkyCaptureStore.enforceRetention(appContext)
            }

            onProgress(PhoneSkySolveStage.SOLVING)
            val observationTime = Instant.ofEpochMilli(
                capture.metadata.exposureMidpointEpochMs ?: (captureStartedAt + capture.exposureNs / 2_000_000L)
            )
            val midSnapshot = exposurePose
            val virtualOpticalPose = midSnapshot?.opticalPoseForCapture(mirrored, extrinsic)
            val imuDirection = virtualOpticalPose?.forward?.let { Direction3(it.x, it.y, it.z) }
                ?: if (!mirrored && captureOrientationMatches) midSnapshot?.opticalAxis ?: rawAtCapture else null
            val imuCameraUp = virtualOpticalPose?.up?.let { Direction3(it.x, it.y, it.z) }
                ?: if (!mirrored && captureOrientationMatches) midSnapshot?.imageUp ?: matrixAtCapture?.let { matrix ->
                CameraAttitudePrior.imageAxesEnu(matrix, capture.metadata.sensorOrientation).imageUp
            } else null
            val attitudePrior = imuDirection?.let { direction ->
                CameraAttitudePrior.fromOpticalAxis(direction, imuCameraUp, observationTime, site)
            }
            val localSolve = withContext(Dispatchers.Default) {
                WideFieldSolver.solve(WideFieldSolveRequest(
                    extraction = solveExtraction,
                    frameWidth = stacked.frame.width,
                    frameHeight = stacked.frame.height,
                    initialFovWidthDeg = capture.fovWidthDeg ?: 72.0,
                    initialFovHeightDeg = capture.fovHeightDeg ?: 54.0,
                    imuDirection = imuDirection,
                    imuCameraUp = imuCameraUp,
                    attitudePrior = attitudePrior,
                    observationTime = observationTime,
                    site = site,
                    catalog = PhoneBrightStarCatalog.load(appContext),
                    captureMetrics = WideFieldCaptureMetrics(capture.metadata.logicalCameraId,
                        capture.metadata.physicalCameraId, capture.metadata.focalLengthMm,
                        capture.capability.equivalentFocalLengthMm?.toDouble(), capture.exposureNs, capture.iso,
                        stacked.inputFrameCount, burst.sessionOpenLatencyMs, capture.captureLatencyMs,
                        extractionNanos, mirrored, fitsFile.absolutePath)
                ))
            }
            val raDeg = localSolve.raDeg
            val decDeg = localSolve.decDeg
            writeSolveDiagnostics(fitsFile, capture, localSolve, attitudePrior)
            if (!localSolve.success || raDeg == null || decDeg == null) {
                PhoneSkySolveResult(
                    success = false,
                    message = localSolve.message,
                    skySolution = localSolve,
                    fitsPath = fitsFile.absolutePath,
                    frame = stacked.frame,
                    extraction = extraction,
                    cameraLabel = capture.capability.displayLabel,
                    inputFrameCount = stacked.inputFrameCount
                )
            } else {
                val stamp = capture.metadata.observationStamp
                val roll = localSolve.rotationDeg
                val residual = localSolve.quality.rmsResidualDeg
                val motion = exposureMotion
                val opticalObservation = if (stacked.inputFrameCount == 1 && stamp != null && roll != null && residual != null &&
                    residual.isFinite() && localSolve.quality.matchedStars >= 5 && motion != null && motion <= 0.05) {
                    val direction = SkyDirection(raDeg, decDeg)
                    OpticalObservation(fitsFile.nameWithoutExtension,
                        captureIdentity, stamp, direction,
                        SkyPose.fromSolution(direction, roll),
                        captureGeometry,
                        maxOf(residual, 0.005) + motion, OpticalObservationKind.PHONE_PLATE,
                        acquisitionDeviceId = "phone/${captureIdentity.physicalLensId}",
                        plateScaleDegPerPixel = localSolve.arcsecPerPixel?.div(3600.0))
                } else null
                if (opticalObservation != null && midSnapshot != null && capturedSensorSession == sensorSessionRevision) {
                    alignment.align(opticalObservation, midSnapshot, site, matchedHandEye)
                    anchorHandEyeUncertaintyDeg = matchedHandEye?.uncertaintyDeg ?: 0.0
                    publish(rawDirection ?: midSnapshot.opticalAxis, System.currentTimeMillis())
                }
                onProgress(PhoneSkySolveStage.COMPLETE)
                PhoneSkySolveResult(
                    success = true,
                    message = if (stacked.inputFrameCount > 1) appContext.getString(com.indigo.mobileobservatory.R.string.wfp_burst_image_only)
                        else "Solved in ${localSolve.quality.elapsedMs} ms; ${localSolve.quality.matchedStars} matched stars",
                    fix = fix,
                    skySolution = localSolve,
                    fitsPath = fitsFile.absolutePath,
                    frame = stacked.frame,
                    extraction = extraction,
                    cameraLabel = capture.capability.displayLabel,
                    inputFrameCount = stacked.inputFrameCount,
                    observation = opticalObservation,
                    exposureMotionDeg = motion,
                    handEyeSample = if (opticalObservation != null && midSnapshot?.elapsedMs != null) midSnapshot.imuPose()?.let {
                        HandEyeSample(opticalObservation, it, midSnapshot.timestampMs, midSnapshot.elapsedMs, site,
                            "$sensorStreamIdentity:$capturedSensorSession")
                    } else null
                )
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (resource: com.indigo.mobileobservatory.camera.PhoneSkyResourceException) {
            val message = appContext.getString(when (resource.limit) {
                com.indigo.mobileobservatory.camera.PhoneSkyResourceLimit.HEAP -> com.indigo.mobileobservatory.R.string.wfp_resource_heap
                com.indigo.mobileobservatory.camera.PhoneSkyResourceLimit.CAMERA_BUFFERS -> com.indigo.mobileobservatory.R.string.wfp_resource_camera
                com.indigo.mobileobservatory.camera.PhoneSkyResourceLimit.STORAGE -> com.indigo.mobileobservatory.R.string.wfp_resource_storage
            })
            PhoneSkySolveResult(false, message, skySolution = WideFieldSolveResult(false, message, failure = WideFieldSolveFailure.RESOURCE_LIMIT),
                inputFrameCount = 0)
        } catch (_: OutOfMemoryError) {
            // Admission checks cannot predict another device/UI allocation racing this work.
            // Never turn a real allocation failure into a larger burst on the retry ladder.
            val message = appContext.getString(com.indigo.mobileobservatory.R.string.wfp_resource_heap)
            PhoneSkySolveResult(false, message, skySolution = WideFieldSolveResult(false, message, failure = WideFieldSolveFailure.RESOURCE_LIMIT),
                inputFrameCount = 0)
        } catch (t: Throwable) {
            PhoneSkySolveResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun writeSolveDiagnostics(
        fitsFile: File,
        capture: com.indigo.mobileobservatory.camera.PhoneSkyCaptureResult,
        solve: WideFieldSolveResult,
        prior: AttitudeSolvePrior?
    ) {
        val metadata = capture.metadata
        val json = JSONObject().apply {
            put("logicalCameraId", metadata.logicalCameraId)
            put("physicalCameraId", metadata.physicalCameraId)
            put("focalLengthMm", metadata.focalLengthMm)
            put("sensorWidthMm", metadata.sensorWidthMm)
            put("sensorHeightMm", metadata.sensorHeightMm)
            put("cropLeftPx", metadata.cropLeftPx)
            put("cropTopPx", metadata.cropTopPx)
            put("cropWidthPx", metadata.cropWidthPx)
            put("cropHeightPx", metadata.cropHeightPx)
            put("sensorOrientation", metadata.sensorOrientation)
            put("distortionCorrectionMode", metadata.distortionCorrectionMode)
            put("calibrationCoordinateDomain", metadata.calibrationCoordinateDomain.name)
            put("lensCalibrationAvailable", metadata.lensCalibration != null)
            put("solveFootprint", metadata.solveFootprint?.let { rectangle -> JSONObject()
                .put("leftPx", rectangle.leftPx).put("topPx", rectangle.topPx)
                .put("widthPx", rectangle.widthPx).put("heightPx", rectangle.heightPx)
                .put("firstPixelCenterX", rectangle.firstPixelCenterX).put("firstPixelCenterY", rectangle.firstPixelCenterY) })
            put("solveReduction", metadata.solveReduction?.let { reduction -> JSONObject()
                .put("sourceWidth", reduction.sourceWidth).put("sourceHeight", reduction.sourceHeight)
                .put("blockSize", reduction.blockSize).put("coveredWidth", reduction.coveredWidth).put("coveredHeight", reduction.coveredHeight) })
            put("exposureMidpointEpochMs", metadata.exposureMidpointEpochMs)
            put("exposureMidpointElapsedMs", metadata.observationStamp?.elapsedMidpointMs)
            put("clockUncertaintyMs", metadata.observationStamp?.clockUncertaintyMs)
            put("raDegJ2000", solve.raDeg)
            put("decDegJ2000", solve.decDeg)
            put("imageUpPositionAngleDeg", solve.rotationDeg)
            put("fovWidthDeg", capture.fovWidthDeg)
            put("fovHeightDeg", capture.fovHeightDeg)
            put("priorRaDeg", prior?.centerRaDeg)
            put("priorDecDeg", prior?.centerDecDeg)
            put("priorRotationDeg", prior?.rotationDeg)
            put("matchedStars", solve.quality.matchedStars)
            put("rmsResidualDeg", solve.quality.rmsResidualDeg)
            put("confidence", solve.quality.confidence)
            put("usedImuPrior", solve.quality.usedImuPrior)
            put("blindFallbackUsed", solve.quality.blindFallbackUsed)
            put("failure", solve.failure?.name)
        }
        val target = fitsFile.resolveSibling("${fitsFile.nameWithoutExtension}_diagnostics.json")
        runCatching { target.writeText(json.toString(2)) }
    }

    private fun publish(direction: Direction3, timestampMs: Long) {
        val pose = attitudeTimeline.latest()?.let { alignment.pose(it, timestampMs, SystemClock.elapsedRealtime()) }
        currentPose = pose
        val sky = pose?.forward?.direction()
        val h = if (sky != null && observerSite != null) CoordinateTransform.j2000ToTopocentric(
            EquatorialCoordinates(sky.raDeg, sky.decDeg), Instant.ofEpochMilli(timestampMs), requireNotNull(observerSite), null) else null
        val (alt, az) = if (h != null) h.altitudeDeg to h.azimuthDeg else direction.toAltAz()
        val absolute = alignment.observation
        val next = SkyAttitudeFix(
            raHours = sky?.raDeg?.div(15.0),
            decDeg = sky?.decDeg,
            altDeg = alt,
            azDeg = az,
            timestampMs = timestampMs,
            uncertaintyDeg = if (pose != null && absolute != null) absolute.uncertaintyDeg +
                anchorHandEyeUncertaintyDeg +
                (SystemClock.elapsedRealtime() - absolute.stamp.elapsedMidpointMs).coerceAtLeast(0) * 0.00002 else null,
            sourceId = id,
            lastAbsoluteTimestampMs = absolute?.stamp?.utcMidpointMs
        )
        fix = next
        onFix?.invoke(next)
    }
}

internal data class Direction3(val east: Double, val north: Double, val up: Double) {
    fun unit(): Direction3 {
        val length = sqrt(east * east + north * north + up * up)
        if (length <= 1e-12) return Direction3(0.0, 1.0, 0.0)
        return Direction3(east / length, north / length, up / length)
    }

    fun dot(other: Direction3): Double = east * other.east + north * other.north + up * other.up

    fun cross(other: Direction3): Direction3 = Direction3(
        east = north * other.up - up * other.north,
        north = up * other.east - east * other.up,
        up = east * other.north - north * other.east
    )

    operator fun plus(other: Direction3) = Direction3(east + other.east, north + other.north, up + other.up)

    operator fun minus(other: Direction3) = Direction3(east - other.east, north - other.north, up - other.up)

    operator fun times(scale: Double) = Direction3(east * scale, north * scale, up * scale)

    fun angleDeg(other: Direction3): Double {
        val a = unit()
        val b = other.unit()
        val cosine = a.dot(b).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    fun toAltAz(): Pair<Double, Double> {
        val value = unit()
        val altitude = Math.toDegrees(asin(value.up.coerceIn(-1.0, 1.0)))
        val azimuth = PushToGuidance.normalizeAzimuth(Math.toDegrees(atan2(value.east, value.north)))
        return altitude to azimuth
    }

    companion object {
        fun fromAltAz(altitudeDeg: Double, azimuthDeg: Double): Direction3 {
            val altitude = Math.toRadians(altitudeDeg)
            val azimuth = Math.toRadians(azimuthDeg)
            val horizontal = cos(altitude)
            return Direction3(
                east = horizontal * sin(azimuth),
                north = horizontal * cos(azimuth),
                up = sin(altitude)
            )
        }
    }
}

/** Shortest 3-D rotation from the sensor direction at solve time to the solved sky direction. */
internal class DirectionAlignment {
    @Volatile
    private var rotation = Quaternion.identity()

    @Volatile
    var isCalibrated: Boolean = false
        private set

    fun calibrate(sensorDirection: Direction3, skyDirection: Direction3) {
        rotation = Quaternion.fromTo(sensorDirection.unit(), skyDirection.unit())
        isCalibrated = true
    }

    fun apply(direction: Direction3): Direction3 = rotation.rotate(direction.unit()).unit()
}

private data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun rotate(vector: Direction3): Direction3 {
        val qv = Quaternion(0.0, vector.east, vector.north, vector.up)
        val result = this * qv * conjugate()
        return Direction3(result.x, result.y, result.z)
    }

    private fun conjugate() = Quaternion(w, -x, -y, -z)

    private operator fun times(other: Quaternion) = Quaternion(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w
    )

    companion object {
        fun identity() = Quaternion(1.0, 0.0, 0.0, 0.0)

        fun fromTo(from: Direction3, to: Direction3): Quaternion {
            val dot = (from.east * to.east + from.north * to.north + from.up * to.up)
                .coerceIn(-1.0, 1.0)
            if (dot > 1.0 - 1e-10) return identity()
            if (dot < -1.0 + 1e-10) {
                val axis = if (kotlin.math.abs(from.east) < 0.9) {
                    Direction3(0.0, from.up, -from.north).unit()
                } else {
                    Direction3(-from.up, 0.0, from.east).unit()
                }
                return Quaternion(0.0, axis.east, axis.north, axis.up)
            }
            val axis = Direction3(
                east = from.north * to.up - from.up * to.north,
                north = from.up * to.east - from.east * to.up,
                up = from.east * to.north - from.north * to.east
            ).unit()
            val halfAngle = acos(dot) / 2.0
            val scale = sin(halfAngle)
            return Quaternion(cos(halfAngle), axis.east * scale, axis.north * scale, axis.up * scale)
        }
    }
}
