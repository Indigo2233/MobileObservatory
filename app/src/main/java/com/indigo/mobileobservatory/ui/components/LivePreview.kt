package com.indigo.mobileobservatory.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.indigo.mobileobservatory.R
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


internal object PreviewCenterMarker {
    /** Medium circle, sized in image pixels so it tracks the frame when zooming. */
    fun radiusPx(imageWidth: Float, imageHeight: Float): Float =
        min(imageWidth, imageHeight) * 0.18f

    fun strokePx(displayScale: Float): Float =
        2f / max(displayScale, 0.25f)
}

@Composable
fun LivePreview(
    bitmap: Bitmap?,
    flipH: Boolean = false,
    flipV: Boolean = false,
    rotationDeg: Int = 0,
    resetTrigger: Int = 0,
    showCenterMarker: Boolean = false,
    modifier: Modifier = Modifier,
    preferFullWidth: Boolean = false
) {
    val offsetSaver = remember {
        Saver<Offset, List<Float>>(save = { listOf(it.x, it.y) }, restore = { Offset(it[0], it[1]) })
    }
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offset by rememberSaveable(stateSaver = offsetSaver) { mutableStateOf(Offset.Zero) }
    var lastBitmapWidth by rememberSaveable { mutableIntStateOf(0) }
    var lastBitmapHeight by rememberSaveable { mutableIntStateOf(0) }
    var lastResetTrigger by rememberSaveable { mutableIntStateOf(resetTrigger) }

    val resetView: () -> Unit = { scale = 1f; offset = Offset.Zero }

    if (resetTrigger != lastResetTrigger) {
        lastResetTrigger = resetTrigger
        resetView()
    }

    val bmpW = bitmap?.width ?: 0
    val bmpH = bitmap?.height ?: 0
    if (bmpW > 0 && bmpH > 0
        && (bmpW != lastBitmapWidth || bmpH != lastBitmapHeight)) {
        lastBitmapWidth = bmpW
        lastBitmapHeight = bmpH
        resetView()
    }

    val transformed = bitmap != null && (scale != 1f || offset != Offset.Zero)
    BackHandler(enabled = transformed, onBack = resetView)
    val zoomDescription = stringResource(R.string.wf_preview_zoom, (scale * 100).roundToInt())
    val imageDescription = stringResource(R.string.image_desc)
    val resetDescription = stringResource(R.string.fit_to_view)

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
            .semantics {
                contentDescription = imageDescription
                stateDescription = zoomDescription
                if (transformed) customActions = listOf(
                    CustomAccessibilityAction(resetDescription) { resetView(); true }
                )
            }
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
        val fitScale = if (preferFullWidth) canvasW / effectiveW else minOf(canvasW / effectiveW, canvasH / effectiveH)

        val centerX = canvasW / 2f + offset.x
        val centerY = canvasH / 2f + offset.y
        val displayScale = fitScale * scale

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
            if (showCenterMarker) {
                val cx = imgW / 2f
                val cy = imgH / 2f
                val stroke = Stroke(width = PreviewCenterMarker.strokePx(displayScale))
                val color = Color(0xE600E5FF)
                drawLine(color, Offset(0f, cy), Offset(imgW, cy), stroke.width)
                drawLine(color, Offset(cx, 0f), Offset(cx, imgH), stroke.width)
                drawCircle(
                    color = color,
                    radius = PreviewCenterMarker.radiusPx(imgW, imgH),
                    center = Offset(cx, cy),
                    style = stroke
                )
            }
        }
    }
}
