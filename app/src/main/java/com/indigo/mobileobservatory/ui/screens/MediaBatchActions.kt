package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.MediaBatchState
import com.indigo.mobileobservatory.media.MediaBatchViewModel
import com.indigo.mobileobservatory.recording.VideoFileInfo

@Composable
internal fun MediaBatchActions(
    task: MediaBatchViewModel,
    state: MediaBatchState,
    selected: List<VideoFileInfo>,
    blocked: Boolean,
    allSelected: Boolean,
    canSelect: Boolean,
    onSelectAll: () -> Unit,
    onClose: () -> Unit,
    onMoveToTrash: (() -> Unit)?
) {
    var shareFailed by remember { mutableStateOf(false) }
    val shareFiles = rememberMediaShareLauncher { shareFailed = true }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { task.save(it) }
    LaunchedEffect(state.readyId) {
        if (state.readyId == null) return@LaunchedEffect
        task.acknowledge()
        val archive = state.archive ?: return@LaunchedEffect
        if (state.share) shareFiles(listOf(archive))
        else destination.launch("Indigo-captures.zip")
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !blocked) {
                Icon(Icons.Default.Close, stringResource(R.string.close))
            }
            Text(stringResource(R.string.media_batch_selected, selected.size), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onMoveToTrash != null) {
                IconButton(onClick = onMoveToTrash, enabled = selected.isNotEmpty() && !blocked,
                    modifier = Modifier.testTag("media-batch-trash")) {
                    Icon(Icons.Default.Delete, stringResource(R.string.media_move_to_trash))
                }
            }
            MediaActionsMenu(stringResource(R.string.media_more_actions), enabled = !blocked) { close ->
                DropdownMenuItem(text = {
                    Text(stringResource(if (allSelected) R.string.media_clear_selection else R.string.media_select_all))
                }, onClick = { close(); onSelectAll() }, enabled = canSelect)
                DropdownMenuItem(text = { Text(stringResource(R.string.media_batch_share)) }, onClick = {
                    close()
                    if (selected.any { it.file.isDirectory }) task.prepare(selected, share = true)
                    else shareFiles(selected.map { it.file })
                }, enabled = selected.isNotEmpty())
                DropdownMenuItem(text = { Text(stringResource(R.string.media_batch_export)) },
                    onClick = { close(); task.prepare(selected) }, enabled = selected.isNotEmpty())
            }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.media_batch_working, state.completed, state.total), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { task.cancel() }) { Text(stringResource(R.string.cancel)) }
            }
        }
        if (state.error || shareFailed) Text(stringResource(R.string.media_batch_failed), Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.error)
        if (state.saved) Text(stringResource(R.string.media_batch_saved), Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall)
    }
}
