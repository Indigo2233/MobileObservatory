package com.indigo.mobileobservatory.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.components.FocuserStopButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocuserStopInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun stoppingTelemetryDuringPressNeverChangesStopIntoZero() {
        val moving = mutableStateOf(true)
        var stops = 0
        compose.setContent { MaterialTheme { FocuserStopButton(moving.value, { stops++ }, Modifier.size(48.dp)) } }
        val button = compose.onNodeWithContentDescription(compose.activity.getString(R.string.wf_focus_stop))
        button.performTouchInput { down(center) }
        compose.runOnIdle { moving.value = false }
        button.performTouchInput { up() }
        assertEquals(1, stops)
        button.assertIsEnabled().performClick()
        assertEquals(2, stops)
    }
}
