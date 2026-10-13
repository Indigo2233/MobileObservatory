@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.stacking.*
import kotlin.math.*
import org.json.JSONObject

private val backgroundDraftSaver = Saver<BackgroundParameters, String>(
    save = { StackResultMetadata.parameters(StackProcessingParameters(background = it)).toString() },
    restore = { runCatching { StackResultMetadata.parseParameters(JSONObject(it)).background }.getOrNull() }
)

@Composable
internal fun backgroundModeLabel(p: BackgroundParameters): String = stringResource(when {
    !p.enabled -> R.string.stack_bg_off
    p.mode == BackgroundMode.MANUAL -> R.string.stack_bg_manual
    else -> R.string.stack_bg_auto
})

/** Edits a draft against a fixed preview; only Apply changes the rendering recipe. */
@Composable
internal fun BackgroundEditor(bitmap: Bitmap?, initial: BackgroundParameters,
    onDismiss: () -> Unit, onApply: (BackgroundParameters) -> Unit) {
    var draft by rememberSaveable(stateSaver = backgroundDraftSaver) { mutableStateOf(initial) }
    val preview = remember { bitmap }
    var drawingMask by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var candidateX by rememberSaveable { mutableStateOf<Double?>(null) }
    var candidateY by rememberSaveable { mutableStateOf<Double?>(null) }
    val candidate = candidateX?.let { x -> candidateY?.let { y -> BackgroundSamplePoint(x, y) } }
    val aspect = (preview?.width ?: 1).toDouble() / (preview?.height ?: 1)
    // Use the same image-space hit radius for removal and loupe refinement/addition.
    fun matchingPoint(point: BackgroundSamplePoint) = draft.samplePoints.indexOfFirst {
        hypot((it.x-point.x) * max(1.0, aspect), (it.y-point.y) * max(1.0, 1/aspect)) < max(.025, draft.sampleRadius)
    }
    val candidateOverlaps = candidate?.let { matchingPoint(it) >= 0 } ?: false
    fun clearCandidate() { candidateX = null; candidateY = null }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.stack_background), style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(draft.enabled && draft.mode == BackgroundMode.AUTO,
                        onClick = { draft = draft.copy(enabled = true, mode = BackgroundMode.AUTO); drawingMask = false; clearCandidate() },
                        label = { Text(stringResource(R.string.stack_bg_auto)) })
                    FilterChip(draft.enabled && draft.mode == BackgroundMode.MANUAL,
                        onClick = { draft = draft.copy(enabled = true, mode = BackgroundMode.MANUAL); drawingMask = false },
                        label = { Text(stringResource(R.string.stack_bg_manual)) })
                    FilterChip(!draft.enabled, onClick = { draft = draft.copy(enabled = false); drawingMask = false; clearCandidate() },
                        label = { Text(stringResource(R.string.stack_bg_off)) })
                }
                val controls: @Composable () -> Unit = {
                    if (draft.enabled) {
                        if (drawingMask) {
                            Text(stringResource(R.string.stack_drag_hint), color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { drawingMask = false }) { Text(stringResource(R.string.cancel)) }
                        } else if (draft.mode == BackgroundMode.MANUAL) {
                            Text(stringResource(R.string.stack_dbe_tap_hint), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.stack_dbe_count, draft.samplePoints.size),
                                color = if (draft.samplePoints.size < 12) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { draft = draft.copy(samplePoints = draft.samplePoints.dropLast(1)) }, enabled = draft.samplePoints.isNotEmpty()) {
                                    Text(stringResource(R.string.stack_dbe_undo))
                                }
                                TextButton(onClick = { draft = draft.copy(samplePoints = emptyList()); clearCandidate() }, enabled = draft.samplePoints.isNotEmpty()) {
                                    Text(stringResource(R.string.stack_dbe_clear))
                                }
                            }
                        } else Text(stringResource(R.string.stack_gc_hint), style = MaterialTheme.typography.bodySmall)
                        StackSlider(R.string.stack_bg_strength, draft.strength, 0.0..1.0) { draft = draft.copy(strength = it) }
                        TextButton(onClick = { advanced = !advanced }) { Text(stringResource(if (advanced) R.string.stack_hide_advanced else R.string.stack_show_advanced)) }
                        if (advanced) {
                            if (draft.mode == BackgroundMode.MANUAL)
                                StackSlider(R.string.stack_dbe_radius, draft.sampleRadius, .005.. .1) { draft = draft.copy(sampleRadius = it) }
                            else StackSlider(R.string.stack_bg_grid, draft.gridSize.toDouble(), 6.0..24.0) { draft = draft.copy(gridSize = it.roundToInt()) }
                            Text(stringResource(R.string.stack_masks, draft.targetMasks.size))
                            OutlinedButton(onClick = { drawingMask = true; clearCandidate() }, enabled = preview != null) { Text(stringResource(R.string.stack_draw_mask)) }
                            TextButton(onClick = { draft = draft.copy(targetMasks = emptyList()) }, enabled = draft.targetMasks.isNotEmpty()) { Text(stringResource(R.string.stack_clear_masks)) }
                        }
                    }
                }
                val image: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier) {
                    StackImage(preview, if (draft.enabled) draft.targetMasks else emptyList(), drawingMask, Modifier.fillMaxSize(),
                        points = if (draft.enabled && draft.mode == BackgroundMode.MANUAL) draft.samplePoints else emptyList(),
                        sampleRadius = draft.sampleRadius,
                        candidatePoint = candidate,
                        onPoint = if (draft.enabled && draft.mode == BackgroundMode.MANUAL && !drawingMask) ({ point ->
                            val match = matchingPoint(point)
                            if (match >= 0) {
                                draft = draft.copy(samplePoints = draft.samplePoints.filterIndexed { index, _ -> index != match })
                                clearCandidate()
                            } else { candidateX = point.x; candidateY = point.y }
                        }) else null,
                        selected = { rectangle -> draft = draft.copy(targetMasks = draft.targetMasks + rectangle); drawingMask = false })
                    if (candidate != null && preview != null) {
                        val oppositeCorner = when {
                            candidate.x >= .5 && candidate.y >= .5 -> Alignment.TopStart
                            candidate.x >= .5 -> Alignment.BottomStart
                            candidate.y >= .5 -> Alignment.TopEnd
                            else -> Alignment.BottomEnd
                        }
                        SampleLoupe(preview, candidate, draft.sampleRadius, Modifier.align(oppositeCorner).padding(6.dp),
                            onRefine = { point -> candidateX = point.x; candidateY = point.y },
                            onCancel = ::clearCandidate,
                            overlapsExisting = candidateOverlaps,
                            canAdd = draft.samplePoints.size < 128 && !candidateOverlaps,
                            onAdd = {
                                // Recheck at commit, including two taps before recomposition.
                                if (draft.samplePoints.size < 128 && matchingPoint(candidate) < 0) {
                                    draft = draft.copy(samplePoints = draft.samplePoints + candidate)
                                    clearCandidate()
                                }
                            })
                    }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f)) {
                    if (maxWidth >= 640.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        image(Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { controls() }
                    } else Column(Modifier.fillMaxSize()) {
                        image(Modifier.fillMaxWidth().weight(1f))
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) { controls() }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { onApply(draft) }, enabled = candidate == null && (!draft.enabled || draft.mode != BackgroundMode.MANUAL || draft.samplePoints.size >= 12)) {
                        Text(stringResource(R.string.stack_bg_apply))
                    }
                }
            }
        }
    }
}

