package com.indigo.mobileobservatory.pointing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
    val inputFrameCount: Int = 1
)

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
    override val capabilityTier: CapabilityTier = CapabilityTier.L1_PHONE

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val alignment = DirectionAlignment()

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
    private val attitudeTimeline = AttitudeTimeline()

    @Volatile
    private var lastSensorOrientationDeg: Int = 90

    var onFix: ((SkyAttitudeFix) -> Unit)? = null
    var onMotion: ((MotionPhase) -> Unit)? = null

    val available: Boolean get() = rotationSensor != null
    val plateSolved: Boolean get() = alignment.isCalibrated

    fun start(): Boolean {
        val sensor = rotationSensor ?: return false
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun latestFix(): SkyAttitudeFix? = fix

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
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
        attitudeTimeline.add(
            AttitudeSnapshot(
                timestampMs = System.currentTimeMillis(),
                opticalAxis = raw,
                imageUp = imageUp
            )
        )
        val previous = lastRawDirection
        val previousNs = lastSensorTimestampNs
        lastRawDirection = raw
        lastSensorTimestampNs = event.timestamp
        if (previous != null && previousNs > 0L) {
            val dtSec = (event.timestamp - previousNs) / 1_000_000_000.0
            if (dtSec in 0.001..0.5) {
                val rate = previous.angleDeg(raw) / dtSec
                val phase = motionGate.ingest(rate, System.currentTimeMillis())
                onMotion?.invoke(phase)
            }
        }
        publish(alignment.apply(raw), System.currentTimeMillis())
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
        onBurstProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): PhoneSkySolveResult {
        // Plate solving remains available without a rotation-vector sensor. In that case it
        // returns the photographic solution while live push-to orientation awaits an IMU sample.
        val rawAtCapture = rawDirection
        val matrixAtCapture = lastRotationMatrix
        val captureStartedAt = System.currentTimeMillis()

        return try {
            onProgress(PhoneSkySolveStage.CAPTURING)
            val directory = PhoneSkyCaptureStore.directory(appContext)
            val fitsFile = File(directory, "${PhoneSkyCaptureStore.newBaseName()}_push_to.fits")
            val burst = withContext(Dispatchers.Default) {
                PhoneSkyCapture(appContext).captureBurst(
                    exposureSeconds = exposureSeconds,
                    iso = iso,
                    preferRaw = preferRaw,
                    cameraId = cameraId,
                    frameCount = burstFrameCount,
                    onFrameCaptured = onBurstProgress
                )
            }
            val capture = burst.first
            lastSensorOrientationDeg = capture.metadata.sensorOrientation
            val stacked = withContext(Dispatchers.Default) {
                val poses = burst.captures.map { item ->
                    val stamp = item.metadata.exposureMidpointEpochMs ?: item.frame.timestamp
                    attitudeTimeline.sample(stamp)?.let { snap ->
                        val up = snap.imageUp ?: return@let null
                        BurstAttitude(snap.opticalAxis, up)
                    }
                }
                ShortExposureStacker.stack(
                    frames = burst.captures.map { it.frame },
                    attitudes = poses,
                    fovWidthDeg = capture.fovWidthDeg,
                    fovHeightDeg = capture.fovHeightDeg
                )
            }
            onProgress(PhoneSkySolveStage.EXTRACTING_STARS)
            val extraction = withContext(Dispatchers.Default) {
                WideFieldStarExtractor.extractFromFrame(
                    frame = stacked.frame,
                    maxStars = 200,
                    fovWidthDeg = capture.fovWidthDeg,
                    fovHeightDeg = capture.fovHeightDeg
                )
            }
            val solveExtraction = StarCoordinateUndistorter.correct(
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
                coordinateDomain = capture.metadata.calibrationCoordinateDomain
            )
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
            val midSnapshot = attitudeTimeline.sample(observationTime.toEpochMilli())
            val imuDirection = midSnapshot?.opticalAxis ?: rawAtCapture
            val imuCameraUp = midSnapshot?.imageUp ?: matrixAtCapture?.let { matrix ->
                CameraAttitudePrior.imageAxesEnu(matrix, capture.metadata.sensorOrientation).imageUp
            }
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
                    catalog = PhoneBrightStarCatalog.load(appContext)
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
                val horizontal = CoordinateTransform.j2000ToTopocentric(
                    EquatorialCoordinates(raDeg, decDeg),
                    observationTime,
                    site,
                    refraction = null
                )
                imuDirection?.let { sensorDirection ->
                    alignment.calibrate(sensorDirection, Direction3.fromAltAz(
                        horizontal.altitudeDeg,
                        horizontal.azimuthDeg
                    ))
                    publish(alignment.apply(rawDirection ?: sensorDirection), System.currentTimeMillis())
                }
                onProgress(PhoneSkySolveStage.COMPLETE)
                PhoneSkySolveResult(
                    success = true,
                    message = "Solved in ${localSolve.quality.elapsedMs} ms; ${localSolve.quality.matchedStars} matched stars",
                    fix = fix,
                    skySolution = localSolve,
                    fitsPath = fitsFile.absolutePath,
                    frame = stacked.frame,
                    extraction = extraction,
                    cameraLabel = capture.capability.displayLabel,
                    inputFrameCount = stacked.inputFrameCount
                )
            }
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
            put("exposureMidpointEpochMs", metadata.exposureMidpointEpochMs)
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
        val (alt, az) = direction.toAltAz()
        val next = SkyAttitudeFix(
            altDeg = alt,
            azDeg = az,
            timestampMs = timestampMs,
            uncertaintyDeg = if (alignment.isCalibrated) 0.5 else null,
            sourceId = id
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
