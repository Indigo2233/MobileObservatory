package com.indigo.mobileobservatory.ui.settings

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class SettingsDraftTest {
    @Test fun unchangedAndRestoredTextDoNotRequireDiscardConfirmation() {
        val initial = SettingsDraft("usb:1", mapOf("fine" to "10"))
        assertFalse(initial.dirty)
        assertFalse(initial.edit("fine", "10").dirty)
        assertTrue(initial.edit("fine", "bad").dirty)
        assertFalse(initial.edit("fine", "25").edit("fine", "10").dirty)
    }

    @Test fun savedStateRoundTripKeepsInvalidInputAndDeviceIdentity() {
        val initial = SettingsDraft("focuser:123:设备", mapOf("fine" to "10", "name" to "L:R"))
            .edit("fine", "-").edit("name", "中文\n文本")
        assertEquals(initial, SettingsDraft.decode(initial.encode()))
    }

    @Test fun laterSaveCannotOverwriteEditsMadeAfterItsSnapshot() {
        val snapshot = SettingsDraft("usb:1", mapOf("fine" to "10")).edit("fine", "20")
        val later = snapshot.edit("fine", "30")
        val saved = later.saved(mapOf("fine" to "20"), snapshot.revision)
        assertEquals("30", saved.fields["fine"])
        assertEquals("20", saved.original["fine"])
        assertTrue(saved.dirty)
    }

    @Test fun reconnectOrTelemetryInitializationNeverReloadsTheOpenDraft() {
        val vm = SettingsDraftViewModel(SavedStateHandle())
        vm.initialize("usb:1", mapOf("fine" to "10"))
        vm.edit("fine", "25")
        vm.initialize("usb:2", mapOf("fine" to "50"))
        assertEquals("usb:1", vm.draft.value?.deviceKey)
        assertEquals("25", vm.draft.value?.fields?.get("fine"))
    }

    @Test fun failureKeepsDirtyDraftAndClearsBusyState() {
        val vm = SettingsDraftViewModel(SavedStateHandle())
        vm.initialize("usb:1", mapOf("fine" to "10"))
        vm.edit("fine", "25")
        vm.beginSave()
        vm.failSave("connection lost")
        assertFalse(vm.saving.value)
        assertTrue(vm.draft.value!!.dirty)
        assertEquals("25", vm.draft.value!!.fields["fine"])
    }

    @Test fun processRestorationKeepsTheDraftButDoesNotRestoreAnInFlightDeviceWrite() {
        val state = SavedStateHandle()
        val old = SettingsDraftViewModel(state)
        old.initialize("usb:1", mapOf("fine" to "10"))
        old.edit("fine", "25")
        old.beginSave()
        val restored = SettingsDraftViewModel(SavedStateHandle(mapOf("draft" to state.get<ArrayList<String>>("draft"))))
        assertFalse(restored.saving.value)
        assertEquals("25", restored.draft.value!!.fields["fine"])
        assertTrue(restored.draft.value!!.dirty)
    }

    @Test fun closingAContextualEditorCannotEraseItsStillMountedEquipmentParent() {
        val vm = SettingsDraftViewModel(SavedStateHandle())
        vm.initialize("usb:1", mapOf("fine" to "10"))
        vm.attachEditor()
        vm.attachEditor()
        vm.edit("fine", "25")
        vm.leaveEditor()
        vm.detachEditor()
        assertEquals("10", vm.draft.value!!.fields["fine"])
        assertFalse(vm.draft.value!!.dirty)
        vm.leaveEditor()
        assertNull(vm.draft.value)
    }
}
