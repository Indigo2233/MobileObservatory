package com.indigo.mobileobservatory.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.polar.PolarAdjustmentSnapshot
import com.indigo.mobileobservatory.polar.PolarErrorReticle
import com.indigo.mobileobservatory.polar.formatPolarAngle
import kotlin.math.min

/** A screen-aligned angular diagram over the photograph; zooming the photograph never changes its units. */
@Composable
internal fun PolarErrorReticleOverlay(snapshot: PolarAdjustmentSnapshot, modifier: Modifier = Modifier) {
    val model = PolarErrorReticle.from(snapshot.current, snapshot.determination.site.latitudeDeg, snapshot.reticleStepArcMin)
    val colors = polarOverlayColors()
    val title = stringResource(R.string.polar_reticle_title)
    val directions = listOf(R.string.polar_reticle_east, R.string.polar_reticle_west,
        R.string.polar_reticle_up, R.string.polar_reticle_down).map { stringResource(it) }
    val labels = model.rings.map { formatPolarAngle(it / 60.0, compact = true) }
    val description = stringResource(R.string.polar_reticle_description, labels.joinToString(" / "),
        formatPolarAngle(model.eastArcMin / 60.0, signed = true),
        formatPolarAngle(model.upArcMin / 60.0, signed = true))
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = minOf(180.dp, maxWidth * .52f, maxHeight)
        Canvas(Modifier.align(Alignment.TopEnd).size(side).testTag("polar-error-reticle")
            .semantics { contentDescription = description }) {
            val center = Offset(size.width / 2, size.height * .55f)
            val radius = min(size.width * .34f, size.height * .34f)
            val stroke = 1.dp.toPx()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10.sp.toPx(); textAlign = Paint.Align.CENTER
            }
            fun label(text: String, position: Offset, color: Color = colors.reference) {
                // A dark outline remains legible across stars and bright backgrounds.
                paint.color = Color.Black.toArgb(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(text, position.x, position.y, paint)
                paint.color = color.toArgb(); paint.style = Paint.Style.FILL
                drawContext.canvas.nativeCanvas.drawText(text, position.x, position.y, paint)
            }
            drawCircle(Color.Black.copy(alpha = .6f), radius + 14.dp.toPx(), center)
            model.rings.forEachIndexed { i, _ ->
                val r = radius * (i + 1) / 3
                drawCircle(colors.reference.copy(alpha = .65f), r, center, style = Stroke(stroke))
                val labelPosition = when (i) {
                    0 -> Offset(-r * .707f, -r * .707f)
                    1 -> Offset(-r * .707f, r * .707f)
                    else -> Offset(r * .707f, r * .707f)
                }
                label(labels[i], center + labelPosition, colors.reference)
            }
            drawLine(colors.azimuth.copy(alpha = .6f), center - Offset(radius, 0f), center + Offset(radius, 0f), stroke)
            drawLine(colors.altitude.copy(alpha = .6f), center - Offset(0f, radius), center + Offset(0f, radius), stroke)
            val current = center + Offset((model.eastArcMin / model.radiusArcMin * radius).toFloat(),
                (-model.upArcMin / model.radiusArcMin * radius).toFloat())
            val corner = Offset(center.x, current.y)
            drawLine(colors.azimuth, current, corner, 2 * stroke)
            drawLine(colors.altitude, corner, center, 2 * stroke)
            drawCircle(colors.target, 4.dp.toPx(), center, style = Stroke(2 * stroke))
            drawCircle(Color.Black, 5.dp.toPx(), current)
            drawCircle(colors.reference, 3.dp.toPx(), current)
            label(title, Offset(center.x, 12.sp.toPx()))
            label(directions[0], center + Offset(radius + 10.dp.toPx(), 4.sp.toPx()), colors.azimuth)
            label(directions[1], center + Offset(-radius - 10.dp.toPx(), 4.sp.toPx()), colors.azimuth)
            label(directions[2], center + Offset(0f, -radius - 4.dp.toPx()), colors.altitude)
            label(directions[3], center + Offset(0f, radius + 12.sp.toPx()), colors.altitude)
        }
    }
}
