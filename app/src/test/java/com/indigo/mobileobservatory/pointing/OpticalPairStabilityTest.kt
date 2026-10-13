package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.ObserverSite
import org.junit.Assert.*
import org.junit.Test

class OpticalPairStabilityTest {
    private val utc = 1_790_000_000_000L
    private val identity = OpticalIdentity("aux-actual-serial", "sensor", "secondary")
    private val geometry = CameraGeometry(400, 300, parity = ImageParity.NORMAL)
    private fun stars(dx: Double = 0.0) = listOf(50.0 to 50.0, 150.0 to 230.0, 220.0 to 70.0,
        280.0 to 230.0, 350.0 to 130.0, 70.0 to 180.0).map { (x, y) -> OpticalStarPsf(x + dx, y, 1.0, .8, 20.0, 1000.0) }
    private fun frames() = (1L..3).map { OpticalFrameStability(it, (it - 1) * 1000, it * 1000,
        1000, 8, 400, 300, stars(it * .2)) }
    private fun observations() = frames().map { raw ->
        val direction = SkyDirection(15.0 + raw.frameId * .0001, 35.0)
        OpticalObservation("aux-${raw.frameId}", identity, ObservationStamp(utc + raw.startElapsedMs + 500,
            raw.startElapsedMs + 500, 1000, 500), direction, SkyPose.fromSolution(direction, 0.0),
            geometry, .05, OpticalObservationKind.AUXILIARY_PLATE, acquisitionDeviceId = "real-aux", plateScaleDegPerPixel = .0005)
    }
    private fun evidence(): OpticalPairStabilityEvidence {
        val frames = frames()
        return OpticalPairStabilityEvidence(observations().zip(frames).map { (observation, raw) ->
            OpticalStabilitySample(observation, raw.stars, raw.startElapsedMs, raw.endElapsedMs, 8, raw.frameId)
        }, frames, OpticalPairStability.motionBound(frames, .0005))
    }
    private fun reference() = OpticalObservation("main", OpticalIdentity("telescope", "axis", "primary"),
        ObservationStamp(utc + 1500, 1500, 1800, 1000), SkyDirection(15.0, 35.0), null, geometry,
        .05, OpticalObservationKind.MAIN_PLATE, acquisitionDeviceId = "real-main")

    @Test fun realRawAndIndependentPlatesAllowAuxiliaryBracketAndFullErrorBudget() {
        val evidence = evidence(); val camera = observations()[1]
        val pair = ReferencePairing.pair(camera, reference(), ObserverSite(35.0, 120.0), true,
            evidence.motionUpperBoundDeg, evidence)
        assertTrue(pair.measuredPairMotionDeg!! > 0)
        assertEquals(evidence, pair.opticalStability)
        assertTrue(pair.pairingUncertaintyDeg >= (.5 + 1) * .0042 + evidence.motionUpperBoundDeg)
    }
    @Test fun auxiliaryCannotClaimZeroMotionOrOmitOriginalEvidence() {
        val evidence = evidence(); val camera = observations()[1]
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePairing.pair(camera, reference(), ObserverSite(0.0, 0.0), true, 0.0, evidence)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePairing.pair(camera, reference(), ObserverSite(0.0, 0.0), true, .005)
        }
    }
    @Test fun missingMiddleFrameOrLateReferenceCannotBeUsedAsStability() {
        val evidence = evidence(); val camera = observations()[1]
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(frameSamples = evidence.frameSamples.drop(1)).validate(camera, reference()) }
        assertThrows(IllegalArgumentException::class.java) { evidence.validate(camera, reference().copy(
            stamp = ObservationStamp(utc + 5000, 5000, 1000, 500))) }
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(frameSamples = evidence.frameSamples.mapIndexed { i, f ->
            if (i == 1) f.copy(frameId = 8) else f
        }).validate(camera, reference()) }
    }
    @Test fun psfWideningAndMotionIncreaseTheBound() {
        val stable = frames()
        val trailed = stable.mapIndexed { i, f -> if (i == 1) f.copy(stars = f.stars.map {
            it.copy(x = it.x + 8, sigmaMajorPx = 3.0)
        }) else f }
        assertTrue(OpticalPairStability.motionBound(trailed, .0005) > OpticalPairStability.motionBound(stable, .0005))
    }
    @Test fun wrongIndependentWcsAndDifferentGenerationFail() {
        val evidence = evidence(); val camera = observations()[1]
        val wrong = evidence.solvedSamples.last().observation.copy(direction = SkyDirection(45.0, 35.0),
            pose = SkyPose.fromSolution(SkyDirection(45.0, 35.0), 0.0))
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(solvedSamples = evidence.solvedSamples.dropLast(1) +
            evidence.solvedSamples.last().copy(observation = wrong)).validate(camera, reference()) }
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(frameSamples = evidence.frameSamples.map { it.copy(captureGeneration = 9) }).validate(camera, reference()) }
    }
    @Test fun phoneStillNeedsItsStrictSensorClockAndSamePhysicalCameraIsRejected() {
        val evidence = evidence(); val camera = observations()[1]
        assertThrows(IllegalArgumentException::class.java) { ReferencePairing.pair(camera.copy(kind = OpticalObservationKind.PHONE_PLATE),
            reference(), ObserverSite(0.0, 0.0), true, evidence.motionUpperBoundDeg, evidence) }
        assertThrows(IllegalArgumentException::class.java) { ReferencePairing.pair(camera, reference().copy(acquisitionDeviceId = "real-aux"),
            ObserverSite(0.0, 0.0), true, evidence.motionUpperBoundDeg, evidence) }
    }
}
