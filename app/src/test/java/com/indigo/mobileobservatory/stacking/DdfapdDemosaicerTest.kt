package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import java.util.concurrent.CancellationException
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class DdfapdDemosaicerTest {
    private val formats = arrayOf(PixelFormat.BAYER_RG16, PixelFormat.BAYER_GR16,
        PixelFormat.BAYER_GB16, PixelFormat.BAYER_BG16)

    @Test fun publishedMenonExampleIncludesTheRefiningStepAndMirrorBorders() {
        val input = floatArrayOf(.30980393f,.36078432f,.30588236f,.3764706f,
            .35686275f,.39607844f,.36078432f,.40000001f)
        val expectedRow = floatArrayOf(.30980393f,.35686275f,.39215687f,
            .30980393f,.36078432f,.39607844f,.30588236f,.36078432f,.39019608f,
            .32156864f,.3764706f,.40000001f)
        val result = DdfapdDemosaicer.demosaic(input,4,2,0,0)
        assertArrayEquals(expectedRow + expectedRow,result.pixels,1e-7f)
    }

    @Test fun nativeSamplesRemainExactOnEveryCfaEvenForOddTinyHdrImages() {
        for ((w,h) in listOf(2 to 2,2 to 7,7 to 2,3 to 5,9 to 8)) for (phase in 0..3) {
            val src = FloatArray(w*h) { p -> (((p*37+19)%101)-42)/32f }
            val original = src.copyOf()
            val result = DdfapdDemosaicer.demosaic(src,w,h,phase and 1,phase shr 1)
            for (y in 0 until h) for (x in 0 until w) {
                val p = y*w+x
                assertEquals("Native sample at $x,$y phase $phase",src[p].toBits(),
                    result.pixels[p*3+colour(x,y,phase)].toBits())
            }
            assertTrue(result.pixels.all { it.isFinite() })
            assertTrue("Negative calibrated signal must survive",result.pixels.any { it < 0f })
            assertTrue("Above-white signal must survive",result.pixels.any { it > 1f })
            assertArrayEquals(original,src,0f)
        }
    }

    @Test fun constantColourPlanesAreRecoveredIncludingEverySmallImageBorder() {
        val levels = floatArrayOf(-.125f,.25f,1.5f)
        for ((w,h) in listOf(2 to 2,2 to 9,9 to 2,7 to 5,10 to 8)) for (phase in 0..3) {
            val src = FloatArray(w*h) { p -> levels[colour(p%w,p/w,phase)] }
            val output = DdfapdDemosaicer.demosaic(src,w,h,phase and 1,phase shr 1)
            for (p in src.indices) for (c in 0..2) assertEquals(levels[c],output.pixels[p*3+c],1e-7f)
        }
    }

    @Test fun roiPhaseMatchesExplicitOutputCfaAndSupportsOddHardwareBin() {
        val w=9; val h=7
        val raw = LinearImage(w,h,1,FloatArray(w*h) { ((it*71)%107)/89f-.15f })
        for (phase in 0..3) for (offset in 0..3) {
            val traits = PixelTraits(roiX=4+(offset and 1),roiY=8+(offset shr 1),binning=3,
                bayerPatternAtOutput=false)
            val actual = LinearPixelDecoder.demosaicSensor(raw,formats[phase],traits,DemosaicAlgorithm.DDFAPD)
            val expected = LinearPixelDecoder.demosaicSensor(raw,formats[phase xor offset],
                algorithm=DemosaicAlgorithm.DDFAPD)
            assertArrayEquals(expected.pixels,actual.pixels,0f)
            val explicit = LinearPixelDecoder.demosaicSensor(raw,formats[phase],
                traits.copy(bayerPatternAtOutput=true,binning=2),DemosaicAlgorithm.DDFAPD)
            val unshifted = LinearPixelDecoder.demosaicSensor(raw,formats[phase],algorithm=DemosaicAlgorithm.DDFAPD)
            assertArrayEquals(unshifted.pixels,explicit.pixels,0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinearPixelDecoder.demosaicSensor(raw,formats[0],
                PixelTraits(binning=2,bayerPatternAtOutput=false),DemosaicAlgorithm.DDFAPD)
        }
    }

    @Test fun decodeNormalizesLeftAlignedSensorSamplesBeforeSelectedAlgorithm() {
        val w=7; val h=5
        val values = IntArray(w*h) { (it*109+7)%4096 }
        val bytes = ByteArray(w*h*2)
        for (p in values.indices) {
            val value = values[p] shl 4
            bytes[p*2]=value.toByte(); bytes[p*2+1]=(value ushr 8).toByte()
        }
        val frame = FrameData(bytes,w,h,PixelFormat.BAYER_GB12,42,0)
        val traits = PixelTraits(alignment=SampleAlignment.LEFT,validBits=12,roiX=1,
            bayerPatternAtOutput=false)
        val result = LinearPixelDecoder.decode(frame,traits,algorithm=DemosaicAlgorithm.DDFAPD)
        val expected = DdfapdDemosaicer.demosaic(FloatArray(w*h) { values[it]/4095f },w,h,1,1)
        assertArrayEquals(expected.pixels,result.pixels,0f)
    }

    @Test fun directionalReconstructionReducesBlurAtAxisAlignedChromaticEdges() {
        val w=64; val h=56
        val low=floatArrayOf(.06f,.12f,.02f); val high=floatArrayOf(.91f,.64f,.30f)
        for (vertical in listOf(false,true)) for (phase in 0..3) {
            fun truth(x: Int,y: Int,c: Int) = if (if (vertical) x>=w/2 else y>=h/2) high[c] else low[c]
            val src = FloatArray(w*h) { p -> truth(p%w,p/w,colour(p%w,p/w,phase)) }
            val sensor=LinearImage(w,h,1,src)
            val ddfapd=LinearPixelDecoder.demosaicSensor(sensor,formats[phase],algorithm=DemosaicAlgorithm.DDFAPD)
            val bilinear=LinearPixelDecoder.demosaicSensor(sensor,formats[phase])
            fun rmse(image: LinearImage): Double {
                var sum=0.0; var n=0
                for (y in 4 until h-4) for (x in 4 until w-4) for (c in 0..2) {
                    val residual=image.pixels[(y*w+x)*3+c]-truth(x,y,c)
                    sum+=residual*residual; n++
                }
                return sqrt(sum/n)
            }
            val d=rmse(ddfapd); val b=rmse(bilinear)
            assertTrue("DDFAPD $d should reduce bilinear edge error $b (vertical=$vertical phase=$phase)",d<b*.6)
        }
    }

    @Test fun cancellationIsCheckedBeforeAllocationAndDuringLateRefinement() {
        val w=41; val h=39
        val src=FloatArray(w*h) { it/1600f }
        val copy=src.copyOf()
        assertThrows(CancellationException::class.java) {
            DdfapdDemosaicer.demosaic(src,w,h,0,0) { throw CancellationException("before scratch") }
        }
        var polls=0
        val stopAfter=9*h+1
        assertThrows(CancellationException::class.java) {
            DdfapdDemosaicer.demosaic(src,w,h,0,0) {
                if (++polls==stopAfter) throw CancellationException("refining")
            }
        }
        assertEquals(stopAfter,polls)
        assertArrayEquals(copy,src,0f)
    }

    @Test fun invalidInputsFailWithoutReturningCorruptedPixels() {
        assertThrows(IllegalArgumentException::class.java) { DdfapdDemosaicer.demosaic(FloatArray(6),1,6,0,0) }
        assertThrows(IllegalArgumentException::class.java) { DdfapdDemosaicer.demosaic(FloatArray(6),3,2,2,0) }
        assertThrows(IllegalArgumentException::class.java) { DdfapdDemosaicer.demosaic(FloatArray(5),3,2,0,0) }
        for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            val src=FloatArray(35) { .2f }; src[17]=bad
            assertThrows(IllegalArgumentException::class.java) { DdfapdDemosaicer.demosaic(src,7,5,0,0) }
        }
    }

    @Test fun defaultStaysBilinearAndNonBayerIsIdentityWithNoAlgorithmScratch() {
        val src=LinearImage(3,3,1,floatArrayOf(.2f,.3f,.4f,.5f,.6f,.7f,.8f,.9f,1f))
        assertArrayEquals(LinearPixelDecoder.demosaicSensor(src,formats[0],algorithm=DemosaicAlgorithm.BILINEAR).pixels,
            LinearPixelDecoder.demosaicSensor(src,formats[0]).pixels,0f)
        for (algorithm in DemosaicAlgorithm.values()) for (format in listOf(PixelFormat.MONO16,PixelFormat.RGB48)) {
            val sensor=if(format.isRgb) LinearImage(3,3,3,FloatArray(27) { it/20f-.1f }) else src
            assertSame(sensor,LinearPixelDecoder.demosaicSensor(sensor,format,algorithm=algorithm))
            assertEquals(0L,LinearPixelDecoder.demosaicExtraBytes(3,3,format,algorithm))
        }
    }

    @Test fun scratchEstimateUsesFullSensorResolutionAndLongArithmetic() {
        assertEquals(4096L+33L*4096*4096,
            LinearPixelDecoder.demosaicExtraBytes(4096,4096,formats[0],DemosaicAlgorithm.DDFAPD))
        assertEquals(4096L+33L*100_000*100_000,DdfapdDemosaicer.extraBytes(100_000,100_000))
        assertEquals(0L,LinearPixelDecoder.demosaicExtraBytes(640,480,formats[0],DemosaicAlgorithm.BILINEAR))
    }

    private fun colour(x: Int,y: Int,phase: Int): Int = when {
        (x and 1)==(phase and 1) && (y and 1)==(phase shr 1) -> 0
        (x and 1)!=(phase and 1) && (y and 1)!=(phase shr 1) -> 2
        else -> 1
    }
}
