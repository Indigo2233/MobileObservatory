@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.recording.SerEndianConvention
import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.camera.AutoExposureMode
import com.indigo.mobileobservatory.stacking.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun StackingScreen(viewModel: CameraViewModel, mode: StackMode, onBack: () -> Unit,
    onCamera: () -> Unit = onBack, onOpenResult: (File?) -> Unit = {}) {
    val coordinator = viewModel.stacking
    val task = coordinator.task.collectAsState().value
    val rendering by coordinator.rendering.collectAsState()
    val live = coordinator.live.state.collectAsState().value
    val rendered by coordinator.preview.collectAsState()
    val cameraFrame by viewModel.previewBitmap.collectAsState()
    val showStack by coordinator.showLiveStack.collectAsState()
    val parameters by coordinator.processing.collectAsState()
    val backgroundWarning by coordinator.backgroundWarning.collectAsState()
    var drawRoi by rememberSaveable { mutableStateOf(false) }
    var drawnRoi by remember { mutableStateOf<PixelRoi?>(null) }
    var browseRecordings by rememberSaveable { mutableStateOf(false) }
    var observingOnly by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var editBackground by rememberSaveable { mutableStateOf(false) }
    var editCalibration by rememberSaveable { mutableStateOf(false) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(coordinator::importFile)
    }
    BackHandler(onBack = onBack)
    if (editCalibration) CalibrationPanel(viewModel, onDismiss = { editCalibration = false }, onCamera = onCamera)
    if (browseRecordings) AppRecordingPicker(viewModel,
        onDismiss = { browseRecordings = false },
        onSelect = { file -> browseRecordings = false; viewModel.openPlanetaryStack(file) },
        onCamera = { browseRecordings = false; onCamera() })

    val bitmap = if (mode == StackMode.LIVE && (!showStack || rendered == null)) cameraFrame else rendered
    if (editBackground) BackgroundEditor(rendered, parameters.background,
        onDismiss = { editBackground = false },
        onApply = { background ->
            coordinator.updateProcessing(parameters.copy(background = background))
            editBackground = false
        })
    // The controls keep one composition position; adaptation is only a measurement change.
    val controls: @Composable () -> Unit = {
        CalibrationEntry(viewModel, onOpen = { editCalibration = true })
        if (task.exportedFiles.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.stack_saved_easy), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.stack_saved_library), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenResult(task.exportedFiles.firstOrNull { it.endsWith(".png", true) }?.let(::File)) }) {
                            Text(stringResource(R.string.stack_view_saved))
                        }
                        TextButton(onClick = { onOpenResult(null) }) { Text(stringResource(R.string.stack_all_results)) }
                    }
                }
            }
        }
        if (mode == StackMode.LIVE) {
            LiveStackControls(viewModel, live, showStack, onCamera)
        } else {
            Text(stringResource(R.string.stack_planetary_steps), style = MaterialTheme.typography.bodySmall)
            PlanetaryControls(coordinator, task, drawnRoi,
                onChoose = { documentPicker.launch(arrayOf("*/*")) },
                onBrowse = { browseRecordings = true },
                onDrawRoi = { drawRoi = true })
        }
        if (task.message.isNotEmpty()) {
            Text(stackMessage(task.message), color = if (task.phase == StackTaskPhase.FAILED)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (task.busy) {
            Text(stackTaskPhase(task.phase), style = MaterialTheme.typography.titleSmall)
            if (task.total > 0) {
                LinearProgressIndicator(progress = (task.completed.toDouble() / task.total).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                Text("${task.completed} / ${task.total}")
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = { coordinator.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
        if (rendering && !task.busy) {
            Text(stringResource(R.string.stack_rendering))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = coordinator::cancelRendering, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
        if (coordinator.hasResult(mode)) {
            ProcessingControls(coordinator, parameters, onBackground = { editBackground = true })
            if (backgroundWarning.isNotEmpty() && backgroundWarning != "DISABLED") {
                Text(stringResource(R.string.stack_bg_warning, stackMessage(backgroundWarning)), color = MaterialTheme.colorScheme.tertiary)
            }
            Text(stringResource(R.string.stack_export_hint), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showDetails = !showDetails }) { Text(stringResource(if (showDetails) R.string.stack_hide_details else R.string.stack_show_details)) }
        if (showDetails) {
        if (task.exportedFiles.isNotEmpty()) task.exportedFiles.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.stack_limits, coordinator.memoryBudgetBytes / 1024 / 1024), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.stack_foreground), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.stack_precision), style = MaterialTheme.typography.bodySmall)
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(if (mode == StackMode.LIVE) R.string.stack_live else R.string.stack_planetary),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = coordinator::export, enabled = coordinator.hasResult(mode) && !task.busy) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.stack_export))
            }
        }
        if (mode == StackMode.LIVE) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (rendered == null || !showStack) stringResource(R.string.stack_eaa_single_hint)
                    else stringResource(R.string.stack_eaa_summary, live.accepted, live.exposureSeconds), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { observingOnly = !observingOnly }) {
                    Text(stringResource(if (observingOnly) R.string.stack_show_controls else R.string.stack_observe_large))
                }
            }
        }
        if (drawRoi) Text(stringResource(R.string.stack_drag_hint), Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.primary)
        val preview: @Composable (Modifier) -> Unit = { modifier ->
            StackImage(bitmap, parameters.background.targetMasks, drawRoi, modifier) { rectangle ->
                task.source?.let { source ->
                    val x = (rectangle.left * source.width).toInt().coerceIn(0, source.width - 1)
                    val y = (rectangle.top * source.height).toInt().coerceIn(0, source.height - 1)
                    drawnRoi = PixelRoi(x, y, (ceil(rectangle.right * source.width).toInt() - x).coerceAtLeast(1),
                        (ceil(rectangle.bottom * source.height).toInt() - y).coerceAtLeast(1))
                }
                drawRoi = false
            }
        }
        val compact = observingOnly && mode == StackMode.LIVE
        Layout(modifier = Modifier.weight(1f).fillMaxWidth(), content = {
            preview(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) { controls() }
            if (compact) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = coordinator::toggleLiveDisplay) { Text(stringResource(if (showStack) R.string.stack_single else R.string.stack_accumulated)) }
                    Text(stackLivePhase(live.phase), Modifier.weight(1f).align(Alignment.CenterVertically))
                    if (live.phase == LiveStackPhase.RUNNING) Button(onClick = { coordinator.live.pause() }) { Text(stringResource(R.string.stack_pause)) }
                    else if (live.phase == LiveStackPhase.PAUSED) Button(onClick = viewModel::startLiveStack) { Text(stringResource(R.string.stack_resume)) }
                }
            }
        }) { children, constraints ->
            val w = constraints.maxWidth; val h = constraints.maxHeight
            if (compact) {
                val bar = children[2].measure(Constraints(minWidth = w, maxWidth = w, maxHeight = h))
                val picture = children[0].measure(Constraints.fixed(w, (h-bar.height).coerceAtLeast(0)))
                // Keep the settings subtree alive without measuring/placing it over the sky.
                layout(w,h) { picture.placeRelative(0,0); bar.placeRelative(0,picture.height) }
            } else if (w >= 720.dp.roundToPx()) {
                val controlsWidth = 340.dp.roundToPx().coerceAtMost(w)
                val picture = children[0].measure(Constraints.fixed(w-controlsWidth,h))
                val panel = children[1].measure(Constraints.fixed(controlsWidth,h))
                layout(w,h) { picture.placeRelative(0,0); panel.placeRelative(picture.width,0) }
            } else {
                val pictureHeight = (h*.42f).roundToInt().coerceAtMost(300.dp.roundToPx()).coerceAtMost(h)
                val picture = children[0].measure(Constraints.fixed(w,pictureHeight))
                val panel = children[1].measure(Constraints.fixed(w,h-pictureHeight))
                layout(w,h) { picture.placeRelative(0,0); panel.placeRelative(0,pictureHeight) }
            }
        }
    }
}

