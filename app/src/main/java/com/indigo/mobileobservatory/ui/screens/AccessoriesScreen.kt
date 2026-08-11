package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.accessories.SerialAccessoryRole
import com.indigo.mobileobservatory.camera.AccessoryDeviceEntry
import com.indigo.mobileobservatory.camera.AccessoryType
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.ui.components.EAFPanel
import com.indigo.mobileobservatory.ui.components.FilterWheelPanel
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel

private enum class DeviceTab {
    CONNECTIONS,
    CAMERA,
    MOUNT,
    FILTER_WHEEL,
    FOCUSER,
    COVER,
    ROTATOR
}

@Composable
fun AccessoriesScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    onOpenCamera: () -> Unit = {},
    onOpenMount: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(DeviceTab.CONNECTIONS) }
    val focuserConnected by viewModel.eafConnected.collectAsState()
    val filterWheelConnected by viewModel.filterWheelConnected.collectAsState()
    val coverConnected by viewModel.coverConnected.collectAsState()
    val rotatorConnected by viewModel.rotatorConnected.collectAsState()
    val cameraConnection by viewModel.connectionState.collectAsState()
    val mountConnection by viewModel.mountConnectionState.collectAsState()

    LaunchedEffect(Unit) { viewModel.scanAccessories() }

    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selectedTab.ordinal) {
            DeviceTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { selectedTab = tab },
                    text = { Text(deviceTabTitle(tab)) }
                )
            }
        }

        when (selectedTab) {
            DeviceTab.CONNECTIONS -> DeviceConnectionPage(
                viewModel = viewModel,
                onOpenTab = { selectedTab = it },
                onOpenCamera = onOpenCamera,
                onOpenMount = onOpenMount
            )
            DeviceTab.CAMERA -> DeviceSummaryPage(
                title = stringResource(R.string.tab_camera),
                connected = cameraConnection is ConnectionState.Connected,
                description = when (val state = cameraConnection) {
                    is ConnectionState.Connected -> state.info.name
                    is ConnectionState.Error -> state.message
                    else -> stringResource(R.string.device_connect_from_connections)
                },
                onOpen = onOpenCamera
            )
            DeviceTab.MOUNT -> DeviceSummaryPage(
                title = stringResource(R.string.tab_mount),
                connected = mountConnection is MountConnectionState.Connected,
                description = viewModel.mountConnectionMessage.collectAsState().value
                    .ifBlank { stringResource(R.string.device_connect_from_connections) },
                onOpen = onOpenMount
            )
            DeviceTab.FILTER_WHEEL -> DeviceControlPage(
                connected = filterWheelConnected,
                title = stringResource(R.string.filter_wheel),
                onOpenConnections = { selectedTab = DeviceTab.CONNECTIONS }
            ) { FilterWheelControls(viewModel) }
            DeviceTab.FOCUSER -> DeviceControlPage(
                connected = focuserConnected,
                title = stringResource(R.string.focuser),
                onOpenConnections = { selectedTab = DeviceTab.CONNECTIONS }
            ) { FocuserControls(viewModel) }
            DeviceTab.COVER -> DeviceControlPage(
                connected = coverConnected,
                title = stringResource(R.string.cover_calibrator),
                onOpenConnections = { selectedTab = DeviceTab.CONNECTIONS }
            ) { CoverControls(viewModel) }
            DeviceTab.ROTATOR -> DeviceControlPage(
                connected = rotatorConnected,
                title = stringResource(R.string.motorized_caa),
                onOpenConnections = { selectedTab = DeviceTab.CONNECTIONS }
            ) { RotatorControls(viewModel) }
        }
    }
}

@Composable
private fun deviceTabTitle(tab: DeviceTab): String = when (tab) {
    DeviceTab.CONNECTIONS -> stringResource(R.string.device_connections)
    DeviceTab.CAMERA -> stringResource(R.string.tab_camera)
    DeviceTab.MOUNT -> stringResource(R.string.tab_mount)
    DeviceTab.FILTER_WHEEL -> stringResource(R.string.filter_wheel)
    DeviceTab.FOCUSER -> stringResource(R.string.focuser)
    DeviceTab.COVER -> stringResource(R.string.cover)
    DeviceTab.ROTATOR -> "CAA"
}

