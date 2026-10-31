package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astrometry.ImagePoint
import com.indigo.mobileobservatory.polar.*
import com.indigo.mobileobservatory.ui.theme.ObservatoryTheme
import kotlin.math.*

internal data class PolarOverlayColors(val azimuth: Color, val altitude: Color, val target: Color, val reference: Color)
@Composable internal fun polarOverlayColors() = if (ObservatoryTheme.redNight)
    PolarOverlayColors(Color(0xffff8888), Color(0xffce5555), Color(0xffff4444), Color(0xffffbbbb))
else PolarOverlayColors(Color(0xffffd54f), Color(0xff43d9ef), Color(0xff62e39e), Color.White)

/** Fit the photograph only. Large corrections are clipped at the photograph's boundary. */
@Composable
internal fun PolarCorrectionImage(image: Bitmap, width: Int, height: Int, geometry: PolarCorrectionGeometry?, modifier: Modifier) {
    val colors = polarOverlayColors()
    val description = stringResource(if (geometry == null) R.string.polar_sample_preview else R.string.polar_correction_overlay_description)
    Canvas(modifier.semantics { contentDescription = description }
        .then(if (geometry != null) Modifier.testTag("polar-correction-overlay") else Modifier)) {
        val scale = minOf(size.width / width, size.height / height)
        val offset = Offset((size.width - width * scale) / 2f, (size.height - height * scale) / 2f)
        val imageSize = IntSize(maxOf(1, (width * scale).roundToInt()), maxOf(1, (height * scale).roundToInt()))
        drawImage(image.asImageBitmap(), dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()), dstSize = imageSize)
        if (geometry != null) {
            drawRect(colors.reference.copy(alpha = .35f), offset, Size(imageSize.width.toFloat(), imageSize.height.toFloat()), style = Stroke(1.dp.toPx()))
            clipRect(offset.x, offset.y, offset.x + imageSize.width, offset.y + imageSize.height) {
                drawPolarCorrection(geometry, offset, imageSize, colors)
            }
        }
    }
}

internal fun DrawScope.drawPolarCorrection(geometry: PolarCorrectionGeometry, imageOffset: Offset, imageSize: IntSize,
                                          colors: PolarOverlayColors) {
    fun point(p: ImagePoint) = imageOffset + Offset(((p.x + .5) * imageSize.width / geometry.width).toFloat(),
        ((p.y + .5) * imageSize.height / geometry.height).toFloat())
    val o = point(geometry.reference); val a = point(geometry.azimuth); val t = point(geometry.target); val h = point(geometry.altitude)
    val stroke = 2.dp.toPx()
    fun line(from: Offset, to: Offset, color: Color, dashed: Boolean = false) {
        drawLine(Color.Black.copy(alpha = .8f), from, to, stroke + 2.dp.toPx())
        drawLine(color, from, to, stroke, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null)
    }
    fun arrow(from: Offset, to: Offset, color: Color) {
        line(from, to, color)
        val delta = to - from; val length = delta.getDistance()
        if (length > 5.dp.toPx()) {
            val unit = delta / length; val side = Offset(-unit.y, unit.x)
            val wing = minOf(8.dp.toPx(), length * .3f)
            line(to, to - unit * wing + side * wing * .45f, color)
            line(to, to - unit * wing - side * wing * .45f, color)
        }
    }
    line(o, h, colors.altitude, true); line(h, t, colors.azimuth, true)
    arrow(o, a, colors.azimuth); arrow(a, t, colors.altitude)
    drawCircle(Color.Black, 6.dp.toPx(), o)
    drawLine(colors.reference, o - Offset(7.dp.toPx(), 0f), o + Offset(7.dp.toPx(), 0f), stroke)
    drawLine(colors.reference, o - Offset(0f, 7.dp.toPx()), o + Offset(0f, 7.dp.toPx()), stroke)
    drawCircle(Color.Black, 8.dp.toPx(), t, style = Stroke(4.dp.toPx()))
    drawCircle(colors.target, 8.dp.toPx(), t, style = Stroke(stroke))
    drawCircle(colors.azimuth, 3.dp.toPx(), a)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PolarCorrectionSummary(snapshot: PolarAdjustmentSnapshot, modifier: Modifier = Modifier) {
    val result = snapshot.current
    val azimuthLabel = stringResource(
        if (abs(result.azimuthErrorDeg) < .5 / 3600.0) R.string.azimuth
        else if (polarAdjustWest(result.azimuthErrorDeg, snapshot.determination.site.latitudeDeg))
            R.string.polar_turn_west else R.string.polar_turn_east)
    val altitudeLabel = stringResource(
        if (abs(result.altitudeErrorDeg) < .5 / 3600.0) R.string.altitude
        else if (polarAdjustDown(result.altitudeErrorDeg, snapshot.determination.site.latitudeDeg))
            R.string.polar_turn_down else R.string.polar_turn_up)
    Surface(modifier.testTag("polar-error-summary").semantics(mergeDescendants = true) {},
        color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.polar_summary_total, formatPolarAngle(result.totalErrorDeg)), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("polar-total-error"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.polar_error_value, formatPolarAngle(abs(result.azimuthErrorDeg)),
                    azimuthLabel), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.polar_error_value, formatPolarAngle(abs(result.altitudeErrorDeg)),
                    altitudeLabel), style = MaterialTheme.typography.titleSmall)
            }
            if (snapshot.geometry == null) Text(stringResource(if (snapshot.projectionUnavailable)
                R.string.polar_correction_no_projection else R.string.polar_correction_no_wcs),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else if (snapshot.geometry.targetOutsideImage) Text(stringResource(R.string.polar_correction_outside), style = MaterialTheme.typography.bodySmall)
        }
    }
}

