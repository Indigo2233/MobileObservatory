package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset

/**
 * Writes packed SER (PSER) files. 10-bit pixels are packed as 5 bytes per 4 pixels,
 * 12-bit pixels as 3 bytes per 2 pixels. 8-bit data is stored as-is.
 * The header uses "PSER-RECORDER" as file ID and stores the native bit depth
 * in PixelDepthPerPlane, allowing the PC converter to reconstruct 16-bit SER.
 */
class PSERWriter(private val file: File) {

    companion object {
        private const val HEADER_SIZE = 178
        private const val FILE_ID = "PSER-RECORDER\u0000"
        private const val TIMESTAMP_SIZE = 8

        private const val COLOR_MONO = 0
        private const val COLOR_BAYER_RGGB = 8
        private const val COLOR_BAYER_GRBG = 9
        private const val COLOR_BAYER_GBRG = 10
        private const val COLOR_BAYER_BGGR = 11
        private const val LITTLE_ENDIAN_FLAG = 0

        fun packedBytesPerFrame(w: Int, h: Int, nativeBits: Int): Int {
            val totalPixels = w * h
            return when (nativeBits) {
                10 -> (totalPixels * 10 + 7) / 8
                12 -> (totalPixels * 12 + 7) / 8
                14 -> (totalPixels * 14 + 7) / 8
                in 15..16 -> totalPixels * 2
                else -> totalPixels
            }
        }
    }

    private var raf: RandomAccessFile? = null
    private var frameCount = 0
    private var width = 0
    private var height = 0
    private var nativeBits = 8
    private var colorId = COLOR_MONO
    private var packedFrameSize = 0
    private val timestamps = mutableListOf<Long>()
    private var packBuffer: ByteArray? = null

    var totalBytesWritten: Long = 0
        private set

    fun open(w: Int, h: Int, format: PixelFormat, cameraName: String? = null, filterName: String? = null) {
        width = w
        height = h
        nativeBits = format.nativeBits
        colorId = when {
            format.name.startsWith("BAYER_RG") -> COLOR_BAYER_RGGB
            format.name.startsWith("BAYER_GR") -> COLOR_BAYER_GRBG
            format.name.startsWith("BAYER_GB") -> COLOR_BAYER_GBRG
            format.name.startsWith("BAYER_BG") -> COLOR_BAYER_BGGR
            else -> COLOR_MONO
        }
        packedFrameSize = packedBytesPerFrame(w, h, nativeBits)
        frameCount = 0
        totalBytesWritten = 0
        timestamps.clear()
        packBuffer = ByteArray(packedFrameSize)

        raf = RandomAccessFile(file, "rw")
        writeHeader(cameraName, filterName)
    }

    private var upscaleBuf: ByteArray? = null

