package com.indigo.mobileobservatory.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.graphics.Color as AndroidColor
import android.view.Surface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.pointing.StarExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

internal fun phonePreviewRotation(sensorOrientation: Int, displayRotation: Int?): Int {
    val displayDegrees = when (displayRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    return Math.floorMod(sensorOrientation - displayDegrees, 360)
}

@Composable
private fun phonePreviewRotation(sensorOrientation: Int): Int {
    val view = LocalView.current
    var displayRotation by remember(view) { mutableStateOf(view.display?.rotation) }
    DisposableEffect(view) {
        val manager = view.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = view.display ?: return
                if (display.displayId == displayId) displayRotation = display.rotation
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayRotation = view.display?.rotation
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return phonePreviewRotation(sensorOrientation, displayRotation)
}

@Composable
internal fun PhoneSkyPreview(
    frame: FrameData,
    extraction: StarExtractionResult?,
    sensorOrientation: Int,
    modifier: Modifier = Modifier
) {
    val rotation = phonePreviewRotation(sensorOrientation)
    val bitmap by produceState<Bitmap?>(null, frame, extraction, rotation) {
        value = null
        value = withContext(Dispatchers.Default) { renderPhoneSkyFrame(frame, extraction, rotation) }
    }
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.push_to_preview_image),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

internal fun renderPhoneSkyFrame(
    frame: FrameData,
    extraction: StarExtractionResult?,
    rotation: Int
): Bitmap {
    require(rotation in 0..270 && rotation % 90 == 0)
    val width = frame.width
    val height = frame.height
    val bytesPerPixel = frame.pixelFormat.bytesPerPixel
    val columns = minOf(64, width)
    val rows = minOf(64, height)
    val sample = IntArray(columns * rows)
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val x = (2 * column + 1) * width / (2 * columns)
            val y = (2 * row + 1) * height / (2 * rows)
            sample[row * columns + column] = readFramePixel(frame, y * width + x)
        }
    }
    sample.sort()
    val low = sample[(sample.lastIndex * 0.05).toInt()]
    val minimumSpan = if (bytesPerPixel == 1) 24 else 64
    val high = max(sample[(sample.lastIndex * 0.997).toInt()], low + minimumSpan)
    val scale = 255f / (high - low)
    val transpose = rotation == 90 || rotation == 270
    val bitmap = Bitmap.createBitmap(if (transpose) height else width,
        if (transpose) width else height, Bitmap.Config.ARGB_8888)
    val row = IntArray(bitmap.width)
    val step = when (rotation) {
        90 -> -width
        180 -> -1
        270 -> width
        else -> 1
    }
    for (y in 0 until bitmap.height) {
        var sourceIndex = when (rotation) {
            90 -> (height - 1) * width + y
            180 -> (height - y) * width - 1
            270 -> width - 1 - y
            else -> y * width
        }
        for (x in row.indices) {
            val value = ((readFramePixel(frame, sourceIndex) - low) * scale).toInt().coerceIn(0, 255)
            row[x] = AndroidColor.rgb(value, value, value)
            sourceIndex += step
        }
        bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
    }

    if (extraction != null) {
        val canvas = android.graphics.Canvas(bitmap)
        canvas.translate(bitmap.width / 2f, bitmap.height / 2f)
        canvas.rotate(rotation.toFloat())
        canvas.translate(-width / 2f, -height / 2f)
        val longSide = max(width, height)
        val paint = android.graphics.Paint().apply {
            color = AndroidColor.RED
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = max(1f, longSide / 640f)
            isAntiAlias = true
        }
        val radius = max(3f, longSide / 360f)
        for (star in extraction.stars) {
            canvas.drawCircle(star.x + 0.5f, star.y + 0.5f, radius, paint)
        }
    }
    return bitmap
}

private fun readFramePixel(frame: FrameData, pixelIndex: Int): Int {
    val bytesPerPixel = frame.pixelFormat.bytesPerPixel
    val index = pixelIndex * bytesPerPixel
    val low = frame.data[index].toInt() and 0xFF
    return if (bytesPerPixel == 1) low else low or ((frame.data[index + 1].toInt() and 0xFF) shl 8)
}
