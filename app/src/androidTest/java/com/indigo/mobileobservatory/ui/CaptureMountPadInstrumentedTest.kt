package com.indigo.mobileobservatory.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.ui.components.CaptureMountPad
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CaptureMountPadInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()

    @Test fun holdingThenReleasingOrCancellingSendsOneStartAndOneStop() {
        val starts = mutableListOf<MountDirection>()
        val stops = mutableListOf<MountDirection>()
        compose.setContent {
            MaterialTheme { CaptureMountPad(MountSlewRate.DEFAULT, true, starts::add, stops::add, {}) }
        }
        MountDirection.entries.forEach { direction ->
            val button = compose.onNodeWithTag("capture-mount-${direction.name.lowercase()}")
            button.performTouchInput { down(center) }
            compose.runOnIdle { assertEquals(direction, starts.last()); assertEquals(starts.size - 1, stops.size) }
            button.performTouchInput { up() }
            compose.runOnIdle { assertEquals(starts, stops) }
        }
        compose.onNodeWithTag("capture-mount-east").performTouchInput { down(center) }
        compose.onNodeWithTag("capture-mount-east").performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(starts, stops); assertEquals(5, stops.size) }
    }

    @Test fun hidingOrDisablingAHeldPadStopsOnlyItsOwnPress() {
        val visible = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        var starts = 0
        var stops = 0
        compose.setContent {
            MaterialTheme { Box {
                if (visible.value) CaptureMountPad(MountSlewRate.DEFAULT, enabled.value, { starts++ }, { stops++ }, {})
            } }
        }
        compose.onNodeWithTag("capture-mount-north").performTouchInput { down(center) }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, stops); visible.value = true }
        compose.onNodeWithTag("capture-mount-north").performTouchInput { cancel() }
        compose.onNodeWithTag("capture-mount-west").performTouchInput { down(center) }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("capture-mount-west").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, starts); assertEquals(2, stops) }
        compose.onNodeWithTag("capture-mount-west").performTouchInput { cancel() }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertEquals(2, stops) }
    }

    @Test fun leavingThePageStopsAHeldDirection() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var stops = 0
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { CaptureMountPad(MountSlewRate.DEFAULT, true, {}, { stops++ }, {}) }
            }
        }
        compose.onNodeWithTag("capture-mount-south").performTouchInput { down(center) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithTag("capture-mount-south").performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun speedMenuSelectsTheSharedRatesAndIdleDismissalDoesNotSendStop() {
        val rate = mutableStateOf(MountSlewRate.DEFAULT)
        var stops = 0
        compose.setContent {
            MaterialTheme { CaptureMountPad(rate.value, true, {}, { stops++ }, { rate.value = it }) }
        }
        compose.onNodeWithTag("capture-mount-speed").performClick()
        compose.onNodeWithTag("capture-mount-rate-RATE_8X").assertIsSelected()
        compose.onNodeWithTag("capture-mount-rate-RATE_MAX").performScrollTo().performClick()
        compose.onNodeWithTag("capture-mount-speed").assert(hasText(MountSlewRate.RATE_MAX.label))
        compose.runOnIdle { assertEquals(MountSlewRate.RATE_MAX, rate.value); assertEquals(0, stops) }
    }
}
