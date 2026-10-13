package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PhonePoseAlignmentTest {
    private val time = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
    private val site = ObserverSite(31.0, 121.0)
    private val direction = SkyDirection(110.0, 40.0)
    private val pose = SkyPose.fromSolution(direction, 120.0)
    private val observation = OpticalObservation("p", OpticalIdentity("phone", "0/2", "phone"),
        ObservationStamp(time, 10000, 1000, 2), direction, pose,
        CameraGeometry(1000, 800, parity = ImageParity.NORMAL), .01, OpticalObservationKind.PHONE_PLATE)
    private val sensor = AttitudeSnapshot(time, Direction3(0.0, 1.0, 0.0), Direction3(0.0, 0.0, 1.0), 10000)

    @Test fun fullRollAnchorPropagatesAcrossLargeAxisChanges() {
        val alignment = PhonePoseAlignment()
        alignment.align(observation, sensor, site)
        val current = alignment.pose(sensor, time, 10000)!!
        assertTrue(current.forward.angleDeg(pose.forward) < 1e-6)
        assertTrue(current.up.angleDeg(pose.up) < .01)
        val moved = sensor.copy(opticalAxis = Direction3(1.0, 0.0, 0.0))
        val next = alignment.pose(moved, time, 10000)!!
        assertTrue(next.forward.angleDeg(pose.right * -1.0) < .03)
    }
    @Test fun stationaryPhoneKeepsHorizonDirectionAsTheSkyMoves() {
        val alignment = PhonePoseAlignment()
        alignment.align(observation, sensor, site)
        val updated = alignment.pose(sensor.copy(elapsedMs = 20_000), time + 10_000, 20_000)!!.forward.direction()
        val before = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(direction.raDeg, direction.decDeg), Instant.ofEpochMilli(time), site, null)
        val after = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(updated.raDeg, updated.decDeg), Instant.ofEpochMilli(time + 10_000), site, null)
        assertEquals(before.altitudeDeg, after.altitudeDeg, 1e-6)
        assertEquals(before.azimuthDeg, after.azimuthDeg, 1e-6)
        assertTrue(updated.vector().angleDeg(direction.vector()) > .01)
        assertEquals(time, alignment.observation!!.stamp.utcMidpointMs)
    }
    @Test fun oldFixClockJumpAndUnknownMirrorExtrinsicsNeverBecomeNewAbsoluteProof() {
        val alignment = PhonePoseAlignment()
        alignment.align(observation, sensor, site)
        assertNull(alignment.pose(sensor, time + 60_001, 70_001))
        assertNull(alignment.pose(sensor, time + 1000, 11000))
        assertNull(alignment.pose(sensor, time + 1000, 10000))
        alignment.align(observation.copy(geometry = observation.geometry!!.copy(parity = ImageParity.MIRRORED)), sensor, site)
        assertNotNull(alignment.observation)
        assertNull(alignment.pose(sensor, time, 10000))
    }
}
