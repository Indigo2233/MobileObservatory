package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.util.FitsHeader
import kotlin.math.abs

/** Describe stored samples independently of display debayering and monochrome settings. */
fun FitsHeader.solveInputFormat(): String {
    val bitpix = intValue("BITPIX")
    val storage = when (bitpix) {
        -32, -64 -> "${abs(bitpix)}-bit float"
        8, 16, 32, 64 -> "$bitpix-bit integer"
        else -> "BITPIX=${get("BITPIX") ?: "?"}"
    }
    val planes = intValue("NAXIS3") ?: 1
    val layout = when {
        intValue("NAXIS") == 3 && planes == 3 -> "RGB"
        planes > 1 -> "$planes planes"
        !get("BAYERPAT").isNullOrBlank() -> "Bayer ${get("BAYERPAT")}"
        else -> "MONO"
    }
    return "FITS · $storage · $layout"
}
