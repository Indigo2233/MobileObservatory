package com.indigo.mobileobservatory.recording

import android.graphics.Bitmap
import android.util.Log
import com.indigo.mobileobservatory.util.ImageUtils
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.stacking.ChannelOrder
import com.indigo.mobileobservatory.stacking.LinearImage
import com.indigo.mobileobservatory.stacking.LinearPixelDecoder
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SERHeader(
    val isPSER: Boolean,
    val colorId: Int,
    val width: Int,
    val height: Int,
    val pixelDepth: Int,
    val frameCount: Int,
    val observer: String,
    val instrument: String,
    val telescope: String,
    val dateTimeUtc: Long,
    val littleEndianFlag: Int = 0
)

/** Existing SER software uses the opposite flag to the original written specification.
 * COMPATIBILITY follows Siril, SER Player and FFmpeg: 0 = LE, 1 = BE.
 * https://siril.readthedocs.io/en/latest/file-formats/SER.html
 */
enum class SerEndianConvention { COMPATIBILITY, SPECIFICATION }

class SERReader(private val file: File,
                val endianConvention: SerEndianConvention = SerEndianConvention.COMPATIBILITY) : AutoCloseable {

    var sampleAlignment: SampleAlignment = SampleAlignment.AUTO
    var lastError: String? = null
        private set

    /** LP750 conversion with per-frame white balance for Bayer playback. */
    var lp750Enabled: Boolean = true

    companion object {
        private const val TAG = "SERReader"
        private const val HEADER_SIZE = 178
    }

    var header: SERHeader? = null
        private set
    private var raf: RandomAccessFile? = null
    private var bytesPerFrame = 0L
    val frameByteSize: Long get() = bytesPerFrame
    private var timestamps: LongArray? = null
    private var timestampOffset: Long? = null

    /** Compatibility API for the player; materialized only on request and bounded to 8 MB.
     * Stacking and large-file playback use [readTimestamp] instead. SER ticks start at year 1.
     */
    val frameTimestamps: LongArray? get() {
        val h = header ?: return null
        if (timestampOffset == null || h.frameCount > 1_000_000) return null
        return timestamps ?: LongArray(h.frameCount) { readTimestamp(it) ?: 0L }.also { timestamps = it }
    }

    /** Average FPS derived from timestamps, or 0 if unavailable. */
    val recordedFps: Double get() {
        val count = header?.frameCount ?: return 0.0
        if (count < 2) return 0.0
        val first = readTimestamp(0) ?: return 0.0
        val last = readTimestamp(count - 1) ?: return 0.0
        val totalTicks = last - first
        if (totalTicks <= 0) return 0.0
        val totalSeconds = totalTicks / 10_000_000.0
        return (count - 1) / totalSeconds
    }

    fun open(): Boolean {
        close()
        lastError = null
        var opened: RandomAccessFile? = null
        try {
            val r = RandomAccessFile(file, "r")
            opened = r
            require(r.length() >= HEADER_SIZE) { "Truncated SER header" }
            val hdrBuf = ByteArray(HEADER_SIZE)
            r.readFully(hdrBuf)
            val bb = ByteBuffer.wrap(hdrBuf).order(ByteOrder.LITTLE_ENDIAN)

            val idBytes = ByteArray(14)
            bb.get(idBytes)
            val fileId = String(idBytes, Charsets.US_ASCII).trimEnd('\u0000')
            require(fileId == "LUCAM-RECORDER" || fileId == "PSER-RECORDER") { "Invalid SER signature" }
            val isPSER = fileId == "PSER-RECORDER"

            bb.position(14)
            bb.getInt() // LuID
            val colorId = bb.getInt()
            val endianFlag = bb.getInt()
            val width = bb.getInt()
            val height = bb.getInt()
            val pixelDepth = bb.getInt()
            val frameCount = bb.getInt()
            require(endianFlag == 0 || endianFlag == 1) { "Invalid SER byte-order flag" }
            require(colorId == 0 || colorId in 8..11 || colorId == 100 || colorId == 101) {
                "Unsupported SER color ID: $colorId"
            }
            require(width > 0 && height > 0 && width.toLong() * height <= LinearImage.MAX_PIXELS) {
                "Invalid dimensions or image exceeds 16 MP reader limit"
            }
            require(pixelDepth in 1..16) { "Invalid SER sample depth" }
            require(frameCount > 0) { "SER contains no readable frames" }
            if (isPSER) require(colorId < 100 && pixelDepth in listOf(8, 10, 12, 14, 16)) {
                "Unsupported packed SER format"
            }

            bb.position(42)
            val obsBytes = ByteArray(40); bb.get(obsBytes)
            val observer = String(obsBytes, Charsets.US_ASCII).trimEnd('\u0000')
            bb.position(82)
            val instrBytes = ByteArray(40); bb.get(instrBytes)
            val instrument = String(instrBytes, Charsets.US_ASCII).trimEnd('\u0000')
            bb.position(122)
            val telBytes = ByteArray(40); bb.get(telBytes)
            val telescope = String(telBytes, Charsets.US_ASCII).trimEnd('\u0000')
            bb.position(162)
            bb.getLong() // DateTime
            val dateTimeUtc = bb.getLong()

            bytesPerFrame = if (isPSER) {
                (width.toLong() * height * pixelDepth + 7L) / 8L
            } else {
                val bpp = if (pixelDepth > 8) 2 else 1
                width.toLong() * height * bpp * if (colorId >= 100) 3 else 1
            }
            require(bytesPerFrame in 1..Int.MAX_VALUE.toLong()) { "Frame allocation exceeds addressable memory" }
            val dataEnd = Math.addExact(HEADER_SIZE.toLong(), Math.multiplyExact(bytesPerFrame, frameCount.toLong()))
            require(r.length() >= dataEnd) { "Truncated SER frames: need $dataEnd bytes, found ${r.length()}" }
            val trailerSize = Math.multiplyExact(frameCount.toLong(), 8L)
            val trailing = r.length() - dataEnd
            require(trailing == 0L || trailing >= trailerSize) { "Truncated SER timestamp trailer" }
            timestampOffset = if (trailing >= trailerSize) dataEnd else null

            header = SERHeader(isPSER, colorId, width, height, pixelDepth, frameCount,
                observer, instrument, telescope, dateTimeUtc, endianFlag)
            raf = r

            val fps = recordedFps
            Log.i(TAG, "Opened: ${file.name} ${width}x${height} ${pixelDepth}bit ${frameCount}frames pser=$isPSER bpf=$bytesPerFrame fps=%.1f".format(fps))
            return true
        } catch (e: Exception) {
            opened?.close()
            header = null
            raf = null
            timestampOffset = null
            lastError = e.message ?: "Could not read SER"
            Log.e(TAG, "Failed to open ${file.name}: ${e.message}")
            return false
        }
    }

    fun readFrame(index: Int): ByteArray? {
        val r = raf ?: return null
        val h = header ?: return null
        if (index < 0 || index >= h.frameCount) return null

        return try {
            val offset = frameOffset(index)
            synchronized(r) {
                r.seek(offset)
                val data = ByteArray(bytesPerFrame.toInt())
                r.readFully(data)
                data
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read frame $index: ${e.message}")
            null
        }
    }

    fun frameOffset(index: Int): Long {
        val h = checkNotNull(header) { "Reader is closed" }
        require(index in 0 until h.frameCount)
        return Math.addExact(HEADER_SIZE.toLong(), Math.multiplyExact(bytesPerFrame, index.toLong()))
    }

    fun readTimestamp(index: Int): Long? {
        val r = raf ?: return null
        val h = header ?: return null
        val base = timestampOffset ?: return null
        if (index !in 0 until h.frameCount) return null
        return synchronized(r) {
            try {
                r.seek(Math.addExact(base, index.toLong() * 8L))
                java.lang.Long.reverseBytes(r.readLong())
            } catch (_: Exception) { null }
        }
    }

    /** One owned frame only; no display stretch, LP750, white balance or 8-bit conversion. */
    fun readLinearFrame(index: Int, maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS): LinearImage? {
        val h = header ?: return null
        require(h.width.toLong() * h.height <= maxPixels) { "Image exceeds stacking budget; use a smaller source ROI" }
        return readFrame(index)?.let { decodeFrameLinear(it, maxPixels) }
    }

    fun decodeFrameLinear(data: ByteArray, maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS): LinearImage {
        return LinearPixelDecoder.demosaicSensor(decodeFrameSensor(data, maxPixels), sensorFormat, sensorTraits)
    }

    /** Raw linear samples for masters and light calibration, before CFA interpolation. */
    fun readSensorFrame(index: Int, maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS): LinearImage? {
        val h = header ?: return null
        require(h.width.toLong() * h.height <= maxPixels) { "Image exceeds calibration budget; use a smaller source ROI" }
        return readFrame(index)?.let { decodeFrameSensor(it, maxPixels) }
    }

    fun decodeFrameSensor(data: ByteArray, maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS): LinearImage {
        val h = checkNotNull(header) { "Reader is closed" }
        require(h.width.toLong() * h.height <= maxPixels) { "Image exceeds stacking budget; use a smaller source ROI" }
        val raw = unpackSamples(data)
        return LinearPixelDecoder.decodeSensor(FrameData(raw, h.width, h.height, sensorFormat, 0, 0), sensorTraits, maxPixels)
    }

    val sensorFormat: PixelFormat get() {
        val h = checkNotNull(header) { "Reader is closed" }
        return when (h.colorId) {
            100, 101 -> if (h.pixelDepth <= 8) PixelFormat.RGB24 else PixelFormat.RGB48
            else -> {
                val prefix = when (h.colorId) { 8 -> "BAYER_RG"; 9 -> "BAYER_GR"; 10 -> "BAYER_GB"; 11 -> "BAYER_BG"; else -> "MONO" }
                val bits = if (h.pixelDepth <= 8) 8 else 16
                PixelFormat.valueOf("$prefix$bits")
            }
        }
    }

    val sensorTraits: PixelTraits get() {
        val h = checkNotNull(header) { "Reader is closed" }
        return PixelTraits(byteOrder = pixelByteOrder(), alignment = if (h.isPSER) SampleAlignment.RIGHT else sampleAlignment,
            validBits = h.pixelDepth, channelOrder = if (h.colorId == 101) ChannelOrder.BGR else ChannelOrder.RGB)
    }

    private fun pixelByteOrder(): ByteOrder {
        val h = checkNotNull(header)
        val little = h.isPSER || if (endianConvention == SerEndianConvention.COMPATIBILITY) {
            h.littleEndianFlag == 0
        } else h.littleEndianFlag == 1
        return if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    }

    /** PSERWriter stores a contiguous least-significant-bit-first bitstream, including tail pixels. */
    private fun unpackSamples(data: ByteArray): ByteArray {
        val h = checkNotNull(header)
        require(data.size.toLong() >= bytesPerFrame) { "Truncated frame buffer" }
        if (!h.isPSER || h.pixelDepth == 8 || h.pixelDepth == 16) return data
        val count = h.width * h.height
        val out = ByteArray(count * 2)
        var accumulator = 0L; var available = 0; var source = 0
        val mask = (1L shl h.pixelDepth) - 1L
        for (i in 0 until count) {
            while (available < h.pixelDepth) {
                accumulator = accumulator or ((data[source++].toLong() and 255L) shl available)
                available += 8
            }
            val v = (accumulator and mask).toInt()
            out[2 * i] = v.toByte(); out[2 * i + 1] = (v ushr 8).toByte()
            accumulator = accumulator ushr h.pixelDepth; available -= h.pixelDepth
        }
        return out
    }

    /** Display compatibility path. Raw stacking must use decodeFrameLinear instead. */
    fun decodeFrameTo8bit(frameData: ByteArray): ByteArray {
        val h = header ?: return frameData
        val raw = unpackSamples(frameData)
        val count = h.width * h.height * if (h.colorId >= 100) 3 else 1
        val sampleBytes = if (h.pixelDepth > 8) 2 else 1
        val little = pixelByteOrder() == ByteOrder.LITTLE_ENDIAN
        fun sample(i: Int): Int {
            val a = raw[i * sampleBytes].toInt() and 255
            if (sampleBytes == 1) return a
            val b = raw[i * sampleBytes + 1].toInt() and 255
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        var alignment = if (h.isPSER) SampleAlignment.RIGHT else sampleAlignment
        if (alignment == SampleAlignment.AUTO) {
            alignment = SampleAlignment.RIGHT
            val max = (1 shl h.pixelDepth) - 1
            for (i in 0 until count) if (sample(i) > max) { alignment = SampleAlignment.LEFT; break }
        }
        val offset = if (alignment == SampleAlignment.LEFT) sampleBytes * 8 - h.pixelDepth else 0
        val max = (1 shl h.pixelDepth) - 1
        return ByteArray(count) { i ->
            val source = if (h.colorId == 101) (i / 3) * 3 + 2 - i % 3 else i
            (((sample(source) ushr offset) * 255L / max).coerceIn(0, 255)).toByte()
        }
    }
    fun frameToBitmap(frameData: ByteArray): Bitmap? {
        val h = header ?: return null
        val gray8 = decodeFrameTo8bit(frameData)
        val w = h.width
        val ht = h.height
        if (w <= 0 || ht <= 0) return null

        return try {
            val pixels = IntArray(w * ht)
            if (h.colorId >= 100) {
                for (i in pixels.indices) {
                    val r = gray8[i * 3].toInt() and 255
                    val g = gray8[i * 3 + 1].toInt() and 255
                    val b = gray8[i * 3 + 2].toInt() and 255
                    pixels[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
            } else if (h.colorId in 8..11 && w >= 3 && ht >= 3) {
                debayer8ToPixels(gray8, pixels, w, ht, h.colorId)
            } else {
                var minV = 255; var maxV = 0
                val sampleStep = ((w * ht) / 10000).coerceAtLeast(1)
                var si2 = 0
                while (si2 < gray8.size && si2 < w * ht) {
                    val sv = gray8[si2].toInt() and 0xFF
                    if (sv < minV) minV = sv
                    if (sv > maxV) maxV = sv
                    si2 += sampleStep
                }
                val rng = (maxV - minV).coerceAtLeast(1)
                for (i in 0 until (w * ht).coerceAtMost(gray8.size)) {
                    val v = ((gray8[i].toInt() and 0xFF) - minV) * 255 / rng
                    val sv = v.coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (sv shl 16) or (sv shl 8) or sv
                }
            }
            if (lp750Enabled && h.colorId in 8..11) {
                applyLp750ToPixels(pixels)
            }
            Bitmap.createBitmap(pixels, w, ht, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM in frameToBitmap: ${w}x${ht}")
            null
        }
    }

    private fun applyLp750ToPixels(pixels: IntArray) {
        if (pixels.isEmpty()) return
        val step = (pixels.size / 10000).coerceAtLeast(1)
        var sumR = 0f; var sumG = 0f; var sumB = 0f; var count = 0
        for (i in pixels.indices step step) {
            val p = pixels[i]
            sumR += ((p ushr 16) and 0xFF) / 255f
            sumG += ((p ushr 8) and 0xFF) / 255f
            sumB += (p and 0xFF) / 255f
            count++
        }
        if (count == 0) return
        val gains = ImageUtils.lp750WhiteBalanceGains(sumR / count, sumG / count, sumB / count)
        for (i in pixels.indices) {
            val p = pixels[i]
            val converted = ImageUtils.lp750Matrix(
                ((p ushr 16) and 0xFF) / 255f,
                ((p ushr 8) and 0xFF) / 255f,
                (p and 0xFF) / 255f
            )
            val r = (converted[0] * gains[0] * 255f).coerceIn(0f, 255f).toInt()
            val g = (converted[1] * gains[1] * 255f).coerceIn(0f, 255f).toInt()
            val b = (converted[2] * gains[2] * 255f).coerceIn(0f, 255f).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun debayer8ToPixels(gray: ByteArray, pixels: IntArray, w: Int, h: Int, colorId: Int) {
        val rX: Int; val rY: Int
        when (colorId) {
            8  -> { rX = 0; rY = 0 } // RGGB
            9  -> { rX = 1; rY = 0 } // GRBG
            10 -> { rX = 0; rY = 1 } // GBRG
            else -> { rX = 1; rY = 1 } // BGGR
        }

        var minVal = 255; var maxVal = 0
        val total = w * h
        val sampleStep = (total / 10000).coerceAtLeast(1)
        var si = 0
        while (si < gray.size && si < total) {
            val v = gray[si].toInt() and 0xFF
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
            si += sampleStep
        }
        val sRange = (maxVal - minVal).coerceAtLeast(1)

        fun g(x: Int, y: Int): Int {
            val i = y * w + x
            return if (i in gray.indices) gray[i].toInt() and 0xFF else 0
        }
        fun stretch(v: Int) = ((v - minVal) * 255 / sRange).coerceIn(0, 255)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val bx = x and 1; val by = y and 1
                val c = g(x, y)
                val r: Int; val gr: Int; val b: Int
                when {
                    bx == rX && by == rY -> {
                        r = c
                        gr = (g(x-1,y) + g(x+1,y) + g(x,y-1) + g(x,y+1)) / 4
                        b = (g(x-1,y-1) + g(x+1,y-1) + g(x-1,y+1) + g(x+1,y+1)) / 4
                    }
                    bx != rX && by != rY -> {
                        b = c
                        gr = (g(x-1,y) + g(x+1,y) + g(x,y-1) + g(x,y+1)) / 4
                        r = (g(x-1,y-1) + g(x+1,y-1) + g(x-1,y+1) + g(x+1,y+1)) / 4
                    }
                    else -> {
                        gr = c
                        if (by == rY) {
                            r = (g(x-1,y) + g(x+1,y)) / 2
                            b = (g(x,y-1) + g(x,y+1)) / 2
                        } else {
                            b = (g(x-1,y) + g(x+1,y)) / 2
                            r = (g(x,y-1) + g(x,y+1)) / 2
                        }
                    }
                }
                pixels[y * w + x] = -0x1000000 or (stretch(r) shl 16) or (stretch(gr) shl 8) or stretch(b)
            }
        }
        // Copy border pixels from nearest interior pixel
        for (x in 0 until w) {
            pixels[x] = pixels[w + x.coerceIn(1, w - 2)]
            pixels[(h-1)*w + x] = pixels[(h-2)*w + x.coerceIn(1, w - 2)]
        }
        for (y in 0 until h) {
            pixels[y * w] = pixels[y * w + 1]
            pixels[y * w + w - 1] = pixels[y * w + w - 2]
        }
    }


    override fun close() {
        raf?.close()
        raf = null
        header = null
        timestamps = null
        timestampOffset = null
        bytesPerFrame = 0
    }
}
