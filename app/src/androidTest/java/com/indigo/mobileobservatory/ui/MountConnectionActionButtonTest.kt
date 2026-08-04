package com.indigo.mobileobservatory.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountTransportType
import com.indigo.mobileobservatory.ui.components.MountConnectionActionButton
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MountConnectionActionButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bluetoothConnectingButtonCancelsConnection() {
        var cancelled = false
        val state = MountConnectionUiState.from(
            connection = MountConnectionState.Connecting,
            transport = MountTransportType.BLUETOOTH,
            busy = true
        )

        compose.setContent {
            MaterialTheme {
                MountConnectionActionButton(
                    state = state,
                    onConnect = {},
                    onCancel = { cancelled = true },
                    onDisconnect = {}
                )
            }
        }

        compose.onNodeWithText("Cancel connection")
            .assertIsEnabled()
            .performClick()
        assertTrue(cancelled)
    }
}
