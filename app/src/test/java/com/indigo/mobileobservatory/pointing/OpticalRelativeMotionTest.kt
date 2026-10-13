package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.ObserverSite
import org.junit.Assert.*
import org.junit.Test

class OpticalRelativeMotionTest {
    private val site = ObserverSite(35.0, 120.0)
    private val direction = SkyDirection(200.0, 35.0)
    private val observation = OpticalObservation("aux", OpticalIdentity("actual", "sensor", "secondary"),
        ObservationStamp(1_790_000_000_000, 1500, 1000, 500), direction, SkyPose.fromSolution(direction, 20.0),
        CameraGeometry(400, 300, parity = ImageParity.NORMAL), .05, OpticalObservationKind.AUXILIARY_PLATE, plateScaleDegPerPixel = .001)
    @Test fun fixedTerrestrialCameraPredictionAgreesWithIndependentSiderealRotation() {
        val star = OpticalStarPsf(170.0, 130.0, 1.0, .8, 20.0, 1000.0)
        val firstPose = requireNotNull(observation.pose)
        val scale = Math.toRadians(.001)
        val starWorld = firstPose.project(SkyVector((star.x - 199.5) * scale, -(star.y - 149.5) * scale, 1.0).unit())
        // Independently rotate the camera around the celestial pole at the sidereal rate.
        val laterPose = SkyPose.fromSolution(SkyDirection(200 + 10 * 360 / 86164.0905, 35.0), 20.0)
        val local = laterPose.unproject(starWorld)
        val expectedX = 199.5 + local.x / local.z / scale
        val expectedY = 149.5 - local.y / local.z / scale
        val predicted = OpticalRelativeMotion.predictedPixel(star, observation, 11500, site, false)
        assertEquals(expectedX, predicted.first, .15); assertEquals(expectedY, predicted.second, .15)
        assertTrue(kotlin.math.hypot(predicted.first - star.x, predicted.second - star.y) > 20)
    }
    @Test fun trackingDoesNotRemoveMeasuredMotionAndUntrackedNeedsRealSite() {
        val star = OpticalStarPsf(170.0, 130.0, 1.0, .8, 20.0, 1000.0)
        assertEquals(star.x to star.y, OpticalRelativeMotion.predictedPixel(star, observation, 5000, null, true))
        assertThrows(IllegalArgumentException::class.java) { OpticalRelativeMotion.predictedPixel(star, observation, 5000, null, false) }
    }
}
