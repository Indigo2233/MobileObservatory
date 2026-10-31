package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.OpticsEquipment
import com.indigo.mobileobservatory.astro.SensorSpec
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolveOpticsFields(
    focalLengthText: String,
    onFocalLengthChange: (String) -> Unit,
    selectedSensorId: String,
    onSensorSelected: (SensorSpec) -> Unit,
    pixelSizeText: String,
    onPixelSizeChange: (String) -> Unit,
    sensors: List<SensorSpec>,
    computedFovHeightDeg: Double?,
    enabled: Boolean = true,
    compact: Boolean = false,
    automaticSensor: SensorSpec? = null
) {
    var menuOpen by remember(automaticSensor?.id) { mutableStateOf(false) }
    val selected = sensors.firstOrNull { it.id == selectedSensorId }
        ?: sensors.firstOrNull { it.id == OpticsEquipment.CUSTOM_SENSOR_ID }
    val showPixelField = selected == null ||
        selected.id == OpticsEquipment.CUSTOM_SENSOR_ID

    val focalField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = focalLengthText,
            onValueChange = onFocalLengthChange,
            label = { Text(stringResource(R.string.focal_length_mm)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            modifier = modifier.testTag("solve-focal")
        )
    }
    val pixelField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = if (showPixelField) pixelSizeText else formatPixelSizeUm(requireNotNull(selected).pixelSizeUm),
            onValueChange = onPixelSizeChange,
            readOnly = !showPixelField,
            label = { Text(stringResource(R.string.sensor_pixel_size_um)) },
            singleLine = true,
            enabled = enabled,
            textStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = modifier.testTag("solve-pixel")
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        if (compact && automaticSensor == null) SolveParameterPair(focalField, pixelField)
        else focalField(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 320.dp).fillMaxWidth())
        if (automaticSensor != null) {
            Text(
                stringResource(R.string.solve_automatic_sensor_summary, automaticSensor.name,
                    automaticSensor.pixelSizeUm, automaticSensor.widthPx, automaticSensor.heightPx),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag("solve-automatic-sensor")
            )
        } else {
            ExposedDropdownMenuBox(
                expanded = menuOpen,
                onExpandedChange = { if (enabled) menuOpen = it }
            ) {
                OutlinedTextField(
                    value = selected?.name ?: stringResource(R.string.sensor_custom),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.fov_sensor)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
                    textStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.menuAnchor()
                        .then(if (compact) Modifier else Modifier.widthIn(max = 320.dp))
                        .fillMaxWidth().testTag("solve-sensor")
                )
                ExposedDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    sensors.forEach { sensor ->
                        DropdownMenuItem(
                            text = { Text(sensor.name) },
                            onClick = {
                                onSensorSelected(sensor)
                                menuOpen = false
                            }
                        )
                    }
                }
            }
            if (!compact && showPixelField) {
                pixelField(Modifier.widthIn(max = 320.dp).fillMaxWidth())
            } else if (!compact && selected != null) {
                Text(
                    stringResource(
                        R.string.sensor_pixel_summary,
                        selected.pixelSizeUm,
                        selected.widthPx,
                        selected.heightPx
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (computedFovHeightDeg != null) {
            Text(
                stringResource(R.string.computed_field_height_deg, computedFovHeightDeg),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag("solve-fov")
            )
        } else if (focalLengthText.toDoubleOrNull()?.let { it.isFinite() && it > 0 } != true) {
            Text(
                stringResource(R.string.solve_need_focal_length),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        } else if (automaticSensor == null && (!compact ||
            (if (showPixelField) pixelSizeText.toDoubleOrNull() else selected?.pixelSizeUm)?.let { it.isFinite() && it > 0 } != true)) {
            Text(
                stringResource(R.string.plate_solve_need_optics),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
internal fun SolveParameterPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 280.dp && fontScale < 1.5f) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}

fun formatPixelSizeUm(value: Double): String = "%.3f".format(Locale.US, value)

fun pixelSizeForSensor(sensor: SensorSpec, typedPixelUm: Double?): Double? {
    return if (sensor.id == OpticsEquipment.CUSTOM_SENSOR_ID) {
        typedPixelUm?.takeIf { it.isFinite() && it > 0.0 }
    } else {
        sensor.pixelSizeUm.takeIf { it.isFinite() && it > 0.0 }
    }
}
