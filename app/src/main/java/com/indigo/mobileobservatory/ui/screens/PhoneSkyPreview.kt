package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.pointing.StarExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun PhoneSkyPreview(
    frame: FrameData,
    extraction: StarExtractionResult?,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<Bitmap?>(null, frame.frameId, extraction) {
        value = withContext(Dispatchers.Default) { renderPhoneSkyFrame(frame, extraction) }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(frame.width.toFloat() / frame.height.coerceAtLeast(1))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

internal fun renderPhoneSkyFrame(
    frame: FrameData,
    extraction: StarExtractionResult?
): Bitmap {
    val sampleCapacity = min(4096, max(1, frame.width * frame.height / 16))
    val sample = FloatArray(sampleCapacity)
    var count = 0
    for (y in 0 until frame.height step 8) {
        for (x in 0 until frame.width step 8) {
            if (count >= sample.size) break
            sample[count++] = readFramePixel(frame, x, y)
        }
    }
    sample.sort(0, count)
    val low = sample[(count * 0.05).toInt().coerceIn(0, count - 1)]
    val high = sample[(count * 0.995).toInt().coerceIn(0, count - 1)].coerceAtLeast(low + 1f)
    val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.RGB_565)
    val row = IntArray(frame.width)
    for (y in 0 until frame.height) {
        for (x in 0 until frame.width) {
            val value = ((readFramePixel(frame, x, y) - low) / (high - low) * 255f)
                .toInt().coerceIn(0, 255)
            row[x] = AndroidColor.rgb(value, value, value)
        }
        bitmap.setPixels(row, 0, frame.width, 0, y, frame.width, 1)
    }

    extraction?.let { result ->
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            color = AndroidColor.RED
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = max(1f, frame.width / 400f)
            isAntiAlias = true
        }
        val radius = max(3f, frame.width / 200f)
        result.stars.take(100).forEach { star ->
            canvas.drawCircle(star.x, star.y, radius, paint)
        }
    }
    return bitmap
}

private fun readFramePixel(frame: FrameData, x: Int, y: Int): Float {
    val bytesPerPixel = frame.pixelFormat.bytesPerPixel
    val index = (y * frame.width + x) * bytesPerPixel
    return if (bytesPerPixel >= 2) {
        ((frame.data[index].toInt() and 0xFF) or
            ((frame.data[index + 1].toInt() and 0xFF) shl 8)).toFloat()
    } else {
        (frame.data[index].toInt() and 0xFF).toFloat()
    }
}
