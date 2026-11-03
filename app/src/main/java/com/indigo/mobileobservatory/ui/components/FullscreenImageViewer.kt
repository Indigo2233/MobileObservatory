package com.indigo.mobileobservatory.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen picture viewer with pinch zoom, drag and rotation.
 *
 * The system back button closes the viewer. A double tap resets the view.
 */
@Composable
fun FullscreenImageViewer(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialRotationDeg: Int = 0
) {
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var rotation by rememberSaveable { mutableIntStateOf(initialRotationDeg) }

    BackHandler(onBack = onDismiss)

    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val imageWidth = bitmap.width.toFloat()
    val imageHeight = bitmap.height.toFloat()

    Box(modifier.background(Color.Black).testTag("fullscreen-image-viewer")) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("fullscreen-image-surface")
                // The viewer publishes its own transform, so a test can read the
                // state the gestures produced.
                .semantics {
                    contentDescription = "fullscreen-image-state"
                    stateDescription = "scale=${(scale * 1000).toInt()} rotation=$rotation " +
                        "offsetX=${offsetX.toInt()} offsetY=${offsetY.toInt()}"
                }
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, rotationChange ->
                        val newScale = (scale * zoom).coerceIn(1f, 12f)
                        // Zoom around the gesture centre, then follow the drag.
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val focus = centroid - centre - Offset(offsetX, offsetY)
                        val factor = newScale / scale
                        offsetX = (offsetX - focus.x * (factor - 1f)) + pan.x
                        offsetY = (offsetY - focus.y * (factor - 1f)) + pan.y
                        scale = newScale
                        if (rotationChange != 0f) rotation = (rotation + rotationChange).roundToInt() % 360
                    }
                }
                .pointerInput(bitmap) {
                    // A double tap returns to the fitted picture. It runs in its own
                    // gesture pass so the transform pass keeps its own state.
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            rotation = initialRotationDeg
                        }
                    )
                }
        ) {
            if (imageWidth <= 0f || imageHeight <= 0f) return@Canvas
            val swapped = rotation % 180 != 0
            val effectiveWidth = if (swapped) imageHeight else imageWidth
            val effectiveHeight = if (swapped) imageWidth else imageHeight
            val fit = min(size.width / effectiveWidth, size.height / effectiveHeight)
            val displayScale = fit * scale
            withTransform({
                // The picture is centred on the origin, then scaled and rotated around
                // that centre. The outer translation places the centre at the middle
                // of the screen plus the drag offset.
                translate(size.width / 2f + offsetX, size.height / 2f + offsetY)
                rotate(rotation.toFloat(), pivot = Offset.Zero)
                scale(displayScale, displayScale, pivot = Offset.Zero)
                translate(-imageWidth / 2f, -imageHeight / 2f)
            }) {
                drawImage(image)
            }
        }
        // A visible rotation control keeps the horizontal flip reachable without a
        // two-finger gesture.
        Surface(
            color = Color(0x66000000),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Row {
                IconButton(onClick = { rotation = (rotation + 90) % 360 },
                    modifier = Modifier.size(48.dp).testTag("fullscreen-image-rotate")) {
                    Icon(Icons.Default.Rotate90DegreesCcw, stringResource(R.string.stack_fullscreen_rotate),
                        tint = Color.White)
                }
                IconButton(onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("fullscreen-image-close")) {
                    Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White)
                }
            }
        }
    }
}
