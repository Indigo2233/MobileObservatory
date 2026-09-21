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
    onEditingTrainChange: (OpticsTrainId) -> Unit,
    onConfigChange: (OpticsTrainConfig) -> Unit,
    onShowOverlayChange: (Boolean) -> Unit,
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
            Text(
                stringResource(R.string.fov_simulator_title),
                style = MaterialTheme.typography.titleMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = editingTrain == OpticsTrainId.PRIMARY,
                    onClick = { onEditingTrainChange(OpticsTrainId.PRIMARY) },
                    label = { Text(stringResource(R.string.star_map_train_primary)) }
                )
                FilterChip(
                    selected = editingTrain == OpticsTrainId.SECONDARY,
                    onClick = { onEditingTrainChange(OpticsTrainId.SECONDARY) },
                    label = { Text(stringResource(R.string.star_map_train_secondary)) }
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
            ChipRow {
                telescopes.forEach { scope ->
                    FilterChip(
                        selected = config.telescopeId == scope.id,
                        onClick = { onConfigChange(config.copy(telescopeId = scope.id)) },
                        label = { Text(scope.name) }
                    )
                }
            }
            if (config.telescopeId == "scope_custom") {
                OutlinedTextField(
                    value = config.customTelescopeFl,
                    onValueChange = { onConfigChange(config.copy(customTelescopeFl = it)) },
                    label = { Text(stringResource(R.string.focal_length_mm)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
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
                    ChipRow {
                        sensors.forEach { sensor ->
                            FilterChip(
                                selected = config.sensorId == sensor.id,
                                onClick = { onConfigChange(config.copy(sensorId = sensor.id)) },
                                label = { Text(sensor.name) }
                            )
                        }
                    }
                    if (config.sensorId == OpticsEquipment.CUSTOM_SENSOR_ID) {
                        OutlinedTextField(
                            value = config.customSensorPixelUm,
                            onValueChange = { onConfigChange(config.copy(customSensorPixelUm = it)) },
                            label = { Text(stringResource(R.string.sensor_pixel_size_um)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = config.customSensorWidth,
                                onValueChange = { onConfigChange(config.copy(customSensorWidth = it)) },
                                label = { Text(stringResource(R.string.sensor_width_px)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = config.customSensorHeight,
                                onValueChange = { onConfigChange(config.copy(customSensorHeight = it)) },
                                label = { Text(stringResource(R.string.sensor_height_px)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
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
