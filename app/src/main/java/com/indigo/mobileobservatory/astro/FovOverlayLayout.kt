package com.indigo.mobileobservatory.astro

import kotlin.math.atan
import kotlin.math.min
import kotlin.math.tan

/**
 * Screen-pixel sizes for star-map FOV overlays.
 *
 * Stellarium Web Engine stores [coreFovDeg] as the FOV of the **smaller**
 * viewport axis (see `proj_perspective_compute_fov`): landscape → vertical,
 * portrait → horizontal. Overlays must scale with the live engine FOV so
 * pinch-zoom keeps angular size fixed on the sky.
 */
object FovOverlayLayout {

    data class ViewFovs(
        /** Horizontal sky FOV spanned by the viewport width, degrees. */
        val horizontalDeg: Double,
        /** Vertical sky FOV spanned by the viewport height, degrees. */
        val verticalDeg: Double
    )

    data class PixelSize(val widthPx: Double, val heightPx: Double)

    /**
     * Split the engine's characteristic FOV into horizontal / vertical FOVs
     * for a viewport of [viewWidthPx] × [viewHeightPx].
     */
    fun viewFovsDegrees(
        viewWidthPx: Double,
        viewHeightPx: Double,
        coreFovDeg: Double
    ): ViewFovs? {
        if (!(viewWidthPx > 0.0) || !(viewHeightPx > 0.0)) return null
        if (!(coreFovDeg > 0.0) || !coreFovDeg.isFinite()) return null
        val aspect = viewWidthPx / viewHeightPx
        val half = Math.toRadians(coreFovDeg / 2.0)
        return if (aspect < 1.0) {
            val fovx = coreFovDeg
            val fovy = Math.toDegrees(2.0 * atan(tan(half) / aspect))
            ViewFovs(fovx, fovy)
        } else {
            val fovy = coreFovDeg
            val fovx = Math.toDegrees(2.0 * atan(tan(half) * aspect))
            ViewFovs(fovx, fovy)
        }
    }

    /**
     * Pixel size of a rectangular sensor FOV drawn centered in the viewport.
     * Returns null when inputs cannot produce a finite positive box.
     */
    fun sensorBoxPixels(
        viewWidthPx: Double,
        viewHeightPx: Double,
        coreFovDeg: Double,
        sensorWidthDeg: Double,
        sensorHeightDeg: Double
    ): PixelSize? {
        if (!(sensorWidthDeg > 0.0) || !(sensorHeightDeg > 0.0)) return null
        val fovs = viewFovsDegrees(viewWidthPx, viewHeightPx, coreFovDeg) ?: return null
        val w = viewWidthPx * (sensorWidthDeg / fovs.horizontalDeg)
        val h = viewHeightPx * (sensorHeightDeg / fovs.verticalDeg)
        if (!w.isFinite() || !h.isFinite() || w <= 0.0 || h <= 0.0) return null
        return PixelSize(w, h)
    }

    /**
     * Pixel diameter of a circular eyepiece FOV. Uses the smaller viewport
     * axis, matching [coreFovDeg]'s definition.
     */
    fun eyepieceDiameterPixels(
        viewWidthPx: Double,
        viewHeightPx: Double,
        coreFovDeg: Double,
        eyepieceFovDeg: Double
    ): Double? {
        if (!(eyepieceFovDeg > 0.0) || !eyepieceFovDeg.isFinite()) return null
        if (!(coreFovDeg > 0.0) || !coreFovDeg.isFinite()) return null
        if (!(viewWidthPx > 0.0) || !(viewHeightPx > 0.0)) return null
        val diameter = min(viewWidthPx, viewHeightPx) * (eyepieceFovDeg / coreFovDeg)
        if (!diameter.isFinite() || diameter <= 0.0) return null
        return diameter
    }
}
