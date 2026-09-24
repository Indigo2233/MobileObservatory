package com.indigo.mobileobservatory.recording

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import com.indigo.mobileobservatory.camera.FrameData
import java.io.File
import java.nio.ByteBuffer

class Mp4Writer(private val file: File) {

    companion object {
        private const val TAG = "Mp4Writer"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 1
        private const val DRAIN_TIMEOUT_US = 5_000_000L
        private const val COLOR_QCOM_YUV420_SEMIPLANAR = 0x7FA30C00
        private const val COLOR_TI_YUV420_PACKED_SEMIPLANAR = 0x7F000100
    }

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0L
    private var codecW = 0
    private var codecH = 0
    private var stride = 0
    private var sliceHeight = 0
    private var fps = 10
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private val converter = Mp4YuvConverter()
    private var nv12Scratch: ByteArray = ByteArray(0)

    var totalBytesWritten: Long = 0
        private set
    var currentFrameCount: Int = 0
        private set
    var isOpen: Boolean = false
        private set

    var wbRedGain: Float
        get() = converter.wbRedGain
        set(value) { converter.wbRedGain = value }
    var wbGreenGain: Float
        get() = converter.wbGreenGain
        set(value) { converter.wbGreenGain = value }
    var wbBlueGain: Float
        get() = converter.wbBlueGain
        set(value) { converter.wbBlueGain = value }

    fun open(w: Int, h: Int, frameRate: Int = 10) {
        releaseQuietly()
        fps = frameRate.coerceIn(1, 60)
        frameIndex = 0
        totalBytesWritten = 0
        currentFrameCount = 0
        muxerStarted = false
        trackIndex = -1

        val errors = mutableListOf<String>()
        val candidates = listAvcEncoders()
        if (candidates.isEmpty()) {
            error("No H.264 encoder available on this phone")
        }

        for (info in candidates) {
            val caps = try {
                info.getCapabilitiesForType(MIME)
            } catch (t: Throwable) {
                errors += "${info.name}: ${t.message}"
                continue
            }
            val videoCaps = caps.videoCapabilities ?: continue
            val size = Mp4EncoderSupport.fitSize(w, h) { tw, th ->
                try {
                    videoCaps.isSizeSupported(tw, th)
                } catch (_: Throwable) {
                    false
                }
            }
            if (size == null) {
                errors += "${info.name}: size ${w}x${h} unsupported"
                continue
            }
            val colors = preferredColorFormats(caps.colorFormats)
            if (colors.isEmpty()) {
                errors += "${info.name}: no usable YUV color format"
                continue
            }
            val brRange = videoCaps.bitrateRange
            val bitrate = Mp4EncoderSupport.bitrate(
                width = size.first,
                height = size.second,
                fps = fps,
                minBitrate = brRange.lower.coerceAtLeast(1),
                maxBitrate = if (brRange.upper > 0) brRange.upper else Mp4EncoderSupport.MAX_BITRATE
            )
            for (color in colors) {
                try {
                    if (tryOpen(info.name, size.first, size.second, color, bitrate)) {
                        isOpen = true
                        Log.i(
                            TAG,
                            "Opened ${w}x${h} as ${codecW}x${codecH} via ${info.name} " +
                                "color=0x${color.toString(16)} bitrate=$bitrate @ ${fps}fps -> ${file.name}"
                        )
                        return
                    }
                } catch (t: Throwable) {
                    errors += "${info.name}/0x${color.toString(16)}: ${t.message}"
                    releaseQuietly()
                }
            }
        }
        error("No H.264 encoder for ${w}x${h}: ${errors.take(4).joinToString("; ")}")
    }

