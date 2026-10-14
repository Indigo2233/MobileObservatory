package com.indigo.mobileobservatory.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.ui.screens.StarMapScreen
import com.indigo.mobileobservatory.ui.screens.StarMapTarget
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real screen/source-selection behavior with deterministic time; no hardware or real WCS claimed. */
class StarMapPointingFollowInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private var saved: Map<String, *> = emptyMap<String, Any>()
    private var initialized = false
    private fun relevant(key: String) = key.startsWith("star_map_") || key == StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM
    private var clock = System.currentTimeMillis()
    private val target = StarMapTarget("Fixed observing target", 1.0, 10.0, "J2000")
    private val sources = mutableStateOf<List<FovPointingCandidate>>(emptyList())
    private val targets = mutableListOf<StarMapTarget>()
    private var refreshes = 0

    @Before fun setup() {
        org.junit.Assume.assumeTrue(BuildConfig.STELLARIUM_ENABLED)
        saved = prefs.all.filterKeys(::relevant)
        val editor = prefs.edit()
        prefs.all.keys.filter(::relevant).forEach { editor.remove(it) }
        editor.putString("star_map_active_train", "primary")
            .putString("star_map_fov_mode", "EYEPIECE").putString("star_map_secondary_fov_mode", "EYEPIECE")
            .putBoolean("star_map_show_fov_overlay", true).putBoolean("star_map_follow_mount", false).commit()
        initialized = true
        compose.mainClock.autoAdvance = false
    }
    @After fun restore() {
        if (!initialized) return
        compose.mainClock.autoAdvance = true
        val edit = prefs.edit()
        prefs.all.keys.filter(::relevant).forEach { edit.remove(it) }
        saved.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> Unit
        } }
        edit.commit()
    }
    private fun candidate(path: String, name: String) = FovPointingCandidate(path,
        FovSkyAnchor(if (path == "primary") 5.0 else 6.0, 20.0, "J2000"), name,
        FovPointingKind.CALIBRATED_OPTICAL, clock, clock, .005, true, "1", name)
    private fun start(vararg paths: Pair<String, String>) {
        sources.value = paths.map { candidate(it.first, it.second) }
        compose.setContent { MaterialTheme { StarMapScreen(null, null,
            mountConnected = false, mountBusy = false, onGoto = {}, onBack = {},
            observerSite = ObserverSite(31.0, 121.0),
            cameraGeometry = ConnectedOpticsGeometry.fromSensorRoi(3.76f, 3008, 3008,
                com.indigo.mobileobservatory.camera.Roi(0, 0, 3008, 3008), 1, "main"),
            cameraPixelSizeUm = 3.76f, cameraFrameWidthPx = 3008, cameraFrameHeightPx = 3008,
            selectedObservationTarget = target, pointingSources = sources.value, pointingUtcNow = { clock },
            onTargetSelected = { targets += it }, onPushTo = { refreshes++ }) } }
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(45_000) {
            compose.mainClock.advanceTimeBy(32)
            compose.onAllNodesWithText(context.getString(R.string.star_map_use_center)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription(context.getString(R.string.star_map_observing_panel)).performClick()
        compose.mainClock.advanceTimeBy(500)
        // Refresh after engine/Activity startup, without relying on the wall-clock startup duration.
        compose.runOnIdle {
            clock = System.currentTimeMillis()
            sources.value = paths.map { candidate(it.first, it.second) }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test fun calibratedPhoneSourceWithoutMountOffersFollowAndCenterAndShowsSourceAge() {
        prefs.edit().putString("star_map_fov_mode", "SENSOR").commit()
        start("primary" to "PHONE_PLATE")
        compose.onNodeWithTag("pointing-follow").assertIsEnabled().assertIsOff().performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("pointing-follow").assertIsOn()
        compose.onNodeWithTag("pointing-center").assertIsEnabled()
        compose.onNodeWithTag("pointing-follow-source").assertTextContains(context.getString(R.string.wf_follow_source_phone), substring = true)
        compose.runOnIdle { assertTrue(prefs.getBoolean("star_map_follow_mount", false)) }
    }

    @Test fun staleSourceStopsFollowAndOffersExplicitRefreshWithoutChangingTarget() {
        start("primary" to "PHONE_PLATE")
        compose.onNodeWithTag("pointing-follow").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { clock += 31_000 }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("pointing-follow").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("pointing-center").assertIsNotEnabled()
        compose.onNodeWithTag("pointing-follow-source").assertTextEquals(context.getString(R.string.wf_fov_stale))
        compose.onAllNodesWithText(context.getString(R.string.wf_follow_refresh)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(target, targets.last()); assertFalse(prefs.getBoolean("star_map_follow_mount", true)) }
    }

    @Test fun switchingOpticalPathUsesItsOwnSourceAndMissingPathCannotFallBack() {
        start("primary" to "PHONE_PLATE", "secondary" to "AUXILIARY_PLATE")
        compose.onNodeWithTag("pointing-follow").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText(context.getString(R.string.star_map_train_secondary)).performScrollTo()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(context.getString(R.string.star_map_train_secondary)).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(context.getString(R.string.star_map_train_secondary)).assertIsSelected()
        compose.runOnIdle { assertEquals("secondary", prefs.getString("star_map_active_train", null)) }
        compose.onNodeWithTag("pointing-center").assertIsEnabled()
        compose.onNodeWithTag("pointing-follow-source").assertTextContains(context.getString(R.string.wf_follow_source_aux), substring = true)
        compose.runOnIdle { sources.value = sources.value.filter { it.opticalPathId == "primary" } }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("pointing-follow").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("pointing-center").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(target, targets.last()); assertFalse(prefs.getBoolean("star_map_follow_mount", true)) }
    }
}
