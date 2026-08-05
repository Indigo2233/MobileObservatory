package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.accessories.SerialAccessoryRole
import com.indigo.mobileobservatory.camera.AccessoryDeviceEntry
import com.indigo.mobileobservatory.camera.AccessoryType
import com.indigo.mobileobservatory.ui.components.EAFPanel
import com.indigo.mobileobservatory.ui.components.FilterWheelPanel
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel

@Composable
fun AccessoriesScreen(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val devices by viewModel.accessoryDevices.collectAsState()
    val scanError by viewModel.accessoryScanError.collectAsState()
    val activeFocuserId by viewModel.activeFocuserDeviceId.collectAsState()
    val activeCoverId by viewModel.activeCoverDeviceId.collectAsState()
    val activeRotatorId by viewModel.activeRotatorDeviceId.collectAsState()
    val filterWheelConnected by viewModel.filterWheelConnected.collectAsState()
    val focuserConnected by viewModel.eafConnected.collectAsState()
    val coverConnected by viewModel.coverConnected.collectAsState()
    val rotatorConnected by viewModel.rotatorConnected.collectAsState()

    LaunchedEffect(Unit) { viewModel.scanAccessories() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.accessories_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(onClick = viewModel::scanAccessories) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(stringResource(R.string.scan))
            }
        }

        if (devices.isEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(stringResource(R.string.no_accessories_found), Modifier.padding(16.dp))
            }
        } else {
            devices.forEach { device ->
                DeviceCard(
                    device = device,
                    connectedRole = when (device.usbDevice.deviceId) {
                        activeFocuserId -> stringResource(R.string.focuser)
                        activeCoverId -> stringResource(R.string.cover)
                        activeRotatorId -> "CAA"
                        else -> if (device.type == AccessoryType.FILTER_WHEEL &&
                            filterWheelConnected) stringResource(R.string.filter_wheel) else null
                    },
                    onConnect = { viewModel.connectAccessory(device) },
                    onDetectAndConnect = { viewModel.connectSerialAuto(device) },
                    onEfucoser = { viewModel.connectEfucoser(device) },
                    onCover = { viewModel.connectCover(device) },
                    onRotator = { viewModel.connectRotator(device) },
                    onDisconnect = {
                        when (device.usbDevice.deviceId) {
                            activeFocuserId -> viewModel.disconnectEaf()
                            activeCoverId -> viewModel.disconnectCover()
                            activeRotatorId -> viewModel.disconnectRotator()
                            else -> if (device.type == AccessoryType.FILTER_WHEEL) {
                                viewModel.disconnectFilterWheel()
                            }
                        }
                    }
                )
            }
        }

        scanError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (focuserConnected) FocuserControls(viewModel)
        if (coverConnected) CoverControls(viewModel)
        if (rotatorConnected) RotatorControls(viewModel)
        if (filterWheelConnected) FilterWheelControls(viewModel)
    }
}

