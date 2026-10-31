package com.indigo.mobileobservatory.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astrometry.ImagePlateSolver
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.camera.CameraInfo
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.ui.screens.PlateSolveScreen
import com.indigo.mobileobservatory.ui.screens.PolarAlignmentScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.PolarSamplesViewModel
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SolveCameraOpticsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private var saved: Map<String, *> = emptyMap<String, Any>()
    private lateinit var image: File
    private var captureFile: File? = null
    private val camera = mutableStateOf<CameraInfo?>(CameraInfo("Test camera", "solve-test", 384, 256, 16, pixelSizeUm = 2.9f))
    private val cameraConnected = mutableStateOf(true)
    private val generated = mutableListOf<File>()
    private val receivedFov = AtomicReference<Double>()
    private val solver = object : ImagePlateSolver {
        override val ready = true
        override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?,
            searchRadiusDeg: Double?): PlateSolveResult {
            receivedFov.set(fovDeg)
            awaitCancellation()
        }
    }

    @Before fun prepare() {
        saved = prefs.all
        prefs.edit().putFloat("polar_latitude_deg", 30f).putFloat("polar_longitude_deg", 110f)
            .putFloat("plate_focal_length_mm", 100f).putFloat("plate_pixel_size_um", 3.76f)
            .putString("plate_sensor_id", "ccd_imx571").putBoolean("polar_manual_mode", true)
            .putBoolean("polar_use_mount_hint", false).putString("polar_exposure_seconds", "1").commit()
        image = File(context.cacheDir, "solve-camera-optics-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @After fun restore() {
        val edit = prefs.edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        edit.commit()
        image.delete()
        generated.forEach { it.delete() }
    }

    private fun showPolar() {
        compose.setContent { MobileObservatoryTheme {
            PolarAlignmentScreen(cameraConnected = cameraConnected.value,
                cameraInfo = camera.value,
                solver = solver, onCaptureFits = { captureFile ?: image }, onBack = {})
        } }
    }

    private fun captureAndStop(): Double {
        receivedFov.set(null)
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(15_000) { receivedFov.get() != null }
        compose.onNodeWithTag("polar-stop").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(hasTestTag("polar-run") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        return receivedFov.get()
    }

    private fun fits(pixel: Float = 4.63f, binning: Int = 2): File {
        val file = File(context.cacheDir, "solve-camera-optics-${UUID.randomUUID()}.fits")
        generated += file
        FITSWriter().write(file, FrameData(ByteArray(384 * 256), 384, 256, PixelFormat.MONO8, 1, System.currentTimeMillis()),
            exposureSeconds = 1f, gain = 0f, pixelSizeUm = pixel, focalLengthMm = 200f, binning = binning)
        return file
    }

    private fun assertFov(value: Double) {
        compose.onNodeWithTag("solve-fov").performScrollTo()
            .assertTextEquals(context.getString(R.string.computed_field_height_deg, value))
    }

    @Test fun polarCaptureUsesTheConnectedCameraDespiteASavedSensorPreset() {
        captureFile = fits(pixel = 2.9f, binning = 1)
        showPolar()
        assertEquals(206.265 * 2.9 * 256 / 100 / 3600, captureAndStop(), .00001)
        captureFile = fits(pixel = 4.63f, binning = 1)
        compose.runOnIdle { camera.value = camera.value!!.copy(name = "Replacement camera", pixelSizeUm = 4.63f) }
        assertEquals(206.265 * 4.63 * 256 / 100 / 3600, captureAndStop(), .00001)
        assertEquals("ccd_imx571", prefs.getString("plate_sensor_id", null))
        assertEquals(3.76f, prefs.getFloat("plate_pixel_size_um", 0f), .0001f)
        compose.runOnIdle { camera.value = camera.value!!.copy(pixelSizeUm = null) }
        compose.onNodeWithText(context.getString(R.string.polar_parameters)).performScrollTo().performClick()
        compose.onNodeWithTag("solve-sensor").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("solve-automatic-sensor").assertDoesNotExist()
        assertFov(206.265 * 3.76 * 256 / 100 / 3600)
    }

    @Test fun connectedPolarCameraDoesNotRequireSensorSelection() {
        showPolar()
        compose.onNodeWithText(context.getString(R.string.polar_parameters)).performScrollTo().performClick()
        compose.onNodeWithTag("solve-focal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("solve-sensor").assertDoesNotExist()
        compose.onNodeWithTag("solve-pixel").assertDoesNotExist()
        compose.onNodeWithTag("solve-automatic-sensor").assert(hasText("Test camera", substring = true))
        compose.runOnIdle { camera.value = camera.value!!.copy(name = "Replacement camera", pixelSizeUm = 4.63f) }
        compose.onNodeWithTag("solve-automatic-sensor").assert(hasText("Replacement camera", substring = true))
        assertFov(206.265 * 4.63 * 256 / 100 / 3600)
        compose.runOnIdle { cameraConnected.value = false }
        compose.onNodeWithTag("solve-automatic-sensor").assertDoesNotExist()
        compose.onNodeWithTag("solve-sensor").performScrollTo().assert(hasText("IMX571", substring = true))
        compose.onNodeWithTag("solve-focal").assert(hasText("100.0"))
    }

    @Test fun unknownCameraPixelSizeKeepsTheManualFieldsAvailable() {
        camera.value = camera.value!!.copy(pixelSizeUm = null)
        showPolar()
        compose.onNodeWithText(context.getString(R.string.polar_parameters)).performScrollTo().performClick()
        compose.onNodeWithTag("solve-sensor").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("solve-pixel").assertExists()
        compose.onNodeWithTag("solve-automatic-sensor").assertDoesNotExist()
        assertEquals(206.265 * 3.76 * 256 / 100 / 3600, captureAndStop(), .00001)
    }

    @Test fun currentFrameUsesTheConnectedCameraAndRetainsTheEnteredFocalLength() {
        compose.setContent { MobileObservatoryTheme {
            PlateSolveScreen(initialFile = image, autoSolve = true, cameraInfo = camera.value, onBack = {})
        } }
        compose.onNodeWithTag("solve-automatic-sensor").performScrollTo().assert(hasText("Test camera", substring = true))
        compose.onNodeWithTag("solve-sensor").assertDoesNotExist()
        assertFov(206.265 * 2.9 * 256 / 100 / 3600)
        compose.onNodeWithTag("solve-focal").performScrollTo().performTextReplacement("150")
        assertFov(206.265 * 2.9 * 256 / 150 / 3600)
        compose.onNodeWithTag("solve-focal").performScrollTo().performTextClearance()
        compose.onNodeWithText(context.getString(R.string.solve_need_focal_length)).assertExists()
        compose.onNodeWithTag("solve-sensor").assertDoesNotExist()
    }

    @Test fun importedFitsKeepsItsOwnSensorWhenAnotherCameraIsConnected() {
        val file = fits()
        compose.setContent { MobileObservatoryTheme {
            PlateSolveScreen(initialFile = file, cameraInfo = camera.value, onBack = {})
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("solve-automatic-sensor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("solve-automatic-sensor").performScrollTo()
            .assert(hasText(context.getString(R.string.solve_image_sensor), substring = true))
        compose.onNodeWithTag("solve-sensor").assertDoesNotExist()
        assertFov(206.265 * 4.63 * 2 * 256 / 100 / 3600)
        compose.runOnIdle { camera.value = camera.value!!.copy(pixelSizeUm = 9f) }
        assertFov(206.265 * 4.63 * 2 * 256 / 100 / 3600)
        compose.runOnIdle { camera.value = null }
        assertFov(206.265 * 4.63 * 2 * 256 / 100 / 3600)
        compose.onNodeWithTag("solve-focal").assert(hasText("100.0"))
    }

    @Test fun importedRasterPreservesManualSensorSelection() {
        compose.setContent { MobileObservatoryTheme {
            PlateSolveScreen(initialFile = image, cameraInfo = camera.value, onBack = {})
        } }
        compose.onNodeWithTag("solve-sensor").performScrollTo().assert(hasText("IMX571", substring = true))
        compose.onNodeWithTag("solve-automatic-sensor").assertDoesNotExist()
        assertFov(206.265 * 3.76 * 256 / 100 / 3600)
    }

    @Test fun importedPolarSampleDisplaysItsOwnOptics() {
        val file = fits()
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)["polar-samples", PolarSamplesViewModel::class.java].session.import(0, file)
        }
        showPolar()
        compose.onNodeWithText(context.getString(R.string.polar_parameters)).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("solve-automatic-sensor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("solve-automatic-sensor").performScrollTo()
            .assert(hasText(context.getString(R.string.solve_image_sensor), substring = true))
        assertFov(206.265 * 4.63 * 2 * 256 / 100 / 3600)
        compose.onNodeWithTag("solve-sensor").assertDoesNotExist()
    }
}
