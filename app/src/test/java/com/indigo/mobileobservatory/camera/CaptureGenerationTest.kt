package com.indigo.mobileobservatory.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CaptureGenerationTest {
    @Test fun aWriteInvalidatesScienceImmediatelyAndMorePreviewFramesCannotRestoreIt() {
        val gate=CaptureGeneration()
        val run=gate.begin(true)
        val old=frame(run)
        assertEquals(run,gate.scienceGeneration)
        gate.invalidateScience()
        repeat(50) {
            assertTrue("Ordinary preview remains available",gate.isActive(run))
            assertNull(gate.scienceGeneration)
            assertEquals(run,old.captureGeneration)
        }
        assertThrows(IllegalStateException::class.java) { gate.begin(true) }
    }

    @Test fun aQueuedOldExposureCannotAcquireNewSettingsAfterRestart() {
        val gate=CaptureGeneration()
        val started=CountDownLatch(1)
        val release=CountDownLatch(1)
        val delivered=AtomicReference<FrameData>()
        val first=gate.begin(true)
        // Model a blocked SDK read or Player One delivery slot holding the original token.
        val reader=Thread {
            val queued=frame(first)
            started.countDown()
            check(release.await(3,TimeUnit.SECONDS))
            delivered.set(queued)
        }.apply { start() }
        try {
            assertTrue(started.await(3,TimeUnit.SECONDS))
            gate.invalidateScience(); gate.stop()
            val replacement=gate.begin(true)
            release.countDown(); reader.join(3000)
            assertFalse(reader.isAlive)
            assertEquals(first,delivered.get().captureGeneration)
            assertNotEquals(gate.scienceGeneration,delivered.get().captureGeneration)
            assertFalse(gate.isActive(first))
            assertTrue(gate.isActive(replacement))
        } finally { release.countDown(); reader.join(3000) }
    }

    @Test fun failedReadbackAllowsPreviewButNeverVerifiedScience() {
        val gate=CaptureGeneration()
        val unverified=gate.begin(false)
        assertTrue(gate.isActive(unverified))
        assertNull(gate.scienceGeneration)
        gate.stop()
        assertFalse(gate.isActive(unverified))
        val verified=gate.begin(true)
        assertEquals(verified,gate.scienceGeneration)
        assertNotEquals(unverified,verified)
    }

    @Test fun generationsCannotCollideAcrossCamerasOrReconnection() {
        val first=CaptureGeneration(); val second=CaptureGeneration()
        val a=first.begin(true); val b=second.begin(true)
        first.stop(); second.stop()
        val c=first.begin(true)
        assertEquals(3,setOf(a,b,c).size)
        assertFalse(second.isActive(a))
        assertFalse(first.isActive(b))
    }

    @Test fun copyingCameraBytesPreservesProvenanceButLegacyFramesStayUnknown() {
        val token=CaptureGeneration().begin(true)
        val original=frame(token)
        val copy=original.copy(data=original.data.copyOf())
        assertEquals(token,copy.captureGeneration)
        assertNotSame(original.data,copy.data)
        assertNull(FrameData(ByteArray(4),2,2,PixelFormat.MONO8,1,0).captureGeneration)
    }

    private fun frame(generation: Long)=FrameData(ByteArray(4),2,2,PixelFormat.MONO8,1,0,generation)
}
