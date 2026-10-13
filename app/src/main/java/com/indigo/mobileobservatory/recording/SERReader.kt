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
import com.indigo.mobileobservatory.stacking.DemosaicAlgorithm
import com.indigo.mobileobservatory.util.BayerDemosaic
import com.indigo.mobileobservatory.stacking.SerCfaInterpretation
import java.io.File
import java.io.IOException
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
                val endianConvention: SerEndianConvention = SerEndianConvention.COMPATIBILITY,
                val cfaInterpretation: SerCfaInterpretation = SerCfaInterpretation.AUTO) : AutoCloseable {

    var sampleAlignment: SampleAlignment = SampleAlignment.AUTO
    var lastError: String? = null
        private set

    /** LP750 conversion with per-frame white balance for Bayer playback. */
    var lp750Enabled: Boolean = false

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
            require(colorId >= 0) { "invalid_source" }
            require(endianFlag == 0 || endianFlag == 1) { "Invalid SER byte-order flag" }
            val effectiveColorId = cfaInterpretation.effectiveColorId(colorId)
            require(effectiveColorId == 0 || effectiveColorId in 8..11 || effectiveColorId == 100 || effectiveColorId == 101) {
                "cfa_unknown_header"
            }
            require(width > 0 && height > 0) { "Invalid SER dimensions" }
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
                Math.addExact(Math.multiplyExact(width.toLong() * height, pixelDepth.toLong()), 7L) / 8L
            } else {
                val bpp = if (pixelDepth > 8) 2 else 1
                Math.multiplyExact(width.toLong() * height, (bpp * if (colorId >= 100) 3 else 1).toLong())
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
        // Header inspection and streaming playback support larger sensors. A caller asking
        // for a complete byte array still has the previous full-frame allocation ceiling.
        require(h.width.toLong() * h.height <= LinearImage.MAX_PIXELS) {
            "Full frame exceeds 16 MP raw buffer limit; use readPreviewFrame for playback"
        }

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

    /** Bounded display sampling, independent of the full-resolution scientific input budget. */
    fun readPreviewFrame(index: Int, maxPixels: Int = SERPreviewDecoder.DEFAULT_PIXELS,
                         cancelCheck: () -> Unit = {}): LinearImage? {
        val r = raf ?: return null
        val h = header ?: return null
        if (index !in 0 until h.frameCount) return null
        return synchronized(r) {
            cancelCheck()
            if (raf !== r || header !== h) return@synchronized null
            val size = if (h.isPSER) (h.width.toLong()*h.height*h.pixelDepth+7L)/8L
                else h.width.toLong()*h.height*(if (h.pixelDepth > 8) 2 else 1)*(if (h.colorId >= 100) 3 else 1)
            try {
                SERPreviewDecoder.read(r,h.copy(colorId = cfaInterpretation.effectiveColorId(h.colorId)),Math.addExact(HEADER_SIZE.toLong(),Math.multiplyExact(size,index.toLong())),size,
                    pixelByteOrder(h) == ByteOrder.LITTLE_ENDIAN,sampleAlignment,maxPixels,cancelCheck)
            } catch (e: IOException) {
                lastError = e.message ?: "Could not read SER preview"
                Log.w(TAG,"Failed to read preview frame $index: ${e.message}")
                null
            }
        }
    }

    /** The player's only file-to-bitmap path; large source frames are never materialized. */
    fun readPreviewBitmap(index: Int, maxPixels: Int = SERPreviewDecoder.DEFAULT_PIXELS,
                          cancelCheck: () -> Unit = {}): Bitmap? {
        val h = header ?: return null
        val image = readPreviewFrame(index,maxPixels,cancelCheck) ?: return null
        val pixels = IntArray(image.pixelCount)
        var low = 0f; var high = 1f
        if (h.colorId < 100) {
            low = Float.POSITIVE_INFINITY; high = Float.NEGATIVE_INFINITY
            for (i in image.pixels.indices) {
                if (i % 16384 == 0) cancelCheck()
                low = minOf(low,image.pixels[i]); high = maxOf(high,image.pixels[i])
            }
        }
        val range = (high-low).coerceAtLeast(1f/65535f)
        fun display(value: Float) = ((value-low)*255f/range).toInt().coerceIn(0,255)
        for (i in pixels.indices) {
            if (i % 16384 == 0) cancelCheck()
            val p = i*image.channels
            val red = display(image.pixels[p])
            val green = if (image.channels == 3) display(image.pixels[p+1]) else red
            val blue = if (image.channels == 3) display(image.pixels[p+2]) else red
            pixels[i] = -0x1000000 or (red shl 16) or (green shl 8) or blue
        }
        if (lp750Enabled && sensorFormat.isBayer) applyLp750ToPixels(pixels,cancelCheck)
        cancelCheck()
        return Bitmap.createBitmap(pixels,image.width,image.height,Bitmap.Config.ARGB_8888)
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
    fun readLinearFrame(index: Int, maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS,
        algorithm: DemosaicAlgorithm = DemosaicAlgorithm.BILINEAR, cancelCheck: () -> Unit = {}): LinearImage? {
        val h = header ?: return null
        require(h.width.toLong() * h.height <= maxPixels) { "Image exceeds stacking budget; use a smaller source ROI" }
        cancelCheck()
        return readFrame(index)?.let { decodeFrameLinear(it, maxPixels, algorithm, cancelCheck) }
    }

    fun decodeFrameLinear(data: ByteArray, maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS,
        algorithm: DemosaicAlgorithm = DemosaicAlgorithm.BILINEAR, cancelCheck: () -> Unit = {}): LinearImage {
        cancelCheck()
        return LinearPixelDecoder.demosaicSensor(decodeFrameSensor(data, maxPixels, cancelCheck), sensorFormat, sensorTraits, algorithm, cancelCheck)
    }

    /** Raw linear samples for masters and light calibration, before CFA interpolation. */
    fun readSensorFrame(index: Int, maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS,
        cancelCheck: () -> Unit = {}): LinearImage? {
        val h = header ?: return null
        require(h.width.toLong() * h.height <= maxPixels) { "Image exceeds calibration budget; use a smaller source ROI" }
        cancelCheck()
        return readFrame(index)?.let { decodeFrameSensor(it, maxPixels, cancelCheck) }
    }

    fun decodeFrameSensor(data: ByteArray, maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS,
        cancelCheck: () -> Unit = {}): LinearImage {
        cancelCheck()
        val h = checkNotNull(header) { "Reader is closed" }
        require(h.width.toLong() * h.height <= maxPixels) { "Image exceeds stacking budget; use a smaller source ROI" }
        val raw = unpackSamples(data, cancelCheck)
        return LinearPixelDecoder.decodeSensor(FrameData(raw, h.width, h.height, sensorFormat, 0, 0), sensorTraits, maxPixels, cancelCheck)
    }

    val sensorFormat: PixelFormat get() {
        val h = checkNotNull(header) { "Reader is closed" }
        return cfaInterpretation.sensorFormat(h.colorId, h.pixelDepth)
    }

    val sensorTraits: PixelTraits get() {
        val h = checkNotNull(header) { "Reader is closed" }
        return PixelTraits(byteOrder = pixelByteOrder(), alignment = if (h.isPSER) SampleAlignment.RIGHT else sampleAlignment,
            validBits = h.pixelDepth, channelOrder = if (h.colorId == 101) ChannelOrder.BGR else ChannelOrder.RGB)
    }

    private fun pixelByteOrder(h: SERHeader = checkNotNull(header)): ByteOrder {
        val little = h.isPSER || if (endianConvention == SerEndianConvention.COMPATIBILITY) {
            h.littleEndianFlag == 0
        } else h.littleEndianFlag == 1
        return if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    }

    /** PSERWriter stores a contiguous least-significant-bit-first bitstream, including tail pixels. */
    private fun unpackSamples(data: ByteArray, cancelCheck: () -> Unit = {}): ByteArray {
        val h = checkNotNull(header)
        require(data.size.toLong() >= bytesPerFrame) { "Truncated frame buffer" }
        if (!h.isPSER || h.pixelDepth == 8 || h.pixelDepth == 16) return data
        val count = h.width * h.height
        val out = ByteArray(count * 2)
        var accumulator = 0L; var available = 0; var source = 0
        val mask = (1L shl h.pixelDepth) - 1L
        for (i in 0 until count) {
            if (i % 16384 == 0) cancelCheck()
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
        val h = header?.let { it.copy(colorId = cfaInterpretation.effectiveColorId(it.colorId)) } ?: return null
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
            if (lp750Enabled && sensorFormat.isBayer) {
                applyLp750ToPixels(pixels)
            }
            Bitmap.createBitmap(pixels, w, ht, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM in frameToBitmap: ${w}x${ht}")
            null
        }
    }

    private fun applyLp750ToPixels(pixels: IntArray, cancelCheck: () -> Unit = {}) {
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
            if (i % 16384 == 0) cancelCheck()
            val p = pixels[i]
            pixels[i] = ImageUtils.lp750Argb(
                ((p ushr 16) and 0xFF) / 255f,
                ((p ushr 8) and 0xFF) / 255f,
                (p and 0xFF) / 255f,
                gains[0], gains[1], gains[2]
            )
        }
        ImageUtils.applyLp750BackgroundWhiteBalance(pixels)
    }

    private fun debayer8ToPixels(gray: ByteArray, pixels: IntArray, w: Int, h: Int, colorId: Int) {
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

        val raw8 = IntArray(w * h) { i ->
            val v = if (i < gray.size) gray[i].toInt() and 0xFF else 0
            ((v - minVal) * 255 / sRange).coerceIn(0, 255)
        }
        val pattern = when (colorId) {
            8 -> "RGGB"
            9 -> "GRBG"
            10 -> "GBRG"
            else -> "BGGR"
        }
        BayerDemosaic.demosaic8(
            raw8, w, h, pattern, BayerDemosaic.Mode.DIRECTIONAL, pixels
        )
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
