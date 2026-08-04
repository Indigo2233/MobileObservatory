package com.indigo.mobileobservatory.astrometry

import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

object FitsWcsParser {
    fun parseCandidates(files: List<File>, fallbackWidth: Int, fallbackHeight: Int): ParsedWcs? {
        for (file in files) {
            val parsed = parse(FitsHeaderReader.read(file), fallbackWidth, fallbackHeight, file)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parse(header: Map<String, String>, fallbackWidth: Int, fallbackHeight: Int, source: File): ParsedWcs? {
        val ra = header.doubleValue("CRVAL1") ?: return null
        val dec = header.doubleValue("CRVAL2") ?: return null
        val width = header.intValue("NAXIS1") ?: fallbackWidth
        val height = header.intValue("NAXIS2") ?: fallbackHeight

        val cd11 = header.doubleValue("CD1_1")
        val cd12 = header.doubleValue("CD1_2")
        val cd21 = header.doubleValue("CD2_1")
        val cd22 = header.doubleValue("CD2_2")
        val scaleXDeg: Double
        val scaleYDeg: Double
        val rotation: Double?
        if (cd11 != null && cd12 != null && cd21 != null && cd22 != null) {
            scaleXDeg = hypot(cd11, cd21)
            scaleYDeg = hypot(cd12, cd22)
            rotation = atan2(cd21, cd11) * 180.0 / PI
        } else {
            val cdelt1 = header.doubleValue("CDELT1")
            val cdelt2 = header.doubleValue("CDELT2")
            if (cdelt1 == null || cdelt2 == null) return ParsedWcs(ra, dec, null, null, null, null, source)
            scaleXDeg = kotlin.math.abs(cdelt1)
            scaleYDeg = kotlin.math.abs(cdelt2)
            rotation = header.doubleValue("CROTA2")
        }

        return ParsedWcs(
            raDeg = normalizeRa(ra),
            decDeg = dec,
            fovWidthDeg = if (width > 0) scaleXDeg * width else null,
            fovHeightDeg = if (height > 0) scaleYDeg * height else null,
            rotationDeg = rotation,
            arcsecPerPixel = ((scaleXDeg + scaleYDeg) / 2.0) * 3600.0,
            source = source
        )
    }

    private fun Map<String, String>.doubleValue(key: String): Double? {
        return this[key]?.replace("D", "E", ignoreCase = true)?.toDoubleOrNull()
    }

    private fun Map<String, String>.intValue(key: String): Int? {
        return this[key]?.toIntOrNull()
    }

    private fun normalizeRa(ra: Double): Double {
        val normalized = ra % 360.0
        return if (normalized < 0) normalized + 360.0 else normalized
    }
}

data class ParsedWcs(
    val raDeg: Double,
    val decDeg: Double,
    val fovWidthDeg: Double?,
    val fovHeightDeg: Double?,
    val rotationDeg: Double?,
    val arcsecPerPixel: Double?,
    val source: File
)
