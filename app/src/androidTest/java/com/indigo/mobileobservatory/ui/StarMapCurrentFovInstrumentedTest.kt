package com.indigo.mobileobservatory.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.pointing.PointingMountCapabilities
import com.indigo.mobileobservatory.ui.screens.StarMapScreen
import com.indigo.mobileobservatory.ui.screens.StarMapTarget
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises native source selection, the WebView bridge and actual projected FOV polygons. */
class StarMapCurrentFovInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private var saved: Map<String, *> = emptyMap<String, Any>()
    private var initialized = false
    private lateinit var web: WebView
    private var clock = System.currentTimeMillis()
    private val coordinates = mutableStateOf<MountCoordinates?>(MountCoordinates(5.0, 20.0))
    private val connected = mutableStateOf(true)
    private val readable = mutableStateOf(true)
    private val sources = mutableStateOf<List<FovPointingCandidate>>(emptyList())
    private val offsetCrop = mutableStateOf(false)
    private val target = StarMapTarget("Fixed FOV target", 5.01, 20.01, "J2000", 74.0)
    private fun relevant(key: String) = key.startsWith("star_map_") || key == StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM

    @Before fun setup() {
        org.junit.Assume.assumeTrue(BuildConfig.STELLARIUM_ENABLED)
        saved = prefs.all.filterKeys(::relevant)
        val edit = prefs.edit()
        prefs.all.keys.filter(::relevant).forEach { edit.remove(it) }
        edit.putString("star_map_active_train", "primary").putString("star_map_fov_mode", "SENSOR")
            .putString("star_map_sensor_id", "ccd_imx585")
            .putBoolean("star_map_show_fov_overlay", true).putBoolean("star_map_follow_mount", false)
            .putBoolean("star_map_follow_phone", false).putBoolean("star_map_simulated_landscape", false).commit()
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

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun eval(script: String): String {
        val value = AtomicReference<String>()
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { value.set(it); done.countDown() }
        }
        assertTrue("WebView evaluation timed out", done.await(10, TimeUnit.SECONDS))
        return value.get()
    }

    private fun snapshot(): JSONObject = JSONObject(JSONTokener(eval("""
        JSON.stringify((function () {
            function layer(id) {
                var e = document.getElementById(id), p = e.querySelector('polygon');
                return {display: getComputedStyle(e).display, text: e.textContent,
                    points: p ? p.getAttribute('points') : '', left: e.style.left, top: e.style.top};
            }
            return {current: layer('fov-current'), target: layer('fov-target'),
                currentArgs: window.__currentFovArgs || null, targetArgs: window.__targetFovArgs || null};
        })())
    """.trimIndent())).nextValue() as String)

    private fun awaitState(predicate: (JSONObject) -> Boolean): JSONObject {
        var state = JSONObject()
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeBy(64)
            state = snapshot()
            predicate(state)
        }
        return state
    }

    private fun screenshot(name: String) {
        fun shell(command: String): String {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        val directory = "/sdcard/Download/polar-ui-screenshots"
        shell("mkdir -p $directory")
        shell("screencap -p $directory/$name.png")
    }

    private fun start() {
        compose.setContent { MaterialTheme { StarMapScreen(coordinates.value, null,
            mountConnected = connected.value,
            mountPointingCapabilities = PointingMountCapabilities(readCoordinates = readable.value),
            mountBusy = false, onGoto = {}, onBack = {},
            cameraGeometry = ConnectedOpticsGeometry.fromSensorRoi(2.9f, 3840, 2160,
                if (offsetCrop.value) Roi(1200, 500, 1000, 1000) else Roi(0, 0, 3840, 2160), 1, "test-camera"),
            cameraPixelSizeUm = 2.9f, cameraFrameWidthPx = 3840, cameraFrameHeightPx = 2160,
            selectedObservationTarget = target, pointingSources = sources.value, pointingUtcNow = { clock }) } }
        compose.waitUntil(45_000) {
            compose.mainClock.advanceTimeBy(32)
            compose.onAllNodesWithText(context.getString(R.string.star_map_use_center)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { web = requireNotNull(findWeb(compose.activity.window.decorView)) }
        eval("""
            ['Current', 'Target'].forEach(function (role) {
                ['Rect', 'Circle'].forEach(function (shape) {
                    var key = 'set' + role + shape + 'FovOverlay', original = MercStarMap[key];
                    MercStarMap[key] = function () {
                        window[role === 'Current' ? '__currentFovArgs' : '__targetFovArgs'] = Array.from(arguments);
                        return original.apply(this, arguments);
                    };
                });
            });
            MercStarMap.setLandscapeVisible(false);
            MercStarMap.centerOnRaDec(5.0, 20.0, 0, 'JNOW');
            MercStarMap.setFovDegrees(8.0, 0);
        """.trimIndent())
        compose.mainClock.advanceTimeBy(128)
    }

    @Test fun mountReadbackDrawsBothFramesWithoutSiteAndMotionKeepsTargetPinned() {
        start()
        val initial = awaitState { it.getJSONObject("current").getString("points").isNotEmpty() &&
            it.getJSONObject("target").getString("points").isNotEmpty() }
        assertTrue(initial.getJSONObject("current").getString("text").contains(context.getString(R.string.wf_fov_mount_estimate)))
        assertTrue(initial.getJSONObject("current").getString("text").contains(context.getString(R.string.wf_fov_north_up_reference)))
        screenshot("current-and-target-reference")
        compose.runOnIdle { coordinates.value = MountCoordinates(5.03, 20.1) }
        val moved = awaitState { !it.isNull("currentArgs") && it.getJSONArray("currentArgs").getDouble(4) == 5.03 }
        assertNotEquals(initial.getJSONObject("current").getString("points"), moved.getJSONObject("current").getString("points"))
        assertEquals(initial.getJSONObject("target").getString("points"), moved.getJSONObject("target").getString("points"))
        assertEquals(0.0, moved.getJSONArray("currentArgs").getDouble(7), 0.0)
        assertEquals(5.01, moved.getJSONArray("targetArgs").getDouble(3), 0.0)
        assertEquals("ICRF", moved.getJSONArray("targetArgs").getString(5))
        compose.onNodeWithContentDescription(context.getString(R.string.star_map_observing_panel)).performClick()
        compose.mainClock.advanceTimeBy(128)
        compose.onNodeWithTag("pointing-follow").assertIsNotEnabled()
        compose.onNodeWithTag("pointing-center").assertIsNotEnabled()
        compose.runOnIdle { connected.value = false }
        val disconnected = awaitState { it.getJSONObject("current").getString("display") == "none" }
        assertEquals("block", disconnected.getJSONObject("target").getString("display"))
    }

    @Test fun validOpticalPositionWinsAndUnknownAngleOrGeometryStillShowsAReference() {
        prefs.edit().putBoolean(PointingOpticsConfiguration.confirmationKey(OpticsTrainId.PRIMARY), true).commit()
        start()
        compose.runOnIdle {
            clock = System.currentTimeMillis()
            sources.value = listOf(FovPointingCandidate("primary", FovSkyAnchor(5.005, 20.005, "J2000"),
                "camera", FovPointingKind.DIRECT_OPTICAL, clock, clock, .005, true, positionAngleDeg = 37.0))
        }
        val optical = awaitState { !it.isNull("currentArgs") && it.getJSONArray("currentArgs").getDouble(7) == 37.0 }
        assertEquals("ICRF", optical.getJSONArray("currentArgs").getString(6))
        assertFalse(optical.getJSONObject("current").getString("text").contains(context.getString(R.string.wf_fov_mount_estimate)))
        assertTrue(optical.getJSONObject("current").getString("points").isNotEmpty())
        compose.runOnIdle { sources.value = listOf(sources.value.single().copy(positionAngleDeg = null)) }
        val unknown = awaitState { it.getJSONArray("currentArgs").getDouble(7) == 0.0 }
        assertTrue(unknown.getJSONObject("current").getString("text").contains(context.getString(R.string.wf_fov_north_up_reference)))
        assertEquals(optical.getJSONObject("target").getString("points"), unknown.getJSONObject("target").getString("points"))
        compose.runOnIdle { offsetCrop.value = true }
        val crop = awaitState { it.getJSONObject("current").getString("text").contains(context.getString(R.string.wf_fov_current_reference)) }
        assertFalse(crop.getJSONObject("current").getString("text").contains(context.getString(R.string.wf_fov_mount_estimate)))
        screenshot("optical-position-crop-reference")
        compose.runOnIdle { clock += 31_000 }
        compose.mainClock.advanceTimeBy(1_000)
        val stale = awaitState { it.getJSONObject("current").getString("text").contains(context.getString(R.string.wf_fov_mount_estimate)) }
        assertEquals("JNOW", stale.getJSONArray("currentArgs").getString(6))
    }

    @Test fun eyepieceReferenceClearsOnCapabilityLossAndMissingCoordinates() {
        prefs.edit().putString("star_map_fov_mode", "EYEPIECE").commit()
        start()
        awaitState { it.getJSONObject("current").getString("display") == "block" &&
            it.getJSONObject("current").getString("text").contains(context.getString(R.string.wf_fov_mount_estimate)) }
        compose.runOnIdle { readable.value = false }
        awaitState { it.getJSONObject("current").getString("display") == "none" }
        compose.runOnIdle { readable.value = true }
        awaitState { it.getJSONObject("current").getString("display") == "block" }
        compose.runOnIdle { coordinates.value = null }
        val missing = awaitState { it.getJSONObject("current").getString("display") == "none" }
        assertEquals("block", missing.getJSONObject("target").getString("display"))
    }
}
