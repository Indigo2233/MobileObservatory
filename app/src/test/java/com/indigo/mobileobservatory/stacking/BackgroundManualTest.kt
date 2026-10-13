package com.indigo.mobileobservatory.stacking

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.*

class BackgroundManualTest {
    private val points=listOf(.08,.25,.75,.92).flatMap { y ->
        listOf(.08,.25,.75,.92).map { x -> BackgroundSamplePoint(x,y) }
    }
    private val manual=BackgroundParameters(strength=1.0,mode=BackgroundMode.MANUAL,
        sampleRadius=.02,samplePoints=points,targetMasks=listOf(TargetMask(.32,.28,.68,.72)))
    private fun nebula(x:Int,y:Int)=.012*exp(-((x-95.5).pow(2)+(y-79.5).pow(2))/(2*14.0*14))
    private fun scene():LinearImage {
        val random=Random(7201)
        return LinearImage(192,160,1,FloatArray(192*160) { p ->
            val x=p%192;val y=p/192;val nx=2*(x+.5)/192-1;val ny=2*(y+.5)/160-1
            (.12+.025*nx-.02*ny-.018*(nx*nx+ny*ny)+.01*nx*ny+nebula(x,y)+random.nextGaussian()*.00035).toFloat()
        })
    }

    @Test fun manuallyPlacedBackgroundPatchesRecoverGradientWithoutEatingTheNebula() {
        val image=scene();val original=image.pixels.copyOf()
        val result=BackgroundGradient.correct(image,manual)
        assertTrue(result.model.reason,result.model.applied)
        assertEquals(BackgroundMode.MANUAL,result.model.mode)
        assertTrue(result.model.acceptedSamples>=12)
        assertTrue(result.model.after.gridSpread<result.model.before.gridSpread*.04)
        val residual=List(image.pixelCount) { p -> result.image.pixels[p]-nebula(p%192,p/192) }
        val anchor=StarRegistration.median(residual)
        val rms=sqrt(residual.sumOf { (it-anchor).pow(2) }/residual.size)
        println("MANUAL_DBE samples=${result.model.acceptedSamples} residualRms=$rms spread=${result.model.before.gridSpread}->${result.model.after.gridSpread}")
        assertTrue("Residual RMS $rms",rms<.00065)
        assertEquals(.012,result.image.pixels[80*192+96]-anchor,.001)
        assertEquals(.025,result.model.coefficients.single()[1],.001)
        assertEquals(-.02,result.model.coefficients.single()[2],.001)
        assertArrayEquals(original,image.pixels,0f)
        assertEquals(points,result.model.samplePoints)
        assertEquals(manual.sampleRadius,result.model.sampleRadius,0.0)
    }

    @Test fun aDistributedFourByThreeLayoutHasEnoughIndependentSupport() {
        val twelve=listOf(.08,.5,.92).flatMap { y -> listOf(.08,.25,.75,.92).map { x -> BackgroundSamplePoint(x,y) } }
        val result=BackgroundGradient.correct(scene(),manual.copy(samplePoints=twelve))
        assertTrue(result.model.reason,result.model.applied)
        assertEquals(12,result.model.acceptedSamples)
    }

    @Test fun insufficientDuplicateOutOfRangeAndInvalidRadiusNeverChangeTheImage() {
        val image=scene()
        val cases=listOf(
            manual.copy(samplePoints=points.take(11)) to "INSUFFICIENT_BACKGROUND_POINTS",
            manual.copy(samplePoints=points+points.first()) to "DUPLICATE_BACKGROUND_POINTS",
            manual.copy(samplePoints=points+BackgroundSamplePoint(-.1,.5)) to "INVALID_BACKGROUND_POINTS",
            manual.copy(samplePoints=points+BackgroundSamplePoint(Double.NaN,.5)) to "INVALID_BACKGROUND_POINTS",
            manual.copy(sampleRadius=Double.NaN) to "INVALID_BACKGROUND_POINTS",
            manual.copy(sampleRadius=0.0) to "INVALID_BACKGROUND_POINTS"
        )
        for((parameters,reason) in cases) {
            val result=BackgroundGradient.correct(image,parameters)
            assertFalse(result.model.applied);assertEquals(reason,result.model.reason);assertSame(image,result.image)
        }
    }

