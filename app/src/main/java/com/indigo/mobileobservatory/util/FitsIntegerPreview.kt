package com.indigo.mobileobservatory.util

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Cached, pre-tone-map representation of an integer FITS preview.
 *
 * File parsing, Bayer downsampling and demosaicing happen once. Display-only LP750
 * conversion can then be rendered repeatedly without reading the source file again.
 */
class FitsIntegerPreview(
    val width: Int,
    val height: Int,
    val info: String,
    private val basePixels: IntArray,
    private val lp750Gains: FloatArray?
) {
    fun render(applyLp750: Boolean, cancelled: () -> Boolean = { false }): IntArray {
        val pixels = basePixels.copyOf()
        val gains = lp750Gains
        if (applyLp750 && gains != null) {
            for (i in pixels.indices) {
                if (i % width == 0 && cancelled()) throw CancellationException("FITS preview cancelled")
                val pixel = pixels[i]
                pixels[i] = ImageUtils.lp750Argb(
                    ((pixel ushr 16) and 0xFF) / 255f,
                    ((pixel ushr 8) and 0xFF) / 255f,
                    (pixel and 0xFF) / 255f,
                    gains[0],
                    gains[1],
                    gains[2]
                )
            }
            ImageUtils.applyLp750BackgroundWhiteBalance(pixels)
        }
        FitsDisplayStretch.applyDisplayToneCurve(pixels)
        return pixels
    }
}

/** Decoder for conventional integer FITS files used by camera captures. */
object FitsIntegerPreviewDecoder {
    private const val FITS_BLOCK_SIZE = 2880
    private const val FITS_CARD_SIZE = 80
    private const val MAX_BAYER_SIDE = 1600

