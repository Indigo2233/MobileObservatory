package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException
import kotlin.math.pow

/** Rust-owned Double sums and UInt16 coverage for one planetary stack pass. */
internal class NativePlanetaryAccumulator private constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
    val channels: Int
) : AutoCloseable {
    fun accumulate(image: LinearImage, warp: PlanetaryWarp, cancelled: StackCancelCheck) {
        require(image.width == width && image.height == height && image.channels == channels)
        val status = NativePlanetaryAccumulatorBridge.accumulate(
            handle, image.pixels, warp.dx, warp.dy, warp.step, cancelled
        )
        when (status) {
            0 -> Unit
            -2 -> throw IllegalStateException("coverage_overflow")
            -3 -> throw CancellationException("Planetary stack discarded")
            else -> error("native_planetary_accumulate")
        }
    }

    fun finish(output: FloatArray, cancelled: StackCancelCheck): PixelCoverage {
        require(output.size >= width * height * channels)
        val counts = ShortArray(width * height)
        when (val status = NativePlanetaryAccumulatorBridge.finish(handle, output, counts, cancelled)) {
            -3L -> throw CancellationException("Planetary stack discarded")
            in 0L..Long.MAX_VALUE -> Unit
            else -> error("native_planetary_finish:$status")
        }
        return PixelCoverage.pack(counts)
    }

    override fun close() {
        if (handle != 0L) {
            NativePlanetaryAccumulatorBridge.release(handle)
            handle = 0L
        }
    }

    companion object {
        fun create(width: Int, height: Int, channels: Int): NativePlanetaryAccumulator? {
            if (!NativePlanetaryLibraries.loaded) return null
            val handle = NativePlanetaryAccumulatorBridge.create(width, height, channels)
            return handle.takeIf { it != 0L }?.let {
                NativePlanetaryAccumulator(it, width, height, channels)
            }
        }
    }
}

internal object NativePlanetaryWarpBuilder {
    /**
     * Returns null when native support is unavailable, otherwise the folded-mesh state.
     * [points] stores x, y, relative dx, relative dy, correlation^4 and support radius.
     */
    fun buildWarp(
        width: Int,
        height: Int,
        step: Int,
        alignment: PlanetaryAlignment,
        dx: FloatArray,
        dy: FloatArray,
        cancelled: StackCancelCheck
    ): Boolean? {
        if (!NativePlanetaryLibraries.loaded) return null
        val points = DoubleArray(alignment.local.size * 6)
        alignment.local.forEachIndexed { index, local ->
            val offset = index * 6
            points[offset] = local.point.x
            points[offset + 1] = local.point.y
            points[offset + 2] = local.shift.dx - alignment.global.dx
            points[offset + 3] = local.shift.dy - alignment.global.dy
            points[offset + 4] = local.shift.correlation.pow(4)
            points[offset + 5] = local.point.radius * 3.0
        }
        return when (NativePlanetaryWarpBuilderBridge.build(
            width, height, step, alignment.global.dx, alignment.global.dy,
            points, dx, dy, cancelled
        )) {
            0 -> false
            1 -> true
            -3 -> throw CancellationException("Planetary stack discarded")
            else -> null
        }
    }
}

private object NativePlanetaryLibraries {
    val loaded: Boolean = try {
        System.loadLibrary("planetary_stack_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }
}

private object NativePlanetaryAccumulatorBridge {
    external fun create(width: Int, height: Int, channels: Int): Long
    external fun accumulate(
        handle: Long,
        frame: FloatArray,
        dx: FloatArray,
        dy: FloatArray,
        step: Int,
        cancelled: StackCancelCheck
    ): Int
    external fun finish(
        handle: Long,
        output: FloatArray,
        coverage: ShortArray,
        cancelled: StackCancelCheck
    ): Long
    external fun release(handle: Long)
}

private object NativePlanetaryWarpBuilderBridge {
    external fun build(
        width: Int,
        height: Int,
        step: Int,
        globalDx: Double,
        globalDy: Double,
        points: DoubleArray,
        dx: FloatArray,
        dy: FloatArray,
        cancelled: StackCancelCheck
    ): Int
}