@Composable
private fun LiveStackControls(vm: CameraViewModel, state: LiveStackState, showStack: Boolean, onCamera: () -> Unit) {
    val c = vm.stacking
    val calibration by c.calibration.state.collectAsState()
    val task by c.task.collectAsState()
    val algorithm by c.demosaicAlgorithm.collectAsState()
    val captureFormat by vm.liveStackPixelFormat.collectAsState()
    val rendering by c.rendering.collectAsState()
    var readiness by remember { mutableStateOf(vm.liveStackStartIssue()) }
    LaunchedEffect(vm) { while (true) { readiness = vm.liveStackStartIssue(); delay(500) } }
    val recording by vm.isRecording.collectAsState()
    val recordedFrames by vm.recordingFrameCount.collectAsState()
    var details by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.stack_eaa_intro), style = MaterialTheme.typography.bodySmall)
    if (state.phase == LiveStackPhase.EMPTY || state.phase == LiveStackPhase.PAUSED) {
        Text(readiness?.let { stackMessage(it) } ?: stringResource(R.string.stack_eaa_ready),
            color = if (readiness == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
        when (readiness) {
            "disable_auto_exposure" -> OutlinedButton(onClick = { vm.setAutoExposureMode(AutoExposureMode.OFF) }) { Text(stringResource(R.string.stack_disable_ae)) }
            "capture_paused" -> OutlinedButton(onClick = vm::resumeCaptureForStacking) { Text(stringResource(R.string.stack_resume_capture)) }
        }
    }
    TextButton(onClick = onCamera) { Text(stringResource(R.string.stack_camera_settings)) }
    if ((state.key?.format ?: captureFormat)?.isBayer == true) {
        DemosaicSelector(algorithm, enabled = state.phase == LiveStackPhase.EMPTY && !task.busy && !calibration.busy && !rendering,
            requiresResetConfirmation = c.hasResult(StackMode.PLANETARY) || task.histogram.isNotEmpty(), onSelect = c::setDemosaicAlgorithm)
        if (state.phase != LiveStackPhase.EMPTY)
            Text(stringResource(R.string.stack_debayer_live_locked), style = MaterialTheme.typography.bodySmall)
    }
    if (task.busy || c.hasResult(StackMode.PLANETARY)) {
        Text(stackMessage("finish_offline_first"), color = MaterialTheme.colorScheme.tertiary)
        TextButton(onClick = { c.open(StackMode.PLANETARY) }) { Text(stringResource(R.string.stack_planetary)) }
    }
    Text(stackLivePhase(state.phase), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.stack_eaa_summary, state.accepted, state.exposureSeconds), style = MaterialTheme.typography.titleMedium)
    if (recording) Text(stringResource(R.string.stack_recording_parallel, recordedFrames), color = MaterialTheme.colorScheme.primary)
    TextButton(onClick = { details = !details }) { Text(stringResource(R.string.stack_quality_details)) }
    if (details) {
    Text(stringResource(R.string.stack_live_stats, state.received, state.accepted, state.rejected, state.dropped,
        state.exposureSeconds, state.stars, state.rmsPixels, state.rotationDegrees), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.stack_perf, state.processingMs, state.javaPeakBytes / 1024 / 1024, state.nativePeakBytes / 1024 / 1024),
        style = MaterialTheme.typography.bodySmall)
    }
    if (state.reason.isNotEmpty()) Text(stackMessage(state.reason), color = MaterialTheme.colorScheme.tertiary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            LiveStackPhase.EMPTY -> Button(onClick = vm::startLiveStack, enabled = readiness == null && !calibration.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stack_start)) }
            LiveStackPhase.RUNNING -> Button(onClick = { c.live.pause() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stack_pause)) }
            LiveStackPhase.PAUSED -> Button(onClick = vm::startLiveStack, enabled = readiness == null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stack_resume)) }
            else -> Unit
        }
        if (state.phase != LiveStackPhase.EMPTY) OutlinedButton(onClick = c::resetLive, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stack_reset)) }
    }
    if (state.phase == LiveStackPhase.RESET_REQUIRED) Text(stringResource(R.string.stack_reset_required))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !showStack, onClick = { if (showStack) c.toggleLiveDisplay() }, label = { Text(stringResource(R.string.stack_single)) })
        FilterChip(selected = showStack, onClick = { if (!showStack) c.toggleLiveDisplay() }, label = { Text(stringResource(R.string.stack_accumulated)) })
    }
}

