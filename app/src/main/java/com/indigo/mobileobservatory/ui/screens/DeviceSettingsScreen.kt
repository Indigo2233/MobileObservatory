package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.ReadoutMode
import com.indigo.mobileobservatory.settings.CameraDefaults
import com.indigo.mobileobservatory.settings.CoverDefaults
import com.indigo.mobileobservatory.settings.FocuserDefaults
import com.indigo.mobileobservatory.accessories.power.PowerInterfaceNames
import com.indigo.mobileobservatory.ui.components.SettingsExitConfirmation
import com.indigo.mobileobservatory.ui.components.SettingsExitState
import com.indigo.mobileobservatory.ui.components.rememberSettingsExitState
import com.indigo.mobileobservatory.ui.devices.DeviceControlDestination
import com.indigo.mobileobservatory.ui.settings.SettingsDraft
import com.indigo.mobileobservatory.ui.settings.SettingsDraftViewModel
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel

internal data class SettingsField(
    val key: String,
    val label: String,
    val valid: (String) -> Boolean = { true },
    val range: String? = null,
    val choices: List<Pair<String, String>> = emptyList(),
    val toggle: Boolean = false,
    val numeric: Boolean = true
)

/** Kept separate from mutable device telemetry: the initialized draft owns its original identity. */
internal data class DeviceSettingsPresentation(
    val deviceKey: String,
    val title: String,
    val connected: Boolean,
    val values: Map<String, String>,
    val fields: List<SettingsField>,
    val hint: String? = null
)

