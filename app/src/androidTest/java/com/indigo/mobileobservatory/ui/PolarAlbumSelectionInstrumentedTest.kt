package com.indigo.mobileobservatory.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astrometry.ImagePlateSolver
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.polar.PolarSamplePhase
import com.indigo.mobileobservatory.polar.PolarAdjustmentSnapshot
import com.indigo.mobileobservatory.polar.PolarAlignmentCalculator
import com.indigo.mobileobservatory.polar.PolarSolvePoint
import com.indigo.mobileobservatory.ui.screens.PolarAlignmentScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.PolarSamplesViewModel
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real sample screen and app-owned library; only the external document chooser and solver are supplied. */
class PolarAlbumSelectionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    @get:Rule val name = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private val keys = listOf("polar_latitude_deg", "polar_longitude_deg", "plate_focal_length_mm", "plate_pixel_size_um", "plate_sensor_id", "polar_manual_mode")
    private var saved: Map<String, Any?> = emptyMap()
    private lateinit var model: PolarSamplesViewModel
    private lateinit var photo: File
    private lateinit var nestedFits: File
    private lateinit var singleFits: File
    private val generated = mutableListOf<File>()
    private val solves = AtomicReference<File?>()
    private val captures = AtomicInteger()
    private val back = AtomicInteger()
    private val registry = DocumentRegistry()
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry get() = registry }

    private inner class DocumentRegistry : ActivityResultRegistry() {
        var request: Int? = null
        var intent: Intent? = null
        var launches = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++; request = requestCode; intent = contract.createIntent(context, input)
        }
        fun finish(uri: Uri?) { dispatchResult(requireNotNull(request), if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(it) }) }
    }

    @Before fun setup() {
        saved = keys.associateWith { prefs.all[it] }
        prefs.edit().putFloat("polar_latitude_deg", 30f).putFloat("polar_longitude_deg", 110f)
            .putFloat("plate_focal_length_mm", 100f).putFloat("plate_pixel_size_um", 2.9f)
            .putBoolean("polar_manual_mode", true).remove("plate_sensor_id").commit()
        val token = "polar-album-${UUID.randomUUID()}"
        photo = File(context.getExternalFilesDir("captures"), "JPG/$token.png").also { file ->
            file.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff203040.toInt())
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            generated += file
        }
        nestedFits = fits(File(context.getExternalFilesDir("recordings"), "FITS/$token/$token-sequence.fit"))
        singleFits = fits(File(context.getExternalFilesDir("captures"), "FITS/$token-single.fits"))
        check(nestedFits.setLastModified(System.currentTimeMillis() - 60_000))
        instrumentation.runOnMainSync {
            model = ViewModelProvider(compose.activity).get("polar-samples", PolarSamplesViewModel::class.java)
            model.session.import(0, photo)
        }
    }

    @After fun cleanup() {
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        shell("mkdir -p /sdcard/Download/polar-ui-screenshots")
        shell("screencap -p /sdcard/Download/polar-ui-screenshots/polar-album-${name.methodName}.png")
        val edit = prefs.edit()
        saved.forEach { (key, value) -> when (value) {
            is Float -> edit.putFloat(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is String -> edit.putString(key, value)
            else -> edit.remove(key)
        } }; edit.commit()
        if (::model.isInitialized) model.session.samples.value.mapNotNull { it.file }
            .filter { it.parentFile == context.cacheDir && it.name.startsWith("platesolve_") }.forEach { it.delete() }
        generated.forEach { it.delete() }
        if (::nestedFits.isInitialized) nestedFits.parentFile?.delete()
    }

    private fun start() {
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?): PlateSolveResult {
                solves.set(inputFile)
                return PlateSolveResult(false, "Selected image reached the solver")
            }
        }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            MobileObservatoryTheme { PolarAlignmentScreen(solver = engine,
                onCaptureFits = { captures.incrementAndGet(); null }, onBack = { back.incrementAndGet() }) }
        } }
        compose.onNodeWithText(context.getString(R.string.polar_parameters)).performScrollTo().performClick()
    }

    private fun choosePoint(index: Int) {
        compose.onNodeWithTag("polar-select-$index").performScrollTo().performClick()
        compose.onNodeWithTag("app-image-picker").assertIsDisplayed()
    }

    private fun selectAlbumImage(file: File) {
        compose.onNodeWithTag("app-image-search").performTextInput(file.name)
        val row = hasText(file.name, substring = true) and hasAnyAncestor(hasTestTag("plate-image-list"))
        compose.waitUntil(10_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(row).performClick()
    }

    private fun seedCalibration(): PolarAdjustmentSnapshot {
        val at = Instant.parse("2026-10-25T15:16:00Z")
        val determination = PolarAlignmentCalculator.determine(listOf(15.0, 30.0, 45.0).map { PolarSolvePoint(it, 60.0, at) },
            ObserverSite(30.0, 110.0), correctForRefraction = false)
        val snapshot = PolarAdjustmentSnapshot.create(determination, determination.initial, model.session.samples.value[0])
        instrumentation.runOnMainSync { model.adjustment = snapshot }
        return snapshot
    }

    @Test fun nestedFitsFromAppAlbumUpdatesOnlyChosenPointAndReachesTheSolverUnchanged() {
        val bytes = nestedFits.readBytes()
        seedCalibration()
        start(); choosePoint(1)
        compose.runOnIdle { assertEquals(0, registry.launches) }
        selectAlbumImage(nestedFits)
        compose.waitUntil(10_000) { model.session.samples.value[1].file != null }
        val selected = requireNotNull(model.session.samples.value[1].file)
        assertNotEquals(nestedFits.absolutePath, selected.absolutePath)
        assertArrayEquals(bytes, selected.readBytes())
        assertEquals(nestedFits.lastModified(), selected.lastModified())
        assertArrayEquals(bytes, nestedFits.readBytes())
        assertEquals(photo, model.session.samples.value[0].file)
        assertNull(model.adjustment)
        assertNull(model.session.samples.value[2].file)
        assertNull(solves.get()); assertEquals(0, captures.get())
        compose.onNodeWithTag("polar-retry-1").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.session.samples.value[1].phase == PolarSamplePhase.FAILED }
        assertEquals(selected, solves.get()); assertEquals(0, captures.get())
        assertArrayEquals(bytes, nestedFits.readBytes())
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-main-preview").assertIsDisplayed()
    }

    @Test fun cancellingTheDocumentChooserKeepsTheAlbumAndBackPreservesTheOldSample() {
        start(); choosePoint(0)
        compose.onNodeWithTag("app-image-files").performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, registry.intent?.action)
            assertTrue(registry.intent?.hasCategory(Intent.CATEGORY_OPENABLE) == true)
            registry.finish(null)
        }
        compose.onNodeWithTag("app-image-picker").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("app-image-picker").fetchSemanticsNodes().isEmpty() }
        assertEquals(photo, model.session.samples.value[0].file)
        assertTrue(photo.isFile); assertNull(solves.get()); assertEquals(0, back.get())
    }

    @Test fun externalFitsAndAppPngUseTheSameSampleImportWithoutCapturing() {
        start(); choosePoint(2)
        compose.onNodeWithTag("app-image-files").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        instrumentation.runOnMainSync {
            assertEquals("*/*", registry.intent?.type)
            registry.finish(Uri.fromFile(singleFits))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { model.session.samples.value[2].file != null }
        assertArrayEquals(singleFits.readBytes(), model.session.samples.value[2].file!!.readBytes())
        compose.onNodeWithTag("app-image-picker").assertDoesNotExist()
        choosePoint(1)
        selectAlbumImage(photo)
        compose.waitUntil(10_000) { model.session.samples.value[1].file != null }
        assertArrayEquals(photo.readBytes(), model.session.samples.value[1].file!!.readBytes())
        assertEquals(photo, model.session.samples.value[0].file)
        assertTrue(photo.isFile); assertTrue(singleFits.isFile)
        assertEquals(0, captures.get()); assertNull(solves.get())
    }

    @Test fun aNewCorrectionImagePreservesTheEstablishedThreePointCalibration() {
        val previous = seedCalibration()
        start(); choosePoint(3)
        selectAlbumImage(nestedFits)
        compose.waitUntil(10_000) { model.session.samples.value[3].file != null }
        assertSame(previous, model.adjustment)
        assertEquals(photo, model.session.samples.value[0].file)
        assertArrayEquals(nestedFits.readBytes(), model.session.samples.value[3].file!!.readBytes())
        assertEquals(0, captures.get()); assertNull(solves.get())
    }

    @Test fun landscapePickerKeepsTheMatchingImageAndSystemFileActionVisible() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        start(); choosePoint(1)
        compose.onNodeWithTag("app-image-search").performTextInput(nestedFits.name)
        val row = hasText(nestedFits.name, substring = true) and hasAnyAncestor(hasTestTag("plate-image-list"))
        compose.waitUntil(10_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        val bounds = compose.onNode(row).fetchSemanticsNode().boundsInRoot
        assertTrue("Image selection needs a full touch target in landscape", bounds.height >= 48 * context.resources.displayMetrics.density)
        compose.onNodeWithTag("app-image-files").assertIsDisplayed()
        compose.onNode(row).performClick()
        compose.waitUntil(10_000) { model.session.samples.value[1].file != null }
        assertArrayEquals(nestedFits.readBytes(), model.session.samples.value[1].file!!.readBytes())
    }

    private fun fits(file: File): File {
        file.parentFile!!.mkdirs()
        val header = listOf("SIMPLE" to "T", "BITPIX" to "16", "NAXIS" to "2", "NAXIS1" to "384", "NAXIS2" to "256",
            "BZERO" to "32768", "DATE-OBS" to "'2026-10-25T15:16:00'", "EXPTIME" to "1")
            .joinToString("") { (key, value) -> (key.padEnd(8) + "= " + value).padEnd(80) } + "END".padEnd(80)
        file.outputStream().use { output ->
            output.write(header.padEnd(((header.length + 2879) / 2880) * 2880).toByteArray(Charsets.US_ASCII))
            val data = ByteBuffer.allocate(384 * 256 * 2).order(ByteOrder.BIG_ENDIAN)
            for (y in 0 until 256) for (x in 0 until 384) data.putShort(((if (x % 29 < 3 && y % 31 < 3) 50000 else 1000) - 32768).toShort())
            output.write(data.array()); output.write(ByteArray((2880 - data.capacity() % 2880) % 2880))
        }
        generated += file
        return file
    }
}
