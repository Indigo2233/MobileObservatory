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
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.util.FileLogger
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

    /** Capture layout frozen when recording starts, separate from display white balance. */
    var sampleTraits: PixelTraits? = null

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
        FileLogger.i(TAG, "Opening source=${w}x${h} fps=$fps file=${file.name}")

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
                    videoCaps.areSizeAndRateSupported(tw, th, fps.toDouble())
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
                        FileLogger.i(
                            TAG,
                            "Opened ${w}x${h} as ${codecW}x${codecH} via ${info.name} " +
                                "color=0x${color.toString(16)} bitrate=$bitrate @ ${fps}fps -> ${file.name}"
                        )
                        return
                    }
                } catch (t: Throwable) {
                    errors += "${info.name}/0x${color.toString(16)}: ${t.message}"
                    FileLogger.w(TAG, "Configure failed codec=${info.name} size=${size.first}x${size.second} " +
                        "color=0x${color.toString(16)} fps=$fps: ${t.javaClass.simpleName}: ${t.message}")
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
            val nv12 = converter.convert(frame, codecW, codecH, sampleTraits)
            // Query capacity first. getInputBuffer AFTER getInputImage invalidates that image.
            val inputCapacity = codec.getInputBuffer(inputIdx)?.capacity()
            val image = try {
                codec.getInputImage(inputIdx)
            } catch (_: Throwable) {
                null
            }
            val dataSize = if (image != null) {
                if (frameIndex == 0L) FileLogger.i(TAG, "Input image=${image.width}x${image.height} " +
                    "capacity=$inputCapacity planes=" + image.planes.joinToString { plane ->
                        "row=${plane.rowStride},pixel=${plane.pixelStride},bytes=${plane.buffer.remaining()}"
                    })
                writeNv12ToImage(image, nv12)
                inputCapacity?.coerceAtLeast(1)
                    ?: (codecW * codecH + codecW * ((codecH + 1) / 2))
            } else {
                // getInputImage may also invalidate the previous ByteBuffer; reacquire it.
                val inputBuf = checkNotNull(codec.getInputBuffer(inputIdx)) { "Encoder input unavailable" }
                if (frameIndex == 0L) FileLogger.i(TAG, "Input buffer capacity=${inputBuf.capacity()} " +
                    "stride=$stride slice=$sliceHeight color=0x${colorFormat.toString(16)}")
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
            FileLogger.e(TAG, "writeFrame failed at frame=$frameIndex source=${frame.width}x${frame.height} " +
                "format=${frame.pixelFormat} output=${codecW}x${codecH}", e)
            throw e
        }
    }

    fun close() {
        if (!isOpen && encoder == null && muxer == null) return
        var failure: Throwable? = null
        try {
            val codec = encoder
            if (codec != null) {
                val deadline = System.nanoTime() + DRAIN_TIMEOUT_US * 1000
                var idx = codec.dequeueInputBuffer(10_000)
                while (idx < 0 && System.nanoTime() < deadline) {
                    drainEncoder(false)
                    idx = codec.dequeueInputBuffer(10_000)
                }
                check(idx >= 0) { "MP4 encoder could not finish its input" }
                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drainEncoder(true)
                check(currentFrameCount > 0 && muxerStarted && totalBytesWritten > 0) { "MP4 contains no encoded frames" }
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            releaseResources()?.let { error -> if (failure == null) failure = error else failure!!.addSuppressed(error) }
            FileLogger.i(TAG, "Closed success=${failure == null}, $currentFrameCount frames, ${totalBytesWritten} bytes")
        }
        failure?.let { FileLogger.e(TAG, "Recording finalization failed", it); throw it }
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
        check(packedSize <= remaining) { "Encoder input buffer is too small" }
        buf.put(nv12Scratch, 0, packedSize)
        buf.position(0)
        buf.limit(packedSize)
        return packedSize
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

    private fun writeNv12ToImage(image: Image, nv12: Nv12Frame) {
        val planes = image.planes
        check(planes.size == 3) { "Encoder did not provide three YUV planes" }
        Mp4InputPlanes.copy(nv12.y, 0, nv12.width, 1, planes[0].buffer,
            planes[0].rowStride, planes[0].pixelStride, nv12.width, nv12.height)
        // U and V can overlap with either UV or VU ordering. Write each logical plane
        // through its own stride/offset; relative ByteBuffer positions cannot identify NV12.
        for (channel in 0..1) {
            val plane = planes[channel + 1]
            Mp4InputPlanes.copy(nv12.uv, channel, nv12.width, 2, plane.buffer,
                plane.rowStride, plane.pixelStride, nv12.width / 2, nv12.height / 2)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = checkNotNull(encoder)
        val mx = checkNotNull(muxer)
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_US * 1000
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "MP4 output format changed during recording" }
                    trackIndex = mx.addTrack(codec.outputFormat)
                    mx.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted) { "MP4 output arrived before its track" }
                            val output = checkNotNull(codec.getOutputBuffer(index))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            mx.writeSampleData(trackIndex, output, info)
                            totalBytesWritten += info.size
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
                else -> {
                    if (!endOfStream) return
                    check(System.nanoTime() < deadline) { "MP4 encoder drain timed out" }
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

    /** Failed configuration attempts are disposable; completed recordings propagate cleanup failures. */
    private fun releaseQuietly() { releaseResources() }

    private fun releaseResources(): Throwable? {
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() }
            catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        }
        release { encoder?.stop() }
        release { encoder?.release() }
        encoder = null
        release { if (muxerStarted) muxer?.stop() }
        release { muxer?.release() }
        muxer = null
        muxerStarted = false
        isOpen = false
        return failure
    }

    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
        return try {
            getInteger(key)
        } catch (_: Exception) {
            default
        }
    }
}