@Composable
private fun PlanetaryControls(c: StackingCoordinator, task: StackingTaskState, drawn: PixelRoi?,
    onChoose: () -> Unit, onBrowse: () -> Unit, onDrawRoi: () -> Unit) {
    val live by c.live.state.collectAsState()
    val algorithm by c.demosaicAlgorithm.collectAsState()
    val calibration by c.calibration.state.collectAsState()
    val rendering by c.rendering.collectAsState()
    val source = task.source
    var roiX by rememberSaveable(source?.identity) { mutableStateOf("0") }
    var roiY by rememberSaveable(source?.identity) { mutableStateOf("0") }
    var roiW by rememberSaveable(source?.identity) { mutableStateOf(source?.width?.toString().orEmpty()) }
    var roiH by rememberSaveable(source?.identity) { mutableStateOf(source?.height?.toString().orEmpty()) }
    var local by rememberSaveable { mutableStateOf(true) }
    var percent by rememberSaveable { mutableStateOf("25") }
    var count by rememberSaveable { mutableStateOf("") }
    var analyzedConfig by rememberSaveable { mutableStateOf("") }
    val currentConfig = "$roiX,$roiY,$roiW,$roiH,$local,${source?.identity},${c.endianConvention},${c.sampleAlignment},$algorithm"
    LaunchedEffect(drawn) { drawn?.let { roiX = it.left.toString(); roiY = it.top.toString(); roiW = it.width.toString(); roiH = it.height.toString() } }
    if (live.phase != LiveStackPhase.EMPTY) {
        Text(stackMessage("reset_live_first"), color = MaterialTheme.colorScheme.tertiary)
        TextButton(onClick = { c.open(StackMode.LIVE) }) { Text(stringResource(R.string.stack_eaa_title)) }
    }
    Button(onClick = onBrowse, enabled = !task.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stack_local_files)) }
    OutlinedButton(onClick = onChoose, enabled = !task.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stack_import_other)) }
    if (source == null) Text(stringResource(R.string.stack_library_hint), style = MaterialTheme.typography.bodySmall)
    Text(stackTaskPhase(task.phase), style = MaterialTheme.typography.titleSmall)
    if (source != null) {
        Text(stringResource(R.string.stack_source, source.file.name, source.width, source.height, source.bits, source.frames))
        if (source.isBayer) DemosaicSelector(algorithm,
            enabled = !task.busy && !calibration.busy && !rendering && live.phase == LiveStackPhase.EMPTY,
            requiresResetConfirmation = task.histogram.isNotEmpty() || c.hasResult(StackMode.PLANETARY), onSelect = c::setDemosaicAlgorithm)
        var decodeExpanded by remember { mutableStateOf(false) }
        TextButton(onClick = { decodeExpanded = !decodeExpanded }) { Text(stringResource(R.string.stack_endian)) }
        if (decodeExpanded) {
            Text(stringResource(R.string.stack_decode_hint), style = MaterialTheme.typography.bodySmall)
            SerEndianConvention.values().forEach { convention ->
                FilterChip(selected = c.endianConvention == convention, enabled = !task.busy,
                    onClick = { c.setDecodeOptions(convention, c.sampleAlignment) },
                    label = { Text(stringResource(if (convention == SerEndianConvention.COMPATIBILITY) R.string.stack_endian_compat else R.string.stack_endian_spec)) })
            }
            Text(stringResource(R.string.stack_alignment))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SampleAlignment.RIGHT, SampleAlignment.LEFT).forEach { alignment ->
                    FilterChip(selected = c.sampleAlignment == alignment, enabled = !task.busy,
                        onClick = { c.setDecodeOptions(c.endianConvention, alignment) },
                        label = { Text(stringResource(when (alignment) { SampleAlignment.AUTO -> R.string.stack_auto; SampleAlignment.RIGHT -> R.string.stack_right; SampleAlignment.LEFT -> R.string.stack_left })) })
                }
            }
        }
        Text(stringResource(R.string.stack_roi), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(roiX, { roiX = it }, R.string.stack_roi_x, Modifier.weight(1f), !task.busy)
            NumberField(roiY, { roiY = it }, R.string.stack_roi_y, Modifier.weight(1f), !task.busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(roiW, { roiW = it }, R.string.stack_roi_w, Modifier.weight(1f), !task.busy)
            NumberField(roiH, { roiH = it }, R.string.stack_roi_h, Modifier.weight(1f), !task.busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { roiX = "0"; roiY = "0"; roiW = source.width.toString(); roiH = source.height.toString() }, enabled = !task.busy) { Text(stringResource(R.string.stack_full_frame)) }
            TextButton(onClick = onDrawRoi, enabled = !task.busy) { Text(stringResource(R.string.stack_draw_roi)) }
        }
        SwitchLine(R.string.stack_local_alignment, local, !task.busy) { local = it }
        Button(onClick = {
            val roi = runCatching { PixelRoi(roiX.toInt(), roiY.toInt(), roiW.toInt(), roiH.toInt()).checked(source.width, source.height) }.getOrNull()
            if (roi == null) c.report("invalid_roi") else { analyzedConfig = currentConfig; c.analyze(roi, local) }
        }, enabled = !task.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stack_analyze)) }
        if (task.histogram.isNotEmpty()) {
            Text(stringResource(R.string.stack_quality, task.qualityFrames, task.referenceFrame + 1))
            QualityHistogram(task.histogram)
            Text(stringResource(R.string.stack_keep), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 25, 50).forEach { value -> FilterChip(selected = percent == "$value" && count.isBlank(),
                    onClick = { percent = "$value"; count = "" }, label = { Text("$value%") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(percent, { percent = it }, R.string.stack_percent, Modifier.weight(1f), !task.busy)
                NumberField(count, { count = it }, R.string.stack_count, Modifier.weight(1f), !task.busy)
            }
            Text(stringResource(R.string.stack_selection_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val p = percent.toDoubleOrNull()
                val n = if (count.isBlank()) null else count.toLongOrNull()
                if (p == null || !p.isFinite() || p <= 0 || p > 100 || (count.isNotBlank() && (n == null || n <= 0))) c.report("invalid_selection")
                else c.stack(PlanetarySelection(p, n))
            }, enabled = !task.busy && analyzedConfig == currentConfig, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stack_align)) }
        }
        task.stats?.let { stats ->
            Text(stringResource(R.string.stack_offline_stats, stats.selectedFrames, stats.stackedFrames, stats.alignmentPoints,
                stats.meanCorrelation, stats.elapsedMillis, stats.estimatedPeakBytes / 1024 / 1024))
            val modes = stats.modeCounts.entries.map { entry ->
                stringResource(when (entry.key) {
                    PlanetaryRegistrationMode.GLOBAL -> R.string.stack_mode_global
                    PlanetaryRegistrationMode.MULTIPOINT -> R.string.stack_mode_local
                    PlanetaryRegistrationMode.GLOBAL_LOW_TEXTURE, PlanetaryRegistrationMode.GLOBAL_LOCAL_FALLBACK -> R.string.stack_mode_fallback
                }) + ": ${entry.value}"
            }.joinToString()
            val rejected = stats.rejectionCounts.entries.map { stackMessage(it.key.name) + ": ${it.value}" }.joinToString()
            Text(stringResource(R.string.stack_modes, modes), style = MaterialTheme.typography.bodySmall)
            if (rejected.isNotEmpty()) Text(stringResource(R.string.stack_rejections, rejected), style = MaterialTheme.typography.bodySmall)
            if (stats.framesWithUnknownExposure > 0) Text(stringResource(R.string.stack_unknown_exposure, stats.framesWithUnknownExposure), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { c.clearPlanetary() }, enabled = !task.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stack_clear_offline)) }
    }
}

