package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.stacking.SerCfaInterpretation

@Composable
internal fun SerCfaSelector(selected: SerCfaInterpretation, enabled: Boolean,
    requiresResetConfirmation: Boolean = false, onSelect: (SerCfaInterpretation) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val auto = stringResource(R.string.stack_auto)
    val mono = stringResource(R.string.stack_cfa_mono)
    fun display(mode: SerCfaInterpretation) = when (mode) {
        SerCfaInterpretation.AUTO -> auto; SerCfaInterpretation.MONO -> mono; else -> mode.name
    }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("stack-cfa-selector")) {
            Text(stringResource(R.string.stack_cfa_summary, display(selected)), Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SerCfaInterpretation.values().forEach { mode ->
                DropdownMenuItem(text = { Text(display(mode)) }, enabled = enabled,
                    modifier = Modifier.testTag("stack-cfa-${mode.name}"), onClick = {
                        expanded = false
                        if (mode != selected) {
                            if (requiresResetConfirmation) pending = mode.name else onSelect(mode)
                        }
                    })
            }
        }
    }
    pending?.let { name ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.stack_cfa_change)) },
            text = { Text(stringResource(R.string.stack_debayer_reanalyze)) },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                pending = null; onSelect(SerCfaInterpretation.valueOf(name))
            }) { Text(stringResource(R.string.stack_cfa_change)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
