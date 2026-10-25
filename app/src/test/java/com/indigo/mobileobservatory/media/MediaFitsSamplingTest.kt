package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.FITSWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class MediaFitsSamplingTest {
    @Test fun reducedBayerCropsRetainColourAndHandleIncompleteEdgeCells() {
        for ((pattern, rx, ry) in listOf(Triple("RGGB", 0, 0), Triple("GRBG", 1, 0),
                Triple("GBRG", 0, 1), Triple("BGGR", 1, 1))) {
            val file = Files.createTempFile("preview-bayer", ".fits").toFile()
            try {
                val cards = linkedMapOf("SIMPLE" to "T", "BITPIX" to "16", "NAXIS" to "2", "NAXIS1" to "9",
                    "NAXIS2" to "9", "BZERO" to "32768", "BAYERPAT" to "'$pattern'", "XBAYROFF" to "1", "YBAYROFF" to "1")
                val header = cards.entries.joinToString("") { (it.key.padEnd(8) + "= " + it.value).padEnd(80) } + "END".padEnd(80)
                DataOutputStream(file.outputStream()).use { out ->
                    out.write(header.padEnd(2880).toByteArray())
                    for (y in 0 until 9) for (x in 0 until 9) {
                        val value = when {
                            x and 1 == (rx xor 1) && y and 1 == (ry xor 1) -> 12001
                            x and 1 != (rx xor 1) && y and 1 != (ry xor 1) -> 30003
                            else -> 20002
                        }
                        out.writeShort(value - 32768)
                    }
                }
                MediaImageFitsCodec.open(file).use { source ->
                    for (step in 1..4) {
                        val image = source.read(MediaImageRegion(1, 1, 7, 7, step))
                        for (i in image.pixels.indices step 3) {
                            assertEquals("$pattern step=$step red", 12001, image.unsigned(i))
                            assertEquals("$pattern step=$step green", 20002, image.unsigned(i + 1))
                            assertEquals("$pattern step=$step blue", 30003, image.unsigned(i + 2))
                        }
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun reducedPlanarRgbKeepsSubEightBitDifferences() {
        val file = Files.createTempFile("preview-rgb", ".fits").toFile()
        try {
            val image = MediaImagePixels(7, 5, 3, ShortArray(7 * 5 * 3) { i ->
                (32768 + (i % 3) * 3 + (i / 3 % 7) % 2).toShort()
            })
            MediaImageFitsCodec.write(file, image, emptyMap())
            MediaImageFitsCodec.open(file).use { source ->
                val reduced = source.read(MediaImageRegion(0, 0, 7, 5, 2))
                assertEquals(32769, reduced.unsigned(0))
                assertEquals(32772, reduced.unsigned(1))
                assertEquals(32775, reduced.unsigned(2))
                assertEquals(32768, reduced.unsigned((reduced.width - 1) * 3))
            }
        } finally { file.delete() }
    }

    @Test fun sparseStarsDoNotPushAConstantSkyToWhite() {
        val values = ShortArray(65536) { 1000 }
        values[32768] = 12000
        val image = MediaImagePixels(256, 256, 1, values, MediaImageWindow(0f, 65535f))
        val window = MediaImageProcessing.window(image)
        assertEquals(1000f, window.black, .01f)
        assertTrue(window.white > window.black)
        MediaImageProcessing.apply(image, MediaImageEditSettings(), floatArrayOf(1f, 1f, 1f), window)
        assertEquals(0, image.unsigned(0))
        assertEquals(65535, image.unsigned(32768))
    }

    @Test fun smallStarsBetweenPreviewSamplePositionsRemainVisible() {
        val width = 3856; val height = 192
        val data = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) {
            val star = x in 100..101 && y in 100..101
            data.putShort((if (star) 12000 else 1000).toShort())
        }
        val file = Files.createTempFile("preview-sampling", ".fits").toFile()
        try {
            FITSWriter().write(file, FrameData(data.array(), width, height, PixelFormat.MONO16, 1, 0), 1f, 0f)
            val preview = MediaImageEditing.readSource(file, 1600) { false }.image
            val peak = preview.pixels.indices.maxOf { preview.unsigned(it) }
            assertTrue("A 2×2 star between the step-3 sample positions must survive minification; peak=$peak", peak > 5000)
            assertEquals(1000, preview.unsigned(0))
        } finally { file.delete() }
    }
}
