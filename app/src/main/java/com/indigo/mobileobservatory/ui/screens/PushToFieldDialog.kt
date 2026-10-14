package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushToFieldDialog(initialShape: String, initialWidth: String, initialHeight: String,
    onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var shape by rememberSaveable { mutableStateOf(initialShape) }
    var width by rememberSaveable { mutableStateOf(initialWidth) }
    var height by rememberSaveable { mutableStateOf(initialHeight) }
    val widthValue = width.toDoubleOrNull()
    val heightValue = height.toDoubleOrNull()
    val valid = widthValue != null && widthValue.isFinite() && widthValue > 0 && widthValue < 180 &&
        (shape == "circle" || heightValue != null && heightValue.isFinite() && heightValue > 0 && heightValue < 180)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wf_push_field)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = shape == "circle", onClick = { shape = "circle" },
                        label = { Text(stringResource(R.string.wf_push_field_circle)) })
                    FilterChip(selected = shape == "camera", onClick = { shape = "camera" },
                        label = { Text(stringResource(R.string.wf_push_field_camera)) })
                }
                OutlinedTextField(width, { width = it }, Modifier.fillMaxWidth().testTag("push-field-width"),
                    label = { Text(stringResource(if (shape == "circle") R.string.wf_push_field_diameter else R.string.wf_push_field_width)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (shape == "camera") {
                    OutlinedTextField(height, { height = it }, Modifier.fillMaxWidth().testTag("push-field-height"),
                        label = { Text(stringResource(R.string.wf_push_field_height)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Text(stringResource(R.string.wf_push_field_camera_conservative), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(shape, width, height) }) {
            Text(stringResource(R.string.wf_push_field_confirm))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
