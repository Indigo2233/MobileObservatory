package com.indigo.mobileobservatory.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.util.Lp700PreviewMapping
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SERPreviewInstrumentedTest {
    private fun header(w: Int,h: Int,color: Int): ByteArray {
        val b = ByteBuffer.allocate(178).order(ByteOrder.LITTLE_ENDIAN)
        b.put("LUCAM-RECORDER".toByteArray(Charsets.US_ASCII)); b.position(18)
        b.putInt(color); b.putInt(0); b.putInt(w); b.putInt(h); b.putInt(16); b.putInt(1)
        return b.array()
    }
    private fun file() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "large-ser-preview-${UUID.randomUUID()}.ser")

    @Test fun fullAsi2600Rgb48CreatesBoundedBitmapAndShowsNativeColour() {
        val file = file(); val w = 6248; val h = 4176
        try {
            RandomAccessFile(file,"rw").use { f ->
                f.write(header(w,h,100)); f.setLength(178L+w.toLong()*h*6)
            }
            SERReader(file).use { reader ->
                assertTrue(reader.lastError,reader.open())
                val blank = reader.readPreviewBitmap(0)!!
                assertTrue(blank.width.toLong()*blank.height <= 1_048_576)
                assertTrue(blank.width <= 2048 && blank.height <= 2048)
                val px = blank.width/2; val py = blank.height/2
                val sx = ((2L*px+1)*w/(2L*blank.width)).toInt()
                val sy = ((2L*py+1)*h/(2L*blank.height)).toInt()
                blank.recycle()
                val values = intArrayOf(60000,25000,5000)
                RandomAccessFile(file,"rw").use { f ->
                    f.seek(178L+(sy.toLong()*w+sx)*6)
                    for (value in values) { f.writeByte(value and 255); f.writeByte(value ushr 8) }
                }
                val bitmap = reader.readPreviewBitmap(0)!!
                val argb = bitmap.getPixel(px,py)
                for (c in 0..2) assertEquals(values[c]*255/65535,(argb ushr (16-c*8)) and 255)
                bitmap.recycle()
            }
        } finally { file.delete() }
    }

    @Test fun faintBayerColourIsStretchedAfterHighBitInterpolationAndLp700RemainsApplied() {
        val file = file(); val w = 32; val h = 32
        try {
            val pixels = ByteBuffer.allocate(w*h*2).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until h) for (x in 0 until w) {
                val c = if (x%2 == 0 && y%2 == 0) 0 else if (x%2 == 1 && y%2 == 1) 2 else 1
                pixels.putShort((71+2*c).toShort())
            }
            file.writeBytes(header(w,h,8)+pixels.array())
            SERReader(file).use { reader ->
                assertTrue(reader.open()); reader.lp750Enabled = false
                val plain = reader.readPreviewBitmap(0)!!
                val rgb = plain.getPixel(10,10)
                val red = ((rgb ushr 16) and 255)/255f
                val green = ((rgb ushr 8) and 255)/255f
                val blue = (rgb and 255)/255f
                assertEquals(0f,red,0f); assertEquals(.5f,green,1f/255); assertEquals(1f,blue,0f)
                val gain = Lp700PreviewMapping.whiteBalanceGains(red,green,blue)
                val expected = Lp700PreviewMapping.matrix(red,green,blue)
                // This constant CFA fixture produces a constant decoded image. Its
                // converted 10th..60th percentile background is therefore the whole
                // image, so the existing background WB applies with full weight.
                for (y in 0 until h) for (x in 0 until w) assertEquals(rgb, plain.getPixel(x,y))
                val matrixRgb = IntArray(3) { c -> (expected[c]*gain[c]*255).coerceIn(0f,255f).toInt() }
                val backgroundTarget = (matrixRgb.sum()/3.0).coerceAtLeast(.255)
                val completeRgb = IntArray(3) { c ->
                    val backgroundGain = (backgroundTarget/matrixRgb[c].toDouble().coerceAtLeast(.255)).coerceIn(.75,1.35)
                    (matrixRgb[c]*backgroundGain).toInt().coerceIn(0,255)
                }
                reader.lp750Enabled = true
                val converted = reader.readPreviewBitmap(0)!!
                val lp = converted.getPixel(10,10)
                val actualRgb = IntArray(3) { c -> (lp ushr (16-c*8)) and 255 }
                android.util.Log.i("SERPreviewLP700", "matrix=${matrixRgb.contentToString()} background=${completeRgb.contentToString()} actual=${actualRgb.contentToString()}")
                for (c in 0..2) assertTrue("channel $c matrix=${matrixRgb.contentToString()} complete=${completeRgb.contentToString()} actual=${actualRgb.contentToString()}",
                    kotlin.math.abs(completeRgb[c]-actualRgb[c]) <= 1)
                plain.recycle(); converted.recycle()
            }
        } finally { file.delete() }
    }
}
