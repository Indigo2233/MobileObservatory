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
import com.indigo.mobileobservatory.astro.SensorSpec
import com.indigo.mobileobservatory.astro.TelescopeSpec

/**
 * Stellarium Plus–style FOV simulator: pick telescope + eyepiece/sensor;
 * the star map keeps showing behind the sheet so overlays update live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarMapFovSheet(
    mode: FovInstrumentMode,
    telescopes: List<TelescopeSpec>,
    eyepieces: List<EyepieceSpec>,
    sensors: List<SensorSpec>,
    selectedTelescopeId: String,
    selectedEyepieceId: String,
    selectedSensorId: String,
    customTelescopeFl: String,
    customEyepieceFl: String,
    customEyepieceAfov: String,
    customSensorPixelUm: String,
    customSensorWidth: String,
    customSensorHeight: String,
    showOverlay: Boolean,
    computation: FovComputation?,
    onModeChange: (FovInstrumentMode) -> Unit,
    onTelescopeSelected: (String) -> Unit,
    onEyepieceSelected: (String) -> Unit,
    onSensorSelected: (String) -> Unit,
    onCustomTelescopeFl: (String) -> Unit,
    onCustomEyepieceFl: (String) -> Unit,
    onCustomEyepieceAfov: (String) -> Unit,
    onCustomSensorPixelUm: (String) -> Unit,
    onCustomSensorWidth: (String) -> Unit,
    onCustomSensorHeight: (String) -> Unit,
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
            Text(
                stringResource(R.string.fov_simulator_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == FovInstrumentMode.EYEPIECE,
                    onClick = { onModeChange(FovInstrumentMode.EYEPIECE) },
                    label = { Text(stringResource(R.string.fov_mode_eyepiece)) }
                )
                FilterChip(
                    selected = mode == FovInstrumentMode.SENSOR,
                    onClick = { onModeChange(FovInstrumentMode.SENSOR) },
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
                        selected = selectedTelescopeId == scope.id,
                        onClick = { onTelescopeSelected(scope.id) },
                        label = { Text(scope.name) }
                    )
                }
            }
            if (selectedTelescopeId == "scope_custom") {
                OutlinedTextField(
                    value = customTelescopeFl,
                    onValueChange = onCustomTelescopeFl,
                    label = { Text(stringResource(R.string.focal_length_mm)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            when (mode) {
                FovInstrumentMode.EYEPIECE -> {
                    Text(
                        stringResource(R.string.fov_eyepiece),
                        style = MaterialTheme.typography.labelLarge
                    )
                    ChipRow {
                        eyepieces.forEach { ep ->
                            FilterChip(
                                selected = selectedEyepieceId == ep.id,
                                onClick = { onEyepieceSelected(ep.id) },
                                label = { Text(ep.name) }
                            )
                        }
                    }
                    if (selectedEyepieceId == "ep_custom") {
                        OutlinedTextField(
                            value = customEyepieceFl,
                            onValueChange = onCustomEyepieceFl,
                            label = { Text(stringResource(R.string.eyepiece_focal_length_mm)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customEyepieceAfov,
                            onValueChange = onCustomEyepieceAfov,
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
                                selected = selectedSensorId == sensor.id,
                                onClick = { onSensorSelected(sensor.id) },
                                label = { Text(sensor.name) }
                            )
                        }
                    }
                    if (selectedSensorId == "ccd_custom") {
                        OutlinedTextField(
                            value = customSensorPixelUm,
                            onValueChange = onCustomSensorPixelUm,
                            label = { Text(stringResource(R.string.sensor_pixel_size_um)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customSensorWidth,
                                onValueChange = onCustomSensorWidth,
                                label = { Text(stringResource(R.string.sensor_width_px)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = customSensorHeight,
                                onValueChange = onCustomSensorHeight,
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

internal fun resolveTelescopeFl(
    telescopes: List<TelescopeSpec>,
    selectedId: String,
    customFl: String
): Double? {
    val selected = telescopes.firstOrNull { it.id == selectedId } ?: return null
    return if (selected.id == "scope_custom") {
        customFl.toDoubleOrNull()
    } else {
        selected.focalLengthMm
    }
}

internal fun resolveEyepiece(
    eyepieces: List<EyepieceSpec>,
    selectedId: String,
    customFl: String,
    customAfov: String
): EyepieceSpec? {
    val selected = eyepieces.firstOrNull { it.id == selectedId } ?: return null
    if (selected.id != "ep_custom") return selected
    val fl = customFl.toDoubleOrNull() ?: return null
    val afov = customAfov.toDoubleOrNull() ?: return null
    return selected.copy(focalLengthMm = fl, apparentFovDeg = afov)
}

internal fun resolveSensor(
    sensors: List<SensorSpec>,
    selectedId: String,
    customPixelUm: String,
    customWidth: String,
    customHeight: String
): SensorSpec? {
    val selected = sensors.firstOrNull { it.id == selectedId } ?: return null
    if (selected.id != "ccd_custom") return selected
    val px = customPixelUm.toDoubleOrNull() ?: return null
    val w = customWidth.toIntOrNull() ?: return null
    val h = customHeight.toIntOrNull() ?: return null
    return selected.copy(pixelSizeUm = px, widthPx = w, heightPx = h)
}
