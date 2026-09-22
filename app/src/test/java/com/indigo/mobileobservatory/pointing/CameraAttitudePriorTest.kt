package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.ObserverSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CameraAttitudePriorTest {
    private val site = ObserverSite(30.0, 120.0)
    private val instant = Instant.parse("2026-08-12T14:00:00Z")

    @Test
    fun zenithPointingMapsSearchCentreToLocalLatitude() {
        val prior = CameraAttitudePrior.fromOpticalAxis(
            opticalAxisEnu = Direction3(0.0, 0.0, 1.0),
            imageUpEnu = Direction3(0.0, 1.0, 0.0),
            instant = instant,
            site = site
        )
        assertEquals(30.0, prior.centerDecDeg, 1.0)
        assertNotNull(prior.rotationDeg)
        assertEquals(0.0, shortestRotationDeltaDeg(prior.rotationDeg!!, 0.0), 8.0)
    }

    @Test
    fun imageUpEastAtZenithGivesQuarterTurn() {
        val prior = CameraAttitudePrior.fromOpticalAxis(
            opticalAxisEnu = Direction3(0.0, 0.0, 1.0),
            imageUpEnu = Direction3(1.0, 0.0, 0.0),
            instant = instant,
            site = site
        )
        assertNotNull(prior.rotationDeg)
        assertTrue(shortestRotationDeltaDeg(prior.rotationDeg!!, 90.0) < 8.0)
    }

    @Test
    fun rearCameraMatrixAtZenithUsesSensorOrientation() {
        // Face-down phone: device +Z toward ground, rear camera looks at zenith.
        // device X = east, device Y = south, device Z = down.
        val matrix = floatArrayOf(
            1f, 0f, 0f,
            0f, -1f, 0f,
            0f, 0f, -1f
        )
        val upright = CameraAttitudePrior.imageAxesEnu(matrix, sensorOrientationDeg = 0)
        assertTrue(upright.opticalAxis.angleDeg(Direction3(0.0, 0.0, 1.0)) < 1e-6)
        assertTrue(upright.imageUp.angleDeg(Direction3(0.0, 1.0, 0.0)) < 1e-6)

        val rotated = CameraAttitudePrior.imageAxesEnu(matrix, sensorOrientationDeg = 90)
        assertTrue(rotated.imageUp.angleDeg(Direction3(1.0, 0.0, 0.0)) < 1e-6)
    }

    @Test
    fun fovBucketsFollowDiagonalBands() {
        assertEquals(FovBucket.WF_A, FovBucket.ofDiagonal(88.0))
        assertEquals(FovBucket.WF_B, FovBucket.ofDiagonal(52.0))
        assertEquals(FovBucket.WF_C, FovBucket.ofDiagonal(36.0))
    }
}
