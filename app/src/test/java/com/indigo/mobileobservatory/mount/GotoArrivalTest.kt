package com.indigo.mobileobservatory.mount

import org.junit.Assert.assertEquals
import org.junit.Test

class GotoArrivalTest {
    @Test
    fun twoCloseSamplesArriveBeforeMotionIsJudged() {
        var state = GotoArrival.State()
        val first = GotoArrival.step(state, errorDeg = 0.04, movedSincePreviousDeg = null)
        state = first.first
        assertEquals(GotoArrival.Decision.CONTINUE, first.second)
        val second = GotoArrival.step(state, errorDeg = 0.03, movedSincePreviousDeg = 0.002)
        assertEquals(GotoArrival.Decision.ARRIVED, second.second)
    }

    @Test
    fun stoppedSlewArrivesEvenWhenResidualExceedsThreeArcmin() {
        var state = GotoArrival.State()
        state = GotoArrival.step(state, errorDeg = 2.0, movedSincePreviousDeg = 1.5).first
        state = GotoArrival.step(state, errorDeg = 0.20, movedSincePreviousDeg = 0.8).first
        assertEquals(true, state.sawSlewMotion)
        repeat(2) {
            val step = GotoArrival.step(state, errorDeg = 0.18, movedSincePreviousDeg = 0.003)
            state = step.first
            assertEquals(GotoArrival.Decision.CONTINUE, step.second)
        }
        val done = GotoArrival.step(state, errorDeg = 0.18, movedSincePreviousDeg = 0.003)
        assertEquals(GotoArrival.Decision.ARRIVED, done.second)
        assertEquals(true, done.first.sawSlewMotion)
    }

    @Test
    fun stillMountEndsTheCommandWithoutNeedingAStop() {
        var state = GotoArrival.State()
        state = GotoArrival.step(state, errorDeg = 8.0, movedSincePreviousDeg = null).first
        repeat(2) {
            val step = GotoArrival.step(state, errorDeg = 8.0, movedSincePreviousDeg = 0.0)
            state = step.first
            assertEquals(GotoArrival.Decision.CONTINUE, step.second)
            assertEquals(false, state.sawSlewMotion)
        }
        val done = GotoArrival.step(state, errorDeg = 8.0, movedSincePreviousDeg = 0.0)
        assertEquals(GotoArrival.Decision.ARRIVED, done.second)
        assertEquals(false, done.first.sawSlewMotion)
    }

    @Test
    fun ongoingSlewDoesNotArrive() {
        var state = GotoArrival.State()
        repeat(6) {
            val step = GotoArrival.step(state, errorDeg = 4.0, movedSincePreviousDeg = 0.4)
            state = step.first
            assertEquals(GotoArrival.Decision.CONTINUE, step.second)
            assertEquals(true, state.sawSlewMotion)
        }
    }
}
