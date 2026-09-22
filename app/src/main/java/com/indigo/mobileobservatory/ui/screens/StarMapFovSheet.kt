@file:OptIn(ExperimentalMaterial3Api::class)

package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.EyepieceSpec
import com.indigo.mobileobservatory.astro.FovComputation
import com.indigo.mobileobservatory.astro.FovInstrumentMode
import com.indigo.mobileobservatory.astro.OpticsEquipment
import com.indigo.mobileobservatory.astro.OpticsTrainConfig
import com.indigo.mobileobservatory.astro.OpticsTrainId
import com.indigo.mobileobservatory.astro.SensorSpec
import com.indigo.mobileobservatory.astro.TelescopeSpec
import com.indigo.mobileobservatory.astro.UserOpticsCatalog
import java.util.Locale

/**
 * Stellarium Plus–style FOV simulator: pick telescope + eyepiece/sensor
 * for the selected optical train. The sky map stays visible behind the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarMapFovSheet(
    editingTrain: OpticsTrainId,
    config: OpticsTrainConfig,
    telescopes: List<TelescopeSpec>,
    eyepieces: List<EyepieceSpec>,
    sensors: List<SensorSpec>,
    showOverlay: Boolean,
    computation: FovComputation?,
    primaryTrainLabel: String,
    secondaryTrainLabel: String,
    onEditingTrainChange: (OpticsTrainId) -> Unit,
    onConfigChange: (OpticsTrainConfig) -> Unit,
    onShowOverlayChange: (Boolean) -> Unit,
    onTelescopesChange: (List<TelescopeSpec>) -> Unit,
    onCamerasChange: (List<SensorSpec>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val defaultTelescopeName = stringResource(R.string.star_map_train_primary)
            val defaultCameraName = stringResource(R.string.fov_named_camera)
            Text(
                stringResource(R.string.fov_simulator_title),
                style = MaterialTheme.typography.titleMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = editingTrain == OpticsTrainId.PRIMARY,
                    onClick = { onEditingTrainChange(OpticsTrainId.PRIMARY) },
                    label = { Text(primaryTrainLabel) }
                )
                FilterChip(
                    selected = editingTrain == OpticsTrainId.SECONDARY,
                    onClick = { onEditingTrainChange(OpticsTrainId.SECONDARY) },
                    label = { Text(secondaryTrainLabel) }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.mode == FovInstrumentMode.EYEPIECE,
                    onClick = { onConfigChange(config.copy(mode = FovInstrumentMode.EYEPIECE)) },
                    label = { Text(stringResource(R.string.fov_mode_eyepiece)) }
                )
                FilterChip(
                    selected = config.mode == FovInstrumentMode.SENSOR,
                    onClick = { onConfigChange(config.copy(mode = FovInstrumentMode.SENSOR)) },
                    label = { Text(stringResource(R.string.fov_mode_sensor)) }
                )
            }

            Text(
                stringResource(R.string.fov_telescope),
                style = MaterialTheme.typography.labelLarge
            )
            NamedEquipmentChips(
                names = telescopes.map { it.id to it.name },
                selectedId = config.telescopeId,
                onSelect = { id ->
                    val spec = telescopes.firstOrNull { it.id == id } ?: return@NamedEquipmentChips
                    onConfigChange(
                        config.copy(
                            telescopeId = spec.id,
                            customTelescopeFl = formatEquipmentNumber(spec.focalLengthMm)
                        )
                    )
                },
                onAdd = {
                    val selected = telescopes.firstOrNull { it.id == config.telescopeId }
                    val next = UserOpticsCatalog.addTelescope(
                        current = telescopes,
                        name = defaultTelescopeName,
                        focalLengthMm = selected?.focalLengthMm ?: 500.0,
                        apertureMm = selected?.apertureMm,
                        id = UserOpticsCatalog.newId("user_scope", System.currentTimeMillis())
                    )
                    val spec = next.last()
                    onTelescopesChange(next)
                    onConfigChange(
                        config.copy(
                            telescopeId = spec.id,
                            customTelescopeFl = formatEquipmentNumber(spec.focalLengthMm)
                        )
                    )
                },
                onDelete = {
                    val next = UserOpticsCatalog.removeTelescope(telescopes, config.telescopeId)
                    if (next === telescopes) return@NamedEquipmentChips
                    onTelescopesChange(next)
                    if (next.none { it.id == config.telescopeId }) {
                        val spec = next.first()
                        onConfigChange(
                            config.copy(
                                telescopeId = spec.id,
                                customTelescopeFl = formatEquipmentNumber(spec.focalLengthMm)
                            )
                        )
                    }
                },
                canDelete = telescopes.size > 1
            )
            val selectedScope = telescopes.firstOrNull { it.id == config.telescopeId }
            if (selectedScope != null) {
                TelescopeEditor(
                    spec = selectedScope,
                    onChange = { updated ->
                        onTelescopesChange(UserOpticsCatalog.replaceTelescope(telescopes, updated))
                        onConfigChange(
                            config.copy(
                                customTelescopeFl = formatEquipmentNumber(updated.focalLengthMm)
                            )
                        )
                    }
                )
            }

            when (config.mode) {
                FovInstrumentMode.EYEPIECE -> {
                    Text(
                        stringResource(R.string.fov_eyepiece),
                        style = MaterialTheme.typography.labelLarge
                    )
                    ChipRow {
                        eyepieces.forEach { ep ->
                            FilterChip(
                                selected = config.eyepieceId == ep.id,
                                onClick = { onConfigChange(config.copy(eyepieceId = ep.id)) },
                                label = { Text(ep.name) }
                            )
                        }
                    }
                    if (config.eyepieceId == "ep_custom") {
                        OutlinedTextField(
                            value = config.customEyepieceFl,
                            onValueChange = { onConfigChange(config.copy(customEyepieceFl = it)) },
                            label = { Text(stringResource(R.string.eyepiece_focal_length_mm)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = config.customEyepieceAfov,
                            onValueChange = { onConfigChange(config.copy(customEyepieceAfov = it)) },
                            label = { Text(stringResource(R.string.eyepiece_apparent_fov)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                FovInstrumentMode.SENSOR -> {
                    Text(
                        stringResource(R.string.fov_sensor),
                        style = MaterialTheme.typography.labelLarge
                    )
                    val libraryCameras = sensors.filter {
                        it.id != OpticsEquipment.CONNECTED_SENSOR_ID
                    }
                    NamedEquipmentChips(
                        names = sensors.map { it.id to it.name },
                        selectedId = config.sensorId,
                        onSelect = { id ->
                            onConfigChange(config.copy(sensorId = id))
                        },
                        onAdd = {
                            val selected = libraryCameras.firstOrNull { it.id == config.sensorId }
                            val next = UserOpticsCatalog.addCamera(
                                current = libraryCameras,
                                name = defaultCameraName,
                                pixelSizeUm = selected?.pixelSizeUm ?: 3.76,
                                widthPx = selected?.widthPx ?: 1920,
                                heightPx = selected?.heightPx ?: 1080,
                                id = UserOpticsCatalog.newId("user_cam", System.currentTimeMillis())
                            )
                            onCamerasChange(next)
                            onConfigChange(config.copy(sensorId = next.last().id))
                        },
                        onDelete = {
                            if (config.sensorId == OpticsEquipment.CONNECTED_SENSOR_ID) {
                                return@NamedEquipmentChips
                            }
                            val next = UserOpticsCatalog.removeCamera(libraryCameras, config.sensorId)
                            if (next === libraryCameras) return@NamedEquipmentChips
                            onCamerasChange(next)
                            val stillVisible = sensors.filter {
                                it.id == OpticsEquipment.CONNECTED_SENSOR_ID ||
                                    next.any { cam -> cam.id == it.id }
                            }
                            if (stillVisible.none { it.id == config.sensorId }) {
                                onConfigChange(
                                    config.copy(sensorId = stillVisible.firstOrNull()?.id ?: next.first().id)
                                )
                            }
                        },
                        canDelete = config.sensorId != OpticsEquipment.CONNECTED_SENSOR_ID &&
                            libraryCameras.size > 1
                    )
                    val selectedSensor = sensors.firstOrNull { it.id == config.sensorId }
                    if (selectedSensor != null) {
                        CameraEditor(
                            spec = selectedSensor,
                            locked = selectedSensor.id == OpticsEquipment.CONNECTED_SENSOR_ID,
                            onChange = { updated ->
                                onCamerasChange(
                                    UserOpticsCatalog.replaceCamera(
                                        libraryCameras,
                                        updated
                                    )
                                )
                                onConfigChange(
                                    config.copy(
                                        customSensorPixelUm = formatEquipmentNumber(updated.pixelSizeUm),
                                        customSensorWidth = updated.widthPx.toString(),
                                        customSensorHeight = updated.heightPx.toString()
                                    )
                                )
                            }
                        )
                    }
                }
            }

            LiveFovSummary(computation)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Switch(checked = showOverlay, onCheckedChange = onShowOverlayChange)
                Text(
                    stringResource(R.string.fov_show_overlay),
                    modifier = Modifier.weight(1f)
                )
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.close))
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        content()
    }
}

@Composable
private fun NamedEquipmentChips(
    names: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow {
            names.forEach { (id, name) ->
                FilterChip(
                    selected = selectedId == id,
                    onClick = { onSelect(id) },
                    label = { Text(name) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAdd) {
                Text(stringResource(R.string.fov_add_equipment))
            }
            TextButton(onClick = onDelete, enabled = canDelete) {
                Text(stringResource(R.string.fov_delete_equipment))
            }
        }
        if (!canDelete && selectedId == OpticsEquipment.CONNECTED_SENSOR_ID) {
            Text(
                stringResource(R.string.fov_connected_camera_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else if (!canDelete) {
            Text(
                stringResource(R.string.fov_keep_one_equipment),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun TelescopeEditor(
    spec: TelescopeSpec,
    onChange: (TelescopeSpec) -> Unit
) {
    var name by remember(spec.id, spec.name) { mutableStateOf(spec.name) }
    var focalLength by remember(spec.id) {
        mutableStateOf(formatEquipmentNumber(spec.focalLengthMm))
    }
    var aperture by remember(spec.id) {
        mutableStateOf(spec.apertureMm?.let(::formatEquipmentNumber).orEmpty())
    }
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            onChange(spec.copy(name = it))
        },
        label = { Text(stringResource(R.string.fov_equipment_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = focalLength,
        onValueChange = {
            focalLength = it
            val fl = it.toDoubleOrNull()
            if (fl != null && fl > 0.0) onChange(spec.copy(focalLengthMm = fl))
        },
        label = { Text(stringResource(R.string.focal_length_mm)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = aperture,
        onValueChange = {
            aperture = it
            val ap = it.toDoubleOrNull()
            onChange(spec.copy(apertureMm = ap?.takeIf { value -> value > 0.0 }))
        },
        label = { Text(stringResource(R.string.fov_aperture_mm)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CameraEditor(
    spec: SensorSpec,
    locked: Boolean,
    onChange: (SensorSpec) -> Unit
) {
    var name by remember(spec.id, spec.name) { mutableStateOf(spec.name) }
    var pixel by remember(spec.id) { mutableStateOf(formatEquipmentNumber(spec.pixelSizeUm)) }
    var width by remember(spec.id) { mutableStateOf(spec.widthPx.toString()) }
    var height by remember(spec.id) { mutableStateOf(spec.heightPx.toString()) }
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            onChange(spec.copy(name = it))
        },
        enabled = !locked,
        label = { Text(stringResource(R.string.fov_equipment_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = pixel,
        onValueChange = {
            pixel = it
            val um = it.toDoubleOrNull()
            if (um != null && um > 0.0) onChange(spec.copy(pixelSizeUm = um))
        },
        enabled = !locked,
        label = { Text(stringResource(R.string.sensor_pixel_size_um)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = width,
            onValueChange = {
                width = it
                val px = it.toIntOrNull()
                if (px != null && px > 0) onChange(spec.copy(widthPx = px))
            },
            enabled = !locked,
            label = { Text(stringResource(R.string.sensor_width_px)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = height,
            onValueChange = {
                height = it
                val px = it.toIntOrNull()
                if (px != null && px > 0) onChange(spec.copy(heightPx = px))
            },
            enabled = !locked,
            label = { Text(stringResource(R.string.sensor_height_px)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatEquipmentNumber(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.4f".format(Locale.US, value).trimEnd('0').trimEnd('.')
    }
}

@Composable
private fun LiveFovSummary(computation: FovComputation?) {
    val text = when {
        computation == null || !computation.hasOverlay ->
            stringResource(R.string.fov_summary_incomplete)
        computation.mode == FovInstrumentMode.EYEPIECE -> {
            val fov = computation.circleDeg!!
            val mag = computation.magnification
            if (mag != null) {
                stringResource(R.string.fov_summary_eyepiece, fov, mag)
            } else {
                stringResource(R.string.fov_summary_eyepiece_only, fov)
            }
        }
        else -> stringResource(
            R.string.fov_summary_sensor,
            computation.rectWidthDeg!!,
            computation.rectHeightDeg!!
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
        color = if (computation?.hasOverlay == true) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        }
    )
}
