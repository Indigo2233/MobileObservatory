package com.indigo.mobileobservatory.media

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.indigo.mobileobservatory.util.FileLogger
import java.io.File

class MediaTrashCleanupWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val recordings = applicationContext.getExternalFilesDir("recordings")
            ?: return Result.retry()
        val trash = File(recordings, ".trash")
        val result = runCatching {
            MediaTrash.purgeExpired(trash, inUse = MediaUsageRegistry::isInUse)
        }.getOrElse { error ->
            FileLogger.e(TAG, "Trash cleanup failed", error)
            return Result.retry()
        }
        FileLogger.i(
            TAG,
            "Trash cleanup purged=${result.purged} retained=${result.retained} failed=${result.failed}"
        )
        return if (result.failed == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "MediaTrashCleanup"
    }
}
