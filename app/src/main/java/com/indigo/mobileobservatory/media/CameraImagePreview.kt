package com.indigo.mobileobservatory.media

import android.graphics.Bitmap
import com.indigo.mobileobservatory.camera.CameraPreviewSettings
import com.indigo.mobileobservatory.camera.FrameProcessor
import com.indigo.mobileobservatory.camera.PreviewWindow
import com.indigo.mobileobservatory.util.Lp700PreviewMapping
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.ceil

data class PreparedCameraPreview(val source: File, val fingerprint: String, val originalWidth: Int,
    val originalHeight: Int, val bitmap: Bitmap, val settings: CameraPreviewSettings, val window: PreviewWindow,
    val lpRecipe: Lp700PreviewMapping.ArgbRecipe?)

/** Saved camera images use the live renderer and its existing display recipe. */
object CameraImagePreview {
    private fun read(file: File, maximumSide: Int = 1600, region: MediaImageRegion? = null,
                     check: () -> Unit): MediaImageFitsCodec.CameraImage {
        if (file.extension.lowercase() in listOf("fit", "fits")) {
            return MediaImageFitsCodec.readCameraImage(file, maximumSide, region, includeHalo = region != null, check = check)
        }
        return openMediaSource(file, check = check).use { source ->
            val step = ceil(maxOf(source.width, source.height).toDouble() / maximumSide).toInt().coerceAtLeast(1)
            val selected = region ?: MediaImageRegion(0, 0, source.width, source.height, step)
            MediaImageFitsCodec.CameraImage(source.read(selected, check).cameraFrame(displayNormalized = source.displayNormalized,
                check = check), source.width, source.height, selected)
        }
    }

    fun prepare(file: File, settings: CameraPreviewSettings, maximumSide: Int = 1600,
                cancelled: () -> Boolean = { false }): PreparedCameraPreview {
        require(maximumSide > 0)
        val key = MediaImageEditing.fingerprint(file)
        MediaImageEditing.checkCancelled(cancelled)
        // Thumbnails share the overview's statistics; only their final display bitmap is smaller.
        val decoded = read(file, maxOf(1600, maximumSide)) { MediaImageEditing.checkCancelled(cancelled) }
        val frame = decoded.snapshot.frame
        MediaImageMemory.check(frame.width.toLong() * frame.height * 8, frame.width.toLong() * frame.height * 4)
        val processor = FrameProcessor(bitmapBufferCount = 1, downsample = false).apply { applyPreviewSettings(settings) }
        var image = processor.frameToBitmap(frame, decoded.snapshot.traits)
        try {
            require(image.width == frame.width && image.height == frame.height) { "memory_budget" }
            val size = maxOf(image.width, image.height)
            if (size > maximumSide) {
                val reduced = Bitmap.createScaledBitmap(image, (image.width.toDouble() * maximumSide / size).roundToInt().coerceAtLeast(1),
                    (image.height.toDouble() * maximumSide / size).roundToInt().coerceAtLeast(1), true)
                if (reduced !== image) image.recycle()
                image = reduced
            }
            MediaImageEditing.checkCancelled(cancelled)
            require(key == MediaImageEditing.fingerprint(file)) { "source_changed" }
            return PreparedCameraPreview(file, key, decoded.originalWidth, decoded.originalHeight, image, settings,
                processor.renderedWindow, processor.renderedLpRecipe)
        } catch (failure: Throwable) { image.recycle(); throw failure }
    }

    fun renderRegion(prepared: PreparedCameraPreview, region: MediaImageRegion,
                     cancelled: () -> Boolean = { false }): Bitmap {
        require(prepared.fingerprint == MediaImageEditing.fingerprint(prepared.source)) { "source_changed" }
        val decoded = read(prepared.source, region = region) { MediaImageEditing.checkCancelled(cancelled) }
        val frame = decoded.snapshot.frame
        MediaImageMemory.check(frame.width.toLong() * frame.height * 8, frame.width.toLong() * frame.height * 4)
        var image = FrameProcessor(bitmapBufferCount = 1, downsample = false).apply {
            applyPreviewSettings(prepared.settings); fixedWindow = prepared.window; fixedLpRecipe = prepared.lpRecipe
        }.frameToBitmap(decoded.snapshot.frame, decoded.snapshot.traits)
        try {
            require(image.width == frame.width && image.height == frame.height) { "memory_budget" }
            if (decoded.region != region) {
                val cropped = Bitmap.createBitmap(image, region.left - decoded.region.left, region.top - decoded.region.top,
                    region.outputWidth, region.outputHeight)
                if (cropped !== image) image.recycle()
                image = cropped
            }
            require(image.width == region.outputWidth && image.height == region.outputHeight) { "memory_budget" }
            MediaImageEditing.checkCancelled(cancelled)
            require(prepared.fingerprint == MediaImageEditing.fingerprint(prepared.source)) { "source_changed" }
            return image
        } catch (failure: Throwable) { image.recycle(); throw failure }
    }
}
