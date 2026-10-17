package com.indigo.mobileobservatory.recording

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.util.FileLogger
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

/** Exercise the phone/emulator's real MediaCodec and MediaMuxer, then read the saved MP4. */
@RunWith(AndroidJUnit4::class)
class Mp4WriterInstrumentedTest {
    @Before fun initializeExportableDiagnostics() {
        FileLogger.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    private fun file() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "mp4-regression-${System.nanoTime()}.mp4")

    @Test fun rgbFramesEncodeWithCorrectChromaAndPlayableDuration() {
        val file = file()
        val writer = Mp4Writer(file)
        val width = 320; val height = 240
        val pixels = ByteArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) pixels[(y * width + x) * 3 + if (x < width / 2) 0 else 2] = 255.toByte()
        try {
            writer.open(width, height, 10)
            repeat(12) { i -> writer.writeFrame(FrameData(pixels, width, height, PixelFormat.RGB24, i.toLong(), i * 100L)) }
            writer.close()
            assertEquals(12, writer.currentFrameCount)
            assertTrue(writer.totalBytesWritten > 0)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.absolutePath)
                assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 1000)
                val bitmap = requireNotNull(reader.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                val red = bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)
                val blue = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2)
                assertTrue("Red/blue planes were swapped", Color.red(red) > Color.blue(red) + 100)
                assertTrue("Blue/UV plane was corrupted", Color.blue(blue) > Color.red(blue) + 100)
                bitmap.recycle()
            } finally { reader.release() }
        } finally { runCatching { writer.close() }; file.delete() }
    }

    @Test fun squareFullSensorRawFramesProduceReadableVideoAtAnEncoderSupportedSize() {
        verifyRawVideo(3536, 3536)
    }

    @Test fun loggedPlayerOne585Raw16SizeProducesReadableVideo() {
        verifyRawVideo(3856, 2180)
    }

    private fun verifyRawVideo(width: Int, height: Int) {
        val file = file()
        val writer = Mp4Writer(file)
        val pixels = ByteArray(width * height * 2) { if (it % 2 == 0) 0 else 96 }
        try {
            writer.open(width, height, 10)
            repeat(3) { i -> writer.writeFrame(FrameData(pixels, width, height, PixelFormat.BAYER_RG16, i.toLong(), i * 100L)) }
            writer.close()
            assertEquals(3, writer.currentFrameCount)
            val reader = MediaExtractor()
            try {
                reader.setDataSource(file.absolutePath)
                assertEquals(1, reader.trackCount)
                val format = reader.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
                assertTrue(format.getInteger(MediaFormat.KEY_WIDTH) in 16..Mp4EncoderSupport.align16(width))
                assertTrue(format.getInteger(MediaFormat.KEY_HEIGHT) in 16..Mp4EncoderSupport.align16(height))
                reader.selectTrack(0)
                var count = 0
                while (reader.sampleTime >= 0) { count++; reader.advance() }
                assertEquals(3, count)
            } finally { reader.release() }
        } finally { runCatching { writer.close() }; file.delete() }
    }

    @Test fun emptyRecordingCannotBeReportedAsSuccessfulAndCleanupIsIdempotent() {
        val file = file()
        val writer = Mp4Writer(file)
        try {
            writer.open(320, 240, 10)
            assertThrows(IllegalStateException::class.java) { writer.close() }
            assertFalse(writer.isOpen)
            writer.close()
        } finally { runCatching { writer.close() }; file.delete() }
    }
}
