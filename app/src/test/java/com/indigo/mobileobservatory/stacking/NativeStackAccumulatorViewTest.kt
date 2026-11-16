package com.indigo.mobileobservatory.stacking

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

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

    @Test
    fun callbackCancellationClearsJniStateAndBorrowedView() {
        val accumulator = requireNotNull(NativeStackAccumulator.create(8, 6, 1))
        accumulator.use {
            val image = LinearImage(8, 6, 1, FloatArray(48) { it.toFloat() })
            accumulator.accumulate(image, RigidTransform(), DeepSkyConfig(), 0.0, StackCancelCheck { false })
            val before = accumulator.finishBorrowed()

            assertThrows(CancellationException::class.java) {
                accumulator.accumulate(image, RigidTransform(), DeepSkyConfig(), 0.0, StackCancelCheck {
                    throw CancellationException("cancel callback")
                })
            }

            val after = accumulator.finishBorrowed()
            assertNotSame(before.pixels, after.pixels)
            assertTrue(after.pixels.all { it.isFinite() })
        }
    }
}
