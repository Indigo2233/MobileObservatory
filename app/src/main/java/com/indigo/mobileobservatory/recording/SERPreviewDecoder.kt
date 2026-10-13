package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.stacking.LinearImage
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Display-only, bounded random-access sampling. Never allocates an original-size frame.
 * Bayer interpolation uses neighbouring original sensor samples before spatial subsampling;
 * selecting every Nth CFA pixel first would discard colours whenever N is even.
 */
internal object SERPreviewDecoder {
    const val DEFAULT_PIXELS = 1_048_576
    const val MAX_PIXELS = 4_194_304
    const val MAX_DIMENSION = 2048
    const val CACHE_BYTES = 8 * 16_384

    fun read(file: RandomAccessFile, header: SERHeader, frameOffset: Long, frameBytes: Long,
             littleEndian: Boolean, alignment: SampleAlignment, maxPixels: Int,
             cancelCheck: () -> Unit): LinearImage {
        require(maxPixels in 1..MAX_PIXELS) { "Invalid preview pixel budget" }
        val width = header.width; val height = header.height
        val scale = max(1.0, max(sqrt(width.toDouble()*height/maxPixels),
            max(width,height).toDouble()/MAX_DIMENSION))
        var outWidth = max(1,(width/scale).toInt())
        var outHeight = max(1,(height/scale).toInt())
        while (outWidth.toLong()*outHeight > maxPixels) {
            if (outWidth >= outHeight) outWidth-- else outHeight--
        }
        val rgb = header.colorId >= 100
        val bayer = header.colorId in 8..11
        val channels = if (rgb || bayer) 3 else 1
        cancelCheck()
        val pixels = FloatArray(outWidth*outHeight*channels)
        val pages = FramePages(file,frameOffset,frameBytes)
        val bits = header.pixelDepth
        val sampleBytes = if (bits > 8) 2 else 1
        val maximum = (1 shl bits)-1
        val declaredAlignment = if (header.isPSER) SampleAlignment.RIGHT else alignment
        val shift = if (declaredAlignment == SampleAlignment.LEFT) sampleBytes*8-bits else 0
        var observedMaximum = 0

        fun sample(x: Int,y: Int,c: Int = 0): Int {
            val index = (y.toLong()*width+x)*(if (rgb) 3 else 1)+c
            val raw: Int
            if (header.isPSER && bits != 8 && bits != 16) {
                // PSER is one LSB-first bitstream; odd row lengths do not start a new byte.
                val bit = index*bits
                val byte = bit/8
                val offset = (bit%8).toInt()
                var word = pages.byte(byte) or (pages.byte(byte+1) shl 8)
                if (offset+bits > 16) word = word or (pages.byte(byte+2) shl 16)
                raw = (word ushr offset) and maximum
            } else {
                val offset = index*sampleBytes
                val a = pages.byte(offset)
                raw = if (sampleBytes == 1) a else {
                    val b = pages.byte(offset+1)
                    if (littleEndian) a or (b shl 8) else (a shl 8) or b
                }
            }
            if (raw > observedMaximum) observedMaximum = raw
            return raw ushr shift
        }
        val redX = if (header.colorId == 9 || header.colorId == 11) 1 else 0
        val redY = if (header.colorId == 10 || header.colorId == 11) 1 else 0
        fun color(x: Int,y: Int) = when {
            (x and 1) == redX && (y and 1) == redY -> 0
            (x and 1) != redX && (y and 1) != redY -> 2
            else -> 1
        }
        for (y in 0 until outHeight) {
            cancelCheck()
            val sy = (((2L*y+1)*height)/(2L*outHeight)).toInt().coerceAtMost(height-1)
            for (x in 0 until outWidth) {
                if (x % 256 == 0) cancelCheck()
                val sx = (((2L*x+1)*width)/(2L*outWidth)).toInt().coerceAtMost(width-1)
                val p = (y*outWidth+x)*channels
                if (rgb) {
                    for (c in 0..2) pixels[p+c] = sample(sx,sy,if (header.colorId == 101) 2-c else c).toFloat()
                } else if (bayer) {
                    val own = color(sx,sy)
                    val center = sample(sx,sy)
                    for (c in 0..2) {
                        if (c == own) { pixels[p+c] = center.toFloat(); continue }
                        var sum = 0; var count = 0
                        for (yy in max(0,sy-1)..min(height-1,sy+1))
                            for (xx in max(0,sx-1)..min(width-1,sx+1))
                                if (color(xx,yy) == c) { sum += sample(xx,yy); count++ }
                        pixels[p+c] = if (count > 0) sum.toFloat()/count else center.toFloat()
                    }
                } else pixels[p] = sample(sx,sy).toFloat()
            }
        }
        // Display AUTO uses only samples contributing to this preview. It cannot infer an
        // entirely dark left-aligned legacy file; scientific readers use explicit traits.
        val autoShift = if (declaredAlignment == SampleAlignment.AUTO && observedMaximum > maximum)
            sampleBytes*8-bits else 0
        val divisor = maximum.toFloat()*(1 shl autoShift)
        for (i in pixels.indices) {
            if (i % 16384 == 0) cancelCheck()
            pixels[i] = (pixels[i]/divisor).coerceIn(0f,1f)
        }
        return LinearImage(outWidth,outHeight,channels,pixels)
    }

    /** Eight LRU pages cover the three Bayer neighbourhood rows, including page boundaries.
     * A direct-mapped cache would thrash when a row stride is a multiple of the cache size.
     */
    private class FramePages(private val file: RandomAccessFile, private val base: Long,
                             private val length: Long) {
        private val pageSize = CACHE_BYTES/8
        private val buffers = Array(8) { ByteArray(pageSize) }
        private val starts = LongArray(8) { -1L }
        private val ages = LongArray(8)
        private var clock = 0L
        private var last = 0

        fun byte(offset: Long): Int {
            require(offset in 0 until length) { "Preview sample outside SER frame" }
            val start = offset/pageSize*pageSize
            var slot = last
            if (starts[slot] != start) {
                slot = starts.indexOf(start)
                if (slot < 0) {
                    slot = 0
                    for (i in 1..7) if (ages[i] < ages[slot]) slot = i
                    file.seek(Math.addExact(base,start))
                    file.readFully(buffers[slot],0,min(pageSize.toLong(),length-start).toInt())
                    starts[slot] = start
                }
                last = slot
            }
            ages[slot] = ++clock
            return buffers[slot][(offset-start).toInt()].toInt() and 255
        }
    }
}
