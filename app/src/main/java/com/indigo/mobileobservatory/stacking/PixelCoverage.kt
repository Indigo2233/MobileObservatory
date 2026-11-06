package com.indigo.mobileobservatory.stacking

import java.util.Arrays

/** Read-only accepted-frame counts shared by result models. */
interface PixelWeights {
    val size: Int
    operator fun get(index: Int): Float
    fun countAt(index: Int): Int = get(index).toInt()
    val minimum: Int
    val maximum: Int
    fun toFloatArray(): FloatArray = FloatArray(size) { get(it) }
    val encoding: String get() = "float32"
}

class DensePixelWeights(private val values: FloatArray) : PixelWeights {
    override val size: Int get() = values.size
    override fun get(index: Int): Float = values[index]
    override val minimum: Int get() = values.minOfOrNull { it.toInt() } ?: 0
    override val maximum: Int get() = values.maxOfOrNull { it.toInt() } ?: 0
    fun values(): FloatArray = values
}

/** Immutable per-pixel accepted-frame counts. Runs keep the ordinary shifted-star-field result
 * compact while preserving exact UInt16 values and O(log runs) random access. */
class PixelCoverage private constructor(
    private val dense: ShortArray?,
    internal val starts: IntArray?,
    internal val counts: ShortArray?,
    override val size: Int,
    override val minimum: Int,
    override val maximum: Int
) : PixelWeights {
    override val encoding: String get() = "uint16_runs_v1"
    init {
        require(size > 0)
        require((dense != null && starts == null && counts == null) ||
            (dense == null && starts != null && counts != null &&
                starts.size == counts.size && starts.isNotEmpty() &&
                starts.first() == 0 && starts.last() < size))
    }

    val storageBytes: Long get() = if (dense != null) dense.size * 2L
        else starts!!.size * 4L + counts!!.size * 2L
    override fun toString(): String = "PixelCoverage(size=$size, encoding=${if (dense != null) "dense" else "runs"}, " +
        "storedBytes=$storageBytes, minimum=$minimum, maximum=$maximum)"

    override fun get(index: Int): Float = countAt(index).toFloat()

    fun count(index: Int): Int {
        require(index in 0 until size)
        val packed = dense
        if (packed != null) return packed[index].toInt() and 0xffff
        val startsValue = starts!!
        val found = Arrays.binarySearch(startsValue, index)
        val run = if (found >= 0) found else -found - 2
        return counts!![run].toInt() and 0xffff
    }

    override fun countAt(index: Int): Int = count(index)

    companion object {
        fun pack(values: ShortArray): PixelCoverage {
            require(values.isNotEmpty())
            var runs = 1
            var minimum = Int.MAX_VALUE
            var maximum = Int.MIN_VALUE
            for (i in values.indices) {
                val value = values[i].toInt() and 0xffff
                if (i > 0 && value != (values[i - 1].toInt() and 0xffff)) runs++
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
            }
            val starts = IntArray(runs)
            val counts = ShortArray(runs)
            var run = 0
            starts[0] = 0
            for (i in values.indices) {
                if (i > 0 && values[i] != values[i - 1]) starts[++run] = i
                counts[run] = values[i]
            }
            // A dense UInt16 plane is smaller than runs when the counts change often. This
            // bound keeps finalization at no more than 2 bytes per pixel.
            return if (runs * 6L >= values.size * 2L)
                PixelCoverage(values, null, null, values.size, minimum, maximum)
            else PixelCoverage(null, starts, counts, values.size, minimum, maximum)
        }

        fun fromInts(values: IntArray): PixelCoverage {
            require(values.isNotEmpty() && values.all { it in 0..65535 })
            return pack(ShortArray(values.size) { values[it].toShort() })
        }
    }
}

/** Mutable UInt16 storage used only while accumulation is active. */
internal class CoverageBuffer(val values: ShortArray) {
    init {
        require(values.isNotEmpty())
    }

    operator fun get(index: Int): Int = values[index].toInt() and 0xffff

    fun increment(index: Int) {
        val current = get(index)
        check(current < 65535) { "coverage_overflow" }
        values[index] = (current + 1).toShort()
    }

    /** Keeps ownership of the native-backed count array; callers must not mutate it again. */
    fun take(): PixelCoverage = PixelCoverage.pack(values)

    /** Copies live counts because the engine may continue accumulating after publishing. */
    fun freezeSnapshot(): PixelCoverage = PixelCoverage.pack(values.copyOf())
}
