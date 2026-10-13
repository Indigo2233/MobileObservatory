package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.CameraScienceLayouts
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.*
import org.junit.Test

/** Byte fixtures exercise SDK layout contracts without loading vendor SDKs on the test host. */
class CameraScienceLayoutsTest {
    private fun fourCC(code: String) = code.foldIndexed(0) { i, value, c -> value or (c.code shl (8 * i)) }
    private fun words(values: IntArray) = ByteArray(values.size * 2).also { out ->
        values.forEachIndexed { i, value -> out[i * 2] = value.toByte(); out[i * 2 + 1] = (value ushr 8).toByte() }
    }
    private fun frame(values: IntArray, format: PixelFormat, width: Int = 2) =
        FrameData(words(values), width, values.size / width, format, 1, 1000)

    @Test fun dimZwoSampleHasSameValueWhenAnotherPixelBecomesBright() {
        for (bits in listOf(10, 12, 14, 16)) {
            val format = PixelFormat.valueOf("MONO$bits")
            val traits = CameraScienceLayouts.zwo(format)!!
            val shift = 16 - bits
            val dark = LinearPixelDecoder.decode(frame(intArrayOf(7 shl shift, 12 shl shift, 100 shl shift, 1 shl shift), format), traits)
            val bright = LinearPixelDecoder.decode(frame(intArrayOf(7 shl shift, ((1 shl bits) - 1) shl shift, 100 shl shift, 1 shl shift), format), traits)
            assertEquals(7f / ((1 shl bits) - 1), dark.pixels[0], 0f)
            assertEquals(dark.pixels[0], bright.pixels[0], 0f)
            assertEquals(1f, bright.pixels[1], 0f)
        }
    }

    @Test fun toupcamAllCfaAndRoiPhasesDecodeFourteenBitColors() {
        val colors = intArrayOf(12345, 6789, 2345) // All have low bits; an 8-bit path cannot preserve these.
        for (pattern in listOf("RGGB", "GRBG", "GBRG", "BGGR")) for (ox in 0..1) for (oy in 0..1) {
            val code = fourCC(pattern)
            val format = CameraScienceLayouts.toupcamFormat(code, 14)!!
            assertEquals(14, format.nativeBits)
            val samples = IntArray(8 * 6) { index ->
                val letter = pattern[((index / 8 + oy) % 2) * 2 + (index % 8 + ox) % 2]
                colors[when (letter) { 'R' -> 0; 'G' -> 1; else -> 2 }]
            }
            for (padding in 0..1) {
                val traits = CameraScienceLayouts.toupcam(code, 14, zeroPadding = padding)!!.copy(roiX = ox, roiY = oy)
                val raw = if (padding == 1) samples.map { it shl 2 }.toIntArray() else samples
                val image = LinearPixelDecoder.decode(frame(raw, format, 8), traits)
                image.pixels.forEachIndexed { i, value -> assertEquals("$pattern ROI($ox,$oy) padding=$padding channel=${i % 3}", colors[i % 3] / 16383f, value, 1e-7f) }
            }
        }
    }

    @Test fun fullContainerPreservesEveryDeliveredBitInsteadOfAssumingAdcDepth() {
        val values = intArrayOf(3, 4097, 32769, 65535)
        val traits = CameraScienceLayouts.fullContainer(PixelFormat.MONO16)!!
        val image = LinearPixelDecoder.decode(frame(values, PixelFormat.MONO16), traits)
        values.forEachIndexed { i, sample -> assertEquals(sample / 65535f, image.pixels[i], 0f) }
        assertEquals(SampleAlignment.RIGHT, traits.alignment)
        assertEquals(16, traits.validBits)
    }

    @Test fun unknownPackingOrProcessedColorCannotEnterSciencePipeline() {
        assertNull(CameraScienceLayouts.fullContainer(PixelFormat.MONO12))
        assertNull(CameraScienceLayouts.fullContainer(PixelFormat.RGB48))
        assertNull(CameraScienceLayouts.zwo(PixelFormat.RGB24))
        assertNull(CameraScienceLayouts.toupcam(fourCC("RGGB"), 14, processedRgb = true))
        assertNull(CameraScienceLayouts.toupcam(fourCC("RGGB"), 14, zeroPadding = 2))
        assertNull(CameraScienceLayouts.toupcamFormat(fourCC("GMCY"), 12))
        assertNull(CameraScienceLayouts.toupcamFormat(fourCC("RGGB"), 11))
        assertEquals(PixelFormat.MONO14, CameraScienceLayouts.toupcamFormat(fourCC("YYYY"), 14))
    }

    @Test fun unverifiedEvenBayerBinIsRejectedBeforeColorFabrication() {
        val traits = CameraScienceLayouts.zwo(PixelFormat.BAYER_RG12)!!.copy(binning = 2)
        try {
            LinearPixelDecoder.decode(frame(IntArray(16) { 1024 }, PixelFormat.BAYER_RG12, 4), traits)
            fail("An even sensor bin needs a verified output CFA")
        } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("Even Bayer")) }
    }
}
