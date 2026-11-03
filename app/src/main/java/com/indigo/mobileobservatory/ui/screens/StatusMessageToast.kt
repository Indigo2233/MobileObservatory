package com.indigo.mobileobservatory.ui.screens

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel

/**
 * Shows the device status message as a short toast.
 *
 * The capture picture carries only the save notice, so the remaining device
 * messages need a channel that does not cover the live view.
 */
@Composable
internal fun StatusMessageToast(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val message by viewModel.statusMessage.collectAsStateWithPageLifecycle()
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusMessage(message)
        }
    }
}
