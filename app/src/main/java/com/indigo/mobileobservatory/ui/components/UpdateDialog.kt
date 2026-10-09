package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.viewmodel.UpdatePhase
import com.indigo.mobileobservatory.ui.viewmodel.UpdateUiState

@Composable
fun UpdateAvailableDialog(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onOpenInstallSettings: () -> Unit
) {
    val manifest = state.manifest ?: return
    if (!state.promptVisible) return

    val required = manifest.isRequiredFor(BuildConfig.VERSION_CODE)
    val downloading = state.phase == UpdatePhase.DOWNLOADING
    val needsPermission = state.phase == UpdatePhase.PERMISSION_REQUIRED

    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.update_current_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE
                    )
                )
                Text(
                    stringResource(
                        R.string.update_new_version,
                        manifest.versionName,
                        manifest.versionCode
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                manifest.notes?.let { notes ->
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = state.progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (state.phase == UpdatePhase.ERROR || needsPermission) {
                    Text(
                        state.statusText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (required) {
                    Text(
                        stringResource(R.string.update_required_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when {
                downloading -> TextButton(onClick = {}, enabled = false) {
                    Text(state.statusText ?: stringResource(R.string.update_downloading, 0))
                }
                else -> TextButton(onClick = onDownload) {
                    Text(
                        stringResource(
                            if (needsPermission) R.string.update_install_button
                            else R.string.update_download_button
                        )
                    )
                }
            }
        },
        dismissButton = {
            when {
                needsPermission -> TextButton(onClick = onOpenInstallSettings) {
                    Text(stringResource(R.string.update_open_settings_button))
                }
                !required && !downloading -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later_button))
                }
            }
        }
    )
}