@Composable
private fun ProcessingControls(c: StackingCoordinator, p: StackProcessingParameters, onBackground: () -> Unit) {
    val task by c.task.collectAsState()
    var advanced by rememberSaveable { mutableStateOf(false) }
    Divider()
    Text(stringResource(R.string.stack_compare), style = MaterialTheme.typography.titleMedium)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProcessingPreset.values().forEach { preset -> FilterChip(selected = p.preset == preset,
            onClick = { c.selectPreset(preset) }, label = { Text(stringResource(when (preset) {
                ProcessingPreset.RAW -> R.string.stack_raw; ProcessingPreset.NATURAL -> R.string.stack_natural; ProcessingPreset.INTENSE -> R.string.stack_intense
            })) }) }
    }
    OutlinedButton(onClick = c::autoStretch, enabled = !task.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.stack_auto_stretch))
    }
    Text(stringResource(R.string.stack_auto_stretch_hint), style = MaterialTheme.typography.bodySmall)
    if (p.preset != ProcessingPreset.RAW && p.stretchEnabled)
        StackSlider(R.string.stack_brightness, p.brightness, .05..8.0) { c.updateProcessing(p.copy(brightness = it)) }
    if (p.preset != ProcessingPreset.RAW) OutlinedButton(onClick = onBackground, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.stack_bg_entry, backgroundModeLabel(p.background)))
    }
    Text(stringResource(R.string.stack_processing_reset_note), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { advanced = !advanced }) { Text(stringResource(if (advanced) R.string.stack_hide_advanced else R.string.stack_show_advanced)) }
    if (advanced && p.preset != ProcessingPreset.RAW) {
        SwitchLine(R.string.stack_stretch_enabled, p.stretchEnabled) { c.updateProcessing(p.copy(stretchEnabled = it)) }
        if (p.stretchEnabled) {
        StackSlider(R.string.stack_black, p.blackPoint, 0.0..min(.99, p.whitePoint - .001)) { c.updateProcessing(p.copy(blackPoint = it)) }
        StackSlider(R.string.stack_white, p.whitePoint, max(.001, p.blackPoint + .001)..1.0) { c.updateProcessing(p.copy(whitePoint = it)) }
        StackSlider(R.string.stack_midtone, p.midtone, .01.. .99) { c.updateProcessing(p.copy(midtone = it)) }
        StackSlider(R.string.stack_curve, p.curve, 0.0..200.0) { c.updateProcessing(p.copy(curve = it)) }
        StackSlider(R.string.stack_highlights, p.highlightCompression, 0.0..1.0) { c.updateProcessing(p.copy(highlightCompression = it)) }
        }
        SwitchLine(R.string.stack_contrast, p.localContrast > 0) { c.updateProcessing(p.copy(localContrast = if (it) .15 else 0.0)) }
        if (p.localContrast > 0) StackSlider(R.string.stack_contrast, p.localContrast, 0.0..1.0) { c.updateProcessing(p.copy(localContrast = it)) }
        SwitchLine(R.string.stack_sharpen, p.sharpening > 0) { c.updateProcessing(p.copy(sharpening = if (it) .5 else 0.0)) }
        if (p.sharpening > 0) StackSlider(R.string.stack_sharpen, p.sharpening, 0.0..2.0) { c.updateProcessing(p.copy(sharpening = it)) }
        SwitchLine(R.string.stack_wb_enabled, p.whiteBalanceEnabled) { c.updateProcessing(p.copy(whiteBalanceEnabled = it)) }
        if (p.whiteBalanceEnabled) {
        SwitchLine(R.string.stack_auto_wb, p.autoWhiteBalance) { c.updateProcessing(p.copy(autoWhiteBalance = it)) }
        StackSlider(R.string.stack_red, p.redBalance, .25..4.0) { c.updateProcessing(p.copy(redBalance = it)) }
        StackSlider(R.string.stack_blue, p.blueBalance, .25..4.0) { c.updateProcessing(p.copy(blueBalance = it)) }
        }
        SwitchLine(R.string.stack_saturation_enabled, p.saturationEnabled) { c.updateProcessing(p.copy(saturationEnabled = it)) }
        if (p.saturationEnabled) StackSlider(R.string.stack_saturation, p.saturation, 0.0..2.0) { c.updateProcessing(p.copy(saturation = it)) }
    }
    OutlinedButton(onClick = c::resetProcessing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stack_reset_recipe)) }
}

