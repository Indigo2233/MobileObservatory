package com.indigo.mobileobservatory.pointing

import org.junit.Assert.*
import org.junit.Test

class PushToVerificationSessionTest {
    private val field = VerificationField(circleDiameterDeg = 1.5)
    private fun evidence(ticket: VerificationTicket, error: Double? = .02, motion: Double? = 0.0) =
        OpticalVerificationEvidence(ticket.requestedElapsedMs + 100, ticket.requestedElapsedMs,
            ticket.requestedElapsedMs + 200, 45.0, 20.0, 45.01, 20.01, error, "install-1",
            true, true, motion, 10.0)
    private fun session() = PushToVerificationSession().apply { setTask("M42", "install-1") }

    @Test fun wideUsbTimeBracketCannotMakeAnOldExposureFresh() {
        val s = PushToVerificationSession(VerificationPolicy(maxClockUncertaintyMs = 15_000.0))
            .apply { setTask("M42", "install-1") }
        val ticket = s.beginCapture(1_000)!!
        val buffered = evidence(ticket).copy(observedElapsedMs = 8_000, exposureStartElapsedMs = 1_000,
            exposureEndElapsedMs = 15_000, clockUncertaintyMs = 7_000.0)
        assertFalse(s.complete(ticket, buffered, field, 15_001))
        assertEquals("observation_stale", s.state.rejectionReason)
    }

    @Test fun opticalProofIsRequiredAndEveryMovementInvalidatesItsGreenState() {
        val s = session()
        assertFalse(s.state.isVerified)
        val first = s.beginCapture(1_000)!!
        assertTrue(s.complete(first, evidence(first), field, 1_300))
        assertTrue(s.state.isVerified)
        s.observeMotion(1, MotionPhase.MOVING, 1_400)
        assertFalse(s.state.isVerified)
        assertNull(s.beginCapture(1_500))
        s.observeMotion(1, MotionPhase.STILL, 1_900)
        assertNull("Cooldown does not forget the need for new proof", s.beginCapture(2_000))
        assertEquals(2_800L, s.nextWakeAtMs)
        s.tick(2_800)
        val second = s.beginCapture(2_800)!!
        assertTrue(s.complete(second, evidence(second), field, 3_100))
        assertTrue(s.state.isVerified)
    }

    @Test fun taskChangeAndExposureMovementRejectLateCallbacks() {
        val s = session()
        val old = s.beginCapture(1_000)!!
        s.setTask("M31", "install-1")
        assertFalse(s.complete(old, evidence(old), field, 1_300))
        assertFalse(s.state.isVerified)
        val fresh = s.beginCapture(1_400)!!
        s.observeMotion(1, MotionPhase.MOVING, 1_450)
        s.observeMotion(1, MotionPhase.STILL, 1_900)
        assertFalse(s.complete(fresh, evidence(fresh), field, 2_000))
        val moved = s.beginCapture(2_100)!!
        assertFalse(s.complete(moved, evidence(moved, motion = .1), field, 2_400))
        assertEquals("exposure_motion", s.state.rejectionReason)
    }

    @Test fun unknownOrCorrelatedReferenceCannotCertifyAnImuNearTarget() {
        for (kind in 0..3) {
            val s = session(); val ticket = s.beginCapture(1_000)!!
            val e = when (kind) {
                0 -> evidence(ticket, error = null)
                1 -> evidence(ticket).copy(independentOpticalReference = false)
                2 -> evidence(ticket, motion = null)
                else -> evidence(ticket).copy(calibrationValid = false)
            }
            assertFalse(s.complete(ticket, e, field, 1_300))
            assertFalse(s.state.isVerified)
        }
        assertTrue("Old solved plus IMU-near never suppresses this movement's capture", PushToAutoSolve.shouldFire(
            MotionPhase.STILL, true, true, true, false, 5_000, 0))
    }

    @Test fun retriesBackOffAndAreBoundedAndExplicitRetryCanRecover() {
        val s = session()
        var now = 1_000L
        for (attempt in 1..5) {
            val ticket = s.beginCapture(now)!!
            s.fail(ticket, now + 1)
            assertFalse(s.state.isVerified)
            assertEquals(attempt, s.state.consecutiveFailures)
            if (attempt < 5) {
                val next = s.nextWakeAtMs!!
                assertNull(s.beginCapture(next - 1))
                now = next
            }
        }
        assertEquals(VerificationPhase.RETRY_LIMIT, s.state.phase)
        assertNull(s.nextWakeAtMs)
        assertNull(s.beginCapture(now + 60_000))
        s.retry(now + 60_001)
        assertNotNull(s.beginCapture(now + 60_001))
    }

