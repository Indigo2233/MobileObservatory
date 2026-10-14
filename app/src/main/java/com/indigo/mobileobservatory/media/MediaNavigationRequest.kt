package com.indigo.mobileobservatory.media

import android.content.Context
import android.content.Intent
import com.indigo.mobileobservatory.MainActivity
import com.indigo.mobileobservatory.recording.MediaLibrary
import java.io.File

data class MediaNavigationRequest(val id: Long, val filePath: String? = null)

object MediaNavigationContract {
    const val ACTION_OPEN_MEDIA = "com.indigo.mobileobservatory.OPEN_MEDIA"
    const val EXTRA_FILE_PATH = "media_file_path"

    fun parentIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_MEDIA).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun ownedFile(context: Context, path: String): File? = runCatching {
        val file = File(path).canonicalFile
        val roots = listOfNotNull(context.getExternalFilesDir("recordings"), context.getExternalFilesDir("captures"))
        require(roots.any { file.path.startsWith(it.canonicalPath + File.separator) })
        require(MediaLibrary.describe(file) != null)
        file
    }.getOrNull()
}
