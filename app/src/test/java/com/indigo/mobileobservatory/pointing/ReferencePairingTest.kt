package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReferencePairingTest {
    private val site = ObserverSite(35.0, 120.0)
    private val time = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
    private val stamp = ObservationStamp(time, 100_000, 1000, 2)
    private val reference = OpticalObservation("ref", OpticalIdentity("telescope", "axis", "primary"), stamp,
        SkyDirection(15.0, 30.0), null, null, .05, OpticalObservationKind.CENTERED_STAR)
    private val phone = OpticalObservation("phone", OpticalIdentity("phone", "0/2", "phone"), stamp.copy(utcMidpointMs = time + 10_000, elapsedMidpointMs = 110_000),
        SkyDirection(20.0, 40.0), SkyPose.fromSolution(SkyDirection(20.0, 40.0), 45.0),
        CameraGeometry(1600, 1200, parity = ImageParity.NORMAL), .01, OpticalObservationKind.PHONE_PLATE)
    @Test fun stationaryDobPairKeepsSameHorizontalAxisWithoutRewritingOriginalObservation() {
        val pair = ReferencePairing.pair(phone, reference, site, false, .01)
        val first = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(reference.direction.raDeg, reference.direction.decDeg), Instant.ofEpochMilli(time), site, null)
        val second = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(pair.referenceDirection.raDeg, pair.referenceDirection.decDeg), Instant.ofEpochMilli(time + 10_000), site, null)
        assertEquals(first.altitudeDeg, second.altitudeDeg, 1e-6)
        assertEquals(first.azimuthDeg, second.azimuthDeg, 1e-6)
        assertSame(reference, pair.reference)
        assertEquals(time, pair.reference.stamp.utcMidpointMs)
        assertTrue(pair.pairingUncertaintyDeg > .04)
    }
    @Test fun trackingReferenceRemainsSiderealButStillBudgetsTimeUncertainty() {
        val pair = ReferencePairing.pair(phone, reference, site, true, .005)
        assertTrue(pair.referenceDirection.vector().angleDeg(reference.direction.vector()) < 1e-8)
        assertTrue(pair.pairingUncertaintyDeg >= .042)
    }
    @Test fun movementCannotBeCompensatedByPretendingTheObservationWasSimultaneous() {
        assertThrows(IllegalArgumentException::class.java) { ReferencePairing.pair(phone, reference, site, false, .2) }
        assertThrows(IllegalArgumentException::class.java) { ReferencePairing.pair(phone,
            reference.copy(stamp = stamp.copy(utcMidpointMs = time + 2000)), site, false, .005) }
    }
}
