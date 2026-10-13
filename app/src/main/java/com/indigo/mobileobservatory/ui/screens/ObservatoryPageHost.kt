package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** A covered task retains drafts/results. Hidden tasks cannot receive system Back. */
internal val LocalObservatoryPageVisible = staticCompositionLocalOf { true }

@Composable
internal fun rememberPageForeground(): Boolean {
    val owner = LocalLifecycleOwner.current
    var started by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return LocalObservatoryPageVisible.current && started
}

@Composable
internal fun ObservatoryPageHost(visible: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember {
        object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
    }
    DisposableEffect(parent, visible) {
        fun update() {
            owner.registry.currentState = if (visible) parent.lifecycle.currentState else Lifecycle.State.CREATED
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        parent.lifecycle.addObserver(observer)
        update()
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner) { onDispose { owner.registry.currentState = Lifecycle.State.DESTROYED } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalObservatoryPageVisible provides visible) {
        Box(if (visible) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds().clearAndSetSemantics {}) { content() }
    }
}
