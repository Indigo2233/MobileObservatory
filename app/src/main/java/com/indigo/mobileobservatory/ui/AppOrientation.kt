package com.indigo.mobileobservatory.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Capture and guiding prefer landscape; browsing workspaces and child pages
 * allow sensor rotation. The foreground shell is the only owner.
 */
enum class AppOrientationMode {
    PORTRAIT,
    LANDSCAPE,
    SENSOR
}

@Composable
fun RememberAppOrientation(mode: AppOrientationMode) {
    val activity = LocalContext.current.findActivityOrNull()
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            activity?.requestedOrientation = previous
        }
    }
    LaunchedEffect(activity, mode) {
        // A workspace transition makes one request; restoring the original orientation
        // between modes would cause a second, unrelated configuration transition.
        activity?.requestedOrientation = when (mode) {
            AppOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            AppOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            AppOrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }
}

tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}
