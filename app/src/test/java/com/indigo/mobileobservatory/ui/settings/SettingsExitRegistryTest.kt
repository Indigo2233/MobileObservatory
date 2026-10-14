package com.indigo.mobileobservatory.ui.settings

import com.indigo.mobileobservatory.ui.components.SettingsExitRegistry
import com.indigo.mobileobservatory.ui.components.SettingsExitState
import org.junit.Assert.*
import org.junit.Test

class SettingsExitRegistryTest {
    @Test fun workspaceChangeIsDeferredUntilTheVisibleEditorMakesAChoice() {
        val registry = SettingsExitRegistry(); val editor = SettingsExitState().apply { dirty = true }
        var navigations = 0; var discarded = 0
        editor.clearDraft = { discarded++ }; registry.register(editor, true)
        registry.request("workspace-CAPTURE") { navigations++ }
        assertEquals(0, navigations); assertEquals("workspace-CAPTURE", editor.pendingExit)
        editor.keepEditing(); assertEquals(0, navigations)
        registry.request("workspace-CAPTURE") { navigations++ }; editor.perform("workspace-CAPTURE")
        assertEquals(1, navigations); assertEquals(1, discarded)
    }
    @Test fun hiddenEditorsDoNotInterceptAnotherWorkspaceAndTopSheetOwnsTheChoice() {
        val registry = SettingsExitRegistry(); val hidden = SettingsExitState().apply { dirty = true }
        var navigations = 0
        registry.register(hidden, false); registry.request("workspace") { navigations++ }
        assertEquals(1, navigations); assertNull(hidden.pendingExit)
        val base = SettingsExitState().apply { dirty = true }; val sheet = SettingsExitState().apply { dirty = true }
        registry.register(base, true); registry.register(sheet, true)
        registry.request("workspace") { navigations++ }
        assertEquals("workspace", sheet.pendingExit); assertNull(base.pendingExit)
    }
    @Test fun restoredPendingWorkspaceExitReceivesFreshActionWithoutAnotherClick() {
        val registry = SettingsExitRegistry(); var destination = "equipment"
        registry.registerAction("workspace-CAPTURE") { destination = "capture" }
        val restored = SettingsExitState().apply { dirty = true; pendingExit = "workspace-CAPTURE" }
        registry.register(restored, true)
        restored.perform(requireNotNull(restored.pendingExit))
        assertEquals("capture", destination)
    }
}
