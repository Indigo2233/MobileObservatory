package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import com.indigo.mobileobservatory.media.*
import com.indigo.mobileobservatory.stacking.StackBitmap
import com.indigo.mobileobservatory.stacking.StackFitsPreview
import com.indigo.mobileobservatory.stacking.StackFitsReader
import com.indigo.mobileobservatory.util.FitsDisplayStretch
import java.util.concurrent.CancellationException
import java.io.File

internal fun stackDisplayFile(source: File): File? = runCatching {
    val metadata = StackFitsReader.readMetadata(source)
    val name = metadata["processedPng"] ?: return null
    if (metadata["schema"] != "indigo-stack-1" || metadata["linearFits"] != source.name || File(name).name != name) return null
    val root = source.parentFile?.parentFile ?: return null
    val directory = File(root, "JPG").canonicalFile
    val display = File(directory, name).canonicalFile
    if (display.parentFile != directory || !display.isFile) return null
    val companion = MediaLineage.metadata(display)
    display.takeIf { companion["schema"] == "indigo-stack-1" && companion["linearFits"] == source.name && companion["processedPng"] == name }
}.getOrNull()

/** Ordinary photos use the UInt16 region pipeline. Legacy small stack previews retain their recipe. */
internal class MediaNativePreview {
    private var legacyStack: Bitmap? = null
    private var savedStack: PreparedMediaImage? = null
    fun render(prepared: PreparedMediaImage, preview: Bitmap, region: MediaImageRegion, cancelled: () -> Boolean): Bitmap {
        val fits = prepared.source.extension.lowercase() in listOf("fit", "fits")
        require(prepared.sourceFingerprint == MediaImageEditing.fingerprint(prepared.source)) { "source_changed" }
        val small = prepared.originalWidth.toLong() * prepared.originalHeight <= 4_194_304L
        if (fits && small && !MediaImageEditing.isEditedFits(prepared.source) && StackFitsReader.isNormalizedStack(prepared.source)) {
            val display = savedStack ?: stackDisplayFile(prepared.source)?.let { MediaImageEditing.prepare(it, cancelled = cancelled) }?.also { savedStack = it }
            if (display != null && display.originalWidth == prepared.originalWidth && display.originalHeight == prepared.originalHeight) {
                return MediaImageEditing.renderRegion(display, MediaImageEditSettings(), region, cancelled)
            }
            val original = if (preview.width == prepared.originalWidth && preview.height == prepared.originalHeight) preview else legacyStack ?: run {
                MediaImageMemory.check(prepared.originalWidth.toLong() * prepared.originalHeight * 64,
                    prepared.originalWidth.toLong() * prepared.originalHeight * 4)
                StackBitmap.create(StackFitsPreview.read(prepared.source, cancelled = cancelled).image) {
                    if (cancelled()) throw CancellationException()
                }.also { legacyStack = it }
            }
            val crop = Bitmap.createBitmap(original, region.left, region.top, region.width, region.height)
            if (region.step == 1) return crop
            val scaled = Bitmap.createScaledBitmap(crop, region.outputWidth, region.outputHeight, true)
            if (crop !== original && scaled !== crop) crop.recycle()
            return scaled
        }
        return MediaImageEditing.renderRegion(prepared, if (fits && !MediaImageEditing.isEditedFits(prepared.source))
            MediaImageEditSettings(gamma = 1f / FitsDisplayStretch.DISPLAY_GAMMA) else MediaImageEditSettings(), region, cancelled)
    }
}
