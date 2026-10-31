package com.indigo.mobileobservatory.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.indigo.mobileobservatory.astrometry.*
import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.camera.CameraInfo
import com.indigo.mobileobservatory.polar.*
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.ui.screens.PolarAlignmentScreen
import com.indigo.mobileobservatory.ui.screens.ObservatoryPageHost
import com.indigo.mobileobservatory.ui.screens.ObservatoryNavigation
import com.indigo.mobileobservatory.ui.screens.ObservatoryWorkspace
import com.indigo.mobileobservatory.ui.screens.ObservatoryDestination
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.TestName
import java.io.File
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant

/** Exercises the actual observing screen, with camera and solver boundaries supplied by the fixture. */
@RunWith(AndroidJUnit4::class)
class PolarWorkflowScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    @get:Rule val testName = TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = listOf("polar_latitude_deg", "polar_longitude_deg", "plate_focal_length_mm", "plate_pixel_size_um",
        "polar_manual_mode", "polar_continuous_estimator", "plate_sensor_id", "polar_exposure_seconds",
        "polar_refraction_adjustment", "polar_pressure_hpa", "polar_temperature_c", "polar_elevation_m",
        "polar_alignment_tolerance_arcmin", "polar_automatic_refresh", "polar_use_mount_hint",
        "polar_blind_fallback", "polar_east_direction", "polar_target_distance_deg",
        "polar_move_rate_deg_sec", "polar_search_radius_deg", "polar_monochrome_preview")
    private var saved = emptyMap<String, Any?>()
    private lateinit var image: File
    private val generated = mutableListOf<File>()

    @Before fun prepare() {
        val prefs = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        saved = keys.associateWith { prefs.all[it] }
        prefs.edit().putFloat("polar_latitude_deg", 30f).putFloat("polar_longitude_deg", 110f)
            .putFloat("plate_focal_length_mm", 100f).putFloat("plate_pixel_size_um", 2.9f)
            .putBoolean("polar_manual_mode", true).putBoolean("polar_continuous_estimator", false)
            .putBoolean("polar_automatic_refresh", true)
            .putBoolean("polar_refraction_adjustment", true).putFloat("polar_pressure_hpa", 1013.25f)
            .putFloat("polar_temperature_c", 15f).putFloat("polar_elevation_m", 0f)
            .putFloat("polar_alignment_tolerance_arcmin", 0f)
            .remove("plate_sensor_id").remove("polar_exposure_seconds").commit()
        image = File(context.cacheDir, "polar-screen-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff101820.toInt())
        val canvas = Canvas(bitmap); val paint = Paint().apply { color = 0xffd0e0ff.toInt() }
        for (i in 1..18) canvas.drawCircle((i * 19).toFloat(), (20 + i * 11).toFloat(), 2.5f, paint)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun screenshot(suffix: String = "") {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        compose.waitForIdle()
        fun shell(command: String): String {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        // connectedAndroidTest uninstalls the app before the workflow can pull app-scoped files.
        val directory = "/sdcard/Download/polar-ui-screenshots"
        val path = "$directory/${testName.methodName}$suffix.png"
        shell("mkdir -p $directory")
        shell("screencap -p $path")
        assertEquals(path, shell("ls $path").trim())
    }

    @After fun restore() {
        screenshot()
        val edit = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
        saved.forEach { (key, value) ->
            when (value) {
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                else -> edit.remove(key)
            }
        }
        edit.commit()
        image.delete()
        generated.forEach { it.delete() }
    }

    private fun screen(engine: ImagePlateSolver, fontScale: Float = 1f, compact: Boolean = false,
        chinese: Boolean = false, automaticMount: Boolean = false,
        pageVisible: () -> Boolean = { true }, stopSignal: () -> Long = { 0L },
        pagePresent: () -> Boolean = { true },
        prepareTracking: suspend () -> Unit = {}, capture: suspend (Float) -> File?) {
        if (automaticMount) context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
            .edit().putBoolean("polar_manual_mode", false).commit()
        val configuration = Configuration(context.resources.configuration).apply {
            if (chinese) setLocale(Locale.SIMPLIFIED_CHINESE)
        }
        val localizedResources = compose.activity.createConfigurationContext(configuration).resources
        val localized: Context = if (chinese) object : ContextWrapper(compose.activity) {
            override fun getResources() = localizedResources
        } else compose.activity
        compose.setContent { MobileObservatoryTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalContext provides localized, LocalConfiguration provides configuration) {
            Box(if (compact) Modifier.size(360.dp, 480.dp) else Modifier) {
            if (pagePresent()) ObservatoryPageHost(pageVisible()) {
            PolarAlignmentScreen(cameraConnected = true,
                cameraInfo = CameraInfo("Test camera", "polar-test", 384, 256, 16, pixelSizeUm = 2.9f),
                cameraExposureUs = 5_000_000f,
                cameraExposureMinUs = 1000f, cameraExposureMaxUs = 20_000_000f,
                mountCoordinates = if (automaticMount) MountCoordinates(10.0, 20.0) else null,
                mountStopSignal = stopSignal(),
                onEnsureMountTracking = prepareTracking,
                solver = engine, onCaptureFits = capture, onBack = {})
            }
            }
            }
        } }
    }

    @Test fun switchingToCaptureAndMediaKeepsPolarSolveAndItsResult() {
        var navigation by mutableStateOf(ObservatoryNavigation()
            .select(ObservatoryWorkspace.TOOLS).open(ObservatoryDestination.POLAR_ALIGNMENT))
        val pageId = navigation.pages.last().id
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val finished = AtomicBoolean()
        val release = CompletableDeferred<PlateSolveResult>()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
                searchRadiusDeg: Double?): PlateSolveResult {
                started.set(true)
                try {
                    return release.await().also { finished.set(true) }
                } finally {
                    if (!currentCoroutineContext().isActive) cancelled.set(true)
                }
            }
        }
        screen(engine, pageVisible = { navigation.workspace == ObservatoryWorkspace.TOOLS }) { image }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { started.get() }
        compose.runOnIdle { navigation = navigation.select(ObservatoryWorkspace.CAPTURE) }
        compose.waitForIdle()
        assertFalse("Switching to capture must preserve the polar solve", cancelled.get())
        compose.runOnIdle { navigation = navigation.select(ObservatoryWorkspace.MEDIA) }
        compose.waitForIdle()
        assertFalse("Switching to media must preserve the polar solve", cancelled.get())
        release.complete(PlateSolveResult(false, "Navigation regression: retained solve result"))
        compose.waitUntil(5_000) { finished.get() || cancelled.get() }
        assertFalse("The solve must finish normally while its page is hidden", cancelled.get())
        assertTrue(finished.get())
        compose.runOnIdle { navigation = navigation.select(ObservatoryWorkspace.TOOLS) }
        assertEquals(pageId, navigation.pages.last().id)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("polar-current-failure").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("polar-current-failure")
            .assert(hasText("Navigation regression: retained solve result", substring = true))
        assertFalse(cancelled.get())
    }

    @Test fun globalStopCancelsPolarSolveWhileItsPageIsHidden() {
        var visible by mutableStateOf(true)
        var stopSignal by mutableStateOf(0L)
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
                searchRadiusDeg: Double?): PlateSolveResult {
                started.set(true)
                try { awaitCancellation() } finally { cancelled.set(true) }
            }
        }
        screen(engine, pageVisible = { visible }, stopSignal = { stopSignal }) { image }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { started.get() }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertFalse(cancelled.get())
        compose.runOnIdle { stopSignal++ }
        compose.waitUntil(5_000) { cancelled.get() }
        compose.runOnIdle { visible = true }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("polar-run") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun actualAppBackgroundStopsPolarSolve() {
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
                searchRadiusDeg: Double?): PlateSolveResult {
                started.set(true)
                try { awaitCancellation() } finally { cancelled.set(true) }
            }
        }
        screen(engine) { image }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { started.get() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        try {
            compose.waitUntil(5_000) { cancelled.get() }
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    @Test fun leavingPolarPageStopsItsActiveSolve() {
        var present by mutableStateOf(true)
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
                searchRadiusDeg: Double?): PlateSolveResult {
                started.set(true)
                try { awaitCancellation() } finally { cancelled.set(true) }
            }
        }
        screen(engine, pagePresent = { present }) { image }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { started.get() }
        compose.runOnIdle { present = false }
        compose.waitUntil(5_000) { cancelled.get() }
    }

    @Test fun automaticPolarChecksTrackingBeforeItsFirstExposure() {
        val prepared = AtomicBoolean()
        val captured = AtomicBoolean()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
                searchRadiusDeg: Double?): PlateSolveResult = error("Capture is held by this test")
        }
        screen(engine, automaticMount = true, prepareTracking = { prepared.set(true) }) {
            assertTrue("Tracking must be checked before exposure", prepared.get())
            captured.set(true)
            awaitCancellation()
        }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { captured.get() }
        compose.onNodeWithTag("polar-stop").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("polar-run") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun automaticPolarTrackingFailurePreventsExposureAndShowsTheFailure() {
        val captures = AtomicInteger()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
                searchRadiusDeg: Double?): PlateSolveResult = error("No exposure expected")
        }
        screen(engine, automaticMount = true, prepareTracking = { error("Tracking verification failed") }) {
            captures.incrementAndGet()
            image
        }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("polar-current-failure").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-current-failure").assert(hasText("Tracking verification failed", substring = true))
        assertEquals(0, captures.get())
    }

    @Test fun exposureAndTestCaptureAreAvailableBeforeAnyPlateSolve() {
        val received = AtomicReference<Float>()
        val engine = object : ImagePlateSolver {
            override val ready = false // Image inspection must work before installing a star database.
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?): PlateSolveResult =
                error("A test exposure must not invoke the solver")
        }
        screen(engine) { received.set(it); image }
        compose.onNodeWithTag("polar-exposure-seconds").assertIsDisplayed().performTextClearance()
        compose.onNodeWithTag("polar-exposure-seconds").performTextInput("3")
        compose.onNodeWithTag("polar-exposure-seconds").performImeAction()
        compose.onNodeWithTag("polar-test-capture").performScrollTo().performClick()
        compose.waitUntil(15_000) { received.get() != null }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-main-preview").assertIsDisplayed()
        assertEquals(3_000_000f, received.get(), .1f)
    }

    @Test fun capturedImageCanBeInspectedDuringSolveAndFailureIsVisibleBesideIt() {
        val started = AtomicBoolean(); val cancelled = AtomicBoolean()
        val release = CompletableDeferred<PlateSolveResult>()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?): PlateSolveResult {
                started.set(true)
                try { return release.await() } finally { if (!currentCoroutineContext().isActive) cancelled.set(true) }
            }
        }
        screen(engine) { image }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { started.get() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-main-preview").assertIsDisplayed()
        compose.onNodeWithTag("polar-preview-expand").assertIsEnabled().performClick()
        compose.onNodeWithTag("polar-fullscreen-preview").assertIsDisplayed()
        screenshot("-fullscreen")
        compose.onNodeWithTag("polar-preview-close").performClick()
        compose.runOnIdle { assertFalse(cancelled.get()) }
        compose.onNodeWithTag("polar-preview-expand").performClick()
        compose.onNodeWithTag("polar-fullscreen-preview").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("polar-fullscreen-preview").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("polar-fullscreen-preview").assertDoesNotExist()
        compose.runOnIdle { assertFalse(cancelled.get()) }
        release.complete(PlateSolveResult(false, "ASTAP failed with exit code 1.", log = "Detected 7 stars.\nNot enough stars to solve the image."))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-current-failure").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-current-failure").assertIsDisplayed().assert(hasText("7", substring = true))
        compose.onNodeWithTag("polar-diagnostics-expand").assertIsDisplayed()
    }

    @Test fun cameraFailureIsShownInTheMainWorkflowWithoutOpeningPointDetails() {
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?) = error("No frame")
        }
        screen(engine) { throw IllegalStateException("USB disconnected") }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-current-failure").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-current-failure").assertIsDisplayed().assert(hasText("USB disconnected", substring = true))
    }

    @Test fun advancedSettingsAlignFieldsAndRetainDrafts() {
        val engine = object : ImagePlateSolver {
            override val ready = false
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
                searchRadiusDeg: Double?): PlateSolveResult = error("Settings do not start a solve")
        }
        screen(engine, chinese = true) { image }
        val labels = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
        compose.onNodeWithText(labels.getString(com.indigo.mobileobservatory.R.string.polar_refresh_auto_hint)).assertDoesNotExist()
        compose.onNodeWithText(labels.getString(com.indigo.mobileobservatory.R.string.polar_setup_summary,
            "30.0", "110.0", "100.0", "2.900")).assertDoesNotExist()
        screenshot("-main")
        compose.onNodeWithTag("polar-parameters-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("polar-latitude").performScrollTo()
        val latitude = compose.onNodeWithTag("polar-latitude").fetchSemanticsNode().boundsInRoot
        val longitude = compose.onNodeWithTag("polar-longitude").fetchSemanticsNode().boundsInRoot
        assertEquals(latitude.top, longitude.top, 1f)
        assertTrue(latitude.right <= longitude.left)
        compose.onNodeWithTag("solve-focal").performScrollTo()
        val focal = compose.onNodeWithTag("solve-focal").getUnclippedBoundsInRoot()
        val sensor = compose.onNodeWithTag("solve-automatic-sensor").getUnclippedBoundsInRoot()
        assertTrue(sensor.top >= focal.bottom)
        compose.onNodeWithTag("solve-sensor").assertDoesNotExist()
        compose.onNodeWithTag("solve-pixel").assertDoesNotExist()
        screenshot("-optics")
        compose.onNodeWithTag("solve-focal").performTextClearance()
        compose.onNodeWithTag("solve-focal").performTextInput("528")
        compose.onNodeWithTag("polar-parameters-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("polar-parameters-toggle").performClick()
        compose.onNodeWithTag("solve-focal").performScrollTo().assertTextContains("528")
    }

    @Test fun largeFontAdvancedSettingsStackFieldsAndKeepControlsReachable() {
        val engine = object : ImagePlateSolver {
            override val ready = false
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
                searchRadiusDeg: Double?): PlateSolveResult = error("Settings do not start a solve")
        }
        screen(engine, fontScale = 2f, compact = true, chinese = true) { image }
        compose.onNodeWithTag("polar-parameters-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("polar-latitude").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("polar-longitude").performScrollTo().assertIsDisplayed()
        val latitude = compose.onNodeWithTag("polar-latitude").fetchSemanticsNode().boundsInRoot
        val longitude = compose.onNodeWithTag("polar-longitude").fetchSemanticsNode().boundsInRoot
        assertTrue(longitude.top >= latitude.bottom)
        compose.onNodeWithTag("solve-focal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("solve-automatic-sensor").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("solve-pixel").assertDoesNotExist()
        compose.onNodeWithTag("polar-mount-hint").performScrollTo().assertIsDisplayed().assertHasClickAction()
        screenshot("-advanced")
    }

    @Test fun diagnosticsKeepsStopAvailableDuringAHangingSolve() = cancelHangingSolve("diagnostics")

    @Test fun fullScreenKeepsStopAvailableDuringAHangingSolve() = cancelHangingSolve("fullscreen")

    @Test fun previewKeepsStopAvailableDuringAHangingSolve() = cancelHangingSolve("preview")

    @Test fun largeFontDiagnosticsKeepsStopAvailableDuringAHangingSolve() =
        cancelHangingSolve("diagnostics", fontScale = 2f, compact = true)

    @Test fun largeFontPreviewKeepsTheImageAndRetryActionVisibleDuringSolve() =
        cancelHangingSolve("preview", fontScale = 2f, compact = true)

    @Test fun largeFontEnglishPreviewKeepsTheImageAndRetryActionVisibleDuringSolve() =
        cancelHangingSolve("preview", fontScale = 2f, compact = true, chinese = false)

    private fun cancelHangingSolve(surface: String, fontScale: Float = 1f, compact: Boolean = false, chinese: Boolean = true) {
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val captures = AtomicInteger()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
                searchRadiusDeg: Double?): PlateSolveResult {
                started.set(true)
                try { awaitCancellation() } finally { cancelled.set(true) }
            }
        }
        screen(engine, fontScale = fontScale, compact = compact, chinese = chinese) { captures.incrementAndGet(); image }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { started.get() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-main-preview").assertIsDisplayed()
        val stopText = if (chinese) "放弃解析并重试" else context.getString(com.indigo.mobileobservatory.R.string.polar_abandon_solve_retry)
        when (surface) {
            "diagnostics" -> {
                compose.onNodeWithTag("polar-diagnostics-expand").performClick()
                screenshot("-before-stop")
                compose.onNodeWithTag("polar-diagnostics-stop").assertTextEquals(stopText).assertIsDisplayed().performClick()
            }
            "fullscreen" -> {
                compose.onNodeWithTag("polar-preview-expand").performClick()
                screenshot("-before-stop")
                compose.onNodeWithTag("polar-inspection-stop").assertTextEquals(stopText).assertIsDisplayed().performClick()
                compose.onNodeWithTag("polar-preview-close").performClick()
            }
            else -> {
                screenshot("-before-stop")
                compose.onNodeWithTag("polar-preview-stop").assertTextEquals(stopText).assertIsDisplayed().performClick()
            }
        }
        compose.waitUntil(5000) { cancelled.get() }
        compose.onNodeWithTag("polar-retry-current").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("polar-recapture-current").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("polar-select-current").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("polar-run").performScrollTo().assertIsEnabled()
        assertEquals(1, captures.get())
    }

    @Test fun compactLargeFontLayoutKeepsTheImageAndFailureVisible() {
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?) =
                PlateSolveResult(false, "ASTAP failed with exit code 2.", log = "Only 7 stars found in image. Abort")
        }
        screen(engine, fontScale = 2f, compact = true) { image }
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-current-failure").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-current-failure").assertIsDisplayed().assert(hasText("7", substring = true))
        compose.onNodeWithTag("polar-main-preview").assertIsDisplayed()
        compose.onNodeWithTag("polar-preview-expand").assertIsEnabled().performClick()
        compose.onNodeWithTag("polar-fullscreen-preview").assertIsDisplayed()
        compose.onNodeWithTag("polar-preview-close").performClick()
    }

    @Test fun landscapeLayoutKeepsTheCaptureControlsAndImageTogether() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(15_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val engine = object : ImagePlateSolver {
            override val ready = false
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?) = error("Test exposure")
        }
        screen(engine) { image }
        compose.onNodeWithTag("polar-test-capture").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-main-preview").assertIsDisplayed()
        compose.onNodeWithTag("polar-exposure-seconds").assertIsDisplayed()
        screenshot("-landscape")
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test fun threeSuccessfulSolvesShowTheCorrectionGuideAndLiveResiduals() = exerciseCorrectionRefresh(false)

    @Test fun manualRefreshWaitsAfterThreePointsAndCapturesOncePerClick() = exerciseCorrectionRefresh(true)

    private fun exerciseCorrectionRefresh(startManual: Boolean) {
        context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit().putFloat("plate_focal_length_mm", 41.54f).commit()
        val at = Instant.parse("2026-10-25T10:00:00Z")
        val site = ObserverSite(30.0, 110.0, 0.0)
        val refraction = RefractionParameters.STANDARD
        val axis = PolarVector3.fromTopocentric(TopocentricCoordinates(30.3, .4))
        val start = PolarVector3.fromTopocentric(TopocentricCoordinates(55.0, 35.0))
        val points = listOf(0.0, 15.0, 30.0).map { rotation ->
            val sky = CoordinateTransform.topocentricToJ2000(PolarVector3.rotateByRodrigues(start, axis, rotation).toTopocentric(), at, site, refraction)
            PolarSolvePoint(sky.raDeg, sky.decDeg, at)
        }
        val determination = PolarAlignmentCalculator.determine(points, site, refraction)
        val later = at.plusSeconds(30)
        val current = ContinuousPolarErrorEstimator.coordinatesForResidual(
            EquatorialCoordinates(points.last().raDeg, points.last().decDeg),
            determination.initial.azimuthErrorDeg, determination.initial.altitudeErrorDeg, .10, .05, site, refraction, later)
        val all = points + PolarSolvePoint(current.raDeg, current.decDeg, later)
        val fixtures = all.mapIndexed { index, point -> fitsFixture(index, point.solvedAt) }
        val captures = AtomicInteger()
        val allowAdjustment = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<File?>()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?): PlateSolveResult {
                val i = fixtures.indexOf(inputFile); val point = all[i]
                val wcs = requireNotNull(SolvedImageWcs.parse(mapOf("PLTSOLVD" to "T", "CRVAL1" to "${point.raDeg}",
                    "CRVAL2" to "${point.decDeg}", "CRPIX1" to "192.5", "CRPIX2" to "128.5",
                    "CD1_1" to "-.004", "CD1_2" to "0", "CD2_1" to "0", "CD2_2" to ".004"), 384, 256))
                return PlateSolveResult(true, "Solved", raDeg = point.raDeg, decDeg = point.decDeg, imageWcs = wcs)
            }
        }
        screen(engine) {
            val index = captures.getAndIncrement()
            if (index == 3) allowAdjustment.await()
            if (index <= 4) fixtures[minOf(index, fixtures.lastIndex)] else pending.await()
        }
        if (startManual) compose.onNodeWithTag("polar-refresh-manual").performScrollTo().performClick()
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        repeat(2) {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("polar-next-point").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("polar-next-point").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("polar-next-point").fetchSemanticsNodes().isEmpty() }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("polar-correction-overlay").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-correction-overlay").assertIsDisplayed()
        compose.onNodeWithTag("polar-error-reticle").assertIsDisplayed()
        compose.onNodeWithTag("polar-error-summary").assertIsDisplayed().assert(hasText("30.00", substring = true))
        screenshot("-three-point-result")
        compose.onNodeWithTag("polar-preview-expand").performClick()
        compose.onNodeWithTag("polar-correction-inspection").assertIsDisplayed()
        compose.onNode(hasTestTag("polar-error-reticle") and
            hasAnyAncestor(hasTestTag("polar-fullscreen-preview"))).assertIsDisplayed()
        screenshot("-result-fullscreen")
        compose.onNodeWithTag("polar-preview-close").performClick()
        if (startManual) {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("polar-run") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("polar-run").performScrollTo().assertIsEnabled()
            assertEquals(3, captures.get())
            compose.onNodeWithTag("polar-run").performClick()
        }
        compose.waitUntil(10_000) { captures.get() == 4 }
        // A running automatic exposure completes, then manual mode prevents the next exposure.
        compose.onNodeWithTag("polar-refresh-manual").performScrollTo().performClick()
        compose.runOnIdle { allowAdjustment.complete(Unit) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("polar-error-summary") and hasText("6.71", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("polar-run") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-run").performScrollTo().assertIsEnabled()
        assertEquals(4, captures.get())
        compose.onNodeWithTag("polar-run").performClick()
        compose.waitUntil(10_000) { captures.get() == 5 }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("polar-run") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-run").performScrollTo().assertIsEnabled()
        assertEquals(5, captures.get())
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-correction-overlay").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-correction-overlay").assertIsDisplayed()
        screenshot("-manual-refreshed")
        // Selecting automatic mode resumes continuous acquisition without a second start action.
        compose.onNodeWithTag("polar-refresh-auto").performScrollTo().performClick()
        compose.waitUntil(10_000) { captures.get() == 6 }
        compose.onNodeWithTag("polar-stop").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("polar-run") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-run").performScrollTo().assertIsEnabled()
        assertEquals(6, captures.get())
        compose.onNodeWithTag("polar-error-summary").assert(hasText("6.71", substring = true))
    }

    private fun fitsFixture(index: Int, at: Instant): File {
        val file = File(context.cacheDir, "polar-result-${UUID.randomUUID()}-$index.fits")
        generated += file
        val header = listOf("SIMPLE" to "T", "BITPIX" to "16", "NAXIS" to "2", "NAXIS1" to "384", "NAXIS2" to "256",
            "BSCALE" to "1", "BZERO" to "32768", "DATE-OBS" to "'$at'", "EXPTIME" to "0")
            .joinToString("") { (key, value) -> (key.padEnd(8) + "= " + value).padEnd(80) } + "END".padEnd(80)
        file.outputStream().use { output ->
            output.write(header.padEnd(((header.length + 2879) / 2880) * 2880).toByteArray(Charsets.US_ASCII))
            val pixels = java.nio.ByteBuffer.allocate(384 * 256 * 2).order(java.nio.ByteOrder.BIG_ENDIAN)
            for (y in 0 until 256) for (x in 0 until 384) {
                val star = (1..18).any { i -> kotlin.math.abs(x - i * 19) < 3 && kotlin.math.abs(y - (20 + i * 11)) < 3 }
                pixels.putShort(((if (star) 50000 else 1000) - 32768).toShort())
            }
            output.write(pixels.array())
            val padding = (2880 - pixels.capacity() % 2880) % 2880
            output.write(ByteArray(padding))
        }
        return file
    }
}