@Composable
private fun DeviceCard(
    device: AccessoryDeviceEntry,
    connectedRole: String?,
    onConnect: () -> Unit,
    onDetectAndConnect: () -> Unit,
    onEfucoser: () -> Unit,
    onCover: () -> Unit,
    onRotator: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isSerial = device.type == AccessoryType.SERIAL_DEVICE ||
        device.type == AccessoryType.EFUCOSER_FOCUSER
    val roles = device.serialRoles
    val matchedRoles = roles?.takeIf { it.isNotEmpty() }
    val probeFailed = roles != null && roles.isEmpty()
    val showFocuser = !isSerial || matchedRoles == null ||
        SerialAccessoryRole.FOCUSER in matchedRoles ||
        SerialAccessoryRole.GEMINI_EAF in matchedRoles
    val showCover = !isSerial || matchedRoles == null ||
        SerialAccessoryRole.COVER in matchedRoles ||
        SerialAccessoryRole.GEMINI_FLAT in matchedRoles
    val showRotator = !isSerial || matchedRoles == null || SerialAccessoryRole.ROTATOR in matchedRoles
    val matchedLabel = when {
        matchedRoles?.singleOrNull() == SerialAccessoryRole.FOCUSER ->
            stringResource(R.string.focuser)
        matchedRoles?.singleOrNull() == SerialAccessoryRole.GEMINI_EAF ->
            stringResource(R.string.gemini_eaf)
        matchedRoles?.singleOrNull() == SerialAccessoryRole.COVER ->
            stringResource(R.string.cover)
        matchedRoles?.singleOrNull() == SerialAccessoryRole.GEMINI_FLAT ->
            stringResource(R.string.gemini_flat)
        matchedRoles?.singleOrNull() == SerialAccessoryRole.ROTATOR -> "CAA"
        else -> null
    }
    val statusText = when {
        connectedRole != null -> stringResource(R.string.serial_connected_as, connectedRole)
        device.probing -> stringResource(R.string.serial_probing)
        matchedLabel != null -> stringResource(R.string.serial_matched_as, matchedLabel)
        isSerial && probeFailed -> stringResource(R.string.serial_unrecognized)
        isSerial -> stringResource(R.string.serial_manual_fallback)
        else -> device.type.name
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (connectedRole != null) {
                    OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
                }
            }
            if (connectedRole == null && !device.probing) {
                if (isSerial) {
                    if (matchedLabel == null) {
                        Button(onClick = onDetectAndConnect) {
                            Text(stringResource(R.string.serial_detect_and_connect))
                        }
                    }
                    if (matchedLabel != null || probeFailed || roles == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (showFocuser) {
                                if (matchedLabel != null) {
                                    Button(onClick = onEfucoser) {
                                        Text(stringResource(R.string.focuser))
                                    }
                                } else {
                                    OutlinedButton(onClick = onEfucoser) {
                                        Text(stringResource(R.string.focuser))
                                    }
                                }
                            }
                            if (showCover) {
                                if (matchedLabel != null) {
                                    Button(onClick = onCover) {
                                        Text(stringResource(R.string.cover))
                                    }
                                } else {
                                    OutlinedButton(onClick = onCover) {
                                        Text(stringResource(R.string.cover))
                                    }
                                }
                            }
                            if (showRotator) {
                                if (matchedLabel != null) {
                                    Button(onClick = onRotator) { Text("CAA") }
                                } else {
                                    OutlinedButton(onClick = onRotator) { Text("CAA") }
                                }
                            }
                        }
                    }
                } else {
                    Button(onClick = onConnect) { Text(stringResource(R.string.connect)) }
                }
            }
        }
    }
}

@Composable
private fun FocuserControls(viewModel: CameraViewModel) {
    val position by viewModel.eafPosition.collectAsState()
    val moving by viewModel.eafMoving.collectAsState()
    val temperature by viewModel.eafTemperature.collectAsState()
    val info by viewModel.eafInfo.collectAsState()
    info?.let {
        Text(stringResource(R.string.focuser_control), style = MaterialTheme.typography.titleMedium)
        EAFPanel(
            isConnected = true,
            position = position,
            isMoving = moving,
            temperature = temperature,
            eafInfo = it,
            onMoveTo = viewModel::eafMoveTo,
            onMoveRelative = viewModel::eafMoveRelative,
            onHalt = viewModel::eafHalt,
            onSetZero = viewModel::eafSetZero,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CoverControls(viewModel: CameraViewModel) {
    val coverState by viewModel.coverState.collectAsState()
    val calibratorState by viewModel.calibratorState.collectAsState()
    val brightness by viewModel.calibratorBrightness.collectAsState()
    val maxBrightness by viewModel.calibratorMaxBrightness.collectAsState()
    val info by viewModel.coverDeviceInfo.collectAsState()
    var pendingBrightness by remember(brightness) { mutableStateOf(brightness.toFloat()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cover_calibrator), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.cover_status, info.orEmpty(), coverState, calibratorState))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::openCover) { Text(stringResource(R.string.open)) }
                Button(onClick = viewModel::closeCover) { Text(stringResource(R.string.close)) }
                OutlinedButton(onClick = viewModel::haltCover) { Text(stringResource(R.string.stop)) }
            }
            Text(stringResource(R.string.brightness_value, pendingBrightness.toInt(), maxBrightness))
            Slider(
                value = pendingBrightness,
                onValueChange = { pendingBrightness = it },
                onValueChangeFinished = {
                    viewModel.setCalibratorBrightness(pendingBrightness.toInt())
                },
                valueRange = 0f..maxBrightness.coerceAtLeast(1).toFloat()
            )
            OutlinedButton(onClick = viewModel::calibratorOff) { Text(stringResource(R.string.turn_off_calibrator)) }
        }
    }
}

