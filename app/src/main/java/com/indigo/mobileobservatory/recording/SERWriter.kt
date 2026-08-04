package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset

class SERWriter(private val file: File) {

    companion object {
        private const val HEADER_SIZE = 178
        private const val FILE_ID = "LUCAM-RECORDER"
        private const val TIMESTAMP_SIZE = 8

        private const val COLOR_MONO = 0
        private const val COLOR_BAYER_RGGB = 8
        private const val COLOR_BAYER_GRBG = 9
        private const val COLOR_BAYER_GBRG = 10
        private const val COLOR_BAYER_BGGR = 11
        private const val LITTLE_ENDIAN_FLAG = 0
    }

    private var raf: RandomAccessFile? = null
    private var frameCount = 0
    private var width = 0
    private var height = 0
    private var pixelDepth = 0
    private var colorId = COLOR_MONO
    private var bytesPerFrame = 0
    private val timestamps = mutableListOf<Long>()
    private var cameraName: String? = null
    private var filterName: String? = null

    var totalBytesWritten: Long = 0
        private set

    fun open(w: Int, h: Int, format: PixelFormat, cameraName: String? = null, filterName: String? = null) {
        this.cameraName = cameraName
        this.filterName = filterName
        width = w
        height = h
        pixelDepth = format.nativeBits.let { if (it <= 8) 8 else it }
        colorId = when {
            format.name.startsWith("BAYER_RG") -> COLOR_BAYER_RGGB
            format.name.startsWith("BAYER_GR") -> COLOR_BAYER_GRBG
            format.name.startsWith("BAYER_GB") -> COLOR_BAYER_GBRG
            format.name.startsWith("BAYER_BG") -> COLOR_BAYER_BGGR
            else -> COLOR_MONO
        }
        val bpp = if (pixelDepth > 8) 2 else 1
        bytesPerFrame = w * h * bpp
        frameCount = 0
        totalBytesWritten = 0
        timestamps.clear()

        raf = RandomAccessFile(file, "rw")
        writeHeader()
    }

    private var upscaleBuf: ByteArray? = null

    fun writeFrame(frame: FrameData) {
        val r = raf ?: return

        if (frame.width != width || frame.height != height) {
            return
        }

        val frameBpp = frame.pixelFormat.bytesPerPixel
        val expectedBpp = if (pixelDepth > 8) 2 else 1

        if (frameBpp < expectedBpp) {
            val shift = pixelDepth - 8
            val pixels = width * height
            var buf = upscaleBuf
            if (buf == null || buf.size < bytesPerFrame) {
                buf = ByteArray(bytesPerFrame)
                upscaleBuf = buf
            }
            for (i in 0 until pixels) {
                if (i < frame.data.size) {
                    val v = (frame.data[i].toInt() and 0xFF) shl shift
                    buf[i * 2] = (v and 0xFF).toByte()
                    buf[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                } else {
                    buf[i * 2] = 0
                    buf[i * 2 + 1] = 0
                }
            }
            r.write(buf, 0, bytesPerFrame)
        } else {
            val actualFrameBytes = width * height * frameBpp
            if (actualFrameBytes < bytesPerFrame) {
                r.write(frame.data, 0, actualFrameBytes)
                val padding = ByteArray(bytesPerFrame - actualFrameBytes)
                r.write(padding)
            } else {
                r.write(frame.data, 0, bytesPerFrame)
            }
        }
        timestamps.add(instantToFileTime(Instant.now()))
        frameCount++
        totalBytesWritten += bytesPerFrame
    }

    fun close() {
        val r = raf ?: return
        writeTimestampTrailer(r)
        updateFrameCount(r)
        r.close()
        raf = null
    }

    val isOpen: Boolean get() = raf != null
    val currentFrameCount: Int get() = frameCount

    private fun writeHeader() {
        val r = raf ?: return
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        val idBytes = FILE_ID.toByteArray(Charsets.US_ASCII)
        buf.put(idBytes)
        buf.position(14)

        buf.putInt(0)                    // LuID
        buf.putInt(colorId)              // ColorID
        buf.putInt(LITTLE_ENDIAN_FLAG)   // LittleEndian
        buf.putInt(width)                // ImageWidth
        buf.putInt(height)               // ImageHeight
        buf.putInt(pixelDepth)           // PixelDepthPerPlane
        buf.putInt(0)                    // FrameCount (updated on close)

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
        buf.putLong(now) // DateTime
        buf.putLong(now) // DateTimeUTC

        r.seek(0)
        r.write(buf.array())
    }

    private fun writeTimestampTrailer(r: RandomAccessFile) {
        val pos = HEADER_SIZE.toLong() + bytesPerFrame.toLong() * frameCount
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
        val ticks = utc.toInstant().toEpochMilli() * 10000L + epochTicks
        return ticks
    }
}
