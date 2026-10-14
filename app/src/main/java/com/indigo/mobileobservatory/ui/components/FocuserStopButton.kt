package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R

/** The action stays STOP even when telemetry reports motion ending during the press. */
@Composable
fun FocuserStopButton(moving: Boolean, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.wf_focus_stop)
    FilledTonalButton(onClick = onStop, modifier = modifier.semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp), colors = if (moving) ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer) else ButtonDefaults.filledTonalButtonColors()) {
        Text("■")
    }
}
