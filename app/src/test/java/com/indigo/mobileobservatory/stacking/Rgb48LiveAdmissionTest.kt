package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Rgb48LiveAdmissionTest {
    private fun key(width: Int = 3856, height: Int = 2180, format: PixelFormat = PixelFormat.RGB48) =
        StackSessionKey("rgb48-camera", width, height, format, Roi(0, 0, width, height),
            1, 1, 5_000_000.0, 100f, "none", "raw")

    @Test fun full585Rgb48UsesTheActualWorkingBudgetIncludingCalibration() {
        val input = key()
        assertTrue(input.width.toLong() * input.height * input.format.bytesPerPixel > 32L shl 20)
        val needed = LiveStackAdmission.workingBytes(input, 0, false)
        println("585 RGB48 full: working=$needed ceiling=${StackMemoryBudget.forHeap(512L shl 20)}")
        assertNull(LiveStackAdmission.issue(input, StackMemoryBudget.forHeap(512L shl 20)))
        assertNull(LiveStackAdmission.issue(input, needed))
        assertEquals("memory_budget", LiveStackAdmission.issue(input, needed - 1))
        val calibrated = LiveStackAdmission.workingBytes(input, 64L shl 20, true)
        assertTrue(calibrated > needed)
        assertNull(LiveStackAdmission.issue(input, calibrated, 64L shl 20, true))
        assertEquals("memory_budget", LiveStackAdmission.issue(input, calibrated - 1, 64L shl 20, true))
    }

    @Test fun fullRgb48PacketIsCopiedOnceAndTheBoundedQueueStillDropsOverflow() = runTest {
        val input = key()
        val bytes = ByteArray(input.width * input.height * input.format.bytesPerPixel)
        bytes[0] = 11; bytes[bytes.lastIndex] = 23
        var decodes = 0
        val session = LiveStackSession(this, { acceptingEngine() }, { packet, _ ->
            decodes++
            assertNotSame(bytes, packet.data)
            assertEquals(11, packet.data[0].toInt())
            assertEquals(23, packet.data.last().toInt())
            LinearImage(1, 1, 3, floatArrayOf(.1f, .2f, .3f))
        }, {}, queueCapacity = 1, maxInFlightFrames = 1)
        try {
            session.start(input)
            session.offer(FrameData(bytes, input.width, input.height, input.format, 1, 0), input)
            assertEquals(LiveStackPhase.RUNNING, session.state.value.phase)
            bytes[0] = 99; bytes[bytes.lastIndex] = 99
            session.offer(FrameData(bytes, input.width, input.height, input.format, 2, 0), input)
            assertEquals(1L, session.state.value.dropped)
            runCurrent()
            assertEquals(1, decodes)
            assertEquals(1L, session.state.value.accepted)
        } finally { session.close(); runCurrent() }
    }

    @Test fun anExplicitSmallerPacketBudgetStillRejectsBeforeDecode() = runTest {
        val input = key()
        var decodes = 0
        val session = LiveStackSession(this, { acceptingEngine() }, { _, _ ->
            decodes++
            error("Rejected packet reached decode")
        }, {}, maxInputBytes = 32L shl 20)
        try {
            session.start(input)
            session.offer(FrameData(ByteArray(input.width * input.height * 6),
                input.width, input.height, input.format, 1, 0), input)
            runCurrent()
            assertEquals(LiveStackPhase.FAILED, session.state.value.phase)
            assertEquals("input_budget", session.state.value.reason)
            assertEquals(0, decodes)
        } finally { session.close(); runCurrent() }
    }

    @Test fun thePixelCeilingAppliesBeforeCopyEvenForSmallSampleFormats() = runTest {
        val input = key(4096, 4097, PixelFormat.MONO8)
        assertTrue(input.width.toLong() * input.height > LinearImage.MAX_PIXELS)
        var decodes = 0
        val session = LiveStackSession(this, { acceptingEngine() }, { _, _ ->
            decodes++
            LinearImage(1, 1, 1, floatArrayOf(.1f))
        }, {})
        try {
            session.start(input)
            session.offer(FrameData(ByteArray(input.width * input.height),
                input.width, input.height, input.format, 1, 0), input)
            assertEquals(LiveStackPhase.FAILED, session.state.value.phase)
            runCurrent()
            assertEquals(0, decodes)
        } finally { session.close(); runCurrent() }
    }

    private fun acceptingEngine() = object : LiveStackEngine {
        override fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean) =
            LiveFrameOutcome(true, 6, 0.0, 0.0)
        override fun snapshot(state: LiveStackState): LiveStackSnapshot? = null
    }
}