    fun writeFrame(frame: FrameData) {
        val r = raf ?: return
        val buf = packBuffer ?: return

        if (nativeBits <= 8) {
            val writeLen = packedFrameSize.coerceAtMost(frame.data.size)
            r.write(frame.data, 0, writeLen)
        } else {
            val frameBpp = frame.pixelFormat.bytesPerPixel
            val src: ByteArray
            if (frameBpp < 2) {
                val pixels = width * height
                var ub = upscaleBuf
                if (ub == null || ub.size < pixels * 2) {
                    ub = ByteArray(pixels * 2)
                    upscaleBuf = ub
                }
                val shift = nativeBits - 8
                for (i in 0 until pixels) {
                    if (i < frame.data.size) {
                        val v = (frame.data[i].toInt() and 0xFF) shl shift
                        ub[i * 2] = (v and 0xFF).toByte()
                        ub[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    } else {
                        ub[i * 2] = 0
                        ub[i * 2 + 1] = 0
                    }
                }
                src = ub
            } else {
                src = frame.data
            }
            packPixels(src, buf, width * height, nativeBits)
            r.write(buf, 0, packedFrameSize)
        }

        timestamps.add(instantToFileTime(Instant.now()))
        frameCount++
        totalBytesWritten += packedFrameSize
    }

    fun close() {
        val r = raf ?: return
        writeTimestampTrailer(r)
        updateFrameCount(r)
        r.close()
        raf = null
        packBuffer = null
    }

    val isOpen: Boolean get() = raf != null
    val currentFrameCount: Int get() = frameCount

    private fun packPixels(src: ByteArray, dst: ByteArray, pixelCount: Int, bits: Int) {
        when (bits) {
            10 -> pack10bit(src, dst, pixelCount)
            12 -> pack12bit(src, dst, pixelCount)
            14 -> pack14bit(src, dst, pixelCount)
            else -> System.arraycopy(src, 0, dst, 0, dst.size.coerceAtMost(src.size))
        }
    }

    private fun pack10bit(src: ByteArray, dst: ByteArray, pixelCount: Int) {
        var di = 0
        var si = 0
        val groups = pixelCount / 4
        for (g in 0 until groups) {
            val p0 = readLE16(src, si); si += 2
            val p1 = readLE16(src, si); si += 2
            val p2 = readLE16(src, si); si += 2
            val p3 = readLE16(src, si); si += 2
            dst[di++] = (p0 and 0xFF).toByte()
            dst[di++] = (((p0 shr 8) and 0x03) or ((p1 and 0x3F) shl 2)).toByte()
            dst[di++] = (((p1 shr 6) and 0x0F) or ((p2 and 0x0F) shl 4)).toByte()
            dst[di++] = (((p2 shr 4) and 0x3F) or ((p3 and 0x03) shl 6)).toByte()
            dst[di++] = ((p3 shr 2) and 0xFF).toByte()
        }
        val rem = pixelCount % 4
        if (rem > 0) {
            var accum = 0L
            var bits = 0
            for (r in 0 until rem) {
                val p = readLE16(src, si); si += 2
                accum = accum or ((p.toLong() and 0x3FF) shl bits)
                bits += 10
            }
            while (bits > 0) {
                dst[di++] = (accum and 0xFF).toByte()
                accum = accum shr 8
                bits -= 8
            }
        }
    }

    private fun pack12bit(src: ByteArray, dst: ByteArray, pixelCount: Int) {
        var di = 0
        var si = 0
        val pairs = pixelCount / 2
        for (p in 0 until pairs) {
            val p0 = readLE16(src, si); si += 2
            val p1 = readLE16(src, si); si += 2
            dst[di++] = (p0 and 0xFF).toByte()
            dst[di++] = (((p0 shr 8) and 0x0F) or ((p1 and 0x0F) shl 4)).toByte()
            dst[di++] = ((p1 shr 4) and 0xFF).toByte()
        }
        if (pixelCount % 2 != 0) {
            val p0 = readLE16(src, si)
            dst[di++] = (p0 and 0xFF).toByte()
            dst[di++] = ((p0 shr 8) and 0x0F).toByte()
        }
    }

    private fun pack14bit(src: ByteArray, dst: ByteArray, pixelCount: Int) {
        var di = 0
        var si = 0
        val groups = pixelCount / 4
        for (g in 0 until groups) {
            val p0 = readLE16(src, si); si += 2
            val p1 = readLE16(src, si); si += 2
            val p2 = readLE16(src, si); si += 2
            val p3 = readLE16(src, si); si += 2
            var accum = (p0.toLong() and 0x3FFF) or
                    ((p1.toLong() and 0x3FFF) shl 14) or
                    ((p2.toLong() and 0x3FFF) shl 28) or
                    ((p3.toLong() and 0x3FFF) shl 42)
            for (b in 0 until 7) {
                dst[di++] = (accum and 0xFF).toByte()
                accum = accum shr 8
            }
        }
        val rem = pixelCount % 4
        if (rem > 0) {
            var accum = 0L
            var totalBits = 0
            for (r in 0 until rem) {
                val p = readLE16(src, si); si += 2
                accum = accum or ((p.toLong() and 0x3FFF) shl totalBits)
                totalBits += 14
            }
            while (totalBits > 0) {
                dst[di++] = (accum and 0xFF).toByte()
                accum = accum shr 8
                totalBits -= 8
            }
        }
    }

    private fun readLE16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeHeader(cameraName: String?, filterName: String?) {
        val r = raf ?: return
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        val idBytes = FILE_ID.toByteArray(Charsets.US_ASCII)
        buf.put(idBytes, 0, 14.coerceAtMost(idBytes.size))
        buf.position(14)

        buf.putInt(0)
        buf.putInt(colorId)
        buf.putInt(LITTLE_ENDIAN_FLAG)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(nativeBits)
        buf.putInt(0)

        val observerBytes = "MobileObservatory".toByteArray(Charsets.US_ASCII)
        buf.position(42)
        buf.put(observerBytes, 0, observerBytes.size.coerceAtMost(40))
        buf.position(82)
        val instrBytes = (cameraName ?: "Camera").toByteArray(Charsets.US_ASCII)
        buf.put(instrBytes, 0, instrBytes.size.coerceAtMost(40))
        buf.position(122)
        val telStr = filterName ?: ""
        val telBytes = telStr.toByteArray(Charsets.US_ASCII)
        buf.put(telBytes, 0, telBytes.size.coerceAtMost(40))

        buf.position(162)
        val now = instantToFileTime(Instant.now())
        buf.putLong(now)
        buf.putLong(now)

        r.seek(0)
        r.write(buf.array())
    }

    private fun writeTimestampTrailer(r: RandomAccessFile) {
        val pos = HEADER_SIZE.toLong() + packedFrameSize.toLong() * frameCount
        r.seek(pos)
        val buf = ByteBuffer.allocate(TIMESTAMP_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        for (ts in timestamps) {
            buf.clear()
            buf.putLong(ts)
            r.write(buf.array())
        }
    }

    private fun updateFrameCount(r: RandomAccessFile) {
        r.seek(38)
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(frameCount)
        r.write(buf.array())
    }

    private fun instantToFileTime(instant: Instant): Long {
        val epochTicks = 621355968000000000L
        val utc = instant.atOffset(ZoneOffset.UTC)
        return utc.toInstant().toEpochMilli() * 10000L + epochTicks
    }
}
