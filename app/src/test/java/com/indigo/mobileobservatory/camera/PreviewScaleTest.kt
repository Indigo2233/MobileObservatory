package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewScaleTest {
    @Test
    fun bin2PreviewDownsamplesToFitBudget() {
        val step = PreviewScale.sampleStep(4784, 3194, keepBayerPhase = true)
        assertEquals(0, step % 2)
        assertTrue(step >= 2)
        val outPixels = (4784 / step).toLong() * (3194 / step)
        assertTrue(outPixels <= PreviewScale.MAX_PIXELS)
    }

    @Test
    fun bin1FullFrameUsesEvenStep() {
        val step = PreviewScale.sampleStep(9576, 6388, keepBayerPhase = true)
        assertEquals(0, step % 2)
        assertTrue(step >= 4)
        val outPixels = (9576 / step).toLong() * (6388 / step)
        assertTrue(outPixels <= PreviewScale.MAX_PIXELS)
    }

    @Test
    fun subsampleKeepsBayerPhase() {
        val w = 8
        val h = 4
        val src = ByteArray(w * h) { i -> i.toByte() }
        val dst = ByteArray(w * h)
        val (outW, outH) = PreviewScale.subsample(src, w, h, 1, 2, dst)
        assertEquals(4, outW)
        assertEquals(2, outH)
        assertEquals(0.toByte(), dst[0])
        assertEquals(2.toByte(), dst[1])
        assertEquals(16.toByte(), dst[outW])
    }
}
