package com.indigo.mobileobservatory.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Imaging (camera / guide / player) stays landscape; star map and other tabs
 * prefer portrait. Manifest uses fullSensor so these runtime requests can take effect.
 */
enum class AppOrientationMode {
    PORTRAIT,
    LANDSCAPE,
    SENSOR
}

@Composable
fun RememberAppOrientation(mode: AppOrientationMode) {
    val activity = LocalContext.current.findActivityOrNull()
    DisposableEffect(activity, mode) {
        val previous = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = when (mode) {
            AppOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            AppOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            AppOrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        onDispose {
            activity?.requestedOrientation = previous
        }
    }
}

tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}
