package com.indigo.mobileobservatory.ui

import android.content.Context
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.astrometry.*
import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.polar.*
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.ui.screens.PolarAlignmentScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.TestName
import java.io.File
import java.util.UUID
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
        "polar_alignment_tolerance_arcmin", "polar_automatic_refresh")
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

    private fun screen(engine: ImagePlateSolver, fontScale: Float = 1f, compact: Boolean = false, capture: suspend (Float) -> File?) {
        compose.setContent { MobileObservatoryTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            Box(if (compact) Modifier.size(360.dp, 480.dp) else Modifier) {
            PolarAlignmentScreen(cameraConnected = true, cameraPixelSizeUm = 2.9f, cameraExposureUs = 5_000_000f,
                cameraExposureMinUs = 1000f, cameraExposureMaxUs = 20_000_000f,
                solver = engine, onCaptureFits = capture, onBack = {})
            }
            }
        } }
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
        compose.onNodeWithTag("polar-error-reticle").assertIsDisplayed()
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
