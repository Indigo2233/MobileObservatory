package com.indigo.mobileobservatory.media

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.indigo.mobileobservatory.recording.VideoFileInfo
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MediaBatchState(val busy: Boolean = false, val completed: Int = 0, val total: Int = 0,
    val archive: File? = null, val share: Boolean = false, val readyId: String? = null,
    val error: Boolean = false, val saved: Boolean = false)

class MediaBatchViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(MediaBatchState())
    val state: StateFlow<MediaBatchState> = mutableState
    private var job: Job? = null

    fun prepare(selected: List<VideoFileInfo>, share: Boolean = false) {
        if (state.value.busy || state.value.archive != null && !state.value.share || selected.isEmpty()) return
        mutableState.value = MediaBatchState(busy = true)
        job = viewModelScope.launch {
            var archive: File? = null
            try {
                archive = withContext(Dispatchers.IO) {
                    val directory = File(getApplication<Application>().cacheDir, "media-share").also { check(it.isDirectory || it.mkdirs()) }
                    // Retain shared URIs long enough for receiving apps; remove expired task outputs.
                    directory.listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 48 * 60 * 60 * 1000L }.forEach { it.delete() }
                    val id = UUID.randomUUID().toString()
                    val partial = File(directory, ".$id.partial")
                    val result = File(directory, "Indigo-captures-$id.zip")
                    archive = result
                    try {
                        FileOutputStream(partial).use { stream ->
                            MediaBatchArchive.write(stream, selected, { ensureActive() }) { done, total ->
                                mutableState.value = mutableState.value.copy(completed = done, total = total)
                            }
                        }
                        ensureActive(); check(partial.renameTo(result)); result
                    } finally { partial.delete() }
                }
                mutableState.value = MediaBatchState(archive = archive, share = share, readyId = UUID.randomUUID().toString())
            } catch (cancelled: CancellationException) {
                archive?.delete(); mutableState.value = MediaBatchState(); throw cancelled
            } catch (_: Exception) { archive?.delete(); mutableState.value = MediaBatchState(error = true) }
        }
    }

    fun save(uri: Uri?) {
        val archive = state.value.archive ?: return
        if (uri == null) { archive.delete(); mutableState.value = MediaBatchState(); return }
        mutableState.value = mutableState.value.copy(busy = true, saved = false, error = false)
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val stream = resolver.openOutputStream(uri, "w") ?: error("storage_unavailable")
                    stream.use { output -> archive.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                    } }
                    ensureActive()
                }
                mutableState.value = MediaBatchState(saved = true)
            } catch (cancelled: CancellationException) {
                removeIncomplete(uri); mutableState.value = MediaBatchState(); throw cancelled
            } catch (_: Exception) { removeIncomplete(uri); mutableState.value = MediaBatchState(error = true)
            } finally { archive.delete() }
        }
    }

    private suspend fun removeIncomplete(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }; Unit
    }
    fun acknowledge() { mutableState.value = mutableState.value.copy(readyId = null) }
    fun cancel() { job?.cancel() }
}
