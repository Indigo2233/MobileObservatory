package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.ui.MountConnectionAction
import com.indigo.mobileobservatory.ui.MountConnectionUiState

@Composable
fun MountConnectionActionButton(
    state: MountConnectionUiState,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit
) {
    Button(
        onClick = {
            when (state.action) {
                MountConnectionAction.CONNECT -> onConnect()
                MountConnectionAction.CANCEL -> onCancel()
                MountConnectionAction.DISCONNECT -> onDisconnect()
            }
        },
        enabled = state.actionEnabled
    ) {
        if (state.showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            stringResource(
                when (state.action) {
                    MountConnectionAction.CONNECT -> R.string.connect
                    MountConnectionAction.CANCEL -> R.string.cancel_connection
                    MountConnectionAction.DISCONNECT -> R.string.disconnect
                }
            )
        )
    }
}
