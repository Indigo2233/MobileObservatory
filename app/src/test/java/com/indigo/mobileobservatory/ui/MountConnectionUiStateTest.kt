package com.indigo.mobileobservatory.ui

import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MountConnectionUiStateTest {
    @Test
    fun bluetoothConnectingOffersCancellation() {
        val ui = MountConnectionUiState.from(
            connection = MountConnectionState.Connecting,
            transport = MountTransportType.BLUETOOTH,
            busy = true
        )

        assertEquals(MountConnectionAction.CANCEL, ui.action)
        assertTrue(ui.actionEnabled)
        assertTrue(ui.showProgress)
    }

    @Test
    fun connectedMountOffersDisconnect() {
        val ui = MountConnectionUiState.from(
            connection = MountConnectionState.Connected,
            transport = MountTransportType.TCP,
            busy = false
        )

        assertEquals(MountConnectionAction.DISCONNECT, ui.action)
        assertTrue(ui.actionEnabled)
    }

    @Test
    fun busyNonBluetoothConnectionCannotBeCancelled() {
        val ui = MountConnectionUiState.from(
            connection = MountConnectionState.Connecting,
            transport = MountTransportType.TCP,
            busy = true
        )

        assertEquals(MountConnectionAction.CONNECT, ui.action)
        assertFalse(ui.actionEnabled)
    }

    @Test
    fun connectionErrorIsExposedToUi() {
        val ui = MountConnectionUiState.from(
            connection = MountConnectionState.Error("Permission denied"),
            transport = MountTransportType.BLUETOOTH,
            busy = false
        )

        assertEquals(MountConnectionAction.CONNECT, ui.action)
    }
}
