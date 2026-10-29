package com.indigo.mobileobservatory.camera

import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment

/** Pure mappings shared by adapters and byte-level tests; no SDK is loaded to establish a contract. */
object CameraScienceLayouts {
    /** ZWO SDK RAW16 left-aligns native ADC samples in a 16-bit container. Vendor explanation:
     * https://bbs.zwoastro.com/d/6945-zwo-asi120mc-12bit-adc-asigetvideodata-function
     */
    fun zwo(format: PixelFormat): PixelTraits? = if (format.isRgb) null else PixelTraits(
        alignment = if (format.isHighBit && format.nativeBits < 16) SampleAlignment.LEFT else SampleAlignment.RIGHT,
        validBits = format.nativeBits, bayerPatternAtOutput = false)

    /** QHY SDK and Player One RAW16 contracts use the delivered 16-bit container.
     * Do not discard lower bits or rescale from cameraInfo's ADC depth; all delivered bits survive.
     */
    fun fullContainer(format: PixelFormat): PixelTraits? = if (format.isRgb || format.nativeBits !in listOf(8, 16)) null else
        PixelTraits(alignment = SampleAlignment.RIGHT, validBits = format.nativeBits, bayerPatternAtOutput = false)

    /** FourCC is the SDK little-endian MAKEFOURCC value, not a guessed camera model. */
    fun toupcamFormat(fourCC: Int, bits: Int): PixelFormat? {
        if (bits !in listOf(8, 10, 12, 14, 16)) return null
        val code = CharArray(4) { ((fourCC ushr (it * 8)) and 255).toChar() }.concatToString()
        val prefix = when (code) {
            "YYYY" -> "MONO"
            "RGGB" -> "BAYER_RG"
            "GRBG" -> "BAYER_GR"
            "GBRG" -> "BAYER_GB"
            "BGGR" -> "BAYER_BG"
            else -> return null
        }
        return PixelFormat.valueOf(prefix + bits)
    }

    /** Bundled toupcam.h TOUPCAM_OPTION_ZERO_PADDING (0x78): high zero padding = 0 (default),
     * low zero padding = 1. RAW=1 bypasses the SDK color/ISP pipeline; RAW=-1 does not.
     * SDK source: https://www.touptekphotonics.com/download/?category=SDK (doc/en.html).
     */
    fun toupcam(fourCC: Int, deliveredBits: Int, processedRgb: Boolean = false, zeroPadding: Int = 0, sensorBin: Int = 1): PixelTraits? {
        if (processedRgb || zeroPadding !in 0..1 || sensorBin < 1 || toupcamFormat(fourCC, deliveredBits) == null) return null
        return PixelTraits(alignment = if (zeroPadding == 1) SampleAlignment.LEFT else SampleAlignment.RIGHT,
            validBits = deliveredBits, binning = sensorBin, bayerPatternAtOutput = sensorBin > 1)
    }
}
