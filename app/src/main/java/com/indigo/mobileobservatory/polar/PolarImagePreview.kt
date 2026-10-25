package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.camera.PreviewStretch
import com.indigo.mobileobservatory.media.*
import java.io.File

/** Display-only UInt16 processing. Captured FITS pixels and solver input remain untouched. */
object PolarImagePreview {
    val settings = MediaImageEditSettings()

    fun prepare(file: File, maximumSide: Int = 1600, cancelled: () -> Boolean = { false }): PreparedMediaImage {
        val key = MediaImageEditing.fingerprint(file)
        val decoded = MediaImageEditing.readSource(file, maximumSide, cancelled)
        val window = if (decoded.displayNormalized) decoded.image.range else MediaImageProcessing.window(
            decoded.image, PreviewStretch.LOW_FRACTION, PreviewStretch.HIGH_FRACTION)
        require(key == MediaImageEditing.fingerprint(file)) { "source_changed" }
        return PreparedMediaImage(file, key, decoded.originalWidth, decoded.originalHeight, decoded.image,
            window, floatArrayOf(1f, 1f, 1f), decoded.header)
    }
}
