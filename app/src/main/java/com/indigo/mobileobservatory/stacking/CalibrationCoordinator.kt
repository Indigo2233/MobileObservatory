package com.indigo.mobileobservatory.stacking

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SerEndianConvention
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class CalibrationLibraryState(
    val masters: List<CalibrationDescriptor> = emptyList(),
    val selectedIds: Map<CalibrationType, String> = emptyMap(),
    val confirmedIds: Set<String> = emptySet(),
    val busy: Boolean = false,
    val completed: Long = 0,
    val total: Long = 0,
    val message: String = "",
    val locked: Boolean = false,
    val initialized: Boolean = false
)

/** Owns a small metadata library and at most one loaded, immutable selection. The monitor and
 * single science dispatcher are shared with StackingCoordinator: reserving a library mutation
 * and starting a stack cannot race. Nothing is calibrated unless the user selects a master.
 */
class CalibrationCoordinator(
    private val app: Application,
    private val scope: CoroutineScope,
    private val monitor: Any,
    private val memoryBudgetBytes: Long,
    private val lockReason: () -> String?,
    private val isSourceBusy: (File) -> Boolean = { false },
    private val endian: () -> SerEndianConvention = { SerEndianConvention.COMPATIBILITY },
    private val alignment: () -> SampleAlignment = { SampleAlignment.RIGHT },
    private val directory: File = File(app.filesDir, "stack-calibration")
) : Closeable {
    private val library = CalibrationLibrary(directory)
    private val preferences = app.getSharedPreferences("stack-calibration-selection-${directory.name}", 0)
    private val restoredIds = CalibrationType.values().mapNotNull { type ->
        preferences.getString(type.name, null)?.let { type to it }
    }.toMap()
    private val restoredConfirmed = preferences.getStringSet("confirmed", emptySet()).orEmpty().toSet()
    private val _state = MutableStateFlow(CalibrationLibraryState(selectedIds = restoredIds,
        confirmedIds = restoredConfirmed, busy = restoredIds.isNotEmpty()))
    val state = _state.asStateFlow()
    @Volatile private var selection = CalibrationSelection()
    private var job: Job? = null
    private var closed = false

    init {
        val initial = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val entries = library.list()
                val loaded = loadSelection(restoredIds, restoredConfirmed) { ensureActive() }
                synchronized(monitor) {
                    if (!closed) {
                        selection = loaded
                        _state.value = _state.value.copy(masters = entries, locked = lockReason() != null)
                    }
                }
            } catch (cancel: CancellationException) {
                _state.update { it.copy(message = "calibration_cancelled") }; throw cancel
            }
            catch (failure: Exception) {
                synchronized(monitor) {
                    if (!closed) {
                        // A missing/corrupt persisted master must not silently turn calibration off.
                        _state.value = _state.value.copy(message = failure.message ?: "calibration_read_failed")
                    }
                }
            } catch (_: OutOfMemoryError) {
                _state.update { it.copy(message = "memory_budget") }
            }
        }
        job = initial
        // Completion also runs when a queued coroutine was cancelled before entering its body.
        // Restored IDs remain visible until the user explicitly retries or disables them.
        initial.invokeOnCompletion { cause -> synchronized(monitor) {
            if (job === initial) {
                job = null
                _state.update { it.copy(busy = false, initialized = true,
                    message = if (cause is CancellationException) "calibration_cancelled" else it.message,
                    locked = lockReason() != null) }
            }
        } }
        initial.start()
    }

    /** Caller holds the shared state monitor when starting a task. No disk I/O here. */
    fun snapshot(): CalibrationSelection = synchronized(monitor) {
        check(!_state.value.busy) { "calibration_busy" }
        check(_state.value.selectedIds.size == selectedCount(selection)) { "calibration_selection_unavailable" }
        selection
    }

    val memoryBytes: Long get() = selection.memoryBytes

    fun updateLock() = synchronized(monitor) {
        if (!closed) _state.update { it.copy(locked = lockReason() != null) }
    }

    fun clearMessage() { _state.update { it.copy(message = "") } }

    fun refresh() = change(requiresUnlocked = false) {
        val entries = library.list()
        _state.update { it.copy(masters = entries) }
    }

    fun selectMaster(type: CalibrationType, id: String?, confirmMatchingSettings: Boolean = false) = change {
        val old = _state.value
        val ids = old.selectedIds.toMutableMap().apply { if (id == null) remove(type) else put(type, id) }
        val confirmed = old.confirmedIds.filterTo(mutableSetOf()) { it in ids.values }
        if (id != null && confirmMatchingSettings) confirmed += id
        val next = loadSelection(ids, confirmed) { ensureActive() }
        ensureActive()
        synchronized(monitor) {
            check(!closed) { "calibration_closed" }
            selection = next
            preferences.edit().apply {
                CalibrationType.values().forEach { category ->
                    ids[category]?.let { putString(category.name, it) } ?: remove(category.name)
                }
                putStringSet("confirmed", confirmed)
            }.apply()
            _state.update { it.copy(selectedIds = ids, confirmedIds = confirmed, message = "") }
        }
    }

    /** Recovery must not try to read remaining broken masters before it can disable them. */
    fun clearSelection() = change {
        synchronized(monitor) {
            selection = CalibrationSelection()
            preferences.edit().apply {
                CalibrationType.values().forEach { remove(it.name) }
                remove("confirmed")
            }.apply()
            _state.update { it.copy(selectedIds = emptyMap(), confirmedIds = emptySet(), message = "") }
        }
    }

    /** Deletes only this app's derived master. Its original recording is never touched. */
    fun deleteMaster(id: String) = change {
        check(id !in _state.value.selectedIds.values) { "calibration_master_in_use" }
        ensureActive()
        library.delete(id)
        _state.update { it.copy(masters = library.list(), message = "") }
    }

    fun createMaster(type: CalibrationType, file: File, confirmMatchingSettings: Boolean = false,
        cfaInterpretation: SerCfaInterpretation = SerCfaInterpretation.AUTO) = change {
        create(type, file, file.name, confirmMatchingSettings, cfaInterpretation)
    }

    /** External recordings are temporary inputs, never misleading new entries in My recordings. */
    fun importMaster(type: CalibrationType, uri: Uri, confirmMatchingSettings: Boolean = false,
        cfaInterpretation: SerCfaInterpretation = SerCfaInterpretation.AUTO) = change {
        var name = "import.ser"
        var size = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
        require(name.endsWith(".ser", true) || name.endsWith(".pser", true)) { "calibration_ser_required" }
        val temporary = File(app.cacheDir, "calibration-import-${UUID.randomUUID()}.partial")
        try {
            check(size < 0 || app.cacheDir.usableSpace > size + 16L * 1024 * 1024) { "storage_full" }
            app.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); copied += count
                        _state.update { it.copy(completed = copied, total = maxOf(0, size)) }
                    }
                }
            } ?: error("source_unreadable")
            create(type, temporary, name, confirmMatchingSettings, cfaInterpretation)
        } finally { temporary.delete() }
    }

    private suspend fun CoroutineScope.create(type: CalibrationType, file: File, name: String, confirmed: Boolean,
        cfaInterpretation: SerCfaInterpretation) {
        check(!isSourceBusy(file)) { "recording_active" }
        val identity = sourceIdentity(file) { ensureActive() }
        SERReader(file, endian(), cfaInterpretation).use { reader ->
            reader.sampleAlignment = alignment()
            check(reader.open()) { reader.lastError ?: "invalid_source" }
            val header = checkNotNull(reader.header)
            val geometry = CalibrationGeometry.from(header.width, header.height, reader.sensorFormat, reader.sensorTraits)
            val capture = recordingCapture(file)
            val bias = if (type == CalibrationType.FLAT) selection.bias else null
            require(geometry.width.toLong() * geometry.height <= LinearImage.DEFAULT_STACK_PIXELS &&
                CalibrationBuilder.estimatedBuildBytes(geometry, bias != null) + selection.memoryBytes <= memoryBudgetBytes) { "memory_budget" }
            val master = CalibrationBuilder.build(type, geometry, capture, header.frameCount.toLong(),
                readFrame = { index ->
                    ensureActive()
                    reader.readSensorFrame(index.toInt()) ?: error(reader.lastError ?: "read_failed")
                }, name = name, sourceIdentity = cfaInterpretation.decodingIdentity(identity,
                    reader.endianConvention.name, reader.sampleAlignment.name, "SENSOR"), bias = bias, allowUnknownCapture = confirmed,
                cancelCheck = { ensureActive() }, progress = { done, total ->
                    _state.update { it.copy(completed = done, total = total) }
                })
            check(sourceIdentity(file) { ensureActive() } == identity) { "source_changed" }
            val saved = library.save(master) { ensureActive() }
            try {
                ensureActive()
                _state.update { it.copy(masters = library.list(), message = "calibration_master_created") }
            } catch (failure: Throwable) { library.delete(saved.id); throw failure }
        }
    }

    private fun loadSelection(ids: Map<CalibrationType, String>, confirmed: Set<String>, checkCancelled: () -> Unit): CalibrationSelection {
        val descriptors = library.list().associateBy { it.id }
        val previous = selection
        val reusable = listOfNotNull(previous.bias, previous.dark, previous.flat).associateBy { it.descriptor.id }
        val bytes = ids.map { (type, id) ->
            val descriptor = descriptors[id] ?: error("calibration_master_missing")
            check(descriptor.type == type) { "calibration_type_mismatch" }
            if (id in reusable) 0L else descriptor.geometry.sampleCount * 4L
        }.sum()
        // During a selection change, old immutable masters remain reachable until the worker
        // commits. Include both selections rather than relying on prompt garbage collection.
        require(bytes + previous.memoryBytes <= memoryBudgetBytes) { "memory_budget" }
        fun load(type: CalibrationType) = ids[type]?.let { reusable[it] ?: library.load(it, checkCancelled) }
        return CalibrationSelection(load(CalibrationType.BIAS), load(CalibrationType.DARK), load(CalibrationType.FLAT),
            allowUnknownCapture = ids.isNotEmpty() && ids.values.all { it in confirmed })
    }

    private fun change(requiresUnlocked: Boolean = true, action: suspend CoroutineScope.() -> Unit): Unit = synchronized(monitor) {
        if (closed) return
        if (_state.value.busy || !_state.value.initialized) { _state.update { it.copy(message = "calibration_busy") }; return }
        if (requiresUnlocked && lockReason() != null) {
            _state.update { it.copy(message = "calibration_reset_required", locked = true) }; return
        }
        _state.update { it.copy(busy = true, completed = 0, total = 0, message = "") }
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancel: CancellationException) { _state.update { it.copy(message = "calibration_cancelled") }; throw cancel }
            catch (error: Exception) { _state.update { it.copy(message = error.message ?: "calibration_failed") } }
            catch (_: OutOfMemoryError) { _state.update { it.copy(message = "memory_budget") } }
        }
        job = next
        next.invokeOnCompletion { synchronized(monitor) {
            if (job === next) { job = null; _state.update { it.copy(busy = false, locked = lockReason() != null) } }
        } }
        next.start()
    }

    fun cancel() = synchronized(monitor) { job?.cancel() }

    override fun close(): Unit = synchronized(monitor) { closed = true; job?.cancel(); Unit }

    suspend fun awaitClosed() { job?.join() }

    companion object {
        private fun selectedCount(selection: CalibrationSelection): Int =
            listOfNotNull(selection.bias, selection.dark, selection.flat).size

        internal fun recordingCapture(file: File): CalibrationCapture =
            CalibrationRecordingContext.readForRecording(file) ?: CalibrationCapture()

        internal fun sourceIdentity(file: File, cancelCheck: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { cancelCheck(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return "sha256:${digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }}"
        }
    }
}
