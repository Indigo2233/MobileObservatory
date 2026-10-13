package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import java.nio.ByteOrder

enum class SampleAlignment { RIGHT, LEFT, AUTO }
enum class ChannelOrder { RGB, BGR }

/** CFA offsets are in sensor pixels. Set bayerPatternAtOutput for a camera that already reports
 * the output CFA. An even hardware bin cannot be inferred from a sensor CFA and is rejected.
 * AUTO only changes alignment when out-of-range samples prove right alignment impossible;
 * explicitly supplied alignment is preferred for dim, left-aligned cameras.
 */
data class PixelTraits(
    val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    val alignment: SampleAlignment = SampleAlignment.AUTO,
    val validBits: Int? = null,
    val channelOrder: ChannelOrder = ChannelOrder.RGB,
    val roiX: Int = 0,
    val roiY: Int = 0,
    val binning: Int = 1,
    val bayerPatternAtOutput: Boolean = true
)

object LinearPixelDecoder {
    /** Worker-owned normalized sensor output. Borrowed pixels remain valid until the next
     * borrowed decode or [release]. Public decode methods retain independent ownership.
     */
    class SensorWorkspace {
        private var buffer: SensorBuffer? = null
        private var inUse = false
        private var allocations = 0L

        val retainedBytes: Long @Synchronized get() = buffer?.pixels?.size?.toLong()?.times(4L) ?: 0L
        val arrayAllocations: Long @Synchronized get() = allocations

        @Synchronized internal fun acquire(width: Int, height: Int, channels: Int): FloatArray {
            check(!inUse) { "Sensor workspace is already in use" }
            val current = buffer
            if (current == null || current.width != width || current.height != height || current.channels != channels) {
                buffer = null
                buffer = SensorBuffer(width, height, channels,
                    FloatArray(Math.multiplyExact(width * height, channels)))
                allocations++
            }
            inUse = true
            return checkNotNull(buffer).pixels
        }

        @Synchronized internal fun finish() { inUse = false }

        @Synchronized fun release() {
            check(!inUse) { "Cannot release sensor workspace while decoding" }
            buffer = null
        }
    }

    private data class SensorBuffer(val width: Int, val height: Int, val channels: Int,
                                    val pixels: FloatArray)

    fun decode(frame: FrameData, traits: PixelTraits = PixelTraits(),
               maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS,
               algorithm: DemosaicAlgorithm = DemosaicAlgorithm.BILINEAR,
               cancelCheck: () -> Unit = {}): LinearImage =
        demosaicSensor(decodeSensor(frame, traits, maxPixels, cancelCheck), frame.pixelFormat, traits, algorithm, cancelCheck)

    /** Session-owned DDFAPD scratch; input decoding and returned RGB remain independently owned. */
    fun decode(frame: FrameData, traits: PixelTraits = PixelTraits(),
               maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS,
               algorithm: DemosaicAlgorithm = DemosaicAlgorithm.BILINEAR,
               workspace: DdfapdDemosaicer.Workspace,
               cancelCheck: () -> Unit = {}): LinearImage =
        demosaicSensor(decodeSensor(frame, traits, maxPixels, cancelCheck), frame.pixelFormat, traits,
            algorithm, workspace, cancelCheck)

    /** Owned sensor samples before demosaic/resampling: CFA is one channel, RGB is interleaved. */
    fun decodeSensor(frame: FrameData, traits: PixelTraits = PixelTraits(),
                     maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS,
                     cancelCheck: () -> Unit = {}): LinearImage =
        decodeSensorImpl(frame, traits, maxPixels, null, cancelCheck)

    /** Returns worker-owned normalized sensor pixels for immediate calibration/demosaic. */
    internal fun decodeSensorBorrowed(frame: FrameData, traits: PixelTraits = PixelTraits(),
                                      maxPixels: Long = LinearImage.DEFAULT_STACK_PIXELS,
                                      workspace: SensorWorkspace,
                                      cancelCheck: () -> Unit = {}): LinearImage =
        decodeSensorImpl(frame, traits, maxPixels, workspace, cancelCheck)

