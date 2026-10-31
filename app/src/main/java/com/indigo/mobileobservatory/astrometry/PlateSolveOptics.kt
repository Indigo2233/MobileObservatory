package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.astro.OpticsFov
import com.indigo.mobileobservatory.astro.SensorSpec
import com.indigo.mobileobservatory.camera.CameraInfo
import com.indigo.mobileobservatory.camera.Roi

data class PlateSolveScaleHint(
    val width: Int,
    val height: Int,
    val pixelSizeUm: Double?,
    val focalLengthMm: Double?,
    val binning: Int,
    val fovHeightDeg: Double?
)

/**
 * ASTAP `-fov` for JPEG/PNG is image height in degrees. That hint must follow
 * the focal length the user typed, not a leftover FITS FOVH or previous solve.
 */
object PlateSolveOptics {
    fun cameraHints(
        info: CameraInfo?,
        roi: Roi? = null,
        roiSensorScale: Int = 1,
        binning: Int = 1
    ): FitsSolveHints {
        if (info == null) return FitsSolveHints()
        val bin = binning.coerceAtLeast(1)
        val roiScale = if (roi != null) roiSensorScale.coerceAtLeast(1) else 1
        return FitsSolveHints(
            width = (roi?.width ?: info.sensorWidth) * roiScale / bin,
            height = (roi?.height ?: info.sensorHeight) * roiScale / bin,
            pixelSizeUm = firstPositive(info.pixelSizeUm?.toDouble()),
            binning = bin
        )
    }

    fun imageSensor(hints: FitsSolveHints, displayName: String): SensorSpec? =
        firstPositive(hints.pixelSizeUm)?.let { pixel ->
            SensorSpec("solve_image", displayName, pixel, hints.width, hints.height)
        }

    /** A saved frame retains its sensor even after a different camera is connected. */
    fun pixelSizeForImage(hints: FitsSolveHints, fallbackPixelSizeUm: Double?): Double? =
        firstPositive(hints.pixelSizeUm, fallbackPixelSizeUm)

    fun scaleHint(
        hints: FitsSolveHints,
        userFocalLengthMm: Double?,
        userPixelSizeUm: Double?
    ): PlateSolveScaleHint {
        val pixel = firstPositive(userPixelSizeUm, hints.pixelSizeUm)
        val focal = firstPositive(userFocalLengthMm, hints.focalLengthMm)
        val binning = hints.binning.coerceAtLeast(1)
        val computed = if (hints.height > 0 && pixel != null && focal != null) {
            OpticsFov.axisDegrees(pixel, focal, hints.height, binning)
        } else {
            null
        }
        val fov = computed ?: hints.fovHeightDeg?.takeIf { it.isFinite() && it > 0.0 }
        return PlateSolveScaleHint(
            width = hints.width,
            height = hints.height,
            pixelSizeUm = pixel,
            focalLengthMm = focal,
            binning = binning,
            fovHeightDeg = fov
        )
    }

    fun astapFovDeg(
        hints: FitsSolveHints,
        userFocalLengthMm: Double?,
        userPixelSizeUm: Double?,
        catalogHeightPx: Int = 0
    ): Double? {
        val effective = if (hints.height > 0) {
            hints
        } else {
            hints.copy(height = catalogHeightPx)
        }
        return scaleHint(effective, userFocalLengthMm, userPixelSizeUm).fovHeightDeg
    }

    fun impliedFocalLengthMm(
        pixelSizeUm: Double?,
        arcsecPerPixel: Double?,
        binning: Int = 1
    ): Double? {
        if (pixelSizeUm == null || arcsecPerPixel == null) return null
        return OpticsFov.impliedFocalLengthMm(pixelSizeUm, arcsecPerPixel, binning)
    }

    private fun firstPositive(vararg values: Double?): Double? {
        return values.firstOrNull { value ->
            value != null && value.isFinite() && value > 0.0
        }
    }
}
