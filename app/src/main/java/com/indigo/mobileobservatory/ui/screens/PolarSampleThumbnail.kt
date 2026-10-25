package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.*
import com.indigo.mobileobservatory.polar.PolarImagePreview
import kotlinx.coroutines.*
import java.io.File

/** Small UInt16 source preview; opening the image uses the existing full image viewer. */
@Composable
internal fun PolarSampleThumbnail(file: File, onOpen: () -> Unit) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        val task = currentCoroutineContext()
        var owned: Bitmap? = null
        var published = false
        try {
            withContext(Dispatchers.IO) {
                val prepared = PolarImagePreview.prepare(file, 480) { !task.isActive }
                owned = MediaImageEditing.renderPreview(prepared, PolarImagePreview.settings) { !task.isActive }
            }
            ensureActive()
            bitmap = owned
            published = true
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failure = error.message }
        finally { if (!published) owned?.recycle() }
    }
    DisposableEffect(bitmap) { val owned = bitmap; onDispose { owned?.recycle() } }
    bitmap?.let { image ->
        Image(image.asImageBitmap(), stringResource(R.string.polar_sample_preview),
            Modifier.fillMaxWidth().heightIn(max = 160.dp).clickable(onClick = onOpen), contentScale = ContentScale.Fit)
    }
    failure?.let { Text(stringResource(R.string.polar_preview_failed, it), style = MaterialTheme.typography.bodySmall) }
}
