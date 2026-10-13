package com.indigo.mobileobservatory.camera

import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisibleLightPreviewInstrumentedTest {
    @Test fun fullAe676SizedFrameKeepsOrangeAndBlueAcrossPreviewDownsampling() {
        val w = 3536; val h = 3536
        val data = ByteArray(w * h * 2)
        for (y in 0 until h) for (x in 0 until w) {
            val left = x < w / 2
            val value = when {
                x % 2 == 0 && y % 2 == 0 -> if (left) 200 else 30
                x % 2 == 1 && y % 2 == 1 -> if (left) 30 else 200
                else -> 80
            }
            // Full-range delivered 16-bit samples, independent of camera ADC depth.
            data[(y * w + x) * 2 + 1] = value.toByte()
        }
        val processor = FrameProcessor().apply { autoStretchEnabled = false }
        val image = processor.frameToBitmap(FrameData(data, w, h, PixelFormat.BAYER_RG16, 1, 0),
            PixelTraits(validBits = 16, alignment = SampleAlignment.RIGHT))
        assertEquals(1768, image.width)
        assertEquals(1768, image.height)
        assertRgb(image.getPixel(image.width / 4, image.height / 2), 199, 79, 29)
        assertRgb(image.getPixel(image.width * 3 / 4, image.height / 2), 29, 79, 199)
    }

    @Test fun onceWhiteBalanceUsesSensorChannelsBeforeStretchAndAppliesToTheSameFrame() {
        val processor = FrameProcessor().apply { autoStretchEnabled = true; triggerAwbOnce() }
        val image = processor.frameToBitmap(mosaic(16, 16, leftAligned = false, red = 200, green = 100, blue = 50),
            PixelTraits(validBits = 12, alignment = SampleAlignment.RIGHT))
        assertEquals(.5f, processor.wbRedGain, .001f)
        assertEquals(2f, processor.wbBlueGain, .001f)
        val pixel = image.getPixel(8, 8)
        assertEquals((pixel ushr 16) and 255, (pixel ushr 8) and 255)
        assertEquals((pixel ushr 8) and 255, pixel and 255)
        assertTrue((pixel and 255) > 0)
    }

    @Test fun sdkRightAndLeftAlignedTwelveBitRawHaveTheSamePreviewBrightnessAndColor() {
        val right = FrameProcessor().apply { autoStretchEnabled = false }.frameToBitmap(
            mosaic(16, 16, false, 192, 80, 32), PixelTraits(validBits = 12, alignment = SampleAlignment.RIGHT))
        val left = FrameProcessor().apply { autoStretchEnabled = false }.frameToBitmap(
            mosaic(16, 16, true, 192, 80, 32), PixelTraits(validBits = 12, alignment = SampleAlignment.LEFT))
        val a = IntArray(256); val b = IntArray(256)
        right.getPixels(a, 0, 16, 0, 0, 16, 16)
        left.getPixels(b, 0, 16, 0, 0, 16, 16)
        assertArrayEquals(a, b)
        assertRgb(right.getPixel(8, 8), 191, 79, 31)
    }

    private fun mosaic(w: Int, h: Int, leftAligned: Boolean, red: Int, green: Int, blue: Int): FrameData {
        val bytes = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) for (x in 0 until w) {
            val channel = when { x % 2 == 0 && y % 2 == 0 -> red; x % 2 == 1 && y % 2 == 1 -> blue; else -> green }
            val value = channel shl 4
            bytes.putShort((if (leftAligned) value shl 4 else value).toShort())
        }
        return FrameData(bytes.array(), w, h, PixelFormat.BAYER_RG12, 1, 0)
    }

    private fun assertRgb(pixel: Int, r: Int, g: Int, b: Int) {
        assertEquals(r, (pixel ushr 16) and 255)
        assertEquals(g, (pixel ushr 8) and 255)
        assertEquals(b, pixel and 255)
    }
}
