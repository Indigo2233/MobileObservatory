package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.indigo.mobileobservatory.accessories.power.DewHeaterControlType
import com.indigo.mobileobservatory.accessories.power.DewHeaterMode
import com.indigo.mobileobservatory.accessories.power.GeminiPowerCapabilities
import com.indigo.mobileobservatory.accessories.power.GeminiPowerTelemetry
import com.indigo.mobileobservatory.accessories.power.PowerInterfaceNames

private data class PowerInterfaceNameTarget(
    val key: String,
    val defaultName: String
)

@Composable
fun PowerBoxPanel(
    capabilities: GeminiPowerCapabilities?,
    telemetry: GeminiPowerTelemetry?,
    customNames: Map<String, String>,
    onSetDcOutput: (Int, Boolean) -> Unit,
    onSetUsbOutput: (Int, Boolean) -> Unit,
    onSetUsbMaster: (Boolean) -> Unit,
    onSetDewEnabled: (Int, Boolean) -> Unit,
    onSetDewMode: (Int, DewHeaterMode) -> Unit,
    onSetDewPower: (Int, Int) -> Unit,
    onSaveInterfaceNames: (Map<String, String>) -> Unit
) {
    val discovered = capabilities
    val live = telemetry
    if (discovered == null || live == null) {
        Text(stringResource(R.string.waiting_for_device_status))
        return
    }

    var showNameEditor by rememberSaveable(discovered.identity) { mutableStateOf(false) }
    val interfaceNameTargets = buildList {
        discovered.dcOutputs.forEach { output ->
            add(
                PowerInterfaceNameTarget(
                    PowerInterfaceNames.dc(output.commandAddress),
                    stringResource(R.string.dc_output_number, output.commandAddress)
                )
            )
        }
        discovered.usbMasterOutput?.let { output ->
            add(
                PowerInterfaceNameTarget(
                    PowerInterfaceNames.usbMaster(output.commandAddress),
                    stringResource(R.string.usb_master_output)
                )
            )
        }
        discovered.usbOutputs.forEach { output ->
            add(
                PowerInterfaceNameTarget(
                    PowerInterfaceNames.usb(output.commandAddress),
                    stringResource(R.string.usb_output_number, output.index + 1)
                )
            )
        }
        discovered.dewHeaters.forEach { heater ->
            add(
                PowerInterfaceNameTarget(
                    PowerInterfaceNames.dew(heater.index + 1),
                    stringResource(R.string.dew_heater_number, heater.index + 1)
                )
            )
        }
    }

    if (showNameEditor) {
        PowerInterfaceNamesDialog(
            targets = interfaceNameTargets,
            customNames = customNames,
            onSave = {
                onSaveInterfaceNames(it)
                showNameEditor = false
            },
            onDismiss = { showNameEditor = false }
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(discovered.identity, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.firmware_value, discovered.firmware))
            Text(
                stringResource(
                    R.string.power_discovered_scope,
                    discovered.dcOutputs.size,
                    discovered.controllableUsbGroupCount,
                    discovered.dewHeaters.size
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { showNameEditor = true }) {
                Text(stringResource(R.string.edit_interface_names))
            }
            if (live.inputVoltageV != null &&
                live.outputCurrentA != null &&
                live.outputPowerW != null
            ) {
                Text(
                    stringResource(
                        R.string.power_electrical_values,
                        live.inputVoltageV,
                        live.outputCurrentA,
                        live.outputPowerW
                    )
                )
            }
        }
    }

    if (discovered.hasAmbientSensor || discovered.hasDeviceTemperatureSensor) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.environment), style = MaterialTheme.typography.titleMedium)
                if (discovered.hasDeviceTemperatureSensor) {
                    live.deviceTemperatureC?.let {
                        Text(stringResource(R.string.device_temperature_value, it))
                    }
                }
                if (discovered.hasAmbientSensor) {
                    live.ambientTemperatureC?.let {
                        Text(stringResource(R.string.ambient_temperature_value, it))
                    }
                    live.humidityPercent?.let {
                        Text(stringResource(R.string.humidity_value, it))
                    }
                    live.dewPointC?.let {
                        Text(stringResource(R.string.dew_point_value, it))
                    }
                }
            }
        }
    }

    if (discovered.dcOutputs.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.dc_outputs), style = MaterialTheme.typography.titleMedium)
                discovered.dcOutputs.forEach { output ->
                    val defaultName = stringResource(R.string.dc_output_number, output.commandAddress)
                    PowerSwitchSetting(
                        label = customNames[PowerInterfaceNames.dc(output.commandAddress)]
                            ?: defaultName,
                        checked = live.dcOutputs.getOrElse(output.index) { false },
                        onCheckedChange = { onSetDcOutput(output.index, it) }
                    )
                }
            }
        }
    }

    if (discovered.usbMasterOutput != null || discovered.usbOutputs.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.usb_outputs), style = MaterialTheme.typography.titleMedium)
                discovered.usbMasterOutput?.let {
                    PowerSwitchSetting(
                        label = customNames[PowerInterfaceNames.usbMaster(it.commandAddress)]
                            ?: stringResource(R.string.usb_master_output),
                        checked = live.usbMasterEnabled == true,
                        onCheckedChange = onSetUsbMaster
                    )
                }
                discovered.usbOutputs.forEach { output ->
                    val defaultName = stringResource(R.string.usb_output_number, output.index + 1)
                    PowerSwitchSetting(
                        label = customNames[PowerInterfaceNames.usb(output.commandAddress)]
                            ?: defaultName,
                        checked = live.usbOutputs.getOrElse(output.index) { false },
                        onCheckedChange = { onSetUsbOutput(output.index, it) }
                    )
                }
            }
        }
    }

    discovered.dewHeaters.forEach { capability ->
        val heater = live.dewHeaters.getOrNull(capability.index) ?: return@forEach
        var pendingPower by remember(capability.index, heater.outputValue) {
            mutableStateOf(heater.outputValue.toFloat())
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    customNames[PowerInterfaceNames.dew(capability.index + 1)]
                        ?: stringResource(R.string.dew_heater_number, capability.index + 1),
                    style = MaterialTheme.typography.titleMedium
                )
                if (capability.supportedModes.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        capability.supportedModes.forEach { mode ->
                            val selected = heater.mode == mode
                            val label = when (mode) {
                                DewHeaterMode.AUTOMATIC -> stringResource(R.string.dew_mode_automatic)
                                DewHeaterMode.MANUAL_PWM -> stringResource(R.string.dew_mode_manual)
                                DewHeaterMode.BINARY_SWITCH -> stringResource(R.string.dew_mode_switch)
                            }
                            if (selected) {
                                Button(onClick = {}) { Text(label) }
                            } else {
                                OutlinedButton(
                                    onClick = { onSetDewMode(capability.index, mode) }
                                ) { Text(label) }
                            }
                        }
                    }
                }
                when (capability.controlType(heater.mode)) {
                    DewHeaterControlType.MANUAL_VALUE -> {
                        Text(
                            stringResource(
                                R.string.dew_manual_output_value,
                                pendingPower.toInt(),
                                capability.manualOutputMaximum
                            )
                        )
                        Slider(
                            value = pendingPower,
                            onValueChange = { pendingPower = it },
                            onValueChangeFinished = {
                                onSetDewPower(capability.index, pendingPower.toInt())
                            },
                            valueRange = 0f..capability.manualOutputMaximum.toFloat(),
                            steps = (capability.manualOutputMaximum - 1).coerceAtLeast(0)
                        )
                    }
                    DewHeaterControlType.ENABLED_SWITCH -> PowerSwitchSetting(
                        label = stringResource(R.string.dew_output_switch),
                        checked = heater.enabled,
                        onCheckedChange = { onSetDewEnabled(capability.index, it) }
                    )
                    DewHeaterControlType.STATUS_ONLY -> Unit
                }
            }
        }
    }
}

@Composable
private fun PowerInterfaceNamesDialog(
    targets: List<PowerInterfaceNameTarget>,
    customNames: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    var editedNames by remember(targets, customNames) {
        mutableStateOf(targets.associate { it.key to customNames[it.key].orEmpty() })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_interface_names)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.interface_name_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                targets.forEach { target ->
                    OutlinedTextField(
                        value = editedNames[target.key].orEmpty(),
                        onValueChange = { value ->
                            editedNames = editedNames +
                                (target.key to value.take(PowerInterfaceNames.MAX_LENGTH))
                        },
                        label = { Text(target.defaultName) },
                        placeholder = { Text(target.defaultName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(editedNames) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PowerSwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
