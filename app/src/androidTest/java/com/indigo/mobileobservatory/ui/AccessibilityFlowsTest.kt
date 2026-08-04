package com.indigo.mobileobservatory.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountTransportType
import com.indigo.mobileobservatory.ui.components.MountConnectionActionButton
import com.indigo.mobileobservatory.ui.components.StarMapBackButton
import com.indigo.mobileobservatory.ui.components.StarMapGotoConfirmation
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bluetoothCancellationRemainsUsableAtTwoHundredPercentFontScale() {
        var cancelled = false
        val state = MountConnectionUiState.from(
            connection = MountConnectionState.Connecting,
            transport = MountTransportType.BLUETOOTH,
            busy = true
        )

        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme {
                    MountConnectionActionButton(
                        state = state,
                        onConnect = {},
                        onCancel = { cancelled = true },
                        onDisconnect = {}
                    )
                }
            }
        }

        compose.onNodeWithText("Cancel connection")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertTrue(cancelled)
    }

    @Test
    fun talkBackCanReturnFromStarMapAndConfirmGoto() {
        var returned = false
        var confirmed = false

        compose.setContent {
            MaterialTheme {
                StarMapBackButton(onBack = { returned = true })
            }
        }
        compose.onNodeWithContentDescription("Back to mount").performClick()
        assertTrue(returned)

        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme {
                    StarMapGotoConfirmation(
                        targetName = "M42",
                        coordinates = "RA 05:35  Dec -05:23",
                        onConfirm = { confirmed = true },
                        onDismiss = {}
                    )
                }
            }
        }
        compose.onNodeWithText("Confirm mount slew").assertIsDisplayed()
        compose.onNodeWithText("Run GOTO").assertIsEnabled().performClick()
        assertTrue(confirmed)
    }

    @Test
    fun simplifiedChineseCoreActionsUseLocalizedResources() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }
        val localizedContext = baseContext.createConfigurationContext(configuration)

        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration
            ) {
                MaterialTheme {
                    StarMapGotoConfirmation(
                        targetName = "M42",
                        coordinates = "RA 05:35  Dec -05:23",
                        onConfirm = {},
                        onDismiss = {}
                    )
                }
            }
        }

        compose.onNodeWithText(localizedContext.getString(R.string.confirm_mount_slew)).assertIsDisplayed()
        compose.onNodeWithText(localizedContext.getString(R.string.execute_goto)).assertIsDisplayed()
        compose.onNodeWithText(localizedContext.getString(R.string.cancel)).assertIsDisplayed()
    }
}
