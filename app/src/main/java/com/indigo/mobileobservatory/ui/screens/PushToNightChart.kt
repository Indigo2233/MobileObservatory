package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.pointing.PushToGuidance
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

private data class ChartStar(
    val name: String,
    val raHours: Double,
    val decDeg: Double,
    val magnitude: Double
)

private val brightStars = listOf(
    ChartStar("Sirius", 6.7525, -16.7161, -1.46),
    ChartStar("Canopus", 6.3992, -52.6957, -0.74),
    ChartStar("Arcturus", 14.2610, 19.1825, -0.05),
    ChartStar("Vega", 18.6156, 38.7837, 0.03),
    ChartStar("Capella", 5.2782, 45.9980, 0.08),
    ChartStar("Rigel", 5.2423, -8.2016, 0.13),
    ChartStar("Procyon", 7.6550, 5.2250, 0.34),
    ChartStar("Betelgeuse", 5.9195, 7.4071, 0.42),
    ChartStar("Altair", 19.8464, 8.8683, 0.77),
    ChartStar("Aldebaran", 4.5987, 16.5093, 0.85),
    ChartStar("Spica", 13.4199, -11.1613, 0.98),
    ChartStar("Antares", 16.4901, -26.4320, 1.06),
    ChartStar("Pollux", 7.7553, 28.0262, 1.14),
    ChartStar("Fomalhaut", 22.9608, -29.6222, 1.16),
    ChartStar("Deneb", 20.6905, 45.2803, 1.25),
    ChartStar("Regulus", 10.1395, 11.9672, 1.35),
    ChartStar("Castor", 7.5767, 31.8883, 1.58),
    ChartStar("Bellatrix", 5.4189, 6.3497, 1.64),
    ChartStar("Polaris", 2.5303, 89.2641, 1.98)
)

/** Low-power horizontal sky chart centred between the phone direction and selected target. */
@Composable
internal fun PushToNightChart(
    currentAltDeg: Double,
    currentAzDeg: Double,
    targetAltDeg: Double,
    targetAzDeg: Double,
    site: ObserverSite,
    instant: Instant,
    modifier: Modifier = Modifier
) {
    val targetLabel = stringResource(R.string.push_to_chart_target)
    val fieldLabel = stringResource(R.string.push_to_chart_field)
    val starPositions = remember(site, instant.epochSecond / 30) {
        brightStars.map { star ->
            star to CoordinateTransform.j2000ToTopocentric(
                EquatorialCoordinates(star.raHours * 15.0, star.decDeg),
                instant,
                site,
                refraction = null
            )
        }
    }
    Canvas(modifier.background(Color(0xFF020305))) {
        val azDelta = PushToGuidance.shortestAzimuthDelta(currentAzDeg, targetAzDeg)
        val centerAz = PushToGuidance.normalizeAzimuth(currentAzDeg + azDelta / 2.0)
        val centerAlt = (currentAltDeg + targetAltDeg) / 2.0
        val separation = kotlin.math.hypot(
            targetAltDeg - currentAltDeg,
            azDelta * cos(Math.toRadians(centerAlt)).coerceAtLeast(0.15)
        )
        val span = max(8.0, separation * 1.5).coerceAtMost(140.0)
        val halfSpan = span / 2.0
        val scaleX = size.width / span
        val scaleY = size.height / span

        fun project(alt: Double, az: Double): Offset? {
            val daz = PushToGuidance.shortestAzimuthDelta(centerAz, az) *
                cos(Math.toRadians(centerAlt)).coerceAtLeast(0.15)
            val dalt = alt - centerAlt
            if (abs(daz) > halfSpan || abs(dalt) > halfSpan) return null
            return Offset(
                size.width / 2f + (daz * scaleX).toFloat(),
                size.height / 2f - (dalt * scaleY).toFloat()
            )
        }

        val grid = Color(0xFF421515)
        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            drawCircle(
                grid.copy(alpha = 0.7f),
                radius = size.minDimension * fraction / 2f,
                center = center,
                style = Stroke(1f)
            )
        }
        drawLine(grid, Offset(0f, center.y), Offset(size.width, center.y), 1f)
        drawLine(grid, Offset(center.x, 0f), Offset(center.x, size.height), 1f)

        val labelPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(150, 85, 85)
            textSize = size.minDimension * 0.035f
        }
        starPositions.forEach { (star, horizontal) ->
            val point = project(horizontal.altitudeDeg, horizontal.azimuthDeg) ?: return@forEach
            val radius = (5.5 - star.magnitude).coerceIn(2.0, 6.0).toFloat()
            drawCircle(Color(0xFFFFE8D0), radius, point)
            if (star.magnitude <= 1.0) {
                drawContext.canvas.nativeCanvas.drawText(
                    star.name,
                    point.x + radius + 4f,
                    point.y - radius,
                    labelPaint
                )
            }
        }

        val current = project(currentAltDeg, currentAzDeg) ?: center
        val target = project(targetAltDeg, targetAzDeg) ?: center
        drawLine(
            Color(0xFFFF6E6E).copy(alpha = 0.7f),
            current,
            target,
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
        drawCircle(Color.White, 8f, current, style = Stroke(3f))
        drawLine(Color.White, current - Offset(12f, 0f), current + Offset(12f, 0f), 2f)
        drawLine(Color.White, current - Offset(0f, 12f), current + Offset(0f, 12f), 2f)
        drawCircle(Color(0xFFFF5252).copy(alpha = 0.25f), 24f, target)
        drawCircle(Color(0xFFFF8A80), 14f, target, style = Stroke(4f))

        labelPaint.color = android.graphics.Color.rgb(255, 138, 128)
        labelPaint.textSize = size.minDimension * 0.042f
        drawContext.canvas.nativeCanvas.drawText(targetLabel, target.x + 18f, target.y - 12f, labelPaint)
        labelPaint.color = android.graphics.Color.rgb(125, 90, 90)
        labelPaint.textSize = size.minDimension * 0.032f
        drawContext.canvas.nativeCanvas.drawText(
            "$fieldLabel ${span.toInt()}°",
            12f,
            size.height - 12f,
            labelPaint
        )
    }
}
