package com.indigo.mobileobservatory.astro

import org.junit.Assert.*
import org.junit.Test

class CurrentFovReferenceTest {
    private val now = 100_000L
    private val mount = FovSkyAnchor(5.0, 20.0, "JNOW")
    private val source = FovPointingCandidate("primary", FovSkyAnchor(5.01, 20.01, "J2000"),
        "camera", FovPointingKind.DIRECT_OPTICAL, now, now, .005, true, positionAngleDeg = 37.0)
    private fun optical(candidate: FovPointingCandidate = source) =
        FovPointingSources.select("primary", listOf(candidate), now)

    @Test fun mountReadbackRestoresCurrentReferenceWithoutClaimingOpticalVerification() {
        val reference = requireNotNull(StarMapFovOverlay.currentReference(FovPointingDecision(), false, mount))
        assertEquals(mount, reference.anchor)
        assertTrue(reference.mountEstimated)
        assertFalse(reference.fieldConfirmed)
        assertNull(reference.positionAngleDeg)
        assertFalse(FovPointingSources.select("primary", listOf(source.copy(kind = FovPointingKind.MOUNT_MODEL)), now).showCurrent)
    }

    @Test fun opticalPositionAndMeasuredAngleTakePriorityOverMount() {
        val reference = requireNotNull(StarMapFovOverlay.currentReference(optical(), true, mount))
        assertEquals(source.anchor, reference.anchor)
        assertEquals(37.0, reference.positionAngleDeg!!, 0.0)
        assertFalse(reference.mountEstimated)
        assertTrue(reference.fieldConfirmed)
    }

    @Test fun missingAngleAndUnconfirmedGeometryRetainAnExplicitOpticalReference() {
        val reference = requireNotNull(StarMapFovOverlay.currentReference(optical(source.copy(positionAngleDeg = Double.NaN)), false, mount))
        assertEquals(source.anchor, reference.anchor)
        assertFalse(reference.mountEstimated)
        assertFalse(reference.fieldConfirmed)
        assertNull(reference.positionAngleDeg)
    }

    @Test fun staleWrongPathAndConflictingOpticalSourcesFallBackToMountReference() {
        val rejected = listOf(optical(source.copy(absoluteUtcMs = now - 31_000)),
            optical(source.copy(opticalPathId = "secondary")),
            FovPointingSources.select("primary", listOf(source, source.copy(sourceId = "other",
                anchor = FovSkyAnchor(12.0, -20.0, "J2000"))), now))
        rejected.forEach {
            assertFalse(it.showCurrent)
            assertEquals(mount, StarMapFovOverlay.currentReference(it, true, mount)?.anchor)
        }
    }

    @Test fun absentOrInvalidMountDataNeverCreatesACurrentFrameAtTheMapCenter() {
        listOf(null, mount.copy(raHours = Double.NaN), mount.copy(decDegrees = Double.POSITIVE_INFINITY),
            mount.copy(decDegrees = 91.0), mount.copy(frame = "unknown")).forEach {
            assertNull(StarMapFovOverlay.currentReference(FovPointingDecision(), true, it))
        }
    }

    @Test fun mountMotionChangesOnlyCurrentAnchorAndClearingItKeepsTheTarget() {
        val target = FovSkyAnchor(7.0, 25.0, "J2000")
        fun scripts(anchor: FovSkyAnchor?) = StarMapFovOverlay.scripts(true,
            FovComputation(FovInstrumentMode.SENSOR, rectWidthDeg = .8, rectHeightDeg = .5),
            "Current mount estimate", "Target", false,
            currentAnchor = StarMapFovOverlay.currentReference(FovPointingDecision(), false, anchor)?.anchor,
            targetAnchor = target)
        val before = scripts(mount)
        val after = scripts(mount.copy(raHours = 6.0))
        assertTrue(before[0].contains("setCurrentRectFovOverlay"))
        assertNotEquals(before[0], after[0])
        assertEquals(before[1], after[1])
        assertTrue(scripts(null)[0].contains("clearCurrentFovOverlay"))
        assertEquals(before[1], scripts(null)[1])
    }
}
