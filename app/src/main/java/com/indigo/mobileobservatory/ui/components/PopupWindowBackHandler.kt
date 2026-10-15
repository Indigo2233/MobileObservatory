package com.indigo.mobileobservatory.ui.components

import android.os.Build
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

/**
 * Call inside popup content. Material 3 1.1.2 handles legacy key events in its own
 * window, but Android 13+ also needs a callback on that window's dispatcher.
 * An activity-only BackHandler cannot intercept the focused popup's system Back.
 */
@Composable
internal fun PopupWindowBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    BackHandler(enabled = enabled) { currentOnBack() }
    if (Build.VERSION.SDK_INT >= 33 && enabled) {
        val view = LocalView.current
        DisposableEffect(view) {
            val callback = OnBackInvokedCallback { currentOnBack() }
            var dispatcher: OnBackInvokedDispatcher? = null
            fun unregister() {
                dispatcher?.unregisterOnBackInvokedCallback(callback)
                dispatcher = null
            }
            fun register() {
                unregister()
                dispatcher = view.findOnBackInvokedDispatcher()
                // Default priority leaves a subsequently opened IME in charge of its own Back.
                dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            }
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = register()
                override fun onViewDetachedFromWindow(v: View) = unregister()
            }
            view.addOnAttachStateChangeListener(listener)
            if (view.isAttachedToWindow) register()
            onDispose { view.removeOnAttachStateChangeListener(listener); unregister() }
        }
    }
}
