package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.*

class ShortExposureStackerTest {
    @Test
    fun stackRejectsIsolatedHotPixelAndPreservesPersistentStar() {
        val frames = List(8) { index ->
            val pixels = ByteArray(4) { 10 }
            pixels[1] = 100.toByte() // Same star in every short exposure.
            if (index == 3) pixels[2] = 255.toByte() // One-frame hot pixel.
            FrameData(pixels, 2, 2, PixelFormat.MONO8, index.toLong(), index.toLong())
        }

        val result = ShortExposureStacker.stack(frames)

        assertEquals(100, result.frame.data[1].toInt() and 0xFF)
        assertEquals(10, result.frame.data[2].toInt() and 0xFF)
        assertTrue(result.rejectedHotPixelSamples > 0)
    }

    @Test
    fun stackRequiresMatchingFrameGeometry() {
        val a = FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, 1, 1)
        val b = FrameData(ByteArray(6), 3, 2, PixelFormat.MONO8, 2, 2)
        try {
            ShortExposureStacker.stack(listOf(a, b))
            throw AssertionError("Expected an invalid burst to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun mirroredWarpCommutesWithImageReflectionForDisplacementAndRoll() {
        val source = patternedFrame()
        val reference = pose(SkyVector(0.0, 1.0, 0.0), 0.0)
        for (moved in listOf(pose(SkyVector(0.0, 1.0, 0.0), .38),
            pose(SkyVector(0.0, 0.0, 1.0), 1.7),
            pose(SkyVector(0.0, 1.0, 0.0), .38).apply(pose(SkyVector(0.0, 0.0, 1.0), 1.7)))) {
            val ordinary = ShortExposureStacker.warp(source, attitude(moved), attitude(reference), 60.0, 46.0)
            val reflected = ShortExposureStacker.warp(mirror(source), attitude(moved, true), attitude(reference, true), 60.0, 46.0)
            assertArrayEquals(mirror(ordinary).data, reflected.data)
            val wrongParity = ShortExposureStacker.warp(mirror(source), attitude(moved), attitude(reference), 60.0, 46.0)
            assertTrue(reflected.data.indices.count { reflected.data[it] != wrongParity.data[it] } > source.data.size / 10)
        }
    }

    @Test fun aCommonWorldRotationCancelsFromExtrinsicOpticalBurstAlignment() {
        val x = pose(SkyVector(1.0, -2.0, 1.0), 127.0)
        val imu0 = pose(SkyVector(0.0, 1.0, 0.0), 0.0)
        val imu1 = pose(SkyVector(0.0, 1.0, 0.0), .3)
        val a = pose(SkyVector(2.0, 1.0, -1.0), 111.0)
        val source = patternedFrame()
        val optical0 = imu0.apply(x); val optical1 = imu1.apply(x)
        val relative = ShortExposureStacker.warp(source, attitude(optical1, true), attitude(optical0, true), 60.0, 46.0)
        val world = ShortExposureStacker.warp(source, attitude(a.apply(optical1), true), attitude(a.apply(optical0), true), 60.0, 46.0)
        assertArrayEquals(relative.data, world.data)
    }

    @Test fun mirrorBurstWithoutCompleteTimedAxesUsesOnlyTheFirstPhotograph() {
        val first = patternedFrame(); val second = first.copy(data = ByteArray(first.data.size) { 255.toByte() })
        val axes = attitude(pose(SkyVector(0.0, 1.0, 0.0), 0.0), true)
        val missing = ShortExposureStacker.stack(listOf(first, second), listOf(axes, null), 60.0, 46.0)
        assertEquals(1, missing.inputFrameCount)
        assertArrayEquals(first.data, missing.frame.data)
        val unknownFov = ShortExposureStacker.stack(listOf(first, second), listOf(axes, axes), null, null)
        assertEquals(1, unknownFov.inputFrameCount)
        assertArrayEquals(first.data, unknownFov.frame.data)
    }

    @Test fun burstParityChangeIsRejected() {
        val frame = patternedFrame(); val p = pose(SkyVector(0.0, 1.0, 0.0), 0.0)
        try {
            ShortExposureStacker.stack(listOf(frame, frame), listOf(attitude(p), attitude(p, true)), 60.0, 46.0)
            throw AssertionError("Mixed burst parity was accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun streamedWarpStackMatchesTheMaterializedPixelMeanAndTrimmedMeanExactly() {
        for (format in listOf(PixelFormat.MONO8, PixelFormat.MONO16)) for (count in listOf(2, 4, 8)) {
            val reference = pose(SkyVector(0.0, 1.0, 0.0), 0.0)
            val frames = (0 until count).map { index ->
                val base = patternedFrame()
                val data = if (format == PixelFormat.MONO8) ByteArray(base.data.size) { p -> ((base.data[p].toInt() and 255) + index * 11).coerceAtMost(255).toByte() }
                    else ByteArray(base.data.size * 2) { p ->
                        val value = (base.data[p / 2].toInt() and 255) * 201 + index * 31
                        if (p % 2 == 0) value.toByte() else (value ushr 8).toByte()
                    }
                base.copy(data = data, pixelFormat = format)
            }
            val axes = frames.indices.map { index -> attitude(pose(SkyVector(0.0, 1.0, 0.0), index * .04)
                .apply(pose(SkyVector(0.0, 0.0, 1.0), index * .15)), true) }
            val materialized = frames.indices.map { index -> if (index == 0) frames[index] else
                ShortExposureStacker.warp(frames[index], axes[index], attitude(reference, true), 60.0, 46.0) }
            val expected = ShortExposureStacker.stack(materialized)
            val streamed = ShortExposureStacker.stack(frames, axes, 60.0, 46.0)
            assertArrayEquals(expected.frame.data, streamed.frame.data)
            assertEquals(count, streamed.inputFrameCount)
            assertEquals(expected.rejectedHotPixelSamples, streamed.rejectedHotPixelSamples)
        }
    }

    private fun patternedFrame(): FrameData {
        val width = 121; val height = 93
        val pixels = ByteArray(width * height) { index ->
            val x = index % width; val y = index / width
            ((x * 7 + y * 11 + (x * y) % 73) % 240 + 10).toByte()
        }
        return FrameData(pixels, width, height, PixelFormat.MONO8, 1, 1)
    }

    private fun mirror(frame: FrameData): FrameData = frame.copy(data = ByteArray(frame.data.size) { index ->
        frame.data[(index / frame.width) * frame.width + frame.width - 1 - index % frame.width]
    })

    private fun attitude(p: SkyPose, mirrored: Boolean = false) = BurstAttitude(
        Direction3(p.forward.x, p.forward.y, p.forward.z), Direction3(p.up.x, p.up.y, p.up.z), mirrored)

    private fun pose(axis: SkyVector, degrees: Double): SkyPose {
        val n = axis.unit(); val angle = Math.toRadians(degrees)
        fun rotate(v: SkyVector) = (v * cos(angle) + n.cross(v) * sin(angle) + n * (n.dot(v) * (1-cos(angle)))).unit()
        return SkyPose(rotate(SkyVector(1.0, 0.0, 0.0)), rotate(SkyVector(0.0, 1.0, 0.0)), rotate(SkyVector(0.0, 0.0, 1.0)))
    }
}