    @Test fun clusteredCollinearOrMaskedSamplesFailConservatively() {
        val image=scene()
        val central=listOf(.38,.46,.54,.62).flatMap { y -> listOf(.38,.5,.62).map { x -> BackgroundSamplePoint(x,y) } }
        val diagonal=List(12) { i -> BackgroundSamplePoint(.08+i*.84/11,.08+i*.84/11) }
        for(locations in listOf(central,diagonal)) {
            val result=BackgroundGradient.correct(image,manual.copy(samplePoints=locations,targetMasks=emptyList()))
            assertFalse(result.model.applied);assertEquals("UNBALANCED_BACKGROUND_COVERAGE",result.model.reason);assertSame(image,result.image)
        }
        val masked=BackgroundGradient.correct(image,manual.copy(targetMasks=listOf(TargetMask(0.0,0.0,1.0,1.0))))
        assertFalse(masked.model.applied);assertEquals("INSUFFICIENT_BACKGROUND_POINTS",masked.model.reason);assertSame(image,masked.image)
    }

    @Test fun pointsOnOneConicCannotDetermineAQuadraticEvenWithWideCoverage() {
        val circle=List(16) { i -> BackgroundSamplePoint(.5+.42*cos(i*2*PI/16),.5+.42*sin(i*2*PI/16)) }
        val image=scene()
        val result=BackgroundGradient.correct(image,manual.copy(samplePoints=circle,targetMasks=emptyList()))
        assertFalse(result.model.applied)
        assertEquals("SINGULAR_BACKGROUND_MODEL",result.model.reason)
        assertSame(image,result.image)
    }

    @Test fun manualRecipeAndModelSerializeAllPointControlsAndReplayIdentically() {
        val image=scene()
        val recipe=StackProcessingParameters(background=manual)
        val restored=StackResultMetadata.parseParameters(JSONObject(StackResultMetadata.parameters(recipe).toString()))
        assertEquals(recipe,restored)
        val first=StackPostProcessor.process(image,recipe)
        val second=StackPostProcessor.process(image,restored)
        assertArrayEquals(first.image.pixels,second.image.pixels,0f)
        assertEquals(first.background,second.background)
        val model=StackResultMetadata.processing(first).getJSONObject("backgroundModel")
        assertEquals("MANUAL",model.getString("mode"))
        assertEquals(.02,model.getDouble("sampleRadius"),0.0)
        assertEquals(points.size,model.getJSONArray("samplePoints").length())
        assertEquals(manual.targetMasks.size,model.getJSONArray("targetMasks").length())
    }

    @Test fun newDefaultsEnableAutoButOldDisabledRecipesStayDisabled() {
        assertTrue(BackgroundParameters().enabled)
        assertEquals(BackgroundMode.AUTO,BackgroundParameters().mode)
        assertTrue(StackProcessingParameters.preset(ProcessingPreset.NATURAL).background.enabled)
        for(enabled in listOf(false,true)) {
            val legacy=StackResultMetadata.parameters(StackProcessingParameters(background=BackgroundParameters(enabled=enabled)))
            legacy.getJSONObject("background").apply { remove("mode");remove("sampleRadius");remove("samplePoints") }
            val restored=StackResultMetadata.parseParameters(legacy)
            assertEquals(enabled,restored.background.enabled)
            assertEquals(BackgroundMode.AUTO,restored.background.mode)
            assertEquals(.025,restored.background.sampleRadius,0.0)
            assertTrue(restored.background.samplePoints.isEmpty())
        }
    }

    @Test fun cancellationAndDisabledModeDoNotApplyAnIncompleteModel() {
        val image=scene()
        assertSame(image,BackgroundGradient.correct(image,manual.copy(enabled=false)).image)
        try {
            BackgroundGradient.correct(image,manual) { true }
            fail("Cancelled fitting must not return a corrected image")
        } catch (_: CancellationException) { }
    }
}
