package com.indigo.mobileobservatory.stacking

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

class StackAutoStretchTest {
    private fun darkScene(): LinearImage = LinearImage(256,128,1,FloatArray(256*128) { p ->
        val x=p%256;val y=p/256
        (.002 + .00012*sin(x*.79+y*.37) + .004*exp(-((x-128.0).pow(2)+(y-64.0).pow(2))/(2*14.0*14))).toFloat()
    })

    @Test fun darkNebulaBecomesVisibleWithoutChangingLinearSamples() {
        val raw=darkScene();val saved=raw.pixels.copyOf()
        val original=StackProcessingParameters.preset(ProcessingPreset.NATURAL).copy(background=BackgroundParameters(enabled=false))
        val estimated=StackAutoStretch.estimate(raw,original)!!
        val before=StackPostProcessor.process(raw,original).image.pixels
        val after=StackPostProcessor.process(raw,estimated).image.pixels
        val background=after[20*256+20]
        val center=after[64*256+128]
        println("AUTO_STRETCH background=$background target=$center priorTarget=${before[64*256+128]} recipe=$estimated")
        assertTrue(background in .05f.. .25f)
        assertTrue(center>background+.2f)
        assertTrue("Diffuse target peak should retain display headroom",center<.995f)
        assertTrue(center>before[64*256+128]*10)
        assertTrue(after.all { it.isFinite() && it in 0f..1f })
        assertArrayEquals(saved,raw.pixels,0f)
    }

    @Test fun sparseHotPixelsDoNotDictateTheRangeAndUncoveredBordersAreIgnored() {
        val raw=darkScene();val base=StackProcessingParameters()
        val normal=StackAutoStretch.estimate(raw,base)!!
        val contaminated=raw.deepCopy()
        repeat(12) { contaminated.pixels[it*2341]=1f }
        val robust=StackAutoStretch.estimate(contaminated,base)!!
        assertEquals(normal.whitePoint,robust.whitePoint,.0002)
        assertEquals(normal.blackPoint,robust.blackPoint,.00002)
        val covered=LinearImage(raw.width,raw.height*2,1,FloatArray(raw.pixels.size*2))
        raw.pixels.copyInto(covered.pixels,raw.pixels.size)
        val weights=FloatArray(covered.pixelCount) { if(it>=raw.pixelCount) 1f else 0f }
        val weighted=StackAutoStretch.estimate(covered,base,weights)!!
        assertEquals(normal.whitePoint,weighted.whitePoint,.00015)
        assertEquals(normal.midtone,weighted.midtone,.03)
    }

    @Test fun constantMostlyConstantAndNonFiniteSignalsCannotInventAStretch() {
        val flat=LinearImage(64,64,1,FloatArray(4096){.002f})
        assertNull(StackAutoStretch.estimate(flat,StackProcessingParameters()))
        repeat(4) { flat.pixels[it*751]=1f }
        assertNull(StackAutoStretch.estimate(flat,StackProcessingParameters()))
        val invalid=darkScene().also { it.pixels[1]=Float.NaN }
        assertNull(StackAutoStretch.estimate(invalid,StackProcessingParameters()))
        assertNull(StackAutoStretch.estimate(darkScene(),StackProcessingParameters(),FloatArray(256*128)))
    }

    @Test fun ordinarySerializedRecipeReplaysPixelsAndPreservesIndependentEdits() {
        val raw=darkScene()
        val user=StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(
            redBalance=1.27,blueBalance=.83,saturation=1.6,whiteBalanceEnabled=false,
            saturationEnabled=false,autoWhiteBalance=true,sharpening=.3,localContrast=.12,
            highlightCompression=.48,background=BackgroundParameters(false,.6,10,listOf(TargetMask(.2,.2,.6,.6))))
        val estimated=StackAutoStretch.estimate(raw,user)!!
        assertEquals(user,estimated.copy(blackPoint=user.blackPoint,whitePoint=user.whitePoint,
            midtone=user.midtone,curve=user.curve,brightness=user.brightness,stretchEnabled=user.stretchEnabled))
        val restored=StackResultMetadata.parseParameters(JSONObject(StackResultMetadata.parameters(estimated).toString()))
        assertEquals(estimated,restored)
        assertArrayEquals(StackPostProcessor.process(raw,estimated).image.pixels,
            StackPostProcessor.process(raw,restored).image.pixels,0f)
        assertEquals(estimated,StackAutoStretch.estimate(raw,user))
    }

    @Test fun explicitAutoFromRawSelectsAnEffectiveDisplayRecipe() {
        val recipe=StackAutoStretch.estimate(darkScene(),StackProcessingParameters.preset(ProcessingPreset.RAW).copy(stretchEnabled=false))!!
        assertEquals(ProcessingPreset.NATURAL,recipe.preset)
        assertTrue(recipe.stretchEnabled)
    }

    @Test fun samplingObservesCancellationBeforeCompletingLargeInputs() {
        val image=LinearImage(1024,1024,1,FloatArray(1024*1024) { .001f+(it%100)*.00001f })
        var checks=0
        try {
            StackAutoStretch.estimate(image,StackProcessingParameters()) { ++checks>=2 }
            fail("Cancelled estimate must not return a recipe")
        } catch (_: CancellationException) { assertEquals(2,checks) }
    }
}
