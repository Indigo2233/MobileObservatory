@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.indigo.mobileobservatory.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.sequence.SequenceEditorMode
import com.indigo.mobileobservatory.sequence.SequencePhase
import com.indigo.mobileobservatory.sequence.SimpleExposureRow
import com.indigo.mobileobservatory.sequence.catalog.SequenceHardwareSnapshot
import com.indigo.mobileobservatory.sequence.catalog.SequenceIssue
import com.indigo.mobileobservatory.sequence.catalog.validateSequence
import com.indigo.mobileobservatory.sequence.plannedFrames
import com.indigo.mobileobservatory.sequence.targetAltitudeCurve
import com.indigo.mobileobservatory.sequence.toNinaSequence
import com.indigo.mobileobservatory.sequence.tonightWindow
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SequenceProgressStrip(
    title: String,
    detail: String,
    paused: Boolean,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = onOpen) { Text("$title  $detail") }
        if (paused) TextButton(onClick = onResume) { Text(stringResource(R.string.resume)) }
        else TextButton(onClick = onPause) { Text(stringResource(R.string.pause)) }
        TextButton(onClick = onStop) { Text(stringResource(R.string.sequence_stop)) }
    }
}

@Composable
fun SequenceScreen(
    viewModel: CameraViewModel,
    redNightMode: Boolean,
    onOpenGuiding: () -> Unit,
    onOpenAccessories: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = viewModel.sequenceRuntime
    val state by runtime.state.collectAsState()
    val mode by runtime.mode.collectAsState()
    val draft by runtime.draft.collectAsState()
    val document by runtime.document.collectAsState()
    val frames by runtime.frames.collectAsState()
    val autofocus by runtime.autofocus.collectAsState()
    val sensorTemp by viewModel.sensorTempTenths.collectAsState()
    val coolerOn by viewModel.coolerOn.collectAsState()
    val coordinates by viewModel.mountCoordinates.collectAsState()
    val site by viewModel.mountSite.collectAsState()
    val tracking by viewModel.mountTrackingEnabled.collectAsState()
    val guideRms by viewModel.guideTotalRmsPx.collectAsState()
    val guideRunning by viewModel.guideRunning.collectAsState()
    val filterNames by viewModel.filterWheelSlotNames.collectAsState()
    val skyTarget by viewModel.sequenceSkyPick.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val mountConnection by viewModel.mountConnectionState.collectAsState()
    val eafConnected by viewModel.eafConnected.collectAsState()
    val coverConnected by viewModel.coverConnected.collectAsState()
    val calibratorMaxBrightness by viewModel.calibratorMaxBrightness.collectAsState()
    val heaterSupported by viewModel.heaterSupported.collectAsState()
    val heaterMaxLevel by viewModel.heaterMaxLevel.collectAsState()
    val rotatorConnected by viewModel.rotatorConnected.collectAsState()
    val sequenceSettings by viewModel.sequenceSettings.collectAsState()
    val guideConnection by viewModel.guideConnectionState.collectAsState()
    val coolingInfo by viewModel.coolingInfo.collectAsState()
    val usbBandwidthRange by viewModel.usbBandwidthRange.collectAsState()
    val virtualStatus by viewModel.virtualSequenceStatus.collectAsState()
    var startIssues by remember { mutableStateOf<List<SequenceIssue>?>(null) }
    var page by rememberSaveable { mutableStateOf("edit") }
    var templateName by rememberSaveable { mutableStateOf(draft.title) }
    var fileMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val running = state.phase == SequencePhase.Running || state.phase == SequencePhase.Paused
    val chartColor = if (redNightMode) Color(0xFFE53935) else MaterialTheme.colorScheme.primary
    val realHardware = SequenceHardwareSnapshot(
        cameraConnected = connection is ConnectionState.Connected,
        coolingCapable = coolingInfo != null,
        usbBandwidthCapable = usbBandwidthRange != null,
        dewHeater = heaterSupported && heaterMaxLevel > 0,
        filterWheelConnected = filterNames.any { it.isNotBlank() },
        focuserConnected = eafConnected,
        guiderConnected = guideConnection is ConnectionState.Connected,
        mountConnected = mountConnection is MountConnectionState.Connected,
        coverConnected = coverConnected,
        rotatorConnected = rotatorConnected,
        flatPanelConnected = coverConnected && calibratorMaxBrightness > 0,
        filterNames = filterNames.filter { it.isNotBlank() }
    )
    val hardware = viewModel.virtualSequenceHardwareSnapshot ?: realHardware
    val displayCoordinates = virtualStatus?.let { MountCoordinates(it.raHours, it.decDeg) } ?: coordinates
    val displayTracking = virtualStatus?.tracking ?: tracking
    val displaySensorTemp = virtualStatus?.sensorTemperatureTenths ?: sensorTemp
    val displayCoolerOn = virtualStatus?.coolerOn ?: coolerOn
    val displayGuideRms = virtualStatus?.guideRmsPx ?: guideRms
    val displayGuideRunning = virtualStatus?.guiding ?: guideRunning
    val displayFilterNames = viewModel.virtualSequenceHardwareSnapshot?.filterNames ?: filterNames
    val plannedFrameCount = if (mode == SequenceEditorMode.Simple) {
        draft.plannedFrames()
    } else {
        document?.plannedFrames() ?: 0
    }
    val fileErrorText = stringResource(R.string.sequence_file_error)
    val fileImportedText = stringResource(R.string.sequence_file_imported)
    val fileExportedText = stringResource(R.string.sequence_file_exported)
    val fileSharedText = stringResource(R.string.sequence_file_shared)
    val shareTitle = stringResource(R.string.sequence_share_file)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val name = withContext(Dispatchers.IO) { sequenceDisplayName(context, uri) ?: uri.lastPathSegment }
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } ?: error("open")
                }
                runtime.importJson(name, json)
                templateName = name?.substringBeforeLast('.', missingDelimiterValue = name)
                    ?.takeIf { it.isNotBlank() }
                    ?: runtime.suggestedFileName().substringBeforeLast('.')
                page = "edit"
                name ?: runtime.suggestedFileName()
            }
            fileMessage = result.fold(
                onSuccess = { fileImportedText.format(it) },
                onFailure = { fileErrorText.format(it.message.orEmpty()) }
            )
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("open")
                }
            }
            fileMessage = result.fold(
                onSuccess = { fileExportedText },
                onFailure = { fileErrorText.format(it.message.orEmpty()) }
            )
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == SequencePhase.Running || state.phase == SequencePhase.Paused) page = "status"
    }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (virtualStatus != null) {
            Text(
                stringResource(R.string.sequence_virtual_devices_banner),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = page == "edit", onClick = { page = "edit" }, label = { Text(stringResource(R.string.sequence_edit)) })
            FilterChip(selected = page == "status", onClick = { page = "status" }, label = { Text(stringResource(R.string.sequence_status)) })
        }
        if (page == "edit") {
            val runningReadonly = running
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (runningReadonly) {
                    Text(stringResource(R.string.sequence_running_readonly))
                    TextButton(onClick = { page = "status" }) { Text(stringResource(R.string.sequence_open_status)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == SequenceEditorMode.Simple,
                        onClick = { if (!running) runtime.setMode(SequenceEditorMode.Simple) },
                        label = { Text(stringResource(R.string.sequence_simple)) }
                    )
                    FilterChip(
                        selected = mode == SequenceEditorMode.Advanced,
                        onClick = { if (!running) runtime.setMode(SequenceEditorMode.Advanced) },
                        label = { Text(stringResource(R.string.sequence_advanced)) }
                    )
                }
                SequenceFileActions(
                    enabled = !running,
                    message = fileMessage,
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "*/*")) },
                    onExport = {
                        pendingExportJson = runtime.exportJson()
                        exportLauncher.launch(runtime.suggestedFileName())
                    },
                    onShare = {
                        scope.launch {
                            val result = runCatching {
                                val file = withContext(Dispatchers.IO) { runtime.shareFile() }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, shareTitle))
                            }
                            fileMessage = result.fold(
                                onSuccess = { fileSharedText },
                                onFailure = { fileErrorText.format(it.message.orEmpty()) }
                            )
                        }
                    }
                )
                if (mode == SequenceEditorMode.Simple) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = templateName,
                            onValueChange = { if (!running) templateName = it },
                            label = { Text(stringResource(R.string.sequence_template)) },
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { runtime.save(templateName.ifBlank { draft.title }) }, enabled = !running) {
                                Text(stringResource(R.string.sequence_save))
                            }
                            Button(onClick = {
                                val issues = validateSequence(draft.toNinaSequence(), hardware)
                                if (issues.isNotEmpty()) startIssues = issues else runtime.start(sequenceSettings)
                            }, enabled = !running) {
                                Text(stringResource(R.string.sequence_start))
                            }
                        }
                        SimpleEditor(
                            draft = draft,
                            enabled = !running,
                            rotatorConnected = hardware.rotatorConnected,
                            skyTarget = skyTarget,
                            filterNames = hardware.filterNames
                        ) { runtime.updateDraft(it) }
                        Button(
                            onClick = { runtime.importSimpleDraft(); templateName = draft.title },
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.sequence_convert_advanced)) }
                    }
                } else {
                    SequenceAdvancedEditor(
                        runtime = runtime,
                        enabled = !running,
                        hardware = hardware,
                        latitudeDeg = site?.latitudeDeg,
                        longitudeDeg = site?.longitudeDeg,
                        chartColor = chartColor,
                        skyTarget = skyTarget,
                        pointingRaHours = displayCoordinates?.raHours,
                        pointingDecDeg = displayCoordinates?.decDeg,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val root = runtime.document.value
                            val issues = root?.let { validateSequence(it, hardware) }.orEmpty()
                            if (issues.isNotEmpty()) startIssues = issues else runtime.start(sequenceSettings)
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.sequence_start))
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${state.phase}  ${state.currentClassName.orEmpty()}  ${state.message.orEmpty()}")
                Text(stringResource(R.string.sequence_progress, state.framesDone, plannedFrameCount))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.phase == SequencePhase.Paused) {
                            Button(onClick = runtime::resume, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.resume))
                            }
                        } else {
                            Button(
                                onClick = runtime::pause,
                                enabled = state.phase == SequencePhase.Running,
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.pause)) }
                        }
                        Button(onClick = runtime::stop, enabled = running, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sequence_stop))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = runtime::skip, enabled = running, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sequence_skip))
                        }
                        Button(onClick = runtime::skipToEnd, enabled = running, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sequence_skip_to_end))
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.sequence_history))
                        frames.forEach { frame ->
                            Text("${frame.name}  ${frame.filter.orEmpty()}  ${frame.exposureSeconds}s  HFR ${frame.hfr ?: "-"}  ${frame.starCount ?: "-"}")
                        }
                        MetricChart(frames.map { it.hfr }, chartColor, Modifier.fillMaxWidth().height(120.dp))
                    }
                }
                virtualStatus?.let { status ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.sequence_virtual_device_status))
                            Text(stringResource(
                                R.string.sequence_virtual_accessory_status,
                                if (status.dewHeaterOn) 1 else 0,
                                status.usbLimit
                            ))
                            Text(stringResource(
                                R.string.sequence_virtual_flat_status,
                                if (status.flatLightOn) 1 else 0,
                                status.flatBrightness
                            ))
                            Text(stringResource(
                                R.string.sequence_virtual_duration_status,
                                status.lastCoolingDurationMinutes,
                                status.lastWarmingDurationMinutes
                            ))
                            Text(stringResource(
                                R.string.sequence_virtual_guiding_status,
                                if (status.guideForceCalibration) 1 else 0
                            ))
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.sequence_mount_attitude))
                        Text("RA ${displayCoordinates?.formatRa().orEmpty()}  Dec ${displayCoordinates?.formatDec().orEmpty()}")
                        Text(stringResource(R.string.sequence_tracking, if (displayTracking) 1 else 0))
                        Text(stringResource(R.string.sequence_camera_temp, displaySensorTemp / 10.0, if (displayCoolerOn) 1 else 0))
                        Text(stringResource(R.string.sequence_guide_rms, displayGuideRms, if (displayGuideRunning) 1 else 0))
                        Text(displayFilterNames.joinToString())
                        Row {
                            TextButton(onClick = onOpenGuiding) { Text(stringResource(R.string.guiding)) }
                            TextButton(onClick = onOpenAccessories) { Text(stringResource(R.string.tab_accessories)) }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.sequence_target_altitude))
                        val curve = if (site != null) {
                            val window = tonightWindow(Instant.now())
                            targetAltitudeCurve(
                                raHours = draft.raHours,
                                decDeg = draft.decDegrees,
                                latitudeDeg = site!!.latitudeDeg,
                                longitudeDeg = site!!.longitudeDeg,
                                from = window.first,
                                until = window.second
                            ).map { it.altitudeDeg }
                        } else {
                            emptyList()
                        }
                        MetricChart(curve, chartColor, Modifier.fillMaxWidth().height(120.dp))
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.sequence_autofocus_history))
                        autofocus.forEach { run ->
                            Text("${run.position}  HFR ${run.hfr}  ${run.filter.orEmpty()}  ${run.temperatureC ?: "-"}")
                            MetricChart(run.curve.map { it.second }, chartColor, Modifier.fillMaxWidth().height(80.dp))
                        }
                    }
                }
            }
        }
    }
    val pendingIssues = startIssues
    if (pendingIssues != null) {
        AlertDialog(
            onDismissRequest = { startIssues = null },
            title = { Text(stringResource(R.string.sequence_preflight_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    pendingIssues.take(12).forEach { issue ->
                        Text("· ${issue.messageZh}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { startIssues = null; runtime.start(sequenceSettings) }) {
                    Text(stringResource(R.string.sequence_preflight_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { startIssues = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SequenceFileActions(
    enabled: Boolean,
    message: String?,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.sequence_import_file))
            }
            OutlinedButton(onClick = onExport, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.sequence_export_file))
            }
            OutlinedButton(onClick = onShare, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.sequence_share_file))
            }
        }
        if (!message.isNullOrBlank()) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun sequenceDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } else {
            null
        }
    }

@Composable
private fun SimpleEditor(
    draft: com.indigo.mobileobservatory.sequence.SimpleSequenceDraft,
    enabled: Boolean,
    rotatorConnected: Boolean,
    skyTarget: com.indigo.mobileobservatory.sequence.SequenceSkyTarget? = null,
    filterNames: List<String> = emptyList(),
    onChange: (com.indigo.mobileobservatory.sequence.SimpleSequenceDraft) -> Unit
) {
    OutlinedTextField(
        value = draft.title,
        onValueChange = { onChange(draft.copy(title = it)) },
        enabled = enabled,
        label = { Text(stringResource(R.string.sequence_target_name)) },
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(
        onClick = {
            skyTarget?.let { sky ->
                onChange(
                    draft.copy(
                        title = sky.name,
                        raHours = sky.raHours,
                        decDegrees = sky.decDeg,
                        positionAngleDeg = sky.positionAngleDeg
                    )
                )
            }
        },
        enabled = enabled && skyTarget != null
    ) {
        Text(
            if (skyTarget == null) {
                stringResource(R.string.sequence_from_star_map_empty)
            } else {
                stringResource(R.string.sequence_from_star_map, skyTarget.name)
            }
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DecimalInputField(
            value = draft.raHours,
            onValueChange = { onChange(draft.copy(raHours = it)) },
            enabled = enabled,
            label = { Text("RA") },
            modifier = Modifier.weight(1f)
        )
        DecimalInputField(
            value = draft.decDegrees,
            onValueChange = { onChange(draft.copy(decDegrees = it)) },
            enabled = enabled,
            label = { Text("Dec") },
            modifier = Modifier.weight(1f)
        )
        if (rotatorConnected) {
            DecimalInputField(
                value = draft.positionAngleDeg,
                onValueChange = { onChange(draft.copy(positionAngleDeg = it)) },
                enabled = enabled,
                label = { Text(stringResource(R.string.sequence_position_angle)) },
                modifier = Modifier.weight(1f)
            )
        }
    }
    if (draft.targets.isNotEmpty()) {
        Text(
            stringResource(
                R.string.sequence_mosaic_panel_summary,
                draft.targets.count { it.enabled },
                draft.targets.size
            ),
            style = MaterialTheme.typography.labelLarge
        )
        draft.targets.forEachIndexed { index, target ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = target.enabled,
                            onClick = {
                                if (enabled) {
                                    onChange(
                                        draft.copy(
                                            targets = draft.targets.mapIndexed { targetIndex, current ->
                                                if (targetIndex == index) {
                                                    current.copy(enabled = !current.enabled)
                                                } else {
                                                    current
                                                }
                                            }
                                        )
                                    )
                                }
                            },
                            enabled = enabled,
                            label = { Text(target.name) }
                        )
                        TextButton(
                            onClick = {
                                onChange(
                                    draft.copy(
                                        targets = draft.targets.mapIndexed { targetIndex, current ->
                                            current.copy(enabled = targetIndex == index)
                                        }
                                    )
                                )
                            },
                            enabled = enabled
                        ) {
                            Text(stringResource(R.string.sequence_only_this_panel))
                        }
                    }
                    Text(
                        stringResource(
                            R.string.sequence_mosaic_panel_coordinates,
                            target.panelRow ?: 1,
                            target.panelColumn ?: 1,
                            target.raHours,
                            target.decDegrees
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                if (index > 0) {
                                    val reordered = draft.targets.toMutableList()
                                    val item = reordered.removeAt(index)
                                    reordered.add(index - 1, item)
                                    onChange(draft.copy(targets = reordered))
                                }
                            },
                            enabled = enabled && index > 0
                        ) { Text(stringResource(R.string.sequence_move_up)) }
                        TextButton(
                            onClick = {
                                if (index < draft.targets.lastIndex) {
                                    val reordered = draft.targets.toMutableList()
                                    val item = reordered.removeAt(index)
                                    reordered.add(index + 1, item)
                                    onChange(draft.copy(targets = reordered))
                                }
                            },
                            enabled = enabled && index < draft.targets.lastIndex
                        ) { Text(stringResource(R.string.sequence_move_down)) }
                    }
                }
            }
        }
    }
    draft.rows.forEachIndexed { index, row ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = row.enabled,
                        onClick = {
                            if (enabled) updateRow(draft, index, row.copy(enabled = !row.enabled), onChange)
                        },
                        enabled = enabled,
                        label = {
                            Text(stringResource(if (row.enabled) R.string.sequence_enabled else R.string.sequence_disabled))
                        }
                    )
                    TextButton(
                        onClick = {
                            onChange(draft.copy(rows = draft.rows.filterIndexed { rowIndex, _ -> rowIndex != index }))
                        },
                        enabled = enabled
                    ) { Text(stringResource(R.string.sequence_remove)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SimpleChoiceField(
                        value = row.filterName.orEmpty(),
                        options = listOf("", row.filterName.orEmpty()) + filterNames,
                        optionLabel = { it.ifBlank { stringResource(R.string.sequence_camera_default) } },
                        onSelect = { updateRow(draft, index, row.copy(filterName = it.ifBlank { null }), onChange) },
                        enabled = enabled && row.enabled,
                        label = stringResource(R.string.sequence_filter),
                        modifier = Modifier.weight(1f)
                    )
                    SimpleChoiceField(
                        value = row.imageType,
                        options = listOf("LIGHT", "FLAT", "DARK", "BIAS", "SNAPSHOT"),
                        onSelect = { updateRow(draft, index, row.copy(imageType = it), onChange) },
                        enabled = enabled && row.enabled,
                        label = stringResource(R.string.sequence_image_type),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalInputField(
                        value = row.exposureSeconds,
                        onValueChange = { updateRow(draft, index, row.copy(exposureSeconds = it), onChange) },
                        enabled = enabled && row.enabled,
                        label = { Text(stringResource(R.string.sequence_exposure)) },
                        modifier = Modifier.weight(1f)
                    )
                    IntegerInputField(
                        value = row.count,
                        onValueChange = { updateRow(draft, index, row.copy(count = it), onChange) },
                        enabled = enabled && row.enabled,
                        label = { Text(stringResource(R.string.sequence_count)) },
                        modifier = Modifier.weight(1f)
                    )
                    IntegerInputField(
                        value = row.binX,
                        onValueChange = {
                            val bin = it.coerceAtLeast(1)
                            updateRow(draft, index, row.copy(binX = bin, binY = bin), onChange)
                        },
                        enabled = enabled && row.enabled,
                        label = { Text(stringResource(R.string.sequence_binning)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalIntegerInputField(
                        value = row.gain.takeIf { it >= 0 },
                        onValueChange = { updateRow(draft, index, row.copy(gain = it ?: -1), onChange) },
                        enabled = enabled && row.enabled,
                        label = { Text(stringResource(R.string.gain)) },
                        modifier = Modifier.weight(1f)
                    )
                    OptionalIntegerInputField(
                        value = row.offset.takeIf { it >= 0 },
                        onValueChange = { updateRow(draft, index, row.copy(offset = it ?: -1), onChange) },
                        enabled = enabled && row.enabled,
                        label = { Text(stringResource(R.string.sequence_offset)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
    TextButton(onClick = {
        if (enabled) onChange(draft.copy(rows = draft.rows + SimpleExposureRow(null, 30.0, -1, -1, 1)))
    }, enabled = enabled) { Text(stringResource(R.string.sequence_add_row)) }
    ToggleLine(stringResource(R.string.sequence_slew), draft.slewBefore, enabled) { onChange(draft.copy(slewBefore = it)) }
    ToggleLine(stringResource(R.string.sequence_center), draft.centerBefore, enabled) { onChange(draft.copy(centerBefore = it)) }
    if (rotatorConnected) {
        ToggleLine(stringResource(R.string.sequence_rotate_to_angle), draft.rotateBefore, enabled) {
            onChange(draft.copy(rotateBefore = it))
        }
    }
    ToggleLine(stringResource(R.string.sequence_guide), draft.guideBefore, enabled) { onChange(draft.copy(guideBefore = it)) }
    ToggleLine(stringResource(R.string.sequence_autofocus_before), draft.autofocusBefore, enabled) { onChange(draft.copy(autofocusBefore = it)) }
    ToggleLine(stringResource(R.string.sequence_end_guide), draft.endStopGuide, enabled) { onChange(draft.copy(endStopGuide = it)) }
    ToggleLine(stringResource(R.string.sequence_end_warm), draft.endWarm, enabled) { onChange(draft.copy(endWarm = it)) }
    ToggleLine(stringResource(R.string.sequence_end_tracking), draft.endStopTracking, enabled) { onChange(draft.copy(endStopTracking = it)) }
    ToggleLine(stringResource(R.string.sequence_end_home), draft.endGoHome, enabled) { onChange(draft.copy(endGoHome = it)) }
    ToggleLine(stringResource(R.string.sequence_end_cover), draft.endCloseCover, enabled) { onChange(draft.copy(endCloseCover = it)) }
    OptionalDecimalInputField(
        value = draft.coolToC,
        onValueChange = { onChange(draft.copy(coolToC = it)) },
        enabled = enabled,
        label = { Text(stringResource(R.string.sequence_cool)) },
        modifier = Modifier.fillMaxWidth()
    )
    OptionalDecimalInputField(
        value = draft.altitudeEndDeg,
        onValueChange = { onChange(draft.copy(altitudeEndDeg = it)) },
        enabled = enabled,
        label = { Text(stringResource(R.string.sequence_altitude_end)) },
        modifier = Modifier.fillMaxWidth()
    )
    OptionalIntegerInputField(
        value = draft.ditherEvery,
        onValueChange = { onChange(draft.copy(ditherEvery = it)) },
        enabled = enabled,
        label = { Text(stringResource(R.string.sequence_dither_every)) },
        modifier = Modifier.fillMaxWidth()
    )
    DecimalInputField(
        value = draft.ditherPixels,
        onValueChange = { onChange(draft.copy(ditherPixels = it)) },
        enabled = enabled && draft.ditherEvery != null,
        label = { Text(stringResource(R.string.sequence_dither_pixels)) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DecimalInputField(
    value: Double,
    onValueChange: (Double) -> Unit,
    enabled: Boolean,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf(simpleNumber(value)) }
    val parsed = text.toDoubleOrNull()?.takeIf { it.isFinite() }
    LaunchedEffect(value) {
        if (text.toDoubleOrNull()?.takeIf { it.isFinite() } != value) text = simpleNumber(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            next.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onValueChange)
        },
        enabled = enabled,
        label = label,
        isError = text.isNotBlank() && parsed == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun OptionalDecimalInputField(
    value: Double?,
    onValueChange: (Double?) -> Unit,
    enabled: Boolean,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf(value?.let(::simpleNumber).orEmpty()) }
    val valid = text.isBlank() || text.toDoubleOrNull()?.isFinite() == true
    LaunchedEffect(value) {
        val current = text.toDoubleOrNull()?.takeIf { it.isFinite() }
        if ((text.isBlank() && value != null) || (text.isNotBlank() && current != value)) {
            text = value?.let(::simpleNumber).orEmpty()
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            if (next.isBlank()) onValueChange(null)
            else next.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onValueChange)
        },
        enabled = enabled,
        label = label,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun IntegerInputField(
    value: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            next.toIntOrNull()?.let(onValueChange)
        },
        enabled = enabled,
        label = label,
        isError = text.isNotBlank() && text.toIntOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun OptionalIntegerInputField(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    enabled: Boolean,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf(value?.toString().orEmpty()) }
    LaunchedEffect(value) {
        val current = text.toIntOrNull()
        if ((text.isBlank() && value != null) || (text.isNotBlank() && current != value)) {
            text = value?.toString().orEmpty()
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            if (next.isBlank()) onValueChange(null) else next.toIntOrNull()?.let(onValueChange)
        },
        enabled = enabled,
        label = label,
        isError = text.isNotBlank() && text.toIntOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun SimpleChoiceField(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    optionLabel: @Composable (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onSelect,
            enabled = enabled,
            label = { Text(label) },
            placeholder = {
                if (value.isBlank()) Text(optionLabel(value))
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.distinct().forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

private fun simpleNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

@Composable
private fun ToggleLine(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(selected = checked, onClick = { if (enabled) onChange(!checked) }, label = { Text(label) })
}

private fun updateRow(
    draft: com.indigo.mobileobservatory.sequence.SimpleSequenceDraft,
    index: Int,
    row: SimpleExposureRow,
    onChange: (com.indigo.mobileobservatory.sequence.SimpleSequenceDraft) -> Unit
) {
    onChange(draft.copy(rows = draft.rows.mapIndexed { rowIndex, current -> if (rowIndex == index) row else current }))
}

@Composable
private fun MetricChart(values: List<Double?>, color: Color, modifier: Modifier) {
    val points = values.mapIndexedNotNull { index, value -> value?.let { index to it } }
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val min = points.minOf { it.second }
        val max = points.maxOf { it.second }
        val span = (max - min).takeIf { it > 1e-6 } ?: 1.0
        val path = Path()
        points.forEachIndexed { drawn, (index, value) ->
            val x = size.width * index / (values.size - 1).coerceAtLeast(1)
            val y = size.height - ((value - min) / span).toFloat() * size.height
            if (drawn == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
        points.forEach { (index, value) ->
            val x = size.width * index / (values.size - 1).coerceAtLeast(1)
            val y = size.height - ((value - min) / span).toFloat() * size.height
            drawCircle(color, radius = 4f, center = Offset(x, y))
        }
    }
}
