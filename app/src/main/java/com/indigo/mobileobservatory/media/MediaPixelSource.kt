package com.indigo.mobileobservatory.media

import java.io.Closeable

/** A source reads only a sampled rectangle. Row decoders and export bands bound temporary memory. */
interface MediaPixelSource : Closeable {
    val width: Int
    val height: Int
    val channels: Int
    val range: MediaImageWindow
    val header: Map<String, String> get() = emptyMap()
    val displayNormalized: Boolean get() = true
    fun read(region: MediaImageRegion, check: () -> Unit = {}): MediaImagePixels
}
