package com.indigo.mobileobservatory.camera

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewPipelineLifecycleInstrumentedTest {
    private fun frame(id: Long) = FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, id, id)

    @Test fun rapidRestartsWaitForBlockedConversionAndPublishOnlyCurrentGeneration() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val oldEntered = CountDownLatch(1)
        val oldMayReturn = CountDownLatch(1)
        val latestEntered = CountDownLatch(1)
        val latestPublished = CountDownLatch(1)
        val released = CountDownLatch(3)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val calls = CopyOnWriteArrayList<Long>()
        val published = CopyOnWriteArrayList<Long>()
        val oldReturned = CopyOnWriteArrayList<ByteArray>()
        val middleReturned = CopyOnWriteArrayList<ByteArray>()
        val latestReturned = CopyOnWriteArrayList<ByteArray>()
        val oldBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val latestBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val old = frame(1)
        val middle = frame(2)
        val latest = frame(3)
        val pipeline = PreviewPipeline(scope, renderFrame = { source ->
            val count = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, count) }
            calls += source.frameId
            try {
                if (source === old) {
                    oldEntered.countDown()
                    check(oldMayReturn.await(5, TimeUnit.SECONDS))
                    oldBitmap
                } else {
                    latestEntered.countDown()
                    latestBitmap
                }
            } finally { active.decrementAndGet() }
        }, targetFps = 60)
        try {
            pipeline.start(onProcessed = { published += it.frameId }, recycleBuffer = {
                oldReturned += it; released.countDown()
            })
            pipeline.submit(old)
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))

            // The intermediate worker may be cancelled before it starts, or while it
            // joins the first worker. Neither case may let the third reuse its buffers.
            pipeline.start(onProcessed = { published += it.frameId }, recycleBuffer = {
                middleReturned += it; released.countDown()
            })
            pipeline.submit(middle)
            pipeline.start(onProcessed = {
                published += it.frameId; latestPublished.countDown()
            }, recycleBuffer = { latestReturned += it; released.countDown() })
            pipeline.submit(latest)
            assertFalse("A restarted worker entered a still-running converter", latestEntered.await(200, TimeUnit.MILLISECONDS))
            assertNull(pipeline.frame.value)
            oldMayReturn.countDown()
            assertTrue(latestPublished.await(5, TimeUnit.SECONDS))
            assertTrue(released.await(5, TimeUnit.SECONDS))
            assertEquals(1, maximumActive.get())
            assertEquals(listOf(1L, 3L), calls)
            assertEquals(listOf(3L), published)
            assertSame(latestBitmap, pipeline.frame.value?.bitmap)
            assertEquals(1L, pipeline.frame.value?.sequence)
            assertEquals(listOf(old.data), oldReturned)
            assertEquals(listOf(middle.data), middleReturned)
            assertEquals(listOf(latest.data), latestReturned)
        } finally {
            oldMayReturn.countDown()
            pipeline.close()
            scope.cancel()
            job.children.toList().joinAll()
            oldBitmap.recycle()
            latestBitmap.recycle()
        }
    }

    @Test fun stopClearsPublishedFrameAndCancelledConversionCannotResurrectIt() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val mayReturn = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val releases = AtomicInteger()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val pipeline = PreviewPipeline(scope, renderFrame = {
            entered.countDown()
            check(mayReturn.await(5, TimeUnit.SECONDS))
            bitmap
        }, targetFps = 60)
        try {
            pipeline.start(onProcessed = { callbacks.incrementAndGet() }, recycleBuffer = {
                releases.incrementAndGet(); returned.countDown()
            })
            pipeline.submit(frame(1))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pipeline.stop(clearFrame = true)
            assertNull(pipeline.frame.value)
            mayReturn.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            job.children.toList().joinAll()
            assertNull(pipeline.frame.value)
            assertEquals(0, callbacks.get())
            assertEquals(1, releases.get())
        } finally {
            mayReturn.countDown()
            pipeline.close()
            scope.cancel()
            job.children.toList().joinAll()
            bitmap.recycle()
        }
    }
}