    private fun decodeSensorImpl(frame: FrameData, traits: PixelTraits, maxPixels: Long,
                                 workspace: SensorWorkspace?,
                                 cancelCheck: () -> Unit): LinearImage {
        cancelCheck()
        require(frame.width > 0 && frame.height > 0)
        val count = frame.width.toLong() * frame.height
        require(count <= maxPixels && count <= LinearImage.MAX_PIXELS) {
            "Image exceeds processing budget; select a smaller camera ROI (at most $maxPixels pixels)"
        }
        require(traits.binning > 0 && traits.roiX >= 0 && traits.roiY >= 0)
        val format = frame.pixelFormat
        val channels = if (format.isRgb) 3 else 1
        val sampleBytes = if (format.isHighBit) 2 else 1
        val sampleCount = (count * channels).toInt()
        require(frame.data.size.toLong() >= count * channels * sampleBytes) { "Truncated raw image" }
        val bits = traits.validBits ?: format.nativeBits
        require(bits in 1..(sampleBytes * 8)) { "Invalid sample depth" }
        if (format.isBayer && !traits.bayerPatternAtOutput) require(traits.binning % 2 == 1) {
            "Even Bayer hardware binning needs an explicit output CFA or monochrome format"
        }
        val max = (1 shl bits) - 1
        fun read(i: Int): Int {
            val a = frame.data[i * sampleBytes].toInt() and 255
            if (sampleBytes == 1) return a
            val b = frame.data[i * sampleBytes + 1].toInt() and 255
            return if (traits.byteOrder == ByteOrder.LITTLE_ENDIAN) a or (b shl 8) else (a shl 8) or b
        }
        var alignment = traits.alignment
        if (alignment == SampleAlignment.AUTO) {
            alignment = SampleAlignment.RIGHT
            if (bits < sampleBytes * 8) {
                for (i in 0 until sampleCount) {
                    if (i % 16384 == 0) cancelCheck()
                    if (read(i) > max) { alignment = SampleAlignment.LEFT; break }
                }
            }
        }
        val shift = if (alignment == SampleAlignment.LEFT) sampleBytes * 8 - bits else 0
        val samples = workspace?.acquire(frame.width, frame.height, channels) ?: FloatArray(sampleCount)
        try {
            for (i in 0 until sampleCount) {
                if (i % 16384 == 0) cancelCheck()
                val value = read(i) ushr shift
                require(value <= max) { "Sample exceeds declared bit depth; check alignment" }
                val dest = if (channels == 3 && traits.channelOrder == ChannelOrder.BGR) {
                    (i / 3) * 3 + (2 - i % 3)
                } else i
                samples[dest] = value.toFloat() / max
            }
            return LinearImage(frame.width, frame.height, channels, samples)
        } finally {
            workspace?.finish()
        }
    }

    /** Call after sensor-space calibration. Negative calibrated samples are deliberately retained. */
    fun demosaicSensor(sensor: LinearImage, format: PixelFormat, traits: PixelTraits = PixelTraits(),
                       algorithm: DemosaicAlgorithm = DemosaicAlgorithm.BILINEAR,
                       cancelCheck: () -> Unit = {}): LinearImage =
        demosaicSensorImpl(sensor, format, traits, algorithm, null, cancelCheck)

    /** Reuses scratch only for DDFAPD. The caller releases it when its worker/session ends. */
    fun demosaicSensor(sensor: LinearImage, format: PixelFormat, traits: PixelTraits = PixelTraits(),
                       algorithm: DemosaicAlgorithm = DemosaicAlgorithm.BILINEAR,
                       workspace: DdfapdDemosaicer.Workspace,
                       cancelCheck: () -> Unit = {}): LinearImage =
        demosaicSensorImpl(sensor, format, traits, algorithm, workspace, cancelCheck)

    /** DDFAPD output owned by [workspace]. Consume it before another borrowed invocation. */
    internal fun demosaicSensorBorrowed(sensor: LinearImage, format: PixelFormat,
                                        traits: PixelTraits = PixelTraits(),
                                        workspace: DdfapdDemosaicer.Workspace,
                                        cancelCheck: () -> Unit = {}): LinearImage =
        demosaicSensorImpl(sensor, format, traits, DemosaicAlgorithm.DDFAPD, workspace,
            cancelCheck, borrowDdfapdOutput = true)

