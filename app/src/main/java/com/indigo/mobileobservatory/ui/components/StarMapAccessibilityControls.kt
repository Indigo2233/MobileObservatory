package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R

@Composable
fun StarMapBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            Icons.Default.ArrowBack,
            contentDescription = stringResource(R.string.back_to_mount)
        )
    }
}

@Composable
fun StarMapGotoConfirmation(
    targetName: String,
    coordinates: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_mount_slew)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(targetName)
                Text(coordinates)
                Text(stringResource(R.string.mount_slew_safety_warning))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.execute_goto))
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
fun StarMapSyncConfirmation(
    targetName: String,
    coordinates: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_mount_sync)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(targetName)
                Text(coordinates)
                Text(stringResource(R.string.mount_sync_warning))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.execute_sync))
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
fun StarMapPrecisionGotoConfirmation(
    targetName: String,
    coordinates: String,
    toleranceArcmin: Double,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE) }
    var exposure by rememberSaveable { mutableStateOf((prefs.getFloat("precision_exposure_us", 5_000_000f) / 1_000_000f).toString()) }
    var radius by rememberSaveable { mutableStateOf(prefs.getFloat("precision_search_radius_deg", 30f).toString()) }
    var gain by rememberSaveable { mutableStateOf(if (prefs.contains("precision_gain")) prefs.getFloat("precision_gain", 0f).toString() else "") }
    var focal by rememberSaveable { mutableStateOf(prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }?.toString().orEmpty()) }
    var blind by rememberSaveable { mutableStateOf(prefs.getBoolean("precision_blind_fallback", true)) }
    val exposureUs = exposure.replace(',', '.').toFloatOrNull()?.times(1_000_000f)?.takeIf { it.isFinite() && it > 0 }
    val searchRadius = radius.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in .1f..180f }
    val gainValue = gain.toFloatOrNull()?.takeIf { it.isFinite() }
    val focalValue = focal.toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }
    val valid = exposureUs != null && searchRadius != null && (gain.isBlank() || gainValue != null) && focalValue != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_precision_goto)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(targetName)
                Text(coordinates)
                Text(stringResource(R.string.precision_goto_warning, toleranceArcmin))
                OutlinedTextField(value = exposure, onValueChange = { exposure = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("precision-exposure"),
                    label = { Text(stringResource(R.string.solve_exposure_seconds)) },
                    isError = exposureUs == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = gain, onValueChange = { gain = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("precision-gain"),
                    label = { Text(stringResource(R.string.solve_gain_optional)) },
                    isError = gain.isNotBlank() && gainValue == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = focal, onValueChange = { focal = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("precision-focal"),
                    label = { Text(stringResource(R.string.solve_focal_mm)) },
                    isError = focalValue == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = radius, onValueChange = { radius = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("precision-radius"),
                    label = { Text(stringResource(R.string.search_radius)) },
                    supportingText = { Text("0.1–180°") },
                    isError = searchRadius == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.solve_blind_fallback), Modifier.weight(1f))
                    Switch(checked = blind, onCheckedChange = { blind = it }, modifier = Modifier.testTag("precision-blind"))
                }
                Text(stringResource(R.string.solve_single_capture_hint))
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                prefs.edit().putFloat("precision_exposure_us", requireNotNull(exposureUs))
                    .putFloat("precision_search_radius_deg", requireNotNull(searchRadius))
                    .putFloat("plate_focal_length_mm", requireNotNull(focalValue))
                    .putBoolean("precision_blind_fallback", blind).apply {
                        if (gainValue == null) remove("precision_gain") else putFloat("precision_gain", gainValue)
                    }.apply()
                onConfirm()
            }) {
                Text(stringResource(R.string.execute_precision_goto))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
