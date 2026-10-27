package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat

/** PHD2 QuickLRecon's unsigned, sliding 2x2 mean. The image dimensions remain unchanged.
 * Only requested pixels are reconstructed. Local photometry reuses a bounded patch.
 * Craig Stark (2006–2010), BSD; distributed in assets/THIRD_PARTY_PHD2.txt. */
internal class GuideImage(private val frame: FrameData) {
    val width = frame.width
    val height = frame.height
    val bayer = frame.pixelFormat.isBayer
    val bits = frame.pixelFormat.nativeBits
    val sensorOffset = if (bayer) .5 else 0.0
    private val data = frame.data
    private val stride = frame.pixelFormat.bytesPerPixel
    private var patch = IntArray(0)
    private var patchX = 0
    private var patchY = 0
    private var patchWidth = 0
    private var patchHeight = 0
    internal val cachedPixels get() = patch.size

    /** Keep image coordinates and edge rules intact while caching only a star's search/sky area. */
    fun beginMeasurement(baseX: Int, baseY: Int, search: Int) {
        patchWidth = 0
        if (!bayer) return
        val radius = search + 12
        patchX = maxOf(baseX - radius, 0)
        patchY = maxOf(baseY - radius, 0)
        val columns = minOf(baseX + radius, width - 1) - patchX + 1
        val rows = minOf(baseY + radius, height - 1) - patchY + 1
        // Unusually large searches can use direct reads without growing the cache indefinitely.
        val area = columns.toLong() * rows
        if (columns <= 0 || rows <= 0 || area > 4096) return
        if (patch.size < area) patch = IntArray(area.toInt())
        patch.fill(-1, 0, area.toInt())
        patchWidth = columns
        patchHeight = rows
    }

    fun value(x: Int, y: Int): Int {
        if (!bayer) return GuidePixelAccess.raw(frame, x, y)
        val localX = x - patchX
        val localY = y - patchY
        if (localX !in 0 until patchWidth || localY !in 0 until patchHeight) return reconstruct(x, y)
        val index = localY * patchWidth + localX
        val cached = patch[index]
        if (cached >= 0) return cached
        return reconstruct(x, y).also { patch[index] = it }
    }

    private fun reconstruct(x: Int, y: Int): Int {
        val offset = (y * width + x) * stride
        var sum = scalar(offset)
        if (x < width - 1) sum += scalar(offset + stride)
        if (y < height - 1) {
            val nextRow = offset + width * stride
            sum += scalar(nextRow)
            if (x < width - 1) sum += scalar(nextRow + stride)
        }
        return sum ushr ((if (x < width - 1) 1 else 0) + (if (y < height - 1) 1 else 0))
    }

    private fun scalar(offset: Int): Int = if (stride == 1) data[offset].toInt() and 255 else
        (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
}

/** Conservative RAW prefilter for full 2x2 QLR windows. If the average exceeds threshold,
 * at least one sample exceeds its phase background plus the same background-relative excess.
 * Only two RAW signal rows are retained; candidate positions still use exact QLR peak checks. */
internal class GuideCandidateMask(private val frame: FrameData, phaseMeans: DoubleArray, threshold: Double) {
    private val phaseLevels = IntArray(4) { kotlin.math.floor(phaseMeans[it]).toInt() }
    // Sum(limits) = 4 * floor(threshold) + remainder, where remainder is in 0..3.
    // Thus four samples at/below their limits cannot produce an above-threshold QLR pixel.
    private val phaseAverage = Math.floorDiv(phaseLevels.sum(), 4)
    private val limits = IntArray(4) { phaseLevels[it] + kotlin.math.floor(threshold).toInt() - phaseAverage }
    private var top = BooleanArray(frame.width)
    private var bottom = BooleanArray(frame.width)
    private var row = -2

    fun prepareRow(y: Int) {
        if (row == y - 1) {
            val old = top
            top = bottom
            bottom = old
        } else fill(top, y)
        fill(bottom, y + 1)
        row = y
    }

    fun mayContainSignal(x: Int): Boolean = top[x] || top[x + 1] || bottom[x] || bottom[x + 1]

    private fun fill(flags: BooleanArray, y: Int) {
        val phaseRow = (y and 1) * 2
        val data = frame.data
        val offset = y * frame.width
        if (frame.pixelFormat.bytesPerPixel == 1) {
            for (x in flags.indices) flags[x] = (data[offset + x].toInt() and 255) > limits[phaseRow + (x and 1)]
        } else {
            var byteOffset = offset * 2
            for (x in flags.indices) {
                val value = (data[byteOffset].toInt() and 255) or ((data[byteOffset + 1].toInt() and 255) shl 8)
                flags[x] = value > limits[phaseRow + (x and 1)]
                byteOffset += 2
            }
        }
    }
}

/** Raw samples stay available for clipping checks and unsmoothed PSF diagnostics. */
internal object GuidePixelAccess {
    fun raw(frame: FrameData, x: Int, y: Int): Int {
        val index = y * frame.width + x
        return when (frame.pixelFormat) {
            PixelFormat.RGB24 -> {
                val base = index * 3
                ((frame.data[base].toInt() and 255) * 299 + (frame.data[base + 1].toInt() and 255) * 587 +
                    (frame.data[base + 2].toInt() and 255) * 114) / 1000
            }
            PixelFormat.RGB48 -> {
                val base = index * 6
                if (base + 5 >= frame.data.size) 0 else
                    (u16(frame.data, base) * 299 + u16(frame.data, base + 2) * 587 + u16(frame.data, base + 4) * 114) / 1000
            }
            else -> if (frame.pixelFormat.bytesPerPixel == 1) {
                if (index >= frame.data.size) 0 else frame.data[index].toInt() and 255
            } else {
                val base = index * 2
                if (base + 1 >= frame.data.size) 0 else u16(frame.data, base)
            }
        }
    }

    fun peak(frame: FrameData, x: Int, y: Int): Int {
        val index = (y * frame.width + x) * frame.pixelFormat.bytesPerPixel
        return when (frame.pixelFormat) {
            PixelFormat.RGB24 -> maxOf(frame.data[index].toInt() and 255, frame.data[index + 1].toInt() and 255, frame.data[index + 2].toInt() and 255)
            PixelFormat.RGB48 -> maxOf(u16(frame.data, index), u16(frame.data, index + 2), u16(frame.data, index + 4))
            else -> raw(frame, x, y)
        }
    }
    private fun u16(data: ByteArray, index: Int) = (data[index].toInt() and 255) or ((data[index + 1].toInt() and 255) shl 8)
}
