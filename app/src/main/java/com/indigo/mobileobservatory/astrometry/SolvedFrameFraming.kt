package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.astro.FovComputation
import com.indigo.mobileobservatory.astro.FovInstrumentMode
import com.indigo.mobileobservatory.astro.FovSkyAnchor
import com.indigo.mobileobservatory.astro.OpticsTrainConfig
import com.indigo.mobileobservatory.astro.PointingOpticsConfiguration
import com.indigo.mobileobservatory.astro.SensorSpec
import java.time.Instant
import kotlin.math.round

/** A photographic footprint. It has no authority to report live pointing or target arrival. */
data class SolvedFrameFraming(
    val anchor: FovSkyAnchor,
    val field: FovComputation,
    val positionAngleDeg: Double,
    val exposureStartedAt: Instant?,
    val cameraName: String,
    val sensor: SensorSpec?,
    val focalLengthMm: Double?
) {
    fun updatedPrimaryOptics(configuration: PointingOpticsConfiguration): PointingOpticsConfiguration? {
        val camera = sensor ?: return null
        val focal = focalLengthMm ?: return null
        val existing = configuration.cameras.firstOrNull { it.copy(id = "") == camera }
        val selectedCamera = existing ?: camera.copy(id = generateSequence(1) { it + 1 }
            .map { "solve_frame_$it" }.first { id -> configuration.cameras.none { it.id == id } })
        val primary: OpticsTrainConfig = configuration.primary.copy(
            mode = FovInstrumentMode.SENSOR,
            telescopeId = "scope_custom".takeIf { id -> configuration.telescopes.any { it.id == id } }
                ?: configuration.primary.telescopeId,
            sensorId = selectedCamera.id,
            customTelescopeFl = focal.toString(),
            telescopeFocalLengthOverrideMm = focal
        )
        if (primary == configuration.primary && existing != null) return configuration
        return configuration.copy(
            primary = primary,
            cameras = if (existing == null) configuration.cameras + selectedCamera else configuration.cameras,
            confirmedTrains = configuration.confirmedTrains - primary.id
        )
    }

    companion object {
        internal fun fromCurrentFrame(result: PlateSolveResult, hints: FitsSolveHints, cameraLabel: String): SolvedFrameFraming? {
            val wcs = result.imageWcs ?: return null
            if (!result.success) return null
            val footprint = runCatching { wcs.centeredFootprint() }.getOrNull() ?: return null
            if (footprint.widthDeg <= 0.0 || footprint.heightDeg <= 0.0) return null
            val pitch = hints.pixelSizeUm?.times(hints.binning)?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { round(it * 10_000) / 10_000 }?.takeIf { it.isFinite() && it > 0.0 }
            val focal = PlateSolveOptics.impliedFocalLengthMm(hints.pixelSizeUm, result.arcsecPerPixel, hints.binning)
                ?.takeIf { it.isFinite() && it > 0.0 }
            val camera = hints.instrumentName ?: cameraLabel
            val sensor = pitch?.let { SensorSpec("", camera, it, wcs.width, wcs.height) }
            return SolvedFrameFraming(
                FovSkyAnchor(footprint.center.raDeg / 15.0, footprint.center.decDeg, "J2000"),
                FovComputation(FovInstrumentMode.SENSOR, rectWidthDeg = footprint.widthDeg, rectHeightDeg = footprint.heightDeg),
                footprint.positionAngleDeg,
                hints.exposureStartedAt, camera, sensor, focal
            )
        }
    }
}
