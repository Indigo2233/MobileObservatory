package com.indigo.mobileobservatory.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import com.indigo.mobileobservatory.astrometry.AstapRunner
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.polar.PolarSamplePhase
import com.indigo.mobileobservatory.polar.PolarAdjustmentSnapshot
import com.indigo.mobileobservatory.polar.PolarAlignmentCalculator
import com.indigo.mobileobservatory.polar.PolarSolvePoint
import com.indigo.mobileobservatory.ui.screens.PolarAlignmentScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.PolarSamplesViewModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
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
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true") {
            shell("mkdir -p /sdcard/Download/polar-ui-screenshots")
            shell("screencap -p /sdcard/Download/polar-ui-screenshots/polar-album-${name.methodName}.png")
        }
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

    private fun start(solver: ImagePlateSolver? = null, onMotionStop: () -> Unit = {}) {
        val engine = solver ?: object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?): PlateSolveResult {
                solves.set(inputFile)
                return PlateSolveResult(false, "Selected image reached the solver")
            }
        }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            MobileObservatoryTheme { PolarAlignmentScreen(solver = engine,
                onCaptureFits = { captures.incrementAndGet(); null }, onStopMountRaMove = onMotionStop,
                onBack = { back.incrementAndGet() }) }
        } }
        compose.onNodeWithText(context.getString(R.string.polar_parameters)).performScrollTo().performClick()
        compose.onNodeWithTag("polar-sample-details").performScrollTo().performClick()
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

    @Test fun cancellingAnAlbumSolveOffersRetryAndPreservesTheCompletedPointWithoutMoving() {
        val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "polar-album-cancel-").toFile()
        val solverContext = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        File(solverContext.filesDir, "astap/d20_0101.1476").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(0)) }
        val process = AtomicReference<Process>()
        val launches = AtomicInteger()
        val stops = AtomicInteger()
        val finish = AtomicBoolean()
        val runner = AstapRunner(solverContext) { builder ->
            val command = if (finish.get()) "echo No-solution-fixture; exit 2"
                else "echo ASTAP-cancellation-fixture; exec /system/bin/sleep 60"
            builder.command("/system/bin/sh", "-c", command).start().also {
                process.set(it); launches.incrementAndGet()
            }
        }
        try {
            instrumentation.runOnMainSync {
                runBlocking {
                    model.session.execute(0, { photo }, { 1.8 }, { _, _ ->
                        PlateSolveResult(true, "Solved", raDeg = 15.0, decDeg = 60.0)
                    })
                }
                model.positionedPoint = 1
            }
            val completed = model.session.samples.value[0]
            val original = nestedFits.readBytes()
            start(runner) { stops.incrementAndGet() }
            choosePoint(1); selectAlbumImage(nestedFits)
            compose.waitUntil(10_000) { model.session.samples.value[1].file != null }
            val selected = requireNotNull(model.session.samples.value[1].file)
            listOf("preview", "diagnostics", "fullscreen").forEachIndexed { pass, surface ->
                if (pass == 0) compose.onNodeWithTag("polar-retry-1").performScrollTo().performClick()
                else compose.onNodeWithTag("polar-retry-current").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    launches.get() == pass + 1 && model.session.samples.value[1].phase == PolarSamplePhase.SOLVING
                }
                val stopTag = when (surface) {
                    "diagnostics" -> {
                        compose.onNodeWithTag("polar-diagnostics-expand").performClick()
                        "polar-diagnostics-stop"
                    }
                    "fullscreen" -> {
                        compose.onNodeWithTag("polar-preview-expand").performClick()
                        "polar-inspection-stop"
                    }
                    else -> "polar-preview-stop"
                }
                compose.onNodeWithTag(stopTag)
                    .assertTextEquals(context.getString(R.string.polar_abandon_solve_retry)).performClick()
                compose.waitUntil(5_000) {
                    model.session.samples.value[1].phase == PolarSamplePhase.CANCELLED && !process.get().isAlive
                }
                if (surface == "fullscreen") compose.onNodeWithTag("polar-preview-close").performClick()
                compose.onNodeWithTag("polar-retry-current").assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithTag("polar-select-current").assertIsEnabled()
                compose.onNodeWithTag("polar-recapture-current").assertDoesNotExist()
                assertEquals(completed, model.session.samples.value[0])
                assertEquals(selected, model.session.samples.value[1].file)
                assertTrue(model.session.samples.value[1].imported)
                assertArrayEquals(original, selected.readBytes())
                assertEquals(0, captures.get()); assertEquals(0, stops.get())
            }
            // The same selected image can complete a new attempt after all three cancellation paths.
            finish.set(true)
            compose.onNodeWithTag("polar-retry-current").performClick()
            compose.waitUntil(10_000) { model.session.samples.value[1].phase == PolarSamplePhase.FAILED }
            assertEquals(4, launches.get())
            assertEquals(completed, model.session.samples.value[0])
            assertTrue(model.session.samples.value[1].result!!.log.contains("No-solution-fixture"))
            assertArrayEquals(original, nestedFits.readBytes())
            assertArrayEquals(original, selected.readBytes())
            assertEquals(0, captures.get()); assertEquals(0, stops.get())
        } finally {
            process.get()?.let { it.destroyForcibly(); it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
            root.deleteRecursively()
        }
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

    @Test fun repeatedlyReplacingAnAlbumSampleKeepsPreviewBitmapsDrawable() {
        start()
        repeat(6) { index ->
            val previous = model.session.samples.value[1].file
            choosePoint(1)
            selectAlbumImage(if (index % 2 == 0) nestedFits else photo)
            compose.waitUntil(10_000) {
                val selected = model.session.samples.value[1].file
                selected != null && selected != previous
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
            val rendered = compose.onNodeWithTag("polar-main-preview").captureToImage()
            assertTrue(rendered.width > 0 && rendered.height > 0)
        }
        assertEquals(0, captures.get())
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
