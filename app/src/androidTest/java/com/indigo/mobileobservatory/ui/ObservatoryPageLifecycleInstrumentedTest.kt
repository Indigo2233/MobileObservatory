package com.indigo.mobileobservatory.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.indigo.mobileobservatory.ui.screens.ObservatoryPageHost
import com.indigo.mobileobservatory.ui.screens.collectAsStateWithPageLifecycle
import com.indigo.mobileobservatory.ui.screens.collectAsStateWithHostLifecycle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ObservatoryPageLifecycleInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun coveredPageStopsCollectingAndResumesWithLatestState() {
        val source = MutableStateFlow(1)
        val subscriptions = AtomicInteger()
        val counted = CountingStateFlow(source, subscriptions)
        var visible by mutableStateOf(true)
        compose.setContent {
            ObservatoryPageHost(visible) {
                val value by counted.collectAsStateWithPageLifecycle()
                Text("value-$value")
            }
        }

        compose.waitUntil { subscriptions.get() == 1 }
        compose.onNodeWithText("value-1").assertExists()
        compose.runOnIdle { visible = false }
        compose.waitUntil { subscriptions.get() == 0 }
        compose.onNodeWithText("value-1").assertDoesNotExist()

        source.value = 2
        compose.runOnIdle { visible = true }
        compose.waitUntil { subscriptions.get() == 1 }
        compose.onNodeWithText("value-2").assertExists()
        assertEquals(1, subscriptions.get())
    }

    @Test fun hiddenTaskKeepsHostReadingsWhileUiSubscriptionsPause() {
        val source = MutableStateFlow(1)
        val uiSubscriptions = AtomicInteger()
        val taskSubscriptions = AtomicInteger()
        val uiFlow = CountingStateFlow(source, uiSubscriptions)
        val taskFlow = CountingStateFlow(source, taskSubscriptions)
        val taskReading = AtomicInteger()
        var visible by mutableStateOf(true)
        compose.setContent {
            ObservatoryPageHost(visible) {
                val ui by uiFlow.collectAsStateWithPageLifecycle()
                val task by taskFlow.collectAsStateWithHostLifecycle()
                val observedTask = task
                SideEffect { taskReading.set(observedTask) }
                Text("ui-$ui")
            }
        }
        compose.waitUntil { uiSubscriptions.get() == 1 && taskSubscriptions.get() == 1 }
        compose.runOnIdle { visible = false }
        compose.waitUntil { uiSubscriptions.get() == 0 }
        assertEquals(1, taskSubscriptions.get())
        source.value = 2
        compose.waitUntil { taskReading.get() == 2 }
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("ui-2").assertExists()
        assertEquals(1, taskSubscriptions.get())
    }

    private class CountingStateFlow<T>(
        private val source: StateFlow<T>,
        private val subscriptions: AtomicInteger
    ) : StateFlow<T> by source {
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<T>): Nothing {
            subscriptions.incrementAndGet()
            try {
                source.collect(collector)
            } finally {
                subscriptions.decrementAndGet()
            }
        }
    }
}
