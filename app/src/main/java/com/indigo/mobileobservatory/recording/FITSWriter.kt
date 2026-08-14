package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.GainControlKind
import com.indigo.mobileobservatory.camera.PixelFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class FITSWriter {

    companion object {
        private const val HEADER_BLOCK = 2880
        private const val CARD_LEN = 80
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
        exposureStartedAt: Instant? = null
    ) {
        val effectiveFormat = configuredFormat ?: frame.pixelFormat
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
        if (is16) {
            cards.add(fitsCard("BZERO", "32768", "offset for unsigned 16-bit"))
            cards.add(fitsCard("BSCALE", "1", "default scaling"))
        }
        cards.add(fitsCard("EXPOSURE", "%.6f".format(exposureSeconds), "exposure time in seconds"))
        val gainComment = listOfNotNull(gainLabel, gainUnit?.takeIf { it.isNotBlank() })
            .joinToString(" ") + " native value"
        cards.add(fitsCard("GAIN", "%.2f".format(gain), gainComment))
        gainDbEquivalent?.let { db ->
            cards.add(fitsCard("GAINDB", "%.2f".format(db), "gain equivalent in dB"))
        }
        if (gainKind == GainControlKind.ISO) {
            cards.add(fitsCard("ISOSPEED", gain.toInt().toString(), "camera ISO speed"))
        }
        cards.add(fitsCard("DATE-OBS", "'$dateObs'", "observation date"))
        cards.add(fitsCard("INSTRUME", "'${cameraName ?: "Camera"}'", "instrument"))
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

            val dataBytes = buildDataBlock(frame, is16, effectiveFormat.nativeBits)
            fos.write(dataBytes)
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

    private fun buildDataBlock(frame: FrameData, is16: Boolean, targetBits: Int): ByteArray {
        val pixelCount = frame.width * frame.height
        val rawSize = pixelCount * (if (is16) 2 else 1)
        val blocksNeeded = (rawSize + HEADER_BLOCK - 1) / HEADER_BLOCK
        val paddedSize = blocksNeeded * HEADER_BLOCK
        val data = ByteArray(paddedSize)

        if (is16) {
            val frameBpp = frame.pixelFormat.bytesPerPixel
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            if (frameBpp < 2) {
                val shift = (targetBits - 8).coerceAtLeast(0)
                for (i in 0 until pixelCount) {
                    val v = if (i < frame.data.size) (frame.data[i].toInt() and 0xFF) shl shift else 0
                    val signed = v - 32768
                    buf.putShort(signed.toShort())
                }
            } else {
                for (i in 0 until pixelCount) {
                    val lo = frame.data[i * 2].toInt() and 0xFF
                    val hi = frame.data[i * 2 + 1].toInt() and 0xFF
                    val unsigned = (hi shl 8) or lo
                    val signed = unsigned - 32768
                    buf.putShort(signed.toShort())
                }
            }
        } else {
            System.arraycopy(frame.data, 0, data, 0, pixelCount.coerceAtMost(frame.data.size))
        }
        return data
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
