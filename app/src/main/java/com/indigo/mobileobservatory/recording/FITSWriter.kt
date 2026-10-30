package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.GainControlKind
import com.indigo.mobileobservatory.camera.PixelFormat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class FITSWriter {

    companion object {
        private const val HEADER_BLOCK = 2880
        private const val CARD_LEN = 80
        private const val DATA_CHUNK_BYTES = 64 * 1024
    }

    fun write(
        file: File,
        frame: FrameData,
        exposureSeconds: Float,
        gain: Float,
        gainKind: GainControlKind = GainControlKind.NATIVE_GAIN,
        gainLabel: String = "Gain",
        gainUnit: String? = null,
        gainDbEquivalent: Float? = null,
        cameraName: String? = null,
        filterName: String? = null,
        configuredFormat: PixelFormat? = null,
        pixelSizeUm: Float? = null,
        focalLengthMm: Float? = null,
        binning: Int = 1,
        exposureStartedAt: Instant? = null,
        imageType: String? = null
    ) {
        val effectiveFormat = configuredFormat ?: frame.pixelFormat
        if (effectiveFormat == PixelFormat.RGB24 || frame.pixelFormat == PixelFormat.RGB24) {
            error("RGB24 Live View frames cannot be written as FITS")
        }
        val is16 = effectiveFormat.isHighBit
        val bitpix = if (is16) 16 else 8
        val naxis1 = frame.width
        val naxis2 = frame.height
        // DATE-OBS is the start of the exposure. The frame reaches us once it has finished, so
        // without an explicit start time we walk back by the exposure length.
        val startedAt = exposureStartedAt
            ?: Instant.now().minusMillis((exposureSeconds.toDouble() * 1000.0).toLong())
        val dateObs = DateTimeFormatter.ISO_INSTANT.format(startedAt.atOffset(ZoneOffset.UTC))

        val cards = mutableListOf<String>()
        cards.add(fitsCard("SIMPLE", "T", "conforms to FITS standard"))
        cards.add(fitsCard("BITPIX", bitpix.toString(), "bits per pixel"))
        cards.add(fitsCard("NAXIS", "2", "number of axes"))
        cards.add(fitsCard("NAXIS1", naxis1.toString(), "width"))
        cards.add(fitsCard("NAXIS2", naxis2.toString(), "height"))
        cards.add(fitsCard("VALIDBIT", effectiveFormat.nativeBits.toString(), "valid sensor bits before FITS container"))
        if (is16) {
            cards.add(fitsCard("BZERO", "32768", "offset for unsigned 16-bit"))
            cards.add(fitsCard("BSCALE", "1", "default scaling"))
        }
        cards.add(fitsCard("EXPOSURE", "%.6f".format(exposureSeconds), "exposure time in seconds"))
        if (gainKind == GainControlKind.ISO) {
            cards.add(fitsCard("ISOSPEED", gain.toInt().toString(), "camera ISO speed"))
        } else {
            val gainComment = listOfNotNull(gainLabel, gainUnit?.takeIf { it.isNotBlank() })
                .joinToString(" ") + " native value"
            cards.add(fitsCard("GAIN", "%.2f".format(gain), gainComment))
            gainDbEquivalent?.let { db ->
                cards.add(fitsCard("GAINDB", "%.2f".format(db), "gain equivalent in dB"))
            }
        }
        cards.add(fitsCard("DATE-OBS", "'$dateObs'", "observation date"))
        cards.add(fitsCard("INSTRUME", "'${cameraName ?: "Camera"}'", "instrument"))
        imageType?.takeIf { it.isNotBlank() }?.let { type ->
            cards.add(fitsCard("IMAGETYP", "'${type.uppercase()}'", "image type"))
        }
        if (pixelSizeUm != null && pixelSizeUm > 0f) {
            cards.add(fitsCard("XPIXSZ", "%.4f".format(pixelSizeUm), "pixel size in microns"))
            cards.add(fitsCard("YPIXSZ", "%.4f".format(pixelSizeUm), "pixel size in microns"))
        }
        cards.add(fitsCard("XBINNING", binning.coerceAtLeast(1).toString(), "binning factor"))
        cards.add(fitsCard("YBINNING", binning.coerceAtLeast(1).toString(), "binning factor"))
        if (focalLengthMm != null && focalLengthMm > 0f) {
            cards.add(fitsCard("FOCALLEN", "%.3f".format(focalLengthMm), "focal length in mm"))
            if (pixelSizeUm != null && pixelSizeUm > 0f) {
                val arcsecPerPixel = 206.265 * pixelSizeUm * binning.coerceAtLeast(1) / focalLengthMm
                cards.add(fitsCard("PIXSCALE", "%.6f".format(arcsecPerPixel), "arcsec per pixel"))
                cards.add(fitsCard("FOVW", "%.6f".format(arcsecPerPixel * naxis1 / 3600.0), "field width in degrees"))
                cards.add(fitsCard("FOVH", "%.6f".format(arcsecPerPixel * naxis2 / 3600.0), "field height in degrees"))
            }
        }
        if (filterName != null) {
            cards.add(fitsCard("FILTER", "'$filterName'", "filter name"))
        }
        if (effectiveFormat.isBayer) {
            val bayerPat = when {
                effectiveFormat.name.startsWith("BAYER_RG") -> "RGGB"
                effectiveFormat.name.startsWith("BAYER_GR") -> "GRBG"
                effectiveFormat.name.startsWith("BAYER_GB") -> "GBRG"
                effectiveFormat.name.startsWith("BAYER_BG") -> "BGGR"
                else -> null
            }
            if (bayerPat != null) {
                cards.add(fitsCard("BAYERPAT", "'$bayerPat'", "Bayer pattern"))
            }
        }
        cards.add(fitsCard("SOFTWARE", "'MobileObservatory'", "capture software"))
        cards.add(fitsEndCard())

        FileOutputStream(file).use { fos ->
            val headerBytes = buildHeader(cards)
            fos.write(headerBytes)

            writeDataBlock(fos, frame, is16, effectiveFormat.nativeBits)
        }
    }

    private fun buildHeader(cards: List<String>): ByteArray {
        val totalCards = cards.size
        val blocksNeeded = (totalCards * CARD_LEN + HEADER_BLOCK - 1) / HEADER_BLOCK
        val headerSize = blocksNeeded * HEADER_BLOCK
        val header = ByteArray(headerSize) { 0x20 }
        var offset = 0
        for (card in cards) {
            val cardBytes = card.toByteArray(Charsets.US_ASCII)
            System.arraycopy(cardBytes, 0, header, offset, cardBytes.size.coerceAtMost(CARD_LEN))
            offset += CARD_LEN
        }
        return header
    }

    private fun writeDataBlock(output: OutputStream, frame: FrameData, is16: Boolean, targetBits: Int) {
        val pixels = frame.width.toLong() * frame.height.toLong()
        require(frame.width > 0 && frame.height > 0 && pixels <= Int.MAX_VALUE) { "Invalid FITS dimensions" }
        val pixelCount = pixels.toInt()
        val rawSize = pixels * (if (is16) 2 else 1)
        val buffer = ByteArray(DATA_CHUNK_BYTES)
        if (is16) {
            val frameBpp = frame.pixelFormat.bytesPerPixel
            if (frameBpp >= 2) require(pixels * 2 <= frame.data.size) { "Truncated FITS frame" }
            val shift = (targetBits - 8).coerceAtLeast(0)
            var index = 0
            while (index < pixelCount) {
                val count = minOf(buffer.size / 2, pixelCount - index)
                for (i in 0 until count) {
                    val source = index + i
                    val unsigned = if (frameBpp < 2) {
                        if (source < frame.data.size) (frame.data[source].toInt() and 0xff) shl shift else 0
                    } else {
                        (frame.data[source * 2].toInt() and 0xff) or
                            ((frame.data[source * 2 + 1].toInt() and 0xff) shl 8)
                    }
                    val signed = unsigned - 32768
                    buffer[i * 2] = (signed shr 8).toByte()
                    buffer[i * 2 + 1] = signed.toByte()
                }
                output.write(buffer, 0, count * 2)
                index += count
            }
        } else {
            val available = minOf(pixelCount, frame.data.size)
            output.write(frame.data, 0, available)
            var missing = pixelCount - available
            while (missing > 0) {
                val count = minOf(missing, buffer.size)
                output.write(buffer, 0, count)
                missing -= count
            }
        }
        val padding = ((HEADER_BLOCK - rawSize % HEADER_BLOCK) % HEADER_BLOCK).toInt()
        if (padding > 0) output.write(ByteArray(padding))
    }

    private fun fitsCard(keyword: String, value: String, comment: String): String {
        val kw = keyword.padEnd(8).take(8)
        val card = if (value.startsWith("'")) {
            "$kw= $value"
        } else {
            "$kw= ${value.padStart(20)}"
        }
        val withComment = "$card / $comment"
        return withComment.padEnd(CARD_LEN).take(CARD_LEN)
    }

    private fun fitsEndCard(): String = "END".padEnd(CARD_LEN)
}