@Composable
internal fun AppRecordingPicker(vm: CameraViewModel, onDismiss: () -> Unit,
    onSelect: (File) -> Unit, onCamera: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val recording by vm.isRecording.collectAsState()
    val recordings = vm.getRecordingsDir()
    val captures = vm.getCapturesDir()
    var loading by remember { mutableStateOf(true) }
    val files by produceState(emptyList<VideoFileInfo>(), refresh, recording) {
        loading = true
        value = withContext(Dispatchers.IO) { MediaLibrary.list(recordings, captures, MediaCategory.SER_PSER, vm.getActiveRecordingFile()) }
        loading = false
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stack_local_files)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.stack_library_hint))
                if (recording) Text(stringResource(R.string.stack_recording_excluded), color = MaterialTheme.colorScheme.tertiary)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (files.isEmpty()) {
                    Text(stringResource(R.string.stack_no_recordings))
                    OutlinedButton(onClick = { vm.setRecordFormat("SER"); onCamera() }, enabled = !recording) {
                        Text(stringResource(R.string.stack_prepare_ser))
                    }
                } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(files, key = { it.file.absolutePath }) { entry ->
                        OutlinedButton(onClick = { onSelect(entry.file) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${entry.format} · ${"%.1f".format(entry.size / 1024.0 / 1024.0)} MiB", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.stack_refresh_library)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Keep the camera controls honest when their preview is showing a retained EAA stack. */
@Composable
internal fun EaaSessionBar(vm: CameraViewModel) {
    val state by vm.stacking.live.state.collectAsState()
    val showStack by vm.stacking.showLiveStack.collectAsState()
    if (state.phase == LiveStackPhase.EMPTY || state.phase == LiveStackPhase.CLOSED) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.stacking.open(StackMode.LIVE) }) { Text("EAA · ${stackLivePhase(state.phase)}") }
            Text(stringResource(R.string.stack_eaa_summary, state.accepted, state.exposureSeconds),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm.stacking::toggleLiveDisplay) {
                Text(stringResource(if (showStack) R.string.stack_switch_single else R.string.stack_switch_accumulated))
            }
        }
    }
}

