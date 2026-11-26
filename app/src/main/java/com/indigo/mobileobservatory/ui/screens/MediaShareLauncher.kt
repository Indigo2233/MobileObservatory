package com.indigo.mobileobservatory.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.MediaUsageRegistry
import com.indigo.mobileobservatory.media.mediaShareIntent
import java.io.File

@Composable
internal fun rememberMediaShareLauncher(
    leaseDirectory: File? = null,
    onFailure: () -> Unit
): (List<File>) -> Unit {
    val context = LocalContext.current
    val currentFailure by rememberUpdatedState(onFailure)
    var lease by remember { mutableStateOf<AutoCloseable?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        lease?.let {
            MediaUsageRegistry.releaseAfter(it, MediaUsageRegistry.SHARE_GRACE_MS)
        }
        lease = null
    }
    DisposableEffect(Unit) {
        onDispose {
            lease?.let {
                MediaUsageRegistry.releaseAfter(it, MediaUsageRegistry.SHARE_GRACE_MS)
            }
            lease = null
        }
    }
    return remember(context, launcher, leaseDirectory) {
        { files ->
            lease?.let {
                MediaUsageRegistry.releaseAfter(it, MediaUsageRegistry.SHARE_GRACE_MS)
            }
            lease = null
            runCatching {
                val nextLease = MediaUsageRegistry.acquire(
                    files + listOfNotNull(leaseDirectory),
                    waitForExclusive = false
                )
                lease = nextLease
                launcher.launch(
                    Intent.createChooser(
                        mediaShareIntent(context, files),
                        context.getString(R.string.media_batch_share)
                    )
                )
            }.onFailure {
                lease?.close()
                lease = null
                currentFailure()
            }
        }
    }
}
