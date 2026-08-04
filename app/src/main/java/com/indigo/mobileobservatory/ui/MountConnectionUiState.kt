package com.indigo.mobileobservatory.ui

import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountTransportType

enum class MountConnectionAction {
    CONNECT,
    CANCEL,
    DISCONNECT
}

data class MountConnectionUiState(
    val action: MountConnectionAction,
    val actionEnabled: Boolean,
    val showProgress: Boolean
) {
    companion object {
        fun from(
            connection: MountConnectionState,
            transport: MountTransportType,
            busy: Boolean
        ): MountConnectionUiState {
            val cancellable = connection is MountConnectionState.Connecting &&
                transport == MountTransportType.BLUETOOTH
            val connected = connection is MountConnectionState.Connected
            val action = when {
                cancellable -> MountConnectionAction.CANCEL
                connected -> MountConnectionAction.DISCONNECT
                else -> MountConnectionAction.CONNECT
            }
            return MountConnectionUiState(
                action = action,
                actionEnabled = !busy || cancellable,
                showProgress = busy
            )
        }
    }
}
