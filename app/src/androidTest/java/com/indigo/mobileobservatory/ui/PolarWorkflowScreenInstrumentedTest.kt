package com.indigo.mobileobservatory.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.astrometry.*
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

/** Exercises the actual observing screen, with camera and solver boundaries supplied by the fixture. */
@RunWith(AndroidJUnit4::class)
class PolarWorkflowScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    @get:Rule val testName = TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = listOf("polar_latitude_deg", "polar_longitude_deg", "plate_focal_length_mm", "plate_pixel_size_um",
        "polar_manual_mode", "polar_continuous_estimator", "plate_sensor_id", "polar_exposure_seconds")
    private var saved = emptyMap<String, Any?>()
    private lateinit var image: File

    @Before fun prepare() {
        val prefs = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        saved = keys.associateWith { prefs.all[it] }
        prefs.edit().putFloat("polar_latitude_deg", 30f).putFloat("polar_longitude_deg", 110f)
            .putFloat("plate_focal_length_mm", 100f).putFloat("plate_pixel_size_um", 2.9f)
            .putBoolean("polar_manual_mode", true).putBoolean("polar_continuous_estimator", false)
            .remove("plate_sensor_id").remove("polar_exposure_seconds").commit()
        image = File(context.cacheDir, "polar-screen-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff101820.toInt())
        val canvas = Canvas(bitmap); val paint = Paint().apply { color = 0xffd0e0ff.toInt() }
        for (i in 1..18) canvas.drawCircle((i * 19).toFloat(), (20 + i * 11).toFloat(), 2.5f, paint)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @After fun restore() {
        runCatching {
            val directory = File(context.getExternalFilesDir(null), "polar-ui-screenshots").apply { mkdirs() }
            val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            File(directory, "${testName.methodName}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val edit = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
        saved.forEach { (key, value) ->
            when (value) {
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
                else -> edit.remove(key)
            }
        }
        edit.commit()
        image.delete()
    }

    private fun screen(engine: ImagePlateSolver, capture: suspend (Float) -> File?) {
        compose.setContent { MobileObservatoryTheme {
            PolarAlignmentScreen(cameraConnected = true, cameraPixelSizeUm = 2.9f, cameraExposureUs = 5_000_000f,
                cameraExposureMinUs = 1000f, cameraExposureMaxUs = 20_000_000f,
                solver = engine, onCaptureFits = capture, onBack = {})
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
        compose.onNodeWithTag("polar-preview-close").performClick()
        compose.runOnIdle { assertFalse(cancelled.get()) }
        release.complete(PlateSolveResult(false, "ASTAP failed with exit code 1.", log = "Detected 7 stars.\nNot enough stars to solve the image."))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-current-failure").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-current-failure").assertIsDisplayed().assert(hasText("7", substring = true))
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
}
