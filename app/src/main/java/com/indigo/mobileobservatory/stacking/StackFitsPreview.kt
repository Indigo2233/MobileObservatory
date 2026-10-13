package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.util.ImageUtils
import java.io.File
import org.json.JSONObject

data class StackFitsPreviewResult(
    val image: LinearImage,
    val metadata: Map<String, String>,
    val parameters: StackProcessingParameters,
    val restoredRecipe: Boolean
)

/** Shared, testable reopen path; quantization happens only later in StackBitmap. */
object StackFitsPreview {
    fun read(file: File, applyLp750: Boolean = false, cancelled: () -> Boolean = { false }): StackFitsPreviewResult {
        var image = StackFitsReader.read(file, LinearImage.DEFAULT_STACK_PIXELS)
        val metadata = StackFitsReader.readMetadata(file)
        val recipe = metadata["processing"]?.let { text ->
            runCatching { StackResultMetadata.parseParameters(JSONObject(text).getJSONObject("parameters")) }.getOrNull()
        }
        val parameters = recipe ?: StackProcessingParameters.preset(ProcessingPreset.NATURAL)
        if (applyLp750 && image.channels == 3) {
            var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
            for (i in 0 until image.pixelCount step maxOf(1, image.pixelCount / 10000)) {
                r += image.pixels[i * 3]; g += image.pixels[i * 3 + 1]; b += image.pixels[i * 3 + 2]; n++
            }
            val gains = ImageUtils.lp750WhiteBalanceGains((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
            val transformed = FloatArray(image.pixels.size)
            for (i in 0 until image.pixelCount) {
                if (i % image.width == 0 && cancelled()) throw java.util.concurrent.CancellationException()
                val rgb = ImageUtils.lp750Matrix(image.pixels[i * 3], image.pixels[i * 3 + 1], image.pixels[i * 3 + 2])
                for (c in 0..2) transformed[i * 3 + c] = (rgb[c] * gains[c]).coerceIn(0f, 1f)
            }
            image = image.copy(pixels = transformed)
        }
        val processed = StackPostProcessor.process(image, parameters, cancelled)
        return StackFitsPreviewResult(processed.image, metadata, parameters, recipe != null)
    }
}
