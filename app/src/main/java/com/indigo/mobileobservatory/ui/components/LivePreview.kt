package com.indigo.mobileobservatory.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import java.util.IdentityHashMap


@Composable
fun LivePreview(
    bitmap: Bitmap?,
    flipH: Boolean = false,
    flipV: Boolean = false,
    rotationDeg: Int = 0,
    resetTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var lastBitmapSize by remember { mutableStateOf(0 to 0) }
    var lastResetTrigger by remember { mutableIntStateOf(0) }

    if (resetTrigger != lastResetTrigger) {
        lastResetTrigger = resetTrigger
        scale = 1f
        offset = Offset.Zero
    }

    val bmpW = bitmap?.width ?: 0
    val bmpH = bitmap?.height ?: 0
    if (bmpW > 0 && bmpH > 0
        && (bmpW != lastBitmapSize.first || bmpH != lastBitmapSize.second)) {
        lastBitmapSize = bmpW to bmpH
        scale = 1f
        offset = Offset.Zero
    }

    val imageBitmapCache = remember { IdentityHashMap<Bitmap, androidx.compose.ui.graphics.ImageBitmap>() }
    val imageBitmap = bitmap?.let { current ->
        imageBitmapCache[current] ?: current.asImageBitmap().also { converted ->
            if (imageBitmapCache.size >= 4) imageBitmapCache.clear()
            imageBitmapCache[current] = converted
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 10f)
                    offset = Offset(
                        x = offset.x + pan.x,
                        y = offset.y + pan.y
                    )
                }
            }
    ) {
        val bmp = bitmap ?: return@Canvas
        val image = imageBitmap ?: return@Canvas

        val canvasW = size.width
        val canvasH = size.height
        val imgW = bmp.width.toFloat()
        val imgH = bmp.height.toFloat()

        val swapped = rotationDeg == 90 || rotationDeg == 270
        val effectiveW = if (swapped) imgH else imgW
        val effectiveH = if (swapped) imgW else imgH
        val fitScale = minOf(canvasW / effectiveW, canvasH / effectiveH)

        val centerX = canvasW / 2f + offset.x
        val centerY = canvasH / 2f + offset.y

        withTransform({
            translate(centerX, centerY)
            scale(
                fitScale * scale * if (flipH) -1f else 1f,
                fitScale * scale * if (flipV) -1f else 1f,
                pivot = Offset.Zero
            )
            rotate(rotationDeg.toFloat(), pivot = Offset.Zero)
            translate(-imgW / 2f, -imgH / 2f)
        }) {
            drawImage(image)
        }
    }
}
