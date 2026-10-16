package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.File

data class FitsSolveHints(
    val width: Int = 0,
    val height: Int = 0,
    val pixelSizeUm: Double? = null,
    val focalLengthMm: Double? = null,
    val binning: Int = 1,
    val fovHeightDeg: Double? = null
)

object FitsSolveHintReader {
    fun read(file: File): FitsSolveHints {
        val extension = file.extension.lowercase()
        val looksLikeFits = extension == "fit" || extension == "fits" || RasterImageSize.isFitsMagic(file)
        if (looksLikeFits) {
            return readFits(file)
        }
        val raster = RasterImageSize.read(file) ?: return FitsSolveHints()
        return FitsSolveHints(width = raster.first, height = raster.second)
    }

    private fun readFits(file: File): FitsSolveHints {
        val header = FitsHeaderParser.read(file)
        val width = header.intValue("NAXIS1") ?: 0
        val height = header.intValue("NAXIS2") ?: 0
        val binning = header.intValue("YBINNING") ?: header.intValue("XBINNING") ?: 1
        val pixelSize = header.doubleValue("YPIXSZ") ?: header.doubleValue("XPIXSZ")
        val focal = header.doubleValue("FOCALLEN")
        val fovHeight = header.doubleValue("FOVH")
            ?: header.doubleValue("PIXSCALE")?.let { it * height / 3600.0 }
            ?: if (pixelSize != null && focal != null && focal > 0.0 && height > 0) {
                206.265 * pixelSize * binning.coerceAtLeast(1) / focal * height / 3600.0
            } else {
                null
            }
        return FitsSolveHints(width, height, pixelSize, focal, binning.coerceAtLeast(1), fovHeight)
    }
}
