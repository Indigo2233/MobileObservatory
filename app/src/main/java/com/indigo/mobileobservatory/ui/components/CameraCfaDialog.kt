package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.stacking.SerCfaInterpretation

/** Choices stay local until Apply. Reset wording makes destruction of the old stack explicit. */
@Composable fun CameraCfaDialog(selection: SerCfaInterpretation, rawAvailable: Boolean,
    requiresStackReset: Boolean, applying: Boolean, issue: (SerCfaInterpretation) -> String?,
    onApply: (SerCfaInterpretation, Boolean) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(selection) { mutableStateOf(selection) }
    val changed = draft != selection
    val reset = changed && requiresStackReset
    val blocked = if (changed) issue(draft) else null
    AlertDialog(onDismissRequest = { if (!applying) onDismiss() },
        title = { Text(stringResource(R.string.camera_cfa_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.camera_cfa_scope), style = MaterialTheme.typography.bodySmall)
            if (!rawAvailable) Text(stringResource(R.string.camera_cfa_rgb), style = MaterialTheme.typography.bodySmall)
            SerCfaInterpretation.entries.forEach { value ->
                val enabled = !applying && (rawAvailable || value == SerCfaInterpretation.AUTO)
                Row(Modifier.fillMaxWidth().testTag("camera-cfa-${value.name}").clickable(enabled = enabled) { draft = value }
                    .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft == value, enabled = enabled,
                        modifier = Modifier.testTag("camera-cfa-radio-${value.name}"), onClick = { draft = value })
                    Text(when (value) {
                        SerCfaInterpretation.AUTO -> stringResource(R.string.camera_cfa_auto)
                        SerCfaInterpretation.MONO -> stringResource(R.string.camera_cfa_mono)
                        else -> value.name
                    })
                }
            }
            if (reset) Text(stringResource(R.string.camera_cfa_reset_stack), color = MaterialTheme.colorScheme.error)
            blocked?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !applying && blocked == null,
            modifier = Modifier.testTag("camera-cfa-apply"), onClick = { onApply(draft, reset) }) {
            Text(stringResource(if (reset) R.string.camera_cfa_reset_apply else R.string.camera_cfa_apply))
        } },
        dismissButton = { TextButton(enabled = !applying, onClick = onDismiss, modifier = Modifier.testTag("camera-cfa-cancel")) {
            Text(stringResource(android.R.string.cancel))
        } })
}
