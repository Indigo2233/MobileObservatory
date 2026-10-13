package com.indigo.mobileobservatory.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R

@Stable
class SettingsExitState {
    var dirty by mutableStateOf(false)
    var canSave by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var safetyAction by mutableStateOf<Pair<String, () -> Unit>?>(null)
    var pendingExit by mutableStateOf<String?>(null)
    var awaitingSave by mutableStateOf(false)
    var save: () -> Unit = {}
    var clearDraft: () -> Unit = {}
    private val actions = mutableMapOf<String, () -> Unit>()
    fun register(id: String, action: () -> Unit) { actions[id] = action }
    fun request(id: String) {
        if (saving) { pendingExit = id; awaitingSave = true; return }
        if (dirty) { pendingExit = id; awaitingSave = false } else perform(id)
    }
    fun perform(id: String) {
        pendingExit = null
        awaitingSave = false
        clearDraft()
        actions[id]?.invoke()
    }
    fun keepEditing() { pendingExit = null; awaitingSave = false }
}

@Composable
fun rememberSettingsExitState(): SettingsExitState = rememberSaveable(saver = listSaver(
    save = { listOf(it.pendingExit.orEmpty(), it.awaitingSave.toString()) },
    restore = { values -> SettingsExitState().apply { pendingExit = values[0].ifEmpty { null }; awaitingSave = values[1].toBoolean() } }
)) { SettingsExitState() }

/** Used by system Back, toolbar Up, sheet dismiss and an explicit close button. */
@Composable
fun SettingsExitConfirmation(state: SettingsExitState) {
    LaunchedEffect(state.pendingExit, state.awaitingSave, state.dirty, state.saving, state.saveError) {
        if (state.awaitingSave && !state.saving) {
            if (!state.dirty) state.pendingExit?.let(state::perform)
            else if (state.saveError != null) state.awaitingSave = false
        }
    }
    val target = state.pendingExit
    if (target != null && !state.awaitingSave) {
        AlertDialog(
            onDismissRequest = state::keepEditing,
            title = { Text(stringResource(R.string.wf_edit_unsaved_title)) },
            text = { Column {
                Text(stringResource(R.string.wf_edit_unsaved_message))
                state.safetyAction?.let { (label, stop) -> Button(onClick = stop) { Text(label) } }
            } },
            confirmButton = {
                TextButton(onClick = { state.awaitingSave = true; state.save() }, enabled = state.canSave && !state.saving) {
                    Text(stringResource(R.string.wf_edit_save_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.perform(target) }) { Text(stringResource(R.string.wf_edit_discard)) }
                TextButton(onClick = state::keepEditing) { Text(stringResource(R.string.wf_edit_keep)) }
            }
        )
    }
}
