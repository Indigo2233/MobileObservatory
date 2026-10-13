package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.stacking.ChannelOrder
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.nio.ByteOrder

/** Canonical scientific recording bytes, before SER/PSER serialization.
 *
 * Input belongs to the camera (or its callback-owned copy); output ALWAYS owns an independent
 * buffer. Geometry is unchanged. Samples are right-aligned, little-endian and RGB ordered.
 * CFA remains a mosaic: only its descriptor is shifted to the actual ROI origin. There is no
 * demosaic, white balance, display stretch or spatial binning here.
 */
object RawRecordingNormalizer {
    /** Also used when opening the writer so its header describes the subsequent normalized data. */
    fun canonicalFormat(format: PixelFormat, traits: PixelTraits): PixelFormat {
        validateTraits(format, traits)
        if (format.isRgb) return format
        val bits = traits.validBits ?: format.nativeBits
        if (format.isMono) return PixelFormat.valueOf("MONO$bits")
        val sensorRedX = if (format.name.startsWith("BAYER_GR") || format.name.startsWith("BAYER_BG")) 1 else 0
        val sensorRedY = if (format.name.startsWith("BAYER_GB") || format.name.startsWith("BAYER_BG")) 1 else 0
        val redX = sensorRedX xor (if (traits.bayerPatternAtOutput) 0 else traits.roiX and 1)
        val redY = sensorRedY xor (if (traits.bayerPatternAtOutput) 0 else traits.roiY and 1)
        val pattern = when {
            redY == 0 && redX == 0 -> "RG"
            redY == 0 -> "GR"
            redX == 0 -> "GB"
            else -> "BG"
        }
        return PixelFormat.valueOf("BAYER_$pattern$bits")
    }

    fun normalize(frame: FrameData, traits: PixelTraits): FrameData {
        val outputFormat = canonicalFormat(frame.pixelFormat, traits)
        require(frame.width > 0 && frame.height > 0) { "Invalid raw recording dimensions" }
        val pixels = frame.width.toLong() * frame.height
        val inputBytes = frame.pixelFormat.bytesPerPixel
        require(pixels <= Int.MAX_VALUE / inputBytes) { "Raw recording frame exceeds buffer limit" }
        val expectedBytes = (pixels * inputBytes).toInt()
        require(frame.data.size >= expectedBytes) { "Truncated raw recording frame" }
        val bits = traits.validBits ?: frame.pixelFormat.nativeBits
        val inputSampleBytes = if (frame.pixelFormat.isHighBit) 2 else 1
        val outputSampleBytes = if (outputFormat.isHighBit) 2 else 1
        val channels = if (frame.pixelFormat.isRgb) 3 else 1
        val maximum = (1 shl bits) - 1
        val outputMaximum = (1 shl outputFormat.nativeBits) - 1
        val shift = if (traits.alignment == SampleAlignment.LEFT) inputSampleBytes * 8 - bits else 0
        val output = ByteArray((pixels * outputFormat.bytesPerPixel).toInt())
        val little = traits.byteOrder == ByteOrder.LITTLE_ENDIAN
        for (index in 0 until pixels.toInt() * channels) {
            val source = index * inputSampleBytes
            val a = frame.data[source].toInt() and 255
            val container = if (inputSampleBytes == 1) a else {
                val b = frame.data[source + 1].toInt() and 255
                if (little) a or (b shl 8) else (a shl 8) or b
            }
            val value = container ushr shift
            require(value <= maximum) { "Raw sample exceeds explicit camera bit depth; recording stopped" }
            // RGB has only 8/16-bit descriptors. Expand lesser sensor depths to its declared range;
            // this mapping is monotone/injective and does not discard any original sensor code.
            val canonical = if (maximum == outputMaximum) value else
                ((value.toLong() * outputMaximum + maximum / 2) / maximum).toInt()
            val destinationSample = if (channels == 3 && traits.channelOrder == ChannelOrder.BGR)
                index / 3 * 3 + 2 - index % 3 else index
            val destination = destinationSample * outputSampleBytes
            output[destination] = canonical.toByte()
            if (outputSampleBytes == 2) output[destination + 1] = (canonical ushr 8).toByte()
        }
        return frame.copy(data = output, pixelFormat = outputFormat)
    }

    private fun validateTraits(format: PixelFormat, traits: PixelTraits) {
        require(traits.alignment != SampleAlignment.AUTO) { "Scientific recording requires an explicit camera sample alignment" }
        val bits = traits.validBits ?: format.nativeBits
        require(bits in listOf(8, 10, 12, 14, 16) && bits <= if (format.isHighBit) 16 else 8) {
            "Unsupported raw recording sample depth"
        }
        require(traits.roiX >= 0 && traits.roiY >= 0 && traits.binning >= 1) { "Invalid raw recording geometry" }
        require(!format.isBayer || traits.bayerPatternAtOutput || traits.binning % 2 == 1) {
            "Even Bayer hardware binning requires a verified output CFA or monochrome format"
        }
    }
}
