package com.indigo.mobileobservatory.camera

enum class PhoneSkyResourceLimit { HEAP, CAMERA_BUFFERS, STORAGE }
class PhoneSkyResourceException(val limit: PhoneSkyResourceLimit) : IllegalStateException(when (limit) {
    PhoneSkyResourceLimit.HEAP -> "Not enough memory. Use fewer burst frames or another lens."
    PhoneSkyResourceLimit.CAMERA_BUFFERS -> "This RAW stream needs too much memory. Turn off RAW or choose another lens."
    PhoneSkyResourceLimit.STORAGE -> "Not enough storage. Free some space or use fewer frames."
})

data class PhoneHeapBudget(val maximumBytes: Long, val usedBytes: Long) {
    init { require(maximumBytes >= 0 && usedBytes >= 0) }
    val availableBytes get() = (maximumBytes - usedBytes).coerceAtLeast(0)
    /** Reserve room for the active app, bitmap/UI work and concurrent device callbacks. */
    val allocationAllowance get() = minOf(256L * 1024 * 1024,
        (availableBytes - 32L * 1024 * 1024).coerceAtLeast(0) / 4 * 3)
    companion object {
        fun current(): PhoneHeapBudget {
            val runtime = Runtime.getRuntime()
            return PhoneHeapBudget(runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory())
        }
    }
}

/** Overflow-safe admission estimates. They reject a request before allocating its outputs. */
object PhoneSkyResourceBudget {
    fun bytes(vararg factors: Long): Long {
        try { return factors.fold(1L) { value, factor -> require(factor >= 0); Math.multiplyExact(value, factor) } }
        catch (_: ArithmeticException) { throw PhoneSkyResourceException(PhoneSkyResourceLimit.HEAP) }
    }
    private fun sum(vararg values: Long): Long {
        try { return values.fold(0L, Math::addExact) }
        catch (_: ArithmeticException) { throw PhoneSkyResourceException(PhoneSkyResourceLimit.HEAP) }
    }
    fun requireHeap(allocationBytes: Long, budget: PhoneHeapBudget = PhoneHeapBudget.current()) {
        if (allocationBytes < 0 || allocationBytes > budget.allocationAllowance)
            throw PhoneSkyResourceException(PhoneSkyResourceLimit.HEAP)
    }
    fun frameBytes(width: Int, height: Int, bpp: Int): Long {
        require(width > 0 && height > 0 && bpp > 0)
        return bytes(width.toLong(), height.toLong(), bpp.toLong()).also {
            if (it > Int.MAX_VALUE - 8L) throw PhoneSkyResourceException(PhoneSkyResourceLimit.HEAP)
        }
    }
    fun requireCaptureHeap(plan: PhoneMonoReduction, bpp: Int, remainingFrames: Int,
                           budget: PhoneHeapBudget = PhoneHeapBudget.current()) {
        require(remainingFrames in 1..16)
        val frame = frameBytes(plan.outputWidth, plan.outputHeight, bpp)
        val pixels = bytes(plan.outputWidth.toLong(), plan.outputHeight.toLong())
        // Retained captures + one stack result + float extraction + FITS/preview scratch.
        requireHeap(sum(bytes(frame, remainingFrames.toLong() + 3), bytes(pixels, 4),
            bytes(plan.coveredWidth.toLong(), 8), bytes(plan.outputWidth.toLong(), 8)), budget)
    }
    fun requireNativeCapture(width: Int, height: Int, raw: Boolean, availablePhysicalBytes: Long) {
        // Two ImageReader slots plus conservative HAL working buffers. YUV includes chroma.
        val native = bytes(width.toLong(), height.toLong(), if (raw) 8 else 6)
        val reserved = maxOf(256L * 1024 * 1024, availablePhysicalBytes / 4)
        if (native > (availablePhysicalBytes - reserved).coerceAtLeast(0))
            throw PhoneSkyResourceException(PhoneSkyResourceLimit.CAMERA_BUFFERS)
    }
    fun requireStorage(availableBytes: Long, width: Int, height: Int, rawDngFrames: Int, outputBytes: Long) {
        require(rawDngFrames in 0..16)
        val dng = if (rawDngFrames == 0) 0L else bytes(sum(bytes(width.toLong(), height.toLong(), 2), 1_048_576), rawDngFrames.toLong())
        val needed = sum(dng, outputBytes, 64L * 1024 * 1024)
        if (needed > availableBytes.coerceAtLeast(0)) throw PhoneSkyResourceException(PhoneSkyResourceLimit.STORAGE)
    }
    fun requireStackOutput(width: Int, height: Int, bpp: Int, frames: Int, budget: PhoneHeapBudget = PhoneHeapBudget.current()) {
        require(frames in 1..16)
        requireHeap(sum(frameBytes(width, height, bpp), bytes(frames.toLong(), 128)), budget)
    }
}
