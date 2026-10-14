package com.indigo.mobileobservatory.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Raw text is retained, including temporarily invalid input. Device telemetry never reloads it. */
data class SettingsDraft(
    val deviceKey: String,
    val original: Map<String, String>,
    val fields: Map<String, String> = original,
    val revision: Int = 0
) {
    val dirty: Boolean get() = fields != original
    fun edit(key: String, value: String): SettingsDraft =
        if (fields[key] == value) this else copy(fields = fields + (key to value), revision = revision + 1)
    fun saved(applied: Map<String, String>, savedRevision: Int): SettingsDraft =
        copy(original = applied, fields = if (revision == savedRevision) applied else fields)

    fun encode(): ArrayList<String> = arrayListOf(deviceKey, revision.toString(), original.size.toString()).apply {
        original.toSortedMap().forEach { (key, value) -> add(key); add(value) }
        add(fields.size.toString())
        fields.toSortedMap().forEach { (key, value) -> add(key); add(value) }
    }

    companion object {
        fun decode(values: List<String>): SettingsDraft? = runCatching {
            var index = 0
            val key = values[index++]
            val revision = values[index++].toInt()
            fun readFields(): Map<String, String> = buildMap {
                val count = values[index++].toInt()
                require(count in 0..128)
                repeat(count) { put(values[index++], values[index++]) }
            }
            val original = readFields()
            val fields = readFields()
            require(index == values.size)
            SettingsDraft(key, original, fields, revision)
        }.getOrNull()
    }
}

/** UI drafts survive configuration changes and callbacks without becoming device state. */
class SettingsDraftViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private var editorViews = 0
    private val _draft = MutableStateFlow(savedState.get<ArrayList<String>>("draft")?.let(SettingsDraft::decode))
    val draft = _draft.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    fun attachEditor() { editorViews++ }
    fun detachEditor() { editorViews = (editorViews - 1).coerceAtLeast(0) }
    fun leaveEditor() {
        if (editorViews > 1) {
            // Equipment and its contextual sheet share one draft. Closing the sheet
            // must not remove the state of a still-mounted equipment editor.
            _draft.value?.let { update(it.copy(fields = it.original, revision = it.revision + 1)) }
            _error.value = null
        } else clear()
    }

    fun initialize(deviceKey: String, fields: Map<String, String>) {
        if (_draft.value == null) update(SettingsDraft(deviceKey, fields))
    }
    fun edit(key: String, value: String) {
        if (_saving.value) return
        _saved.value = false
        _error.value = null
        _draft.value?.edit(key, value)?.let(::update)
    }
    fun useForDevice(deviceKey: String, original: Map<String, String>) {
        if (_saving.value) return
        val current = _draft.value ?: return
        update(current.copy(deviceKey = deviceKey, original = original,
            fields = current.fields + ("_title" to original["_title"].orEmpty()), revision = current.revision + 1))
        _error.value = null
        _saved.value = false
    }
    fun beginSave(): SettingsDraft? {
        if (_saving.value) return null
        val snapshot = _draft.value ?: return null
        _saving.value = true
        _error.value = null
        _saved.value = false
        return snapshot
    }
    fun completeSave(snapshot: SettingsDraft, applied: Map<String, String>) {
        val current = _draft.value ?: return
        if (current.deviceKey != snapshot.deviceKey) return
        update(current.saved(applied, snapshot.revision))
        _saving.value = false
        _saved.value = true
    }
    fun failSave(message: String) { _saving.value = false; _error.value = message }
    fun clear() { _draft.value = null; savedState.remove<ArrayList<String>>("draft"); _saving.value = false; _error.value = null; _saved.value = false }
    private fun update(value: SettingsDraft) { _draft.value = value; savedState["draft"] = value.encode() }
}
