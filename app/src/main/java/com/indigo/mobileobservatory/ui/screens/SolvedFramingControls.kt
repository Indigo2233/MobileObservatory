package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.EquatorialEpoch
import com.indigo.mobileobservatory.astro.FovComputation
import com.indigo.mobileobservatory.astrometry.SolvedFrameFraming
import com.indigo.mobileobservatory.pointing.PhoneBrightStarCatalog
import com.indigo.mobileobservatory.pointing.SkyDirection
import com.indigo.mobileobservatory.pointing.SkyPose
import com.indigo.mobileobservatory.pointing.SkyVector
import com.indigo.mobileobservatory.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.hypot
import kotlin.math.tan

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SolvedFramingControls(
    framing: SolvedFrameFraming,
    opticsApplied: Boolean,
    onApplyOptics: () -> Unit,
    onBack: () -> Unit
) {
    var confirm by remember(framing) { mutableStateOf(false) }
    val exposureTime = remember(framing.exposureStartedAt) {
        framing.exposureStartedAt?.let { DateFormat.getDateTimeInstance().format(Date.from(it)) }
    }
    val focal = framing.focalLengthMm
    Column(Modifier.testTag("star-map-solved-framing")) {
        Text(stringResource(R.string.solved_framing_frame), style = MaterialTheme.typography.titleSmall)
        Text(listOfNotNull(framing.cameraName, exposureTime).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.solved_framing_field, requireNotNull(framing.field.rectWidthDeg),
            requireNotNull(framing.field.rectHeightDeg), framing.positionAngleDeg),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.solved_framing_repeat), style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.solved_framing_back)) }
            if (framing.sensor != null && focal != null) {
                TextButton(onClick = { confirm = true }, enabled = !opticsApplied,
                    modifier = Modifier.testTag("solved-framing-apply-optics")) {
                    Text(stringResource(if (opticsApplied) R.string.solved_framing_applied else R.string.solved_framing_apply))
                }
            }
        }
    }
    val sensor = framing.sensor
    if (confirm && sensor != null && focal != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.solved_framing_apply)) },
            text = { Text(stringResource(R.string.solved_framing_apply_details, sensor.name, sensor.widthPx,
                sensor.heightPx, sensor.pixelSizeUm, focal)) },
            confirmButton = { TextButton(onClick = { confirm = false; onApplyOptics() }) { Text(stringResource(R.string.solved_framing_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
internal fun NativeSolvedFraming(
    framing: SolvedFrameFraming,
    target: StarMapTarget?,
    targetField: FovComputation?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val center = remember(framing) { SkyDirection(framing.anchor.raHours * 15, framing.anchor.decDegrees) }
    val view = remember(center) { SkyPose.fromSolution(center, 0.0) }
    val stars by produceState<List<Pair<SkyVector, Double>>>(emptyList(), context) {
        try {
            value = withContext(Dispatchers.IO) {
                PhoneBrightStarCatalog.load(context).stars.map { SkyDirection(it.raDeg, it.decDeg).vector() to it.magnitude }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            FileLogger.e("SolvedFraming", "Unable to load the native star chart", failure)
        }
    }
    val targetDirection = remember(target) {
        target?.let {
            val coordinates = EquatorialCoordinates(it.raHours * 15, it.decDegrees)
            val j2000 = if (EquatorialEpoch.isJ2000(it.frame)) coordinates else EquatorialEpoch.jnowToJ2000(coordinates)
            SkyDirection(j2000.raDeg, j2000.decDeg)
        }
    }
    val measured = remember(framing, center) { framingCorners(center, framing.field, framing.positionAngleDeg) }
    val planned = remember(target, targetDirection, targetField) {
        if (target != null && targetDirection != null && targetField != null) {
            framingCorners(targetDirection, targetField, target.positionAngleDeg)
        } else emptyList()
    }
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val starColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.testTag("native-solved-framing")) {
        val half = { degrees: Double -> tan(Math.toRadians(degrees / 2)) }
        val span = hypot(half(requireNotNull(framing.field.rectWidthDeg)), half(requireNotNull(framing.field.rectHeightDeg)))
        val scale = size.minDimension / (span * 2.5)
        fun project(ray: SkyVector): Offset? {
            val z = ray.dot(view.forward)
            if (z <= 0.0) return null
            return Offset((size.width / 2 - scale * ray.dot(view.right) / z).toFloat(),
                (size.height / 2 - scale * ray.dot(view.up) / z).toFloat())
        }
        drawLine(gridColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        drawLine(gridColor, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
        for ((ray, magnitude) in stars) {
            val point = project(ray) ?: continue
            if (point.x in 0f..size.width && point.y in 0f..size.height) {
                drawCircle(starColor, (4.0 - magnitude * 0.45).coerceAtLeast(1.0).toFloat(), point)
            }
        }
        fun polygon(corners: List<SkyVector>): Path? {
            val points = corners.mapNotNull(::project)
            if (points.isEmpty() || points.size != corners.size) return null
            return Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
        }
        polygon(measured)?.let { drawPath(it, Color(0xFF57CF96), style = Stroke(2.dp.toPx())) }
        polygon(planned)?.let { drawPath(it, Color(0xFFFFCE65), style = Stroke(2.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(9f, 6f)))) }
        targetDirection?.vector()?.let(::project)?.let { drawCircle(Color(0xFFFFCE65), 4.dp.toPx(), it, style = Stroke(1.dp.toPx())) }
    }
}

private fun framingCorners(center: SkyDirection, field: FovComputation, angle: Double): List<SkyVector> {
    val width = field.rectWidthDeg ?: return emptyList()
    val height = field.rectHeightDeg ?: return emptyList()
    val pose = SkyPose.fromSolution(center, angle)
    val x = tan(Math.toRadians(width / 2))
    val y = tan(Math.toRadians(height / 2))
    return listOf(SkyVector(-x, y, 1.0), SkyVector(x, y, 1.0), SkyVector(x, -y, 1.0),
        SkyVector(-x, -y, 1.0)).map(pose::project)
}