    fun writeFrame(frame: FrameData) {
        val codec = encoder ?: return
        if (!isOpen) return
        try {
            val inputIdx = dequeueInput(codec)
            if (inputIdx < 0) {
                Log.w(TAG, "No input buffer available, skipping frame $frameIndex")
                return
            }
            val nv12 = converter.convert(frame, codecW, codecH)
            val image = try {
                codec.getInputImage(inputIdx)
            } catch (_: Throwable) {
                null
            }
            val inputBuf = codec.getInputBuffer(inputIdx)
            val dataSize = if (image != null) {
                writeNv12ToImage(image, nv12)
                inputBuf?.capacity()?.coerceAtLeast(1)
                    ?: (codecW * codecH + codecW * ((codecH + 1) / 2))
            } else {
                if (inputBuf == null) {
                    codec.queueInputBuffer(inputIdx, 0, 0, 0, 0)
                    return
                }
                fillCodecBuffer(inputBuf, nv12)
            }
            if (dataSize <= 0) {
                codec.queueInputBuffer(inputIdx, 0, 0, 0, 0)
                return
            }
            val presentationTimeUs = (frameIndex * 1_000_000L) / fps
            codec.queueInputBuffer(inputIdx, 0, dataSize, presentationTimeUs, 0)
            frameIndex++
            drainEncoder(false)
            currentFrameCount++
        } catch (e: Throwable) {
            Log.e(TAG, "writeFrame error (frame $frameIndex)", e)
        }
    }

