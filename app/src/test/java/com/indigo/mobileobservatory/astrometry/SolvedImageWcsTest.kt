package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SolvedImageWcsTest {
    private fun header(angle: Double, parity: Int) = mapOf("PLTSOLVD" to "T", "CRVAL1" to "359.9", "CRVAL2" to "65",
        "CRPIX1" to "200.5", "CRPIX2" to "150.5", "CD1_1" to (parity * .002 * cos(angle)).toString(),
        "CD1_2" to (-.002 * sin(angle)).toString(), "CD2_1" to (parity * .002 * sin(angle)).toString(),
        "CD2_2" to (.002 * cos(angle)).toString())

    @Test fun rawPixelsRoundTripThroughRotatedMirroredAndRaWrapImages() {
        for (degrees in listOf(0.0, 90.0, 180.0, 275.0)) for (parity in listOf(-1, 1)) {
            val wcs = requireNotNull(SolvedImageWcs.parse(header(Math.toRadians(degrees), parity), 400, 300))
            for (point in listOf(ImagePoint(0.0, 0.0), ImagePoint(399.0, 299.0), ImagePoint(199.5, 149.5), ImagePoint(-10.0, 320.0))) {
                val actual = requireNotNull(wcs.pixel(wcs.direction(point)))
                assertEquals(point.x, actual.x, 1e-7); assertEquals(point.y, actual.y, 1e-7)
            }
            assertNull(wcs.pixel(EquatorialCoordinates(179.9, -65.0)))
        }
    }

    @Test fun legacyCrotaAndPcMatricesPreserveTheirSigns() {
        val base = header(0.0, -1).filterKeys { !it.startsWith("CD") }
        val rotation = 33.0; val r = Math.toRadians(rotation)
        val crota = requireNotNull(SolvedImageWcs.parse(base + mapOf("CDELT1" to "-.002", "CDELT2" to ".002", "CROTA2" to "$rotation"), 400, 300))
        val explicit = requireNotNull(SolvedImageWcs.parse(header(r, -1), 400, 300))
        val p = ImagePoint(250.0, 123.0)
        val expected = explicit.direction(p); val actual = crota.direction(p)
        assertEquals(expected.raDeg, actual.raDeg, 1e-9); assertEquals(expected.decDeg, actual.decDeg, 1e-9)
        val pc = requireNotNull(SolvedImageWcs.parse(base + mapOf("CDELT1" to "-.002", "CDELT2" to ".002",
            "PC1_1" to "0", "PC1_2" to "1", "PC2_1" to "1", "PC2_2" to "0"), 400, 300))
        val ray = pc.direction(p); val pixel = requireNotNull(pc.pixel(ray))
        assertEquals(p.x, pixel.x, 1e-7); assertEquals(p.y, pixel.y, 1e-7)
    }

    @Test fun solverBinningUsesPixelCentresAndUnsupportedWcsFailsClosed() {
        val source = header(0.0, -1).toMutableMap()
        source["NAXIS1"] = "200"; source["NAXIS2"] = "150"; source["CRPIX1"] = "100.5"; source["CRPIX2"] = "75.5"
        val wcs = requireNotNull(SolvedImageWcs.parse(source, 400, 300))
        source["CRVAL1"] = "123" // A later ASTAP sidecar cannot change an already published WCS.
        val center = requireNotNull(wcs.pixel(EquatorialCoordinates(359.9, 65.0)))
        assertEquals(199.5, center.x, 1e-9); assertEquals(149.5, center.y, 1e-9)
        assertNull(SolvedImageWcs.parse(source + ("CTYPE1" to "RA---TAN-SIP"), 400, 300))
        assertNull(SolvedImageWcs.parse(source - "CRPIX1", 400, 300))
        assertNull(SolvedImageWcs.parse(source + ("CD2_2" to "0"), 400, 300))
    }
}
