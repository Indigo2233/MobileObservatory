package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.stacking.LinearImage
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SERPreviewReaderTest {
    private fun header(w: Int,h: Int,bits: Int,count: Int,color: Int = 0,endian: Int = 0,packed: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(178).order(ByteOrder.LITTLE_ENDIAN)
        b.put((if (packed) "PSER-RECORDER" else "LUCAM-RECORDER").toByteArray(Charsets.US_ASCII))
        b.position(18); b.putInt(color); b.putInt(endian); b.putInt(w); b.putInt(h); b.putInt(bits); b.putInt(count)
        return b.array()
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected invalid request") } catch (_: IllegalArgumentException) { }
    }
    private fun cfa(x: Int,y: Int,id: Int): Int {
        val rx = if (id == 9 || id == 11) 1 else 0
        val ry = if (id == 10 || id == 11) 1 else 0
        return if (x%2 == rx && y%2 == ry) 0 else if (x%2 != rx && y%2 != ry) 2 else 1
    }
    /** Independent bit-by-bit fixture packing, including pixels crossing byte/page boundaries. */
    private fun bytes(samples: IntArray,bits: Int,packed: Boolean,little: Boolean = true): ByteArray {
        if (packed) {
            val result = ByteArray((samples.size*bits+7)/8)
            for (i in samples.indices) for (bit in 0 until bits) if ((samples[i] ushr bit) and 1 != 0) {
                val at = i*bits+bit
                result[at/8] = (result[at/8].toInt() or (1 shl (at%8))).toByte()
            }
            return result
        }
        val b = ByteBuffer.allocate(samples.size*(if (bits > 8) 2 else 1))
            .order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        for (value in samples) if (bits > 8) b.putShort(value.toShort()) else b.put(value.toByte())
        return b.array()
    }

    @Test fun fullAsi2600SparseRecordingsOpenAndPreviewBeyondTwoGiBWithoutRawAllocation() {
        val w = 6248; val h = 4176; val count = 96
        for ((packed,bits,color) in listOf(Triple(false,16,0),Triple(false,16,8),Triple(true,12,11))) {
            val path = Files.createTempDirectory("large-preview").resolve(if (packed) "large.pser" else "large.ser")
            val frameSize = if (packed) (w.toLong()*h*bits+7)/8 else w.toLong()*h*2
            val offset = 178L+frameSize*(count-1)
            try {
                Files.newByteChannel(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,StandardOpenOption.SPARSE).use { f ->
                    f.write(ByteBuffer.wrap(header(w,h,bits,count,color,packed=packed)))
                    f.position(178L+frameSize*count-1); f.write(ByteBuffer.wrap(byteArrayOf(0)))
                }
                SERReader(path.toFile()).use { r ->
                    assertTrue(r.lastError,r.open())
                    assertEquals(w,r.header!!.width); assertEquals(h,r.header!!.height)
                    assertTrue(r.frameOffset(count-1) > Int.MAX_VALUE)
                    assertEquals(offset,r.frameOffset(count-1))
                    val preview = r.readPreviewFrame(count-1)!!
                    assertTrue(preview.pixelCount <= SERPreviewDecoder.DEFAULT_PIXELS)
                    assertTrue(preview.width <= 2048 && preview.height <= 2048)
                    assertEquals(if (color == 0) 1 else 3,preview.channels)
                    assertTrue(preview.pixels.all { it == 0f })
                    val px = preview.width/2; val py = preview.height/2
                    val sx = ((2L*px+1)*w/(2L*preview.width)).toInt()
                    val sy = ((2L*py+1)*h/(2L*preview.height)).toInt()
                    val values = intArrayOf(301,1202,2303)
                    // Write independent nonzero samples only into the selected final frame.
                    // This detects narrowing the >2GiB offset or accidentally reading frame 0.
                    RandomAccessFile(path.toFile(),"rw").use { f ->
                        for (y in sy-1..sy+1) for (x in sx-1..sx+1) {
                            val value = values[if (color == 0) 0 else cfa(x,y,color)]
                            val sample = y.toLong()*w+x
                            if (packed) {
                                for (bit in 0 until bits) {
                                    val at = sample*bits+bit
                                    f.seek(offset+at/8); val old = f.readUnsignedByte()
                                    val mask = 1 shl (at%8).toInt()
                                    val changed = if ((value ushr bit) and 1 != 0) old or mask else old and mask.inv()
                                    f.seek(offset+at/8); f.writeByte(changed)
                                }
                            } else {
                                f.seek(offset+sample*2); f.writeByte(value and 255); f.writeByte(value ushr 8)
                            }
                        }
                    }
                    val marked = r.readPreviewFrame(count-1)!!
                    for (c in 0 until marked.channels)
                        assertEquals(values[c].toFloat()/((1 shl bits)-1),
                            marked.pixels[(py*marked.width+px)*marked.channels+c],1e-7f)
                    assertTrue(r.readPreviewFrame(0,16)!!.pixels.all { it == 0f })
                    reject { r.readFrame(0) }
                    reject { r.readLinearFrame(0) }
                    reject { r.readSensorFrame(0) }
                    assertNull(r.readPreviewFrame(-1)); assertNull(r.readPreviewFrame(count))
                }
            } finally { Files.deleteIfExists(path); Files.deleteIfExists(path.parent) }
        }
    }

    @Test fun downsamplingPreservesDarkHighBitSignalsRgbBgrEndianAndAllCfaPhases() {
        val file = Files.createTempFile("preview-formats",".ser").toFile()
        val w = 17; val h = 13
        try {
            for (bits in listOf(8,10,12,14,16)) for (packed in listOf(false,true))
                for (id in listOf(0,8,9,10,11,100,101)) for (little in listOf(false,true)) {
                    if (packed && (id >= 100 || !little)) continue
                    val channels = if (id >= 100) 3 else 1
                    val raw = IntArray(w*h*channels) { i ->
                        val x = (i/channels)%w; val y = i/channels/w
                        val c = if (id >= 100) (if (id == 101) 2-i%3 else i%3)
                            else if (id in 8..11) cfa(x,y,id) else 0
                        71+2*c+x+2*y
                    }
                    file.writeBytes(header(w,h,bits,1,id,if (little) 0 else 1,packed)+bytes(raw,bits,packed,little))
                    SERReader(file).use { r ->
                        r.sampleAlignment = SampleAlignment.RIGHT
                        assertTrue(r.lastError,r.open())
                        val output = r.readPreviewFrame(0,15)!!
                        assertTrue(output.pixelCount <= 15)
                        for (y in 0 until output.height) for (x in 0 until output.width) {
                            val sx = ((2L*x+1)*w/(2L*output.width)).toInt()
                            val sy = ((2L*y+1)*h/(2L*output.height)).toInt()
                            assertTrue(sx in 1 until w-1 && sy in 1 until h-1)
                            for (c in 0 until output.channels)
                                assertEquals("$id/$bits/packed=$packed/LE=$little ($x,$y,$c)",
                                    (71+2*c+sx+2*sy).toFloat()/((1 shl bits)-1),
                                    output.pixels[(y*output.width+x)*output.channels+c],1e-7f)
                        }
                    }
                }
        } finally { file.delete() }
    }

    @Test fun packedOddWidthRowsTailPixelsAndCachePageBoundariesKeepEveryBit() {
        val file = Files.createTempFile("preview-packed",".pser").toFile()
        val w = 10003; val h = 3
        try {
            for (bits in listOf(8,10,12,14,16)) {
                val maximum = (1 shl bits)-1
                val raw = IntArray(w*h) { (it*31+17) and maximum }
                file.writeBytes(header(w,h,bits,1,packed=true)+bytes(raw,bits,true))
                SERReader(file).use { r ->
                    assertTrue(r.open())
                    val output = r.readPreviewFrame(0,2048)!!
                    for (y in 0 until output.height) for (x in 0 until output.width) {
                        val sx = ((2L*x+1)*w/(2L*output.width)).toInt()
                        val sy = ((2L*y+1)*h/(2L*output.height)).toInt()
                        assertEquals(raw[sy*w+sx].toFloat()/maximum,output.pixels[y*output.width+x],1e-7f)
                    }
                }
                // No reduction: exercises the odd final packed sample and partial last byte.
                val small = raw.copyOf(15)
                file.writeBytes(header(5,3,bits,1,packed=true)+bytes(small,bits,true))
                SERReader(file).use { r ->
                    assertTrue(r.open()); val output = r.readPreviewFrame(0)!!
                    for (i in small.indices) assertEquals(small[i].toFloat()/maximum,output.pixels[i],1e-7f)
                }
            }
        } finally { file.delete() }
    }

    @Test fun explicitLegacyLeftAlignmentAndSpecificationFlagWorkWithoutEightBitQuantization() {
        val file = Files.createTempFile("preview-left",".ser").toFile()
        try {
            val raw = intArrayOf(1,2,3,4095)
            for (little in listOf(false,true)) {
                file.writeBytes(header(2,2,12,1,endian=if (little) 1 else 0)+bytes(raw.map { it shl 4 }.toIntArray(),12,false,little))
                SERReader(file,SerEndianConvention.SPECIFICATION).use { r ->
                    r.sampleAlignment = SampleAlignment.LEFT; assertTrue(r.open())
                    val output = r.readPreviewFrame(0)!!
                    for (i in raw.indices) assertEquals(raw[i]/4095f,output.pixels[i],1e-8f)
                    r.sampleAlignment = SampleAlignment.AUTO
                    assertArrayEquals(output.pixels,r.readPreviewFrame(0)!!.pixels,0f)
                }
            }
        } finally { file.delete() }
    }

    @Test fun cancellationBudgetsMalformedArithmeticAndTruncationFailSafely() {
        val file = Files.createTempFile("preview-errors",".ser").toFile()
        try {
            for (packed in listOf(false,true)) {
                file.writeBytes(header(Int.MAX_VALUE,Int.MAX_VALUE,16,Int.MAX_VALUE,packed=packed))
                SERReader(file).use { r -> assertFalse(r.open()); assertNotNull(r.lastError) }
            }
            file.writeBytes(header(64,64,16,1)+ByteArray(64*64*2))
            SERReader(file).use { r ->
                assertTrue(r.open())
                reject { r.readPreviewFrame(0,0) }; reject { r.readPreviewFrame(0,Int.MAX_VALUE) }
                var checks = 0
                try {
                    r.readPreviewFrame(0) { if (++checks == 10) throw CancellationException("stop") }
                    fail("Expected cancellation")
                } catch (_: CancellationException) { assertEquals(10,checks) }
                assertNotNull(r.readPreviewFrame(0))
                RandomAccessFile(file,"rw").use { it.setLength(178+8) }
                assertNull(r.readPreviewFrame(0)); assertNotNull(r.lastError)
            }
            assertEquals(128*1024,SERPreviewDecoder.CACHE_BYTES)
            assertTrue(SERPreviewDecoder.DEFAULT_PIXELS < LinearImage.DEFAULT_STACK_PIXELS)
        } finally { file.delete() }
    }
}