    private fun demosaicSensorImpl(sensor: LinearImage, format: PixelFormat, traits: PixelTraits,
                                  algorithm: DemosaicAlgorithm, workspace: DdfapdDemosaicer.Workspace?,
                                  cancelCheck: () -> Unit,
                                  borrowDdfapdOutput: Boolean = false): LinearImage {
        cancelCheck()
        require(sensor.channels == if (format.isRgb) 3 else 1) { "Sensor channel layout does not match its format" }
        require(traits.binning > 0 && traits.roiX >= 0 && traits.roiY >= 0)
        if (!format.isBayer) return sensor
        if (!traits.bayerPatternAtOutput) require(traits.binning % 2 == 1) {
            "Even Bayer hardware binning needs an explicit output CFA or monochrome format"
        }
        val phaseX = if (traits.bayerPatternAtOutput) 0 else traits.roiX and 1
        val phaseY = if (traits.bayerPatternAtOutput) 0 else traits.roiY and 1
        val redX = (if (format.name.startsWith("BAYER_GR") || format.name.startsWith("BAYER_BG")) 1 else 0) xor phaseX
        val redY = (if (format.name.startsWith("BAYER_GB") || format.name.startsWith("BAYER_BG")) 1 else 0) xor phaseY
        return when (algorithm) {
            DemosaicAlgorithm.BILINEAR -> debayer(sensor.pixels, sensor.width, sensor.height, redX, redY, cancelCheck)
            DemosaicAlgorithm.VNG -> VngDemosaicer.demosaic(sensor.pixels, sensor.width, sensor.height, redX, redY, cancelCheck)
            DemosaicAlgorithm.DDFAPD -> when {
                workspace == null -> DdfapdDemosaicer.demosaic(sensor.pixels, sensor.width, sensor.height, redX, redY, cancelCheck)
                borrowDdfapdOutput -> DdfapdDemosaicer.demosaicBorrowed(sensor.pixels, sensor.width, sensor.height,
                    redX, redY, workspace, cancelCheck)
                else -> DdfapdDemosaicer.demosaic(sensor.pixels, sensor.width, sensor.height, redX, redY, workspace, cancelCheck)
            }
        }
    }

    /** Additional peak scratch only: the sensor input and returned RGB are budgeted by callers.
     * Account at sensor resolution, before software binning. Non-Bayer input is passed through. */
    fun demosaicExtraBytes(width: Int, height: Int, format: PixelFormat, algorithm: DemosaicAlgorithm): Long {
        require(width > 0 && height > 0)
        if (!format.isBayer) return 0L
        return when (algorithm) {
            DemosaicAlgorithm.BILINEAR -> 0L
            DemosaicAlgorithm.VNG -> VngDemosaicer.extraBytes(width, height)
            DemosaicAlgorithm.DDFAPD -> DdfapdDemosaicer.extraBytes(width, height)
        }
    }

    /** Bilinear interpolation in floating point, before any geometric resampling. At a border,
     * only existing samples of the requested color contribute (no clamped opposite-color taps).
     */
    private fun debayer(src: FloatArray, w: Int, h: Int, redX: Int, redY: Int, cancelCheck: () -> Unit): LinearImage {
        require(w >= 2 && h >= 2) { "A Bayer image needs at least 2 x 2 pixels" }
        fun color(x: Int, y: Int): Int = when {
            (x and 1) == redX && (y and 1) == redY -> 0
            (x and 1) != redX && (y and 1) != redY -> 2
            else -> 1
        }
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            if (x == 0) cancelCheck()
            val own = color(x, y)
            for (c in 0..2) {
                if (c == own) { out[(y * w + x) * 3 + c] = src[y * w + x]; continue }
                var sum = 0f
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h && color(xx, yy) == c) {
                        sum += src[yy * w + xx]; n++
                    }
                }
                out[(y * w + x) * 3 + c] = sum / n.coerceAtLeast(1)
            }
        }
        return LinearImage(w, h, 3, out)
    }

    /** Optional precision-preserving software bin, applied AFTER demosaic. */
    fun bin(image: LinearImage, factor: Int): LinearImage {
        require(factor >= 1)
        if (factor == 1) return image
        val w = image.width / factor; val h = image.height / factor
        require(w > 0 && h > 0)
        val out = FloatArray(w * h * image.channels)
        val area = factor.toDouble() * factor
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until image.channels) {
            var sum = 0.0
            for (dy in 0 until factor) for (dx in 0 until factor) {
                sum += image.pixels[((y * factor + dy) * image.width + x * factor + dx) * image.channels + c]
            }
            out[(y * w + x) * image.channels + c] = (sum / area).toFloat()
        }
        return LinearImage(w, h, image.channels, out)
    }
}
