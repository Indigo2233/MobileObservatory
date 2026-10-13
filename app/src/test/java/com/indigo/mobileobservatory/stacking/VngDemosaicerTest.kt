package com.indigo.mobileobservatory.stacking

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class VngDemosaicerTest {
    private fun color(x: Int, y: Int, rx: Int, ry: Int) = when {
        (x and 1) == rx && (y and 1) == ry -> 0
        (x and 1) != rx && (y and 1) != ry -> 2
        else -> 1
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid input to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun fourCfaFullInteriorMatchesCommittedOfficialOpenCvFixtureAtSensorCoordinates() {
        // Reproduce independently: python scripts/verify_vng_reference.py
        // The fixture stores full, unmodified OpenCV 4.10.0 RGB8 outputs. Its native
        // CFA channel proves the two-row displacement in the returned OpenCV image.
        val w = 33; val h = 31
        val golden = requireNotNull(javaClass.getResourceAsStream("/stacking/vng-opencv-4.10.0.rgb8"))
            .use { it.readBytes() }
        assertEquals(4*w*h*3,golden.size)
        val raw = FloatArray(w*h) { p ->
            val x = p%w; val y = p/w
            (16*(4+(3*x+5*y+(x*y%7))%7)).toFloat()
        }
        for (ry in 0..1) for (rx in 0..1) {
            val actual = VngDemosaicer.demosaic(raw,w,h,rx,ry)
            val offset = (ry*2+rx)*w*h*3
            var unshiftedNativeMismatches = 0
            // Both implementations use genuine VNG here; neither border is included.
            for (y in 4 until h-2) for (x in 2 until w-2) {
                val native = color(x,y,rx,ry)
                assertEquals(raw[y*w+x],actual.pixels[(y*w+x)*3+native],0f)
                assertEquals(raw[y*w+x],(golden[offset+((y-2)*w+x)*3+native].toInt() and 255).toFloat(),0f)
                if ((golden[offset+(y*w+x)*3+native].toInt() and 255).toFloat() != raw[y*w+x])
                    unshiftedNativeMismatches++
                for (c in 0..2) {
                    val expected = golden[offset+((y-2)*w+x)*3+c].toInt() and 255
                    assertEquals("CFA $rx,$ry sensor $x,$y channel $c",expected.toFloat(),actual.pixels[(y*w+x)*3+c],.501f)
                }
            }
            assertEquals("The reference shift must be observable in native samples",581,unshiftedNativeMismatches)
        }
    }

    @Test fun independentOpenCv410OracleMatchesInteriorWithinItsIntegerRounding() {
        // Oracle generated using the official OpenCV 4.10.0 binary, opencv-python-headless
        // 4.10.0.84: cv2.cvtColor(raw, cv2.COLOR_BayerRGGB2RGB_VNG).
        // raw[y,x] = 4*(5+(13*x+7*y+3*(x*y%11)+(37 if x+2*y>31 else 0))%51).
        // Read oracle[y-2,x]: upstream VNG has an explicit bayer += 2*bstep input displacement.
        // All samples are multiples of four, eliminating the original integer gradient floor.
        // These fixed expected pixels are independent of this Kotlin implementation.
        val golden = intArrayOf(
            194,136,115, 128,93,72, 57,48,52, 40,32,45, 175,164,158,
            172,106,92, 92,24,4, 79,48,28, 61,32,32, 153,167,168,
            210,180,135, 196,142,115, 246,212,212, 156,144,130, 155,172,138,
            170,119,136, 126,92,122, 133,122,120, 165,148,148, 181,181,176,
            88,20,70, 60,26,73, 122,100,122, 140,128,127, 199,180,196)
        val raw = FloatArray(25 * 23) { p ->
            val x = p % 25; val y = p / 25
            (4 * (5 + (13*x + 7*y + 3*(x*y%11) + if (x+2*y>31) 37 else 0) % 51)).toFloat()
        }
        val output = VngDemosaicer.demosaic(raw,25,23,0,0)
        var i = 0
        for (y in 6..10) for (x in 5..9) for (c in 0..2)
            assertEquals("OpenCV oracle at $x,$y/$c", golden[i++].toFloat(), output.pixels[(y*25+x)*3+c], .501f)
    }

    @Test fun allFourCfasOddDimensionsSmallImagesAndEdgesPreserveNativeSamplesAndConstantColor() {
        val rgb = floatArrayOf(-.125f, .234567f, 1.25f)
        for (w in listOf(2,3,4,5,9,17)) for (h in listOf(2,3,4,5,11)) for (rx in 0..1) for (ry in 0..1) {
            val raw = FloatArray(w*h) { rgb[color(it%w,it/w,rx,ry)] }; val before = raw.copyOf()
            val output = VngDemosaicer.demosaic(raw,w,h,rx,ry)
            assertEquals(3,output.channels)
            for (p in raw.indices) {
                assertEquals(raw[p].toRawBits(),output.pixels[p*3+color(p%w,p/w,rx,ry)].toRawBits())
                for (c in 0..2) assertEquals("$w x $h/$rx,$ry/$p/$c",rgb[c],output.pixels[p*3+c],1e-7f)
            }
            assertArrayEquals(before,raw,0f)
        }
    }

    @Test fun knownGrayDirectionalScenesReduceInterpolationErrorAndBayerGridColor() {
        val w = 65; val h = 63
        for (kind in 0..4) for (rx in 0..1) for (ry in 0..1) {
            fun scene(x: Int,y: Int): Float = when (kind) {
                0 -> if (x < 32) .1f else .8f
                1 -> if (y < 30) .1f else .8f
                2 -> if (x-y < 1) .1f else .8f
                3 -> (.45 + .3*sin((x+y)*.35)).toFloat()
                else -> (.45 + .3*sin(x*.4)*cos(y*.07)).toFloat()
            }
            val raw = FloatArray(w*h) { scene(it%w,it/w) }
            val vng = VngDemosaicer.demosaic(raw,w,h,rx,ry).pixels
            var error = 0.0; var baselineError = 0.0; var grid = 0.0; var baselineGrid = 0.0
            for (y in 3 until h-3) for (x in 3 until w-3) {
                val p = y*w+x; val reference = scene(x,y)
                val baseline = bilinearAt(raw,w,h,x,y,rx,ry)
                for (c in 0..2) {
                    error += (vng[p*3+c]-reference).toDouble().pow(2)
                    baselineError += (baseline[c]-reference).pow(2)
                }
                // A gray scene must not acquire alternating R/B colour from its CFA sampling.
                grid += abs(vng[p*3]-vng[p*3+2]); baselineGrid += abs(baseline[0]-baseline[2])
            }
            assertTrue("scene $kind CFA $rx,$ry: squared error $error vs $baselineError",error < baselineError*.99)
            assertTrue("scene $kind CFA $rx,$ry: false colour $grid vs $baselineGrid",grid < baselineGrid*.9)
        }
    }

    @Test fun sixteenBitAndVeryFaintSignalsDoNotQuantizeAndCalibrationRangeIsNotClipped() {
        val w = 31; val h = 29
        val raw = FloatArray(w*h) { i -> .25f + ((i%w)*5 + (i/w)*7) / 65535f }
        val out = VngDemosaicer.demosaic(raw,w,h,0,0)
        for (y in 3 until h-3) for (x in 3 until w-3) for (c in 0..2)
            assertEquals("Linear plane must be reconstructed",raw[y*w+x],out.pixels[(y*w+x)*3+c],1e-7f)
        assertNotEquals(out.pixels[(6*w+6)*3+1],out.pixels[(6*w+7)*3+1])
        val tiny = raw.map { (it-.25f)*.0001f }.toFloatArray()
        val tinyOut = VngDemosaicer.demosaic(tiny,w,h,0,0)
        for (y in 3 until h-3) for (x in 3 until w-3) for (c in 0..2)
            assertEquals(tiny[y*w+x],tinyOut.pixels[(y*w+x)*3+c],1e-11f)
        val wide = raw.map { (it-.252f)*1000f }.toFloatArray()
        val wideOut = VngDemosaicer.demosaic(wide,w,h,0,0)
        assertTrue(wideOut.pixels.minOrNull()!! < 0f); assertTrue(wideOut.pixels.maxOrNull()!! > 1f)
    }

    @Test fun reconstructionIsAffineEquivariantWithoutAnEightBitNoiseFloor() {
        val w = 25; val h = 23
        // Binary fractions make the linear transformations exact in the Float input, so a
        // changed direction decision cannot be explained by input quantization.
        val raw = FloatArray(w*h) { ((it*37 + (it/w)*19) % 61 - 15) / 64f }
        val original = VngDemosaicer.demosaic(raw,w,h,1,0)
        for ((scale,offset) in listOf(1f/4096 to 0f, 2f to -.5f, .5f to .25f)) {
            val transformed = VngDemosaicer.demosaic(FloatArray(raw.size) { raw[it]*scale+offset },w,h,1,0)
            for (i in original.pixels.indices)
                assertEquals(original.pixels[i]*scale+offset,transformed.pixels[i],max(1e-11f,abs(scale)*2e-7f))
        }
    }

    @Test fun rotationPreservesDirectionDecisionsAndEffectiveCfaWithoutShiftingTheImage() {
        val w = 21; val h = 19
        for (rx in 0..1) for (ry in 0..1) {
            val raw = FloatArray(w*h) { (((it%w)*13+(it/w)*7+it*it)%83)/64f-.25f }
            val output = VngDemosaicer.demosaic(raw,w,h,rx,ry)
            // Rotate 90 degrees clockwise, including the actual sensor CFA phase.
            val rotated = FloatArray(raw.size)
            for (y in 0 until h) for (x in 0 until w) rotated[x*h+h-1-y] = raw[y*w+x]
            val other = VngDemosaicer.demosaic(rotated,h,w,((h-1) and 1) xor ry,rx)
            for (y in 0 until h) for (x in 0 until w) for (c in 0..2)
                assertEquals("$rx,$ry at $x,$y/$c",output.pixels[(y*w+x)*3+c],other.pixels[(x*h+h-1-y)*3+c],2e-7f)
        }
    }

    @Test fun cancellationInterruptsInteriorWorkAndLeavesSourceUntouched() {
        val w = 97; val h = 99
        val src = FloatArray(w*h) { (it%101)/100f }; val copy = src.copyOf(); var checks = 0
        try {
            VngDemosaicer.demosaic(src,w,h,0,0) { if (++checks == 210) throw CancellationException("stop in VNG rows") }
            fail("Cancellation was not observed")
        } catch (_: CancellationException) { assertEquals(210,checks) }
        assertArrayEquals(copy,src,0f)
    }

    @Test fun scratchIsWidthBoundedAndInvalidDimensionsOrSamplesFailBeforeProcessing() {
        assertEquals(VngDemosaicer.extraBytes(1024,7),VngDemosaicer.extraBytes(1024,2048))
        assertEquals(1024L*3*7*8+4096,VngDemosaicer.extraBytes(1024,2048))
        assertTrue(VngDemosaicer.extraBytes(2048,2048) < 512*1024)
        assertEquals(4096L,VngDemosaicer.extraBytes(3,3))
        rejects { VngDemosaicer.extraBytes(Int.MAX_VALUE,Int.MAX_VALUE) }
        rejects { VngDemosaicer.demosaic(FloatArray(4),1,4,0,0) }
        rejects { VngDemosaicer.demosaic(FloatArray(3),2,2,0,0) }
        rejects { VngDemosaicer.demosaic(FloatArray(4),2,2,2,0) }
        for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))
            rejects { VngDemosaicer.demosaic(floatArrayOf(0f,1f,bad,0f),2,2,0,0) }
    }

    private fun bilinearAt(src: FloatArray,w: Int,h: Int,x: Int,y: Int,rx: Int,ry: Int) = DoubleArray(3) { c ->
        if (color(x,y,rx,ry) == c) src[y*w+x].toDouble() else {
            var sum = 0.0; var count = 0
            for (yy in max(0,y-1)..min(h-1,y+1)) for (xx in max(0,x-1)..min(w-1,x+1))
                if (color(xx,yy,rx,ry) == c) { sum += src[yy*w+xx]; count++ }
            sum/count
        }
    }
}
