package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.PSERWriter
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

class DemosaicRecordingIntegrationTest {
    @Test fun unpackedSerAndPackedPserPassEachAlgorithmWithoutChangingPlaybackDefaultOrSensorSamples() {
        val directory = Files.createTempDirectory("demosaic-recordings").toFile()
        try {
            for (format in listOf(PixelFormat.BAYER_RG12, PixelFormat.BAYER_GR12, PixelFormat.BAYER_GB12, PixelFormat.BAYER_BG12)) {
                val samples = ByteArray(24 * 20 * 2)
                for (y in 0 until 20) for (x in 0 until 24) {
                    val value = 400 + ((x * 113 + y * 61 + (x xor y) * 29) % 2900)
                    val p = (y * 24 + x) * 2
                    samples[p] = value.toByte(); samples[p + 1] = (value ushr 8).toByte()
                }
                val frame = FrameData(samples, 24, 20, format, 0, 0)
                for (packed in listOf(false, true)) {
                    val file = directory.resolve("${format.name}.${if (packed) "pser" else "ser"}")
                    if (packed) PSERWriter(file).apply { open(24, 20, format); writeFrame(frame); close() }
                    else SERWriter(file).apply { open(24, 20, format); writeFrame(frame); close() }
                    SERReader(file).use { reader ->
                        reader.sampleAlignment = SampleAlignment.RIGHT
                        assertTrue(reader.open())
                        val sensor = reader.readSensorFrame(0)!!
                        val original = sensor.pixels.copyOf()
                        val bilinear = LinearPixelDecoder.demosaicSensor(sensor, reader.sensorFormat, reader.sensorTraits, DemosaicAlgorithm.BILINEAR)
                        assertArrayEquals("Old playback/read APIs retain bilinear", bilinear.pixels, reader.readLinearFrame(0)!!.pixels, 0f)
                        for (algorithm in DemosaicAlgorithm.values()) {
                            val expected = LinearPixelDecoder.demosaicSensor(sensor, reader.sensorFormat, reader.sensorTraits, algorithm)
                            assertArrayEquals("${format.name} $packed $algorithm", expected.pixels,
                                reader.readLinearFrame(0, algorithm = algorithm)!!.pixels, 0f)
                            var checks = 0
                            try {
                                reader.readLinearFrame(0, algorithm = algorithm, cancelCheck = {
                                    if (++checks >= 3) throw CancellationException("test cancellation")
                                })
                                fail("Cancellation must reach selected raw processing")
                            } catch (_: CancellationException) { assertTrue(checks >= 3) }
                            assertArrayEquals(expected.pixels, reader.readLinearFrame(0, algorithm = algorithm)!!.pixels, 0f)
                        }
                        assertArrayEquals(original, reader.readSensorFrame(0)!!.pixels, 0f)
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
