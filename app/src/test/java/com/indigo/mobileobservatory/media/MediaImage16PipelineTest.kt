package com.indigo.mobileobservatory.media

import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class MediaImage16PipelineTest {
    @Test fun fitsRetainsEveryUnsigned16BitCodeIncludingTheSignedBoundary() = directory { root ->
        val image = MediaImagePixels(256, 256, 1, ShortArray(65536) { it.toShort() })
        val file = File(root, "ramp.fit")
        MediaImageFitsCodec.write(file, image, mapOf("schema" to "indigo-image-edit-1", "precision" to "uint16"))
        val read = MediaImageFitsCodec.read(file).image
        assertArrayEquals(image.pixels, read.pixels)
        assertEquals(131072L, read.byteSize)
        assertEquals(0.0, read.sample(0), 0.0); assertEquals(65535.0, read.sample(65535), 0.0)
    }

    @Test fun pngStreamsBandsAndAnIndependentDecoderFindsAll16Bits() = directory { root ->
        val file = File(root, "ramp.png"); var bands = 0
        MediaPng16Codec.write(file, 257, 65, 3) { region ->
            assertTrue(region.height <= 32); bands++
            fixture(region, 3)
        }
        assertEquals(3, bands); assertTrue(MediaPng16Codec.is16Bit(file))
        // Inspect the encoder's big-endian, Sub-filtered scanlines using only zlib.
        val compressed = java.io.ByteArrayOutputStream()
        java.io.DataInputStream(file.inputStream()).use { input ->
            input.readFully(ByteArray(8))
            while (true) {
                val size = input.readInt(); val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val bytes = ByteArray(size).also(input::readFully); input.readInt()
                if (type == "IDAT") compressed.write(bytes)
                if (type == "IEND") break
            }
        }
        java.io.DataInputStream(java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(compressed.toByteArray()))).use { input ->
            for (y in 0 until 65) {
                assertEquals(1, input.readUnsignedByte())
                for (x in 0 until 257) for (c in 0..2) {
                    val current = code(x,y,c); val prior = if (x == 0) 0 else code(x-1,y,c)
                    assertEquals(((current ushr 8) - (prior ushr 8)) and 255, input.readUnsignedByte())
                    assertEquals(((current and 255) - (prior and 255)) and 255, input.readUnsignedByte())
                }
            }
            assertEquals(-1,input.read())
        }
        MediaPng16Codec.open(file).use { source ->
            val full = source.read(MediaImageRegion(0, 0, 257, 65))
            assertArrayEquals(fixture(MediaImageRegion(0, 0, 257, 65), 3).pixels, full.pixels)
            val cropped = source.read(MediaImageRegion(101, 29, 83, 35, 2))
            for (y in 0 until cropped.height) for (x in 0 until cropped.width) for (c in 0..2)
                assertEquals(code(101 + x * 2, 29 + y * 2, c), cropped.unsigned((y * cropped.width + x) * 3 + c))
        }
    }

    @Test fun pngImportsIndependentInterlacedAndFiltered16BitImages() = directory { root ->
        for (interlaced in listOf(false, true)) for (channels in listOf(1, 2, 3, 4)) {
            val file = File(root, "external-$interlaced-$channels.png")
            javaClass.getResourceAsStream("/media-u16/external-$interlaced-$channels.png")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            MediaPng16Codec.open(file).use { source ->
                val full = source.read(MediaImageRegion(0, 0, 19, 13))
                fun expected(region: MediaImageRegion): ShortArray {
                    val colors = if (channels <= 2) 1 else 3
                    return ShortArray(region.outputWidth * region.outputHeight * colors) { i ->
                        val x = region.left + i / colors % region.outputWidth * region.step
                        val y = region.top + i / colors / region.outputWidth * region.step
                        val alpha = if (channels == 2 || channels == 4) code(x,y,channels-1) else 65535
                        ((code(x,y,i%colors).toLong() * alpha + 32767) / 65535).toShort()
                    }
                }
                assertArrayEquals(expected(MediaImageRegion(0, 0, 19, 13)), full.pixels)
                val part = source.read(MediaImageRegion(3, 5, 11, 6, 2))
                assertArrayEquals(expected(MediaImageRegion(3, 5, 11, 6, 2)), part.pixels)
            }
        }
    }

    @Test fun processingTheSameSamplesAsBandsOrOddSizedRegionsIsIdentical() = directory { root ->
        val file = File(root, "source.fit")
        MediaImageFitsCodec.write(file, fixture(MediaImageRegion(0, 0, 37, 43), 3), emptyMap())
        MediaImageFitsCodec.open(file).use { source ->
            val settings = MediaImageEditSettings(lp700 = true, redGain = 1.2f, blueGain = .8f, gamma = 1.4f, exposureStops = .3f, blackPoint = .02f, whitePoint = .94f)
            val window = MediaImageWindow(100f, 60000f); val gains = floatArrayOf(.9f, 1.1f, 1.2f)
            val full = source.read(MediaImageRegion(0, 0, 37, 43))
            MediaImageProcessing.apply(full, settings, gains, window)
            for (top in 0 until 43 step 7) for (left in 0 until 37 step 9) {
                val tile = source.read(MediaImageRegion(left, top, minOf(9, 37 - left), minOf(7, 43 - top)))
                MediaImageProcessing.apply(tile, settings, gains, window)
                for (y in 0 until tile.height) for (x in 0 until tile.width) for (c in 0..2)
                    assertEquals(full.unsigned(((top + y) * 37 + left + x) * 3 + c), tile.unsigned((y * tile.width + x) * 3 + c))
            }
        }
    }

    @Test fun fixedPointColorAndCurveAgreeWithTheNumericalReference() {
        val source = shortArrayOf(24001, 32003, 18007, 12013, 22021, 28027, 33001.toShort(), 25007, 29003)
        for (lp700 in listOf(false, true)) for (window in listOf(MediaImageWindow(0f,65535f), MediaImageWindow(10000f,30000f), MediaImageWindow(100f,110f))) {
            val image = MediaImagePixels(3, 1, 3, source.copyOf(), MediaImageWindow(0f,65535f))
            val settings = MediaImageEditSettings(lp700 = lp700, gamma = 1.4f, redGain = 1.1f, greenGain = .9f, blueGain = 1.2f, blackPoint = .01f, exposureStops = .2f)
            val gains = floatArrayOf(.85f, 1.05f, 1.15f)
            MediaImageProcessing.apply(image, settings, gains, window)
            for (pixel in 0..2) {
                val r = ((source[pixel * 3].toInt() and 65535) - window.black) / window.span
                val g = ((source[pixel * 3 + 1].toInt() and 65535) - window.black) / window.span
                val b = ((source[pixel * 3 + 2].toInt() and 65535) - window.black) / window.span
                val rgb = if (lp700) doubleArrayOf((-.3649013871 * r - .7934794639 * g + 2.7793761244 * b) * gains[0],
                    (.0713524433 * r + 1.0864368006 * g - .2364007716 * b) * gains[1],
                    (3.1869189013 * r - 1.5490042912 * g - 1.6877848393 * b) * gains[2]) else doubleArrayOf(r, g, b)
                val wb = doubleArrayOf(settings.redGain.toDouble(), settings.greenGain.toDouble(), settings.blueGain.toDouble())
                for (c in 0..2) rgb[c] = (rgb[c] * wb[c]).coerceAtLeast(0.0) * 2.0.pow(settings.exposureStops.toDouble())
                val luma = rgb[0] * .299 + rgb[1] * .587 + rgb[2] * .114
                val tone = ((luma - settings.blackPoint) / (settings.whitePoint - settings.blackPoint)).coerceIn(0.0, 1.0).pow(1.0 / settings.gamma)
                for (c in 0..2) {
                    val expected = (rgb[c] * tone / luma).coerceIn(0.0, 1.0) * 65535
                    assertEquals("LP=$lp700 pixel=$pixel channel=$c", expected, image.unsigned(pixel * 3 + c).toDouble(), 12.0)
                }
            }
        }
    }

    @Test fun curvesRetainThousandsOfDistinctLevelsAndRemainMonotonic() {
        val image = MediaImagePixels(65536, 1, 1, ShortArray(65536) { it.toShort() })
        MediaImageProcessing.apply(image, MediaImageEditSettings(gamma = 1.6f, blackPoint = .02f, whitePoint = .94f), floatArrayOf(1f, 1f, 1f))
        assertTrue(image.pixels.toSet().size > 10000)
        for (i in 1 until 65536) assertTrue(image.unsigned(i) >= image.unsigned(i - 1))
    }

    @Test fun exifTransformsPreserveTheRequestedPixelGridForAllOrientations() {
        for (orientation in 2..8) {
            val base = object : MediaPixelSource {
                override val width = 7; override val height = 5; override val channels = 1; override val range = MediaImageWindow(0f, 1f)
                override fun read(region: MediaImageRegion, check: () -> Unit) = MediaImagePixels(region.outputWidth, region.outputHeight, 1,
                    ShortArray(region.outputWidth * region.outputHeight) { i -> ((region.top + i / region.outputWidth * region.step) * 7 + region.left + i % region.outputWidth * region.step).toShort() })
                override fun close() = Unit
            }
            val source = OrientedMediaSource(base, orientation)
            val full = source.read(MediaImageRegion(0, 0, source.width, source.height))
            assertEquals(35, full.pixels.toSet().size)
            val expectedCorners = mapOf(2 to listOf(6,0,34,28), 3 to listOf(34,28,6,0), 4 to listOf(28,34,0,6),
                5 to listOf(0,28,6,34), 6 to listOf(28,0,34,6), 7 to listOf(34,6,28,0), 8 to listOf(6,34,0,28))
            assertEquals(expectedCorners.getValue(orientation), listOf(full.unsigned(0), full.unsigned(full.width - 1), full.unsigned((full.height - 1) * full.width), full.unsigned(full.width * full.height - 1)))
            val part = source.read(MediaImageRegion(1, 1, source.width - 2, source.height - 2, 2))
            for (y in 0 until part.height) for (x in 0 until part.width)
                assertEquals(full.unsigned((1 + y * 2) * full.width + 1 + x * 2), part.unsigned(y * part.width + x))
        }
    }

    @Test fun a24MegapixelBayerSourceReadsOnlyTheRequestedRegion() = directory { root ->
        val file = File(root, "24mp.fit")
        val header = mapOf("SIMPLE" to "T", "BITPIX" to "16", "NAXIS" to "2", "NAXIS1" to "6000", "NAXIS2" to "4000", "BZERO" to "32768", "BAYERPAT" to "'RGGB'")
        val text = header.entries.joinToString("") { (it.key.padEnd(8) + "= " + it.value).padEnd(80) } + "END".padEnd(80)
        RandomAccessFile(file, "rw").use { it.write(text.padEnd(2880).toByteArray()); it.setLength(2880 + 6000L * 4000 * 2) }
        MediaImageFitsCodec.open(file).use { source ->
            val part = source.read(MediaImageRegion(2501, 1801, 512, 512))
            assertEquals(3, part.channels); assertEquals(512 * 512 * 6L, part.byteSize)
            assertTrue(part.pixels.all { (it.toInt() and 65535) == 32768 })
        }
        val visible = checkNotNull(MediaViewportRegion.visible(6000, 4000, 1080, 800, 1f, 0f, 0f))
        assertEquals(1, visible.step); assertTrue(visible.outputWidth < 1500 && visible.outputHeight < 1250)
        assertTrue(visible.outputWidth.toLong() * visible.outputHeight * 6 < 12L * 1024 * 1024)
    }

    @Test fun nativeBitmapMemoryIsBudgetedSeparatelyFromTheManagedHeap() {
        val mb = 1024L * 1024
        assertTrue(MediaImageMemory.admitted(4 * mb, 96 * mb, 32 * mb, 1024 * mb))
        assertFalse(MediaImageMemory.admitted(96 * mb, 0, 32 * mb, 1024 * mb))
        assertFalse(MediaImageMemory.admitted(4 * mb, 96 * mb, 32 * mb, 64 * mb))
        assertTrue(MediaImageMemory.admitted(4 * mb, 96 * mb, 32 * mb, null))
    }

    @Test fun bayerTileEdgesReadTheirNeighborsOutsideTheRegion() = directory { root ->
        val file = File(root, "bayer.fit")
        val cards = linkedMapOf("SIMPLE" to "T", "BITPIX" to "16", "NAXIS" to "2", "NAXIS1" to "11", "NAXIS2" to "9", "BZERO" to "32768", "BAYERPAT" to "'GRBG'", "XBAYROFF" to "1", "YBAYROFF" to "1")
        val header = cards.entries.joinToString("") { (it.key.padEnd(8) + "= " + it.value).padEnd(80) } + "END".padEnd(80)
        DataOutputStream(file.outputStream()).use { output ->
            output.write(header.padEnd(2880).toByteArray())
            for (y in 0 until 9) for (x in 0 until 11) output.writeShort(((x * x * 99 + y * y * 277 + (x and 1) * 14000 + (y and 1) * 9000) and 65535) - 32768)
        }
        MediaImageFitsCodec.open(file).use { source ->
            val full = source.read(MediaImageRegion(0, 0, 11, 9))
            for (step in 1..3) {
                val part = source.read(MediaImageRegion(3, 1, 5, 7, step))
                for (y in 0 until part.height) for (x in 0 until part.width) for (c in 0..2)
                    assertEquals(full.unsigned(((1 + y * step) * 11 + 3 + x * step) * 3 + c), part.unsigned((y * part.width + x) * 3 + c))
            }
        }
    }

    @Test fun pngCancellationAndBadCrcAreDetected() = directory { root ->
        val file = File(root, "bad.png")
        MediaPng16Codec.write(file, 13, 17, 3) { fixture(it, 3) }
        RandomAccessFile(file, "rw").use { input -> input.seek(input.length() - 1); input.writeByte(0) }
        assertThrows(IllegalArgumentException::class.java) { MediaPng16Codec.open(file).use { it.read(MediaImageRegion(0, 0, 13, 17)) } }
        val cancelled = File(root, "cancelled.png")
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            MediaPng16Codec.write(cancelled, 20, 100, 3, { throw java.util.concurrent.CancellationException() }) { fixture(it, 3) }
        }
    }

    @Test fun pngTransparentSampleKeysAreCompositedAt16BitPrecision() = directory { root ->
        val file = File(root, "transparent-key.png")
        MediaPng16Codec.write(file, 3, 1, 3) { fixture(it, 3) }
        val original = file.readBytes()
        val key = java.io.ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { output ->
            val data = java.nio.ByteBuffer.allocate(6).putShort(0).putShort(10009).putShort(20018).array()
            val type = "tRNS".toByteArray(); val crc = java.util.zip.CRC32().apply { update(type); update(data) }
            output.writeInt(6); output.write(type); output.write(data); output.writeInt(crc.value.toInt())
        } }.toByteArray()
        file.writeBytes(original.copyOfRange(0, 33) + key + original.copyOfRange(33, original.size))
        MediaPng16Codec.open(file).use { source ->
            val image = source.read(MediaImageRegion(0, 0, 3, 1))
            for (c in 0..2) { assertEquals(0, image.unsigned(c)); assertEquals(code(1,0,c), image.unsigned(3+c)) }
        }
    }

    private fun fixture(region: MediaImageRegion, channels: Int) = MediaImagePixels(region.outputWidth, region.outputHeight, channels,
        ShortArray(region.outputWidth * region.outputHeight * channels) { i ->
            code(region.left + i / channels % region.outputWidth * region.step, region.top + i / channels / region.outputWidth * region.step, i % channels).toShort()
        })
    private fun code(x: Int, y: Int, channel: Int) = (x * 137 + y * 277 + channel * 10009) and 65535
    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("media-u16").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
