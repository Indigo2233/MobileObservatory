package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.GainControlKind
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.sequence.catalog.SequenceHardwareSnapshot
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VirtualSequenceStatus(
    val sensorTemperatureTenths: Int = 120,
    val coolerOn: Boolean = false,
    val raHours: Double = 5.588,
    val decDeg: Double = -5.391,
    val tracking: Boolean = true,
    val guiding: Boolean = false,
    val guideRmsPx: Float = 0.42f,
    val guideForceCalibration: Boolean = false,
    val filterName: String = "L",
    val focuserPosition: Int = 20_000,
    val rotatorAngleDeg: Double = 0.0,
    val coverOpen: Boolean = true,
    val dewHeaterOn: Boolean = false,
    val usbLimit: Int = 40,
    val flatLightOn: Boolean = false,
    val flatBrightness: Int = 0,
    val lastCoolingDurationMinutes: Double = 0.0,
    val lastWarmingDurationMinutes: Double = 0.0
)

/**
 * Deterministic device set used only by the x86/x86_64 emulator test build.
 * It exercises the normal sequence engine and artifact writers without USB hardware.
 */
class VirtualSequenceHardware(
    private val fitsWriter: FITSWriter = FITSWriter()
) : SequenceHardware, SequenceWorld {
    companion object {
        val FILTER_NAMES = listOf("L", "R", "G", "B", "Ha", "OIII")

        val SNAPSHOT = SequenceHardwareSnapshot(
            cameraConnected = true,
            coolingCapable = true,
            usbBandwidthCapable = true,
            dewHeater = true,
            filterWheelConnected = true,
            focuserConnected = true,
            focuserHasTemperature = true,
            guiderConnected = true,
            mountConnected = true,
            coverConnected = true,
            rotatorConnected = true,
            flatPanelConnected = true,
            filterNames = FILTER_NAMES
        )
    }

    private val frameCounter = AtomicLong(0)
    private val _status = MutableStateFlow(VirtualSequenceStatus())
    val status: StateFlow<VirtualSequenceStatus> = _status.asStateFlow()
    private var lastFrameHfr: Double? = null
    private var currentGain = 120
    private var currentOffset = 10

    override suspend fun takeExposure(
        seconds: Double,
        gain: Int,
        offset: Int,
        destDir: File,
        binning: Int,
        imageType: String
    ): SessionFrame {
        delay((seconds * 100.0).toLong().coerceIn(120L, 1_200L))
        if (gain >= 0) currentGain = gain
        if (offset >= 0) currentOffset = offset
        val frameId = frameCounter.incrementAndGet()
        val safeBin = binning.coerceIn(1, 4)
        val width = 640 / safeBin
        val height = 480 / safeBin
        val frame = deterministicStarField(width, height, frameId, currentOffset)
        val normalizedType = imageType.ifBlank { "LIGHT" }.uppercase(Locale.US)
        val filter = _status.value.filterName
        val safeFilter = filter.replace(Regex("[^A-Za-z0-9_-]"), "_")
        destDir.mkdirs()
        val file = File(
            destDir,
            "virtual-${frameId.toString().padStart(4, '0')}-${normalizedType.lowercase(Locale.US)}-$safeFilter.fits"
        )
        fitsWriter.write(
            file = file,
            frame = frame,
            exposureSeconds = seconds.toFloat(),
            gain = currentGain.toFloat(),
            gainKind = GainControlKind.NATIVE_GAIN,
            gainLabel = "Gain",
            cameraName = "Indigo Virtual Camera",
            filterName = filter,
            configuredFormat = PixelFormat.MONO16,
            pixelSizeUm = 3.76f,
            focalLengthMm = 480f,
            binning = safeBin,
            imageType = normalizedType
        )
        val hfr = 2.0 + (frameId % 5) * 0.08
        lastFrameHfr = hfr
        return SessionFrame(
            name = file.name,
            filter = filter,
            exposureSeconds = seconds,
            hfr = hfr,
            starCount = 36
        )
    }

    override suspend fun switchFilter(name: String) {
        val match = FILTER_NAMES.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: throw DeviceUnavailable("filter $name")
        delay(180)
        update { copy(filterName = match) }
    }

    override suspend fun cool(targetC: Double, durationMinutes: Double) {
        update { copy(coolerOn = true, lastCoolingDurationMinutes = durationMinutes.coerceAtLeast(0.0)) }
        val target = (targetC * 10.0).toInt()
        repeat(4) {
            delay((durationMinutes * 25.0).toLong().coerceIn(60L, 250L))
            update {
                val delta = target - sensorTemperatureTenths
                copy(sensorTemperatureTenths = sensorTemperatureTenths + delta / max(1, 4 - it))
            }
        }
        update { copy(sensorTemperatureTenths = target) }
    }

    override suspend fun warm(durationMinutes: Double) {
        delay((durationMinutes * 100.0).toLong().coerceIn(120L, 600L))
        update {
            copy(
                coolerOn = false,
                sensorTemperatureTenths = 120,
                lastWarmingDurationMinutes = durationMinutes.coerceAtLeast(0.0)
            )
        }
    }

    override suspend fun slew(raHours: Double, decDeg: Double) {
        delay(350)
        update { copy(raHours = raHours.mod(24.0), decDeg = decDeg.coerceIn(-90.0, 90.0)) }
    }

    override suspend fun center(raHours: Double, decDeg: Double) {
        slew(raHours, decDeg)
        delay(220)
    }

    override suspend fun plateSolve(): Pair<Double, Double> =
        _status.value.let { it.raHours to it.decDeg }

    override suspend fun syncMount(raHours: Double, decDeg: Double) {
        update { copy(raHours = raHours.mod(24.0), decDeg = decDeg.coerceIn(-90.0, 90.0)) }
    }

    override suspend fun guide(enabled: Boolean, forceCalibration: Boolean) {
        delay(180)
        update {
            copy(
                guiding = enabled,
                guideForceCalibration = forceCalibration,
                guideRmsPx = if (enabled) 0.42f else 0f
            )
        }
    }

    override suspend fun dither(radiusPx: Double) {
        update { copy(guideRmsPx = radiusPx.coerceAtLeast(0.5).toFloat()) }
        delay(350)
        update { copy(guideRmsPx = 0.48f) }
    }

    override suspend fun tracking(mode: Int) {
        update { copy(tracking = mode != 5) }
    }

    override suspend fun goHome() {
        delay(300)
        update { copy(raHours = 0.0, decDeg = 90.0) }
    }

    override suspend fun cover(open: Boolean) {
        delay(150)
        update { copy(coverOpen = open) }
    }

    override suspend fun dewHeater(on: Boolean) {
        update { copy(dewHeaterOn = on) }
    }

    override suspend fun usbLimit(value: Int) {
        update { copy(usbLimit = value.coerceIn(0, 100)) }
    }

    override suspend fun flatLight(on: Boolean) {
        update {
            copy(
                flatLightOn = on,
                flatBrightness = if (on && flatBrightness == 0) 128 else flatBrightness
            )
        }
    }

    override suspend fun flatBrightness(value: Int) {
        update { copy(flatBrightness = value.coerceIn(0, 255), flatLightOn = value > 0) }
    }

    override suspend fun moveFocuser(position: Int) {
        delay(180)
        update { copy(focuserPosition = position.coerceAtLeast(0)) }
    }

    override suspend fun rotateTo(angleDeg: Double) {
        delay(180)
        update { copy(rotatorAngleDeg = angleDeg.mod(360.0)) }
    }

    override suspend fun autofocus(destDir: File): AutofocusRun {
        val origin = _status.value.focuserPosition
        val curve = listOf(
            origin - 160 to 4.1,
            origin - 80 to 2.8,
            origin to 2.0,
            origin + 80 to 2.7,
            origin + 160 to 4.0
        )
        delay(450)
        return AutofocusRun(
            atMillis = System.currentTimeMillis(),
            temperatureC = _status.value.sensorTemperatureTenths / 10.0,
            filter = _status.value.filterName,
            position = origin,
            hfr = 2.0,
            curve = curve
        )
    }

    override suspend fun waitUntil(epochMillis: Long) {
        delay((epochMillis - System.currentTimeMillis()).coerceIn(0L, 2_000L))
    }

    override fun guidingLocked(): Boolean = _status.value.guiding
    override fun raHours(): Double = _status.value.raHours
    override fun decDeg(): Double = _status.value.decDeg
    override fun focuserPosition(): Int = _status.value.focuserPosition
    override fun altitudeDeg(): Double = 55.0
    override fun altitudeRising(): Boolean = true
    override fun minutesToMeridian(): Double = 90.0
    override fun temperatureC(): Double = _status.value.sensorTemperatureTenths / 10.0
    override fun lastHfr(): Double? = lastFrameHfr
    override fun filterName(): String = _status.value.filterName
    override fun sunAltitudeDeg(): Double = -24.0
    override fun moonAltitudeDeg(): Double = -10.0
    override fun moonIlluminationPct(): Double = 32.0
    override fun observerLatitudeDeg(): Double = 31.23
    override fun observerLongitudeDeg(): Double = 121.47

    private fun update(block: VirtualSequenceStatus.() -> VirtualSequenceStatus) {
        _status.value = _status.value.block()
    }

    private fun deterministicStarField(
        width: Int,
        height: Int,
        frameId: Long,
        offset: Int
    ): FrameData {
        val pixels = IntArray(width * height) { index ->
            550 + offset.coerceAtLeast(0) * 2 + ((index * 17 + frameId.toInt() * 13) and 0x7F)
        }
        repeat(36) { star ->
            val x = 8 + ((star * 83 + 29) % (width - 16).coerceAtLeast(1))
            val y = 8 + ((star * 47 + 17) % (height - 16).coerceAtLeast(1))
            val peak = 18_000 + (star * 1_181 % 42_000)
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val distance2 = dx * dx + dy * dy
                    if (distance2 > 16) continue
                    val index = (y + dy) * width + x + dx
                    pixels[index] = (pixels[index] + peak / (1 + distance2)).coerceAtMost(65_535)
                }
            }
        }
        val bytes = ByteArray(pixels.size * 2)
        pixels.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return FrameData(
            data = bytes,
            width = width,
            height = height,
            pixelFormat = PixelFormat.MONO16,
            frameId = frameId,
            timestamp = System.nanoTime()
        )
    }
}
