package com.indigo.mobileobservatory.camera.playerone

import org.junit.Assert.*
import org.junit.Test

class PlayerOneSingleReadTest {
    @Test fun longExposureIsNeverReadBeforeTheSdkReportsReady() {
        for (exposureMs in listOf(5000L, 10000L, 30000L)) {
            var now = 0L
            var reads = 0
            assertTrue(awaitPlayerOneSingleImage(
                active = { true },
                imageReady = { now >= exposureMs + 50 },
                readImage = { check(now >= exposureMs + 50); reads++ },
                pause = { now += 20 }))
            assertEquals(1, reads)
            assertTrue(now >= exposureMs + 50)
        }
    }

    @Test fun stoppedExposureNeverReadsOrTriggersAnotherFrame() {
        var now = 0L
        var reads = 0
        assertFalse(awaitPlayerOneSingleImage(
            active = { now < 1000 },
            imageReady = { false },
            readImage = { reads++ },
            pause = { now += 20 }))
        assertEquals(0, reads)
        assertEquals(1000L, now)
    }

    @Test fun stoppingDuringTheReadyQueryPreventsReadout() {
        var active = true
        assertFalse(awaitPlayerOneSingleImage(
            active = { active }, imageReady = { active = false; true },
            readImage = { error("Read after stop") }))
    }
}
