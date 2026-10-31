package com.indigo.mobileobservatory.stacking

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class StackResultMetadataTest {
    private fun recipe() = StackProcessingParameters(
        preset = ProcessingPreset.INTENSE, blackPoint = .02, whitePoint = .91, midtone = .37,
        curve = 36.7, brightness = 1.19, localContrast = .14, highlightCompression = .64,
        sharpening = .32, autoWhiteBalance = true, redBalance = 1.07, blueBalance = .96,
        saturation = 1.15, background = BackgroundParameters(true, .73, 10,
            listOf(TargetMask(.35,.3,.64,.7), TargetMask(.08,.12,.14,.18))))

    @Test fun fullRecipeRoundTripRetainsEveryFieldAndMaskWithoutQuantization() {
        val original = recipe().copy(stretchEnabled=false,whiteBalanceEnabled=false,saturationEnabled=false)
        val serialized = StackResultMetadata.parameters(original).toString()
        val restored = StackResultMetadata.parseParameters(JSONObject(serialized))
        assertEquals(original, restored)
        assertEquals(2, JSONObject(serialized).getJSONObject("background").getJSONArray("targetMasks").length())
    }

    @Test fun recipesWrittenBeforeIndependentSwitchesPreserveTheirExistingBehavior() {
        val legacy=StackResultMetadata.parameters(recipe())
        legacy.remove("stretchEnabled");legacy.remove("whiteBalanceEnabled");legacy.remove("saturationEnabled")
        assertEquals(recipe(),StackResultMetadata.parseParameters(JSONObject(legacy.toString())))
    }

    @Test fun missingDisplayVersionSelectsLegacyProcessingAndUnknownVersionsAreRejected() {
        val legacy=StackResultMetadata.parameters(recipe())
        legacy.remove("displayVersion")
        assertEquals(recipe().copy(displayVersion=1),StackResultMetadata.parseParameters(legacy))
        legacy.put("displayVersion",3)
        try { StackResultMetadata.parseParameters(legacy);fail("Unsupported display version accepted") }
        catch (expected:IllegalArgumentException) { assertTrue(expected.message!!.contains("version")) }
    }

    @Test fun reloadedRecipeReproducesActualProcessedPixelsAndBackgroundModel() {
        val width=96;val height=96
        val image=LinearImage(width,height,3,FloatArray(width*height*3) { i ->
            val x=(i/3)%width;val y=(i/3)/width
            val base=.1+.03*x/width-.01*y/height+.01*exp(-((x-48.0).pow(2)+(y-48.0).pow(2))/100)
            (base*when(i%3) {0->1.1;1->1.0;else->.85}).toFloat()
        })
        val raw=image.pixels.copyOf()
        val original=StackPostProcessor.process(image,recipe())
        val exported=JSONObject(StackResultMetadata.processing(original).toString())
        val restored=StackPostProcessor.process(image,StackResultMetadata.parseParameters(exported.getJSONObject("parameters")))
        assertArrayEquals(original.image.pixels,restored.image.pixels,0f)
        assertArrayEquals(raw,image.pixels,0f)
        assertEquals(original.background,restored.background)
        assertEquals(original.whiteBalanceGains,restored.whiteBalanceGains)
        val model=exported.getJSONObject("backgroundModel")
        assertEquals(original.background!!.applied,model.getBoolean("applied"))
        assertEquals(original.background!!.acceptedSamples,model.getInt("acceptedSamples"))
        assertEquals(original.background!!.before.gridSpread,model.getJSONObject("before").getDouble("gridSpread"),0.0)
        assertEquals(original.background!!.after.noise,model.getJSONObject("after").getDouble("noise"),0.0)
        assertEquals(3,model.getJSONArray("coefficients").length())
    }

    @Test fun rawRecipeRoundTripLeavesOriginalLinearSamplesIncludingHeadroomUntouched() {
        val image=LinearImage(2,2,1,floatArrayOf(-.01f,.001234567f,.97f,1.03f))
        val parameters=StackResultMetadata.parseParameters(JSONObject(StackResultMetadata.parameters(
            StackProcessingParameters.preset(ProcessingPreset.RAW)).toString()))
        val result=StackPostProcessor.process(image,parameters)
        assertSame(image,result.image)
        assertArrayEquals(image.pixels,result.image.pixels,0f)
        assertFalse(StackResultMetadata.processing(result).has("backgroundModel"))
    }

    @Test fun unknownRecipeVersionFailsRatherThanSilentlyChangingPixels() {
        val metadata=StackResultMetadata.parameters(recipe()).put("schema",2)
        try { StackResultMetadata.parseParameters(metadata);fail("Unsupported recipe accepted") }
        catch (expected:IllegalArgumentException) { assertTrue(expected.message!!.contains("version")) }
    }
}
