package com.indigo.mobileobservatory.camera.playerone

import org.junit.Assert.*
import org.junit.Test

class PlayerOneImageReadTest {
    @Test fun shortStreamingExposuresDoNotInheritTheReadinessPollingFrameRateLimit() {
        var reads = 0
        repeat(100) {
            assertTrue(awaitPlayerOneImage(active = { true },
                imageReady = { error("Short streaming exposure must use the SDK wait") },
                readImage = { reads++ }, waitForReady = false,
                pause = { error("Polling would throttle the stream") }))
        }
        assertEquals(100, reads)
    }

    @Test fun longExposureIsNeverReadBeforeTheSdkReportsReady() {
        for (exposureMs in listOf(5000L, 10000L, 30000L)) {
            var now = 0L
            var reads = 0
            assertTrue(awaitPlayerOneImage(
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
        assertFalse(awaitPlayerOneImage(
            active = { now < 1000 },
            imageReady = { false },
            readImage = { reads++ },
            pause = { now += 20 }))
        assertEquals(0, reads)
        assertEquals(1000L, now)
    }

    @Test fun continuousFramesEachWaitForTheirOwnExposureToComplete() {
        var now = 0L
        var frames = 0
        repeat(3) {
            val readyAt = now + 10_200L
            assertTrue(awaitPlayerOneImage(
                active = { true }, imageReady = { now >= readyAt },
                readImage = { assertTrue(now >= readyAt); frames++ }, pause = { now += 20 }))
        }
        assertEquals(3, frames)
        assertEquals(30_600L, now)
    }

    @Test fun stoppingDuringTheReadyQueryPreventsReadout() {
        var active = true
        assertFalse(awaitPlayerOneImage(
            active = { active }, imageReady = { active = false; true },
            readImage = { error("Read after stop") }))
    }
}
