package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.shareMediaFiles
import com.indigo.mobileobservatory.media.writeMediaSharePreview
import com.indigo.mobileobservatory.ui.theme.ObservatoryTheme
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun MediaImageShareButton(file: File, preview: Bitmap?, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    fun share(files: List<File>) {
        if (!shareMediaFiles(context, files)) Toast.makeText(context, R.string.media_share_failed, Toast.LENGTH_LONG).show()
    }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled && !preparing,
            modifier = Modifier.testTag("media-image-share")) {
            Icon(Icons.Default.Share, null, tint = ObservatoryTheme.colors.onImage)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.media_batch_share), color = ObservatoryTheme.colors.onImage)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.media_share_preview)) }, enabled = preview != null,
                onClick = {
                    expanded = false
                    val bitmap = preview ?: return@DropdownMenuItem
                    preparing = true
                    scope.launch {
                        try {
                            val job = currentCoroutineContext()
                            val output = withContext(Dispatchers.IO) { writeMediaSharePreview(context, file, bitmap) { job.ensureActive() } }
                            share(listOf(output))
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: OutOfMemoryError) { Toast.makeText(context, R.string.media_edit_memory, Toast.LENGTH_LONG).show()
                        } catch (_: Exception) { Toast.makeText(context, R.string.media_share_failed, Toast.LENGTH_LONG).show()
                        } finally { preparing = false }
                    }
                })
            DropdownMenuItem(text = { Text(stringResource(R.string.media_share_original)) },
                onClick = { expanded = false; share(listOf(file)) })
            Text(stringResource(R.string.media_share_preview_hint), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.widthIn(max = 280.dp).padding(12.dp))
        }
        if (preparing) CircularProgressIndicator(Modifier.size(24.dp))
    }
}
