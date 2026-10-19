package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.recording.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SerRegionDecoderTest {
    private fun recording(format: PixelFormat, width: Int = 319, height: Int = 303, packed: Boolean = false): File {
        val file = File.createTempFile("region-science", if (packed) ".pser" else ".ser")
        val channels = if (format.isRgb) 3 else 1
        val bits = format.nativeBits
        val bytes = ByteBuffer.allocate(width * height * format.bytesPerPixel).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
            val value = ((.4 + .12 * kotlin.math.sin(x * .19) + .08 * kotlin.math.cos(y * .27) + .03 * c) * ((1 shl bits) - 1)).toInt()
            if (bits == 8) bytes.put(value.toByte()) else bytes.putShort(value.toShort())
        }
        val frame = FrameData(bytes.array(), width, height, format, 1, 0)
        if (packed) PSERWriter(file).apply { open(width, height, format); writeFrame(frame); close() }
        else SERWriter(file).apply { open(width, height, format); writeFrame(frame); close() }
        return file
    }
    private fun crop(image: LinearImage, region: PixelRoi): LinearImage {
        val pixels = FloatArray(region.width * region.height * image.channels)
        for (y in 0 until region.height) {
            val from = ((region.top + y) * image.width + region.left) * image.channels
            image.pixels.copyInto(pixels, y * region.width * image.channels, from, from + region.width * image.channels)
        }
        return LinearImage(region.width, region.height, image.channels, pixels)
    }

    @Test fun tiledCfaAtOddOriginsMatchesWholeFrameCalibrationDemosaicThenBinIncludingTileSeams() {
        for (format in listOf(PixelFormat.BAYER_RG16, PixelFormat.BAYER_GR16, PixelFormat.BAYER_GB16, PixelFormat.BAYER_BG16)) {
            val file = recording(format)
            try { SERReader(file).use { reader ->
                reader.sampleAlignment = SampleAlignment.RIGHT; assertTrue(reader.open())
                val geometry = CalibrationGeometry.from(319, 303, format, reader.sensorTraits)
                fun master(type: CalibrationType) = CalibrationMaster(CalibrationDescriptor(type.name, "", type, geometry,
                    CalibrationCapture(), "test", 1, 0), FloatArray(319 * 303) { i ->
                    if (type == CalibrationType.BIAS) .01f + i % 319 * .00001f else .9f + i % 303 * .001f
                })
                val calibration = CalibrationSelection(bias = master(CalibrationType.BIAS), flat = master(CalibrationType.FLAT), allowUnknownCapture = true)
                val full = calibration.apply(reader.readSensorFrame(0)!!, geometry, CalibrationCapture())
                for (algorithm in DemosaicAlgorithm.entries) {
                    val expectedFull = LinearPixelDecoder.demosaicSensor(full, format, reader.sensorTraits, algorithm)
                    for (bin in listOf(1, 2, 4)) for (region in listOf(PixelRoi(13, 15, 283, 271), PixelRoi(0, 0, 319, 303))) {
                        val plan = OfflineRegionPlan(319, 303, format, region, algorithm, StackTarget.PLANETARY, bin)
                        val workspace = DdfapdDemosaicer.Workspace()
                        val actual = try { SerRegionDecoder.read(reader, 0, plan, calibration, geometry, CalibrationCapture(), workspace) {} }
                            finally { workspace.release() }
                        val expected = LinearPixelDecoder.bin(crop(expectedFull, region), bin)
                        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
                        assertArrayEquals("$format $algorithm Bin$bin $region", expected.pixels, actual.pixels, 1e-6f)
                    }
                }
            } } finally { file.delete() }
        }
    }

    @Test fun packedRowsAndTailBitsReadTheSameNativeSamplesWithoutAFullFrameBuffer() {
        for (bits in listOf(8, 10, 12, 14, 16)) for (packed in listOf(true, false)) {
            val file = recording(PixelFormat.valueOf("MONO$bits"), width = 47, height = 31, packed = packed)
            try { SERReader(file).use { reader ->
                reader.sampleAlignment = SampleAlignment.RIGHT; assertTrue(reader.open())
                val region = PixelRoi(3, 5, 41, 25)
                assertArrayEquals(crop(reader.readSensorFrame(0)!!, region).pixels, reader.readSensorRegion(0, region)!!.pixels, 0f)
            } } finally { file.delete() }
        }
    }

    @Test fun regionReadsLargeSourceBeyondFullFrameCeilingAndHonorsCancellationAndBounds() {
        val file = File.createTempFile("large-region", ".ser")
        try {
            val header = ByteBuffer.allocate(178).order(ByteOrder.LITTLE_ENDIAN)
            header.put("LUCAM-RECORDER".toByteArray()); header.position(18)
            header.putInt(0); header.putInt(0); header.putInt(5120); header.putInt(4000); header.putInt(16); header.putInt(1)
            RandomAccessFile(file, "rw").use { it.write(header.array()); it.setLength(178L + 5120L * 4000 * 2)
                it.seek(178L + (3000L * 5120 + 4100) * 2); it.write(byteArrayOf(0x34, 0x12)) }
            SERReader(file).use { reader ->
                reader.sampleAlignment = SampleAlignment.RIGHT; assertTrue(reader.open())
                val region = PixelRoi(4100, 3000, 16, 16)
                assertEquals(0x1234 / 65535f, reader.readSensorRegion(0, region)!!.pixels[0], 0f)
                assertThrows(IllegalArgumentException::class.java) { reader.readSensorRegion(0, region, maxPixels = 100) }
                assertThrows(java.util.concurrent.CancellationException::class.java) { reader.readSensorRegion(0, region) { throw java.util.concurrent.CancellationException() } }
                assertThrows(IllegalArgumentException::class.java) { reader.readSensorRegion(0, PixelRoi(5110, 0, 16, 16)) }
            }
        } finally { file.delete() }
    }

    @Test fun regionPreservesEndianBitAlignmentAndRgbChannelOrder() {
        val file = File.createTempFile("region-endian", ".ser")
        try {
            val w = 31; val h = 29; val roi = PixelRoi(3, 5, 23, 19)
            for (color in listOf(0, 8, 100, 101)) for (bits in listOf(8, 12, 16))
                for (little in listOf(false, true)) for (left in listOf(false, true)) {
                val channels = if (color >= 100) 3 else 1; val sampleBytes = if (bits == 8) 1 else 2
                val header = ByteBuffer.allocate(178).order(ByteOrder.LITTLE_ENDIAN)
                header.put("LUCAM-RECORDER".toByteArray()); header.position(18)
                header.putInt(color); header.putInt(if (little) 0 else 1); header.putInt(w); header.putInt(h); header.putInt(bits); header.putInt(1)
                val data = ByteBuffer.allocate(w * h * channels * sampleBytes).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                val max = (1 shl bits) - 1
                for (i in 0 until w * h * channels) {
                    val value = ((i * 71 + 19) and max) shl if (left) sampleBytes * 8 - bits else 0
                    if (sampleBytes == 1) data.put(value.toByte()) else data.putShort(value.toShort())
                }
                file.writeBytes(header.array() + data.array())
                SERReader(file).use { reader ->
                    reader.sampleAlignment = if (left) SampleAlignment.LEFT else SampleAlignment.RIGHT
                    assertTrue(reader.open())
                    val actual = reader.readSensorRegion(0, roi)!!
                    for (y in 0 until roi.height) for (x in 0 until roi.width) for (c in 0 until channels) {
                        val channel = if (color == 101) 2 - c else c
                        val original = ((roi.top + y) * w + roi.left + x) * channels + channel
                        assertEquals(((original * 71 + 19) and max) / max.toFloat(), actual.pixels[(y * roi.width + x) * channels + c], 0f)
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test fun budgetsFollowHeapAndTiledOutputInsteadOfFullNativeDdfapdPlanes() {
        assertEquals(1024L * 1024 * 1024 / 5 * 2, StackMemoryBudget.forHeap(1024L * 1024 * 1024))
        assertTrue(StackMemoryBudget.forHeap(1024L * 1024 * 1024) > 256L * 1024 * 1024)
        assertEquals(84L * 1000 * 1000 + 8L * 1024 * 1024, PlanetaryStacker.estimatedPeakBytes(1000, 1000, 3, boundedDecode = true))
        val full = OfflineRegionPlan(3856, 2180, PixelFormat.BAYER_RG16, PixelRoi(0, 0, 3856, 2180), DemosaicAlgorithm.DDFAPD, StackTarget.PLANETARY)
        assertNotNull(full.issue(128L shl 20))
        assertNull(full.copy(softwareBin = 4).issue(76L shl 20))
        val crop = full.copy(region = PixelRoi(500, 500, 600, 600))
        assertNull(crop.issue(76L shl 20))
        val suggested = full.suggested(76L shl 20)!!
        assertNull(full.copy(region = suggested).issue(76L shl 20))
        assertTrue(full.copy(target = StackTarget.DEEP_SKY).workingBytes <
            DeepSkyStacker.estimateWorkingBytes(3856, 2180, 3))
    }

    @Test fun userCropEdgesKeepTheOriginalFrameClippingContract() {
        val full = LinearImage(200, 160, 1, FloatArray(200 * 160) { i ->
            val x = i % 200; val y = i / 200
            if ((x - 18) * (x - 18) + (y - 80) * (y - 80) < 40 * 40) (.4 + .05 * kotlin.math.sin(y * .4)).toFloat() else .02f
        })
        val region = PixelRoi(8, 0, 80, 160)
        val selected = crop(full, region)
        val expected = PlanetaryQualityEstimator.measure(full, region)
        val actual = PlanetaryQualityEstimator.measure(selected, PixelRoi(0, 0, 80, 160),
            PlanetaryImageEdges(left = false, right = false))
        assertEquals(PlanetaryRejection.NONE, expected.rejection)
        assertEquals(expected.rejection, actual.rejection)
        assertEquals(PlanetaryRejection.OUT_OF_FRAME,
            PlanetaryQualityEstimator.measure(selected, PixelRoi(0, 0, 80, 160)).rejection)
    }
}
