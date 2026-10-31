package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MountSettleTest {
    @Test fun stationaryMountWaitsThreeSecondsBeforeCapture() = runTest {
        awaitMountSettled(coordinates = { MountCoordinates(1.0, 30.0) }, elapsedMs = { currentTime })
        assertEquals(3000L, currentTime)
    }

    @Test fun renewedMotionRestartsTheQuietPeriod() = runTest {
        awaitMountSettled(
            moving = { currentTime in 1500L..2499L },
            coordinates = { MountCoordinates(1.0, 30.0) },
            elapsedMs = { currentTime })
        assertTrue(currentTime >= 5500L)
    }

    @Test fun coordinateDriftRestartsTheQuietPeriodEvenAfterBusyClears() = runTest {
        awaitMountSettled(coordinates = { MountCoordinates(if (currentTime < 2000) 1.0 else 1.1, 30.0) },
            elapsedMs = { currentTime })
        assertEquals(5000L, currentTime)
    }

    @Test fun stoppingDuringSettlingPreventsExposure() = runTest {
        var captured = false
        val task = launch {
            awaitMountSettled(coordinates = { null }, elapsedMs = { currentTime })
            captured = true
        }
        advanceTimeBy(1500)
        task.cancelAndJoin()
        advanceUntilIdle()
        assertFalse(captured)
    }

    @Test fun slowAccumulatedMovementAlsoRestartsSettling() = runTest {
        awaitMountSettled(coordinates = {
            MountCoordinates(1.0 + minOf(currentTime, 2000L) * .0000015, 0.0)
        }, elapsedMs = { currentTime })
        assertTrue(currentTime >= 4500L)
    }

    @Test fun initialCoordinateReadDoesNotConsumeTheQuietInterval() = runTest {
        var first = true
        awaitMountSettled(coordinates = {
            if (first) { first = false; delay(2000) }
            MountCoordinates(1.0, 30.0)
        }, elapsedMs = { currentTime })
        assertEquals(5000L, currentTime)
    }
}
