package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.PixelFormat

/** Interpretation of one sensor sample per pixel, independent of its SER declaration. */
enum class SerCfaInterpretation(val colorId: Int?) {
    AUTO(null), MONO(0), RGGB(8), BGGR(11), GRBG(9), GBRG(10);

    fun effectiveColorId(declared: Int): Int {
        if (this == AUTO) return declared
        require(declared >= 0) { "invalid_source" }
        require(declared in 0..99) { "cfa_rgb_not_raw" }
        return checkNotNull(colorId)
    }

    fun sensorFormat(declared: Int, depth: Int): PixelFormat {
        val effective = effectiveColorId(declared)
        if (effective == 100 || effective == 101) return if (depth <= 8) PixelFormat.RGB24 else PixelFormat.RGB48
        val prefix = when (effective) {
            0 -> "MONO"; 8 -> "BAYER_RG"; 11 -> "BAYER_BG"; 9 -> "BAYER_GR"; 10 -> "BAYER_GB"
            else -> error("cfa_unknown_header")
        }
        return PixelFormat.valueOf("$prefix${if (depth <= 8) 8 else 16}")
    }

    fun decodingIdentity(fileIdentity: String, endian: String, alignment: String, algorithm: String): String =
        "$fileIdentity|cfa:$name|endian:$endian|alignment:$alignment|debayer:$algorithm"
}
