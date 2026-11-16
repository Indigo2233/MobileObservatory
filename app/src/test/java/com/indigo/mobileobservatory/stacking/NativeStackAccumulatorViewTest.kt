package com.indigo.mobileobservatory.stacking

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class NativeStackAccumulatorViewTest {
    @Test
    fun borrowedExportReusesViewUntilAnotherFrameIsAccumulated() {
        val accumulator = requireNotNull(NativeStackAccumulator.create(8, 6, 1))
        accumulator.use {
            val image = LinearImage(8, 6, 1, FloatArray(48) { it.toFloat() })
            accumulator.accumulate(image, RigidTransform(), DeepSkyConfig(), 0.0, StackCancelCheck { false })
            val first = accumulator.finishBorrowed()
            val second = accumulator.finishBorrowed()
            assertSame(first.pixels, second.pixels)

            accumulator.accumulate(image, RigidTransform(), DeepSkyConfig(), 0.0, StackCancelCheck { false })
            val afterNextFrame = accumulator.finishBorrowed()
            assertNotSame(first.pixels, afterNextFrame.pixels)
        }
    }
}
