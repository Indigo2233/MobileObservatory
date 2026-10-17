package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.AppBinning
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.stacking.*
import com.indigo.mobileobservatory.ui.screens.MountMotionStopBanner
import com.indigo.mobileobservatory.ui.screens.collectAsStateWithPageLifecycle
import com.indigo.mobileobservatory.ui.screens.stackMessage
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.min

/** Both capture ROI and live stacking use the same context and action boundary. */
@Composable
fun StackRoiControls(viewModel: CameraViewModel, showBinning: Boolean = false) {
    var context by remember { mutableStateOf(viewModel.stackRoiContext()) }
    LaunchedEffect(viewModel) { while (true) { context = viewModel.stackRoiContext(); delay(300) } }
    val current = context
    if (current == null) Text(stringResource(R.string.stack_roi_need_camera), style = MaterialTheme.typography.bodySmall)
    else StackRoiPanel(current, showBinning, viewModel::setStackPlanningBin) { roi ->
        val applied = viewModel.applyStackRoi(current, roi)
        context = viewModel.stackRoiContext()
        applied
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun StackRoiPanel(context: StackRoiContext, showBinning: Boolean = false,
                          onBin: (Int) -> Unit = {}, onApply: (Roi) -> Boolean) {
    var aspect by rememberSaveable { mutableStateOf(StackRoiAspect.SENSOR) }
    var details by rememberSaveable { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    val current = context.key.roi
    var centerX by rememberSaveable(context.sensorWidth, context.key.hardwareBin, current) {
        mutableDoubleStateOf(current.x + current.width / 2.0)
    }
    var centerY by rememberSaveable(context.sensorHeight, context.key.hardwareBin, current) {
        mutableDoubleStateOf(current.y + current.height / 2.0)
    }
    val maximum = remember(context, aspect, centerX, centerY) { StackRoiPlanner.maximum(context, aspect, centerX, centerY) }
    val issue = context.issue()
    val software = context.key.softwareBin.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().testTag("stack-roi-planner"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!showBinning) Text(stringResource(R.string.stack_roi_title), style = MaterialTheme.typography.titleSmall)
        if (showBinning) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bin", modifier = Modifier.align(Alignment.CenterVertically))
            AppBinning.OPTIONS.forEach { bin ->
                FilterChip(selected = context.key.hardwareBin * software == bin,
                    enabled = context.blockedReason == null, onClick = { onBin(bin) }, label = { Text("$bin×") })
            }
        }
        Text(stringResource(R.string.stack_roi_bins, context.key.hardwareBin, software), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.stack_roi_current, current.width, current.height, current.width / software, current.height / software),
            style = MaterialTheme.typography.bodySmall)
        Text(if (issue == null) stringResource(R.string.stack_roi_fits) else stackMessage(issue),
            color = if (issue == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StackRoiAspect.entries.forEach { choice ->
                FilterChip(selected = aspect == choice, onClick = { aspect = choice },
                    modifier = Modifier.testTag("stack-roi-aspect-${choice.name}"),
                    label = { Text(when (choice) {
                        StackRoiAspect.SENSOR -> stringResource(R.string.stack_roi_sensor_ratio)
                        StackRoiAspect.SQUARE -> "1:1"
                        StackRoiAspect.FOUR_THREE -> "4:3"
                        StackRoiAspect.SIXTEEN_NINE -> "16:9"
                    }) })
            }
        }
        val outline = MaterialTheme.colorScheme.outline
        val currentColor = if (issue == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
        val maxColor = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(156.dp).testTag("stack-roi-frame")
            .pointerInput(context.sensorWidth, context.sensorHeight, context.key.hardwareBin, current) {
                detectTapGestures { point ->
                    val scale = min(size.width.toFloat() / context.sensorWidth, size.height.toFloat() / context.sensorHeight)
                    val left = (size.width - context.sensorWidth * scale) / 2
                    val top = (size.height - context.sensorHeight * scale) / 2
                    centerX = ((point.x - left) / scale).toDouble().coerceIn(0.0, context.sensorWidth.toDouble())
                    centerY = ((point.y - top) / scale).toDouble().coerceIn(0.0, context.sensorHeight.toDouble())
                }
            }) {
            val scale = min(size.width / context.sensorWidth, size.height / context.sensorHeight)
            val origin = Offset((size.width - context.sensorWidth * scale) / 2, (size.height - context.sensorHeight * scale) / 2)
            drawRect(outline, origin, Size(context.sensorWidth * scale, context.sensorHeight * scale), style = Stroke(1.dp.toPx()))
            fun topLeft(roi: Roi) = origin + Offset(roi.x * scale, roi.y * scale)
            drawRect(currentColor, topLeft(current), Size(current.width * scale, current.height * scale), style = Stroke(2.dp.toPx()))
            maximum?.let { roi ->
                drawRect(maxColor.copy(alpha = .12f), topLeft(roi), Size(roi.width * scale, roi.height * scale))
                drawRect(maxColor, topLeft(roi), Size(roi.width * scale, roi.height * scale),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 5.dp.toPx()))))
            }
        }
        Text(stringResource(R.string.stack_roi_frame_hint), style = MaterialTheme.typography.labelSmall)
        if (maximum != null) {
            Text(stringResource(R.string.stack_roi_maximum, maximum.width, maximum.height),
                modifier = Modifier.testTag("stack-roi-maximum"), color = maxColor, style = MaterialTheme.typography.titleSmall)
            Button(onClick = { changed = !onApply(maximum) }, enabled = context.blockedReason == null && maximum != current,
                modifier = Modifier.fillMaxWidth().testTag("stack-roi-apply")) { Text(stringResource(R.string.stack_roi_apply)) }
        } else Text(stringResource(R.string.stack_roi_unavailable), style = MaterialTheme.typography.bodySmall)
        if (changed) Text(stringResource(R.string.stack_roi_changed), color = MaterialTheme.colorScheme.error)
        context.blockedReason?.let { reason ->
            Text(when (reason) {
                "roi_calibration_selected" -> stringResource(R.string.stack_roi_calibration_locked)
                "roi_capture_busy" -> stringResource(R.string.stack_roi_capture_busy)
                else -> stackMessage(reason)
            },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { details = !details }) {
            Text(stringResource(R.string.stack_roi_why))
            Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (details) {
            fun mib(value: Long) = ceil(value / 1048576.0).toLong()
            Text(stringResource(R.string.stack_roi_memory, mib(context.estimatedBytes()), mib(context.budgetBytes)), style = MaterialTheme.typography.bodySmall)
            maximum?.let { Text(stringResource(R.string.stack_roi_estimated_max, mib(context.estimatedBytes(it))), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.stack_roi_explanation), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackRoiDialog(viewModel: CameraViewModel, onClose: () -> Unit) {
    val motion by viewModel.mountMotionState.collectAsStateWithPageLifecycle()
    val recording by viewModel.isRecording.collectAsStateWithPageLifecycle()
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PopupWindowBackHandler(onBack = onClose)
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp).navigationBarsPadding().padding(16.dp)) {
            MountMotionStopBanner(motion, viewModel::stopMountMotion)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.stack_roi_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (recording) TextButton(onClick = viewModel::stopRecording) { Text(stringResource(R.string.stop_recording)) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { StackRoiControls(viewModel, showBinning = true) }
        }
    }
}
