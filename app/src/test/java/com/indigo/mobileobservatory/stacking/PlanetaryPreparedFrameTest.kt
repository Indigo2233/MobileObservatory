package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class PlanetaryPreparedFrameTest {
    private val w = 80; private val h = 72
    private val roi = PixelRoi(2,2,w-4,h-4)
    private fun scene(channels: Int,shift: Double = 0.0): LinearImage = LinearImage(w,h,channels,
        FloatArray(w*h*channels) { i ->
            val x = (i/channels)%w-shift; val y = (i/channels)/w.toDouble()+shift*.3
            val limb = .5*(1-tanh((hypot(x-39.5,y-35.5)-25)/.9))
            (.012+limb*(.4+.07*sin(y*.45)+.02*cos(x*.41))+i%channels*.011).toFloat()
        })

    @Test fun sharedSnapshotKeepsAlignmentAndResidualBitExactAndSkipsOnePlaneTraversal() {
        for (channels in listOf(1,3)) for (local in listOf(false,true)) {
            var checks = 0
            val reg = PlanetaryRegistration(scene(channels),roi,
                PlanetaryOptions(localAlignment=local,maxGlobalShift=8,alignmentPatchSize=16)) { checks++ }
            val source = scene(channels,1.3)
            checks = 0
            val prior = reg.align(source)
            val warp = PlanetaryWarp(w,h,prior)
            val priorResidual = reg.edgeResidual(source,warp)
            val legacyChecks = checks
            checks = 0
            val prepared = reg.prepareFrame(source)
            val actual = reg.align(prepared)
            val residual = reg.edgeResidual(prepared,warp)
            assertEquals(prior,actual)
            assertEquals(priorResidual.toRawBits(),residual.toRawBits())
            assertEquals("One cancellation check per row of the removed luminance traversal",h,legacyChecks-checks)
            assertEquals(w*h,prepared.pixels.size)
        }
    }

    @Test fun preparedDataIsOwnedAndDoesNotRetainOrReuseMutatedSourceStorage() {
        val reg = PlanetaryRegistration(scene(3),roi,PlanetaryOptions(maxGlobalShift=8))
        val source = scene(3,1.3)
        val prepared = reg.prepareFrame(source)
        val expected = reg.align(prepared)
        val original = prepared.pixels.copyOf()
        source.pixels.fill(0f)
        assertArrayEquals(original,prepared.pixels,0f)
        assertEquals(expected,reg.align(prepared))
        assertTrue(reg.prepareFrame(source).pixels.all { it == 0f })
        val other = PlanetaryRegistration(scene(3),roi,PlanetaryOptions(maxGlobalShift=8))
        try { other.align(prepared); fail("Wrong registration accepted prepared frame") } catch (_: IllegalArgumentException) { }
    }

    @Test fun preparationAndSharedAlignmentRemainCancellable() {
        var cancel = false
        val reg = PlanetaryRegistration(scene(1),roi,PlanetaryOptions(maxGlobalShift=8)) {
            if (cancel) throw CancellationException("cancel")
        }
        val prepared = reg.prepareFrame(scene(1,1.0))
        cancel = true
        try { reg.prepareFrame(scene(1)); fail("Expected cancellation") } catch (_: CancellationException) { }
        try { reg.align(prepared); fail("Expected cancellation") } catch (_: CancellationException) { }
    }
}
