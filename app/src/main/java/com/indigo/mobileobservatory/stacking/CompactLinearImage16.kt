package com.indigo.mobileobservatory.stacking

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Worker-owned UInt16 samples with a signed linear range per block/channel. No display transform. */
internal class CompactLinearImage16(
    override val width: Int,
    override val height: Int,
    override val channels: Int
) : LinearSamples {
    private val columns: Int
    private val values: ShortArray
    private val offsets: FloatArray
    private val steps: DoubleArray
    override val encoding get() = ENCODING
    override var maximumQuantizationError = 0.0
        private set
    override val storageBytes get() = values.size * 2L + offsets.size * 4L + steps.size * 8L

    init {
        require(width > 0 && height > 0 && width.toLong() * height <= LinearImage.MAX_PIXELS)
        require(channels == 1 || channels == 3)
        columns = (width + BLOCK_SIDE - 1) / BLOCK_SIDE
        val blocks = columns * ((height + BLOCK_SIDE - 1) / BLOCK_SIDE) * channels
        values = ShortArray(width * height * channels)
        offsets = FloatArray(blocks)
        steps = DoubleArray(blocks)
    }

    fun beginFrame() {
        maximumQuantizationError = 0.0
    }

    /** One bounded decoded/bin-averaged tile is encoded immediately, including short edge blocks. */
    fun writeBlock(left: Int, top: Int, blockWidth: Int, blockHeight: Int, source: FloatArray,
                   cancelCheck: () -> Unit = {}) {
        require(left >= 0 && top >= 0 && left % BLOCK_SIDE == 0 && top % BLOCK_SIDE == 0)
        require(blockWidth == min(BLOCK_SIDE, width - left) && blockWidth > 0 &&
            blockHeight == min(BLOCK_SIDE, height - top) && blockHeight > 0)
        require(source.size >= blockWidth * blockHeight * channels)
        val metadata = (top / BLOCK_SIDE * columns + left / BLOCK_SIDE) * channels
        for (channel in 0 until channels) {
            cancelCheck()
            var low = Float.POSITIVE_INFINITY
            var high = Float.NEGATIVE_INFINITY
            for (y in 0 until blockHeight) {
                cancelCheck()
                for (x in 0 until blockWidth) {
                    val value = source[(y * blockWidth + x) * channels + channel]
                    require(value.isFinite()) { "INVALID_PIXELS" }
                    low = min(low, value); high = max(high, value)
                }
            }
            val step = (high.toDouble() - low.toDouble()) / 65535.0
            offsets[metadata + channel] = low
            steps[metadata + channel] = step
            if (step > 0) {
                val floatRounding = Math.ulp(max(abs(low), abs(high))).toDouble() / 2
                maximumQuantizationError = max(maximumQuantizationError, step / 2 + floatRounding)
            }
            for (y in 0 until blockHeight) {
                cancelCheck()
                for (x in 0 until blockWidth) {
                    val value = source[(y * blockWidth + x) * channels + channel]
                    val code = if (step == 0.0) 0 else
                        ((value.toDouble() - low.toDouble()) / step).roundToInt().coerceIn(0, 65535)
                    values[((top + y) * width + left + x) * channels + channel] = code.toShort()
                }
            }
        }
    }

    override fun sample(index: Int): Float {
        val pixel = index / channels
        val metadata = ((pixel / width / BLOCK_SIDE) * columns + pixel % width / BLOCK_SIDE) * channels
        return read(index, metadata + index % channels)
    }

    override fun luminanceSample(pixel: Int): Double {
        val metadata = ((pixel / width / BLOCK_SIDE) * columns + pixel % width / BLOCK_SIDE) * channels
        val i = pixel * channels
        return if (channels == 1) read(i, metadata).toDouble() else
            read(i, metadata) * 0.2126 + read(i + 1, metadata + 1) * 0.7152 + read(i + 2, metadata + 2) * 0.0722
    }

    override fun sampleBilinear(x0: Int, y0: Int, x1: Int, y1: Int, ax: Double, ay: Double, result: FloatArray) {
        val a = (y0 * width + x0) * channels
        val b = (y0 * width + x1) * channels
        val c = (y1 * width + x0) * channels
        val d = (y1 * width + x1) * channels
        val ma = (y0 / BLOCK_SIDE * columns + x0 / BLOCK_SIDE) * channels
        val mb = (y0 / BLOCK_SIDE * columns + x1 / BLOCK_SIDE) * channels
        val mc = (y1 / BLOCK_SIDE * columns + x0 / BLOCK_SIDE) * channels
        val md = (y1 / BLOCK_SIDE * columns + x1 / BLOCK_SIDE) * channels
        for (channel in 0 until channels) {
            result[channel] =
                ((read(a + channel, ma + channel) * (1 - ax) + read(b + channel, mb + channel) * ax) * (1 - ay) +
                    (read(c + channel, mc + channel) * (1 - ax) + read(d + channel, md + channel) * ax) * ay).toFloat()
        }
    }

    override fun allFinite(): Boolean = offsets.indices.all {
        offsets[it].isFinite() && steps[it].isFinite() && steps[it] >= 0 &&
            (offsets[it].toDouble() + 65535 * steps[it]).toFloat().isFinite()
    }

    private fun read(index: Int, metadata: Int): Float =
        (offsets[metadata].toDouble() + (values[index].toInt() and 65535) * steps[metadata]).toFloat()

    companion object {
        const val BLOCK_SIDE = OfflineRegionPlan.TILE
        const val ENCODING = "uint16_block_affine_v1"

        fun storageBytes(width: Int, height: Int, channels: Int): Long {
            val columns = (width.toLong() + BLOCK_SIDE - 1) / BLOCK_SIDE
            val rows = (height.toLong() + BLOCK_SIDE - 1) / BLOCK_SIDE
            return width.toLong() * height * channels * 2 + columns * rows * channels * 12
        }
    }
}
