package com.indigo.mobileobservatory.camera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indigo.mobileobservatory.stacking.SerCfaInterpretation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraCfaPreviewBoundaryInstrumentedTest {
    @Test fun newCfaWhiteBalanceResetWaitsForOldRendererAndCannotBeOverwrittenByItsLateAwb() = runBlocking {
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Default)
        val oldEntered = CountDownLatch(1); val oldMayReturn = CountDownLatch(1)
        val reset = CountDownLatch(1); val published = CountDownLatch(1)
        val oldBitmap = Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        val processor = FrameProcessor().apply { autoStretchEnabled=false }
        val colorReset = PreviewColorReset()
        val completedResets = AtomicInteger()
        var freshBitmap: Bitmap? = null
        val pipeline = PreviewPipeline(scope, renderFrame={ frame ->
            if (frame.frameId == 1L) {
                oldEntered.countDown(); check(oldMayReturn.await(5, TimeUnit.SECONDS))
                processor.wbRedGain=.2f; processor.wbBlueGain=4f; processor.setAwbContinuous(true)
                oldBitmap
            } else processor.frameToBitmap(frame).also { freshBitmap=it }
        }, targetFps=60)
        try {
            pipeline.start(beforeRender={ colorReset.apply("old-CFA") { processor.resetWb(); completedResets.incrementAndGet() } })
            pipeline.submit(FrameData(ByteArray(4),2,2,PixelFormat.MONO8,1,0))
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
            fun resetNewColor() { colorReset.apply("new-CFA") { processor.resetWb(); completedResets.incrementAndGet(); reset.countDown() } }
            // B requests new CFA but is cancelled while waiting for A. C requests the same
            // CFA as B, and must still clear the old gains because B never entered beforeRender.
            pipeline.start(beforeRender=::resetNewColor)
            val run = pipeline.start(onProcessed={ published.countDown() }, beforeRender=::resetNewColor)
            val raw = ByteArray(24*24) { p ->
                val x=p%24; val y=p/24
                when { x%2==1 && y%2==1 -> 220; x%2==0 && y%2==0 -> 20; else -> 80 }.toByte()
            }
            val next = CameraCfaLayout.interpret(FrameData(raw,24,24,PixelFormat.MONO8,2,0), SerCfaInterpretation.BGGR)
            pipeline.submit(next, expectedGeneration=run)
            assertFalse("Reset ran while the old converter could still write AWB", reset.await(200, TimeUnit.MILLISECONDS))
            oldMayReturn.countDown()
            assertTrue(published.await(5, TimeUnit.SECONDS))
            assertEquals(1f, processor.wbRedGain, 0f); assertEquals(1f, processor.wbBlueGain, 0f)
            assertEquals(FrameProcessor.AwbMode.OFF, processor.awbMode)
            assertEquals(2,completedResets.get())
            val color = pipeline.frame.value!!.bitmap.getPixel(12,12)
            assertTrue("New CFA retained old channel gains", Color.red(color) > Color.blue(color) + 100)
        } finally {
            oldMayReturn.countDown(); pipeline.close(); scope.cancel(); job.children.toList().joinAll()
            oldBitmap.recycle(); freshBitmap?.recycle()
        }
    }

    @Test fun staleProducerTokenReturnsBytesToItsOwnCameraInsteadOfTheNewCameraRecycler() = runBlocking {
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Default)
        val oldReturns=AtomicInteger(); val newReturns=AtomicInteger(); val calls=AtomicInteger()
        val published=CountDownLatch(1)
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        val pipeline=PreviewPipeline(scope, renderFrame={ calls.incrementAndGet(); bitmap }, targetFps=60)
        try {
            val oldRun=pipeline.start(recycleBuffer={ oldReturns.incrementAndGet() })
            val newRun=pipeline.start(onProcessed={ published.countDown() }, recycleBuffer={ newReturns.incrementAndGet() })
            pipeline.submit(FrameData(ByteArray(4),2,2,PixelFormat.MONO8,1,0), expectedGeneration=oldRun,
                rejectedRecycler={ oldReturns.incrementAndGet() })
            assertEquals(1,oldReturns.get()); assertEquals(0,newReturns.get())
            pipeline.submit(FrameData(ByteArray(4),2,2,PixelFormat.MONO8,2,0), expectedGeneration=newRun)
            assertTrue(published.await(5,TimeUnit.SECONDS))
            withTimeout(5000) { while (newReturns.get()!=1) delay(10) }
            assertEquals(1,calls.get()); assertEquals(1,oldReturns.get()); assertEquals(1,newReturns.get())
        } finally { pipeline.close(); scope.cancel(); job.children.toList().joinAll(); bitmap.recycle() }
    }
}
