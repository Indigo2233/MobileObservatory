package com.indigo.mobileobservatory.ui.screens

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.recording.MediaCategory
import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.recording.VideoFileInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Task-local selection from the same app-owned index as the media workspace. */
@Composable
internal fun AppImagePicker(
    selectionLabel: String,
    onDismiss: () -> Unit,
    onSelect: (Uri, Long?) -> Unit
) {
    val context = LocalContext.current
    val selected by rememberUpdatedState(onSelect)
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selected(uri, null)
    }
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val images by produceState(emptyList<VideoFileInfo>(), refresh) {
        loading = true
        error = null
        try {
            value = withContext(Dispatchers.IO) {
                MediaLibrary.list(context.getExternalFilesDir("recordings"), context.getExternalFilesDir("captures"),
                    MediaCategory.PHOTOS, expandFitsFolders = true)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message.orEmpty() }
        finally { loading = false }
    }
    val matches = remember(images, query) {
        val text = query.trim()
        images.filter { text.isBlank() || it.name.contains(text, true) || it.file.parentFile?.name?.contains(text, true) == true }
    }
    AlertDialog(
        modifier = Modifier.testTag("app-image-picker"),
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_image_album), Modifier.weight(1f))
                IconButton(onClick = { refresh++ }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selectionLabel, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.app_image_album_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.app_image_search)) },
                    modifier = Modifier.fillMaxWidth().testTag("app-image-search"))
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    error != null -> Text(stringResource(R.string.app_image_load_failed, error.orEmpty()), color = MaterialTheme.colorScheme.error)
                    matches.isEmpty() -> Text(stringResource(if (images.isEmpty()) R.string.app_image_album_empty else R.string.app_image_no_matches))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(matches, key = { it.file.absolutePath }) { entry ->
                            OutlinedButton(onClick = { selected(Uri.fromFile(entry.file), entry.file.lastModified()) },
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text("${entry.format} · ${Formatter.formatShortFileSize(context, entry.size)} · ${entry.file.parentFile?.name.orEmpty()}",
                                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { document.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("app-image-files")) {
            Text(stringResource(R.string.app_image_system_files))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