@Composable
fun DeviceSettingsScreen(
    cameraViewModel: CameraViewModel,
    deviceKey: String,
    onBack: () -> Unit,
    exitState: SettingsExitState = rememberSettingsExitState()
) {
    val requested = DeviceControlDestination.fromKey(deviceKey) ?: DeviceControlDestination.CAMERA
    val destination = if (requested == DeviceControlDestination.COOLER) DeviceControlDestination.CAMERA else requested
    val motion by cameraViewModel.mountMotionState.collectAsState()
    val stop = stringResource(R.string.stop_mount)
    SideEffect { exitState.safetyAction = if (motion.showsGlobalStop) stop to cameraViewModel::stopMountMotion else null }
    val presentation = deviceSettingsPresentation(cameraViewModel, destination)
    val editor: SettingsDraftViewModel = viewModel(key = "device-settings-${destination.key}")
    SettingsDraftEditor(presentation, editor, exitState, onBack, extraControls = { usable ->
        if (destination == DeviceControlDestination.FOCUSER) FocuserZeroControl(cameraViewModel, usable)
    }) { snapshot, success, failure ->
        if (presentation.deviceKey != snapshot.deviceKey || !presentation.connected) {
            failure("device_changed")
        } else saveDeviceDraft(cameraViewModel, destination, snapshot, success, failure)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDraftEditor(
    presentation: DeviceSettingsPresentation,
    model: SettingsDraftViewModel,
    exit: SettingsExitState,
    onBack: () -> Unit,
    showToolbar: Boolean = true,
    extraControls: @Composable (Boolean) -> Unit = {},
    onSave: (SettingsDraft, (Map<String, String>) -> Unit, (String) -> Unit) -> Unit
) {
    val draft by model.draft.collectAsState()
    val saving by model.saving.collectAsState()
    val error by model.error.collectAsState()
    val saved by model.saved.collectAsState()
    com.indigo.mobileobservatory.ui.components.RegisterSettingsExitGuard(exit)
    DisposableEffect(model) {
        model.attachEditor()
        onDispose { model.detachEditor() }
    }
    LaunchedEffect(draft == null, presentation.deviceKey) {
        if (draft == null) model.initialize(presentation.deviceKey, presentation.values + ("_title" to presentation.title))
    }
    val active = draft
    val sameDevice = presentation.connected && active?.deviceKey == presentation.deviceKey
    val fields = presentation.fields.toMutableList()
    if (active?.fields?.containsKey("count") == true) {
        val count = active.fields["count"]?.toIntOrNull()?.takeIf { it in 1..16 } ?: 0
        repeat(count) { i -> if (fields.none { it.key == "name$i" }) fields += SettingsField("name$i", stringResource(R.string.slot_n, i + 1), numeric = false) }
    }
    val invalid = active?.let { d -> fields.filterNot { it.valid(d.fields[it.key].orEmpty()) }.map { it.key }.toSet() }.orEmpty()
    val saveFailed = stringResource(R.string.wf_edit_failed)
    val save: () -> Unit = {
        if (sameDevice && invalid.isEmpty()) {
            model.beginSave()?.let { snapshot ->
                onSave(snapshot, { applied -> model.completeSave(snapshot, applied) }, { detail ->
                    model.failSave(if (detail == "device_changed") detail else detail.ifBlank { saveFailed })
                })
            }
        }
    }
    SideEffect {
        exit.dirty = active?.dirty == true
        exit.canSave = sameDevice && invalid.isEmpty() && !saving
        exit.saving = saving
        exit.saveError = error
        exit.save = save
        exit.clearDraft = model::leaveEditor
        exit.register("back", onBack)
    }
    BackHandler { exit.request("back") }
    SettingsExitConfirmation(exit)
    Scaffold(topBar = { if (showToolbar) {
        TopAppBar(title = { Text(stringResource(R.string.wf_device_settings)) }, navigationIcon = {
            IconButton(onClick = { exit.request("back") }) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
        })
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(active?.original?.get("_title") ?: presentation.title, style = MaterialTheme.typography.titleMedium)
            if (!sameDevice && active != null) {
                Text(stringResource(R.string.wf_edit_device_changed), color = MaterialTheme.colorScheme.error)
                if (presentation.connected) TextButton(onClick = {
                    model.useForDevice(presentation.deviceKey, presentation.values + ("_title" to presentation.title))
                }, enabled = !saving) { Text(stringResource(R.string.wf_edit_use_current_device, presentation.title)) }
            }
            if (active != null) {
                fields.forEach { field ->
                    val value = active.fields[field.key].orEmpty()
                    when {
                        field.toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(field.label, Modifier.weight(1f))
                            Switch(value == "1", { model.edit(field.key, if (it) "1" else "0") }, enabled = !saving,
                                modifier = Modifier.testTag("device-setting-${field.key}"))
                        }
                        field.choices.isNotEmpty() -> {
                            Text(field.label, style = MaterialTheme.typography.titleSmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                field.choices.forEach { (key, label) -> FilterChip(selected = value == key,
                                    onClick = { model.edit(field.key, key) }, label = { Text(label) }, enabled = !saving) }
                            }
                        }
                        else -> OutlinedTextField(value = value, onValueChange = { model.edit(field.key, it) },
                            label = { Text(field.label) }, enabled = !saving, singleLine = true,
                            isError = field.key in invalid,
                            supportingText = { field.range?.let { range -> Text(stringResource(R.string.wf_edit_valid_range, range)) } },
                            keyboardOptions = KeyboardOptions(keyboardType = if (field.numeric) KeyboardType.Decimal else KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth().testTag("device-setting-${field.key}"))
                    }
                }
                presentation.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (invalid.isNotEmpty()) Text(stringResource(R.string.wf_edit_invalid), color = MaterialTheme.colorScheme.error)
                if (error != null) Text(if (error == "device_changed") stringResource(R.string.wf_edit_device_changed) else error.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (saved) Text(stringResource(R.string.wf_edit_saved), color = MaterialTheme.colorScheme.primary)
                Button(onClick = save, enabled = sameDevice && invalid.isEmpty() && !saving && active.dirty,
                    modifier = Modifier.fillMaxWidth().testTag("device-settings-save")) {
                    Text(stringResource(if (saving) R.string.wf_edit_saving else R.string.device_settings_apply))
                }
                extraControls(sameDevice && !saving)
            } else CircularProgressIndicator()
        }
    }
}

@Composable
private fun FocuserZeroControl(vm: CameraViewModel, usable: Boolean) {
    val moving by vm.eafMoving.collectAsState()
    val mountMotion by vm.mountMotionState.collectAsState()
    var confirm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, enabled = usable && !moving) { Text(stringResource(R.string.wf_focus_zero)) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.wf_focus_zero)) }, text = { Column {
            Text(stringResource(R.string.wf_focus_zero_confirm))
            if (mountMotion.showsGlobalStop) MountMotionStopBanner(mountMotion, vm::stopMountMotion)
        } },
        confirmButton = { TextButton(onClick = { vm.eafSetZero(); confirm = false }, enabled = usable && !moving) { Text(stringResource(R.string.wf_focus_zero)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}

private fun integerField(key: String, label: String, min: Int, max: Int = Int.MAX_VALUE, optional: Boolean = false) =
    SettingsField(key, label, valid = { (optional && it.isBlank()) || it.toIntOrNull()?.let { n -> n in min..max } == true }, range = if (max == Int.MAX_VALUE) "≥$min" else "$min–$max")

private fun floatField(key: String, label: String, min: Float, max: Float, optional: Boolean = false) =
    SettingsField(key, label, valid = { (optional && it.isBlank()) || it.toFloatOrNull()?.let { n -> n.isFinite() && n in min..max } == true }, range = "$min–$max")

@Composable
private fun deviceSettingsPresentation(vm: CameraViewModel, destination: DeviceControlDestination): DeviceSettingsPresentation = when (destination) {
    DeviceControlDestination.CAMERA, DeviceControlDestination.COOLER -> {
        val connection by vm.connectionState.collectAsState()
        val formats by vm.supportedPixelFormats.collectAsState()
        val modes by vm.supportedReadoutModes.collectAsState()
        val nativeModes by vm.nativeReadoutModes.collectAsState()
        val nativeCurrent by vm.nativeReadoutModeId.collectAsState()
        val currentMode by vm.readoutMode.collectAsState()
        val currentFormat by vm.pixelFormat.collectAsState()
        val gain by vm.gain.collectAsState()
        val gainCapability by vm.gainCapability.collectAsState()
        val offset by vm.offset.collectAsState()
        val offsetRange by vm.offsetRange.collectAsState()
        val offsetLabel by vm.offsetLabel.collectAsState()
        val info = (connection as? ConnectionState.Connected)?.info
        val defaults = vm.cameraDefaults()
        val fields = buildList {
            if (nativeModes.isNotEmpty()) add(SettingsField("native", stringResource(R.string.default_readout_mode),
                choices = nativeModes.map { it.id to it.displayName }, valid = { key -> nativeModes.any { it.id == key } }))
            else if (modes.isNotEmpty()) add(SettingsField("readout", stringResource(R.string.default_readout_mode),
                choices = modes.map { it.name to it.displayName }, valid = { key -> modes.any { it.name == key } }))
            gainCapability?.let { add(floatField("gain", it.label + it.unit?.let { unit -> " ($unit)" }.orEmpty(), it.min, it.max, optional = true)) }
            if (formats.isNotEmpty()) add(SettingsField("format", stringResource(R.string.default_pixel_format),
                choices = formats.map { it.name to it.displayName }, valid = { key -> formats.any { it.name == key } }))
            offsetRange?.let { add(floatField("offset", offsetLabel, it.min, it.max, optional = true)) }
        }
        val values = mapOf("readout" to (defaults?.readoutMode ?: currentMode).name,
            "native" to (defaults?.nativeReadoutModeId ?: nativeCurrent).orEmpty(),
            "format" to (defaults?.pixelFormat ?: currentFormat).name,
            "gain" to (defaults?.gain ?: gain).toString(), "offset" to (defaults?.offset ?: offset)?.toString().orEmpty())
        DeviceSettingsPresentation("camera:${info?.serialNumber}:${vm.cameraManager.claimedUsbDevice?.deviceName}", info?.name ?: stringResource(R.string.camera), info != null, values, fields)
    }
    DeviceControlDestination.FOCUSER -> {
        val info by vm.eafInfo.collectAsState()
        val connected by vm.eafConnected.collectAsState()
        val id by vm.activeFocuserDeviceId.collectAsState()
        val d = vm.focuserDefaults()
        DeviceSettingsPresentation("focuser:$id:${info?.name}", info?.name ?: stringResource(R.string.focuser), connected && info != null,
            focuserFields(d), listOf(
                integerField("fine", stringResource(R.string.fine_step), 1), integerField("coarse", stringResource(R.string.coarse_step), 1),
                integerField("max", stringResource(R.string.max_step), 1, optional = true), integerField("backlash", stringResource(R.string.overshoot_steps), 0),
                SettingsField("direction", stringResource(R.string.focuser_reverse_direction), toggle = true),
                SettingsField("backlashDirection", stringResource(R.string.backlash_direction), toggle = true)))
    }
    DeviceControlDestination.COVER -> {
        val info by vm.coverDeviceInfo.collectAsState()
        val connected by vm.coverConnected.collectAsState()
        val id by vm.activeCoverDeviceId.collectAsState()
        val maximum by vm.calibratorMaxBrightness.collectAsState()
        val d = vm.coverDefaults()
        DeviceSettingsPresentation("cover:$id:$info", info ?: stringResource(R.string.cover), connected,
            coverFields(d), listOf(integerField("open", stringResource(R.string.cover_open_angle), 0, 360),
                integerField("closed", stringResource(R.string.cover_closed_angle), 0, 360), integerField("working", stringResource(R.string.cover_working_angle), 0, 360),
                integerField("brightness", stringResource(R.string.default_brightness), 0, maximum.coerceAtLeast(0), optional = true)),
            hint = stringResource(R.string.cover_angle_capability_hint))
    }
    DeviceControlDestination.FILTER -> {
        val info by vm.filterWheelInfo.collectAsState()
        val connected by vm.filterWheelConnected.collectAsState()
        val id by vm.activeFilterWheelDeviceId.collectAsState()
        val names by vm.filterWheelSlotNames.collectAsState()
        val bidirectional by vm.filterWheelBidirectional.collectAsState()
        val values = mapOf("count" to (info?.slotCount ?: names.size).toString(), "bidirectional" to if (bidirectional) "1" else "0") + names.mapIndexed { i, name -> "name$i" to name }
        DeviceSettingsPresentation("filter:$id:${info?.name}", info?.name ?: stringResource(R.string.filter_wheel), connected,
            values, listOf(integerField("count", stringResource(R.string.slot_count), 1, 16), SettingsField("bidirectional", stringResource(R.string.bidirectional), toggle = true)) +
                names.mapIndexed { i, _ -> SettingsField("name$i", stringResource(R.string.slot_n, i + 1), valid = { it.length <= 64 }, numeric = false) })
    }
    DeviceControlDestination.ROTATOR -> {
        val info by vm.rotatorDeviceInfo.collectAsState()
        val connected by vm.rotatorConnected.collectAsState()
        val id by vm.activeRotatorDeviceId.collectAsState()
        val scale by vm.rotatorStepsPerDegree.collectAsState()
        val reversed by vm.rotatorReversed.collectAsState()
        val hold by vm.rotatorHold.collectAsState()
        val supportsHold by vm.rotatorSupportsHold.collectAsState()
        DeviceSettingsPresentation("rotator:$id:$info", info ?: stringResource(R.string.motorized_caa), connected,
            mapOf("scale" to scale.toString(), "reverse" to if (reversed) "1" else "0", "hold" to if (hold) "1" else "0"),
            listOf(integerField("scale", stringResource(R.string.steps_per_degree), 1), SettingsField("reverse", stringResource(R.string.reverse), toggle = true)) +
                if (supportsHold) listOf(SettingsField("hold", stringResource(R.string.motor_hold), toggle = true)) else emptyList())
    }
    DeviceControlDestination.POWER -> {
        val connected by vm.powerBoxConnected.collectAsState()
        val info by vm.powerBoxCapabilities.collectAsState()
        val names by vm.powerBoxInterfaceNames.collectAsState()
        val id by vm.activePowerBoxDeviceId.collectAsState()
        val targets = buildList {
            info?.dcOutputs?.forEach { add(PowerInterfaceNames.dc(it.commandAddress) to stringResource(R.string.dc_output_number, it.commandAddress)) }
            info?.usbMasterOutput?.let { add(PowerInterfaceNames.usbMaster(it.commandAddress) to stringResource(R.string.usb_master_output)) }
            info?.usbOutputs?.forEach { add(PowerInterfaceNames.usb(it.commandAddress) to stringResource(R.string.usb_output_number, it.index + 1)) }
            info?.dewHeaters?.forEach { add(PowerInterfaceNames.dew(it.index + 1) to stringResource(R.string.dew_heater_number, it.index + 1)) }
        }
        DeviceSettingsPresentation("power:$id:${info?.identity}", info?.identity ?: stringResource(R.string.power_management), connected && info != null,
            targets.associate { it.first to names[it.first].orEmpty() },
            targets.map { SettingsField(it.first, it.second, valid = { value -> value.length <= PowerInterfaceNames.MAX_LENGTH }, numeric = false) })
    }
    else -> DeviceSettingsPresentation(destination.key, "", false, emptyMap(), emptyList())
}

private fun focuserFields(d: FocuserDefaults) = mapOf("fine" to d.fineStep.toString(), "coarse" to d.coarseStep.toString(),
    "max" to d.maxStep?.toString().orEmpty(), "direction" to d.direction.toString(), "backlash" to d.backlashSteps.toString(), "backlashDirection" to d.backlashDirection.toString())
private fun coverFields(d: CoverDefaults) = mapOf("open" to d.openAngle.toString(), "closed" to d.closedAngle.toString(), "working" to d.workingAngle.toString(), "brightness" to d.brightness?.toString().orEmpty())

private fun saveDeviceDraft(vm: CameraViewModel, destination: DeviceControlDestination, draft: SettingsDraft,
    success: (Map<String, String>) -> Unit, failure: (String) -> Unit) {
    val currentKey = when (destination) {
        DeviceControlDestination.CAMERA, DeviceControlDestination.COOLER -> "camera:${(vm.connectionState.value as? ConnectionState.Connected)?.info?.serialNumber}:${vm.cameraManager.claimedUsbDevice?.deviceName}"
        DeviceControlDestination.FOCUSER -> "focuser:${vm.activeFocuserDeviceId.value}:${vm.eafInfo.value?.name}"
        DeviceControlDestination.COVER -> "cover:${vm.activeCoverDeviceId.value}:${vm.coverDeviceInfo.value}"
        DeviceControlDestination.FILTER -> "filter:${vm.activeFilterWheelDeviceId.value}:${vm.filterWheelInfo.value?.name}"
        DeviceControlDestination.ROTATOR -> "rotator:${vm.activeRotatorDeviceId.value}:${vm.rotatorDeviceInfo.value}"
        DeviceControlDestination.POWER -> "power:${vm.activePowerBoxDeviceId.value}:${vm.powerBoxCapabilities.value?.identity}"
        else -> destination.key
    }
    if (currentKey != draft.deviceKey) { failure("device_changed"); return }
    val f = draft.fields
    val onFailure: (Throwable) -> Unit = { failure(it.message.orEmpty()) }
    try {
        when (destination) {
            DeviceControlDestination.CAMERA, DeviceControlDestination.COOLER -> vm.saveCameraDefaults(CameraDefaults(
                readoutMode = ReadoutMode.entries.firstOrNull { it.name == f["readout"] },
                nativeReadoutModeId = f["native"]?.ifBlank { null }, pixelFormat = PixelFormat.entries.firstOrNull { it.name == f["format"] },
                gain = f["gain"]?.toFloatOrNull(), offset = f["offset"]?.toFloatOrNull()),
                onSaved = { applied -> success(f + mapOf("gain" to applied.gain?.toString().orEmpty(), "offset" to applied.offset?.toString().orEmpty(),
                    "format" to applied.pixelFormat?.name.orEmpty(), "readout" to applied.readoutMode?.name.orEmpty(), "native" to applied.nativeReadoutModeId.orEmpty())) }, onFailure = onFailure)
            DeviceControlDestination.FOCUSER -> {
                val d = FocuserDefaults(f.getValue("fine").toInt(), f.getValue("coarse").toInt(), f["max"]?.toIntOrNull(),
                    f.getValue("direction").toInt(), f.getValue("backlash").toInt(), f.getValue("backlashDirection").toInt())
                vm.saveFocuserDefaults(d, onSaved = { success(f + focuserFields(d)) }, onFailure = onFailure)
            }
            DeviceControlDestination.COVER -> {
                val d = CoverDefaults(f.getValue("open").toInt(), f.getValue("closed").toInt(), f.getValue("working").toInt(), f["brightness"]?.toIntOrNull())
                vm.saveCoverDefaults(d, onSaved = { success(f + coverFields(d)) }, onFailure = onFailure)
            }
            DeviceControlDestination.FILTER -> {
                val count = f.getValue("count").toInt()
                if (count != vm.filterWheelInfo.value?.slotCount) vm.setFilterWheelSlotCount(count)
                repeat(count) { vm.setFilterWheelSlotName(it, f["name$it"].orEmpty().ifBlank { "${it + 1}" }) }
                vm.setFilterWheelBidirectional(f["bidirectional"] == "1")
                success(f + (0 until count).associate { "name$it" to f["name$it"].orEmpty().ifBlank { "${it + 1}" } })
            }
            DeviceControlDestination.ROTATOR -> {
                vm.setRotatorStepsPerDegree(f.getValue("scale").toInt())
                vm.setRotatorReversed(f["reverse"] == "1")
                if (vm.rotatorSupportsHold.value) vm.setRotatorHold(f["hold"] == "1")
                success(f)
            }
            DeviceControlDestination.POWER -> {
                val names = PowerInterfaceNames.normalize(f)
                vm.setPowerBoxInterfaceNames(names)
                success(f.mapValues { (key, value) -> if (key == "_title") value else names[key].orEmpty() })
            }
            else -> failure("device_changed")
        }
    } catch (error: Throwable) { onFailure(error) }
}
