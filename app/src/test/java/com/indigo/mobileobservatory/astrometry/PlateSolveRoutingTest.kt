package com.indigo.mobileobservatory.astrometry

import org.junit.Assert.assertEquals
import org.junit.Test

class PlateSolveRoutingTest {
    @Test fun phoneLensesAlwaysUseWideFieldEngine() {
        listOf(14.0, 24.0, 35.0, 50.0).forEach {
            assertEquals(PlateSolveEngine.PHONE_WIDE_FIELD, PlateSolveRouting.select(PlateSolveInputKind.PHONE_BUILT_IN, it))
        }
    }
    @Test fun externalAstapBoundaryIsTwoHundredMillimetres() {
        assertEquals(PlateSolveEngine.PARAMETERS_REQUIRED, PlateSolveRouting.select(PlateSolveInputKind.EXTERNAL_CAMERA, 199.9))
        assertEquals(PlateSolveEngine.ASTAP, PlateSolveRouting.select(PlateSolveInputKind.EXTERNAL_CAMERA, 200.0))
        assertEquals(PlateSolveEngine.PARAMETERS_REQUIRED, PlateSolveRouting.select(PlateSolveInputKind.EXTERNAL_CAMERA, null))
    }
}
