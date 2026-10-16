package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.pointing.GuidanceCommand
import com.indigo.mobileobservatory.pointing.GuidanceProximity
import com.indigo.mobileobservatory.pointing.PushToGuidance
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val ReticleDim = Color(0xFF3A1010)

/** Shared distance-adaptive reticle used by the current push-to workflows. */
@Composable
internal fun SseReticle(
    cmd: GuidanceCommand,
    deltaAlt: Double,
    deltaAz: Double,
    eyepieceFovDeg: Double,
    color: Color,
    pulseAlpha: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            setColor(android.graphics.Color.WHITE)
        }
    }
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = min(size.width, size.height) * 0.42f
        labelPaint.textSize = with(density) { 16.sp.toPx() }

        when (cmd.proximity) {
            GuidanceProximity.FAR -> drawFarMode(
                centerX, centerY, radius, deltaAlt, deltaAz, color, labelPaint
            )
            GuidanceProximity.MEDIUM -> drawMediumMode(
                centerX, centerY, radius, deltaAlt, deltaAz, color, labelPaint
            )
            GuidanceProximity.NEAR -> drawNearMode(
                centerX, centerY, radius, deltaAlt, deltaAz, eyepieceFovDeg, color, pulseAlpha
            )
            GuidanceProximity.ON_TARGET -> drawOnTarget(centerX, centerY, radius, color, pulseAlpha)
        }
    }
}

private fun DrawScope.drawFarMode(
    centerX: Float,
    centerY: Float,
    radius: Float,
    deltaAlt: Double,
    deltaAz: Double,
    color: Color,
    paint: android.graphics.Paint
) {
    drawCircle(ReticleDim, radius, Offset(centerX, centerY), style = Stroke(3f))
    if (deltaAlt > 0.3) drawChevron(centerX, centerY - radius * 0.72f, 0f, color, radius * 0.22f)
    if (deltaAlt < -0.3) drawChevron(centerX, centerY + radius * 0.72f, 180f, color, radius * 0.22f)
    if (deltaAz > 0.3) drawChevron(centerX + radius * 0.72f, centerY, 90f, color, radius * 0.22f)
    if (deltaAz < -0.3) drawChevron(centerX - radius * 0.72f, centerY, -90f, color, radius * 0.22f)

    paint.color = android.graphics.Color.argb(
        255,
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
    paint.textSize = size.minDimension * 0.07f
    drawContext.canvas.nativeCanvas.drawText(
        PushToGuidance.formatDegrees(kotlin.math.hypot(deltaAlt, deltaAz)),
        centerX,
        centerY + paint.textSize / 3f,
        paint
    )
    paint.textSize = size.minDimension * 0.035f
    paint.color = android.graphics.Color.GRAY
    drawContext.canvas.nativeCanvas.drawText("PUSH", centerX, centerY + radius * 0.28f, paint)
}

private fun DrawScope.drawMediumMode(
    centerX: Float,
    centerY: Float,
    radius: Float,
    deltaAlt: Double,
    deltaAz: Double,
    color: Color,
    paint: android.graphics.Paint
) {
    drawCircle(color.copy(alpha = 0.12f), radius, Offset(centerX, centerY))
    drawCircle(color, radius, Offset(centerX, centerY), style = Stroke(2.5f))
    for (index in 1..4) {
        drawCircle(
            color.copy(alpha = 0.2f),
            radius * index / 5f,
            Offset(centerX, centerY),
            style = Stroke(1f)
        )
    }
    drawLine(color.copy(alpha = 0.45f), Offset(centerX - radius, centerY), Offset(centerX + radius, centerY), 2f)
    drawLine(color.copy(alpha = 0.45f), Offset(centerX, centerY - radius), Offset(centerX, centerY + radius), 2f)

    val scale = (kotlin.math.hypot(deltaAlt, deltaAz) / 10.0).toFloat().coerceAtMost(1f)
    val angle = atan2(deltaAz, deltaAlt).toFloat()
    val length = scale * radius * 0.85f
    val targetX = centerX + length * sin(angle)
    val targetY = centerY - length * cos(angle)
    drawLine(color, Offset(centerX, centerY), Offset(targetX, targetY), strokeWidth = 5f, cap = StrokeCap.Round)
    drawCircle(color, 10f, Offset(targetX, targetY))
    drawCircle(Color.White, 4f, Offset(centerX, centerY))

    paint.color = android.graphics.Color.WHITE
    paint.textSize = size.minDimension * 0.045f
    drawContext.canvas.nativeCanvas.drawText(
        PushToGuidance.formatDegrees(kotlin.math.hypot(deltaAlt, deltaAz)),
        centerX,
        centerY + radius + paint.textSize * 1.4f,
        paint
    )
}

private fun DrawScope.drawNearMode(
    centerX: Float,
    centerY: Float,
    radius: Float,
    deltaAlt: Double,
    deltaAz: Double,
    fovDeg: Double,
    color: Color,
    pulseAlpha: Float
) {
    drawCircle(color.copy(alpha = 0.08f), radius, Offset(centerX, centerY))
    drawCircle(color, radius, Offset(centerX, centerY), style = Stroke(3f))
    drawCircle(Color.White.copy(alpha = 0.35f), radius * 0.08f, Offset(centerX, centerY), style = Stroke(2f))
    val tick = radius * 0.12f
    drawLine(Color.White.copy(alpha = 0.5f), Offset(centerX - tick, centerY), Offset(centerX + tick, centerY), 2f)
    drawLine(Color.White.copy(alpha = 0.5f), Offset(centerX, centerY - tick), Offset(centerX, centerY + tick), 2f)

    val halfFov = (fovDeg / 2.0).coerceAtLeast(0.05)
    val targetX = centerX + (deltaAz / halfFov).toFloat().coerceIn(-1.15f, 1.15f) * radius
    val targetY = centerY + (-deltaAlt / halfFov).toFloat().coerceIn(-1.15f, 1.15f) * radius
    drawCircle(color.copy(alpha = pulseAlpha), 16f, Offset(targetX, targetY))
    drawCircle(Color.White, 5f, Offset(targetX, targetY))
    drawLine(color.copy(alpha = 0.5f), Offset(centerX, centerY), Offset(targetX, targetY), 2f)
}

private fun DrawScope.drawOnTarget(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    pulseAlpha: Float
) {
    drawCircle(color.copy(alpha = pulseAlpha * 0.25f), radius * 0.9f, Offset(centerX, centerY))
    drawCircle(color, radius * 0.55f, Offset(centerX, centerY), style = Stroke(4f))
    drawCircle(color, radius * 0.28f, Offset(centerX, centerY), style = Stroke(3f))
    drawCircle(color, 14f, Offset(centerX, centerY))
    drawCircle(Color.White, 5f, Offset(centerX, centerY))
}

private fun DrawScope.drawChevron(
    x: Float,
    y: Float,
    rotationDeg: Float,
    color: Color,
    size: Float
) {
    rotate(rotationDeg, Offset(x, y)) {
        val path = Path().apply {
            moveTo(x, y - size)
            lineTo(x - size * 0.7f, y + size * 0.45f)
            lineTo(x, y + size * 0.1f)
            lineTo(x + size * 0.7f, y + size * 0.45f)
            close()
        }
        drawPath(path, color)
        drawPath(path, Color.White.copy(alpha = 0.25f), style = Stroke(2f))
    }
}
