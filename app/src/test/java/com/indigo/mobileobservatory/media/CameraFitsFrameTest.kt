package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.recording.FITSWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class CameraFitsFrameTest {
    @Test fun cameraTransportPreservesUnsignedSamplesAndDeclaredCfaDepth() {
        for (format in listOf(PixelFormat.MONO8, PixelFormat.BAYER_GR8, PixelFormat.MONO12,
                PixelFormat.BAYER_RG12, PixelFormat.BAYER_BG16)) {
            val bytes = ByteArray(32 * 24 * format.bytesPerPixel) { (it * 37).toByte() }
            // Keep values within the declared sensor depth.
            if (format.nativeBits == 12) for (i in 1 until bytes.size step 2) bytes[i] = (bytes[i].toInt() and 15).toByte()
            fits(FrameData(bytes, 32, 24, format, 1, 0)) { file ->
                val decoded = MediaImageFitsCodec.readCameraImage(file)
                assertEquals(format, decoded.snapshot.frame.pixelFormat)
                assertEquals(format.nativeBits, decoded.snapshot.traits?.validBits)
                assertArrayEquals(bytes, decoded.snapshot.frame.data)
                assertEquals(32, decoded.originalWidth); assertEquals(24, decoded.originalHeight)
            }
        }
    }

    @Test fun nativeCropMovesCfaPhaseAndReadsOnlyItsRectangle() {
        val bytes = ByteArray(32 * 24) { it.toByte() }
        fits(FrameData(bytes, 32, 24, PixelFormat.BAYER_RG8, 1, 0)) { file ->
            val decoded = MediaImageFitsCodec.readCameraImage(file, region = MediaImageRegion(3, 5, 7, 9))
            val frame = decoded.snapshot.frame
            assertEquals(PixelFormat.BAYER_BG8, frame.pixelFormat)
            assertEquals(7 * 9, frame.data.size)
            for (y in 0 until 9) for (x in 0 until 7) assertEquals(bytes[(y + 5) * 32 + x + 3], frame.data[y * 7 + x])
        }
    }

    @Test fun reducedCameraTransportReusesAreaSamplingAndPreservesSmallStars() {
        val w = 3856; val h = 192
        val bytes = ByteArray(w * h) { 10 }
        for (y in 100..101) for (x in 100..101) bytes[y * w + x] = 200.toByte()
        fits(FrameData(bytes, w, h, PixelFormat.BAYER_RG8, 1, 0)) { file ->
            val decoded = MediaImageFitsCodec.readCameraImage(file).snapshot.frame
            assertEquals(PixelFormat.BAYER_RG8, decoded.pixelFormat)
            val codes = decoded.data.map { it.toInt() and 255 }
            assertTrue(codes.max() > 25)
            assertEquals(10, codes.first())
        }
    }

    @Test fun reducedBayerTransportKeepsDepthPhaseAndSeparateColourStatistics() {
        for (format in listOf(PixelFormat.BAYER_RG8,PixelFormat.BAYER_GR12,PixelFormat.BAYER_BG16)) {
            val width=65; val height=49
            val data=ByteArray(width*height*format.bytesPerPixel)
            for(y in 0 until height) for(x in 0 until width) {
                val code=20+20*(y%2)+5*(x%2)
                val i=(y*width+x)*format.bytesPerPixel
                data[i]=code.toByte()
            }
            fits(FrameData(data,width,height,format,1,0)) { file ->
                for(step in 2..4) {
                    val decoded=MediaImageFitsCodec.readCameraImage(file,region=MediaImageRegion(1,3,63,45,step))
                    val frame=decoded.snapshot.frame
                    assertTrue(frame.pixelFormat.isBayer)
                    assertEquals(format.nativeBits,decoded.snapshot.traits?.validBits)
                    for(y in 0 until frame.height) for(x in 0 until frame.width) {
                        val i=(y*frame.width+x)*format.bytesPerPixel
                        assertEquals("step=$step x=$x y=$y",20+20*((y+3)%2)+5*((x+1)%2),frame.data[i].toInt() and 255)
                    }
                }
            }
        }
    }

    @Test fun cancelledFileReadsNeverPublishAnotherFrame() {
        fits(FrameData(ByteArray(32 * 24), 32, 24, PixelFormat.MONO8, 1, 0)) { file ->
            val failure = runCatching { MediaImageFitsCodec.readCameraImage(file) { throw CancellationException() } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        }
    }

    private fun fits(frame: FrameData, test: (File) -> Unit) {
        val file = Files.createTempFile("camera-display", ".fits").toFile()
        try { FITSWriter().write(file, frame, 1f, 0f); test(file) } finally { file.delete() }
    }
}
