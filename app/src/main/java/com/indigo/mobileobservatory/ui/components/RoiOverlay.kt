package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.indigo.mobileobservatory.camera.Roi

private enum class DragHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private const val ROI_MIN_SIZE = 64

@Composable
fun RoiOverlay(
    roi: Roi,
    sensorWidth: Int,
    sensorHeight: Int,
    onRoiChange: (Roi) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragHandle by remember { mutableStateOf(DragHandle.NONE) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var startRoi by remember { mutableStateOf(roi) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(roi, sensorWidth, sensorHeight) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val scaleX = size.width.toFloat() / sensorWidth
                        val scaleY = size.height.toFloat() / sensorHeight
                        val roiRect = Rect(
                            roi.x * scaleX, roi.y * scaleY,
                            (roi.x + roi.width) * scaleX, (roi.y + roi.height) * scaleY
                        )
                        val handleSize = 60f
                        dragHandle = when {
                            (pos - roiRect.topLeft).getDistance() < handleSize -> DragHandle.TOP_LEFT
                            (pos - roiRect.topRight).getDistance() < handleSize -> DragHandle.TOP_RIGHT
                            (pos - roiRect.bottomLeft).getDistance() < handleSize -> DragHandle.BOTTOM_LEFT
                            (pos - roiRect.bottomRight).getDistance() < handleSize -> DragHandle.BOTTOM_RIGHT
                            roiRect.contains(pos) -> DragHandle.MOVE
                            else -> DragHandle.MOVE
                        }
                        dragStart = pos
                        startRoi = roi
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val scaleX = size.width.toFloat() / sensorWidth
                        val scaleY = size.height.toFloat() / sensorHeight
                        val dx = ((change.position.x - dragStart.x) / scaleX).toInt()
                        val dy = ((change.position.y - dragStart.y) / scaleY).toInt()

                        val newRoi = when (dragHandle) {
                            DragHandle.MOVE -> {
                                val mx = (startRoi.x + dx).coerceIn(0, sensorWidth - startRoi.width)
                                val my = (startRoi.y + dy).coerceIn(0, sensorHeight - startRoi.height)
                                Roi(mx, my, startRoi.width, startRoi.height)
                            }
                            DragHandle.TOP_LEFT -> {
                                val nx = (startRoi.x + dx).coerceIn(0, startRoi.x + startRoi.width - ROI_MIN_SIZE)
                                val ny = (startRoi.y + dy).coerceIn(0, startRoi.y + startRoi.height - ROI_MIN_SIZE)
                                val nw = startRoi.x + startRoi.width - nx
                                val nh = startRoi.y + startRoi.height - ny
                                Roi(nx, ny, nw.coerceAtLeast(ROI_MIN_SIZE), nh.coerceAtLeast(ROI_MIN_SIZE))
                            }
                            DragHandle.BOTTOM_RIGHT -> {
                                val nw = (startRoi.width + dx).coerceIn(ROI_MIN_SIZE, sensorWidth - startRoi.x)
                                val nh = (startRoi.height + dy).coerceIn(ROI_MIN_SIZE, sensorHeight - startRoi.y)
                                Roi(startRoi.x, startRoi.y, nw.coerceAtLeast(ROI_MIN_SIZE), nh.coerceAtLeast(ROI_MIN_SIZE))
                            }
                            DragHandle.TOP_RIGHT -> {
                                val nw = (startRoi.width + dx).coerceIn(ROI_MIN_SIZE, sensorWidth - startRoi.x)
                                val ny = (startRoi.y + dy).coerceIn(0, startRoi.y + startRoi.height - ROI_MIN_SIZE)
                                val nh = startRoi.y + startRoi.height - ny
                                Roi(startRoi.x, ny, nw.coerceAtLeast(ROI_MIN_SIZE), nh.coerceAtLeast(ROI_MIN_SIZE))
                            }
                            DragHandle.BOTTOM_LEFT -> {
                                val nx = (startRoi.x + dx).coerceIn(0, startRoi.x + startRoi.width - ROI_MIN_SIZE)
                                val nw = startRoi.x + startRoi.width - nx
                                val nh = (startRoi.height + dy).coerceIn(ROI_MIN_SIZE, sensorHeight - startRoi.y)
                                Roi(nx, startRoi.y, nw.coerceAtLeast(ROI_MIN_SIZE), nh.coerceAtLeast(ROI_MIN_SIZE))
                            }
                            DragHandle.NONE -> roi
                        }
                        onRoiChange(newRoi)
                    },
                    onDragEnd = { dragHandle = DragHandle.NONE }
                )
            }
    ) {
        val scaleX = size.width / sensorWidth
        val scaleY = size.height / sensorHeight
        val roiRect = Rect(
            roi.x * scaleX, roi.y * scaleY,
            (roi.x + roi.width) * scaleX, (roi.y + roi.height) * scaleY
        )

        drawRect(
            Color(0x40000000),
            topLeft = Offset.Zero,
            size = Size(size.width, roiRect.top)
        )
        drawRect(
            Color(0x40000000),
            topLeft = Offset(0f, roiRect.bottom),
            size = Size(size.width, size.height - roiRect.bottom)
        )
        drawRect(
            Color(0x40000000),
            topLeft = Offset(0f, roiRect.top),
            size = Size(roiRect.left, roiRect.height)
        )
        drawRect(
            Color(0x40000000),
            topLeft = Offset(roiRect.right, roiRect.top),
            size = Size(size.width - roiRect.right, roiRect.height)
        )

        drawRect(
            Color(0xFFFFD700),
            topLeft = roiRect.topLeft,
            size = roiRect.size,
            style = Stroke(
                width = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
            )
        )

        val handleRadius = 10f
        val handleColor = Color(0xFFFFD700)
        listOf(roiRect.topLeft, roiRect.topRight, roiRect.bottomLeft, roiRect.bottomRight).forEach { corner ->
            drawCircle(handleColor, handleRadius, corner)
        }
    }
}
