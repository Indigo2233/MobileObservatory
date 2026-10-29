package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.delay

@Composable
fun VirtualObservationEntry(vm: CameraViewModel) {
    val session by vm.virtualObservation.collectAsStateWithPageLifecycle()
    val busy by vm.virtualObservationBusy.collectAsStateWithPageLifecycle()
    val message by vm.virtualObservationMessage.collectAsStateWithPageLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.virtual_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.virtual_description), style = MaterialTheme.typography.bodySmall)
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = vm::startVirtualObservation, enabled = session == null && !busy,
                modifier = Modifier.testTag("virtual-start")) { Text(stringResource(if (busy) R.string.connecting else R.string.virtual_start)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VirtualObservationBar(vm: CameraViewModel) {
    val session by vm.virtualObservation.collectAsStateWithPageLifecycle()
    session?.let { active ->
        var expanded by remember(active) { mutableStateOf(false) }
        var fine by remember(active) { mutableStateOf(false) }
        var visible by remember(active) { mutableStateOf(active.model.starsVisible) }
        var backlash by remember(active) { mutableStateOf(active.model.decBacklashMs) }
        var failureQueued by remember(active) { mutableStateOf(false) }
        var largePoleOffset by remember(active) { mutableStateOf(false) }
        var errors by remember(active) { mutableStateOf(active.model.poleError()) }
        LaunchedEffect(active) { while (true) { errors = active.model.poleError(); failureQueued = active.model.failedSolvesRemaining > 0; delay(500) } }
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.testTag("virtual-banner")) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.virtual_badge), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("virtual-controls")) { Text(stringResource(R.string.virtual_controls)) }
                    TextButton(onClick = vm::stopVirtualObservation, modifier = Modifier.testTag("virtual-stop")) { Text(stringResource(R.string.sim_camera_stop)) }
                }
                if (expanded) {
                    Text(stringResource(R.string.virtual_errors, errors.first * 60, errors.second * 60), style = MaterialTheme.typography.labelSmall)
                    val step = if (fine) 1.0 / 60 else .1
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            largePoleOffset = !largePoleOffset
                            active.model.setPoleError(
                                if (largePoleOffset) .8 else com.indigo.mobileobservatory.simulation.VirtualObservatory.DEFAULT_AZIMUTH_ERROR_DEG,
                                if (largePoleOffset) .6 else com.indigo.mobileobservatory.simulation.VirtualObservatory.DEFAULT_ALTITUDE_ERROR_DEG)
                            errors = active.model.poleError()
                        }, modifier = Modifier.testTag("virtual-polar-preset")) {
                            Text(stringResource(if (largePoleOffset) R.string.virtual_small_polar_offset else R.string.virtual_large_polar_offset))
                        }
                        listOf(Triple(R.string.virtual_east, step, 0.0), Triple(R.string.virtual_west, -step, 0.0),
                            Triple(R.string.virtual_up, 0.0, step), Triple(R.string.virtual_down, 0.0, -step)).forEachIndexed { i, action ->
                            OutlinedButton(onClick = { active.model.adjustPole(action.second, action.third); errors = active.model.poleError() },
                                modifier = Modifier.testTag("virtual-adjust-$i")) { Text(stringResource(action.first)) }
                        }
                        TextButton(onClick = { fine = !fine }) { Text(stringResource(R.string.virtual_step, if (fine) 1 else 6)) }
                        TextButton(onClick = { visible = !visible; active.model.starsVisible = visible }, modifier = Modifier.testTag("virtual-stars")) {
                            Text(stringResource(if (visible) R.string.virtual_hide_stars else R.string.virtual_show_stars))
                        }
                        TextButton(onClick = { active.model.failedSolvesRemaining = 2; failureQueued = true }, modifier = Modifier.testTag("virtual-fail-solve")) {
                            Text(stringResource(if (failureQueued) R.string.virtual_failure_queued else R.string.virtual_fail_solve))
                        }
                        TextButton(onClick = { backlash = if (backlash == 1800) 3000 else 1800; active.model.decBacklashMs = backlash }) {
                            Text(stringResource(R.string.virtual_backlash, backlash))
                        }
                    }
                }
            }
        }
    }
}
