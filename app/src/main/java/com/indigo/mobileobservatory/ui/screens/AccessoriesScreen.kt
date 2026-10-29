package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.AccessoryDeviceEntry
import com.indigo.mobileobservatory.camera.AccessoryType
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.ui.components.PowerBoxPanel
import com.indigo.mobileobservatory.ui.components.rememberSettingsExitState
import com.indigo.mobileobservatory.ui.devices.DeviceConnectionRules
import com.indigo.mobileobservatory.ui.devices.DeviceControlDestination
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlin.math.roundToInt

/**
 * Equipment owns the complete device details. A detail has one parent (the list),
 * while status-strip shortcuts render exactly the same controls in place.
 */
@Composable
fun AccessoriesScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    onOpenCamera: () -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    var selectedDeviceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSettings by rememberSaveable { mutableStateOf(false) }
    val selected = selectedDeviceKey?.let(DeviceControlDestination::fromKey)
    BackHandler(enabled = selected != null || onBack != null) {
        if (selected != null) selectedDeviceKey = null else onBack?.invoke()
    }
    LaunchedEffect(Unit) { viewModel.scanAccessories() }

    Column(modifier.fillMaxSize()) {
        if (selected == null) {
            EquipmentList(viewModel, onOpen = { selectedDeviceKey = it.key })
        } else if (editingSettings) {
            DeviceSettingsScreen(viewModel, selected.key, onBack = { editingSettings = false })
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedDeviceKey = null }) {
                    Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                }
                Text(deviceTitle(selected), style = MaterialTheme.typography.titleLarge)
            }
            Box(Modifier.weight(1f)) {
                DeviceDetailContent(viewModel, selected, onOpenCamera, onSettings = { editingSettings = true })
            }
        }
    }
}

