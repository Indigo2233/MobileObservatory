package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.stacking.ChannelOrder
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.nio.ByteOrder

/** Canonical scientific recording bytes, before SER/PSER serialization.
 *
 * The default API returns an independent buffer. The recording queue may explicitly normalize
 * its exclusively owned copy in place; SDK/preview buffers must never use that API. Geometry is
 * unchanged. Samples are right-aligned, little-endian and RGB ordered.
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
        return convert(frame, traits, null)
    }

    /** Reuse caller-owned destination storage; it must not alias the camera's input array. */
    fun normalizeInto(frame: FrameData, traits: PixelTraits, destination: ByteArray): FrameData {
        require(destination !== frame.data) { "Use normalizeOwnedInPlace only for an exclusively owned buffer" }
        return convert(frame, traits, destination)
    }

    /** Mutates only the queue's exclusively owned copy. No frame-sized allocation or scratch.
     * A failed conversion leaves this owned copy unusable and it must be discarded. */
    fun normalizeOwnedInPlace(frame: FrameData, traits: PixelTraits): FrameData = convert(frame, traits, frame.data)

    private fun convert(frame: FrameData, traits: PixelTraits, destinationBuffer: ByteArray?): FrameData {
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
        val outputBytes = (pixels * outputFormat.bytesPerPixel).toInt()
        val output = destinationBuffer ?: ByteArray(outputBytes)
        require(output.size >= outputBytes) { "Raw recording destination is too small" }
        // The only container-size change is 16 -> 8 bit mono/CFA. Forward traversal can
        // compact that case safely because each source sample is read before it is overwritten.
        require(output !== frame.data || outputSampleBytes <= inputSampleBytes)
        val little = traits.byteOrder == ByteOrder.LITTLE_ENDIAN
        fun readCanonical(index: Int): Int {
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
            return if (maximum == outputMaximum) value else
                ((value.toLong() * outputMaximum + maximum / 2) / maximum).toInt()
        }
        fun writeCanonical(index: Int, canonical: Int) {
            val destination = index * outputSampleBytes
            output[destination] = canonical.toByte()
            if (outputSampleBytes == 2) output[destination + 1] = (canonical ushr 8).toByte()
        }
        if (channels == 1) {
            for (index in 0 until pixels.toInt()) writeCanonical(index, readCanonical(index))
        } else {
            val bgr = traits.channelOrder == ChannelOrder.BGR
            for (pixel in 0 until pixels.toInt()) {
                val index = pixel * 3
                // Capture all channels first: swapping R/B in the same array must not destroy
                // the original first channel before it is written into the final channel.
                val first = readCanonical(index)
                val green = readCanonical(index + 1)
                val last = readCanonical(index + 2)
                writeCanonical(index, if (bgr) last else first)
                writeCanonical(index + 1, green)
                writeCanonical(index + 2, if (bgr) first else last)
            }
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
