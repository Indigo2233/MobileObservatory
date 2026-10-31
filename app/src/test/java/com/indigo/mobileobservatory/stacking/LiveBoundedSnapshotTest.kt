package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveBoundedSnapshotTest {
    private fun stars(width: Int, height: Int): LinearImage {
        val pixels = FloatArray(width * height * 3) { .03f }
        for (cy in 24 until height - 12 step 31) for (cx in 24 until width - 12 step 37) {
            for (dy in -5..5) for (dx in -5..5) for (c in 0..2) {
                pixels[((cy + dy) * width + cx + dx) * 3 + c] +=
                    (.4 * (1 - c * .2) * exp(-(dx * dx + dy * dy) / 3.0)).toFloat()
            }
        }
        return LinearImage(width, height, 3, pixels)
    }

    @Test fun deepSkyPreviewIsBoundedAndImmutableWhileExportRetainsAllPixels() {
        val image = stars(320, 240)
        val engine = LiveDeepSkyEngine(128L shl 20, previewMaxSide = 80)
        assertTrue(engine.add(image, 5.0) { false }.accepted)
        val state = LiveStackState(accepted = 1, imageVersion = 1)
        val preview = engine.previewSnapshot(state, 80)!!
        val previewCopy = preview.image.pixels.copyOf()
        assertEquals(80, preview.image.width)
        assertEquals(60, preview.image.height)
        val full = engine.exportSnapshot(state)!!
        assertEquals(320, full.image.width)
        assertEquals(240, full.image.height)
        assertArrayEquals(image.pixels, full.image.pixels, 0f)
        assertSame(full.image.pixels, engine.exportSnapshot(state)!!.image.pixels)
        for (p in preview.weights.indices) {
            val source = p / 80 * 4 * 320 + p % 80 * 4
            for (c in 0..2) assertEquals(full.image.pixels[source * 3 + c], preview.image.pixels[p * 3 + c], 0f)
        }
        val changed = image.copy(pixels = FloatArray(image.pixels.size) { image.pixels[it] * 1.05f })
        assertTrue(engine.add(changed, 5.0) { false }.accepted)
        assertArrayEquals(previewCopy, preview.image.pixels, 0f)
    }

    @Test fun serializedExportDropsNewPacketsAndAlwaysRestoresIngestionAfterFailure() = runTest {
        val key = StackSessionKey("camera", 2, 2, PixelFormat.MONO8, Roi(0, 0, 2, 2),
            1, 1, 1_000_000.0, 0f, "none", "raw")
        fun frame(id: Long) = FrameData(ByteArray(4) { 64 }, 2, 2, PixelFormat.MONO8, id, 0)
        var previews = 0
        var fullSnapshots = 0
        var releases = 0
        val image = LinearImage(2, 2, 1, FloatArray(4) { .25f })
        val engine = object : LiveStackEngine {
            override fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean) =
                LiveFrameOutcome(true, 6, 0.0, 0.0)
            override fun snapshot(state: LiveStackState): LiveStackSnapshot {
                fullSnapshots++
                return LiveStackSnapshot(image, FloatArray(4) { 1f }, state)
            }
            override fun previewSnapshot(state: LiveStackState, maxSide: Int): LiveStackSnapshot {
                previews++
                return LiveStackSnapshot(LinearImage(1, 1, 1, floatArrayOf(.25f)), floatArrayOf(1f), state)
            }
        }
        val session = LiveStackSession(this, { engine }, { _, _ -> image }, {},
            onWorkerReset = { releases++ }, previewMaxSide = 1, queueCapacity = 1, maxInFlightFrames = 1)
        try {
            session.start(key)
            session.offer(frame(1), key)
            runCurrent()
            assertEquals(1, previews)
            assertEquals(0, fullSnapshots)
            assertEquals(1, session.latestSnapshot!!.image.width)
            val beforeExport = releases
            assertThrows(IllegalStateException::class.java) {
                session.withExportSnapshot(session.state.value.generation) {
                    assertEquals(2, it.image.width)
                    assertTrue(releases > beforeExport)
                    session.offer(frame(2), key)
                    error("export cancelled")
                }
            }
            assertEquals(1L, session.state.value.dropped)
            session.offer(frame(3), key)
            runCurrent()
            assertEquals(2L, session.state.value.accepted)
            assertEquals(LiveStackPhase.RUNNING, session.state.value.phase)
        } finally { session.close(); runCurrent() }
    }
}
