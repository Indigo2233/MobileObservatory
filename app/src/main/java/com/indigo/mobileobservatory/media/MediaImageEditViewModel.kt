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
    val completionId: String? = null,
    val recipeError: Boolean = false
)

/** Saving belongs to the image task and continues through an Activity configuration change. */
class MediaImageEditViewModel : ViewModel() {
    private val mutableHistory = MutableStateFlow(MediaEditHistory())
    val history: StateFlow<MediaEditHistory> = mutableHistory
    private val mutableCheckpoint = MutableStateFlow(MediaImageEditSettings())
    val checkpoint: StateFlow<MediaImageEditSettings> = mutableCheckpoint
    private var boundSource: String? = null
    fun bind(sourceFingerprint: String, settings: MediaImageEditSettings) {
        if (boundSource == sourceFingerprint) {
            if (mutableHistory.value.current == mutableCheckpoint.value && mutableCheckpoint.value != settings) {
                mutableHistory.value = MediaEditHistory(settings)
                mutableCheckpoint.value = settings
            }
            return
        }
        boundSource = sourceFingerprint
        mutableHistory.value = MediaEditHistory(settings)
        mutableCheckpoint.value = settings
    }
    fun change(settings: MediaImageEditSettings) { mutableHistory.value = mutableHistory.value.change(settings) }
    fun update(settings: MediaImageEditSettings) { mutableHistory.value = mutableHistory.value.update(settings) }
    fun commitGesture() { mutableHistory.value = mutableHistory.value.commit() }
    fun undo() { mutableHistory.value = mutableHistory.value.undo() }
    fun redo() { mutableHistory.value = mutableHistory.value.redo() }
    fun resetHistory(settings: MediaImageEditSettings) { mutableHistory.value = MediaEditHistory(settings) }
    fun markSaved(settings: MediaImageEditSettings) { mutableCheckpoint.value = settings }
    private val mutableState = MutableStateFlow(MediaImageExportState())
    val state: StateFlow<MediaImageExportState> = mutableState
    private var exportJob: Job? = null

    fun export(prepared: PreparedMediaImage, capturesDir: File, format: MediaImageExportFormat,
               settings: MediaImageEditSettings, recipeStore: MediaEditRecipeStore? = null) {
        if (mutableState.value.running) return
        mutableState.value = mutableState.value.copy(running = true, error = null)
        exportJob = viewModelScope.launch {
            try {
                val context = currentCoroutineContext()
                val result = withContext(Dispatchers.IO) {
                    val output = MediaImageEditing.export(prepared, capturesDir, format, settings) { !context.isActive }
                    val recipeSaved = runCatching { recipeStore?.save(prepared, settings) }.isSuccess
                    output to recipeSaved
                }
                mutableState.value = MediaImageExportState(result = result.first, savedSettings = settings, recipeError = !result.second,
                    completionId = UUID.randomUUID().toString())
                markSaved(settings)
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
