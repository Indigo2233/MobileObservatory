package com.indigo.mobileobservatory.camera

import java.util.concurrent.atomic.AtomicLong

/** Separates a delivery run from its scientific validity. Changing settings invalidates
 * provenance immediately but leaves ordinary preview running. A new verified SDK start is
 * required to restore validity; waiting or receiving more frames never restores it.
 * Adapters must retain the returned token in the reader/delivery queue, not fetch it later.
 */
class CaptureGeneration {
    @Volatile private var active: Long? = null
    @Volatile var scienceGeneration: Long? = null
        private set

    @Synchronized fun begin(verifiedSettings: Boolean): Long {
        check(active == null) { "Previous capture run is still active" }
        val generation = next.incrementAndGet()
        active = generation
        scienceGeneration = generation.takeIf { verifiedSettings }
        return generation
    }

    @Synchronized fun invalidateScience() { scienceGeneration = null }
    @Synchronized fun stop() { scienceGeneration = null; active = null }
    fun isActive(generation: Long): Boolean = active == generation

    companion object { private val next = AtomicLong() }
}
