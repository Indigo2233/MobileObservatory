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
import com.indigo.mobileobservatory.stacking.DemosaicAlgorithm

/** One compact entry; changing a completed offline workspace asks the user to save first. */
@Composable
internal fun DemosaicSelector(selected: DemosaicAlgorithm, enabled: Boolean,
    requiresResetConfirmation: Boolean = false, onSelect: (DemosaicAlgorithm) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var pendingName by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("stack-debayer-selector")) {
            val label = if (selected == DemosaicAlgorithm.BILINEAR) stringResource(R.string.stack_debayer_bilinear) else selected.name
            Text(stringResource(R.string.stack_debayer_summary, label), Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(DemosaicAlgorithm.DDFAPD, DemosaicAlgorithm.VNG, DemosaicAlgorithm.BILINEAR).forEach { algorithm ->
                DropdownMenuItem(text = { Text(stringResource(when (algorithm) {
                    DemosaicAlgorithm.DDFAPD -> R.string.stack_debayer_ddfapd
                    DemosaicAlgorithm.VNG -> R.string.stack_debayer_vng
                    DemosaicAlgorithm.BILINEAR -> R.string.stack_debayer_bilinear
                })) }, enabled = enabled, modifier = Modifier.testTag("stack-debayer-${algorithm.name}"), onClick = {
                    expanded = false
                    if (algorithm != selected) {
                        if (requiresResetConfirmation) pendingName = algorithm.name else onSelect(algorithm)
                    }
                })
            }
        }
    }
    pendingName?.let { name ->
        AlertDialog(onDismissRequest = { pendingName = null },
            title = { Text(stringResource(R.string.stack_debayer_change)) },
            text = { Text(stringResource(R.string.stack_debayer_reanalyze)) },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                pendingName = null; onSelect(DemosaicAlgorithm.valueOf(name))
            }) { Text(stringResource(R.string.stack_debayer_change)) } },
            dismissButton = { TextButton(onClick = { pendingName = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
