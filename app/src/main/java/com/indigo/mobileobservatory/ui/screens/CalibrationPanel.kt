@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.stacking.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel

@Composable
internal fun CalibrationEntry(vm: CameraViewModel, onOpen: () -> Unit) {
    val state by vm.stacking.calibration.state.collectAsState()
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.stack_cal_entry, if (state.selectedIds.isEmpty())
            stringResource(R.string.stack_cal_none) else CalibrationType.values().filter { it in state.selectedIds }.joinToString(" + ") { it.name }))
    }
}

/** A compact library first; each type exposes its own short capture guide on demand. */
@Composable
internal fun CalibrationPanel(vm: CameraViewModel, onDismiss: () -> Unit, onCamera: () -> Unit) {
    val coordinator = vm.stacking
    val library = coordinator.calibration
    val state by library.state.collectAsState()
    val task by coordinator.task.collectAsState()
    val recording by vm.isRecording.collectAsState()
    var typeName by rememberSaveable { mutableStateOf<String?>(null) }
    val type = typeName?.let(CalibrationType::valueOf)
    var guideStep by rememberSaveable { mutableIntStateOf(-1) }
    var confirmMatching by rememberSaveable { mutableStateOf(false) }
    var browse by rememberSaveable { mutableStateOf(false) }
    var resetConfirmation by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val latestType by rememberUpdatedState(type)
    val latestConfirmed by rememberUpdatedState(confirmMatching)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val category = latestType
        if (uri != null && category != null) library.importMaster(category, uri, latestConfirmed)
    }
    if (browse && type != null) {
        AppRecordingPicker(vm, onDismiss = { browse = false },
            onSelect = { file -> browse = false; library.createMaster(type, file, confirmMatching) },
            onCamera = { browse = false; onDismiss(); onCamera() })
        return
    }
    if (resetConfirmation) AlertDialog(onDismissRequest = { resetConfirmation = false },
        title = { Text(stringResource(R.string.stack_cal_reset)) },
        text = { Text(stringResource(R.string.stack_cal_reset_hint)) },
        confirmButton = { TextButton(onClick = {
            coordinator.resetLive(); coordinator.clearPlanetary(); resetConfirmation = false
        }) { Text(stringResource(R.string.stack_cal_reset)) } },
        dismissButton = { TextButton(onClick = { resetConfirmation = false }) { Text(stringResource(R.string.cancel)) } })
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.stack_cal_delete)) },
            text = { Text(stringResource(R.string.stack_cal_delete_hint)) },
            confirmButton = { TextButton(onClick = { library.deleteMaster(id); deleteId = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } })
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.94f).padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (type != null) TextButton(onClick = { if (guideStep >= 0) guideStep = -1 else typeName = null }) { Text(stringResource(if (guideStep >= 0) R.string.stack_cal_library_back else R.string.back)) }
                    Text(if (type == null) stringResource(R.string.stack_cal_title) else calibrationTypeLabel(type),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.stack_cal_done)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.busy) {
                        Text(stringResource(R.string.stack_cal_working))
                        if (state.total > 0) {
                            LinearProgressIndicator((state.completed.toDouble()/state.total).toFloat().coerceIn(0f,1f), Modifier.fillMaxWidth())
                            Text("${state.completed} / ${state.total}")
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = library::cancel) { Text(stringResource(R.string.cancel)) }
                    }
                    if (state.message.isNotEmpty()) Text(stackMessage(state.message), color = MaterialTheme.colorScheme.tertiary)
                    if (state.locked) {
                        Text(stringResource(R.string.stack_cal_locked), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { resetConfirmation = true }, enabled = !state.busy && !task.busy) {
                            Text(stringResource(R.string.stack_cal_reset))
                        }
                    }
                    if (type == null) {
                        Text(stringResource(R.string.stack_cal_optional), style = MaterialTheme.typography.bodySmall)
                        CalibrationType.values().forEach { category ->
                            val selected = state.masters.firstOrNull { it.id == state.selectedIds[category] }
                            OutlinedButton(onClick = { typeName = category.name; guideStep = -1; library.clearMessage() }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(calibrationTypeLabel(category), style = MaterialTheme.typography.titleSmall)
                                    Text(selected?.name ?: stringResource(R.string.stack_cal_none), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (state.selectedIds.isNotEmpty()) TextButton(onClick = library::clearSelection,
                            enabled = state.initialized && !state.locked && !state.busy) {
                            Text(stringResource(R.string.stack_cal_clear_all))
                        }
                    } else if (guideStep >= 0) {
                        Text(stringResource(R.string.stack_cal_step, guideStep+1), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when (guideStep) {
                            0 -> when (type) {
                                CalibrationType.BIAS -> R.string.stack_cal_bias_prepare
                                CalibrationType.DARK -> R.string.stack_cal_dark_prepare
                                CalibrationType.FLAT -> R.string.stack_cal_flat_prepare
                            }
                            1 -> when (type) {
                                CalibrationType.BIAS -> R.string.stack_cal_bias_settings
                                CalibrationType.DARK -> R.string.stack_cal_dark_settings
                                CalibrationType.FLAT -> R.string.stack_cal_flat_settings
                            }
                            else -> R.string.stack_cal_record_steps
                        }))
                        if (guideStep == 2) {
                            OutlinedButton(onClick = { vm.setRecordFormat("SER"); onDismiss(); onCamera() },
                                enabled = !recording && !state.locked && !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.stack_cal_go_camera))
                            }
                            TextButton(onClick = { guideStep = -1; browse = true }, enabled = !state.locked && !state.busy) {
                                Text(stringResource(R.string.stack_cal_already_recorded))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (guideStep > 0) TextButton(onClick = { guideStep-- }) { Text(stringResource(R.string.stack_cal_previous)) }
                            if (guideStep < 2) Button(onClick = { guideStep++ }) { Text(stringResource(R.string.stack_cal_next)) }
                        }
                    } else {
                        OutlinedButton(onClick = { guideStep = 0 }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stack_cal_guide)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = confirmMatching, onCheckedChange = { confirmMatching = it }, enabled = !state.busy)
                            Text(stringResource(R.string.stack_cal_confirm), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                        TextButton(onClick = { library.selectMaster(type, null) }, enabled = !state.locked && !state.busy) {
                            Text(stringResource(R.string.stack_cal_use_none))
                        }
                        state.masters.filter { it.type == type }.forEach { master ->
                            val selected = state.selectedIds[type] == master.id
                            OutlinedButton(onClick = { library.selectMaster(type, master.id, confirmMatching) },
                                enabled = !state.locked && !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (selected) stringResource(R.string.stack_cal_selected, master.name) else master.name)
                                    Text("${master.geometry.width} × ${master.geometry.height} · ${master.geometry.layout} · ${master.frameCount} " + stringResource(R.string.stack_cal_frames), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            TextButton(onClick = { deleteId = master.id }, enabled = !selected && !state.locked && !state.busy) {
                                Text(stringResource(R.string.stack_cal_delete))
                            }
                        }
                        if (state.masters.none { it.type == type }) Text(stringResource(R.string.stack_cal_library_empty), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { browse = true }, enabled = !state.locked && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stack_cal_from_recording)) }
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.locked && !state.busy) { Text(stringResource(R.string.stack_cal_import_ser)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun calibrationTypeLabel(type: CalibrationType) = stringResource(when (type) {
    CalibrationType.BIAS -> R.string.stack_cal_bias
    CalibrationType.DARK -> R.string.stack_cal_dark
    CalibrationType.FLAT -> R.string.stack_cal_flat
})
