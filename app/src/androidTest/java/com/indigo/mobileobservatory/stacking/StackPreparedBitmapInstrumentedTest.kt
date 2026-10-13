package com.indigo.mobileobservatory.stacking

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StackPreparedBitmapInstrumentedTest {
    private fun image(channels: Int): LinearImage {
        val w = 129; val h = 97
        return LinearImage(w,h,channels,FloatArray(w*h*channels) { i ->
            val x = (i/channels)%w; val y = (i/channels)/w
            (.01+.0003*x+.0002*y+.01*(i%channels)+.17*exp(-((x-56.3).pow(2)+(y-40.2).pow(2))/20)).toFloat()
        })
    }
    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width*bitmap.height).also {
        bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    }

    @Test fun directRowsMatchFullFloatMappingAtFullAndPreviewSizesAndPngRoundTrip() {
        for (channels in listOf(1,3)) {
            val source = image(channels); val cache = BackgroundModelCache()
            val recipes = listOf(StackProcessingParameters(),
                StackProcessingParameters(autoWhiteBalance=true,sharpening=.7,localContrast=.2,saturation=1.6),
                StackProcessingParameters(background=BackgroundParameters(enabled=false),stretchEnabled=false),
                StackProcessingParameters.preset(ProcessingPreset.RAW))
            for (parameters in recipes) {
                val processed = StackPostProcessor.process(source,parameters)
                for (side in listOf(Int.MAX_VALUE,37)) {
                    val expected = StackBitmap.create(processed.image,side)
                    val prepared = StackPostProcessor.prepare(source,parameters,cache)
                    val actual = StackBitmap.create(prepared,side)
                    assertEquals(expected.width,actual.width); assertEquals(expected.height,actual.height)
                    assertArrayEquals(pixels(expected),pixels(actual))
                    if (side == Int.MAX_VALUE) {
                        val oldPng = ByteArrayOutputStream().also { assertTrue(expected.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray()
                        val newPng = ByteArrayOutputStream().also { assertTrue(actual.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray()
                        assertArrayEquals(oldPng,newPng)
                        val reopened = BitmapFactory.decodeByteArray(newPng,0,newPng.size)
                        assertArrayEquals(pixels(actual),pixels(reopened)); reopened.recycle()
                    }
                    expected.recycle(); actual.recycle()
                }
            }
        }
    }

    @Test fun rowRenderingCancellationCanBeFollowedBySuccessfulRender() {
        val source = image(3)
        val prepared = StackPostProcessor.prepare(source,StackProcessingParameters())
        var rows = 0
        try {
            StackBitmap.create(prepared,64) { if (++rows == 3) throw CancellationException("cancel rows") }
            fail("Expected cancelled Bitmap rendering")
        } catch (_: CancellationException) { assertEquals(3,rows) }
        val bitmap = StackBitmap.create(prepared,64)
        assertTrue(bitmap.width <= 64 && bitmap.height <= 64)
        bitmap.recycle()
    }
}
