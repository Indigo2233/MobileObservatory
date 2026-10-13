package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.stacking.ChannelOrder
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteOrder

class RawRecordingBufferReuseTest {
    @Test fun reusableAndOwnedPathsAreBitExactAcrossEndianAlignmentDepthCfaAndChannelOrders() {
        val formats = listOf(PixelFormat.MONO8, PixelFormat.MONO16, PixelFormat.RGB24, PixelFormat.RGB48,
            PixelFormat.BAYER_RG16, PixelFormat.BAYER_GR16, PixelFormat.BAYER_GB16, PixelFormat.BAYER_BG16)
        for (format in formats) for (bits in listOf(8, 10, 12, 14, 16).filter { format.isHighBit || it == 8 }) {
            for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN))
                for (alignment in listOf(SampleAlignment.LEFT, SampleAlignment.RIGHT))
                    for (channels in ChannelOrder.values()) {
                        val traits = PixelTraits(byteOrder = order, alignment = alignment, validBits = bits,
                            channelOrder = channels, roiX = 1, roiY = 1, bayerPatternAtOutput = false)
                        val count = 257; val components = if (format.isRgb) 3 else 1
                        val maximum = (1 shl bits) - 1
                        val samples = IntArray(count * components) { if (it == 0) 0 else if (it == 1) maximum else (it * 239 + 17) and maximum }
                        val camera = FrameData(encode(samples, format, traits), count, 1, format, 42, 1234)
                        val original = camera.data.copyOf()
                        val expectedFormat = RawRecordingNormalizer.canonicalFormat(format, traits)
                        val expectedSamples = IntArray(samples.size) { i ->
                            val source = if (components == 3 && channels == ChannelOrder.BGR) i / 3 * 3 + 2 - i % 3 else i
                            val outputMaximum = (1 shl expectedFormat.nativeBits) - 1
                            ((samples[source].toLong() * outputMaximum + maximum / 2) / maximum).toInt()
                        }
                        val expected = encode(expectedSamples, expectedFormat, PixelTraits(alignment = SampleAlignment.RIGHT))
                        val owned = camera.copy(data = camera.data.copyOf())
                        val inPlace = RawRecordingNormalizer.normalizeOwnedInPlace(owned, traits)
                        val destination = ByteArray(expected.size + 17) { 0x55 }
                        val reused = RawRecordingNormalizer.normalizeInto(camera, traits, destination)
                        val safe = RawRecordingNormalizer.normalize(camera, traits)
                        val label = "$format/$bits/$order/$alignment/$channels"
                        assertSame(label, owned.data, inPlace.data)
                        assertSame(label, destination, reused.data)
                        assertNotSame(label, camera.data, safe.data)
                        for (converted in listOf(inPlace, reused, safe)) {
                            assertArrayEquals(label, expected, converted.data.copyOf(expected.size))
                            assertEquals(expectedFormat, converted.pixelFormat)
                            assertEquals(camera.width, converted.width); assertEquals(camera.height, converted.height)
                            assertEquals(camera.frameId, converted.frameId); assertEquals(camera.timestamp, converted.timestamp)
                        }
                        assertArrayEquals(label, original, camera.data)
                        assertTrue(destination.drop(expected.size).all { it == 0x55.toByte() })
                    }
        }
    }

    @Test fun explicitDestinationRejectsAliasingAndUndersizedBuffersBeforeMutation() {
        val camera = FrameData(byteArrayOf(1, 0, 2, 0), 2, 1, PixelFormat.MONO16, 0, 0)
        val traits = PixelTraits(alignment = SampleAlignment.RIGHT)
        val original = camera.data.copyOf()
        assertThrows(IllegalArgumentException::class.java) { RawRecordingNormalizer.normalizeInto(camera, traits, camera.data) }
        assertThrows(IllegalArgumentException::class.java) { RawRecordingNormalizer.normalizeInto(camera, traits, ByteArray(3)) }
        assertArrayEquals(original, camera.data)
    }

    @Test fun fourMegapixelOwnedNormalizationAllocatesNoFrameSizedArray() {
        // This local JVM diagnostic must still compile against Android's boot classpath.
        // HotSpot management extensions are optional and are accessed only by reflection.
        val management = runCatching { Class.forName("java.lang.management.ManagementFactory") }.getOrNull()
        val allocationApi = runCatching { Class.forName("com.sun.management.ThreadMXBean") }.getOrNull()
        assumeTrue(management != null && allocationApi != null)
        val bean = management!!.getMethod("getThreadMXBean").invoke(null)
        val api = allocationApi!!
        assumeTrue(api.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean)
        if (!(api.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) as Boolean))
            api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val allocatedBytes = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val traits = PixelTraits(alignment = SampleAlignment.RIGHT)
        val warmup = FrameData(ByteArray(32 * 32 * 2), 32, 32, PixelFormat.MONO16, 0, 0)
        repeat(50) { RawRecordingNormalizer.normalizeOwnedInPlace(warmup, traits) }
        val frame = FrameData(ByteArray(2048 * 2048 * 2), 2048, 2048, PixelFormat.MONO16, 1, 0)
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(bean, id) as Long
        val normalized = RawRecordingNormalizer.normalizeOwnedInPlace(frame, traits)
        val allocated = (allocatedBytes.invoke(bean, id) as Long) - before
        assertSame(frame.data, normalized.data)
        assertTrue("Owned normalization allocated $allocated bytes for ${frame.data.size}-byte input", allocated < 65536)
        println("RAW_RECORDING_ALLOCATION {\"pixels\":4194304,\"inputCapacity\":${frame.data.size},\"newFrameArrayCapacity\":0,\"measuredThreadAllocatedBytes\":$allocated}")
    }

    private fun encode(values: IntArray, format: PixelFormat, traits: PixelTraits): ByteArray {
        val bytes = if (format.isHighBit) 2 else 1
        val bits = traits.validBits ?: format.nativeBits
        val shift = if (traits.alignment == SampleAlignment.LEFT) bytes * 8 - bits else 0
        return ByteArray(values.size * bytes).also { output ->
            values.forEachIndexed { i, code ->
                val value = code shl shift
                if (bytes == 1) output[i] = value.toByte()
                else if (traits.byteOrder == ByteOrder.LITTLE_ENDIAN) {
                    output[i * 2] = value.toByte(); output[i * 2 + 1] = (value ushr 8).toByte()
                } else {
                    output[i * 2] = (value ushr 8).toByte(); output[i * 2 + 1] = value.toByte()
                }
            }
        }
    }
}
