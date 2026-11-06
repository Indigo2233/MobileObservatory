package com.indigo.mobileobservatory.stacking

/** Native Float32 mean/M2 plus UInt16 coverage. The array transfer is once per accepted frame. */
internal class NativeStackAccumulator private constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
    val channels: Int
) : AutoCloseable {

    /** Returns the number of rejected pixels, or throws if the native pass failed. */
    fun accumulate(image: LinearImage, transform: RigidTransform, config: DeepSkyConfig,
                   noiseFloor: Double, cancelled: StackCancelCheck): Int {
        require(image.width == width && image.height == height && image.channels == channels)
        val rejected = StackAccumulatorNative.accumulate(handle, image.pixels, width, height, channels,
            transform.cosine, transform.sine, transform.dx, transform.dy,
            config.sigmaThreshold, noiseFloor, config.warmupSamples, cancelled)
        if (rejected == -2) throw CoverageLimitException()
        if (rejected == -3) throw java.util.concurrent.CancellationException("Stack generation discarded")
        check(rejected >= 0) { "native_accumulator_accumulate" }
        return rejected
    }

    /** Copies the current image and count runs. Variance is released only after the final pass. */
    fun finish(releaseVariance: Boolean = false): DeepSkyAccumulatorResult {
        val pixels = width * height
        if (releaseVariance) StackAccumulatorNative.releaseM2(handle)
        val output = FloatArray(pixels * channels)
        val counts = ShortArray(pixels)
        readRows(0, height, output, null, counts)
        return DeepSkyAccumulatorResult(output, PixelCoverage.pack(counts))
    }

    /** Copies one contiguous output band, optionally including M2 and UInt16 coverage. */
    fun readRows(firstRow: Int, rows: Int, mean: FloatArray, m2: FloatArray?, coverage: ShortArray) {
        require(firstRow >= 0 && rows > 0 && firstRow + rows <= height)
        require(m2 == null || m2.size >= width * rows * channels)
        require(mean.size >= width * rows * channels && coverage.size >= width * rows)
        check(StackAccumulatorNative.readRows(handle, firstRow, rows, mean, m2, coverage) >= 0) {
            "native_accumulator_read"
        }
    }

    override fun close() {
        if (handle != 0L) {
            StackAccumulatorNative.release(handle)
            handle = 0L
        }
    }

    companion object {
        private val loaded: Boolean = try {
            System.loadLibrary("stack_accumulator_jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

        fun create(width: Int, height: Int, channels: Int): NativeStackAccumulator? =
            if (!loaded) null else StackAccumulatorNative.create(width, height, channels).takeIf { it != 0L }?.let {
                NativeStackAccumulator(it, width, height, channels)
            }
    }
}

internal object StackAccumulatorNative {
    external fun create(width: Int, height: Int, channels: Int): Long
    external fun accumulate(handle: Long, pixels: FloatArray, width: Int, height: Int,
        channels: Int, cosine: Double, sine: Double, dx: Double, dy: Double,
        sigmaThreshold: Double, noiseFloor: Double, warmupSamples: Int,
        cancelled: StackCancelCheck): Int
    external fun readRows(handle: Long, firstRow: Int, rowCount: Int,
        mean: FloatArray, m2: FloatArray?, coverage: ShortArray): Long
    external fun release(handle: Long)
    external fun releaseM2(handle: Long)
}

internal class CoverageLimitException : IllegalStateException("coverage_limit")

internal fun interface StackCancelCheck {
    fun isCancelled(): Boolean
}

internal data class DeepSkyAccumulatorResult(val pixels: FloatArray, val coverage: PixelCoverage)
