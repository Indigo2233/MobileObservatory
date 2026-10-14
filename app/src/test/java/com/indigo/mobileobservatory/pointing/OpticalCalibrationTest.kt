package com.indigo.mobileobservatory.pointing

import org.junit.Assert.*
import org.junit.Test

class OpticalCalibrationTest {
    private val phone = OpticalIdentity("phone", "rear-1", "phone")
    private val tube = OpticalIdentity("tube", "eyepiece", "main")
    private val geometry = CameraGeometry(1600, 1200, parity = ImageParity.NORMAL, intrinsicsFingerprint = "f1")
    private val offset = SkyVector(.35, -.2, .91).unit()
    private fun pair(id: String, ra: Double, dec: Double, roll: Double, error: Double = 0.0): CalibrationPair {
        val pose = SkyPose.fromSolution(SkyDirection(ra, dec), roll)
        val stamp = ObservationStamp(1_700_000_000_000, 9000, 1000, 5)
        val direction = pose.project(offset).direction()
        return CalibrationPair(OpticalObservation("p$id", phone, stamp, SkyDirection(ra, dec), pose, geometry, .015, OpticalObservationKind.PHONE_PLATE),
            OpticalObservation("r$id", tube, stamp, direction.copy(decDeg = direction.decDeg + error), null, null, .02, OpticalObservationKind.CENTERED_STAR))
    }
    @Test fun arbitraryRigidNonCoaxialAxisWorksAtOtherRaDecAndRoll() {
        val a = pair("1", 345.0, -15.0, 110.0); val b = pair("2", 70.0, 60.0, 310.0)
        val c = OpticalCalibrationFitter.fit(a, b)
        assertEquals(RelationshipModel.AXIS_ONLY, c.model)
        assertTrue(c.axisInSource.angleDeg(offset) < 1e-7)
        val other = pair("3", 190.0, -30.0, 20.0)
        val estimate = requireNotNull(c.estimate(other.camera, tube))
        assertTrue(estimate.direction.vector().angleDeg(other.reference.direction.vector()) < 1e-6)
    }
    @Test fun validationCannotBeItsOwnTrainingOrMountReadback() {
        val a = pair("1", 10.0, 30.0, 0.0); val b = pair("2", 60.0, 15.0, 50.0)
        assertThrows(IllegalArgumentException::class.java) { OpticalCalibrationFitter.fit(a, a) }
        assertThrows(IllegalArgumentException::class.java) { OpticalCalibrationFitter.fit(a, b.copy(reference = b.reference.copy(kind = OpticalObservationKind.MOUNT_MODEL))) }
        assertThrows(IllegalArgumentException::class.java) { OpticalCalibrationFitter.fit(a, b.copy(reference = b.reference.copy(independentReferenceId = a.reference.id))) }
    }
    @Test fun failedValidationRejectsInsteadOfAveragingTheErrorAway() {
        assertThrows(IllegalArgumentException::class.java) { OpticalCalibrationFitter.fit(pair("1", 20.0, 30.0, 0.0), pair("2", 80.0, 40.0, 100.0, 2.0)) }
    }
    @Test fun physicalLensMountingCropAndParityAreNotInterchangeable() {
        val a = pair("1", 20.0, 30.0, 0.0); val b = pair("2", 80.0, 40.0, 100.0)
        val c = OpticalCalibrationFitter.fit(a, b)
        assertNull(c.estimate(a.camera.copy(identity = phone.copy(physicalLensId = "rear-2")), tube))
        assertNull(c.estimate(a.camera.copy(identity = phone.copy(installationRevision = 1)), tube))
        assertNull(c.estimate(a.camera.copy(geometry = geometry.copy(parity = ImageParity.MIRRORED)), tube))
        assertNull(c.estimate(a.camera.copy(geometry = geometry.copy(intrinsicsFingerprint = "changed")), tube))
        assertNull(c.estimate(a.camera, tube.copy(opticsRevision = 1)))
    }
    @Test fun pairRejectsClockJumpUnknownTimeAndSeparatedExposures() {
        val a = pair("1", 20.0, 30.0, 0.0)
        listOf(a.reference.stamp.copy(utcMidpointMs = a.reference.stamp.utcMidpointMs + 5000),
            a.reference.stamp.copy(clockUncertaintyMs = 1000),
            a.reference.stamp.copy(utcMidpointMs = a.reference.stamp.utcMidpointMs + 2000, elapsedMidpointMs = 11000)).forEach {
            assertThrows(IllegalArgumentException::class.java) { a.copy(reference = a.reference.copy(stamp = it)).validate() }
        }
    }
    @Test fun fullPoseAndReflectionAreNotConflated() {
        val a = pair("1", 20.0, 30.0, 0.0); val b = pair("2", 80.0, 40.0, 100.0)
        val relative = SkyPose.fromSolution(offset.direction(), 20.0)
        fun withPose(p: CalibrationPair): CalibrationPair { val pose = requireNotNull(p.camera.pose).apply(relative); return p.copy(reference = p.reference.copy(pose = pose, direction = pose.forward.direction(), geometry = geometry, kind = OpticalObservationKind.MAIN_PLATE)) }
        val c = OpticalCalibrationFitter.fit(withPose(a), withPose(b))
        assertEquals(RelationshipModel.FULL_POSE, c.model)
        assertThrows(IllegalArgumentException::class.java) { SkyPose(SkyVector(-1.0, 0.0, 0.0), SkyVector(0.0, 1.0, 0.0), SkyVector(0.0, 0.0, 1.0)) }
    }
    @Test fun syncOrReconnectPreventsCounterDifferencing() {
        val revision = MountModelRevision(1, 1, 1, "EQ")
        val first = MountAxisObservation(359.0, 20.0, 10, .01, AxisReadingKind.MOTOR_COUNTER, revision)
        assertEquals(2.0, first.copy(axis1Deg = 1.0, elapsedMs = 20).deltaFrom(first)!!.first, 1e-9)
        assertNull(first.copy(elapsedMs = 20, revision = revision.copy(model = 2)).deltaFrom(first))
        assertNull(first.copy(elapsedMs = 20, revision = revision.copy(connection = 2)).deltaFrom(first))
        assertNull(first.copy(elapsedMs = 20, revision = revision.copy(zero = 2)).deltaFrom(first))
    }
}
