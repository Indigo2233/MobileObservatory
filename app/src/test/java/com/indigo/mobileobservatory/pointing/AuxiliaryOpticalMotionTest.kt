package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.ObserverSite
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryOpticalMotionTest {
    private val stars = listOf(50.0 to 50.0, 150.0 to 230.0, 220.0 to 70.0,
        280.0 to 230.0, 350.0 to 130.0, 70.0 to 180.0).map { (x, y) -> OpticalStarPsf(x, y, 1.0, .8, 20.0, 1000.0) }
    private fun frame(id: Long, shift: Double = 0.0, generation: Long = 8) = OpticalFrameStability(id,
        (id - 1) * 1000, id * 1000, 1000, generation, 400, 300, stars.map { it.copy(x = it.x + shift) })
    private fun sample() : OpticalStabilitySample {
        val direction = SkyDirection(15.0, 35.0)
        val observation = OpticalObservation("aux", OpticalIdentity("actual-aux", "sensor", "secondary"),
            ObservationStamp(1_790_000_000_500, 500, 1000, 500), direction, SkyPose.fromSolution(direction, 0.0),
            CameraGeometry(400, 300, parity = ImageParity.NORMAL), .05, OpticalObservationKind.AUXILIARY_PLATE,
            plateScaleDegPerPixel = .0005)
        return OpticalStabilitySample(observation, stars, 0, 1000, 8, 1)
    }
    @Test fun measuredQuietFramesCanScheduleButClockTicksAloneNeverProveStillness() {
        val gate = AuxiliaryOpticalMotion(ObserverSite(35.0, 120.0), true)
        assertEquals(MotionPhase.SETTLING, gate.tick(5000, 1500).phase)
        for (id in 1L..4) gate.ingest(frame(id), "layout")
        assertEquals(MotionPhase.STILL, gate.state.phase)
        val epoch = gate.state.epoch
        gate.tick(7000, 1500)
        assertEquals(MotionPhase.SETTLING, gate.state.phase)
        assertFalse(gate.state.reliable); assertTrue(gate.state.epoch > epoch)
    }
    @Test fun newMovementClearsThePreviousProofEvenBelowBootstrapThreshold() {
        val gate = AuxiliaryOpticalMotion(ObserverSite(35.0, 120.0), true)
        for (id in 1L..4) gate.ingest(frame(id), "layout")
        gate.configure(sample(), certifiedQuiet = true, observedElapsedMs = 4000)
        val epoch = gate.state.epoch
        gate.ingest(frame(5, shift = 1.2), "layout")
        assertEquals(MotionPhase.MOVING, gate.state.phase); assertTrue(gate.state.epoch > epoch)
    }
    @Test fun droppedFrameChangedLayoutAndMissingStarsInvalidateTheMotionEpoch() {
        val gate = AuxiliaryOpticalMotion(ObserverSite(35.0, 120.0), true)
        for (id in 1L..4) gate.ingest(frame(id), "layout")
        val epoch = gate.state.epoch
        gate.ingest(frame(6), "layout")
        assertTrue(gate.state.epoch > epoch); assertNotEquals(MotionPhase.STILL, gate.state.phase)
        val afterGap = gate.state.epoch
        gate.ingest(frame(7), "new-layout")
        assertTrue(gate.state.epoch > afterGap)
        gate.ingest(frame(8).copy(stars = emptyList()), "new-layout")
        assertFalse(gate.state.reliable)
    }
    @Test fun controlledFreshAcquisitionCanRestartFrameIdsWithoutHidingActualMotion() {
        val gate = AuxiliaryOpticalMotion(ObserverSite(35.0, 120.0), true)
        for (id in 1L..4) gate.ingest(frame(id), "layout")
        gate.configure(sample(), certifiedQuiet = true, observedElapsedMs = 4000)
        val epoch = gate.state.epoch
        gate.expectAcquisitionRestart()
        gate.ingest(frame(1, generation = 9).copy(startElapsedMs = 4000, endElapsedMs = 5000), "layout")
        assertEquals(epoch, gate.state.epoch)
        gate.ingest(frame(2, shift = 2.0, generation = 9).copy(startElapsedMs = 5000, endElapsedMs = 6000), "layout")
        assertTrue(gate.state.epoch > epoch); assertEquals(MotionPhase.MOVING, gate.state.phase)
    }
    @Test fun auxiliaryUsbPolicyKeepsOldFixAgeLimitAndNeverLetsAnOldEpochTurnGreen() {
        val verification = PushToVerificationSession(VerificationPolicy(maxClockUncertaintyMs = 15000.0))
        verification.setTask("target", "real-aux-to-primary:v1")
        verification.observeMotion(0, MotionPhase.STILL, 1000)
        val ticket = requireNotNull(verification.beginCapture(1000))
        val evidence = OpticalVerificationEvidence(2000, 1500, 2500, 40.0, 100.0, 40.0, 100.0,
            .1, "real-aux-to-primary:v1", true, true, .005, 500.0)
        assertTrue(verification.complete(ticket, evidence, VerificationField(circleDiameterDeg = 2.0), 3000))
        assertTrue(verification.state.isVerified)
        verification.observeMotion(1, MotionPhase.MOVING, 3100)
        assertFalse(verification.state.isVerified)
        assertFalse(verification.complete(ticket, evidence, VerificationField(circleDiameterDeg = 2.0), 3200))
        val old = PushToVerificationSession(VerificationPolicy(maxClockUncertaintyMs = 15000.0))
        old.setTask("target", "real-aux-to-primary:v1")
        val oldTicket = requireNotNull(old.beginCapture(1000))
        assertFalse(old.complete(oldTicket, evidence, VerificationField(circleDiameterDeg = 2.0), 13000))
        assertEquals("observation_stale", old.state.rejectionReason)
    }
}
