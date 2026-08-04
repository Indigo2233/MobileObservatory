package com.indigo.mobileobservatory.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.indigo.mobileobservatory.camera.FrameData
import java.io.File
import java.nio.ByteBuffer

class Mp4Writer(private val file: File) {

    companion object {
        private const val TAG = "Mp4Writer"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 1
        private const val DEFAULT_BITRATE_FACTOR = 8
        private const val DRAIN_TIMEOUT_US = 5_000_000L
    }

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0L
    private var width = 0
    private var height = 0
    private var codecW = 0
    private var codecH = 0
    private var stride = 0
    private var sliceHeight = 0
    private var fps = 10

    var totalBytesWritten: Long = 0
        private set
    var currentFrameCount: Int = 0
        private set
    var isOpen: Boolean = false
        private set

    fun open(w: Int, h: Int, frameRate: Int = 10) {
        width = w
        height = h
        fps = frameRate.coerceIn(1, 60)
        frameIndex = 0
        totalBytesWritten = 0
        currentFrameCount = 0

        codecW = (w + 15) and 0x7FFFFFF0
        codecH = (h + 15) and 0x7FFFFFF0

        val format = MediaFormat.createVideoFormat(MIME, codecW, codecH).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
            setInteger(MediaFormat.KEY_BIT_RATE, codecW * codecH * DEFAULT_BITRATE_FACTOR)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        encoder = MediaCodec.createEncoderByType(MIME).also { codec ->
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val inputFmt = codec.inputFormat
            stride = inputFmt.getIntegerSafe(MediaFormat.KEY_STRIDE, codecW)
            sliceHeight = inputFmt.getIntegerSafe(MediaFormat.KEY_SLICE_HEIGHT, codecH)
            if (stride < codecW) stride = codecW
            if (sliceHeight < codecH) sliceHeight = codecH
        }

        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        isOpen = true
        Log.i(TAG, "Opened ${w}x${h} (codec ${codecW}x${codecH}, stride=$stride, slice=$sliceHeight) @ ${fps}fps -> ${file.name}")
    }

    var wbRedGain = 1.0f
    var wbGreenGain = 1.0f
    var wbBlueGain = 1.0f

    fun writeFrame(frame: FrameData) {
        val codec = encoder ?: return
        if (!isOpen) return

        try {
            val inputIdx = codec.dequeueInputBuffer(10_000)
            if (inputIdx < 0) {
                drainEncoder(false)
                val retryIdx = codec.dequeueInputBuffer(10_000)
                if (retryIdx < 0) {
                    Log.w(TAG, "No input buffer available, skipping frame $frameIndex")
                    return
                }
                val inputBuf = codec.getInputBuffer(retryIdx) ?: return
                val dataSize = fillNv12(inputBuf, frame)
                if (dataSize == 0) {
                    codec.queueInputBuffer(retryIdx, 0, 0, 0, 0)
                    return
                }
                val presentationTimeUs = (frameIndex * 1_000_000L) / fps
                codec.queueInputBuffer(retryIdx, 0, dataSize, presentationTimeUs, 0)
            } else {
                val inputBuf = codec.getInputBuffer(inputIdx) ?: return
                val dataSize = fillNv12(inputBuf, frame)
                if (dataSize == 0) {
                    codec.queueInputBuffer(inputIdx, 0, 0, 0, 0)
                    return
                }
                val presentationTimeUs = (frameIndex * 1_000_000L) / fps
                codec.queueInputBuffer(inputIdx, 0, dataSize, presentationTimeUs, 0)
            }
            frameIndex++
            drainEncoder(false)
            currentFrameCount++
        } catch (e: Throwable) {
            Log.e(TAG, "writeFrame error (frame $frameIndex)", e)
        }
    }

