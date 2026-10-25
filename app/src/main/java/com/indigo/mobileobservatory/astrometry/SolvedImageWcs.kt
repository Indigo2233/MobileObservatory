package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.pointing.SkyDirection
import kotlin.math.*

data class ImagePoint(val x: Double, val y: Double) {
    operator fun plus(other: ImagePoint) = ImagePoint(x + other.x, y + other.y)
    operator fun minus(other: ImagePoint) = ImagePoint(x - other.x, y - other.y)
}

/** Immutable WCS bound to the original camera image. Pixel centres are zero-based, raw Y goes down. */
class SolvedImageWcs private constructor(
    val width: Int, val height: Int, private val gridWidth: Int, private val gridHeight: Int,
    private val wcs: OpticalWcs
) {
    fun pixel(coordinates: EquatorialCoordinates): ImagePoint? {
        val p = wcs.pixel(SkyDirection(coordinates.raDeg, coordinates.decDeg)) ?: return null
        return ImagePoint((p.first - .5) * width / gridWidth - .5, (p.second - .5) * height / gridHeight - .5)
    }
    fun direction(point: ImagePoint): EquatorialCoordinates {
        val p = wcs.direction((point.x + .5) * gridWidth / width + .5, (point.y + .5) * gridHeight / height + .5)
        return EquatorialCoordinates(p.raDeg, p.decDeg)
    }
    companion object {
        fun parse(header: Map<String, String>, width: Int, height: Int): SolvedImageWcs? = runCatching {
            require(width > 0 && height > 0)
            val normalized = header.toMutableMap()
            fun number(key: String) = header[key]?.replace("D", "E", true)?.toDoubleOrNull()
            val cdKeys = listOf("CD1_1", "CD1_2", "CD2_1", "CD2_2")
            require(requireNotNull(number("CRVAL2")) in -90.0..90.0)
            if (cdKeys.any { header.containsKey(it) }) cdKeys.forEach { normalized.putIfAbsent(it, "0") }
            if (cdKeys.none { header.containsKey(it) }) {
                val sx = requireNotNull(number("CDELT1")); val sy = requireNotNull(number("CDELT2"))
                val pcKeys = listOf("PC1_1", "PC1_2", "PC2_1", "PC2_2")
                val cd = if (pcKeys.any { header.containsKey(it) }) {
                    listOf(sx * (number("PC1_1") ?: 1.0), sx * (number("PC1_2") ?: 0.0),
                        sy * (number("PC2_1") ?: 0.0), sy * (number("PC2_2") ?: 1.0))
                } else {
                    val angle = Math.toRadians(number("CROTA2") ?: 0.0)
                    listOf(sx * cos(angle), -sy * sin(angle), sx * sin(angle), sy * cos(angle))
                }
                cdKeys.zip(cd).forEach { (key, value) -> normalized[key] = value.toString() }
            }
            val optical = OpticalWcs.parse(normalized.toMap())
            SolvedImageWcs(width, height, number("NAXIS1")?.toInt()?.takeIf { it > 0 } ?: width,
                number("NAXIS2")?.toInt()?.takeIf { it > 0 } ?: height, optical)
        }.getOrNull()
    }
}
