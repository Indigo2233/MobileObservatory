package com.indigo.mobileobservatory.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarMapLoadRulesTest {
    @Test
    fun readyOnlyAcceptedWhileLoading() {
        assertEquals(
            StarMapEngineState.Ready,
            StarMapLoadRules.acceptReady(StarMapEngineState.Loading)
        )
        assertEquals(
            StarMapEngineState.Ready,
            StarMapLoadRules.acceptReady(StarMapEngineState.Ready)
        )
        val error = StarMapEngineState.Error("broken")
        assertEquals(error, StarMapLoadRules.acceptReady(error))
    }

    @Test
    fun failureDoesNotReplaceReadyEngine() {
        assertEquals(
            StarMapEngineState.Ready,
            StarMapLoadRules.acceptFailure(StarMapEngineState.Ready, "late error")
        )
        val failed = StarMapLoadRules.acceptFailure(StarMapEngineState.Loading, "missing")
        assertTrue(failed is StarMapEngineState.Error)
        assertEquals("missing", (failed as StarMapEngineState.Error).message)
    }

    @Test
    fun timeoutOnlyAppliesWhileLoading() {
        val timedOut = StarMapLoadRules.acceptTimeout(
            StarMapEngineState.Loading,
            "timeout"
        )
        assertEquals("timeout", (timedOut as StarMapEngineState.Error).message)
        assertEquals(
            StarMapEngineState.Ready,
            StarMapLoadRules.acceptTimeout(StarMapEngineState.Ready, "timeout")
        )
    }
}
