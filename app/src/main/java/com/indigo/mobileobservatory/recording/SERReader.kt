package com.indigo.mobileobservatory.recording

import android.graphics.Bitmap
import android.util.Log
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
    val dateTimeUtc: Long
)

class SERReader(private val file: File) {

    companion object {
        private const val TAG = "SERReader"
        private const val HEADER_SIZE = 178
    }

    var header: SERHeader? = null
        private set
    private var raf: RandomAccessFile? = null
    private var bytesPerFrame = 0
    private var timestamps: LongArray? = null

    /** Frame timestamps in 100ns ticks (Windows FILETIME epoch). Null if no timestamp trailer. */
    val frameTimestamps: LongArray? get() = timestamps

    /** Average FPS derived from timestamps, or 0 if unavailable. */
    val recordedFps: Double get() {
        val ts = timestamps ?: return 0.0
        if (ts.size < 2) return 0.0
        val totalTicks = ts.last() - ts.first()
        if (totalTicks <= 0) return 0.0
        val totalSeconds = totalTicks / 10_000_000.0
        return (ts.size - 1) / totalSeconds
    }

    fun open(): Boolean {
        try {
            val r = RandomAccessFile(file, "r")
            val hdrBuf = ByteArray(HEADER_SIZE)
            r.readFully(hdrBuf)
            val bb = ByteBuffer.wrap(hdrBuf).order(ByteOrder.LITTLE_ENDIAN)

            val idBytes = ByteArray(14)
            bb.get(idBytes)
            val fileId = String(idBytes, Charsets.US_ASCII).trimEnd('\u0000')
            val isPSER = fileId.startsWith("PSER")

            bb.position(14)
            bb.getInt() // LuID
            val colorId = bb.getInt()
            bb.getInt() // LittleEndian
            val width = bb.getInt()
            val height = bb.getInt()
            val pixelDepth = bb.getInt()
            val frameCount = bb.getInt()

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
                PSERWriter.packedBytesPerFrame(width, height, pixelDepth)
            } else {
                val bpp = if (pixelDepth > 8) 2 else 1
                width * height * bpp
            }

            header = SERHeader(isPSER, colorId, width, height, pixelDepth, frameCount,
                observer, instrument, telescope, dateTimeUtc)
            raf = r

            readTimestamps(r, frameCount)
            val fps = recordedFps
            Log.i(TAG, "Opened: ${file.name} ${width}x${height} ${pixelDepth}bit ${frameCount}frames pser=$isPSER bpf=$bytesPerFrame fps=%.1f".format(fps))
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ${file.name}: ${e.message}")
            return false
        }
    }

    fun readFrame(index: Int): ByteArray? {
        val r = raf ?: return null
        val h = header ?: return null
        if (index < 0 || index >= h.frameCount) return null

        return try {
            val offset = HEADER_SIZE.toLong() + bytesPerFrame.toLong() * index
            synchronized(r) {
                r.seek(offset)
                val data = ByteArray(bytesPerFrame)
                r.readFully(data)
                data
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read frame $index: ${e.message}")
            null
        }
    }

    private var detectedBits = 0

    /**
     * Decode a frame to 8-bit grayscale for display.
     * Handles both SER (16-bit LE) and PSER (packed) formats.
     */
    fun decodeFrameTo8bit(frameData: ByteArray): ByteArray {
        val h = header ?: return frameData
        val totalPixels = h.width * h.height

        if (h.pixelDepth <= 8) return frameData

        if (h.isPSER && h.pixelDepth in 10..14) {
            return unpackTo8bit(frameData, totalPixels, h.pixelDepth)
        }

        if (frameData.size >= totalPixels * 2) {
            if (detectedBits == 0) {
                val effective = detectEffectiveBits(frameData, totalPixels)
                val headerBits = h.pixelDepth
                detectedBits = if (headerBits in 10..14 && effective > headerBits) {
                    effective
                } else {
                    headerBits.coerceAtLeast(effective)
                }
                Log.i(TAG, "Bit depth: effective=$effective, header=$headerBits, using=$detectedBits")
            }

            val out = ByteArray(totalPixels)
            val headerBits = h.pixelDepth
            val isLeftJustified = detectedBits > headerBits
            val shift = if (isLeftJustified) {
                detectedBits - 8
            } else {
                (headerBits - 8).coerceAtLeast(0)
            }

            for (i in 0 until totalPixels) {
                val lo = frameData[i * 2].toInt() and 0xFF
                val hi = frameData[i * 2 + 1].toInt() and 0xFF
                val raw = lo or (hi shl 8)
                out[i] = (raw shr shift).coerceIn(0, 255).toByte()
            }
            return out
        }

        return frameData
    }

    private fun detectEffectiveBits(data: ByteArray, totalPixels: Int): Int {
        var maxVal = 0
        val count = totalPixels.coerceAtMost(data.size / 2)
        for (i in 0 until count) {
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt() and 0xFF
            val v = lo or (hi shl 8)
            if (v > maxVal) maxVal = v
        }
        return when {
            maxVal <= 0xFF -> 8
            maxVal <= 0x3FF -> 10
            maxVal <= 0xFFF -> 12
            maxVal <= 0x3FFF -> 14
            else -> 16
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
            if (h.colorId in 8..11) {
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
            Bitmap.createBitmap(pixels, w, ht, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM in frameToBitmap: ${w}x${ht}")
            null
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


    fun close() {
        raf?.close()
        raf = null
        header = null
        timestamps = null
        detectedBits = 0
    }

    private fun readTimestamps(r: RandomAccessFile, frameCount: Int) {
        try {
            val tsOffset = HEADER_SIZE.toLong() + bytesPerFrame.toLong() * frameCount
            if (tsOffset + frameCount * 8L > r.length()) {
                timestamps = null
                return
            }
            r.seek(tsOffset)
            val ts = LongArray(frameCount)
            val buf = ByteArray(8)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                r.readFully(buf)
                bb.position(0)
                ts[i] = bb.getLong()
            }
            timestamps = ts
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read timestamps: ${e.message}")
            timestamps = null
        }
    }

    private fun unpackTo8bit(packed: ByteArray, pixelCount: Int, bits: Int): ByteArray {
        val out = ByteArray(pixelCount)
        val shift = bits - 8
        when (bits) {
            10 -> unpack10to8(packed, out, pixelCount, shift)
            12 -> unpack12to8(packed, out, pixelCount, shift)
            14 -> unpack14to8(packed, out, pixelCount, shift)
            else -> {
                for (i in 0 until pixelCount.coerceAtMost(packed.size)) {
                    out[i] = packed[i]
                }
            }
        }
        return out
    }

    private fun unpack10to8(src: ByteArray, dst: ByteArray, pixelCount: Int, shift: Int) {
        var si = 0
        var pi = 0
        val groups = pixelCount / 4
        for (g in 0 until groups) {
            if (si + 4 >= src.size) break
            val b0 = src[si++].toInt() and 0xFF
            val b1 = src[si++].toInt() and 0xFF
            val b2 = src[si++].toInt() and 0xFF
            val b3 = src[si++].toInt() and 0xFF
            val b4 = src[si++].toInt() and 0xFF
            val p0 = b0 or ((b1 and 0x03) shl 8)
            val p1 = (b1 shr 2) or ((b2 and 0x0F) shl 6)
            val p2 = (b2 shr 4) or ((b3 and 0x3F) shl 4)
            val p3 = (b3 shr 6) or (b4 shl 2)
            dst[pi++] = (p0 shr shift).coerceIn(0, 255).toByte()
            dst[pi++] = (p1 shr shift).coerceIn(0, 255).toByte()
            dst[pi++] = (p2 shr shift).coerceIn(0, 255).toByte()
            dst[pi++] = (p3 shr shift).coerceIn(0, 255).toByte()
        }
    }

    private fun unpack12to8(src: ByteArray, dst: ByteArray, pixelCount: Int, shift: Int) {
        var si = 0
        var pi = 0
        val pairs = pixelCount / 2
        for (p in 0 until pairs) {
            if (si + 2 >= src.size) break
            val b0 = src[si++].toInt() and 0xFF
            val b1 = src[si++].toInt() and 0xFF
            val b2 = src[si++].toInt() and 0xFF
            val p0 = b0 or ((b1 and 0x0F) shl 8)
            val p1 = (b1 shr 4) or (b2 shl 4)
            dst[pi++] = (p0 shr shift).coerceIn(0, 255).toByte()
            dst[pi++] = (p1 shr shift).coerceIn(0, 255).toByte()
        }
    }

    private fun unpack14to8(src: ByteArray, dst: ByteArray, pixelCount: Int, shift: Int) {
        var si = 0
        var pi = 0
        val groups = pixelCount / 4
        for (g in 0 until groups) {
            if (si + 6 >= src.size) break
            var accum = 0L
            for (b in 0 until 7) {
                accum = accum or ((src[si++].toLong() and 0xFF) shl (b * 8))
            }
            dst[pi++] = ((accum and 0x3FFF) shr shift).toInt().coerceIn(0, 255).toByte()
            dst[pi++] = (((accum shr 14) and 0x3FFF) shr shift).toInt().coerceIn(0, 255).toByte()
            dst[pi++] = (((accum shr 28) and 0x3FFF) shr shift).toInt().coerceIn(0, 255).toByte()
            dst[pi++] = (((accum shr 42) and 0x3FFF) shr shift).toInt().coerceIn(0, 255).toByte()
        }
    }
}