    fun close() {
        if (!isOpen) return
        try {
            val codec = encoder
            if (codec != null) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainEncoder(true)
                codec.stop()
                codec.release()
            }
            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "close error", e)
        }
        encoder = null
        muxer = null
        muxerStarted = false
        isOpen = false
        Log.i(TAG, "Closed, $currentFrameCount frames, ${totalBytesWritten} bytes")
    }

    private fun fillNv12(buf: ByteBuffer, frame: FrameData): Int {
        buf.clear()

        val yPlaneSize = stride * sliceHeight
        val uvPlaneSize = stride * ((sliceHeight + 1) / 2)
        val needed = yPlaneSize + uvPlaneSize
        if (buf.capacity() < needed) {
            Log.w(TAG, "Input buffer too small: ${buf.capacity()} < $needed")
            return 0
        }

        if (frame.pixelFormat.isBayer) {
            fillNv12Bayer(buf, frame)
        } else {
            fillNv12Mono(buf, frame)
        }

        buf.position(0)
        buf.limit(needed)
        return needed
    }

    private fun fillNv12Mono(buf: ByteBuffer, frame: FrameData) {
        val src = frame.data
        val isHigh = frame.pixelFormat.isHighBit
        val shift = if (isHigh) (frame.pixelFormat.nativeBits - 8).coerceAtLeast(0) else 0

        val yRow = ByteArray(stride)
        for (y in 0 until sliceHeight) {
            if (y < height) {
                if (isHigh) {
                    for (x in 0 until width.coerceAtMost(stride)) {
                        val idx = (y * width + x) * 2
                        if (idx + 1 < src.size) {
                            val lo = src[idx].toInt() and 0xFF
                            val hi = src[idx + 1].toInt() and 0xFF
                            yRow[x] = ((lo or (hi shl 8)) shr shift).coerceIn(0, 255).toByte()
                        } else {
                            yRow[x] = 0
                        }
                    }
                } else {
                    val rowStart = y * width
                    val copyLen = width.coerceAtMost(stride).coerceAtMost(src.size - rowStart)
                    if (copyLen > 0) {
                        System.arraycopy(src, rowStart, yRow, 0, copyLen)
                    }
                }
                for (x in width until stride) yRow[x] = 0
            } else {
                yRow.fill(0)
            }
            buf.put(yRow, 0, stride)
        }

        val neutral: Byte = 128.toByte()
        val uvRow = ByteArray(stride)
        uvRow.fill(neutral)
        for (y in 0 until (sliceHeight + 1) / 2) {
            buf.put(uvRow, 0, stride)
        }
    }

    private fun fillNv12Bayer(buf: ByteBuffer, frame: FrameData) {
        val src = frame.data
        val is10 = frame.pixelFormat.is10bit
        val shf = if (is10) (frame.pixelFormat.nativeBits - 8).coerceAtLeast(0) else 0

        val pfName = frame.pixelFormat.name
        val isRG = pfName.startsWith("BAYER_RG")
        val isGR = pfName.startsWith("BAYER_GR")
        val isGB = pfName.startsWith("BAYER_GB")
        val rX: Int; val rY: Int
        when {
            isRG -> { rX = 0; rY = 0 }
            isGR -> { rX = 1; rY = 0 }
            isGB -> { rX = 0; rY = 1 }
            else -> { rX = 1; rY = 1 }
        }

        val w = width; val h = height
        val grey8 = ByteArray(w * h)
        if (is10) {
            for (i in 0 until w * h) {
                val idx = i * 2
                if (idx + 1 < src.size)
                    grey8[i] = (((src[idx].toInt() and 0xFF) or ((src[idx + 1].toInt() and 0xFF) shl 8)) shr shf).coerceIn(0, 255).toByte()
            }
        } else {
            val copyLen = (w * h).coerceAtMost(src.size)
            System.arraycopy(src, 0, grey8, 0, copyLen)
        }

        val wR = wbRedGain; val wG = wbGreenGain; val wB = wbBlueGain

        val uvHeight = (sliceHeight + 1) / 2
        val uvPlane = ByteArray(stride * uvHeight)
        uvPlane.fill(128.toByte())

        val yRow = ByteArray(stride)
        for (y in 0 until sliceHeight) {
            yRow.fill(0)
            if (y < h) {
                val yEven = (y % 2 == 0)
                for (x in 0 until stride.coerceAtMost(w)) {
                    val bx = x % 2; val by = y % 2
                    val r: Int; val g: Int; val b: Int
                    val cur = grey8[y * w + x].toInt() and 0xFF

                    val xm = if (x > 0) x - 1 else 0
                    val xp = if (x < w - 1) x + 1 else w - 1
                    val ym = if (y > 0) y - 1 else 0
                    val yp = if (y < h - 1) y + 1 else h - 1

                    when {
                        bx == rX && by == rY -> {
                            r = cur
                            g = ((grey8[y * w + xm].toInt() and 0xFF) + (grey8[y * w + xp].toInt() and 0xFF) +
                                    (grey8[ym * w + x].toInt() and 0xFF) + (grey8[yp * w + x].toInt() and 0xFF)) shr 2
                            b = ((grey8[ym * w + xm].toInt() and 0xFF) + (grey8[ym * w + xp].toInt() and 0xFF) +
                                    (grey8[yp * w + xm].toInt() and 0xFF) + (grey8[yp * w + xp].toInt() and 0xFF)) shr 2
                        }
                        bx != rX && by != rY -> {
                            b = cur
                            g = ((grey8[y * w + xm].toInt() and 0xFF) + (grey8[y * w + xp].toInt() and 0xFF) +
                                    (grey8[ym * w + x].toInt() and 0xFF) + (grey8[yp * w + x].toInt() and 0xFF)) shr 2
                            r = ((grey8[ym * w + xm].toInt() and 0xFF) + (grey8[ym * w + xp].toInt() and 0xFF) +
                                    (grey8[yp * w + xm].toInt() and 0xFF) + (grey8[yp * w + xp].toInt() and 0xFF)) shr 2
                        }
                        else -> {
                            g = cur
                            if (by == rY) {
                                r = ((grey8[y * w + xm].toInt() and 0xFF) + (grey8[y * w + xp].toInt() and 0xFF)) shr 1
                                b = ((grey8[ym * w + x].toInt() and 0xFF) + (grey8[yp * w + x].toInt() and 0xFF)) shr 1
                            } else {
                                b = ((grey8[y * w + xm].toInt() and 0xFF) + (grey8[y * w + xp].toInt() and 0xFF)) shr 1
                                r = ((grey8[ym * w + x].toInt() and 0xFF) + (grey8[yp * w + x].toInt() and 0xFF)) shr 1
                            }
                        }
                    }
                    val r8 = (r * wR).toInt().coerceIn(0, 255)
                    val g8 = (g * wG).toInt().coerceIn(0, 255)
                    val b8 = (b * wB).toInt().coerceIn(0, 255)
                    yRow[x] = (((66 * r8 + 129 * g8 + 25 * b8 + 128) shr 8) + 16).coerceIn(16, 235).toByte()

                    if (yEven && x % 2 == 0) {
                        val u = (((-38 * r8 - 74 * g8 + 112 * b8 + 128) shr 8) + 128).coerceIn(16, 240)
                        val v = (((112 * r8 - 94 * g8 - 18 * b8 + 128) shr 8) + 128).coerceIn(16, 240)
                        val uvOff = (y / 2) * stride + x
                        if (uvOff + 1 < uvPlane.size) {
                            uvPlane[uvOff] = u.toByte()
                            uvPlane[uvOff + 1] = v.toByte()
                        }
                    }
                }
            }
            buf.put(yRow, 0, stride)
        }
        buf.put(uvPlane, 0, uvPlane.size)
    }

    private fun getPixel(data: ByteArray, x: Int, y: Int, w: Int, is10: Boolean): Int {
        return if (is10) {
            val idx = (y * w + x) * 2
            if (idx + 1 >= data.size) 0
            else (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
        } else {
            val idx = y * w + x
            if (idx >= data.size) 0 else data[idx].toInt() and 0xFF
        }
    }

    private fun avgN(d: ByteArray, x: Int, y: Int, w: Int, h: Int, is10: Boolean): Int {
        var s = 0; var c = 0
        if (x > 0)     { s += getPixel(d, x - 1, y, w, is10); c++ }
        if (x < w - 1) { s += getPixel(d, x + 1, y, w, is10); c++ }
        if (y > 0)     { s += getPixel(d, x, y - 1, w, is10); c++ }
        if (y < h - 1) { s += getPixel(d, x, y + 1, w, is10); c++ }
        return if (c > 0) s / c else 0
    }

    private fun avgD(d: ByteArray, x: Int, y: Int, w: Int, h: Int, is10: Boolean): Int {
        var s = 0; var c = 0
        if (x > 0 && y > 0)         { s += getPixel(d, x - 1, y - 1, w, is10); c++ }
        if (x < w - 1 && y > 0)     { s += getPixel(d, x + 1, y - 1, w, is10); c++ }
        if (x > 0 && y < h - 1)     { s += getPixel(d, x - 1, y + 1, w, is10); c++ }
        if (x < w - 1 && y < h - 1) { s += getPixel(d, x + 1, y + 1, w, is10); c++ }
        return if (c > 0) s / c else 0
    }

    private fun avgH(d: ByteArray, x: Int, y: Int, w: Int, is10: Boolean): Int {
        var s = 0; var c = 0
        if (x > 0)     { s += getPixel(d, x - 1, y, w, is10); c++ }
        if (x < w - 1) { s += getPixel(d, x + 1, y, w, is10); c++ }
        return if (c > 0) s / c else 0
    }

    private fun avgV(d: ByteArray, x: Int, y: Int, h: Int, w: Int, is10: Boolean): Int {
        var s = 0; var c = 0
        if (y > 0)     { s += getPixel(d, x, y - 1, w, is10); c++ }
        if (y < h - 1) { s += getPixel(d, x, y + 1, w, is10); c++ }
        return if (c > 0) s / c else 0
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        val mx = muxer ?: return
        val bufInfo = MediaCodec.BufferInfo()
        val deadlineNs = if (endOfStream) System.nanoTime() + DRAIN_TIMEOUT_US * 1000 else 0L

        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufInfo, if (endOfStream) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = mx.addTrack(codec.outputFormat)
                        mx.start()
                        muxerStarted = true
                        Log.i(TAG, "Muxer started, format: ${codec.outputFormat}")
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf == null) {
                        codec.releaseOutputBuffer(outIdx, false)
                    } else {
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufInfo.size = 0
                        }
                        if (bufInfo.size > 0 && muxerStarted) {
                            outBuf.position(bufInfo.offset)
                            outBuf.limit(bufInfo.offset + bufInfo.size)
                            mx.writeSampleData(trackIndex, outBuf, bufInfo)
                            totalBytesWritten += bufInfo.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
                else -> {
                    if (!endOfStream) return
                    if (System.nanoTime() > deadlineNs) {
                        Log.w(TAG, "Drain timeout reached, stopping")
                        return
                    }
                }
            }
        }
    }

    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
        return try { getInteger(key) } catch (_: Exception) { default }
    }
}
