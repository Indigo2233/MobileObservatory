package com.indigo.mobileobservatory.stacking

/** The coordinator joins the old renderer before stacking/export, and closes the source before
 * starting a new preview. Those phases share the result, not their temporary workspaces. */
internal data class OfflineDeepSkyMemory(
    val stackingBytes: Long,
    val previewBytes: Long,
    val exportBytes: Long,
    val accumulatorBytes: Long = 0
) {
    val peakBytes: Long get() = maxOf(stackingBytes, previewBytes, exportBytes)

    companion object {
        const val PREVIEW_MAX_SIDE = 1280
        private const val HEADROOM = 8L * 1024 * 1024
        private const val PREVIEW_BITMAP_BYTES = PREVIEW_MAX_SIDE * PREVIEW_MAX_SIDE * 4L

        fun estimate(width: Int, height: Int, channels: Int, reusableInput: Boolean,
                     decoderWorkingBytes: Long = 0, retainedCalibrationBytes: Long = 0): OfflineDeepSkyMemory {
            require(width >= 0 && height >= 0 && channels in listOf(1, 3))
            require(decoderWorkingBytes >= 0 && retainedCalibrationBytes >= 0)
            val pixels = width.toLong() * height
            fun planes(bytesPerPixel: Long) = Math.multiplyExact(pixels, bytesPerPixel)
            val shared = Math.addExact(retainedCalibrationBytes, HEADROOM)
            // The input frame remains a Java allocation. Mean and M2 live in native Float32
            // storage while coverage uses UInt16. The Java renderer also needs bounded output;
            // native storage is reported separately from Java heap admission.
            val accumulator = planes(channels * 8L + 2L)
            // Finalization copies the native mean and UInt16 coverage into one Java image plus
            // a compact count buffer. The input buffer is released before this copy.
            val result = planes(channels * 4L + 2L)
            val stacking = Math.addExact(Math.addExact(shared, decoderWorkingBytes),
                maxOf(planes(if (reusableInput) 4L else 8L), result))
            // Worst supported recipe: luma, detail blur, contrast blur and a separable-blur
            // temporary during preparation; only three planes survive into Bitmap rendering.
            // An old preview can have a different aspect ratio, so reserve its maximum size.
            val step = maxOf(1L, (maxOf(width, height).toLong() + PREVIEW_MAX_SIDE - 1) / PREVIEW_MAX_SIDE)
            val previewWidth = (width + step - 1) / step
            val previewHeight = (height + step - 1) / step
            val previewBitmap = previewWidth * previewHeight * 4
            val preview = shared + result + maxOf(planes(16), planes(12) + previewBitmap) +
                PREVIEW_BITMAP_BYTES + previewWidth * (channels * 4L + 4)
            // Preparing filters needs four mono planes. PNG rendering instead retains three
            // plus its full-size ARGB Bitmap (also four bytes/pixel). FITS writes scalars.
            // The previous bounded preview and one Float/ARGB row remain while exporting.
            val export = shared + result + maxOf(planes(16), planes(12) + planes(4)) +
                PREVIEW_BITMAP_BYTES + width * (channels * 4L + 4)
            return OfflineDeepSkyMemory(stacking, preview, export, accumulator)
        }
    }
}
