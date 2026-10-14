package com.indigo.mobileobservatory.astro

import org.junit.Assert.*
import org.junit.Test

class FovPointingSourcesTest {
    private fun source(id: String = "phone", path: String = "primary", kind: FovPointingKind = FovPointingKind.CALIBRATED_OPTICAL,
        absolute: Long = 10_000, estimate: Long = 11_900, error: Double? = .02, ra: Double = 5.0,
        calibrated: Boolean = true, pa: Double? = 45.0) = FovPointingCandidate(path, FovSkyAnchor(ra, 20.0, "J2000"),
        id, kind, absolute, estimate, error, calibrated, "install-1", "$id-observation", pa)

    @Test fun mountReadbackOrWrongOpticalPathCanOnlyProvidePlanning() {
        for (candidate in listOf(source(kind = FovPointingKind.MOUNT_MODEL), source(path = "finder"))) {
            val decision = FovPointingSources.select("primary", listOf(candidate), 12_000)
            assertFalse(decision.showCurrent)
            assertNull(decision.currentAnchor)
            assertEquals(FovPointingRole.PLANNING, decision.role)
        }
    }

    @Test fun missingCalibrationAgeErrorOrCameraRollNeverProducesAFakeCurrentBox() {
        for (candidate in listOf(source(calibrated = false), source(absolute = 1_000), source(error = null), source(pa = null))) {
            val now = if (candidate.absoluteUtcMs == 1_000L) 40_000L else 12_000L
            assertFalse(FovPointingSources.select("primary", listOf(candidate), now, requireRoll = true).showCurrent)
        }
        val circle = FovPointingSources.select("primary", listOf(source(pa = null)), 12_000)
        assertTrue(circle.showCurrent)
    }

    @Test fun propagationCannotResetTheAbsoluteAgeAndNarrowFieldsRejectLargeUncertainty() {
        assertFalse(FovPointingSources.select("primary", listOf(source(kind = FovPointingKind.PROPAGATED,
            absolute = 1_000, estimate = 39_999)), 40_000).showCurrent)
        assertFalse(FovPointingSources.select("primary", listOf(source(error = .2)), 12_000, maxUncertaintyDeg = .05).showCurrent)
    }

    @Test fun sourceChoiceUsesErrorAndFreshnessWhileIndependentDisagreementIsVisible() {
        val finePhone = source(error = .01)
        val noisyMain = source("main", kind = FovPointingKind.DIRECT_OPTICAL, error = .1)
        val selected = FovPointingSources.select("primary", listOf(noisyMain, finePhone), 12_000)
        assertEquals("phone", selected.source!!.sourceId)
        assertEquals(FovPointingRole.CALIBRATED, selected.role)
        val conflict = FovPointingSources.select("primary", listOf(finePhone, source("main", ra = 6.0)), 12_000)
        assertEquals(FovPointingReason.CONFLICT, conflict.reason)
        assertFalse(conflict.showCurrent)
    }

    @Test fun measuredCameraPaIsPassedToCurrentRectangleWhilePlanningUsesTargetOnly() {
        val rectangle = FovComputation(FovInstrumentMode.SENSOR, rectWidthDeg = 2.0, rectHeightDeg = 1.0)
        val decision = FovPointingSources.select("primary", listOf(source()), 12_000, requireRoll = true)
        val scripts = StarMapFovOverlay.scripts(true, rectangle, "Measured", "Target", false,
            currentAnchor = decision.currentAnchor, currentPositionAngleDeg = decision.source!!.positionAngleDeg)
        assertTrue(scripts.first().contains("\"ICRF\",45.00000000"))
        val noSource = StarMapFovOverlay.scripts(true, rectangle, "", "Planning", false)
        assertTrue(noSource.first().contains("clearCurrentFovOverlay"))
        assertTrue(noSource[1].contains("setTargetRectFovOverlay"))
    }
}
