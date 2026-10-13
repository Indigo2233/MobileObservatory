package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.pointing.ImageParity
import org.junit.Assert.*
import org.junit.Test

class OpticalWcsTest {
    private val header = mapOf("PLTSOLVD" to "T", "CRVAL1" to "359.9", "CRVAL2" to "0.0", "CRPIX1" to "100.5", "CRPIX2" to "50.5",
        "CD1_1" to "-0.01", "CD1_2" to "0", "CD2_1" to "0", "CD2_2" to "0.01")
    @Test fun axisPixelIsProjectedRatherThanAssumedToBeReferencePixel() {
        val wcs = OpticalWcs.parse(header)
        assertEquals(359.9, wcs.direction(100.5, 50.5).raDeg, 1e-9)
        assertEquals(0.0999991877, wcs.direction(80.5, 50.5).raDeg, 1e-8)
        assertEquals(Math.toDegrees(kotlin.math.atan(Math.toRadians(.5))), wcs.direction(100.5, 100.5).decDeg, 1e-9)
        assertEquals(ImageParity.NORMAL, wcs.parity)
    }
    @Test fun mirroredImageIsExplicitAndPoseStaysRightHanded() {
        val wcs = OpticalWcs.parse(header + ("CD1_1" to "0.01"))
        assertEquals(ImageParity.MIRRORED, wcs.parity)
        val pose = wcs.pose(100.5, 50.5)
        assertEquals(1.0, pose.right.cross(pose.up).dot(pose.forward), 1e-9)
    }
    @Test fun rawPixelRaysAgreeWithWcsAfterParityNormalization() {
        for (mirrored in listOf(false, true)) {
            val wcs = OpticalWcs.parse(if (mirrored) header + ("CD1_1" to "0.01") else header)
            val pose = wcs.pose(100.5, 50.5)
            val dx = 20.0; val dy = -10.0; val radiansPerPixel = Math.toRadians(.01)
            val ray = com.indigo.mobileobservatory.pointing.SkyVector(
                dx * radiansPerPixel * (if (mirrored) -1 else 1), -dy * radiansPerPixel, 1.0)
            val expected = wcs.direction(100.5 + dx, 50.5 + dy)
            assertTrue(pose.project(ray).angleDeg(expected.vector()) < 1e-7)
        }
    }
    @Test fun unsupportedDistortionEpochAndSingularMatrixCannotClaimOpticalReference() {
        for (extra in listOf(mapOf("CTYPE1" to "RA---TAN-SIP"), mapOf("PV1_1" to "1"),
            mapOf("EQUINOX" to "1950"), mapOf("CD2_2" to "0"), mapOf("CUNIT1" to "rad"), mapOf("PLTSOLVD" to "F"))) {
            assertThrows(IllegalArgumentException::class.java) { OpticalWcs.parse(header + extra) }
        }
    }
}