/** One tap from the global status strip; dismissing preserves the underlying task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceQuickControlSheet(
    viewModel: CameraViewModel,
    deviceKey: String,
    onDismiss: () -> Unit,
    onOpenCamera: () -> Unit = {}
) {
    val destination = DeviceControlDestination.fromKey(deviceKey) ?: return
    val mountMotion by viewModel.mountMotionState.collectAsStateWithPageLifecycle()
    var editing by rememberSaveable(deviceKey) { mutableStateOf(false) }
    val exit = rememberSettingsExitState()
    SideEffect {
        exit.register("close", onDismiss)
        if (!editing) { exit.dirty = false; exit.saving = false; exit.clearDraft = {} }
    }
    val dismiss: () -> Unit = { if (editing) exit.request("close") else onDismiss() }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { value ->
            if (value == SheetValue.Hidden && editing && (exit.dirty || exit.saving)) { exit.request("close"); false } else true
        }),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(deviceTitle(destination), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = dismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.close))
                }
            }
            if (mountMotion.showsGlobalStop) {
                MountMotionStopBanner(
                    state = mountMotion,
                    onStop = viewModel::stopMountMotion,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Box(Modifier.weight(1f)) {
                if (editing) DeviceSettingsScreen(viewModel, destination.key, onBack = { editing = false }, exitState = exit)
                else DeviceDetailContent(
                    viewModel, destination,
                    onOpenCamera = { onDismiss(); onOpenCamera() },
                    onSettings = { editing = true }
                )
            }
        }
    }
}

@Composable
private fun deviceTitle(destination: DeviceControlDestination): String = stringResource(
    when (destination) {
        DeviceControlDestination.CAMERA -> R.string.tab_camera
        DeviceControlDestination.MOUNT -> R.string.tab_mount
        DeviceControlDestination.COOLER -> R.string.cooler
        DeviceControlDestination.FILTER -> R.string.filter_wheel
        DeviceControlDestination.FOCUSER -> R.string.focuser
        DeviceControlDestination.COVER -> R.string.cover_calibrator
        DeviceControlDestination.ROTATOR -> R.string.motorized_caa
        DeviceControlDestination.POWER -> R.string.power_management
    }
)

private data class DeviceSummary(val connected: Boolean, val identity: String, val usbId: Int? = null)

@Composable
private fun deviceSummary(viewModel: CameraViewModel, destination: DeviceControlDestination): DeviceSummary =
    when (destination) {
        DeviceControlDestination.CAMERA, DeviceControlDestination.COOLER -> {
            val state by viewModel.connectionState.collectAsStateWithPageLifecycle()
            val info = (state as? ConnectionState.Connected)?.info
            DeviceSummary(info != null, info?.name.orEmpty())
        }
        DeviceControlDestination.MOUNT -> {
            val state by viewModel.mountConnectionState.collectAsStateWithPageLifecycle()
            val info by viewModel.mountDetectedInfo.collectAsStateWithPageLifecycle()
            DeviceSummary(state is MountConnectionState.Connected, info.orEmpty())
        }
        DeviceControlDestination.FILTER -> {
            val connected by viewModel.filterWheelConnected.collectAsStateWithPageLifecycle()
            val info by viewModel.filterWheelInfo.collectAsStateWithPageLifecycle()
            val usbId by viewModel.activeFilterWheelDeviceId.collectAsStateWithPageLifecycle()
            DeviceSummary(connected, info?.name.orEmpty(), usbId)
        }
        DeviceControlDestination.FOCUSER -> {
            val connected by viewModel.eafConnected.collectAsStateWithPageLifecycle()
            val info by viewModel.eafInfo.collectAsStateWithPageLifecycle()
            val usbId by viewModel.activeFocuserDeviceId.collectAsStateWithPageLifecycle()
            DeviceSummary(connected, info?.name.orEmpty(), usbId)
        }
        DeviceControlDestination.COVER -> {
            val connected by viewModel.coverConnected.collectAsStateWithPageLifecycle()
            val info by viewModel.coverDeviceInfo.collectAsStateWithPageLifecycle()
            val usbId by viewModel.activeCoverDeviceId.collectAsStateWithPageLifecycle()
            DeviceSummary(connected, info.orEmpty(), usbId)
        }
        DeviceControlDestination.ROTATOR -> {
            val connected by viewModel.rotatorConnected.collectAsStateWithPageLifecycle()
            val info by viewModel.rotatorDeviceInfo.collectAsStateWithPageLifecycle()
            val usbId by viewModel.activeRotatorDeviceId.collectAsStateWithPageLifecycle()
            DeviceSummary(connected, info.orEmpty(), usbId)
        }
        DeviceControlDestination.POWER -> {
            val connected by viewModel.powerBoxConnected.collectAsStateWithPageLifecycle()
            val info by viewModel.powerBoxCapabilities.collectAsStateWithPageLifecycle()
            val usbId by viewModel.activePowerBoxDeviceId.collectAsStateWithPageLifecycle()
            DeviceSummary(connected, info?.identity.orEmpty(), usbId)
        }
    }

@Composable
private fun EquipmentList(viewModel: CameraViewModel, onOpen: (DeviceControlDestination) -> Unit) {
    val scanError by viewModel.accessoryScanError.collectAsStateWithPageLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.wf_equipment_title), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = viewModel::scanAccessories) {
                Icon(Icons.Default.Refresh, stringResource(R.string.scan))
            }
        }
        VirtualObservationEntry(viewModel)
        DeviceControlDestination.equipment.forEach { destination ->
            val summary = deviceSummary(viewModel, destination)
            Card(Modifier.fillMaxWidth().testTag("equipment-${destination.key}").clickable { onOpen(destination) }) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(deviceTitle(destination), style = MaterialTheme.typography.titleMedium)
                        if (summary.connected && summary.identity.isNotBlank()) {
                            Text(summary.identity, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            stringResource(if (summary.connected) R.string.connected else R.string.not_connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, stringResource(R.string.wf_device_details))
                }
            }
        }
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DeviceDetailContent(
    viewModel: CameraViewModel,
    destination: DeviceControlDestination,
    onOpenCamera: () -> Unit,
    onSettings: (() -> Unit)? = null
) {
    if (destination == DeviceControlDestination.MOUNT) {
        MountControlScreen(
            viewModel = viewModel, onOpenStarMap = {}, onOpenPolarAlignment = {},
            onOpenGuiding = {}, showObservingShortcuts = false
        )
    } else Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Keep each destination in a balanced composition branch. A labelled early
        // return from this inline Column corrupts Compose 1.5's subcomposed slot table.
        when (destination) {
            DeviceControlDestination.CAMERA, DeviceControlDestination.COOLER -> CameraDeviceContent(viewModel, onOpenCamera, onSettings)
            else -> AccessoryDeviceContent(viewModel, destination, onSettings)
        }
    }
}

@Composable
private fun AccessoryDeviceContent(viewModel: CameraViewModel, destination: DeviceControlDestination, onSettings: (() -> Unit)?) {
    val summary = deviceSummary(viewModel, destination)
    if (summary.connected) {
            DeviceIdentity(summary)
            when (destination) {
                DeviceControlDestination.FILTER -> FilterWheelControls(viewModel)
                DeviceControlDestination.FOCUSER -> FocuserControls(viewModel)
                DeviceControlDestination.COVER -> CoverControls(viewModel)
                DeviceControlDestination.POWER -> PowerBoxControls(viewModel, onSettings)
                DeviceControlDestination.ROTATOR -> RotatorControls(viewModel)
                else -> Unit
            }
            if (destination != DeviceControlDestination.POWER) onSettings?.let { TextButton(onClick = it) { Text(stringResource(R.string.wf_device_settings)) } }
            // Connection is secondary; opening/closing a page never disconnects the device.
            TextButton(onClick = {
                when (destination) {
                    DeviceControlDestination.FILTER -> viewModel.disconnectFilterWheel()
                    DeviceControlDestination.FOCUSER -> viewModel.disconnectEaf()
                    DeviceControlDestination.COVER -> viewModel.disconnectCover()
                    DeviceControlDestination.POWER -> viewModel.disconnectPowerBox()
                    DeviceControlDestination.ROTATOR -> viewModel.disconnectRotator()
                    else -> Unit
                }
            }) { Text(stringResource(R.string.disconnect)) }
    } else {
        TargetedAccessoryConnection(viewModel, destination)
    }
}

@Composable
private fun DeviceIdentity(summary: DeviceSummary) {
    if (summary.identity.isNotBlank()) Text(summary.identity, style = MaterialTheme.typography.titleMedium)
    summary.usbId?.let {
        Text(
            stringResource(R.string.wf_device_usb_identity, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TargetedAccessoryConnection(viewModel: CameraViewModel, destination: DeviceControlDestination) {
    val devices by viewModel.accessoryDevices.collectAsStateWithPageLifecycle()
    val scanError by viewModel.accessoryScanError.collectAsStateWithPageLifecycle()
    val filterId by viewModel.activeFilterWheelDeviceId.collectAsStateWithPageLifecycle()
    val focuserId by viewModel.activeFocuserDeviceId.collectAsStateWithPageLifecycle()
    val coverId by viewModel.activeCoverDeviceId.collectAsStateWithPageLifecycle()
    val rotatorId by viewModel.activeRotatorDeviceId.collectAsStateWithPageLifecycle()
    val powerId by viewModel.activePowerBoxDeviceId.collectAsStateWithPageLifecycle()
    val occupiedIds = listOfNotNull(filterId, focuserId, coverId, rotatorId, powerId).toSet()
    val candidates = devices.filter {
        it.usbDevice.deviceId !in occupiedIds &&
            DeviceConnectionRules.accepts(destination, it.type, it.serialRoles)
    }
    LaunchedEffect(destination) { viewModel.scanAccessories() }
    Row(
        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.not_connected), style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(onClick = viewModel::scanAccessories) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Text(stringResource(R.string.scan))
        }
    }
    if (candidates.isEmpty()) {
        Text(stringResource(R.string.wf_device_connect_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    candidates.forEach { device ->
        TargetedConnectionCard(device) {
            when (destination) {
                DeviceControlDestination.FILTER -> viewModel.connectAccessory(device)
                DeviceControlDestination.FOCUSER -> if (device.type == AccessoryType.FOCUSER) {
                    viewModel.connectAccessory(device)
                } else viewModel.connectEfucoser(device)
                DeviceControlDestination.COVER -> viewModel.connectCover(device)
                DeviceControlDestination.ROTATOR -> viewModel.connectRotator(device)
                DeviceControlDestination.POWER -> viewModel.connectPowerBox(device)
                else -> Unit
            }
        }
    }
    scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun TargetedConnectionCard(device: AccessoryDeviceEntry, onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(device.name, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.wf_device_usb_identity, device.usbDevice.deviceId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (device.probing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.width(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.serial_probing))
                }
            } else {
                if (device.type == AccessoryType.SERIAL_DEVICE && device.serialRoles.isNullOrEmpty()) {
                    Text(stringResource(R.string.wf_device_unrecognized), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onConnect) { Text(stringResource(R.string.connect)) }
            }
        }
    }
}

@Composable
private fun CameraDeviceContent(viewModel: CameraViewModel, onOpenCamera: () -> Unit, onSettings: (() -> Unit)?) {
    val state by viewModel.connectionState.collectAsStateWithPageLifecycle()
    val allDevices by viewModel.devices.collectAsStateWithPageLifecycle()
    LaunchedEffect(Unit) {
        if (state !is ConnectionState.Connected) viewModel.cameraManager.enumerateDevices()
    }
    val connected = state as? ConnectionState.Connected
    if (connected != null) {
        val info = connected.info
        Text(info.name, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.sn_label, info.serialNumber), style = MaterialTheme.typography.bodySmall)
        Text("${info.sensorWidth} × ${info.sensorHeight}", style = MaterialTheme.typography.bodySmall)
        CoolerControls(viewModel)
        CameraEnvironmentControls(viewModel)
        onSettings?.let { TextButton(onClick = it) { Text(stringResource(R.string.wf_device_settings)) } }
        Button(onClick = onOpenCamera) { Text(stringResource(R.string.wf_device_open_capture)) }
        TextButton(onClick = viewModel::disconnectCamera) { Text(stringResource(R.string.disconnect)) }
    } else {
        val connecting = state is ConnectionState.Connecting || state is ConnectionState.Enumerating
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(if (connecting) R.string.connecting else R.string.not_connected))
            OutlinedButton(
                onClick = { viewModel.cameraManager.enumerateDevices() }, enabled = !connecting
            ) { Text(stringResource(R.string.scan)) }
        }
        val candidates = viewModel.camerasAvailableToMain(allDevices)
        if (candidates.isEmpty() && !connecting) {
            Text(stringResource(if (allDevices.isNotEmpty()) R.string.camera_in_use_as_guide else R.string.no_camera_found))
        }
        candidates.forEach { camera ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(camera.name, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.sn_label, camera.serialNumber), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { viewModel.connectCameraBySn(camera.serialNumber) }, enabled = !connecting
                    ) { Text(stringResource(R.string.connect)) }
                }
            }
        }
        (state as? ConnectionState.Error)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun CoolerControls(viewModel: CameraViewModel) {
    val info by viewModel.coolingInfo.collectAsStateWithPageLifecycle()
    val on by viewModel.coolerOn.collectAsStateWithPageLifecycle()
    val sensor by viewModel.sensorTempTenths.collectAsStateWithPageLifecycle()
    val target by viewModel.targetTempTenths.collectAsStateWithPageLifecycle()
    val power by viewModel.coolingPowerPct.collectAsStateWithPageLifecycle()
    val rampStatus by viewModel.rampStatus.collectAsStateWithPageLifecycle()
    var draft by remember(target) { mutableStateOf((target / 10.0).toString()) }
    val capability = info
    if (capability == null || !capability.hasTec) {
        Text(stringResource(R.string.wf_device_cooler_unavailable), style = MaterialTheme.typography.bodySmall)
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wf_device_sensor_temperature, sensor / 10.0, power.toDouble()))
            SwitchSetting(stringResource(R.string.cooler), on, viewModel::setCoolerOn)
            if (capability.canSetTarget) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.target_temp_label)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                val parsed = draft.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { (it * 10).roundToInt() }
                Button(
                    onClick = { parsed?.let(viewModel::setTargetTemperature) },
                    enabled = parsed != null && parsed in capability.targetMinTenths..capability.targetMaxTenths
                ) { Text(stringResource(R.string.wf_device_apply_temperature)) }
            }
            if (rampStatus.isNotBlank()) Text(rampStatus, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Runtime environment controls remain immediate; defaults are edited in a separate draft. */
