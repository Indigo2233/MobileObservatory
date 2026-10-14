package com.indigo.mobileobservatory.media

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class LibraryImportState(val busy: Boolean = false, val revision: Long = 0,
    val importedName: String? = null, val error: ExternalMediaImportError? = null,
    val completionId: String? = null)

/** Import is a library operation, independent of either stacking task or camera ownership. */
class LibraryImportViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(LibraryImportState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun importFile(uri: Uri) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, importedName = null)
        job = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    ExternalMediaImporter.importFile(getApplication(), Intent(Intent.ACTION_VIEW, uri))
                }
                _state.value = LibraryImportState(revision = _state.value.revision + 1, importedName = file.name,
                    completionId = UUID.randomUUID().toString())
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(busy = false)
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(busy = false,
                    error = (error as? ExternalMediaImportException)?.reason ?: ExternalMediaImportError.COPY_FAILED)
            }
        }
    }

    fun cancel() { job?.cancel() }
}