    @Test fun clockExpiresAnUnmovingDobAndCaptureTimeoutDoesNotAcceptLateResult() {
        val s = session(); val ticket = s.beginCapture(1_000)!!
        s.complete(ticket, evidence(ticket), field, 1_300)
        assertEquals(11_090L, s.nextWakeAtMs)
        s.tick(11_090)
        assertFalse(s.state.isVerified)
        val retry = s.beginCapture(11_100)!!
        s.tick(56_100)
        assertEquals("capture_timeout", s.state.rejectionReason)
        assertFalse(s.complete(retry, evidence(retry), field, 56_101))
    }

    @Test fun narrowFieldAndRectangleRollRespectTheWholeErrorBudget() {
        val narrow = VerificationField(circleDiameterDeg = .1)
        assertFalse(narrow.contains(45.0, 20.0, 45.0, 20.0, .03))
        assertTrue(narrow.contains(45.0, 20.0, 45.0, 20.0, .01))
        val unknownRoll = VerificationField(widthDeg = 2.0, heightDeg = .2)
        assertFalse(unknownRoll.contains(0.0, 0.0, 0.0, .2, 0.0))
        assertTrue(unknownRoll.copy(altitudeUpRollDeg = 0.0).contains(0.0, 0.0, 0.0, .2, 0.0))
        assertFalse(unknownRoll.copy(altitudeUpRollDeg = 90.0).contains(0.0, 0.0, 0.0, .2, 0.0))
    }

    @Test fun slowAndSubDegreePushesInvalidateAndSettleOnAClock() {
        val gate = MotionGate()
        gate.consumeAutoCapture(); gate.notifyCaptureFinished()
        gate.ingest(.1, 1_000)
        gate.ingest(.1, 1_100)
        gate.ingest(.1, 1_300)
        assertTrue(gate.motionEpoch > 0)
        assertEquals(MotionPhase.MOVING, gate.phase)
        val epoch = gate.motionEpoch
        gate.ingest(0.0, 1_400)
        assertEquals(1_800L, gate.nextWakeAtMs)
        assertEquals(MotionPhase.STILL, gate.tick(1_800))
        assertTrue(gate.autoCaptureDue)
        gate.notifyCaptureFinished(epoch - 1)
        assertTrue("A stale exposure cannot erase new movement", gate.autoCaptureDue)
    }

    @Test fun sphericalDistanceRemainsValidAtZenithAzimuthSeamAndAntipodes() {
        assertEquals(0.0, PushToGuidance.compute(90.0, 0.0, 90.0, 180.0).separationDeg, 1e-12)
        assertEquals(2.0, PushToGuidance.compute(0.0, 359.0, 0.0, 1.0).separationDeg, 1e-10)
        assertEquals(180.0, PushToGuidance.compute(0.0, 0.0, 0.0, 180.0).separationDeg, 1e-10)
        assertTrue(PushToGuidance.compute(89.0, 0.0, 89.0, 90.0).separationDeg < 1.5)
    }

    @Test fun sensorDiscontinuityCannotBecomeStillWithoutANewQuietSample() {
        val gate = MotionGate()
        gate.invalidateForDiscontinuity(1_000)
        assertNull(gate.nextWakeAtMs)
        assertEquals(MotionPhase.SETTLING, gate.tick(10_000))
        assertFalse(gate.consumeAutoCapture())
        gate.ingest(0.0, 10_001)
        assertEquals(10_401L, gate.nextWakeAtMs)
        assertEquals(MotionPhase.STILL, gate.tick(10_401))
        assertTrue(gate.consumeAutoCapture())
    }

    @Test fun rectangleUsesImageUpRollBeforeComparingItsUnequalSides() {
        val rect = VerificationField(widthDeg = 2.0, heightDeg = .2, altitudeUpRollDeg = 45.0)
        assertFalse(rect.contains(0.0, 0.0, .2, .2, 0.0))
        assertTrue(rect.copy(altitudeUpRollDeg = -45.0).contains(0.0, 0.0, .2, .2, 0.0))
    }

    @Test fun fixedDobAndMovingSkyUseNewTimeEvenWithNoSensorEvents() {
        val direction = com.indigo.mobileobservatory.astro.EquatorialCoordinates(120.0, 0.0)
        val site = com.indigo.mobileobservatory.astro.ObserverSite(45.0, 0.0)
        val start = java.time.Instant.ofEpochMilli(1_700_000_000_000)
        val first = com.indigo.mobileobservatory.astro.CoordinateTransform.j2000ToTopocentric(direction, start, site, null)
        val later = com.indigo.mobileobservatory.astro.CoordinateTransform.j2000ToTopocentric(direction, start.plusSeconds(60), site, null)
        val drift = PushToGuidance.separationDeg(first.altitudeDeg, first.azimuthDeg, later.altitudeDeg, later.azimuthDeg)
        assertTrue("Sidereal drift is a spherical angle, independent of azimuth singularities: $drift", drift in .20.. .30)
        assertFalse(VerificationField(circleDiameterDeg = .1).contains(first.altitudeDeg, first.azimuthDeg,
            later.altitudeDeg, later.azimuthDeg, .001))
    }
}
