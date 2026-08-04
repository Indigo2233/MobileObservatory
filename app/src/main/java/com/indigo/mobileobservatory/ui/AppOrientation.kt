package com.indigo.mobileobservatory.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * App default is portrait. Only imaging surfaces stay landscape.
 */
enum class AppOrientationMode {
    PORTRAIT,
    LANDSCAPE
}

@Composable
fun RememberAppOrientation(mode: AppOrientationMode) {
    val activity = LocalContext.current.findActivityOrNull()
    DisposableEffect(activity, mode) {
        val orientation = when (mode) {
            AppOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            AppOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        activity?.requestedOrientation = orientation
        onDispose { }
    }
}

tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}
