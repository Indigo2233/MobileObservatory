package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.util.FitsHeaderParser
import com.indigo.mobileobservatory.camera.CameraCfaLayout
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Convert the album's bounded UInt16 samples to the live renderer's wire format. */
internal fun MediaImagePixels.cameraFrame(gain: Double = 1.0, displayNormalized: Boolean = false,
                                         check: () -> Unit = {}): CameraCfaLayout.Snapshot {
    val bytes = width.toLong() * height * if (displayNormalized) 3 else channels * 2
    require(bytes <= Int.MAX_VALUE) { "memory_budget" }
    MediaImageMemory.check(bytes)
    val data = ByteArray(bytes.toInt())
    if (displayNormalized) {
        for (pixel in 0 until width * height) {
            if (pixel % 8192 == 0) check()
            for (channel in 0..2) data[pixel * 3 + channel] =
                ((unsigned(pixel * channels + if (channels == 1) 0 else channel) + 128) / 257).toByte()
        }
    } else for (i in pixels.indices) {
        if (i % 8192 == 0) check()
        val code = (unsigned(i) * gain).roundToInt().coerceIn(0, 65535)
        data[i * 2] = code.toByte(); data[i * 2 + 1] = (code ushr 8).toByte()
    }
    val format = if (displayNormalized) PixelFormat.RGB24 else if (channels == 3) PixelFormat.RGB48 else PixelFormat.MONO16
    return CameraCfaLayout.Snapshot(FrameData(data, width, height, format, 0, 0),
        PixelTraits(validBits = format.nativeBits, alignment = SampleAlignment.RIGHT, bayerPatternAtOutput = true))
}

data class DecodedMediaImage(
    val image: MediaImagePixels,
    val originalWidth: Int,
    val originalHeight: Int,
    val header: Map<String, String> = emptyMap(),
    val displayNormalized: Boolean = false
)

/** Uncompressed primary FITS images; Bayer samples are demosaiced at their original CFA positions. */
object MediaImageFitsCodec {
    data class CameraImage(val snapshot: CameraCfaLayout.Snapshot, val originalWidth: Int, val originalHeight: Int,
                           val region: MediaImageRegion)

    /** File transport for the existing camera renderer. No display processing happens here. */
    fun readCameraImage(file: File, maximumSide: Int = 1600, region: MediaImageRegion? = null,
                        includeHalo: Boolean = false, check: () -> Unit = {}): CameraImage = RandomAccessFile(file, "r").use { input ->
        require(maximumSide > 0)
        val source = FitsSource(input, null, check)
        val step = ceil(maxOf(source.width, source.height).toDouble() / maximumSide).toInt().coerceAtLeast(1)
        var selected = region ?: MediaImageRegion(0, 0, source.width, source.height, step)
        require(selected.left.toLong() + selected.width <= source.width && selected.top.toLong() + selected.height <= source.height)
        if (includeHalo && selected.step == 1 && source.header["BAYERPAT"] != null) {
            val left = (selected.left - 2).coerceAtLeast(0); val top = (selected.top - 2).coerceAtLeast(0)
            val right = (selected.left.toLong() + selected.width + 2).coerceAtMost(source.width.toLong()).toInt()
            val bottom = (selected.top.toLong() + selected.height + 2).coerceAtMost(source.height.toLong()).toInt()
            selected = MediaImageRegion(left, top, right - left, bottom - top)
        }
        CameraImage(source.cameraFrame(selected, check), source.width, source.height, selected)
    }

    fun open(file: File, rangeHint: MediaImageWindow? = null, check: () -> Unit = {}): MediaPixelSource {
        val input = RandomAccessFile(file, "r")
        return try { FitsSource(input, rangeHint, check) } catch (failure: Throwable) { input.close(); throw failure }
    }

    fun read(file: File, maximumSide: Int = Int.MAX_VALUE, check: () -> Unit = {},
             admit: (Long) -> Unit = {}, rangeHint: MediaImageWindow? = null): DecodedMediaImage =
        open(file, rangeHint, check).use { source ->
            require(maximumSide > 0)
            val step = ceil(maxOf(source.width, source.height).toDouble() / maximumSide).toInt().coerceAtLeast(1)
            val region = MediaImageRegion(0, 0, source.width, source.height, step)
            admit(region.outputWidth.toLong() * region.outputHeight * source.channels * 2)
            DecodedMediaImage(source.read(region, check), source.width, source.height, source.header, source.displayNormalized)
        }