@Composable
private fun CameraEnvironmentControls(viewModel: CameraViewModel) {
    val heaterSupported by viewModel.heaterSupported.collectAsStateWithPageLifecycle()
    val heaterLevel by viewModel.heaterLevel.collectAsStateWithPageLifecycle()
    val heaterMaximum by viewModel.heaterMaxLevel.collectAsStateWithPageLifecycle()
    val fanSupported by viewModel.fanSupported.collectAsStateWithPageLifecycle()
    val fanLevel by viewModel.fanLevel.collectAsStateWithPageLifecycle()
    val fanMaximum by viewModel.fanMaxLevel.collectAsStateWithPageLifecycle()
    if (heaterSupported || fanSupported) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wf_device_runtime_hint), style = MaterialTheme.typography.bodySmall)
                if (heaterSupported && heaterMaximum > 0) EnvironmentLevelControl(stringResource(R.string.anti_dew_heater), heaterLevel, heaterMaximum, viewModel::setHeaterLevel)
                if (fanSupported && fanMaximum > 0) EnvironmentLevelControl(stringResource(R.string.fan_speed), fanLevel, fanMaximum, viewModel::setFanLevel)
            }
        }
    }
}

@Composable
private fun EnvironmentLevelControl(title: String, current: Int, maximum: Int, onChange: (Int) -> Unit) {
    var value by remember(current) { mutableStateOf(current.toFloat()) }
    if (maximum <= 1) SwitchSetting(title, current > 0) { onChange(if (it) 1 else 0) }
    else {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Slider(value = value, onValueChange = { value = it }, onValueChangeFinished = { onChange(value.roundToInt()) },
            valueRange = 0f..maximum.toFloat(), steps = (maximum - 1).coerceAtMost(100))
    }
}

