package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.MediaBatchViewModel
import com.indigo.mobileobservatory.media.shareMediaFiles
import com.indigo.mobileobservatory.recording.VideoFileInfo

@Composable
internal fun MediaBatchActions(selected: List<VideoFileInfo>, onClear: () -> Unit) {
    val context = LocalContext.current
    val task: MediaBatchViewModel = viewModel(key = "media-batch")
    val state by task.state.collectAsStateWithPageLifecycle()
    var shareFailed by remember { mutableStateOf(false) }
    val blocked = state.busy || state.archive != null && !state.share
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { task.save(it) }
    LaunchedEffect(state.readyId) {
        if (state.readyId == null) return@LaunchedEffect
        task.acknowledge()
        val archive = state.archive ?: return@LaunchedEffect
        if (state.share) shareFailed = !shareMediaFiles(context, listOf(archive))
        else destination.launch("Indigo-captures.zip")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onClear, enabled = !blocked) { Text(stringResource(R.string.media_batch_selected, selected.size)) }
            OutlinedButton(onClick = { task.prepare(selected) }, enabled = selected.isNotEmpty() && !blocked) {
                Text(stringResource(R.string.media_batch_export))
            }
            TextButton(onClick = {
                if (selected.any { it.file.isDirectory }) task.prepare(selected, share = true)
                else shareFailed = !shareMediaFiles(context, selected.map { it.file })
            }, enabled = selected.isNotEmpty() && !blocked) { Text(stringResource(R.string.media_batch_share)) }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                Text(stringResource(R.string.media_batch_working, state.completed, state.total), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { task.cancel() }) { Text(stringResource(R.string.cancel)) }
            }
        }
        if (state.error || shareFailed) Text(stringResource(R.string.media_batch_failed), color = MaterialTheme.colorScheme.error)
        if (state.saved) Text(stringResource(R.string.media_batch_saved), style = MaterialTheme.typography.bodySmall)
    }
}
