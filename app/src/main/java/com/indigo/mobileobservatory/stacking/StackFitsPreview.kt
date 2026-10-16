package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.util.Lp700PreviewMapping
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
            val transformed = Lp700PreviewMapping.mapLinearRgb(image.pixels, image.width) {
                if (cancelled()) throw java.util.concurrent.CancellationException()
            }
            image = image.copy(pixels = transformed)
        }
        val processed = StackPostProcessor.process(image, parameters, cancelled)
        return StackFitsPreviewResult(processed.image, metadata, parameters, recipe != null)
    }
}
