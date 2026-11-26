package com.indigo.mobileobservatory

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.indigo.mobileobservatory.media.MediaTrashCleanupWorker
import java.util.concurrent.TimeUnit

class MediaMaintenanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            val manager = WorkManager.getInstance(this)
            manager.enqueueUniqueWork(
                MEDIA_TRASH_STARTUP_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MediaTrashCleanupWorker>().build()
            )
            val periodic = PeriodicWorkRequestBuilder<MediaTrashCleanupWorker>(
                1,
                TimeUnit.DAYS
            ).build()
            manager.enqueueUniquePeriodicWork(
                MEDIA_TRASH_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
        }.onFailure { error ->
            Log.e("MediaMaintenance", "Could not schedule trash retention", error)
        }
    }

    private companion object {
        const val MEDIA_TRASH_WORK = "media-trash-retention"
        const val MEDIA_TRASH_STARTUP_WORK = "media-trash-startup"
    }
}
