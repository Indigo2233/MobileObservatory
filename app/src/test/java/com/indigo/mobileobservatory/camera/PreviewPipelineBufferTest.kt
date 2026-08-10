package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPipelineBufferTest {
    @Test
    fun replacedAndStoppedFramesReturnToTheirCameraPool() {
        val scope = TestScope()
        val returned = mutableListOf<ByteArray>()
        val pipeline = PreviewPipeline(scope, FrameProcessor(), targetFps = 30)
        val first = ByteArray(16)
        val second = ByteArray(16)

        pipeline.start(
            paused = { true },
            recycleBuffer = returned::add
        )
        pipeline.submit(FrameData(first, 4, 4, PixelFormat.MONO8, 1, 1))
        pipeline.submit(FrameData(second, 4, 4, PixelFormat.MONO8, 2, 2))
        pipeline.stop()

        assertTrue(returned.size == 2)
        assertSame(first, returned[0])
        assertSame(second, returned[1])
    }
}
