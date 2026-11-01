package com.indigo.mobileobservatory.polar

import android.content.Context
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.astrometry.SolveFailureArchive
import com.indigo.mobileobservatory.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun retainSolveFailure(context: Context, task: String, index: Int, sample: PolarSample) {
    val file = sample.file ?: return
    if (sample.phase != PolarSamplePhase.FAILED || sample.failedPhase != PolarSamplePhase.SOLVING) return
    withContext(Dispatchers.IO) {
        try {
            val directory = checkNotNull(context.getExternalFilesDir("captures")) { "Capture storage unavailable" }
            val diagnostics = buildString {
                appendLine("buildId=${BuildConfig.SOURCE_BUILD_ID}")
                appendLine("task=$task point=${index + 1} phase=${sample.phase} failedPhase=${sample.failedPhase}")
                appendLine("source=${file.absolutePath} bytes=${file.length()} capturedAt=${sample.capturedAt}")
                appendLine("reason=${sample.failure.orEmpty()} elapsedMs=${sample.result?.elapsedMs}")
                append(sample.result?.log.orEmpty())
            }
            val job = currentCoroutineContext()
            val saved = SolveFailureArchive.save(directory, file, diagnostics, checkActive = { job.ensureActive() })
                ?: return@withContext
            FileLogger.i("SolveEvidence", "Saved image=${saved.image.absolutePath} log=${saved.log.absolutePath}")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            FileLogger.w("SolveEvidence", "Could not retain image=${file.absolutePath}: ${failure.message}")
        }
    }
}
