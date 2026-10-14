package com.indigo.mobileobservatory.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Suspends retained-page UI subscriptions while [ObservatoryPageHost] is covered. */
@Composable
internal fun <T> StateFlow<T>.collectAsStateWithPageLifecycle(): State<T> =
    collectAsStateWithLifecycle()
