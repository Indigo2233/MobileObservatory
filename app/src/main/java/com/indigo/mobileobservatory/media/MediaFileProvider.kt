package com.indigo.mobileobservatory.media

import android.net.Uri
import androidx.core.content.FileProvider

/** FITS attachments use document sharing even when the platform maps their suffix to image/fits. */
class MediaFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val platformType = super.getType(uri)
        return when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
            "fit", "fits" -> "application/octet-stream"
            else -> platformType
        }
    }
}