@Composable
private fun DeviceConnectionPage(
    viewModel: CameraViewModel,
    onOpenTab: (DeviceTab) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenMount: () -> Unit
) {
    val devices by viewModel.accessoryDevices.collectAsState()
    val scanError by viewModel.accessoryScanError.collectAsState()
    val activeFocuserId by viewModel.activeFocuserDeviceId.collectAsState()
    val activeCoverId by viewModel.activeCoverDeviceId.collectAsState()
    val activeRotatorId by viewModel.activeRotatorDeviceId.collectAsState()
    val filterWheelConnected by viewModel.filterWheelConnected.collectAsState()
    val cameraConnection by viewModel.connectionState.collectAsState()
    val mountConnection by viewModel.mountConnectionState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.device_connections), style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(onClick = viewModel::scanAccessories) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(stringResource(R.string.scan))
            }
        }

        ConnectionShortcutCard(
            title = stringResource(R.string.tab_camera),
            connected = cameraConnection is ConnectionState.Connected,
            onOpen = onOpenCamera
        )
        ConnectionShortcutCard(
            title = stringResource(R.string.tab_mount),
            connected = mountConnection is MountConnectionState.Connected,
            onOpen = onOpenMount
        )

        if (devices.isEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(stringResource(R.string.no_accessories_found), Modifier.padding(16.dp))
            }
        } else {
            devices.forEach { device ->
                val connectedTab = when (device.usbDevice.deviceId) {
                    activeFocuserId -> DeviceTab.FOCUSER
                    activeCoverId -> DeviceTab.COVER
                    activeRotatorId -> DeviceTab.ROTATOR
                    else -> if (device.type == AccessoryType.FILTER_WHEEL && filterWheelConnected) {
                        DeviceTab.FILTER_WHEEL
                    } else null
                }
                val connectedRole = connectedTab?.let { tab ->
                    when (tab) {
                        DeviceTab.FOCUSER -> stringResource(R.string.focuser)
                        DeviceTab.COVER -> stringResource(R.string.cover)
                        DeviceTab.ROTATOR -> "CAA"
                        DeviceTab.FILTER_WHEEL -> stringResource(R.string.filter_wheel)
                        else -> null
                    }
                }
                DeviceCard(
                    device = device,
                    connectedRole = connectedRole,
                    onConnect = { viewModel.connectAccessory(device) },
                    onDetectAndConnect = { viewModel.connectSerialAuto(device) },
                    onFocuser = { viewModel.connectEfucoser(device) },
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
                    },
                    onOpenControl = { onOpenTab(connectedTab ?: DeviceTab.CONNECTIONS) }
                )
            }
        }
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ConnectionShortcutCard(title: String, connected: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (connected) stringResource(R.string.connected) else stringResource(R.string.not_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.open_device_control)) }
        }
    }
}

