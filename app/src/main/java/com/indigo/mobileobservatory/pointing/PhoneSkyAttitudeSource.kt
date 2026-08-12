package com.indigo.mobileobservatory.pointing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.indigo.mobileobservatory.astrometry.AstapRunner
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
    private var fix: SkyAttitudeFix? = null

    var onFix: ((SkyAttitudeFix) -> Unit)? = null

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
        val rawAtCapture = rawDirection
            ?: return PhoneSkySolveResult(false, "Phone attitude sensor has no reading yet.")
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
            val stacked = withContext(Dispatchers.Default) {
                ShortExposureStacker.stack(burst.captures.map { it.frame })
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
            onCapture(stacked.frame, extraction, stacked.inputFrameCount)
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
                captureStartedAt + capture.exposureNs / 2_000_000L
            )
            val localSolve = withContext(Dispatchers.Default) {
                ImuAssistedWideFieldSolver.solve(
                    extraction = extraction,
                    frameWidth = stacked.frame.width,
                    frameHeight = stacked.frame.height,
                    fovWidthDeg = capture.fovWidthDeg ?: 72.0,
                    fovHeightDeg = capture.fovHeightDeg ?: 54.0,
                    imuDirection = rawAtCapture,
                    instant = observationTime,
                    site = site,
                    catalog = PhoneBrightStarCatalog.load(appContext)
                )
            }
            if (localSolve.success && localSolve.center != null) {
                alignment.calibrate(rawAtCapture, Direction3.fromAltAz(
                    localSolve.center.altitudeDeg,
                    localSolve.center.azimuthDeg
                ))
                publish(alignment.apply(rawDirection ?: rawAtCapture), System.currentTimeMillis())
                onProgress(PhoneSkySolveStage.COMPLETE)
                return PhoneSkySolveResult(
                    success = true,
                    message = localSolve.message,
                    fix = fix,
                    fitsPath = fitsFile.absolutePath,
                    frame = stacked.frame,
                    extraction = extraction,
                    cameraLabel = capture.capability.displayLabel,
                    inputFrameCount = stacked.inputFrameCount
                )
            }
            val fovDeg = capture.fovHeightDeg ?: capture.fovWidthDeg ?: 60.0
            // Transitional solver only. A typical 24 mm-equivalent phone main camera has a
            // roughly 45–85 degree field and requires W08 or a tetra3 index built for that range.
            // D50 remains useful for narrow cameras and must not be treated as the phone baseline.
            val solved = AstapRunner(appContext).solve(fitsFile, fovDeg)
            val raDeg = solved.raDeg
            val decDeg = solved.decDeg
            if (!solved.success || raDeg == null || decDeg == null) {
                PhoneSkySolveResult(
                    success = false,
                    message = solved.message,
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
                alignment.calibrate(rawAtCapture, Direction3.fromAltAz(
                    horizontal.altitudeDeg,
                    horizontal.azimuthDeg
                ))
                val current = rawDirection ?: rawAtCapture
                publish(alignment.apply(current), System.currentTimeMillis())
                onProgress(PhoneSkySolveStage.COMPLETE)
                PhoneSkySolveResult(
                    success = true,
                    message = "Solved in ${solved.elapsedMs} ms",
                    fix = fix,
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