    fun read(file: File, cancelled: () -> Boolean = { false }): FitsIntegerPreview {
        RandomAccessFile(file, "r").use { input ->
            val header = readHeader(input)
            require(header.width > 0 && header.height > 0) {
                "Invalid FITS: ${header.width}x${header.height}"
            }

            val bytesPerSample = when (header.bitpix) {
                8 -> 1
                16 -> 2
                32, -32 -> 4
                else -> throw IllegalArgumentException("Unsupported FITS BITPIX=${header.bitpix}")
            }
            val channels = if (header.naxis >= 3) header.naxis3 else 1
            val sourceSamples = header.width.toLong() * header.height * channels
            val dataBytes = sourceSamples * bytesPerSample
            require(dataBytes > 0 && header.dataOffset + dataBytes <= input.length()) {
                "Truncated FITS image data"
            }
            require(dataBytes <= Int.MAX_VALUE) { "FITS image is too large to preview" }

            val data = input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                header.dataOffset,
                dataBytes
            ).order(ByteOrder.BIG_ENDIAN)

            return decode(header, channels, bytesPerSample, data, cancelled)
        }
    }

    private fun decode(
        header: Header,
        channels: Int,
        bytesPerSample: Int,
        data: ByteBuffer,
        cancelled: () -> Boolean
    ): FitsIntegerPreview {
        val sourceWidth = header.width
        val sourceHeight = header.height
        // An even scale preserves the Bayer phase at every preview pixel.
        val bayerScale = if (header.bayerPattern != null) {
            val initial = ceil(maxOf(sourceWidth, sourceHeight) / MAX_BAYER_SIDE.toDouble())
                .toInt().coerceAtLeast(1)
            if (initial > 1 && initial % 2 != 0) initial + 1 else initial
        } else {
            1
        }
        val width = if (bayerScale > 1) (sourceWidth / bayerScale).coerceAtLeast(2) else sourceWidth
        val height = if (bayerScale > 1) (sourceHeight / bayerScale).coerceAtLeast(2) else sourceHeight
        val pixelCount = width * height

        fun readSample(sampleIndex: Long): Int {
            val byteOffset = Math.multiplyExact(sampleIndex, bytesPerSample.toLong()).toInt()
            val raw = when (header.bitpix) {
                8 -> data.get(byteOffset).toInt() and 0xFF
                16 -> data.getShort(byteOffset).toInt()
                32 -> data.getInt(byteOffset)
                -32 -> data.getFloat(byteOffset).toInt()
                else -> error("Unsupported FITS BITPIX=${header.bitpix}")
            }
            return (raw.toDouble() * header.bscale + header.bzero).roundToInt()
        }

        val pixels = IntArray(pixelCount)
        var lp750Gains: FloatArray? = null
        if (channels == 1) {
            val rawValues = IntArray(pixelCount)
            if (bayerScale == 1) {
                for (y in 0 until height) {
                    if (cancelled()) throw CancellationException("FITS preview cancelled")
                    val rowOffset = y.toLong() * sourceWidth
                    for (x in 0 until width) rawValues[y * width + x] = readSample(rowOffset + x)
                }
            } else {
                // Read only samples that contribute to the phase-preserving preview.
                // This avoids scanning every full-resolution source pixel.
                for (y in 0 until height) {
                    if (cancelled()) throw CancellationException("FITS preview cancelled")
                    val sourceYStart = y * bayerScale
                    val sourceYEnd = minOf(sourceYStart + bayerScale, sourceHeight)
                    val firstSourceY = firstWithParity(sourceYStart, y)
                    for (x in 0 until width) {
                        val sourceXStart = x * bayerScale
                        val sourceXEnd = minOf(sourceXStart + bayerScale, sourceWidth)
                        val firstSourceX = firstWithParity(sourceXStart, x)
                        var sum = 0L
                        var count = 0
                        var sourceY = firstSourceY
                        while (sourceY < sourceYEnd) {
                            val rowOffset = sourceY.toLong() * sourceWidth
                            var sourceX = firstSourceX
                            while (sourceX < sourceXEnd) {
                                sum += readSample(rowOffset + sourceX)
                                count++
                                sourceX += 2
                            }
                            sourceY += 2
                        }
                        rawValues[y * width + x] = if (count == 0) 0 else (sum / count).toInt()
                    }
                }
            }

            val displayWindow = FitsDisplayStretch.window(rawValues)
            if (header.bayerPattern != null) {
                lp750Gains = lp750WhiteBalance(
                    rawValues,
                    width,
                    height,
                    header.bayerPattern,
                    displayWindow.black,
                    displayWindow.range
                )
                val raw8 = IntArray(rawValues.size) { displayWindow.to8(rawValues[it]) }
                BayerDemosaic.demosaic8(
                    raw8,
                    width,
                    height,
                    header.bayerPattern,
                    BayerDemosaic.Mode.DIRECTIONAL,
                    pixels
                )
            } else {
                for (i in rawValues.indices) {
                    val gray = displayWindow.to8(rawValues[i])
                    pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
        } else {
            val planes = Array(3) { IntArray(pixelCount) }
            for (channel in 0 until channels.coerceAtMost(3)) {
                val channelOffset = channel.toLong() * sourceWidth * sourceHeight
                for (y in 0 until height) {
                    if (cancelled()) throw CancellationException("FITS preview cancelled")
                    val rowOffset = channelOffset + y.toLong() * sourceWidth
                    for (x in 0 until width) planes[channel][y * width + x] = readSample(rowOffset + x)
                }
            }
            val displayWindow = FitsDisplayStretch.window(planes.take(channels.coerceAtMost(3)))
            lp750Gains = ImageUtils.lp750WhiteBalanceGains(
                planes[0].averageNormalized(displayWindow),
                planes[1].averageNormalized(displayWindow),
                planes[2].averageNormalized(displayWindow)
            )
            for (i in pixels.indices) {
                val red = (displayWindow.normalize(planes[0][i]) * 255f).toInt().coerceIn(0, 255)
                val green = (displayWindow.normalize(planes[1][i]) * 255f).toInt().coerceIn(0, 255)
                val blue = (displayWindow.normalize(planes[2][i]) * 255f).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        val type = when {
            channels > 1 -> " RGB"
            header.bayerPattern != null -> " Bayer(${header.bayerPattern})"
            else -> " Mono"
        }
        return FitsIntegerPreview(
            width = width,
            height = height,
            info = "${width}x${height} ${header.bitpix}bit$type",
            basePixels = pixels,
            lp750Gains = lp750Gains
        )
    }

    private fun readHeader(input: RandomAccessFile): Header {
        var width = 0
        var height = 0
        var bitpix = 16
        var naxis = 2
        var naxis3 = 1
        var bscale = 1.0
        var bzero = 0.0
        var bayerPattern: String? = null
        var endFound = false
        var bytesRead = 0L
        val block = ByteArray(FITS_BLOCK_SIZE)

        while (!endFound) {
            val count = input.read(block)
            if (count <= 0) break
            bytesRead += count
            var offset = 0
            while (offset + FITS_CARD_SIZE <= count) {
                val card = String(block, offset, FITS_CARD_SIZE, Charsets.US_ASCII)
                offset += FITS_CARD_SIZE
                when {
                    card.startsWith("NAXIS1") -> width = card.value().toIntOrNull() ?: 0
                    card.startsWith("NAXIS2") -> height = card.value().toIntOrNull() ?: 0
                    card.startsWith("NAXIS3") -> naxis3 = card.value().toIntOrNull() ?: 1
                    card.startsWith("BITPIX") -> bitpix = card.value().toIntOrNull() ?: 16
                    card.startsWith("NAXIS ") -> naxis = card.value().toIntOrNull() ?: 2
                    card.startsWith("BSCALE") -> bscale = card.value().toDoubleOrNull() ?: 1.0
                    card.startsWith("BZERO") -> bzero = card.value().toDoubleOrNull() ?: 0.0
                    card.startsWith("BAYERPAT") -> {
                        val parsed = card.substringAfter("'").substringBefore("'").trim().uppercase()
                        bayerPattern = parsed.takeIf { it in setOf("RGGB", "GRBG", "GBRG", "BGGR") }
                    }
                    card.startsWith("END ") || card.trimEnd() == "END" -> endFound = true
                }
                if (endFound) break
            }
            if (count < FITS_BLOCK_SIZE) break
        }
        require(endFound) { "Invalid FITS header: END card is missing" }
        val dataOffset = ((bytesRead + FITS_BLOCK_SIZE - 1) / FITS_BLOCK_SIZE) * FITS_BLOCK_SIZE
        return Header(width, height, bitpix, naxis, naxis3, bscale, bzero, bayerPattern, dataOffset)
    }

    private fun firstWithParity(start: Int, paritySource: Int): Int =
        if ((start and 1) == (paritySource and 1)) start else start + 1

    private fun String.value(): String = substringAfter("=").substringBefore("/").trim()

    private fun IntArray.averageNormalized(window: FitsDisplayStretch.Window): Float {
        if (isEmpty()) return 0f
        val step = (size / 10_000).coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        for (i in indices step step) {
            sum += window.normalize(this[i]).toDouble()
            count++
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun lp750WhiteBalance(
        raw: IntArray,
        width: Int,
        height: Int,
        bayerPattern: String,
        minimum: Int,
        range: Int
    ): FloatArray {
        val redX: Int
        val redY: Int
        when (bayerPattern) {
            "RGGB" -> { redX = 0; redY = 0 }
            "GRBG" -> { redX = 1; redY = 0 }
            "GBRG" -> { redX = 0; redY = 1 }
            else -> { redX = 1; redY = 1 }
        }
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
        val step = 4
        for (y in 0 until height - 1 step step * 2) {
            for (x in 0 until width - 1 step step * 2) {
                val v00 = raw[y * width + x]
                val v10 = raw[y * width + x + 1]
                val v01 = raw[(y + 1) * width + x]
                val v11 = raw[(y + 1) * width + x + 1]
                val red: Int
                val green1: Int
                val green2: Int
                val blue: Int
                when {
                    redX == 0 && redY == 0 -> { red = v00; green1 = v10; green2 = v01; blue = v11 }
                    redX == 1 && redY == 0 -> { green1 = v00; red = v10; blue = v01; green2 = v11 }
                    redX == 0 && redY == 1 -> { green1 = v00; blue = v10; red = v01; green2 = v11 }
                    else -> { blue = v00; green1 = v10; green2 = v01; red = v11 }
                }
                sumR += red
                sumG += (green1 + green2) * 0.5
                sumB += blue
                count++
            }
        }
        if (count == 0) return floatArrayOf(1f, 1f, 1f)
        return ImageUtils.lp750WhiteBalanceGains(
            ((sumR / count - minimum) / range).toFloat(),
            ((sumG / count - minimum) / range).toFloat(),
            ((sumB / count - minimum) / range).toFloat()
        )
    }

    private data class Header(
        val width: Int,
        val height: Int,
        val bitpix: Int,
        val naxis: Int,
        val naxis3: Int,
        val bscale: Double,
        val bzero: Double,
        val bayerPattern: String?,
        val dataOffset: Long
    )
}
