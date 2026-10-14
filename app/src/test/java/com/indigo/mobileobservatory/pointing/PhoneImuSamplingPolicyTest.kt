package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PhoneImuSamplingPolicyTest {
    @Test fun continuousQuietDowngradesButUnknownOrSettlingMotionDoesNot() {
        val policy = PhoneImuSamplingPolicy()
        policy.start()
        assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(60_000))
        policy.observeMotion(MotionPhase.STILL, 60_000)
        assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(64_999))
        assertEquals(PhoneImuSamplingMode.IDLE, policy.desiredMode(65_000))
        assertEquals(100_000, policy.desiredMode(65_000)!!.periodUs)
        policy.observeMotion(MotionPhase.SETTLING, 65_100)
        assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(90_000))
    }

    @Test fun movingAndExplicitVerificationWakeImmediatelyWithQuietHysteresis() {
        val policy = idlePolicy()
        policy.observeMotion(MotionPhase.MOVING, 6_000)
        assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(6_000))
        policy.observeMotion(MotionPhase.SETTLING, 6_100)
        policy.observeMotion(MotionPhase.STILL, 6_500)
        assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(11_499))
        assertEquals(PhoneImuSamplingMode.IDLE, policy.desiredMode(11_500))
        policy.requestVerification(12_000)
        assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(12_000))
        assertEquals(PhoneImuSamplingMode.IDLE, policy.desiredMode(17_000))
    }

    @Test fun overlappingCaptureLeasesHoldActiveAndReleaseAfterFailureOrCancellation() {
        val policy = idlePolicy()
        val first = policy.beginVerification(6_000)
        val second = policy.beginVerification(7_000)
        try {
            assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(60_000))
            policy.endVerification(first, 60_000)
            assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(90_000))
            throw kotlinx.coroutines.CancellationException("STOP")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Source uses this same finally path for cancellation, resource failure and success.
        } finally {
            policy.endVerification(second, 90_000)
        }
        assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(94_999))
        assertEquals(PhoneImuSamplingMode.IDLE, policy.desiredMode(95_000))
    }

    @Test fun oldFinallyCannotReleaseANewSessionLeaseOrWakeAStoppedSource() {
        val policy = idlePolicy()
        val old = policy.beginVerification(6_000)
        policy.stop()
        policy.requestVerification(7_000)
        policy.endVerification(old, 7_000)
        assertNull(policy.desiredMode(7_000))
        policy.start()
        policy.observeMotion(MotionPhase.STILL, 8_000)
        val current = policy.beginVerification(8_000)
        assertNotEquals(old, current)
        policy.endVerification(old, 90_000)
        assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(100_000))
        policy.endVerification(current, 100_000)
        assertEquals(PhoneImuSamplingMode.IDLE, policy.desiredMode(105_000))
    }

    @Test fun idleSamplesStillDetectASlowPushAndWakeBeforeTheNextCapture() {
        val policy = idlePolicy()
        val gate = MotionGate()
        gate.consumeAutoCapture(); gate.notifyCaptureFinished()
        gate.ingest(0.0, 5_000)
        // Below the instantaneous move-on threshold, but above the existing noise floor.
        for (elapsed in 5_100L..6_200L step 100) {
            policy.observeMotion(gate.ingest(.02, elapsed), elapsed)
        }
        assertEquals(MotionPhase.MOVING, gate.phase)
        assertTrue(gate.motionEpoch > 0)
        assertEquals(PhoneImuSamplingMode.ACTIVE, policy.desiredMode(6_200))
        assertFalse(gate.consumeAutoCapture())
    }

    @Test fun overlappingQueuesIgnoreTransitionDuplicatesButRejectRealClockOrDeliveryGaps() {
        val order = PhoneImuSampleOrdering()
        fun sample(mode: PhoneImuSamplingMode, elapsedMs: Long, selected: PhoneImuSamplingMode = mode) =
            order.classify(mode, elapsedMs * 1_000_000, selected)
        assertEquals(PhoneImuSampleDisposition.ACCEPT, sample(PhoneImuSamplingMode.ACTIVE, 1_000))
        assertEquals(PhoneImuSampleDisposition.IGNORE, sample(PhoneImuSamplingMode.IDLE, 900, PhoneImuSamplingMode.ACTIVE))
        assertEquals(PhoneImuSampleDisposition.IGNORE, sample(PhoneImuSamplingMode.IDLE, 1_000))
        assertEquals(PhoneImuSampleDisposition.ACCEPT, sample(PhoneImuSamplingMode.IDLE, 1_100))
        assertEquals(PhoneImuSampleDisposition.IGNORE, sample(PhoneImuSamplingMode.ACTIVE, 1_040))
        assertEquals(PhoneImuSampleDisposition.ACCEPT, sample(PhoneImuSamplingMode.ACTIVE, 1_120))
        assertEquals(PhoneImuSampleDisposition.DISCONTINUITY, sample(PhoneImuSamplingMode.ACTIVE, 1_110))
        order.clear()
        assertEquals(PhoneImuSampleDisposition.ACCEPT, sample(PhoneImuSamplingMode.IDLE, 2_000))
        assertEquals(PhoneImuSampleDisposition.DISCONTINUITY, sample(PhoneImuSamplingMode.ACTIVE, 2_501))
        order.clear()
        assertEquals(PhoneImuSampleDisposition.ACCEPT, sample(PhoneImuSamplingMode.IDLE, 3_000))
        for (elapsed in 3_100L..3_600L step 100) {
            assertEquals(PhoneImuSampleDisposition.ACCEPT, sample(PhoneImuSamplingMode.IDLE, elapsed, PhoneImuSamplingMode.ACTIVE))
        }
        assertEquals(PhoneImuSampleDisposition.ACCEPT, sample(PhoneImuSamplingMode.ACTIVE, 3_620))
    }

    @Test fun idleToActiveTransitionPreservesTimedPoseAndExposureMotionEvidence() {
        val utc = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
        val site = ObserverSite(31.0, 121.0)
        val direction = SkyDirection(110.0, 40.0)
        val opticalPose = SkyPose.fromSolution(direction, 120.0)
        val observation = OpticalObservation("sample", OpticalIdentity("phone", "0/2", "phone"),
            ObservationStamp(utc, 10_000, 1_000, 2), direction, opticalPose,
            CameraGeometry(1_000, 800, parity = ImageParity.NORMAL), .01, OpticalObservationKind.PHONE_PLATE)
        val alignment = PhonePoseAlignment()
        val timeline = AttitudeTimeline()
        val order = PhoneImuSampleOrdering()
        fun record(mode: PhoneImuSamplingMode, elapsed: Long) {
            if (order.classify(mode, elapsed * 1_000_000, mode) == PhoneImuSampleDisposition.ACCEPT) {
                timeline.add(AttitudeSnapshot(utc + elapsed - 10_000,
                    Direction3(0.0, 1.0, 0.0), Direction3(0.0, 0.0, 1.0), elapsed))
            }
        }
        record(PhoneImuSamplingMode.ACTIVE, 10_000)
        alignment.align(observation, timeline.latest()!!, site)
        for (elapsed in 10_020L..11_000L step 20) record(PhoneImuSamplingMode.ACTIVE, elapsed)
        for (elapsed in 11_000L..13_000L step 100) record(PhoneImuSamplingMode.IDLE, elapsed)
        // The newly added fast queue can deliver an older first event; it is not a clock jump.
        record(PhoneImuSamplingMode.ACTIVE, 12_980)
        for (elapsed in 13_020L..13_600L step 20) record(PhoneImuSamplingMode.ACTIVE, elapsed)
        assertSame(observation, alignment.observation)
        assertEquals(0.0, timeline.motionDuring(12_850, 13_550)!!, 1e-8)
        assertNotNull(timeline.sampleElapsed(13_010))
        val current = alignment.pose(timeline.latest()!!, utc + 3_600, 13_600)!!
        val before = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(direction.raDeg, direction.decDeg), Instant.ofEpochMilli(utc), site, null)
        val after = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(current.forward.direction().raDeg, current.forward.direction().decDeg), Instant.ofEpochMilli(utc + 3_600), site, null)
        assertEquals(before.altitudeDeg, after.altitudeDeg, 1e-6)
        assertEquals(before.azimuthDeg, after.azimuthDeg, 1e-6)
        // The established freshness and UTC-jump boundaries remain in force at either rate.
        assertNull(alignment.pose(timeline.latest()!!, utc + 4_101, 14_101))
        assertNull(alignment.pose(timeline.latest()!!, utc + 4_600, 13_600))
    }

    @Test fun immediateActiveReRegistrationIgnoresItsFirstCachedDuplicateButKeepsClockChecks() {
        val order = PhoneImuSampleOrdering()
        fun active(elapsedMs: Long) = order.classify(PhoneImuSamplingMode.ACTIVE,
            elapsedMs * 1_000_000, PhoneImuSamplingMode.ACTIVE)
        assertEquals(PhoneImuSampleDisposition.ACCEPT, active(1_000))
        // Downgrade then immediately request verification, before any idle event arrives.
        order.activeQueueStarted()
        assertEquals(PhoneImuSampleDisposition.IGNORE, active(1_000))
        assertEquals(PhoneImuSampleDisposition.ACCEPT, active(1_020))
        assertEquals(PhoneImuSampleDisposition.DISCONTINUITY, active(1_019))
    }

    private fun idlePolicy() = PhoneImuSamplingPolicy().apply {
        start(); observeMotion(MotionPhase.STILL, 0)
        assertEquals(PhoneImuSamplingMode.IDLE, desiredMode(5_000))
    }
}
