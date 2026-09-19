package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    enabled: Boolean = true
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = sensors.firstOrNull { it.id == selectedSensorId }
        ?: sensors.firstOrNull { it.id == OpticsEquipment.CUSTOM_SENSOR_ID }
    val showPixelField = selected == null ||
        selected.id == OpticsEquipment.CUSTOM_SENSOR_ID

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = focalLengthText,
            onValueChange = onFocalLengthChange,
            label = { Text(stringResource(R.string.focal_length_mm)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
        )
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
                modifier = Modifier.menuAnchor().widthIn(max = 320.dp).fillMaxWidth()
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
        if (showPixelField) {
            OutlinedTextField(
                value = pixelSizeText,
                onValueChange = onPixelSizeChange,
                label = { Text(stringResource(R.string.sensor_pixel_size_um)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
            )
        } else if (selected != null) {
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
        if (computedFovHeightDeg != null) {
            Text(
                stringResource(R.string.computed_field_height_deg, computedFovHeightDeg),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            Text(
                stringResource(R.string.plate_solve_need_optics),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

fun formatPixelSizeUm(value: Double): String = "%.3f".format(Locale.US, value)

fun pixelSizeForSensor(sensor: SensorSpec, typedPixelUm: Double?): Double? {
    return if (sensor.id == OpticsEquipment.CUSTOM_SENSOR_ID) {
        typedPixelUm?.takeIf { it > 0.0 }
    } else {
        sensor.pixelSizeUm.takeIf { it > 0.0 }
    }
}
