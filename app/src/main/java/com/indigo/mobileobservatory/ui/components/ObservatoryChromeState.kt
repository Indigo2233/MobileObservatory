package com.indigo.mobileobservatory.ui.components

import androidx.compose.runtime.*
import com.indigo.mobileobservatory.ui.screens.rememberPageForeground

/** The current foreground editor owns cross-workspace navigation, as it owns Back/Up. */
@Stable
class SettingsExitRegistry {
    private val editors = linkedMapOf<SettingsExitState, Boolean>()
    private val actions = linkedMapOf<String, () -> Unit>()
    fun registerAction(id: String, action: () -> Unit) {
        actions[id] = action; editors.keys.forEach { it.register(id, action) }
    }
    fun register(state: SettingsExitState, visible: Boolean) {
        editors[state] = visible; actions.forEach { (id, action) -> state.register(id, action) }
    }
    fun unregister(state: SettingsExitState) { editors.remove(state) }
    fun request(id: String, action: () -> Unit) {
        registerAction(id, action)
        val editor = editors.entries.lastOrNull { it.value && (it.key.dirty || it.key.saving) }?.key
        if (editor == null) action() else { editor.register(id, action); editor.request(id) }
    }
}

val LocalSettingsExitRegistry = staticCompositionLocalOf<SettingsExitRegistry?> { null }

@Composable
fun RegisterSettingsExitGuard(state: SettingsExitState) {
    val registry = LocalSettingsExitRegistry.current
    val visible = rememberPageForeground()
    SideEffect { registry?.register(state, visible) }
    DisposableEffect(registry, state) { onDispose { registry?.unregister(state) } }
}

@Stable
class ObservatoryImmersion {
    private val requests = mutableStateMapOf<Any, Boolean>()
    val active get() = requests.values.any { it }
    fun update(token: Any, immersive: Boolean) { requests[token] = immersive }
    fun remove(token: Any) { requests.remove(token) }
    /**
     * A full-screen picture covers the device bar as well. The bar carries the
     * settings entry, which the picture itself does not offer.
     */
    private val pictureRequests = mutableStateMapOf<Any, Boolean>()
    val pictureFullScreen get() = pictureRequests.values.any { it }
    fun updatePicture(token: Any, fullScreen: Boolean) { pictureRequests[token] = fullScreen }
    fun removePicture(token: Any) { pictureRequests.remove(token) }
}

val LocalObservatoryImmersion = staticCompositionLocalOf<ObservatoryImmersion?> { null }

/** Only explicit preview/player immersion in the visible page can hide workspace navigation. */
@Composable
fun ReportObservatoryImmersion(immersive: Boolean) {
    val state = LocalObservatoryImmersion.current
    val token = remember { Any() }
    val foreground = rememberPageForeground()
    SideEffect { state?.update(token, immersive && foreground) }
    DisposableEffect(state, token) { onDispose { state?.remove(token) } }
}

/** A full-screen picture covers the device bar and the workspace navigation. */
@Composable
fun ReportObservatoryPictureFullScreen(fullScreen: Boolean) {
    val state = LocalObservatoryImmersion.current
    val token = remember { Any() }
    val foreground = rememberPageForeground()
    SideEffect { state?.updatePicture(token, fullScreen && foreground) }
    DisposableEffect(state, token) { onDispose { state?.removePicture(token) } }
}
