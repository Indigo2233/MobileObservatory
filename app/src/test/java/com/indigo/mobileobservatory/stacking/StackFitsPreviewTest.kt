package com.indigo.mobileobservatory.stacking

import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class StackFitsPreviewTest {
    @Test fun normalizedFloatFileReopensWithDistinctNaturalTonesInsteadOfIntegerZeroes() {
        val directory = Files.createTempDirectory("fits-preview").toFile()
        try {
            val source = LinearImage(8, 1, 1, floatArrayOf(0f, 1f / 65535, 2f / 65535, .01f, .1f, .25f, .5f, 1f))
            val file = directory.resolve("float.fits")
            StackFitsWriter.write(file, source)
            assertTrue(StackFitsReader.isFloatingPoint(file))
            assertTrue(StackFitsReader.isNormalizedStack(file))
            val preview = StackFitsPreview.read(file)
            assertEquals(ProcessingPreset.NATURAL, preview.parameters.preset)
            assertFalse(preview.restoredRecipe)
            assertEquals(0f, preview.image.pixels.first(), 0f)
            // The default display shoulder reserves highlight headroom at sensor white.
            assertEquals(.85f, preview.image.pixels.last(), 1e-6f)
            for (i in 1 until preview.image.pixels.size) assertTrue(preview.image.pixels[i] > preview.image.pixels[i - 1])
            assertTrue(preview.image.pixels[3] > .01f)
            assertArrayEquals(source.pixels, StackFitsReader.read(file).pixels, 0f)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun foreignFloatCountsAndBayerKeepLegacyDisplayRoute() {
        val directory = Files.createTempDirectory("foreign-float-preview").toFile()
        try {
            val counts = floatArrayOf(100f, 250f, 500f, 1000f)
            for (bayer in listOf(false, true)) {
                val file = directory.resolve("foreign-$bayer.fits")
                writeForeignFloat(file, counts, bayer)
                assertTrue(StackFitsReader.isFloatingPoint(file))
                assertFalse(StackFitsReader.isNormalizedStack(file))
                assertArrayEquals(counts, StackFitsReader.read(file).pixels, 0f)
                assertTrue(StackFitsReader.readMetadata(file).isEmpty())
            }
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun embeddedStackSchemaAlsoIdentifiesNormalizedProduct() {
        val directory = Files.createTempDirectory("stack-schema-preview").toFile()
        try {
            val file = directory.resolve("schema.fits")
            val source = LinearImage(2, 2, 1, floatArrayOf(0f, .1f, .5f, 1f))
            val metadata = mapOf("schema" to "indigo-stack-1", "source" to "source.ser")
            StackFitsWriter.write(file, source, metadata)
            // Retain the actual extension but omit the optional identifying primary card.
            RandomAccessFile(file, "rw").use { reader ->
                val card = ByteArray(80)
                while (true) {
                    val position = reader.filePointer
                    reader.readFully(card)
                    val text = String(card, Charsets.US_ASCII)
                    if (text.startsWith("BUNIT   ")) {
                        reader.seek(position); reader.write("COMMENT no BUNIT".padEnd(80).toByteArray(Charsets.US_ASCII)); break
                    }
                    check(!text.startsWith("END "))
                }
            }
            assertTrue(StackFitsReader.isNormalizedStack(file))
            assertEquals(metadata, StackFitsReader.readMetadata(file))
            assertArrayEquals(source.pixels, StackFitsReader.read(file).pixels, 0f)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    private fun writeForeignFloat(file: File, pixels: FloatArray, bayer: Boolean) {
        fun card(key: String, value: String) = (key.padEnd(8) + "= " + value).padEnd(80)
        val cards = mutableListOf(card("SIMPLE", "T"), card("BITPIX", "-32"), card("NAXIS", "2"),
            card("NAXIS1", "2"), card("NAXIS2", "2"), card("BUNIT", "'adu'"))
        if (bayer) cards += card("BAYERPAT", "'RGGB'")
        cards += "END".padEnd(80)
        DataOutputStream(file.outputStream()).use { out ->
            out.write(cards.joinToString("").padEnd(2880).toByteArray(Charsets.US_ASCII))
            pixels.forEach(out::writeFloat)
            out.write(ByteArray(2880 - pixels.size * 4))
        }
    }

    @Test fun savedRecipeRestoresExactPngProcessingAndRawFileKeepsAllColorPrecision() {
        val directory = Files.createTempDirectory("fits-recipe-preview").toFile()
        try {
            val source = LinearImage(16, 8, 3, FloatArray(16 * 8 * 3) { .005f + (it % 67) / 150f })
            for (preset in ProcessingPreset.values()) {
                val recipe = StackProcessingParameters.preset(preset).copy(redBalance = 1.2, blueBalance = .8, saturation = .9)
                val expected = StackPostProcessor.process(source, recipe)
                val metadata = mapOf("source" to "颜色测试.ser", "frameCount" to "31",
                    "processing" to StackResultMetadata.processing(expected).toString())
                val file = directory.resolve("$preset.fits")
                StackFitsWriter.write(file, source, metadata)
                assertEquals(metadata, StackFitsReader.readMetadata(file))
                val reopened = StackFitsPreview.read(file)
                assertTrue(reopened.restoredRecipe)
                assertEquals(recipe, reopened.parameters)
                assertArrayEquals(expected.image.pixels, reopened.image.pixels, 0f)
                assertArrayEquals(source.pixels, StackFitsReader.read(file).pixels, 0f)
            }
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
