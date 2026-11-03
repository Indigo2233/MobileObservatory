package com.indigo.mobileobservatory.camera

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The automatic white balance must reach the picture of the very frame it was
 * measured on. A one frame delay is visible to the user and defeats the point
 * of a one-shot white balance.
 */
@RunWith(AndroidJUnit4::class)
class WhiteBalanceFrameInstrumentedTest {
    @Test fun theRgb24GainsReachTheFrameTheyWereMeasuredOn() {
        val processor = FrameProcessor()
        processor.setAwbContinuous(true)
        val bitmap = processor.frameToBitmap(rgb24Frame(red = 200, green = 100, blue = 100))
        // The gray world balance brings the red channel to the green level. Without
        // the measurement inside the same frame the first picture stays red.
        val red = Color.red(bitmap.getPixel(0, 0))
        val green = Color.green(bitmap.getPixel(0, 0))
        assertEquals("The balance must reach the frame it was measured on", green, red)
        assertEquals(100, green)
    }

    @Test fun theRgb48GainsReachTheFrameTheyWereMeasuredOn() {
        val processor = FrameProcessor()
        processor.setAwbContinuous(true)
        // Two brightness levels give the stretch a usable window.
        val bitmap = processor.frameToBitmap(rgb48Frame())
        val red = Color.red(bitmap.getPixel(0, 0))
        val green = Color.green(bitmap.getPixel(0, 0))
        assertEquals("The balance must reach the frame it was measured on", green, red)
        assertTrue("The red channel must be pulled down", red < 255)
    }

    @Test fun aOneShotBalanceAppliesToTheNextFrameOnlyOnce() {
        val processor = FrameProcessor()
        processor.triggerAwbOnce()
        val first = processor.frameToBitmap(rgb24Frame(red = 200, green = 100, blue = 100))
        val firstRed = Color.red(first.getPixel(0, 0))
        val firstGreen = Color.green(first.getPixel(0, 0))
        assertEquals(firstGreen, firstRed)
        // The gains stay in place; the balance does not run on every frame.
        val second = processor.frameToBitmap(rgb24Frame(red = 200, green = 100, blue = 100))
        assertEquals(firstRed, Color.red(second.getPixel(0, 0)))
        assertEquals(firstGreen, Color.green(second.getPixel(0, 0)))
    }

    private fun rgb24Frame(red: Int, green: Int, blue: Int): FrameData {
        val data = ByteArray(2 * 2 * 3)
        for (pixel in 0 until 4) {
            val base = pixel * 3
            data[base] = red.toByte()
            data[base + 1] = green.toByte()
            data[base + 2] = blue.toByte()
        }
        return FrameData(data, 2, 2, PixelFormat.RGB24, 1, 0)
    }

    private fun rgb48Frame(): FrameData {
        val data = ByteArray(2 * 2 * 6)
        // Two bright and two dim pixels; both are red-heavy.
        val pixels = listOf(
            Triple(40000, 20000, 20000), Triple(40000, 20000, 20000),
            Triple(10000, 5000, 5000), Triple(10000, 5000, 5000))
        for ((pixel, value) in pixels.withIndex()) {
            val (red, green, blue) = value
            val base = pixel * 6
            data[base] = (red and 0xFF).toByte()
            data[base + 1] = (red shr 8).toByte()
            data[base + 2] = (green and 0xFF).toByte()
            data[base + 3] = (green shr 8).toByte()
            data[base + 4] = (blue and 0xFF).toByte()
            data[base + 5] = (blue shr 8).toByte()
        }
        return FrameData(data, 2, 2, PixelFormat.RGB48, 1, 0)
    }
}
