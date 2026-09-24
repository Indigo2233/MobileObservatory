@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.sequence.SequenceEditorMode
import com.indigo.mobileobservatory.sequence.SequencePhase
import com.indigo.mobileobservatory.sequence.SimpleExposureRow
import com.indigo.mobileobservatory.sequence.plannedFrames
import com.indigo.mobileobservatory.sequence.targetAltitudeCurve
import com.indigo.mobileobservatory.sequence.tonightWindow
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import java.time.Instant

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
    val runtime = viewModel.sequenceRuntime
    val state by runtime.state.collectAsState()
    val mode by runtime.mode.collectAsState()
    val draft by runtime.draft.collectAsState()
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
    var page by rememberSaveable { mutableStateOf("edit") }
    var templateName by rememberSaveable { mutableStateOf(draft.title) }
    val running = state.phase == SequencePhase.Running || state.phase == SequencePhase.Paused
    val chartColor = if (redNightMode) Color(0xFFE53935) else MaterialTheme.colorScheme.primary

    LaunchedEffect(state.phase) {
        if (state.phase == SequencePhase.Running || state.phase == SequencePhase.Paused) page = "status"
    }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            Button(onClick = { runtime.start() }, enabled = !running) {
                                Text(stringResource(R.string.sequence_start))
                            }
                        }
                        SimpleEditor(
                            draft = draft,
                            enabled = !running,
                            skyTarget = skyTarget
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
                        latitudeDeg = site?.latitudeDeg,
                        longitudeDeg = site?.longitudeDeg,
                        chartColor = chartColor,
                        skyTarget = skyTarget,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { runtime.start() }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sequence_start))
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${state.phase}  ${state.currentClassName.orEmpty()}  ${state.message.orEmpty()}")
                Text(stringResource(R.string.sequence_progress, state.framesDone, draft.plannedFrames()))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.phase == SequencePhase.Paused) Button(onClick = runtime::resume) { Text(stringResource(R.string.resume)) }
                    else Button(onClick = runtime::pause, enabled = state.phase == SequencePhase.Running) { Text(stringResource(R.string.pause)) }
                    Button(onClick = runtime::stop, enabled = running) { Text(stringResource(R.string.sequence_stop)) }
                    Button(onClick = runtime::skip, enabled = running) { Text(stringResource(R.string.sequence_skip)) }
                    Button(onClick = runtime::skipToEnd, enabled = running) { Text(stringResource(R.string.sequence_skip_to_end)) }
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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.sequence_mount_attitude))
                        Text("RA ${coordinates?.formatRa().orEmpty()}  Dec ${coordinates?.formatDec().orEmpty()}")
                        Text(stringResource(R.string.sequence_tracking, if (tracking) 1 else 0))
                        Text(stringResource(R.string.sequence_camera_temp, sensorTemp / 10.0, if (coolerOn) 1 else 0))
                        Text(stringResource(R.string.sequence_guide_rms, guideRms, if (guideRunning) 1 else 0))
                        Text(filterNames.joinToString())
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
                        MetricChart(curve.map { it as Double? }, chartColor, Modifier.fillMaxWidth().height(120.dp))
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.sequence_autofocus_history))
                        autofocus.forEach { run ->
                            Text("${run.position}  HFR ${run.hfr}  ${run.filter.orEmpty()}  ${run.temperatureC ?: "-"}")
                            MetricChart(run.curve.map { it.second as Double? }, chartColor, Modifier.fillMaxWidth().height(80.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleEditor(
    draft: com.indigo.mobileobservatory.sequence.SimpleSequenceDraft,
    enabled: Boolean,
    skyTarget: com.indigo.mobileobservatory.sequence.SequenceSkyTarget? = null,
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
        OutlinedTextField(
            value = draft.raHours.toString(),
            onValueChange = { text -> text.toDoubleOrNull()?.let { onChange(draft.copy(raHours = it)) } },
            enabled = enabled,
            label = { Text("RA") },
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = draft.decDegrees.toString(),
            onValueChange = { text -> text.toDoubleOrNull()?.let { onChange(draft.copy(decDegrees = it)) } },
            enabled = enabled,
            label = { Text("Dec") },
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = draft.positionAngleDeg.toString(),
            onValueChange = { text -> text.toDoubleOrNull()?.let { onChange(draft.copy(positionAngleDeg = it)) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.sequence_position_angle)) },
            modifier = Modifier.weight(1f)
        )
    }
    draft.rows.forEachIndexed { index, row ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = row.filterName.orEmpty(),
                onValueChange = { text -> updateRow(draft, index, row.copy(filterName = text.ifBlank { null }), onChange) },
                enabled = enabled,
                label = { Text(stringResource(R.string.sequence_filter)) },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = row.exposureSeconds.toString(),
                onValueChange = { text -> text.toDoubleOrNull()?.let { updateRow(draft, index, row.copy(exposureSeconds = it), onChange) } },
                enabled = enabled,
                label = { Text(stringResource(R.string.sequence_exposure)) },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = row.count.toString(),
                onValueChange = { text -> text.toIntOrNull()?.let { updateRow(draft, index, row.copy(count = it), onChange) } },
                enabled = enabled,
                label = { Text(stringResource(R.string.sequence_count)) },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                if (enabled) onChange(draft.copy(rows = draft.rows.filterIndexed { rowIndex, _ -> rowIndex != index }))
            }) { Text(stringResource(R.string.sequence_remove)) }
        }
    }
    TextButton(onClick = {
        if (enabled) onChange(draft.copy(rows = draft.rows + SimpleExposureRow(null, 30.0, 0, 0, 1)))
    }) { Text(stringResource(R.string.sequence_add_row)) }
    ToggleLine(stringResource(R.string.sequence_slew), draft.slewBefore, enabled) { onChange(draft.copy(slewBefore = it)) }
    ToggleLine(stringResource(R.string.sequence_center), draft.centerBefore, enabled) { onChange(draft.copy(centerBefore = it)) }
    ToggleLine(stringResource(R.string.sequence_guide), draft.guideBefore, enabled) { onChange(draft.copy(guideBefore = it)) }
    ToggleLine(stringResource(R.string.sequence_autofocus_before), draft.autofocusBefore, enabled) { onChange(draft.copy(autofocusBefore = it)) }
    ToggleLine(stringResource(R.string.sequence_end_guide), draft.endStopGuide, enabled) { onChange(draft.copy(endStopGuide = it)) }
    ToggleLine(stringResource(R.string.sequence_end_warm), draft.endWarm, enabled) { onChange(draft.copy(endWarm = it)) }
    ToggleLine(stringResource(R.string.sequence_end_tracking), draft.endStopTracking, enabled) { onChange(draft.copy(endStopTracking = it)) }
    ToggleLine(stringResource(R.string.sequence_end_home), draft.endGoHome, enabled) { onChange(draft.copy(endGoHome = it)) }
    ToggleLine(stringResource(R.string.sequence_end_cover), draft.endCloseCover, enabled) { onChange(draft.copy(endCloseCover = it)) }
    OutlinedTextField(
        value = draft.coolToC?.toString().orEmpty(),
        onValueChange = { text -> onChange(draft.copy(coolToC = text.toDoubleOrNull())) },
        enabled = enabled,
        label = { Text(stringResource(R.string.sequence_cool)) },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = draft.altitudeEndDeg?.toString().orEmpty(),
        onValueChange = { text -> onChange(draft.copy(altitudeEndDeg = text.toDoubleOrNull())) },
        enabled = enabled,
        label = { Text(stringResource(R.string.sequence_altitude_end)) },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = draft.ditherEvery?.toString().orEmpty(),
        onValueChange = { text -> onChange(draft.copy(ditherEvery = text.toIntOrNull())) },
        enabled = enabled,
        label = { Text(stringResource(R.string.sequence_dither_every)) },
        modifier = Modifier.fillMaxWidth()
    )
}

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

