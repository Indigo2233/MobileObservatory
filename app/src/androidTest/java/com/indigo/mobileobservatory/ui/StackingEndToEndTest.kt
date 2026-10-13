package com.indigo.mobileobservatory.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.SERWriter
import com.indigo.mobileobservatory.recording.MediaCategory
import com.indigo.mobileobservatory.stacking.*
import com.indigo.mobileobservatory.ui.screens.PlayerScreen
import com.indigo.mobileobservatory.ui.screens.StackingScreen
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

/** Actual Android bitmap/file/Compose paths. No camera or native camera SDK is required. */
@RunWith(AndroidJUnit4::class)
class StackingEndToEndTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun planetaryUiAnalyzesSelectsStacksExportsAndReopensTheSameRecipe() {
        val store=ViewModelStore()
        lateinit var viewModel:CameraViewModel
        instrumentation.runOnMainSync {
            viewModel=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
            viewModel.stacking.open(StackMode.PLANETARY)
        }
        val fixture=fixture(12)
        var browse by mutableStateOf(false)
        var openedResult by mutableStateOf<File?>(null)
        compose.setContent { MaterialTheme {
            if(browse) PlayerScreen(viewModel.getRecordingsDir(),viewModel.getCapturesDir(),
                initialFile=openedResult,initialCategory=MediaCategory.STACK_RESULTS,onBack={browse=false})
            else StackingScreen(viewModel,StackMode.PLANETARY,onBack={},onOpenResult={ openedResult=it;browse=true })
        } }
        try {
            compose.onNodeWithText(app.getString(R.string.stack_empty)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.stack_export)).assertIsNotEnabled()
            compose.runOnIdle { viewModel.stacking.loadFile(fixture) }
            waitFor(viewModel.stacking,StackTaskPhase.READY)
            compose.onNodeWithText(app.getString(R.string.stack_analyze)).performScrollTo().performClick()
            waitFor(viewModel.stacking,StackTaskPhase.ANALYZED)
            assertEquals(12L,viewModel.stacking.task.value.qualityFrames)
            compose.onNodeWithText("50%").performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.stack_align)).performScrollTo().performClick()
            waitFor(viewModel.stacking,StackTaskPhase.COMPLETE)
            compose.waitUntil(30_000) { !viewModel.stacking.rendering.value && !viewModel.stacking.hasPlanetaryWork() && viewModel.stacking.hasResult(StackMode.PLANETARY) }
            val stats=viewModel.stacking.task.value.stats!!
            assertEquals(6L,stats.selectedFrames)
            assertTrue(stats.stackedFrames>0)
            assertTrue(stats.alignmentPoints>0)

            // A real preset click must render the same recipe later used for both export formats.
            compose.onNodeWithText(app.getString(R.string.stack_intense)).performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(ProcessingPreset.INTENSE,viewModel.stacking.processing.value.preset) }
            compose.waitUntil(30_000) { !viewModel.stacking.rendering.value && viewModel.stacking.processing.value.preset==ProcessingPreset.INTENSE }
            val expected=viewModel.stacking.preview.value!!.copy(Bitmap.Config.ARGB_8888,false)
            screenshot("planetary-result.png")
            compose.onNodeWithText(app.getString(R.string.stack_export)).performClick()
            compose.waitUntil(30_000) { viewModel.stacking.task.value.exportedFiles.size==3 && !viewModel.stacking.hasPlanetaryWork() }
            val files=viewModel.stacking.task.value.exportedFiles.map(::File)
            assertTrue(files.all { it.isFile && it.length()>0 })
            val fits=files.single { it.extension=="fits" }
            val png=files.single { it.extension=="png" }
            val json=files.single { it.extension=="json" }
            assertTrue(fixture.isFile)
            val exported=BitmapFactory.decodeFile(png.absolutePath)
            assertArrayEquals(pixels(expected),pixels(exported))
            expected.recycle();exported.recycle()

            val metadata=JSONObject(json.readText())
            val recipe=StackResultMetadata.parseParameters(JSONObject(metadata.getString("processing")).getJSONObject("parameters"))
            assertEquals(viewModel.stacking.processing.value,recipe)
            assertEquals(stats.stackedFrames.toString(),metadata.getString("frameCount"))
            val artifacts=artifactDirectory()
            files.forEach { it.copyTo(File(artifacts,it.name),overwrite=true) }
            File(artifacts,"android-metrics.json").writeText(JSONObject().apply {
                put("device",android.os.Build.MODEL);put("api",android.os.Build.VERSION.SDK_INT)
                put("environment","Android emulator; synthetic input; not physical camera performance")
                put("width",160);put("height",128);put("inputFrames",12)
                put("selectedFrames",stats.selectedFrames);put("stackedFrames",stats.stackedFrames)
                put("alignmentPoints",stats.alignmentPoints);put("stackElapsedMs",stats.elapsedMillis)
                put("sampledJavaPeakBytes",viewModel.stacking.task.value.javaPeakBytes)
                put("sampledNativePeakBytes",viewModel.stacking.task.value.nativePeakBytes)
                put("previewEqualsPng",true);put("restoredRecipeEqualsExport",true)
            }.toString(2))
            val raw=StackFitsReader.read(fits)
            assertEquals(160,raw.width);assertEquals(128,raw.height)
            val reopened=StackFitsPreview.read(fits)
            assertTrue(reopened.restoredRecipe)
            assertEquals(recipe,reopened.parameters)
            assertArrayEquals(StackPostProcessor.process(raw,recipe).image.pixels,reopened.image.pixels,0f)

            // A beginner can open the exported image directly without finding an Android folder.
            compose.onNodeWithText(app.getString(R.string.stack_view_saved)).performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription(app.getString(R.string.image_desc)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(app.getString(R.string.image_desc)).assertIsDisplayed()
            // Also exercise discovery of the independently named FITS through the actual library.
            compose.runOnIdle { browse=false;openedResult=null }
            compose.onNodeWithText(app.getString(R.string.stack_all_results)).performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.media_category_fits)).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(fits.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(fits.name).performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription(app.getString(R.string.image_desc)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(app.getString(R.string.image_desc)).assertIsDisplayed()
            screenshot("planetary-player-reopened.png")
            File(artifactDirectory(),"end-to-end-exports.txt").writeText(files.joinToString("\n") { it.absolutePath })
        } finally {
            fixture.delete()
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test fun backgroundCancellationReleasesAnalysisCacheAndAllowsFreshRetry() {
        compose.setContent { MaterialTheme { Text(app.getString(R.string.stack_planetary)) } }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val coordinator=StackingCoordinator(app,scope)
        val fixture=fixture(200)
        val cache=File(app.cacheDir,"stack-analysis")
        val original=cache.listFiles().orEmpty().map { it.name }.toSet()
        try {
            instrumentation.runOnMainSync { coordinator.open(StackMode.PLANETARY);coordinator.loadFile(fixture) }
            waitFor(coordinator,StackTaskPhase.READY)
            instrumentation.runOnMainSync { coordinator.analyze(PixelRoi(0,0,160,128),true) }
            compose.waitUntil(20_000) { coordinator.task.value.completed>0 || coordinator.task.value.phase==StackTaskPhase.FAILED }
            assertNotEquals(coordinator.task.value.message,StackTaskPhase.FAILED,coordinator.task.value.phase)
            instrumentation.runOnMainSync { coordinator.onBackground() }
            compose.waitUntil(20_000) { !coordinator.hasPlanetaryWork() && !coordinator.rendering.value }
            assertEquals(StackTaskPhase.CANCELLED,coordinator.task.value.phase)
            assertFalse(coordinator.hasResult(StackMode.PLANETARY))
            assertTrue(cache.listFiles().orEmpty().all { it.name in original })
            instrumentation.runOnMainSync { coordinator.onForeground();coordinator.analyze(PixelRoi(0,0,160,128),false) }
            waitFor(coordinator,StackTaskPhase.ANALYZED)
            assertEquals(200L,coordinator.task.value.qualityFrames)
            val malformed=File(fixture.parentFile,"truncated.ser").apply { writeBytes(byteArrayOf(1,2,3)) }
            try {
                instrumentation.runOnMainSync { coordinator.loadFile(malformed) }
                compose.waitUntil(20_000) { coordinator.task.value.phase==StackTaskPhase.FAILED && !coordinator.hasPlanetaryWork() }
                assertNull(coordinator.task.value.source)
                assertTrue(coordinator.task.value.histogram.isEmpty())
                assertNull(coordinator.task.value.stats)
                assertNull(coordinator.preview.value)
            } finally { malformed.delete() }
            instrumentation.runOnMainSync { coordinator.clearPlanetary() }
            compose.waitUntil(20_000) { !coordinator.hasPlanetaryWork() && coordinator.task.value.phase==StackTaskPhase.EMPTY }
            assertTrue(cache.listFiles().orEmpty().all { it.name in original })
        } finally {
            instrumentation.runOnMainSync { coordinator.close() }
            scope.cancel();fixture.delete()
        }
    }

    @Test fun analyzedRoiAndSelectionSurviveWidthBranchesAndSavedPageReentry() {
        val store=ViewModelStore()
        lateinit var viewModel:CameraViewModel
        instrumentation.runOnMainSync {
            viewModel=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
            viewModel.stacking.open(StackMode.PLANETARY)
        }
        val fixture=fixture(12)
        var width by mutableStateOf(380.dp)
        var showStack by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            // CameraScreen uses this same per-destination SaveableStateProvider contract.
            val savedPages=rememberSaveableStateHolder()
            if(showStack) savedPages.SaveableStateProvider(StackMode.PLANETARY.name) {
                // requiredWidth exercises both real 720dp branches even on a narrow emulator.
                Box(Modifier.requiredWidth(width).fillMaxHeight()) {
                    StackingScreen(viewModel,StackMode.PLANETARY,onBack={
                        viewModel.stacking.leave();showStack=false
                    })
                }
            } else Text("Stack page closed")
        } }
        fun field(label:Int)=compose.onNode(hasSetTextAction() and hasText(app.getString(label)))
        fun assertField(label:Int,value:String) {
            field(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(value)))
        }
        fun assertAnalyzedConfiguration() {
            assertField(R.string.stack_roi_x,"10")
            assertField(R.string.stack_roi_y,"0")
            assertField(R.string.stack_roi_w,"108")
            assertField(R.string.stack_roi_h,"96")
            assertField(R.string.stack_percent,"50")
            compose.onNode(isToggleable()).assertIsOff()
            // This also checks the saved analyzedConfig still matches all restored controls.
            compose.onNodeWithText(app.getString(R.string.stack_align)).assertIsEnabled()
            assertEquals(StackTaskPhase.ANALYZED,viewModel.stacking.task.value.phase)
            assertEquals(12L,viewModel.stacking.task.value.qualityFrames)
        }
        try {
            compose.runOnIdle { viewModel.stacking.loadFile(fixture) }
            waitFor(viewModel.stacking,StackTaskPhase.READY)
            field(R.string.stack_roi_x).performScrollTo().performTextReplacement("10")
            field(R.string.stack_roi_w).performScrollTo().performTextReplacement("108")
            field(R.string.stack_roi_h).performScrollTo().performTextReplacement("96")
            // Use a non-default alignment setting as well as a non-default crop.
            compose.onNode(isToggleable()).performScrollTo().performClick().assertIsOff()
            compose.onNodeWithText(app.getString(R.string.stack_analyze)).performScrollTo().performClick()
            waitFor(viewModel.stacking,StackTaskPhase.ANALYZED)
            compose.onNodeWithText("50%").performScrollTo().performClick()
            assertAnalyzedConfiguration()
            val histogram=viewModel.stacking.task.value.histogram.toList()
            val reference=viewModel.stacking.task.value.referenceFrame

            // Semantic assertions do not require controls to fit physically on a phone screen.
            compose.runOnIdle { width=800.dp }
            assertAnalyzedConfiguration()
            compose.runOnIdle { width=380.dp }
            assertAnalyzedConfiguration()
            assertEquals(histogram,viewModel.stacking.task.value.histogram)
            assertEquals(reference,viewModel.stacking.task.value.referenceFrame)

            // Exercise the screen's actual Back callback, remove its composition, then reopen.
            compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
            compose.onNodeWithText("Stack page closed").assertIsDisplayed()
            compose.runOnIdle { viewModel.stacking.open(StackMode.PLANETARY);showStack=true }
            assertAnalyzedConfiguration()
            assertEquals(histogram,viewModel.stacking.task.value.histogram)
            compose.onNodeWithText(app.getString(R.string.stack_align)).performScrollTo().assertIsDisplayed()
        } finally {
            fixture.delete()
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun waitFor(coordinator:StackingCoordinator,phase:StackTaskPhase) {
        compose.waitUntil(40_000) { coordinator.task.value.phase==phase || coordinator.task.value.phase==StackTaskPhase.FAILED }
        assertEquals(coordinator.task.value.message,phase,coordinator.task.value.phase)
    }

    private fun fixture(frames:Int):File {
        val directory=File(app.cacheDir,"stack-instrumentation").apply { mkdirs() }
        val file=File(directory,"planet-${System.nanoTime()}.ser")
        val writer=SERWriter(file)
        writer.open(160,128,PixelFormat.MONO16,"Synthetic 16-bit planetary fixture","L")
        try { repeat(frames) { f ->
            val bytes=ByteArray(160*128*2)
            for(y in 0 until 128) for(x in 0 until 160) {
                val sx=x-.15*sin(f*.8);val sy=y-.17*cos(f*.7)
                val radius=hypot((sx-79.5)/1.1,sy-63.5)
                val limb=(1-tanh((radius-44)/.8))*.5
                val belts=.045*sin(sy*.47)+.025*sin(sy*.23+sx*.14)
                val texture=.025*sin(sx*.61+sy*.24)*cos(sy*.49)
                val value=((.012+limb*(.37+belts+texture+.08*max(0.0,1-radius/44)))*65535).roundToInt().coerceIn(0,65535)
                val p=(y*160+x)*2;bytes[p]=value.toByte();bytes[p+1]=(value ushr 8).toByte()
            }
            writer.writeFrame(FrameData(bytes,160,128,PixelFormat.MONO16,f.toLong(),f*10_000L))
        } } finally { writer.close() }
        return file
    }

    private fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
    private fun artifactDirectory()=(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        ?: File(app.getExternalFilesDir(null),"stack-test-artifacts")).apply { mkdirs() }
    private fun screenshot(name:String) {
        val screenshot=instrumentation.uiAutomation.takeScreenshot() ?: return
        try { File(artifactDirectory(),name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { screenshot.recycle() }
    }
}
