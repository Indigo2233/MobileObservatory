package com.indigo.mobileobservatory.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.recording.VideoFileInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Task-local selection from the same app-owned index as the media workspace. */
@Composable
internal fun AppImagePicker(
    recordingsDir: File?,
    capturesDir: File?,
    excludeFile: File?,
    onDismiss: () -> Unit,
    onSelect: (File) -> Unit,
    selectionLabel: String? = null,
    onDocumentSelect: ((Uri) -> Unit)? = null
) {
    val selected by rememberUpdatedState(onSelect)
    val documentSelected by rememberUpdatedState(onDocumentSelect)
    val documentContract = remember {
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent =
                super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
        }
    }
    val document = rememberLauncherForActivityResult(documentContract) { uri ->
        if (uri != null) documentSelected?.invoke(uri)
    }
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val images by produceState(emptyList<VideoFileInfo>(), recordingsDir, capturesDir, excludeFile, refresh) {
        loading = true
        error = null
        try {
            value = withContext(Dispatchers.IO) {
                MediaLibrary.listImages(recordingsDir, capturesDir, excludeFile)
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
                Text(listOfNotNull(stringResource(R.string.plate_image_library), selectionLabel).joinToString(" · "),
                    Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { refresh++ }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.app_image_search)) },
                    modifier = Modifier.fillMaxWidth().testTag("app-image-search"))
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    error != null -> Text(stringResource(R.string.app_image_load_failed, error.orEmpty()), color = MaterialTheme.colorScheme.error)
                    matches.isEmpty() -> Text(stringResource(if (images.isNotEmpty()) R.string.app_image_no_matches
                        else if (onDocumentSelect != null) R.string.app_image_album_empty else R.string.plate_image_library_empty))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp).testTag("plate-image-list"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(matches, key = { it.file.absolutePath }) { entry ->
                            OutlinedButton(onClick = { selected(entry.file) },
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text("${entry.format} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.file.lastModified()))}",
                                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onDocumentSelect != null) TextButton(onClick = { document.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("app-image-files")) {
                Text(stringResource(R.string.app_image_system_files))
            } else TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        dismissButton = if (onDocumentSelect != null) ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }) else null
    )
}