    private class FitsSource(private val input: RandomAccessFile, hint: MediaImageWindow?, check: () -> Unit) : MediaPixelSource {
        private val h = FitsHeaderParser.read(input)
        override val header = h.cards
        override val width = h.intValue("NAXIS1") ?: 0
        override val height = h.intValue("NAXIS2") ?: 0
        private val axes = h.intValue("NAXIS") ?: 0
        private val planes = if (axes == 3) h.intValue("NAXIS3") ?: 0 else 1
        private val bitpix = h.intValue("BITPIX") ?: 0
        private val bytesPerSample = abs(bitpix) / 8
        private val scale = h.doubleValue("BSCALE") ?: 1.0
        private val zero = h.doubleValue("BZERO") ?: 0.0
        private val pattern = h["BAYERPAT"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        override val channels = if (pattern != null || planes == 3) 3 else 1
        override val displayNormalized = h["EDITED"] == "T" && h["BUNIT"] in listOf("normalized display", "display intensity")
        override val range: MediaImageWindow
        init {
            require(h["SIMPLE"] == "T" && axes in 2..3 && width > 0 && height > 0 && planes in listOf(1, 3)) { "Unsupported FITS image geometry" }
            require(bitpix in listOf(8, 16, 32, -32)) { "Unsupported FITS sample type" }
            val bytes = Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), planes * bytesPerSample.toLong())
            require(bytes <= input.length() - h.dataOffset) { "Truncated FITS image" }
            require(scale.isFinite() && zero.isFinite()) { "Invalid FITS scaling" }
            require(pattern == null || (planes == 1 && width >= 2 && height >= 2 && pattern in listOf("RGGB", "GRBG", "GBRG", "BGGR"))) { "Unsupported Bayer pattern" }
            range = hint ?: when {
                bitpix == 8 -> physicalRange(0.0 * scale + zero, 255.0 * scale + zero)
                bitpix == 16 -> physicalRange(-32768.0 * scale + zero, 32767.0 * scale + zero)
                displayNormalized && bitpix == -32 -> MediaImageWindow(0f, 1f)
                else -> scanRange(bytes, check)
            }
        }
        private fun physicalRange(first: Double, second: Double): MediaImageWindow {
            val low = minOf(first, second).toFloat(); val high = maxOf(first, second).toFloat()
            require(low.isFinite() && high.isFinite()) { "Non-finite FITS sample" }
            return if (high > low) MediaImageWindow(low, high) else MediaImageWindow(minOf(0f, low), maxOf(1f, high + 1))
        }
        private fun value(data: ByteBuffer, offset: Int): Double {
            val raw = when (bitpix) { 8 -> (data.get(offset).toInt() and 255).toDouble()
                16 -> data.getShort(offset).toDouble(); 32 -> data.getInt(offset).toDouble(); else -> data.getFloat(offset).toDouble() }
            val result = raw * scale + zero
            require(result.isFinite()) { "Non-finite FITS sample" }
            return result
        }
        private fun scanRange(bytes: Long, check: () -> Unit): MediaImageWindow {
            val block = ByteArray(64 * 1024); val view = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN)
            var remaining = bytes; var low = Double.POSITIVE_INFINITY; var high = Double.NEGATIVE_INFINITY
            input.seek(h.dataOffset)
            while (remaining > 0) {
                check(); val length = minOf(remaining, block.size.toLong()).toInt(); input.readFully(block, 0, length)
                for (i in 0 until length step bytesPerSample) { val v = value(view, i); low = minOf(low, v); high = maxOf(high, v) }
                remaining -= length
            }
            return physicalRange(low, high)
        }
        fun cameraFrame(region: MediaImageRegion, check: () -> Unit): CameraCfaLayout.Snapshot {
            require(region.left.toLong() + region.width <= width && region.top.toLong() + region.height <= height)
            val rawInteger = !displayNormalized && planes == 1 && scale == 1.0 &&
                ((bitpix == 8 && zero == 0.0) || (bitpix == 16 && zero == 32768.0))
            val bits = if (bitpix == 8) 8 else h.intValue("VALIDBIT")?.takeIf { it in listOf(10, 12, 14, 16) } ?: 16
            if (rawInteger && region.step > 1) return reducedCameraFrame(region,bits,check)
            if (rawInteger && region.step == 1) {
                val bytes = region.width.toLong() * region.height * bytesPerSample
                MediaImageMemory.check(bytes + region.width.toLong() * bytesPerSample)
                require(bytes <= Int.MAX_VALUE) { "memory_budget" }
                val data = ByteArray(bytes.toInt())
                val row = ByteArray(region.width * bytesPerSample)
                val view = ByteBuffer.wrap(row).order(ByteOrder.BIG_ENDIAN)
                for (y in 0 until region.height) {
                    check()
                    input.seek(h.dataOffset + ((region.top + y).toLong() * width + region.left) * bytesPerSample)
                    input.readFully(row)
                    for (x in 0 until region.width) {
                        val value = if (bitpix == 8) row[x].toInt() and 255 else view.getShort(x * 2).toInt() + 32768
                        val i = (y * region.width + x) * bytesPerSample
                        data[i] = value.toByte()
                        if (bytesPerSample == 2) data[i + 1] = (value ushr 8).toByte()
                    }
                }
                val prefix = when (pattern) { "RGGB" -> "BAYER_RG"; "GRBG" -> "BAYER_GR";
                    "GBRG" -> "BAYER_GB"; "BGGR" -> "BAYER_BG"; else -> "MONO" }
                val traits = PixelTraits(validBits = bits, alignment = SampleAlignment.RIGHT,
                    roiX = region.left + (h.intValue("XBAYROFF") ?: 0), roiY = region.top + (h.intValue("YBAYROFF") ?: 0),
                    bayerPatternAtOutput = false)
                val format = CameraCfaLayout.displayFormat(PixelFormat.valueOf("$prefix$bits"), traits)
                return CameraCfaLayout.Snapshot(FrameData(data, region.width, region.height, format, 0, 0),
                    traits.copy(bayerPatternAtOutput = true))
            }
            // Reuse the album's bounded area sampling for reduced images and imported FITS types.
            val image = read(region, check)
            val gain = if (rawInteger && bitpix == 16 && bits < 16) 65535.0 / ((1 shl bits) - 1) else 1.0
            return image.cameraFrame(gain, displayNormalized, check)
        }

        /** Average each CFA parity separately so reduced camera views keep the live raw
         * histogram, white-balance and clipping order. Only one source row is retained. */
        private fun reducedCameraFrame(region: MediaImageRegion,bits: Int,check: () -> Unit): CameraCfaLayout.Snapshot {
            val w=region.outputWidth; val h=region.outputHeight
            val size=w.toLong()*h*bytesPerSample
            require(size<=Int.MAX_VALUE) { "memory_budget" }
            MediaImageMemory.check(size+region.width.toLong()*bytesPerSample+w*12L)
            val data=ByteArray(size.toInt())
            val row=ByteBuffer.allocate(region.width*bytesPerSample).order(ByteOrder.BIG_ENDIAN)
            val sums=LongArray(w); val counts=IntArray(w)
            val mosaic=pattern!=null
            val period=if(mosaic) 2 else 1
            val cell=period.toLong()*region.step
            for(y in 0 until h) {
                sums.fill(0); counts.fill(0)
                val firstY=((y/period)*cell+y%period).toInt()
                val lastY=minOf((y/period+1)*cell,region.height.toLong()).toInt()
                for(sy in firstY until lastY step period) {
                    check(); row.clear()
                    input.seek(this.h.dataOffset+((region.top+sy).toLong()*width+region.left)*bytesPerSample)
                    input.readFully(row.array())
                    for(sx in 0 until region.width) {
                        val x=((sx/cell)*period+sx%period).toInt()
                        if(x>=w) continue
                        val code=if(bitpix==8) row.get(sx).toInt() and 255 else row.getShort(sx*2).toInt()+32768
                        sums[x]+=code.toLong(); counts[x]++
                    }
                }
                for(x in 0 until w) {
                    val code=if(counts[x]>0) ((sums[x]+counts[x]/2)/counts[x]).toInt() else 0
                    val i=(y*w+x)*bytesPerSample
                    data[i]=code.toByte()
                    if(bytesPerSample==2) data[i+1]=(code ushr 8).toByte()
                }
            }
            val prefix=when(pattern) { "RGGB"->"BAYER_RG"; "GRBG"->"BAYER_GR";
                "GBRG"->"BAYER_GB"; "BGGR"->"BAYER_BG"; else->"MONO" }
            val traits=PixelTraits(validBits=bits,alignment=SampleAlignment.RIGHT,
                roiX=region.left+(this.h.intValue("XBAYROFF")?:0),roiY=region.top+(this.h.intValue("YBAYROFF")?:0),
                bayerPatternAtOutput=false)
            val format=CameraCfaLayout.displayFormat(PixelFormat.valueOf("$prefix$bits"),traits)
            return CameraCfaLayout.Snapshot(FrameData(data,w,h,format,0,0),traits.copy(bayerPatternAtOutput=true))
        }

        override fun read(region: MediaImageRegion, check: () -> Unit): MediaImagePixels {
            require(region.left.toLong() + region.width <= width && region.top.toLong() + region.height <= height)
            if (region.step > 1) return readReduced(region, check)
            val count = region.outputWidth.toLong() * region.outputHeight * channels
            require(count <= Int.MAX_VALUE) { "memory_budget" }
            val x0 = (region.left - 1).coerceAtLeast(0)
            val x1 = (region.left.toLong() + region.width + 1).coerceAtMost(width.toLong()).toInt()
            val rowBytes = Math.multiplyExact(x1 - x0, bytesPerSample)
            MediaImageMemory.check(count * 2 + rowBytes.toLong() * 12)
            val pixels = ShortArray(count.toInt())
            val rowKeys = LongArray(12) { -1 }
            val rowBuffers = arrayOfNulls<ByteBuffer>(12)
            var replacement = 0
            fun row(plane: Int, y: Int): ByteBuffer {
                val key = plane.toLong() * height + y
                for (i in rowKeys.indices) if (rowKeys[i] == key) return rowBuffers[i]!!
                val slot = replacement; replacement = (replacement + 1) % rowKeys.size
                val buffer = rowBuffers[slot] ?: ByteBuffer.allocate(rowBytes).order(ByteOrder.BIG_ENDIAN).also { rowBuffers[slot] = it }
                buffer.clear()
                val offset = h.dataOffset + (key * width + x0) * bytesPerSample
                while (buffer.hasRemaining()) { check(); require(input.channel.read(buffer, offset + buffer.position()) > 0) { "Truncated FITS row" } }
                rowKeys[slot] = key
                return buffer
            }
            fun reflect(v: Int, size: Int) = when { v < 0 -> -v; v >= size -> 2 * size - 2 - v; else -> v }.coerceIn(0, size - 1)
            fun raw(buffer: ByteBuffer, x: Int) = value(buffer, (reflect(x, width) - x0) * bytesPerSample)
            val redX = ((if (pattern == "GRBG" || pattern == "BGGR") 1 else 0) - (h.intValue("XBAYROFF") ?: 0)) and 1
            val redY = ((if (pattern == "GBRG" || pattern == "BGGR") 1 else 0) - (h.intValue("YBAYROFF") ?: 0)) and 1
            for (y in 0 until region.outputHeight) {
                check(); val sy = region.top + y * region.step
                val centerRow = row(0, sy)
                val firstRow = if (pattern != null) row(0, reflect(sy - 1, height)) else if (channels == 3) row(1, sy) else centerRow
                val lastRow = if (pattern != null) row(0, reflect(sy + 1, height)) else if (channels == 3) row(2, sy) else centerRow
                for (x in 0 until region.outputWidth) {
                    val sx = region.left + x * region.step; val i = (y * region.outputWidth + x) * channels
                    if (pattern == null) {
                        pixels[i] = range.encode(raw(centerRow, sx))
                        if (channels == 3) { pixels[i + 1] = range.encode(raw(firstRow, sx)); pixels[i + 2] = range.encode(raw(lastRow, sx)) }
                    }
                    else {
                        val center = raw(centerRow, sx)
                        val horizontal = (raw(centerRow, sx - 1) + raw(centerRow, sx + 1)) * .5
                        val vertical = (raw(firstRow, sx) + raw(lastRow, sx)) * .5
                        val diagonal = (raw(firstRow, sx - 1) + raw(firstRow, sx + 1) + raw(lastRow, sx - 1) + raw(lastRow, sx + 1)) * .25
                        val red = sx and 1 == redX && sy and 1 == redY
                        val blue = sx and 1 != redX && sy and 1 != redY
                        pixels[i] = range.encode(when { red -> center; blue -> diagonal; sy and 1 == redY -> horizontal; else -> vertical })
                        pixels[i + 1] = range.encode(if (red || blue) (horizontal + vertical) * .5 else center)
                        pixels[i + 2] = range.encode(when { blue -> center; red -> diagonal; sy and 1 == redY -> vertical; else -> horizontal })
                    }
                }
            }
            return MediaImagePixels(region.outputWidth, region.outputHeight, channels, pixels, range)
        }

        /** Area averages include every source sample; small stars survive between preview pixels.
         * Bayer channel means retain the original CFA phase, including odd crop origins. Only
         * UInt16 output and a row of integer accumulators are retained. Native 1:1 stays exact. */
        private fun readReduced(region: MediaImageRegion, check: () -> Unit): MediaImagePixels {
            val rowSamples = Math.multiplyExact(region.outputWidth, channels)
            val count = rowSamples.toLong() * region.outputHeight
            require(count <= Int.MAX_VALUE) { "memory_budget" }
            val rowBytes = Math.multiplyExact(region.width, bytesPerSample)
            MediaImageMemory.check(count * 2 + rowBytes + rowSamples * 12L)
            val pixels = ShortArray(count.toInt())
            val sums = LongArray(rowSamples)
            val counts = IntArray(rowSamples)
            val buffer = ByteBuffer.allocate(rowBytes).order(ByteOrder.BIG_ENDIAN)
            val redX = ((if (pattern == "GRBG" || pattern == "BGGR") 1 else 0) - (h.intValue("XBAYROFF") ?: 0)) and 1
            val redY = ((if (pattern == "GBRG" || pattern == "BGGR") 1 else 0) - (h.intValue("YBAYROFF") ?: 0)) and 1
            for (y in 0 until region.outputHeight) {
                sums.fill(0); counts.fill(0)
                val y0 = region.top + y * region.step
                val y1 = minOf(y0.toLong() + region.step, region.top.toLong() + region.height).toInt()
                for (plane in 0 until planes) for (sy in y0 until y1) {
                    check(); buffer.clear()
                    val offset = h.dataOffset + ((plane.toLong() * height + sy) * width + region.left) * bytesPerSample
                    while (buffer.hasRemaining()) {
                        check()
                        require(input.channel.read(buffer, offset + buffer.position()) > 0) { "Truncated FITS row" }
                    }
                    for (x in 0 until region.width) {
                        val sx = region.left + x
                        val channel = if (pattern == null) plane else when {
                            sx and 1 == redX && sy and 1 == redY -> 0
                            sx and 1 != redX && sy and 1 != redY -> 2
                            else -> 1
                        }
                        val i = (x / region.step) * channels + channel
                        sums[i] += (range.encode(value(buffer, x * bytesPerSample)).toInt() and 65535).toLong()
                        counts[i]++
                    }
                }
                for (x in 0 until region.outputWidth) {
                    var edge: MediaImagePixels? = null
                    for (c in 0 until channels) {
                        val i = x * channels + c
                        pixels[y * rowSamples + i] = if (counts[i] > 0) {
                            ((sums[i] + counts[i] / 2) / counts[i]).toShort()
                        } else {
                            // A one-pixel edge of a Bayer tile needs neighbouring CFA colours.
                            val full = edge ?: read(MediaImageRegion(region.left + x * region.step, y0, 1, 1), check).also { edge = it }
                            full.pixels[c]
                        }
                    }
                }
            }
            return MediaImagePixels(region.outputWidth, region.outputHeight, channels, pixels, range)
        }
        override fun close() = input.close()
    }

    /** UInt16 FITS uses the standard signed storage + BZERO=32768 convention, planar RGB. */
    fun write(file: File, image: MediaImagePixels, metadata: Map<String, String>, check: () -> Unit = {}) =
        writeBands(file, image.width, image.height, image.channels, metadata, check) { region ->
            MediaImagePixels(region.width, region.height, image.channels, image.pixels.copyOfRange(
                region.top * image.width * image.channels, (region.top + region.height) * image.width * image.channels))
        }

    fun writeBands(file: File, width: Int, height: Int, channels: Int, metadata: Map<String, String>,
                   check: () -> Unit = {}, read: (MediaImageRegion) -> MediaImagePixels) {
        val sourceHeader = metadata.filterKeys { it.startsWith("fits.") }.mapKeys { it.key.removePrefix("fits.") }
        val cards = mutableListOf(card("SIMPLE", "T"), card("BITPIX", "16"),
            card("NAXIS", if (channels == 3) "3" else "2"), card("NAXIS1", width.toString()), card("NAXIS2", height.toString()))
        if (channels == 3) cards += card("NAXIS3", "3")
        cards += card("EXTEND", "T")
        cards += card("BSCALE", "1")
        cards += card("BZERO", "32768")
        cards += card("DATAMIN", "0")
        cards += card("DATAMAX", "65535")
        cards += card("BUNIT", quote("display intensity"))
        cards += card("COLORSPC", quote(if (channels == 3) "RGB" else "MONO"))
        cards += card("EDITED", "T")
        cards += card("DATE", quote(Instant.now().toString()))
        cards += card("SOFTWARE", quote("Indigo Observatory"))
        // Geometry and row ordering are retained, so the source WCS remains applicable.
        for ((key, value) in sourceHeader) {
            if (key in listOf("DATE-OBS", "INSTRUME", "OBJECT", "FILTER", "CTYPE1", "CTYPE2", "CUNIT1", "CUNIT2", "RADESYS", "WCSNAME")) {
                cards += card(key, quote(value))
            } else if (key in listOf("EXPOSURE", "EXPTIME", "GAIN", "GAINDB", "ISOSPEED", "XPIXSZ", "YPIXSZ", "XBINNING", "YBINNING", "FOCALLEN", "EQUINOX", "WCSAXES", "LONPOLE", "LATPOLE", "PIXSCALE", "FOVW", "FOVH") ||
                key.matches(Regex("(CRPIX|CRVAL|CDELT|CROTA)[12]|(CD|PC)[12]_[12]|PV[12]_[0-9]+|(A|B|AP|BP)_ORDER|(A|B|AP|BP)_[0-9]+_[0-9]+"))) {
                if (value.replace('D', 'E', ignoreCase = true).toDoubleOrNull()?.isFinite() == true) cards += card(key, value.replace('d', 'D'))
            }
        }
        cards += "HISTORY  Image-library edits are baked into the 16-bit display values.".padEnd(80).take(80)
        val json = metadataJson(metadata).toByteArray(Charsets.UTF_8)
        require(json.size <= 16 * 1024 * 1024)
        RandomAccessFile(file, "rw").use { output ->
            val header = headerBytes(cards)
            output.write(header)
            val start = output.filePointer
            val dataBytes = Math.multiplyExact(width.toLong() * height, channels * 2L)
            val padding = (2880 - dataBytes % 2880) % 2880
            val extension = headerBytes(listOf(card("XTENSION", quote("IMAGE   ")), card("BITPIX", "8"), card("NAXIS", "1"),
                card("NAXIS1", json.size.toString()), card("PCOUNT", "0"), card("GCOUNT", "1"),
                card("EXTNAME", quote("EDITMETA")), card("ENCODING", quote("UTF-8 JSON"))))
            output.setLength(start + dataBytes + padding)
            val row = ByteArray(Math.multiplyExact(width, 2))
            for (top in 0 until height step 32) {
                check()
                val bandHeight = minOf(32, height - top)
                val band = read(MediaImageRegion(0, top, width, bandHeight))
                require(band.width == width && band.height == bandHeight && band.channels == channels)
                for (c in 0 until channels) for (y in 0 until bandHeight) {
                    check()
                    for (x in 0 until width) {
                        val signed = band.unsigned((y * width + x) * channels + c) - 32768
                        row[x * 2] = (signed shr 8).toByte(); row[x * 2 + 1] = signed.toByte()
                    }
                    output.seek(start + ((c.toLong() * height + top + y) * width) * 2)
                    output.write(row)
                }
            }
            check(); output.seek(start + dataBytes + padding); output.write(extension); output.write(json)
            output.write(ByteArray(((2880 - json.size % 2880) % 2880))); output.fd.sync()
        }
    }

    private fun headerBytes(cards: List<String>): ByteArray {
        val text = (cards + "END".padEnd(80)).joinToString("")
        return text.padEnd(((text.length + 2879) / 2880) * 2880).toByteArray(Charsets.US_ASCII)
    }

    fun metadataJson(values: Map<String, String>): String = values.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
        "${jsonString(it.key)}:${jsonString(it.value)}"
    }
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
    private fun quote(value: String): String = buildString {
        append('\'')
        for (c in value) {
            val escaped = if (c == '\'') "''" else if (c.code in 32..126) c.toString() else "?"
            if (length + escaped.length > 67) break
            append(escaped)
        }
        append('\'')
    }
    private fun card(key: String, value: String) =
        (key.padEnd(8).take(8) + "= " + if (value.startsWith("'")) value else value.padStart(20)).padEnd(80).take(80)
}