    fun close() {
        if (!isOpen && encoder == null && muxer == null) return
        try {
            val codec = encoder
            if (codec != null) {
                try {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    drainEncoder(true)
                } catch (e: Throwable) {
                    Log.w(TAG, "EOS drain failed", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "close error", e)
        } finally {
            releaseQuietly()
            Log.i(TAG, "Closed, $currentFrameCount frames, ${totalBytesWritten} bytes")
        }
    }

    private fun tryOpen(
        codecName: String,
        width: Int,
        height: Int,
        color: Int,
        bitrate: Int
    ): Boolean {
        codecW = width
        codecH = height
        colorFormat = color
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        encoder = codec
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val inputFmt = codec.inputFormat
        stride = inputFmt.getIntegerSafe(MediaFormat.KEY_STRIDE, codecW)
        sliceHeight = inputFmt.getIntegerSafe(MediaFormat.KEY_SLICE_HEIGHT, codecH)
        if (stride < codecW) stride = codecW
        if (sliceHeight < codecH) sliceHeight = codecH
        colorFormat = inputFmt.getIntegerSafe(MediaFormat.KEY_COLOR_FORMAT, color)
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return true
    }

    private fun dequeueInput(codec: MediaCodec): Int {
        var inputIdx = codec.dequeueInputBuffer(10_000)
        if (inputIdx < 0) {
            drainEncoder(false)
            inputIdx = codec.dequeueInputBuffer(10_000)
        }
        return inputIdx
    }

    private fun fillCodecBuffer(buf: ByteBuffer, nv12: Nv12Frame): Int {
        buf.clear()
        val remaining = buf.remaining()
        val packedSize = if (isPlanar(colorFormat)) {
            packI420(nv12, stride, sliceHeight)
        } else {
            packNv12(nv12, stride, sliceHeight)
        }
        val n = packedSize.coerceAtMost(remaining)
        buf.put(nv12Scratch, 0, n)
        buf.position(0)
        buf.limit(n)
        return n
    }

    private fun packNv12(nv12: Nv12Frame, rowStride: Int, slice: Int): Int {
        val yPlaneSize = rowStride * slice
        val uvPlaneSize = rowStride * ((slice + 1) / 2)
        val needed = yPlaneSize + uvPlaneSize
        if (nv12Scratch.size < needed) nv12Scratch = ByteArray(needed)
        val out = nv12Scratch
        var dst = 0
        for (y in 0 until slice) {
            if (y < nv12.height) {
                System.arraycopy(nv12.y, y * nv12.width, out, dst, nv12.width)
                if (rowStride > nv12.width) out.fill(0, dst + nv12.width, dst + rowStride)
            } else {
                out.fill(0, dst, dst + rowStride)
            }
            dst += rowStride
        }
        val uvH = (slice + 1) / 2
        val srcUvH = (nv12.height + 1) / 2
        for (y in 0 until uvH) {
            if (y < srcUvH) {
                System.arraycopy(nv12.uv, y * nv12.width, out, dst, nv12.width)
                if (rowStride > nv12.width) out.fill(128.toByte(), dst + nv12.width, dst + rowStride)
            } else {
                out.fill(128.toByte(), dst, dst + rowStride)
            }
            dst += rowStride
        }
        return needed
    }

    private fun packI420(nv12: Nv12Frame, rowStride: Int, slice: Int): Int {
        val yPlaneSize = rowStride * slice
        val chromaH = (slice + 1) / 2
        val chromaStride = (rowStride + 1) / 2
        val chromaSize = chromaStride * chromaH
        val needed = yPlaneSize + chromaSize * 2
        if (nv12Scratch.size < needed) nv12Scratch = ByteArray(needed)
        val out = nv12Scratch
        var dst = 0
        for (y in 0 until slice) {
            if (y < nv12.height) {
                System.arraycopy(nv12.y, y * nv12.width, out, dst, nv12.width)
                if (rowStride > nv12.width) out.fill(0, dst + nv12.width, dst + rowStride)
            } else {
                out.fill(0, dst, dst + rowStride)
            }
            dst += rowStride
        }
        val srcUvH = (nv12.height + 1) / 2
        for (plane in 0..1) {
            for (y in 0 until chromaH) {
                for (x in 0 until chromaStride) {
                    val srcX = (x * 2).coerceAtMost(nv12.width - 2)
                    val value = if (y < srcUvH && srcX + plane < nv12.uv.size) {
                        nv12.uv[y * nv12.width + srcX + plane]
                    } else {
                        128.toByte()
                    }
                    out[dst + y * chromaStride + x] = value
                }
            }
            dst += chromaSize
        }
        return needed
    }

    private fun writeNv12ToImage(image: Image, nv12: Nv12Frame): Int {
        val planes = image.planes
        if (planes.size < 3) return 0
        copyY(planes[0], nv12)
        val u = planes[1]
        val v = planes[2]
        if (u.pixelStride == 2 && v.pixelStride == 2) {
            if (isNv12(u, v)) {
                copyInterleavedUv(u, nv12, swapVu = false)
            } else {
                copyInterleavedUv(u, nv12, swapVu = true)
            }
        } else {
            copyPlanarChroma(u, nv12, uPlane = true)
            copyPlanarChroma(v, nv12, uPlane = false)
        }
        return 1
    }

    private fun copyY(plane: Image.Plane, nv12: Nv12Frame) {
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val row = if (pixelStride == 1 && rowStride == nv12.width) null else ByteArray(rowStride)
        for (y in 0 until nv12.height) {
            val srcOff = y * nv12.width
            if (row == null) {
                val limit = buf.remaining().coerceAtMost(nv12.width)
                if (limit > 0) buf.put(nv12.y, srcOff, limit)
            } else {
                if (pixelStride == 1) {
                    System.arraycopy(nv12.y, srcOff, row, 0, nv12.width.coerceAtMost(row.size))
                    if (row.size > nv12.width) row.fill(0, nv12.width, row.size)
                } else {
                    row.fill(0)
                    var x = 0
                    while (x < nv12.width && x * pixelStride < row.size) {
                        row[x * pixelStride] = nv12.y[srcOff + x]
                        x++
                    }
                }
                val limit = buf.remaining().coerceAtMost(row.size)
                if (limit > 0) buf.put(row, 0, limit)
            }
        }
    }

    private fun copyInterleavedUv(plane: Image.Plane, nv12: Nv12Frame, swapVu: Boolean) {
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val uvH = (nv12.height + 1) / 2
        val row = ByteArray(rowStride)
        for (y in 0 until uvH) {
            row.fill(128.toByte())
            val srcOff = y * nv12.width
            var x = 0
            while (x + 1 < nv12.width && x + 1 < row.size) {
                val u = nv12.uv[srcOff + x]
                val v = nv12.uv[srcOff + x + 1]
                if (swapVu) {
                    row[x] = v
                    row[x + 1] = u
                } else {
                    row[x] = u
                    row[x + 1] = v
                }
                x += 2
            }
            val limit = buf.remaining().coerceAtMost(row.size)
            if (limit > 0) buf.put(row, 0, limit)
        }
    }

    private fun copyPlanarChroma(plane: Image.Plane, nv12: Nv12Frame, uPlane: Boolean) {
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val uvH = (nv12.height + 1) / 2
        val chromaW = (nv12.width + 1) / 2
        val row = ByteArray(rowStride)
        val chromaOffset = if (uPlane) 0 else 1
        for (y in 0 until uvH) {
            row.fill(128.toByte())
            val srcOff = y * nv12.width
            for (x in 0 until chromaW) {
                val dst = x * pixelStride
                if (dst < row.size) {
                    row[dst] = nv12.uv[srcOff + x * 2 + chromaOffset]
                }
            }
            val limit = buf.remaining().coerceAtMost(row.size)
            if (limit > 0) buf.put(row, 0, limit)
        }
    }

    private fun isNv12(u: Image.Plane, v: Image.Plane): Boolean {
        return try {
            val uBuf = u.buffer.duplicate()
            val vBuf = v.buffer.duplicate()
            if (uBuf.capacity() <= 1 || vBuf.capacity() <= 0) return true
            uBuf.position() <= vBuf.position()
        } catch (_: Throwable) {
            true
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        val mx = muxer ?: return
        val bufInfo = MediaCodec.BufferInfo()
        val deadlineNs = if (endOfStream) System.nanoTime() + DRAIN_TIMEOUT_US * 1000 else 0L

        while (true) {
            val outIdx = try {
                codec.dequeueOutputBuffer(bufInfo, if (endOfStream) 10_000 else 0)
            } catch (t: Throwable) {
                Log.e(TAG, "dequeueOutputBuffer failed", t)
                return
            }
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
                            try {
                                mx.writeSampleData(trackIndex, outBuf, bufInfo)
                                totalBytesWritten += bufInfo.size
                            } catch (t: Throwable) {
                                Log.e(TAG, "muxer write failed", t)
                            }
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

    private fun listAvcEncoders(): List<MediaCodecInfo> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hardware = mutableListOf<MediaCodecInfo>()
        val software = mutableListOf<MediaCodecInfo>()
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }) continue
            if (isSoftwareEncoder(info)) software += info else hardware += info
        }
        return hardware + software
    }

    private fun isSoftwareEncoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (info.isSoftwareOnly) return true
            } catch (_: Throwable) {
            }
        }
        val name = info.name
        return name.startsWith("OMX.google.", ignoreCase = true) ||
            name.startsWith("c2.android.", ignoreCase = true) ||
            name.startsWith("c2.google.", ignoreCase = true)
    }

    private fun preferredColorFormats(supported: IntArray): List<Int> {
        val set = supported.toSet()
        val preferred = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            COLOR_QCOM_YUV420_SEMIPLANAR,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
            COLOR_TI_YUV420_PACKED_SEMIPLANAR,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )
        val ordered = preferred.filter { it in set }.toMutableList()
        if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible !in ordered) {
            ordered.add(0, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        return ordered.distinct()
    }

    private fun isPlanar(color: Int): Boolean {
        return color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    }

    private fun releaseQuietly() {
        try {
            encoder?.stop()
        } catch (_: Throwable) {
        }
        try {
            encoder?.release()
        } catch (_: Throwable) {
        }
        encoder = null
        try {
            if (muxerStarted) muxer?.stop()
        } catch (_: Throwable) {
        }
        try {
            muxer?.release()
        } catch (_: Throwable) {
        }
        muxer = null
        muxerStarted = false
        isOpen = false
    }

    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
        return try {
            getInteger(key)
        } catch (_: Exception) {
            default
        }
    }
}
