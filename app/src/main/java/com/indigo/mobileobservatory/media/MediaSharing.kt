package com.indigo.mobileobservatory.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.indigo.mobileobservatory.R
import java.io.File
import java.util.Locale
import java.util.UUID

/** Share only provider content URIs with temporary read access. */
fun mediaShareIntent(context: Context, files: List<File>): Intent {
    require(files.isNotEmpty() && files.all { it.isFile && it.canRead() })
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
    val types = files.map { file ->
        when (file.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "fit", "fits" -> "image/fits"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }.distinct()
    return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = types.singleOrNull() ?: if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        clipData = ClipData.newUri(context.contentResolver, "captures", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun shareMediaFiles(context: Context, files: List<File>): Boolean = runCatching {
    context.startActivity(Intent.createChooser(mediaShareIntent(context, files), context.getString(R.string.media_batch_share)))
    true
}.getOrDefault(false)

/** The current display rendition, at preview resolution and 8 bits per channel. Call on IO. */
fun writeMediaSharePreview(context: Context, source: File, bitmap: Bitmap, checkActive: () -> Unit = {}): File {
    val directory = File(context.cacheDir, "media-share").apply { check(isDirectory || mkdirs()) }
    val target = File(directory, "${source.nameWithoutExtension.take(40)}-preview-${UUID.randomUUID()}.png")
    try {
        checkActive()
        target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        checkActive()
        return target
    } catch (failure: Throwable) {
        target.delete()
        throw failure
    }
}