@Composable
private fun RotatorControls(viewModel: CameraViewModel) {
    val angle by viewModel.rotatorAngle.collectAsState()
    val moving by viewModel.rotatorMoving.collectAsState()
    val position by viewModel.rotatorPositionSteps.collectAsState()
    val scale by viewModel.rotatorStepsPerDegree.collectAsState()
    val scaleFromBoard by viewModel.rotatorStepsPerDegreeFromBoard.collectAsState()
    val reversed by viewModel.rotatorReversed.collectAsState()
    val hold by viewModel.rotatorHold.collectAsState()
    val info by viewModel.rotatorDeviceInfo.collectAsState()
    var target by remember { mutableStateOf("0") }
    var showScaleEditor by remember { mutableStateOf(false) }
    var scaleText by remember(scale) { mutableStateOf(scale.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.motorized_caa), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.rotator_status,
                    info.orEmpty(),
                    angle,
                    position,
                    if (moving) stringResource(R.string.moving_suffix) else ""
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (scaleFromBoard) {
                    stringResource(R.string.steps_per_degree_from_board, scale)
                } else {
                    stringResource(R.string.steps_per_degree_default, scale)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text(stringResource(R.string.target_angle_short)) },
                    singleLine = true,
                    modifier = Modifier.width(96.dp)
                )
                Button(onClick = { target.toDoubleOrNull()?.let(viewModel::moveRotatorTo) }) {
                    Text(stringResource(R.string.go))
                }
                OutlinedButton(onClick = { viewModel.moveRotatorRelative(-5.0) }) { Text("-5°") }
                OutlinedButton(onClick = { viewModel.moveRotatorRelative(5.0) }) { Text("+5°") }
                OutlinedButton(onClick = viewModel::haltRotator) { Text(stringResource(R.string.stop)) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::homeRotator) { Text(stringResource(R.string.home)) }
                OutlinedButton(onClick = viewModel::zeroRotator) {
                    Text(stringResource(R.string.set_current_zero))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.reverse), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.reverse_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = reversed,
                    onCheckedChange = viewModel::setRotatorReversed
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.motor_hold), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.motor_hold_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = hold,
                    onCheckedChange = viewModel::setRotatorHold
                )
            }

            TextButton(onClick = {
                showScaleEditor = !showScaleEditor
                scaleText = scale.toString()
            }) {
                Text(
                    if (showScaleEditor) stringResource(R.string.hide_steps_per_degree_editor)
                    else stringResource(R.string.edit_steps_per_degree)
                )
            }
            if (showScaleEditor) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = scaleText,
                        onValueChange = { scaleText = it },
                        label = { Text(stringResource(R.string.steps_per_degree)) },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                    Button(onClick = {
                        scaleText.toIntOrNull()?.let {
                            viewModel.setRotatorStepsPerDegree(it)
                            showScaleEditor = false
                        }
                    }) { Text(stringResource(R.string.set)) }
                }
            }
        }
    }
}

@Composable
private fun FilterWheelControls(viewModel: CameraViewModel) {
    val position by viewModel.filterWheelPosition.collectAsState()
    val moving by viewModel.filterWheelMoving.collectAsState()
    val names by viewModel.filterWheelSlotNames.collectAsState()
    val info by viewModel.filterWheelInfo.collectAsState()
    info?.let {
        Text(stringResource(R.string.filter_wheel_control), style = MaterialTheme.typography.titleMedium)
        FilterWheelPanel(
            isConnected = true,
            currentPosition = position,
            isMoving = moving,
            slotNames = names,
            slotCount = it.slotCount,
            onSetPosition = viewModel::setFilterWheelPosition,
            onReset = viewModel::resetFilterWheel,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