@Composable
private fun FocuserControls(viewModel: CameraViewModel) {
    val position by viewModel.eafPosition.collectAsStateWithPageLifecycle()
    val moving by viewModel.eafMoving.collectAsStateWithPageLifecycle()
    val temperature by viewModel.eafTemperature.collectAsStateWithPageLifecycle()
    val info by viewModel.eafInfo.collectAsStateWithPageLifecycle()
    val deviceId by viewModel.activeFocuserDeviceId.collectAsStateWithPageLifecycle()
    val active = info
    if (active == null) {
        Text(stringResource(R.string.waiting_for_device_status))
        return
    }
    var target by rememberSaveable(active.name, deviceId) { mutableStateOf(position.toString()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pos_format, position, active.maxPosition))
            temperature?.let { Text("%.1f °C".format(it), style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                value = target, onValueChange = { target = it },
                label = { Text(stringResource(R.string.wf_device_focus_position)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            val parsed = target.toIntOrNull()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { parsed?.let(viewModel::eafMoveTo) }, modifier = Modifier.weight(1f),
                    enabled = !moving && parsed != null && parsed in active.minPosition..active.maxPosition
                ) { Text(stringResource(R.string.wf_device_move_to)) }
                OutlinedButton(onClick = viewModel::eafHalt) { Text(stringResource(R.string.stop)) }
            }
            listOf(active.fineStep, active.coarseStep).distinct().forEach { step ->
                Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.eafMoveRelative(-step) }, enabled = !moving,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("−$step") }
                    Text(stringResource(R.string.wf_device_focus_step, step), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { viewModel.eafMoveRelative(step) }, enabled = !moving,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("+$step") }
                }
            }
        }
    }
}

