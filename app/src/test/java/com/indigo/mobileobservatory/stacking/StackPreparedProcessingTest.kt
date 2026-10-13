package com.indigo.mobileobservatory.stacking

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class StackPreparedProcessingTest {
    private fun image(channels: Int): LinearImage {
        val w = 160; val h = 128
        return LinearImage(w,h,channels,FloatArray(w*h*channels) { i ->
            val x = (i/channels)%w; val y = (i/channels)/w; val c = i%channels
            (.04+.0002*x+.00015*y+.01*c+.0006*sin(x*1.5+y*.7)+
                .35*exp(-((x-78.3).pow(2)+(y-60.7).pow(2))/32)).toFloat()
        })
    }
    private fun recipes(): List<StackProcessingParameters> {
        val manual = BackgroundParameters(mode=BackgroundMode.MANUAL,samplePoints=(0..3).flatMap { y ->
            (0..3).map { x -> BackgroundSamplePoint(.12+x*.25,.12+y*.25) } })
        return listOf(StackProcessingParameters(background=BackgroundParameters(enabled=false)),
            StackProcessingParameters(autoWhiteBalance=true),
            StackProcessingParameters(sharpening=.7,localContrast=.2,saturation=1.7,redBalance=1.2,blueBalance=.8),
            StackProcessingParameters(background=manual,whiteBalanceEnabled=false,stretchEnabled=false,saturation=.7),
            StackProcessingParameters.preset(ProcessingPreset.RAW))
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hash(pixels: FloatArray): String {
        val b = ByteBuffer.allocate(pixels.size*4); pixels.forEach { b.putInt(it.toRawBits()) }; return hash(b.array())
    }

    @Test fun outputAndBackgroundMetadataMatchIndependentPreOptimizationB081540Baseline() {
        // Generated before changing production code, in a separate JVM using the b081540
        // compiled app classes. Hashes cover every Float bit and the complete background model.
        val pixels = listOf(
            "8564ede24a474edd39ae1c36ffd62c15fe4b66dcc94d52a37212837ca0fe2d87",
            "1df7430b0df899ded19a0e7d27667b38feb9446f83158b612d6406042103eb69",
            "f163388f1b08be9c0583222eeb0ad5631c60f19feaf89795decd7a45d2579442",
            "3d1f85561e7255769076b88f6bdf0ff6ff40900d001f4e9a643251b02a3307a4",
            "72d2b561e0dbb92922c14ec97b97f8644294167ec767b00b1f36abaee030756e",
            "7b6fc08ca648e1492c679d1db83b92d193e7f732ed93c26bb8c388ff60b9712b",
            "93aa2162e2a25c563cac7599d89fffe757824d83e761420331cb02507af3d2c7",
            "ec1aba569ead44424826ce127cc6d5f3e4e676fe82993a9c15e8ac1ece81e8b8",
            "0c880edbb1be8a967aaad5323003cdfdee0429500b7106ec8118f183d16d11b1",
            "33f6099a22ea9109f4ec069467322141a42aae7fa43e94f3ddaff962f34b1d8d")
        val none = "74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b"
        val mono = "64451627344f81198fa6f319c98a35cd60f0a0229e15f74cc72dccc1ac97bd90"
        val rgb = "8a43328cf75c946193e43f5a5fc1028752ef127b4d49c5312fcba2aabe09c093"
        val models = listOf(none,mono,mono,"cfc945e9cb1f1d351bccd1461ba8b05fbce8c4e7cc278482fa46adec0f660075",none,
            none,rgb,rgb,"696519dd204bbf8bbb57dc05136681b7d5df4ccebdbaef4e7d6b8d74202168bc",none)
        var case = 0
        for (channels in listOf(1,3)) for (p in recipes()) {
            val result = StackPostProcessor.process(image(channels),p)
            assertEquals("Pixels case $case",pixels[case],hash(result.image.pixels))
            assertEquals("Background case $case",models[case],hash(result.background.toString().toByteArray()))
            case++
        }
    }

    @Test fun noFilterPathHasNoLuminanceOrNoiseScratchAndRowsPreserveFullResolutionHalos() {
        for (channels in listOf(1,3)) {
            val source = image(channels)
            for (p in recipes()) {
                val expected = StackPostProcessor.process(source,p).image
                val prepared = StackPostProcessor.prepare(source,p)
                val filters = p.preset != ProcessingPreset.RAW && (p.sharpening > 0 || p.localContrast > 0)
                assertEquals(filters,prepared.noiseEstimated)
                assertEquals(if (filters) 3 else 0,prepared.workingPlaneCount)
                for (step in listOf(1,2,7)) {
                    val count = (source.width+step-1)/step
                    val row = FloatArray(count*channels)
                    for (y in 0 until source.height step step) {
                        prepared.writeRow(y,row,step)
                        for (x in 0 until count) for (c in 0 until channels)
                            assertEquals(expected.pixels[(y*source.width+x*step)*channels+c].toRawBits(),row[x*channels+c].toRawBits())
                    }
                }
            }
        }
    }

    @Test fun cacheFitsOnceAcrossToneChangesButInvalidatesOnIdentityAndBackgroundParameters() {
        val source = image(3); val cache = BackgroundModelCache()
        val p = StackProcessingParameters()
        val first = StackPostProcessor.prepare(source,p,cache)
        assertEquals(1L,cache.fitCount)
        repeat(6) { n ->
            val next = StackPostProcessor.prepare(source,p.copy(brightness=1.0+n*.1,midtone=.2+n*.1),cache)
            assertSame(first.background,next.background)
        }
        assertEquals(1L,cache.fitCount)
        StackPostProcessor.prepare(source.deepCopy(),p,cache)
        assertEquals(2L,cache.fitCount)
        val masks = mutableListOf<TargetMask>()
        val bg = BackgroundParameters(targetMasks=masks)
        cache.model(source,bg)
        assertEquals(3L,cache.fitCount)
        masks += TargetMask(.2,.2,.3,.3)
        cache.model(source,bg)
        assertEquals(4L,cache.fitCount)
        cache.model(source,bg.copy(strength=.6))
        assertEquals(5L,cache.fitCount)
        cache.clear(); cache.model(source,bg.copy(strength=.6))
        assertEquals(6L,cache.fitCount)
    }

    @Test fun clearingDuringFitDoesNotResurrectDiscardedCacheAndCancelledFitsAreNotStored() {
        val source = image(1); val cache = BackgroundModelCache(); var calls = 0
        val prior = cache.model(source,BackgroundParameters()) {
            if (++calls == 2) cache.clear()
            false
        }
        assertEquals(1L,cache.fitCount)
        assertNotSame(prior,cache.model(source,BackgroundParameters()))
        assertEquals(2L,cache.fitCount)
        cache.clear()
        try { cache.model(source,BackgroundParameters()) { true }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(2L,cache.fitCount)
        cache.model(source,BackgroundParameters()); assertEquals(3L,cache.fitCount)
    }
}