@Composable
internal fun StackSlider(label: Int, value: Double, range: ClosedFloatingPointRange<Double>, change: (Double) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            Text("%.3f".format(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { change(it.toDouble()) }, valueRange = range.start.toFloat()..range.endInclusive.toFloat())
    }
}

@Composable
private fun SwitchLine(label: Int, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f)); Switch(checked, change, enabled = enabled)
    }
}

@Composable
private fun NumberField(value: String, change: (String) -> Unit, label: Int, modifier: Modifier, enabled: Boolean) {
    OutlinedTextField(value, change, modifier, label = { Text(stringResource(label)) }, singleLine = true,
        enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun QualityHistogram(values: List<Long>) {
    val color = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.stack_keep)
    Canvas(Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = description }) {
        val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val bin = size.width / values.size.coerceAtLeast(1)
        values.forEachIndexed { index, count ->
            val h = (count.toDouble() / max * size.height).toFloat()
            drawRect(color, Offset(index * bin, size.height - h), Size((bin - 1).coerceAtLeast(1f), h))
        }
    }
}

@Composable
internal fun StackImage(bitmap: Bitmap?, masks: List<TargetMask>, selecting: Boolean, modifier: Modifier,
    points: List<BackgroundSamplePoint> = emptyList(), sampleRadius: Double = .025,
    candidatePoint: BackgroundSamplePoint? = null,
    onPoint: ((BackgroundSamplePoint) -> Unit)? = null, selected: (TargetMask) -> Unit) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val maskColor = MaterialTheme.colorScheme.tertiary
    val candidateColor = MaterialTheme.colorScheme.primary
    val latestPoint by rememberUpdatedState(onPoint)
    val latestSelected by rememberUpdatedState(selected)
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (bitmap == null) Text(stringResource(R.string.stack_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Image(bitmap.asImageBitmap(), stringResource(R.string.stack_accumulated), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize().testTag("stack-sample-surface").pointerInput(bitmap.width, bitmap.height, selecting, onPoint != null) {
                fun normalized(point: Offset): Offset {
                    val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                    val w = bitmap.width * scale; val h = bitmap.height * scale
                    return Offset(((point.x - (size.width - w) / 2) / w).coerceIn(0f, 1f),
                        ((point.y - (size.height - h) / 2) / h).coerceIn(0f, 1f))
                }
                if (selecting) detectDragGestures(onDragStart = { dragStart = normalized(it); dragEnd = dragStart },
                    onDragCancel = { dragStart = null; dragEnd = null }, onDragEnd = {
                        val a = dragStart; val b = dragEnd
                        if (a != null && b != null && abs(a.x - b.x) > .005 && abs(a.y - b.y) > .005)
                            latestSelected(TargetMask(min(a.x, b.x).toDouble(), min(a.y, b.y).toDouble(), max(a.x, b.x).toDouble(), max(a.y, b.y).toDouble()))
                        dragStart = null; dragEnd = null
                    }) { change, _ -> change.consume(); dragEnd = normalized(change.position) }
                else if (onPoint != null) detectTapGestures { position ->
                    val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                    val w = bitmap.width * scale; val h = bitmap.height * scale
                    val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
                    // Letterboxing is not a sample location.
                    if (position.x in ox..(ox+w) && position.y in oy..(oy+h)) {
                        val p = normalized(position)
                        latestPoint?.invoke(BackgroundSamplePoint(p.x.toDouble(), p.y.toDouble()))
                    }
                }
            }) {
                val scale = min(size.width / bitmap.width, size.height / bitmap.height)
                val w = bitmap.width * scale; val h = bitmap.height * scale
                val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
                fun rectangle(mask: TargetMask) = drawRect(maskColor, Offset(ox + (mask.left * w).toFloat(), oy + (mask.top * h).toFloat()),
                    Size(((mask.right - mask.left) * w).toFloat(), ((mask.bottom - mask.top) * h).toFloat()), style = Stroke(2.dp.toPx()))
                masks.forEach { rectangle(it) }
                points.forEach { point ->
                    val center = Offset(ox + (point.x * w).toFloat(), oy + (point.y * h).toFloat())
                    drawCircle(maskColor, (sampleRadius * min(w, h)).toFloat(), center, style = Stroke(2.dp.toPx()))
                    drawCircle(maskColor, 2.dp.toPx(), center)
                }
                candidatePoint?.let { point ->
                    val center = Offset(ox + (point.x * w).toFloat(), oy + (point.y * h).toFloat())
                    drawCircle(candidateColor, (sampleRadius * min(w, h)).toFloat(), center, style = Stroke(2.dp.toPx()))
                }
                val a = dragStart; val b = dragEnd
                if (a != null && b != null) rectangle(TargetMask(min(a.x, b.x).toDouble(), min(a.y, b.y).toDouble(), max(a.x, b.x).toDouble(), max(a.y, b.y).toDouble()))
            }
        }
    }
}

