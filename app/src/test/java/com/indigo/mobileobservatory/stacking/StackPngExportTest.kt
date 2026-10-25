package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.media.MediaImageRegion
import com.indigo.mobileobservatory.media.MediaPng16Codec
import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class StackPngExportTest {
    @Test fun streamedPngRetainsSixteenBitsAndMatchesExactProcessing() {
        val root = Files.createTempDirectory("stack-png16").toFile()
        try {
            for (channels in listOf(1, 3)) for (preset in listOf(ProcessingPreset.RAW, ProcessingPreset.NATURAL)) {
                val input = LinearImage(257, 65, channels, FloatArray(257 * 65 * channels) { .100001f + (it % 997) * .000701f })
                val parameters = StackProcessingParameters.preset(preset).copy(background = BackgroundParameters(enabled = false), autoWhiteBalance = false)
                val expected = StackPostProcessor.process(input, parameters).image
                val prepared = StackPostProcessor.prepare(input, parameters)
                val file = File(root, "$channels-$preset.png")
                StackPngExport.write(file, prepared)
                assertTrue(MediaPng16Codec.is16Bit(file))
                MediaPng16Codec.open(file).use { source ->
                    val decoded = source.read(MediaImageRegion(0, 0, 257, 65))
                    assertEquals(channels, decoded.channels)
                    for (i in expected.pixels.indices) assertEquals((expected.pixels[i].coerceIn(0f, 1f).toDouble() * 65535).roundToInt(), decoded.unsigned(i))
                    assertTrue(decoded.pixels.any { (it.toInt() and 65535) % 257 != 0 })
                }
            }
        } finally { root.deleteRecursively() }
    }
}