@Composable
private fun FilterWheelControls(viewModel: CameraViewModel) {
    val position by viewModel.filterWheelPosition.collectAsStateWithPageLifecycle()
    val moving by viewModel.filterWheelMoving.collectAsStateWithPageLifecycle()
    val names by viewModel.filterWheelSlotNames.collectAsStateWithPageLifecycle()
    val info by viewModel.filterWheelInfo.collectAsStateWithPageLifecycle()
    val active = info
    if (active == null || active.slotCount <= 0) {
        Text(stringResource(R.string.waiting_for_device_status))
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (moving) Text(stringResource(R.string.moving_suffix), style = MaterialTheme.typography.bodySmall)
            (0 until active.slotCount).chunked(2).forEach { slots ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    slots.forEach { slot ->
                        FilledTonalButton(
                            onClick = { viewModel.setFilterWheelPosition(slot) }, enabled = !moving,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = slot == position }
                        ) {
                            Text(if (slot == position) "● " + names.getOrElse(slot) { "${slot + 1}" } else names.getOrElse(slot) { "${slot + 1}" })
                        }
                    }
                }
            }
            TextButton(onClick = viewModel::resetFilterWheel, enabled = !moving) {
                Text(stringResource(R.string.reset))
            }
        }
    }
}

@Composable
private fun CoverControls(viewModel: CameraViewModel) {
    val coverState by viewModel.coverState.collectAsStateWithPageLifecycle()
    val calibratorState by viewModel.calibratorState.collectAsStateWithPageLifecycle()
    val brightness by viewModel.calibratorBrightness.collectAsStateWithPageLifecycle()
    val maxBrightness by viewModel.calibratorMaxBrightness.collectAsStateWithPageLifecycle()
    val info by viewModel.coverDeviceInfo.collectAsStateWithPageLifecycle()
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
private fun PowerBoxControls(viewModel: CameraViewModel, onSettings: (() -> Unit)?) {
    val capabilities by viewModel.powerBoxCapabilities.collectAsStateWithPageLifecycle()
    val telemetry by viewModel.powerBoxTelemetry.collectAsStateWithPageLifecycle()
    val customNames by viewModel.powerBoxInterfaceNames.collectAsStateWithPageLifecycle()
    PowerBoxPanel(
        capabilities = capabilities,
        telemetry = telemetry,
        customNames = customNames,
        onSetDcOutput = viewModel::setPowerBoxDcOutput,
        onSetUsbOutput = viewModel::setPowerBoxUsbOutput,
        onSetUsbMaster = viewModel::setPowerBoxUsbMaster,
        onSetDewEnabled = viewModel::setPowerBoxDewEnabled,
        onSetDewMode = viewModel::setPowerBoxDewMode,
        onSetDewPower = viewModel::setPowerBoxDewPower,
        onSaveInterfaceNames = viewModel::setPowerBoxInterfaceNames,
        onEditNames = onSettings
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RotatorControls(viewModel: CameraViewModel) {
    val angle by viewModel.rotatorAngle.collectAsStateWithPageLifecycle()
    val moving by viewModel.rotatorMoving.collectAsStateWithPageLifecycle()
    val position by viewModel.rotatorPositionSteps.collectAsStateWithPageLifecycle()
    val info by viewModel.rotatorDeviceInfo.collectAsStateWithPageLifecycle()
    var target by remember { mutableStateOf("0") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rotator_status, info.orEmpty(), angle, position, if (moving) stringResource(R.string.moving_suffix) else ""))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