@Composable
private fun stackLivePhase(phase: LiveStackPhase) = stringResource(when (phase) {
    LiveStackPhase.EMPTY -> R.string.stack_phase_empty; LiveStackPhase.RUNNING -> R.string.stack_phase_running
    LiveStackPhase.PAUSING -> R.string.stack_phase_pausing; LiveStackPhase.PAUSED -> R.string.stack_phase_paused
    LiveStackPhase.RESET_REQUIRED -> R.string.stack_phase_reset_required; LiveStackPhase.FAILED -> R.string.stack_phase_failed
    LiveStackPhase.CLOSED -> R.string.stack_phase_closed
})

@Composable
private fun stackTaskPhase(phase: StackTaskPhase) = stringResource(when (phase) {
    StackTaskPhase.EMPTY -> R.string.stack_phase_empty; StackTaskPhase.READING -> R.string.stack_phase_reading
    StackTaskPhase.READY -> R.string.stack_phase_ready; StackTaskPhase.ANALYZING -> R.string.stack_phase_analyzing
    StackTaskPhase.ANALYZED -> R.string.stack_phase_analyzed; StackTaskPhase.REGISTERING -> R.string.stack_phase_registering
    StackTaskPhase.STACKING -> R.string.stack_phase_stacking; StackTaskPhase.PROCESSING -> R.string.stack_phase_processing
    StackTaskPhase.EXPORTING -> R.string.stack_phase_exporting; StackTaskPhase.COMPLETE -> R.string.stack_phase_complete
    StackTaskPhase.CANCELLED -> R.string.stack_phase_cancelled; StackTaskPhase.FAILED -> R.string.stack_phase_failed
})