@Composable
private fun DeviceCard(
    device: AccessoryDeviceEntry,
    connectedRole: String?,
    onConnect: () -> Unit,
    onDetectAndConnect: () -> Unit,
    onFocuser: () -> Unit,
    onCover: () -> Unit,
    onRotator: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenControl: () -> Unit
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
    val showRotator = !isSerial || matchedRoles == null ||
        SerialAccessoryRole.ROTATOR in matchedRoles ||
        SerialAccessoryRole.WANDERER_ROTATOR in matchedRoles
    val matchedLabel = when {
        matchedRoles?.singleOrNull() == SerialAccessoryRole.FOCUSER -> stringResource(R.string.focuser)
        matchedRoles?.singleOrNull() == SerialAccessoryRole.GEMINI_EAF -> stringResource(R.string.gemini_eaf)
        matchedRoles?.singleOrNull() == SerialAccessoryRole.COVER -> stringResource(R.string.cover)
        matchedRoles?.singleOrNull() == SerialAccessoryRole.GEMINI_FLAT -> stringResource(R.string.gemini_flat)
        matchedRoles?.singleOrNull() == SerialAccessoryRole.ROTATOR -> "CAA"
        matchedRoles?.singleOrNull() == SerialAccessoryRole.WANDERER_ROTATOR -> "Wanderer CAA"
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
                    Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (connectedRole != null) {
                    OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
                }
            }
            if (connectedRole != null) {
                TextButton(onClick = onOpenControl) { Text(stringResource(R.string.open_device_control)) }
            } else if (!device.probing) {
                if (isSerial) {
                    if (matchedLabel == null) {
                        Button(onClick = onDetectAndConnect) { Text(stringResource(R.string.serial_detect_and_connect)) }
                    }
                    if (matchedLabel != null || probeFailed || roles == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (showFocuser) {
                                if (matchedLabel != null) Button(onClick = onFocuser) { Text(stringResource(R.string.focuser)) }
                                else OutlinedButton(onClick = onFocuser) { Text(stringResource(R.string.focuser)) }
                            }
                            if (showCover) {
                                if (matchedLabel != null) Button(onClick = onCover) { Text(stringResource(R.string.cover)) }
                                else OutlinedButton(onClick = onCover) { Text(stringResource(R.string.cover)) }
                            }
                            if (showRotator) {
                                if (matchedLabel != null) Button(onClick = onRotator) { Text("CAA") }
                                else OutlinedButton(onClick = onRotator) { Text("CAA") }
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
private fun DeviceSummaryPage(
    title: String,
    connected: Boolean,
    description: String,
    onOpen: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (connected) stringResource(R.string.connected) else stringResource(R.string.not_connected),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connected) Button(onClick = onOpen) { Text(stringResource(R.string.open_device_control)) }
            }
        }
    }
}

@Composable
private fun DeviceControlPage(
    connected: Boolean,
    title: String,
    onOpenConnections: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (connected) {
            content()
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.not_connected))
                    Text(stringResource(R.string.device_connect_from_connections))
                    Button(onClick = onOpenConnections) { Text(stringResource(R.string.device_connections)) }
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
        EAFPanel(
            isConnected = true,
            position = position,
            isMoving = moving,
            temperature = temperature,
            eafInfo = it,
            onMoveTo = viewModel::eafMoveTo,
            onMoveRelative = viewModel::eafMoveRelative,
            onHalt = viewModel::eafHalt,
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
                onValueChangeFinished = { viewModel.setCalibratorBrightness(pendingBrightness.toInt()) },
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
    val reversed by viewModel.rotatorReversed.collectAsState()
    val hold by viewModel.rotatorHold.collectAsState()
    val supportsHold by viewModel.rotatorSupportsHold.collectAsState()
    val info by viewModel.rotatorDeviceInfo.collectAsState()
    var target by remember { mutableStateOf("0") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rotator_status, info.orEmpty(), angle, position, if (moving) stringResource(R.string.moving_suffix) else ""))
            Text(stringResource(R.string.steps_per_degree_default, scale), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text(stringResource(R.string.target_angle_short)) }, singleLine = true, modifier = Modifier.width(96.dp))
                Button(onClick = { target.toDoubleOrNull()?.let(viewModel::moveRotatorTo) }) { Text(stringResource(R.string.go)) }
                OutlinedButton(onClick = { viewModel.moveRotatorRelative(-5.0) }) { Text("-5°") }
                OutlinedButton(onClick = { viewModel.moveRotatorRelative(5.0) }) { Text("+5°") }
                OutlinedButton(onClick = viewModel::haltRotator) { Text(stringResource(R.string.stop)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::homeRotator) { Text(stringResource(R.string.home)) }
                OutlinedButton(onClick = viewModel::zeroRotator) { Text(stringResource(R.string.set_current_zero)) }
            }
            SwitchSetting(stringResource(R.string.reverse), reversed, viewModel::setRotatorReversed)
            if (supportsHold) {
                SwitchSetting(stringResource(R.string.motor_hold), hold, viewModel::setRotatorHold)
            }
        }
    }
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FilterWheelControls(viewModel: CameraViewModel) {
    val position by viewModel.filterWheelPosition.collectAsState()
    val moving by viewModel.filterWheelMoving.collectAsState()
    val names by viewModel.filterWheelSlotNames.collectAsState()
    val info by viewModel.filterWheelInfo.collectAsState()
    info?.let {
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
