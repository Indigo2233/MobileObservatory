package com.indigo.mobileobservatory.guide

data class GuideMeasurement(val sequence: Long, val deliveredMs: Long, val stars: List<GuideStar>)

/** Joins the processed stars to their actual capture callback, never a newer unrelated callback. */
class GuideMeasurements {
    private data class Key(val generation: Long?, val frameId: Long)
    private val deliveries = LinkedHashMap<Key, Pair<Long, Long>>()
    private var sequence = 0L
    private var run = 0L
    @Volatile var latest: GuideMeasurement? = null
        private set
    @Synchronized fun startRun(): Long { clear(); return run }
    @Synchronized fun delivered(generation: Long?, frameId: Long, elapsedMs: Long, session: Long = run) {
        if (session != run) return
        sequence++
        deliveries[Key(generation, frameId)] = sequence to elapsedMs
        while (deliveries.size > 16) deliveries.remove(deliveries.keys.first())
    }
    @Synchronized fun processed(generation: Long?, frameId: Long, stars: List<GuideStar>, session: Long = run): GuideMeasurement? {
        if (session != run) return null
        val stamp = deliveries.remove(Key(generation, frameId)) ?: return null
        return GuideMeasurement(stamp.first, stamp.second, stars).also { latest = it }
    }
    @Synchronized fun boundary(): Long = sequence
    @Synchronized fun clear() { run++; deliveries.clear(); latest = null }
    @Synchronized fun forgetSamples() { deliveries.clear(); latest = null }
    fun fresh(after: Long, notBeforeMs: Long): GuideMeasurement? = latest?.takeIf {
        it.sequence > after && it.deliveredMs >= notBeforeMs
    }
}
