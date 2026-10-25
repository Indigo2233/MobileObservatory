package com.indigo.mobileobservatory.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaImageExportState(
    val running: Boolean = false,
    val result: File? = null,
    val savedSettings: MediaImageEditSettings? = null,
    val error: String? = null,
    val completionId: String? = null
)

/** Saving belongs to the image task and continues through an Activity configuration change. */
class MediaImageEditViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(MediaImageExportState())
    val state: StateFlow<MediaImageExportState> = mutableState
    private var exportJob: Job? = null

    fun export(prepared: PreparedMediaImage, capturesDir: File, format: MediaImageExportFormat,
               settings: MediaImageEditSettings) {
        if (mutableState.value.running) return
        mutableState.value = mutableState.value.copy(running = true, error = null)
        exportJob = viewModelScope.launch {
            try {
                val context = currentCoroutineContext()
                val file = withContext(Dispatchers.IO) {
                    MediaImageEditing.export(prepared, capturesDir, format, settings) { !context.isActive }
                }
                mutableState.value = MediaImageExportState(result = file, savedSettings = settings,
                    completionId = UUID.randomUUID().toString())
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(running = false)
                throw cancelled
            } catch (failure: OutOfMemoryError) {
                failed("memory_budget")
            } catch (failure: Exception) {
                failed(failure.message ?: "save_failed")
            }
        }
    }

    fun cancel() { exportJob?.cancel() }
    suspend fun cancelAndJoin() { exportJob?.cancelAndJoin() }
    fun acknowledgeCompletion() { mutableState.value = mutableState.value.copy(completionId = null) }
    private fun failed(message: String) {
        mutableState.value = mutableState.value.copy(running = false, error = message,
            completionId = UUID.randomUUID().toString())
    }
}
