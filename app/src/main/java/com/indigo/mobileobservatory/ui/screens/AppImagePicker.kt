package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.recording.VideoFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun AppImagePicker(recordingsDir: File?, capturesDir: File?, excludeFile: File?,
    onDismiss: () -> Unit, onSelect: (File) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val files by produceState(emptyList<VideoFileInfo>(), recordingsDir, capturesDir, excludeFile) {
        loading = true
        try {
            value = withContext(Dispatchers.IO) { MediaLibrary.listImages(recordingsDir, capturesDir, excludeFile) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: Exception) { failed = true
        } finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plate_image_library)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    failed -> Text(stringResource(R.string.plate_image_load_failed), color = MaterialTheme.colorScheme.error)
                    files.isEmpty() -> Text(stringResource(R.string.plate_image_library_empty))
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp).testTag("plate-image-list")) {
                        items(files, key = { it.file.absolutePath }) { info ->
                            Column(Modifier.fillMaxWidth().clickable { onSelect(info.file) }.padding(vertical = 12.dp)) {
                                Text(info.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${info.format} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                                    java.text.DateFormat.SHORT).format(java.util.Date(info.file.lastModified()))}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
