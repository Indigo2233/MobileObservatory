package com.indigo.mobileobservatory.pointing

import org.junit.Assert.*
import org.junit.Test

class PhoneSkySolveResultTest {
    private val direction = SkyDirection(110.0, 40.0)
    private val observation = OpticalObservation("single", OpticalIdentity("phone", "0/2", "phone"),
        ObservationStamp(1_700_000_000_000, 10_000, 1000, 2), direction,
        SkyPose.fromSolution(direction, 120.0), CameraGeometry(1000, 800, parity = ImageParity.NORMAL), .01,
        OpticalObservationKind.PHONE_PLATE)

    @Test fun aBurstCanRetainItsImageSolutionButCannotBecomeTimedPointingEvidence() {
        val solution = WideFieldSolveResult(true, "Image solved", 110.0, 40.0, 120.0)
        val diagnostic = PhoneSkySolveResult(true, "One exposure needed", skySolution = solution, inputFrameCount = 4)
        assertSame(solution, diagnostic.skySolution)
        assertTrue(diagnostic.requiresSingleFrameVerification)
        assertNull(diagnostic.observation)
        assertNull(diagnostic.handEyeSample)
        try { diagnostic.copy(observation = observation); fail("Burst promoted itself to timed evidence") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun anActualSingleContributorKeepsTheOriginalExposureStampAndNeedsNoBurstRecovery() {
        val single = PhoneSkySolveResult(true, "Single solved", observation = observation, inputFrameCount = 1)
        assertSame(observation, single.observation)
        assertEquals(10_000L, single.observation!!.stamp.elapsedMidpointMs)
        assertFalse(single.requiresSingleFrameVerification)
        assertFalse(PhoneSkySolveResult(false, "Resource limit", inputFrameCount = 0).requiresSingleFrameVerification)
    }
}
