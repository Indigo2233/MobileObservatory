package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.pointing.*
import kotlin.math.*

/** Strict TAN pixel projection for a declared optical-axis pixel, in FITS 1-based coordinates. */
class OpticalWcs private constructor(val header: Map<String, String>, val parity: ImageParity,
                                   val scaleDegPerPixel: Double) {
    private fun number(key: String) = requireNotNull(header[key]?.replace("D", "E")?.toDoubleOrNull())
    fun direction(x: Double, y: Double): SkyDirection {
        val dx = x - number("CRPIX1"); val dy = y - number("CRPIX2")
        val xi = Math.toRadians(number("CD1_1") * dx + number("CD1_2") * dy)
        val eta = Math.toRadians(number("CD2_1") * dx + number("CD2_2") * dy)
        val ra = Math.toRadians(number("CRVAL1")); val dec = Math.toRadians(number("CRVAL2"))
        val denominator = cos(dec) - eta * sin(dec)
        return SkyDirection((Math.toDegrees(ra + atan2(xi, denominator)) + 360) % 360,
            Math.toDegrees(atan2(sin(dec) + eta * cos(dec), hypot(denominator, xi))))
    }
    fun pose(x: Double, y: Double): SkyPose {
        val center = direction(x, y).vector()
        // Our FITS writer preserves camera row order. Camera image-up is decreasing raw Y,
        // even though FITS display software conventionally draws its +Y axis upwards.
        val imageUp = direction(x, y - 1).vector()
        val up = (imageUp - center * imageUp.dot(center)).unit()
        return SkyPose(up.cross(center).unit(), up, center)
    }
    companion object {
        fun parse(header: Map<String, String>): OpticalWcs {
            require(header["PLTSOLVD"] != "F")
            // ASTAP .ini is explicitly TAN/J2000 but omits CTYPE; other missing-CTYPE sources fail closed.
            require(header["PLTSOLVD"] == "T" || (header["CTYPE1"] == "RA---TAN" && header["CTYPE2"] == "DEC--TAN"))
            require(header["CTYPE1"] == null || header["CTYPE1"] == "RA---TAN")
            require(header["CTYPE2"] == null || header["CTYPE2"] == "DEC--TAN")
            require(header.keys.none { it.startsWith("PV1_") || it.startsWith("PV2_") || it == "A_ORDER" || it == "B_ORDER" }) { "Distorted WCS needs a distortion-aware reference" }
            require(header["EQUINOX"]?.toDoubleOrNull()?.let { abs(it - 2000.0) < .001 } != false)
            require(header["RADESYS"]?.let { it == "ICRS" || it == "FK5" } != false)
            require(listOf("CUNIT1", "CUNIT2").all { header[it]?.lowercase()?.let { unit -> unit in setOf("deg", "degree", "degrees") } != false })
            val values = listOf("CRVAL1", "CRVAL2", "CRPIX1", "CRPIX2", "CD1_1", "CD1_2", "CD2_1", "CD2_2")
                .map { requireNotNull(header[it]?.replace("D", "E")?.toDoubleOrNull()).also { n -> require(n.isFinite()) } }
            val determinant = values[4] * values[7] - values[5] * values[6]
            require(abs(determinant) > 1e-16)
            return OpticalWcs(header, if (determinant < 0) ImageParity.NORMAL else ImageParity.MIRRORED,
                maxOf(hypot(values[4], values[6]), hypot(values[5], values[7])))
        }
    }
}
