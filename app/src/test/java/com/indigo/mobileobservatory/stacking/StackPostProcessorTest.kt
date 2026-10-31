package com.indigo.mobileobservatory.stacking

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class StackPostProcessorTest {
    @Test fun independentSwitchesBypassNonNeutralSettingsAndRestoreIdenticalPixels() {
        val image=LinearImage(32,32,3,FloatArray(32*32*3) { i ->
            ((.08+(i/3%32)/100.0)*when(i%3){0->1.2;1->1.0;else->.8}).toFloat()
        })
        val settings=StackProcessingParameters(blackPoint=.03,whitePoint=.8,midtone=.31,curve=32.0,
            brightness=1.4,highlightCompression=.7,autoWhiteBalance=true,redBalance=1.3,blueBalance=.7,saturation=1.8,
            background=BackgroundParameters(enabled=false))
        val enabled=StackPostProcessor.process(image,settings).image.pixels
        val allOff=settings.copy(stretchEnabled=false,whiteBalanceEnabled=false,saturationEnabled=false)
        assertArrayEquals(image.pixels,StackPostProcessor.process(image,allOff).image.pixels,0f)
        assertArrayEquals(enabled,StackPostProcessor.process(image,allOff.copy(stretchEnabled=true,
            whiteBalanceEnabled=true,saturationEnabled=true)).image.pixels,0f)
        assertArrayEquals(StackPostProcessor.process(image,settings.copy(autoWhiteBalance=false,redBalance=1.0,blueBalance=1.0)).image.pixels,
            StackPostProcessor.process(image,settings.copy(whiteBalanceEnabled=false)).image.pixels,0f)
        assertArrayEquals(StackPostProcessor.process(image,settings.copy(saturation=1.0)).image.pixels,
            StackPostProcessor.process(image,settings.copy(saturationEnabled=false)).image.pixels,0f)
        val neutralStretch=settings.copy(blackPoint=0.0,whitePoint=1.0,midtone=.5,curve=0.0,brightness=1.0,highlightCompression=0.0)
        assertArrayEquals(StackPostProcessor.process(image,neutralStretch).image.pixels,
            StackPostProcessor.process(image,settings.copy(stretchEnabled=false)).image.pixels,1e-7f)
        assertFalse(enabled.contentEquals(image.pixels))
    }

    @Test fun disablingStretchKeepsIndependentSharpeningActive() {
        val image=LinearImage(16,16,1,FloatArray(256) { p -> .1f + if(p%16 in 5..10 && p/16 in 5..10) .2f else 0f })
        val base=StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(stretchEnabled=false,
            sharpening=1.0,localContrast=.2,background=BackgroundParameters(enabled=false))
        assertArrayEquals(StackPostProcessor.process(image,base.copy(blackPoint=0.0,whitePoint=1.0,midtone=.5,
            curve=0.0,brightness=1.0,highlightCompression=0.0,stretchEnabled=true)).image.pixels,
            StackPostProcessor.process(image,base).image.pixels,1e-7f)
    }

    @Test fun maskedQuadraticGradientPreservesWeakNebulaAndSmoothEdges() {
        val w=192; val h=192; val random=Random(72)
        fun signal(x:Int,y:Int)=.012*exp(-((x-98.0).pow(2)+(y-90.0).pow(2))/(2*18.0*18.0))
        val input=LinearImage(w,h,1,FloatArray(w*h) { p -> val x=p%w;val y=p/w; val nx=2.0*(x+.5)/w-1;val ny=2.0*(y+.5)/h-1
            (.12+.035*nx-.018*ny-.025*(nx*nx+ny*ny)+signal(x,y)+random.nextGaussian()*.0007).toFloat() })
        val original=input.pixels.copyOf()
        val result=BackgroundGradient.correct(input,BackgroundParameters(true,1.0,12,listOf(TargetMask(.25,.2,.77,.73))))
        println("BACKGROUND applied=${result.model.applied}, reason=${result.model.reason}, spread ${result.model.before.gridSpread} -> ${result.model.after.gridSpread}")
        assertTrue(result.model.reason,result.model.applied)
        assertTrue(result.model.after.gridSpread<result.model.before.gridSpread*.15)
        val residual=ArrayList<Double>()
        for(y in 5 until h-5) for(x in 5 until w-5) residual+=result.image.pixels[y*w+x]-signal(x,y)
        val offset=StarRegistration.median(residual)
        val rms=sqrt(residual.sumOf { (it-offset).pow(2) }/residual.size)
        println("BACKGROUND target-preserving RMS=$rms; model=${result.model.coefficients}")
        assertTrue("Gradient residual $rms",rms<.0015)
        val centerSignal=result.image.pixels[90*w+98]-offset
        assertEquals(.012,centerSignal,.002)
        assertArrayEquals(original,input.pixels,0f)
        val coeff=result.model.coefficients.single()
        assertEquals(.035,coeff[1],.003);assertEquals(-.018,coeff[2],.003)
    }

    @Test fun unreliableFullyMaskedModelLeavesOriginalUntouched() {
        val input=LinearImage(64,64,1,FloatArray(4096){.2f})
        val result=BackgroundGradient.correct(input,BackgroundParameters(true,targetMasks=listOf(TargetMask(0.0,0.0,1.0,1.0))))
        assertFalse(result.model.applied);assertSame(input,result.image)
        assertEquals("INSUFFICIENT_UNMASKED_BACKGROUND",result.model.reason)
    }

    @Test fun strongStretchIsReproducibleMonotonicProtectsHighlightsAndKeepsRaw() {
        val input=LinearImage(128,32,1,FloatArray(128*32){p -> (p%128)/127f})
        val raw=input.pixels.copyOf();val params=StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(localContrast=0.0,background=BackgroundParameters(enabled=false))
        val first=StackPostProcessor.process(input,params);val second=StackPostProcessor.process(input,params)
        assertArrayEquals(first.image.pixels,second.image.pixels,0f);assertArrayEquals(raw,input.pixels,0f)
        for(x in 1 until 128) assertTrue(first.image.pixels[x]>first.image.pixels[x-1])
        assertTrue(first.image.pixels[1]>input.pixels[1]*5)
        assertTrue(first.image.pixels[120]<first.image.pixels[125])
        assertTrue(first.image.pixels[127]<.99f)
        assertSame(input,StackPostProcessor.process(input,StackProcessingParameters.preset(ProcessingPreset.RAW)).image)
    }

    @Test fun noiseGatedSharpeningDoesNotCreateRingingOrAmplifyFlatNoiseExcessively() {
        val w=128;val h=64;val random=Random(412)
        val image=LinearImage(w,h,1,FloatArray(w*h){p -> ((if(p%w<64) .12 else .6)+random.nextGaussian()*.002).toFloat()})
        val params=StackProcessingParameters(curve=0.0,highlightCompression=0.0,sharpening=1.0,background=BackgroundParameters(enabled=false))
        val output=StackPostProcessor.process(image,params).image
        fun noise(im:LinearImage)=sqrt((5 until 55).sumOf { y -> (5 until 50).sumOf { x -> (im.pixels[y*w+x]-.12).pow(2) } }/(50*45))
        assertTrue(noise(output)<noise(image)*1.2)
        assertTrue(output.pixels.minOrNull()!!>=image.pixels.minOrNull()!!-1e-6)
        assertTrue(output.pixels.maxOrNull()!!<=image.pixels.maxOrNull()!!+1e-6)
    }

    @Test fun whiteBalanceAndGamutCompressionRetainNeutralColors() {
        val w=64;val h=32
        val input=LinearImage(w,h,3,FloatArray(w*h*3){i -> val base=.08+(i/3%w)/128.0; (base*when(i%3){0->1.3;1->1.0;else->.8}).toFloat()})
        val result=StackPostProcessor.process(input,StackProcessingParameters(autoWhiteBalance=true,saturation=1.7,background=BackgroundParameters(enabled=false)))
        assertEquals(1/1.3,result.whiteBalanceGains[0],.002);assertEquals(1/.8,result.whiteBalanceGains[2],.002)
        for(p in 0 until w*h) {
            assertEquals(result.image.pixels[p*3],result.image.pixels[p*3+1],1e-5f)
            assertEquals(result.image.pixels[p*3+1],result.image.pixels[p*3+2],1e-5f)
        }
        assertTrue(result.image.pixels.all { it in 0f..1f })
    }
}
