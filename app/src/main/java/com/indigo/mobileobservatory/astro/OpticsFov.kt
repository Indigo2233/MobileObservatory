package com.indigo.mobileobservatory.astro

/**
 * Imaging FOV from telescope focal length and camera geometry.
 *
 * plate scale (arcsec/px) = 206.265 × pixelSizeUm × binning / focalLengthMm
 * FOV (deg) = plateScale × pixels / 3600
 */
object OpticsFov {
    fun plateScaleArcsecPerPixel(
        pixelSizeUm: Double,
        focalLengthMm: Double,
        binning: Int = 1
    ): Double? {
        if (!pixelSizeUm.isFinite() || pixelSizeUm <= 0.0) return null
        if (!focalLengthMm.isFinite() || focalLengthMm <= 0.0) return null
        val bin = binning.coerceAtLeast(1)
        return 206.265 * pixelSizeUm * bin / focalLengthMm
    }

    fun axisDegrees(
        pixelSizeUm: Double,
        focalLengthMm: Double,
        pixels: Int,
        binning: Int = 1
    ): Double? {
        if (pixels <= 0) return null
        val scale = plateScaleArcsecPerPixel(pixelSizeUm, focalLengthMm, binning) ?: return null
        return scale * pixels / 3600.0
    }

    fun rectangleDegrees(
        pixelSizeUm: Double,
        focalLengthMm: Double,
        widthPx: Int,
        heightPx: Int,
        binning: Int = 1
    ): Pair<Double, Double>? {
        val w = axisDegrees(pixelSizeUm, focalLengthMm, widthPx, binning) ?: return null
        val h = axisDegrees(pixelSizeUm, focalLengthMm, heightPx, binning) ?: return null
        return w to h
    }
}