/** Magnifies the displayed sky before committing a sample. Tapping the loupe moves its centre
 * in source-image coordinates; edge crops preserve that mapping and never invent pixels. */
@Composable
private fun SampleLoupe(bitmap: Bitmap, point: BackgroundSamplePoint, radius: Double, modifier: Modifier,
    onRefine: (BackgroundSamplePoint) -> Unit, onCancel: () -> Unit, overlapsExisting: Boolean,
    canAdd: Boolean, onAdd: () -> Unit) {
    val side = (min(bitmap.width, bitmap.height) / 8).coerceIn(8, 96).coerceAtMost(min(bitmap.width, bitmap.height))
    val x = point.x * bitmap.width
    val y = point.y * bitmap.height
    val left = (x - side/2.0).roundToInt().coerceIn(0, bitmap.width - side)
    val top = (y - side/2.0).roundToInt().coerceIn(0, bitmap.height - side)
    val color = MaterialTheme.colorScheme.primary
    Surface(modifier.width(164.dp).testTag("stack-sample-loupe-card"), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
        tonalElevation = 8.dp) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(if (overlapsExisting) R.string.stack_dbe_point_too_close else R.string.stack_dbe_loupe),
                style = MaterialTheme.typography.labelSmall,
                color = if (overlapsExisting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(Modifier.fillMaxWidth().height(118.dp).clipToBounds().background(Color.Black).testTag("stack-sample-loupe")
                .pointerInput(left, top, side, bitmap.width, bitmap.height) {
                    detectTapGestures { tap ->
                        val cropSize = min(size.width, size.height).toFloat()
                        val ox = (size.width-cropSize)/2
                        if (tap.x in ox..(ox+cropSize) && tap.y in 0f..cropSize) onRefine(BackgroundSamplePoint(
                            ((left + (tap.x-ox)/cropSize * side) / bitmap.width).toDouble().coerceIn(0.0,1.0),
                            ((top + tap.y/cropSize * side) / bitmap.height).toDouble().coerceIn(0.0,1.0)))
                    }
                }) {
                val dst = min(size.width, size.height).roundToInt()
                val ox = ((size.width - dst)/2).roundToInt()
                drawImage(bitmap.asImageBitmap(), IntOffset(left, top), IntSize(side, side), IntOffset(ox,0), IntSize(dst,dst), filterQuality = FilterQuality.None)
                val center = Offset((ox + (x-left)/side*dst).toFloat(), ((y-top)/side*dst).toFloat())
                drawCircle(color, (radius*min(bitmap.width,bitmap.height)/side*dst).toFloat(), center, style = Stroke(1.dp.toPx()))
                // Hollow centre leaves a star at the exact sample location visible.
                val gap = 3.dp.toPx(); val arm = 9.dp.toPx()
                drawLine(color, center-Offset(arm,0f), center-Offset(gap,0f), 1.dp.toPx())
                drawLine(color, center+Offset(gap,0f), center+Offset(arm,0f), 1.dp.toPx())
                drawLine(color, center-Offset(0f,arm), center-Offset(0f,gap), 1.dp.toPx())
                drawLine(color, center+Offset(0f,gap), center+Offset(0f,arm), 1.dp.toPx())
            }
            Row {
                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(stringResource(R.string.cancel)) }
                Button(onClick = onAdd, enabled = canAdd, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.stack_dbe_add)) }
            }
        }
    }
}
