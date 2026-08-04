package com.indigo.mobileobservatory.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R

@Composable
fun FocusAssistOverlay(
    bitmap: Bitmap?,
    focusScore: Float?,
    focusHistory: List<Float>,
    zoomCenter: Pair<Float, Float>,
    zoomFactor: Float,
    onZoomFactorChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    if (!expanded) {
        CompactFocusBar(
            focusScore = focusScore,
            onExpand = { expanded = true },
            onDismiss = onDismiss,
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.End
    ) {
        FocusZoomWindow(
            bitmap = bitmap,
            zoomCenter = zoomCenter,
            zoomFactor = zoomFactor,
            focusScore = focusScore,
            onZoomFactorChange = onZoomFactorChange,
            onCollapse = { expanded = false },
            onDismiss = onDismiss
        )
        FocusCurve(
            history = focusHistory,
            currentScore = focusScore
        )
    }
}

@Composable
private fun CompactFocusBar(
    focusScore: Float?,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xCC000000))
            .border(1.dp, Color(0x88666666), shape)
            .height(36.dp)
            .clickable(onClick = onExpand)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (focusScore != null) {
                stringResource(R.string.focus_score, "%.1f".format(focusScore))
            } else {
                stringResource(R.string.focus_no_data)
            },
            color = focusScoreColor(focusScore),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Icon(
            Icons.Default.ExpandLess,
            contentDescription = null,
            tint = Color(0xCCFFFFFF),
            modifier = Modifier.size(18.dp)
        )
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.close),
            tint = Color(0xCCFFFFFF),
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onDismiss)
                .padding(5.dp)
        )
    }
}

@Composable
private fun FocusZoomWindow(
    bitmap: Bitmap?,
    zoomCenter: Pair<Float, Float>,
    zoomFactor: Float,
    focusScore: Float?,
    onZoomFactorChange: (Float) -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(shape)
            .background(Color(0xCC000000))
            .border(1.5.dp, Color(0xAAFFFFFF), shape)
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (focusScore != null) {
                    stringResource(R.string.focus_score, "%.1f".format(focusScore))
                } else {
                    stringResource(R.string.focus_no_data)
                },
                color = focusScoreColor(focusScore),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${zoomFactor.toInt()}x",
                color = Color(0xAAFFFFFF),
                fontSize = 10.sp,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures {
                        val next = if (zoomFactor >= 5f) 2f else zoomFactor + 1f
                        onZoomFactorChange(next)
                    }
                }
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = Color(0xCCFFFFFF),
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onCollapse)
                    .padding(2.dp)
            )
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                tint = Color(0xCCFFFFFF),
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onDismiss)
                    .padding(2.dp)
            )
        }

        if (bitmap != null) {
            val bmpW = bitmap.width
            val bmpH = bitmap.height
            val cx = (zoomCenter.first * bmpW).toInt()
            val cy = (zoomCenter.second * bmpH).toInt()
            val cropW = (bmpW / zoomFactor).toInt().coerceAtLeast(4)
            val cropH = (bmpH / zoomFactor).toInt().coerceAtLeast(4)
            val srcX = (cx - cropW / 2).coerceIn(0, (bmpW - cropW).coerceAtLeast(0))
            val srcY = (cy - cropH / 2).coerceIn(0, (bmpH - cropH).coerceAtLeast(0))
            val actualW = cropW.coerceAtMost(bmpW - srcX)
            val actualH = cropH.coerceAtMost(bmpH - srcY)

            val imageBitmap = bitmap.asImageBitmap()

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(actualW, actualH),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_preview), color = Color(0x88FFFFFF), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FocusCurve(
    history: List<Float>,
    currentScore: Float?
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .width(168.dp)
            .height(48.dp)
            .clip(shape)
            .background(Color(0xCC000000))
            .border(1.5.dp, Color(0xAA666666), shape)
            .padding(4.dp)
    ) {
        if (history.size < 2) {
            Text(
                stringResource(R.string.adjusting),
                color = Color(0x88FFFFFF),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawFocusCurve(history, currentScore)
            }
        }
    }
}

private fun focusScoreColor(focusScore: Float?): Color = when {
    focusScore != null && focusScore > 80f -> Color(0xFF66FF66)
    focusScore != null && focusScore > 50f -> Color(0xFFFFFF66)
    else -> Color.White
}

private fun DrawScope.drawFocusCurve(history: List<Float>, currentScore: Float?) {
    val w = size.width
    val h = size.height
    val n = history.size
    val peak = history.max()
    val yMax = (peak * 1.1f).coerceAtLeast(1f)

    // Peak dashed line
    val peakY = h - (peak / yMax * h)
    drawLine(
        color = Color(0x88FF6666),
        start = Offset(0f, peakY),
        end = Offset(w, peakY),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
    )

    // Curve
    val path = Path()
    for (i in history.indices) {
        val x = i.toFloat() / (n - 1).coerceAtLeast(1) * w
        val y = h - (history[i] / yMax * h)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Color(0xFF44AAFF), style = Stroke(width = 1.5f))

    // Current score dot
    if (currentScore != null && n > 0) {
        val lastX = w
        val lastY = h - (currentScore / yMax * h)
        drawCircle(Color(0xFF66FF66), radius = 3f, center = Offset(lastX, lastY))
    }
}
