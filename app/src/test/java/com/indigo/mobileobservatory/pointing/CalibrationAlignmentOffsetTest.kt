package com.indigo.mobileobservatory.pointing

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CalibrationAlignmentOffsetTest {
    private fun pair(local: SkyVector, ra: Double = 359.0, dec: Double = 71.0, roll: Double = 137.0): CalibrationPair {
        val pose = SkyPose.fromSolution(SkyDirection(ra, dec), roll)
        val stamp = ObservationStamp(1_700_000_000_000, 10_000, 1000, 5)
        return CalibrationPair(
            OpticalObservation("phone", OpticalIdentity("phone", "lens-2", "phone"), stamp,
                pose.forward.direction(), pose, CameraGeometry(1000, 800, parity = ImageParity.MIRRORED,
                    intrinsicsFingerprint = "known-normalized-geometry"), .015, OpticalObservationKind.PHONE_PLATE),
            OpticalObservation("centered", OpticalIdentity("tube", "axis", "primary"), stamp,
                pose.project(local).direction(), null, null, .02, OpticalObservationKind.CENTERED_STAR)
        )
    }

    @Test fun coaxialAxesHaveNoOffsetDespiteSkyOrientationAndRoll() {
        val offset = CalibrationAlignmentOffset.fromPair(pair(SkyVector(0.0, 0.0, 1.0)))
        assertEquals(0.0, offset.separationDeg, 1e-10)
        assertEquals(0.0, offset.horizontalDeg, 1e-10)
        assertEquals(0.0, offset.verticalDeg, 1e-10)
        assertEquals(.035, offset.uncertaintyDeg, 1e-10)
    }

    @Test fun signedHorizontalAndVerticalAreDefinedInTheCameraBasis() {
        val h = Math.toRadians(-12.0)
        val v = Math.toRadians(7.0)
        val local = SkyVector(cos(v) * sin(h), sin(v), cos(v) * cos(h))
        val a = CalibrationAlignmentOffset.fromPair(pair(local))
        val b = CalibrationAlignmentOffset.fromPair(pair(local, 71.0, -85.0, 20.0))
        assertEquals(-12.0, a.horizontalDeg, 1e-9)
        assertEquals(7.0, a.verticalDeg, 1e-9)
        assertEquals(a.horizontalDeg, b.horizontalDeg, 1e-9)
        assertEquals(a.verticalDeg, b.verticalDeg, 1e-9)
        assertEquals(SkyVector(0.0, 0.0, 1.0).angleDeg(local), a.separationDeg, 1e-9)
    }

    @Test fun nonOverlappingFieldsAndAnAxisBehindTheCameraRemainFinite() {
        val offset = CalibrationAlignmentOffset.fromPair(pair(SkyVector(.1, -.2, -.97).unit()))
        assertTrue(offset.separationDeg > 90.0)
        assertTrue(offset.horizontalDeg > 90.0)
        assertTrue(offset.verticalDeg < 0.0)
        assertTrue(offset.horizontalDeg.isFinite() && offset.verticalDeg.isFinite())
    }

    @Test fun staleOrUnnormalizedPairCannotProvideAnAlignmentMeasurement() {
        val good = pair(SkyVector(.1, .1, .99).unit())
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationAlignmentOffset.fromPair(good.copy(camera = good.camera.copy(
                geometry = good.camera.geometry!!.copy(parity = ImageParity.UNKNOWN))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationAlignmentOffset.fromPair(good.copy(reference = good.reference.copy(
                kind = OpticalObservationKind.MOUNT_MODEL)))
        }
    }
}
