package com.indigo.mobileobservatory.pointing

import org.junit.Assert.*
import org.junit.Test

class ObservationTimingTest {
    @Test fun cameraTimestampUsesElapsedRealtimeAndUnknownClocksFailClosed() {
        val stamp = requireNotNull(ObservationClock.fromRealtimeSensor(1_000_000_000, 1_000_000_000, true, 1_700_000_000_000, 3_000_000_000))
        assertEquals(1500L, stamp.elapsedMidpointMs)
        assertEquals(1_699_999_998_500, stamp.utcMidpointMs)
        assertNull(ObservationClock.fromRealtimeSensor(1_000_000_000, 1_000_000_000, false, 1_700_000_000_000, 3_000_000_000))
        assertNull(ObservationClock.fromRealtimeSensor(4_000_000_000, 1_000_000_000, true, 1_700_000_000_000, 3_000_000_000))
    }
    @Test fun midpointCannotUseStaleSensorFromBeforeCameraStartup() {
        val timeline = AttitudeTimeline()
        timeline.add(AttitudeSnapshot(1000, Direction3(0.0, 1.0, 0.0), Direction3(0.0, 0.0, 1.0), 500))
        assertNull(timeline.sampleElapsed(800))
        timeline.add(AttitudeSnapshot(2000, Direction3(0.0, 1.0, 0.0), Direction3(0.0, 0.0, 1.0), 1500))
        assertNull(timeline.sampleElapsed(1000))
    }
    @Test fun rollingPhoneWithoutChangingItsCenterIsStillMotion() {
        val timeline = AttitudeTimeline()
        timeline.add(AttitudeSnapshot(1000, Direction3(0.0, 1.0, 0.0), Direction3(0.0, 0.0, 1.0), 500))
        timeline.add(AttitudeSnapshot(1100, Direction3(0.0, 1.0, 0.0), Direction3(.1, 0.0, .995).unit(), 600))
        assertTrue(requireNotNull(timeline.motionDuring(500, 600)) > 5)
    }
    @Test fun equalEndpointsDoNotProveStillnessAcrossAnInteriorSensorGap() {
        val timeline = AttitudeTimeline()
        for (stamp in listOf(500L, 600L, 1200L, 1300L)) timeline.add(AttitudeSnapshot(stamp,
            Direction3(0.0, 1.0, 0.0), Direction3(0.0, 0.0, 1.0), stamp))
        assertNull(timeline.motionDuring(500, 1300))
    }
    @Test fun obliqueAxisMotionUsesTheFullRotationAngleRatherThanTheLargestBasisDisplacement() {
        val timeline = AttitudeTimeline()
        val axis = SkyVector(0.0, 1.0, 1.0).unit()
        val radians = Math.toRadians(.06)
        fun rotated(v: SkyVector) = v * kotlin.math.cos(radians) + axis.cross(v) * kotlin.math.sin(radians) +
            axis * (axis.dot(v) * (1 - kotlin.math.cos(radians)))
        val forward = rotated(SkyVector(0.0, 0.0, 1.0)); val up = rotated(SkyVector(0.0, 1.0, 0.0))
        timeline.add(AttitudeSnapshot(500, Direction3(0.0, 0.0, 1.0), Direction3(0.0, 1.0, 0.0), 500))
        timeline.add(AttitudeSnapshot(600, Direction3(forward.x, forward.y, forward.z), Direction3(up.x, up.y, up.z), 600))
        assertTrue(SkyVector(0.0, 0.0, 1.0).angleDeg(forward) < .05)
        assertTrue(SkyVector(0.0, 1.0, 0.0).angleDeg(up) < .05)
        assertEquals(.06, requireNotNull(timeline.motionDuring(500, 600)), 1e-8)
    }
}
