package com.indigo.mobileobservatory.stacking

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.recording.SERWriter
import com.indigo.mobileobservatory.media.MediaImageRegion
import com.indigo.mobileobservatory.media.MediaPng16Codec
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Real registration, Android rendering/export and coordinator lifecycle; no physical camera. */
@RunWith(AndroidJUnit4::class)
class StackEaaCoordinatorTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private val key=StackSessionKey("eaa-fixture",128,128,PixelFormat.MONO16,Roi(0,0,128,128),1,1,100_000.0,10f,"L","raw")

    @Test fun firstSnapshotAdaptsOnceAndImmediateManualAutoExportUsesTheSameRecipe()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val c=StackingCoordinator(app,scope)
        val exports=mutableListOf<File>()
        try {
            main { c.open(StackMode.LIVE);c.startLive(key);c.acceptFrame(frame(1),key) }
            await { c.live.state.value.accepted==1L && c.livePreview.value!=null && !c.rendering.value }
            val first=c.processing.value
            assertTrue(first.whitePoint<.2)
            main { c.acceptFrame(frame(2,1.6),key) }
            await { c.live.state.value.accepted==2L }
            main { c.live.pause() }
            await { c.live.state.value.phase==LiveStackPhase.PAUSED && !c.rendering.value && c.live.latestSnapshot?.state?.accepted==2L }
            assertEquals("Later snapshots must not pump display parameters",first,c.processing.value)
            val original=c.live.latestSnapshot!!.image.pixels.copyOf()
            main { c.report("old_failure");c.autoStretch();c.export() }
            await { c.task.value.exportedFiles.size==3 && !c.hasPlanetaryWork() && !c.rendering.value }
            exports+=c.task.value.exportedFiles.map(::File)
            assertNotEquals(first,c.processing.value)
            assertNotEquals("old_failure",c.task.value.message)
            assertArrayEquals(original,c.live.latestSnapshot!!.image.pixels,0f)
            val metadata=JSONObject(exports.single { it.extension=="json" }.readText())
            val recipe=StackResultMetadata.parseParameters(JSONObject(metadata.getString("processing")).getJSONObject("parameters"))
            assertEquals(c.processing.value,recipe)
            val raw=StackFitsReader.read(exports.single { it.extension=="fits" })
            assertArrayEquals(original,raw.pixels,0f)
            val restored=StackFitsPreview.read(exports.single { it.extension=="fits" })
            val processed=StackPostProcessor.process(raw,recipe).image
            assertArrayEquals(processed.pixels,restored.image.pixels,0f)
            val pngFile=exports.single { it.extension=="png" }
            MediaPng16Codec.open(pngFile).use { reader ->
                val values=reader.read(MediaImageRegion(0,0,processed.width,processed.height))
                for(i in processed.pixels.indices)
                    assertEquals((processed.pixels[i].coerceIn(0f,1f).toDouble()*65535).roundToInt(),values.unsigned(i))
            }
            val png=BitmapFactory.decodeFile(pngFile.absolutePath)
            try {
                val previewPixels=pixels(c.livePreview.value!!)
                val pngPixels=pixels(png)
                assertEquals(previewPixels.size,pngPixels.size)
                // Preview uses a display tone LUT; Android also reduces the saved
                // 16-bit PNG to 8-bit Bitmap channels. Keep exact export checks above.
                for(i in pngPixels.indices) for(shift in listOf(0,8,16))
                    assertTrue(abs((previewPixels[i] ushr shift and 255)-(pngPixels[i] ushr shift and 255))<=1)
            } finally { png.recycle() }
        } finally { main { c.close() };scope.cancel();exports.forEach { it.delete() } }
    }

    @Test fun userChangesBeatPendingAutoAndResetRestoresTheInitialOpportunity()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val c=StackingCoordinator(app,scope)
        try {
            val custom=StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(whitePoint=.7,saturation=1.31)
            main { c.open(StackMode.LIVE);c.updateProcessing(custom);c.startLive(key);c.acceptFrame(frame(1),key) }
            await { c.live.state.value.accepted==1L && c.livePreview.value!=null && !c.rendering.value }
            assertEquals(custom,c.processing.value)
            val later=custom.copy(midtone=.41)
            main { c.autoStretch();c.updateProcessing(later) }
            await { !c.rendering.value }
            assertEquals("An obsolete estimate must not overwrite a manual edit",later,c.processing.value)
            main { c.resetLive();c.startLive(key);c.acceptFrame(frame(2),key) }
            await { c.live.state.value.accepted==1L && c.livePreview.value!=null && !c.rendering.value }
            assertTrue(c.processing.value.whitePoint<.2)
            assertEquals(later.saturation,c.processing.value.saturation,0.0)
            main { c.resetLive();c.selectPreset(ProcessingPreset.RAW);c.startLive(key);c.acceptFrame(frame(3),key) }
            await { c.live.state.value.accepted==1L && c.livePreview.value!=null && !c.rendering.value }
            assertEquals(ProcessingPreset.RAW,c.processing.value.preset)
        } finally { main { c.close() };scope.cancel() }
    }

    @Test fun modePresetsPersistAndLeavingOfflineResumesMainCameraStackRendering()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val c=StackingCoordinator(app,scope)
        try {
            val liveRecipe=StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(saturation=1.4)
            main { c.open(StackMode.LIVE);c.updateProcessing(liveRecipe);c.startLive(key);c.acceptFrame(frame(1),key) }
            await { c.livePreview.value!=null && !c.rendering.value }
            main { c.open(StackMode.PLANETARY);c.selectPreset(ProcessingPreset.RAW);c.leave() }
            assertNull(c.destination.value)
            assertEquals(liveRecipe,c.processing.value)
            await { !c.rendering.value }
            val before=c.livePreview.value
            main { c.acceptFrame(frame(2),key) }
            await { c.live.state.value.accepted==2L }
            main { c.live.pause() }
            await { c.live.state.value.phase==LiveStackPhase.PAUSED && c.livePreview.value!==before && !c.rendering.value }
            main { c.open(StackMode.LIVE) }
            assertEquals(liveRecipe,c.processing.value)
            main { c.open(StackMode.PLANETARY) }
            assertEquals(ProcessingPreset.RAW,c.processing.value.preset)
        } finally { main { c.close() };scope.cancel() }
    }

    @Test fun recordingGuardProtectsLoadAnalyzeAndStackWithoutBlockingOtherFiles()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val busy=AtomicBoolean(true)
        val file=File(app.cacheDir,"eaa-source-${System.nanoTime()}.ser")
        SERWriter(file).let { writer ->
            writer.open(128,128,PixelFormat.MONO16,"EAA fixture","L")
            try { repeat(3) { writer.writeFrame(frame(it.toLong())) } } finally { writer.close() }
        }
        val other=File(app.cacheDir,"eaa-other-${System.nanoTime()}.ser")
        file.copyTo(other)
        val c=StackingCoordinator(app,scope) { source -> source==file && busy.get() }
        try {
            main { c.open(StackMode.PLANETARY);c.loadFile(file) }
            await { c.task.value.phase==StackTaskPhase.FAILED && !c.hasPlanetaryWork() }
            assertEquals("recording_active",c.task.value.message)
            main { c.loadFile(other) }
            await { c.task.value.phase==StackTaskPhase.READY && !c.hasPlanetaryWork() }
            busy.set(false)
            main { c.loadFile(file) }
            await { c.task.value.phase==StackTaskPhase.READY && !c.hasPlanetaryWork() }
            busy.set(true)
            main { c.analyze(null,false) }
            await { c.task.value.phase==StackTaskPhase.FAILED && !c.hasPlanetaryWork() }
            assertEquals("recording_active",c.task.value.message)
            busy.set(false)
            main { c.analyze(null,false) }
            await { c.task.value.phase==StackTaskPhase.ANALYZED && !c.hasPlanetaryWork() }
            busy.set(true)
            main { c.stack(PlanetarySelection(100.0)) }
            await { c.task.value.phase==StackTaskPhase.FAILED && !c.hasPlanetaryWork() }
            assertEquals("recording_active",c.task.value.message)
        } finally { main { c.close() };scope.cancel();file.delete();other.delete() }
    }

    private fun main(action:()->Unit)=instrumentation.runOnMainSync { action() }
    private suspend fun await(condition:()->Boolean) { withTimeout(30_000) { while(!condition()) delay(20) } }
    private fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
    private fun frame(id:Long,scale:Double=1.0):FrameData {
        val random=Random(725)
        val stars=List(20) { Triple(12+random.nextDouble()*104,12+random.nextDouble()*104,.035+random.nextDouble()*.04) }
        val data=ByteArray(128*128*2)
        for(y in 0 until 128) for(x in 0 until 128) {
            var v=.002+.00010*sin(x*.71+y*.31)
            stars.forEach { (sx,sy,amplitude) -> v+=amplitude*exp(-((x-sx).pow(2)+(y-sy).pow(2))/(2*1.3*1.3)) }
            val value=(v*scale*65535).roundToInt().coerceIn(0,65535)
            val p=(y*128+x)*2;data[p]=value.toByte();data[p+1]=(value ushr 8).toByte()
        }
        return FrameData(data,128,128,PixelFormat.MONO16,id,id*100_000)
    }
}