@Composable
internal fun stackMessage(code: String): String {
    val resource = when (code.substringBefore(':')) {
        "calibration_busy" -> R.string.stack_cal_working
        "calibration_cancelled" -> R.string.stack_phase_cancelled
        "calibration_reset_required" -> R.string.stack_cal_locked
        "calibration_unknown_settings" -> R.string.stack_cal_unknown
        "calibration_settings_mismatch", "calibration_geometry_mismatch", "calibration_type_mismatch" -> R.string.stack_cal_mismatch
        "calibration_clipped_flat", "calibration_invalid_flat_response" -> R.string.stack_cal_invalid_flat
        "calibration_ser_required", "calibration_source_format" -> R.string.stack_cal_ser_required
        "calibration_capture_changed" -> R.string.stack_cal_changed
        "calibration_changed_analyze_again", "demosaic_changed_analyze_again" -> R.string.stack_reason_analyze
        "demosaic_busy" -> R.string.stack_debayer_busy
        "demosaic_changed" -> R.string.stack_debayer_changed
        "calibration_selection_unavailable", "calibration_master_missing", "calibration_read_failed",
        "calibration_corrupt_master", "calibration_truncated_master", "calibration_invalid_master" -> R.string.stack_cal_unavailable
        "calibration_master_created" -> R.string.stack_cal_created
        "calibration_master_in_use" -> R.string.stack_cal_in_use
        "memory_budget", "input_budget", "MEMORY_BUDGET" -> R.string.stack_reason_memory
        "settings_changed", "GEOMETRY" -> R.string.stack_reason_settings
        "camera_disconnected" -> R.string.stack_reason_disconnect
        "TOO_FEW_STARS" -> R.string.stack_reason_stars
        "TRAILING" -> R.string.stack_reason_trailing; "CLOUD" -> R.string.stack_reason_cloud
        "REGISTRATION", "GLOBAL_MATCH" -> R.string.stack_reason_registration
        "background_paused" -> R.string.stack_reason_background; "background_cancelled" -> R.string.stack_reason_background_cancel
        "connect_camera" -> R.string.stack_reason_camera; "disable_auto_exposure" -> R.string.stack_reason_ae
        "preview_only_camera" -> R.string.stack_reason_preview; "software_exposure_unknown" -> R.string.stack_reason_software
        "unknown_science_layout" -> R.string.stack_reason_layout
        "bayer_hardware_bin" -> R.string.stack_reason_binning; "settings_busy", "filter_moving" -> R.string.stack_reason_busy
        "wait_frame" -> R.string.stack_reason_wait; "reset_live_first" -> R.string.stack_reason_reset_live
        "capture_paused" -> R.string.stack_capture_paused
        "recording_active" -> R.string.stack_recording_excluded
        "auto_stretch_unavailable" -> R.string.stack_auto_stretch_unavailable
        "finish_offline_first" -> R.string.stack_reason_reset_offline; "storage_full", "storage_unavailable" -> R.string.stack_reason_storage
        "source_changed" -> R.string.stack_reason_source; "choose_source" -> R.string.stack_reason_choose
        "analyze_first" -> R.string.stack_reason_analyze; "invalid_roi" -> R.string.stack_reason_roi
        "invalid_selection" -> R.string.stack_reason_selection; "no_result" -> R.string.stack_reason_no_result
        "export_success" -> R.string.stack_reason_export; "cancelled" -> R.string.stack_phase_cancelled
        "LOW_SIGNAL" -> R.string.stack_quality_bad; "SATURATION" -> R.string.stack_quality_saturated
        "OUT_OF_FRAME" -> R.string.stack_quality_out; "NOISE" -> R.string.stack_quality_noise
        "LOCAL_MATCH" -> R.string.stack_quality_local; "FOLDED_WARP" -> R.string.stack_quality_fold
        "EDGE_MISMATCH" -> R.string.stack_quality_edge
        "INSUFFICIENT_UNMASKED_BACKGROUND" -> R.string.stack_bg_insufficient
        "INVALID_BACKGROUND_POINTS", "DUPLICATE_BACKGROUND_POINTS" -> R.string.stack_dbe_invalid
        "INSUFFICIENT_BACKGROUND_POINTS" -> R.string.stack_dbe_need_points
        "EXTENDED_SIGNAL_OR_POOR_BACKGROUND_COVERAGE", "UNBALANCED_BACKGROUND_COVERAGE" -> R.string.stack_bg_extended
        "SINGULAR_BACKGROUND_MODEL", "IRREGULAR_BACKGROUND_MODEL", "UNSAFE_MODEL_EXTRAPOLATION" -> R.string.stack_bg_unreliable
        "CORRECTION_WOULD_CRUSH_SHADOWS" -> R.string.stack_bg_shadows
        else -> null
    }
    return if (resource != null) stringResource(resource) else stringResource(R.string.stack_reject_detail, code)
}
