package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.stacking.PixelTraits
import java.nio.ByteOrder
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Test

class OpticalPsfSamplerTest {
    private fun frame(order: ByteOrder = ByteOrder.LITTLE_ENDIAN, shift: Double = 0.0,
        spread: Double = 1.4, bayer: Boolean = false): FrameData {
        val width = 200; val height = 180; val bytes = ByteArray(width * height * 2)
        val positions = listOf(30.0 to 30.0, 70.0 to 130.0, 110.0 to 45.0, 150.0 to 130.0, 170.0 to 65.0, 35.0 to 90.0)
        for (y in 0 until height) for (x in 0 until width) {
            val value = (100 + positions.sumOf { (cx, cy) ->
                8000 * exp(-((x - cx - shift) * (x - cx - shift) + (y - cy) * (y - cy)) / (2 * spread * spread))
            }).toInt()
            val index = (y * width + x) * 2
            bytes[index] = (if (order == ByteOrder.LITTLE_ENDIAN) value else value ushr 8).toByte()
            bytes[index + 1] = (if (order == ByteOrder.LITTLE_ENDIAN) value ushr 8 else value).toByte()
        }
        return FrameData(bytes, width, height, if (bayer) PixelFormat.BAYER_RG16 else PixelFormat.MONO16, 1, 1, 5)
    }
    @Test fun realCentroidAndPsfAreMeasuredWithoutStretchOrZeroMotionAssumption() {
        val before = OpticalPsfSampler.sample(frame(), PixelTraits())
        val after = OpticalPsfSampler.sample(frame(shift = 2.0), PixelTraits())
        assertEquals(6, before.size); assertEquals(6, after.size)
        before.forEach { star ->
            val match = after.minByOrNull { kotlin.math.hypot(it.x - star.x, it.y - star.y) }!!
            assertEquals(2.0, match.x - star.x, .03)
            assertTrue(star.sigmaMajorPx > .5)
        }
    }
    @Test fun endiannessAndCfaCellCoordinatesPreservePhysicalStarCenters() {
        val little = OpticalPsfSampler.sample(frame(), PixelTraits())
        val big = OpticalPsfSampler.sample(frame(ByteOrder.BIG_ENDIAN), PixelTraits(byteOrder = ByteOrder.BIG_ENDIAN))
        assertEquals(little, big)
        val bayer = OpticalPsfSampler.sample(frame(bayer = true), PixelTraits())
        assertEquals(6, bayer.size)
        bayer.forEach { star ->
            val match = little.minByOrNull { kotlin.math.hypot(it.x - star.x, it.y - star.y) }!!
            assertEquals(match.x, star.x, .1); assertEquals(match.y, star.y, .1)
        }
    }
    @Test fun widerPsfHasLargerMeasuredBoundAndRgbIsRejected() {
        val narrow = OpticalPsfSampler.sample(frame(), PixelTraits())
        val broad = OpticalPsfSampler.sample(frame(spread = 2.4), PixelTraits())
        assertTrue(broad.map { it.sigmaMajorPx }.average() > narrow.map { it.sigmaMajorPx }.average())
        assertThrows(IllegalArgumentException::class.java) { OpticalPsfSampler.sample(frame().copy(pixelFormat = PixelFormat.RGB24), null) }
    }
}
