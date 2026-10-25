package com.indigo.mobileobservatory.media

import java.io.*
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** 16-bit PNG rows are filtered / compressed with bounded buffers. Pixel buffers are UInt16. */
object MediaPng16Codec {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    fun is16Bit(file: File): Boolean = file.inputStream().use { stream ->
        val header = ByteArray(25)
        var count = 0
        while (count < header.size) { val n = stream.read(header, count, header.size - count); if (n < 0) return false; count += n }
        header.copyOfRange(0, 8).contentEquals(signature) && header[24].toInt() == 16
    }

    fun write(file: File, width: Int, height: Int, channels: Int, check: () -> Unit = {},
              read: (MediaImageRegion) -> MediaImagePixels) {
        require(width > 0 && height > 0 && channels in listOf(1, 3))
        FileOutputStream(file).use { fileOutput ->
            val output = DataOutputStream(BufferedOutputStream(fileOutput, 64 * 1024))
            output.write(signature)
            val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
                it.writeInt(width); it.writeInt(height); it.writeByte(16); it.writeByte(if (channels == 3) 2 else 0)
                it.writeByte(0); it.writeByte(0); it.writeByte(0)
            } }.toByteArray()
            chunk(output, "IHDR", header)
            val idat = object : OutputStream() {
                private val buffer = ByteArray(32 * 1024); private var count = 0
                override fun write(value: Int) { buffer[count++] = value.toByte(); if (count == buffer.size) flush() }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    var remaining = length; var position = offset
                    while (remaining > 0) {
                        val size = minOf(remaining, buffer.size - count)
                        bytes.copyInto(buffer, count, position, position + size); position += size; count += size; remaining -= size
                        if (count == buffer.size) flush()
                    }
                }
                override fun flush() { if (count > 0) { chunk(output, "IDAT", buffer, count); count = 0 } }
                override fun close() = flush()
            }
            val compressor = java.util.zip.Deflater()
            try { DeflaterOutputStream(idat, compressor, 32 * 1024).use { compressed ->
                val row = ByteArray(Math.addExact(Math.multiplyExact(width, channels * 2), 1))
                // Sub filtering preserves smooth astronomical gradients without retaining another band.
                val bpp = channels * 2
                for (top in 0 until height step 32) {
                    check(); val band = read(MediaImageRegion(0, top, width, minOf(32, height - top)))
                    require(band.width == width && band.height == minOf(32, height - top) && band.channels == channels)
                    for (y in 0 until band.height) {
                        check(); row[0] = 1
                        for (x in 0 until width * channels) {
                            val value = band.unsigned(y * width * channels + x)
                            row[1 + x * 2] = (value ushr 8).toByte(); row[2 + x * 2] = value.toByte()
                        }
                        for (i in row.lastIndex downTo bpp + 1) row[i] = (row[i].toInt() - row[i - bpp].toInt()).toByte()
                        compressed.write(row)
                    }
                }
            } } finally { compressor.end() }
            chunk(output, "IEND", byteArrayOf()); output.flush(); fileOutput.fd.sync()
        }
    }

    private fun chunk(output: DataOutputStream, type: String, bytes: ByteArray, count: Int = bytes.size) {
        val name = type.toByteArray(Charsets.US_ASCII); val crc = CRC32()
        crc.update(name); crc.update(bytes, 0, count)
        output.writeInt(count); output.write(name); output.write(bytes, 0, count); output.writeInt(crc.value.toInt())
    }

    fun open(file: File): MediaPixelSource = PngSource(file)

    private class IdatStream(private val input: DataInputStream) : InputStream() {
        private var remaining = 0; private var active = false; private var end = false
        private val crc = CRC32()
        private fun next(): Boolean {
            while (!end && remaining == 0) {
                if (active) { require(input.readInt() == crc.value.toInt()) { "PNG CRC mismatch" }; active = false }
                val size = input.readInt(); require(size >= 0) { "Invalid PNG chunk" }
                val name = ByteArray(4).also(input::readFully); val type = name.toString(Charsets.US_ASCII)
                remaining = size; crc.reset(); crc.update(name); active = true
                if (type == "IDAT" && size > 0) return true
                val scratch = ByteArray(8192)
                while (remaining > 0) { val n = minOf(remaining, scratch.size); input.readFully(scratch, 0, n); crc.update(scratch, 0, n); remaining -= n }
                require(input.readInt() == crc.value.toInt()) { "PNG CRC mismatch" }; active = false
                if (type == "IEND") end = true
            }
            return !end
        }
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!next()) return -1
            val n = input.read(bytes, offset, minOf(length, remaining)); if (n < 0) throw EOFException("Truncated PNG data")
            crc.update(bytes, offset, n); remaining -= n; return n
        }
        override fun close() = input.close()
        fun finish() { val scratch = ByteArray(8192); while (read(scratch) >= 0) { /* Validate remaining chunk CRCs. */ } }
    }

    private class PngSource(private val file: File) : MediaPixelSource {
        override val width: Int
        override val height: Int
        override val channels: Int
        override val range = MediaImageWindow(0f, 1f)
        private val storedChannels: Int
        private val interlaced: Boolean
        private var inflater: DataInputStream? = null
        private var decompressor: java.util.zip.Inflater? = null
        private var idat: IdatStream? = null
        private var rowIndex = 0
        private var previous = ByteArray(0)
        private var current = ByteArray(0)
        private var transparentSamples: IntArray? = null
        init {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(ByteArray(8).also(input::readFully).contentEquals(signature))
                require(input.readInt() == 13 && input.readInt() == 0x49484452) { "Invalid PNG header" }
                val bytes = ByteArray(13).also(input::readFully)
                val crc = CRC32().apply { update("IHDR".toByteArray(Charsets.US_ASCII)); update(bytes) }
                require(input.readInt() == crc.value.toInt()) { "PNG CRC mismatch" }
                val data = DataInputStream(ByteArrayInputStream(bytes))
                width = data.readInt(); height = data.readInt()
                require(width > 0 && height > 0 && data.readUnsignedByte() == 16)
                val color = data.readUnsignedByte()
                storedChannels = when (color) { 0 -> 1; 2 -> 3; 4 -> 2; 6 -> 4; else -> error("Unsupported 16-bit PNG color type") }
                channels = if (color in listOf(0, 4)) 1 else 3
                require(data.readUnsignedByte() == 0 && data.readUnsignedByte() == 0)
                val interlace = data.readUnsignedByte(); require(interlace in 0..1); interlaced = interlace == 1
                Math.multiplyExact(width, storedChannels * 2)
                // tRNS contains one transparent sample key for grayscale/RGB PNGs.
                while (true) {
                    val size = input.readInt(); require(size >= 0)
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    if (type == "IDAT") break
                    if (type == "tRNS") {
                        require(storedChannels in listOf(1, 3) && size == storedChannels * 2)
                        val bytes = ByteArray(size).also(input::readFully)
                        val checksum = CRC32().apply { update(type.toByteArray(Charsets.US_ASCII)); update(bytes) }
                        require(input.readInt() == checksum.value.toInt()) { "PNG CRC mismatch" }
                        transparentSamples = IntArray(storedChannels) { c -> ((bytes[c * 2].toInt() and 255) shl 8) or (bytes[c * 2 + 1].toInt() and 255) }
                    } else {
                        var remaining = size.toLong() + 4
                        while (remaining > 0) { val n = input.skip(minOf(remaining, 8192)); if (n == 0L) { require(input.read() >= 0); remaining-- } else remaining -= n }
                    }
                }
            }
        }
        private fun reset() {
            close()
            val input = DataInputStream(BufferedInputStream(file.inputStream(), 64 * 1024))
            try {
                input.readFully(ByteArray(33))
                val chunks = IdatStream(input); val engine = java.util.zip.Inflater()
                idat = chunks; decompressor = engine
                inflater = DataInputStream(InflaterInputStream(chunks, engine, 64 * 1024))
            } catch (failure: Throwable) { input.close(); throw failure }
            rowIndex = 0; previous = ByteArray(width * storedChannels * 2); current = ByteArray(previous.size)
        }
        private fun unfilter(stream: DataInputStream, row: ByteArray, prior: ByteArray) {
            val filter = stream.readUnsignedByte(); require(filter in 0..4) { "Invalid PNG filter" }; stream.readFully(row)
            val bpp = storedChannels * 2
            for (i in row.indices) {
                val a = if (i >= bpp) row[i - bpp].toInt() and 255 else 0
                val b = prior[i].toInt() and 255; val c = if (i >= bpp) prior[i - bpp].toInt() and 255 else 0
                val predictor = when (filter) {
                    0 -> 0; 1 -> a; 2 -> b; 3 -> (a + b) / 2
                    else -> { val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                        if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                }
                row[i] = (row[i].toInt() + predictor).toByte()
            }
        }
        override fun read(region: MediaImageRegion, check: () -> Unit): MediaImagePixels {
            require(region.left.toLong() + region.width <= width && region.top.toLong() + region.height <= height)
            val count = region.outputWidth.toLong() * region.outputHeight * channels
            require(count <= Int.MAX_VALUE) { "memory_budget" }
            MediaImageMemory.check(count * 2 + width.toLong() * storedChannels * 4 + 256 * 1024)
            val pixels = ShortArray(count.toInt())
            fun copyPixel(row: ByteArray, rawX: Int, sourceX: Int, sourceY: Int) {
                if (sourceX < region.left || sourceY < region.top || sourceX >= region.left.toLong() + region.width || sourceY >= region.top.toLong() + region.height ||
                    (sourceX - region.left) % region.step != 0 || (sourceY - region.top) % region.step != 0) return
                val dest = (((sourceY - region.top) / region.step) * region.outputWidth + (sourceX - region.left) / region.step) * channels
                fun code(c: Int): Int { val i = (rawX * storedChannels + c) * 2; return ((row[i].toInt() and 255) shl 8) or (row[i + 1].toInt() and 255) }
                val alpha = if (storedChannels == 2 || storedChannels == 4) code(storedChannels - 1)
                    else if (transparentSamples?.let { key -> key.indices.all { code(it) == key[it] } } == true) 0 else 65535
                for (c in 0 until channels) pixels[dest + c] = ((code(c).toLong() * alpha + 32767) / 65535).toShort()
            }
            if (interlaced) {
                reset(); val stream = inflater!!
                val passes = arrayOf(intArrayOf(0,0,8,8), intArrayOf(4,0,8,8), intArrayOf(0,4,4,8), intArrayOf(2,0,4,4), intArrayOf(0,2,2,4), intArrayOf(1,0,2,2), intArrayOf(0,1,1,2))
                for ((x0, y0, dx, dy) in passes) {
                    val pw = if (width <= x0) 0 else (width - x0 + dx - 1) / dx
                    val ph = if (height <= y0) 0 else (height - y0 + dy - 1) / dy
                    if (pw == 0 || ph == 0) continue
                    var prior = ByteArray(pw * storedChannels * 2); var row = ByteArray(prior.size)
                    for (y in 0 until ph) {
                        check(); unfilter(stream, row, prior)
                        for (x in 0 until pw) copyPixel(row, x, x0 + x * dx, y0 + y * dy)
                        val swap = prior; prior = row; row = swap
                    }
                }
                require(stream.read() == -1) { "Unexpected PNG pixel data" }; idat!!.finish()
            } else {
                if (inflater == null || region.top < rowIndex) reset()
                val last = region.top + (region.outputHeight - 1) * region.step
                while (rowIndex <= last) {
                    check(); unfilter(inflater!!, current, previous)
                    if (rowIndex >= region.top && (rowIndex - region.top) % region.step == 0)
                        for (x in 0 until region.outputWidth) { val sx = region.left + x * region.step; copyPixel(current, sx, sx, rowIndex) }
                    val swap = previous; previous = current; current = swap; rowIndex++
                }
                if (rowIndex == height) { require(inflater!!.read() == -1) { "Unexpected PNG pixel data" }; idat!!.finish() }
            }
            return MediaImagePixels(region.outputWidth, region.outputHeight, channels, pixels)
        }
        override fun close() { try { inflater?.close() } finally { inflater = null; decompressor?.end(); decompressor = null; idat = null } }
    }
}
