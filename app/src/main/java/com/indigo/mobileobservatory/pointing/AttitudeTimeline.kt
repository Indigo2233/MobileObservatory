package com.indigo.mobileobservatory.pointing

internal data class AttitudeSnapshot(
    val timestampMs: Long,
    val opticalAxis: Direction3,
    val imageUp: Direction3? = null,
    val elapsedMs: Long? = null
)

/** Ring buffer of rotation-vector samples used for burst alignment and mid-exposure updates. */
internal class AttitudeTimeline(private val capacity: Int = 240) {
    private val items = ArrayDeque<AttitudeSnapshot>(capacity)

    @Synchronized
    fun add(snapshot: AttitudeSnapshot) {
        val previousElapsed = items.lastOrNull()?.elapsedMs
        if (previousElapsed != null && snapshot.elapsedMs != null && snapshot.elapsedMs <= previousElapsed) return
        items.addLast(snapshot)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized
    fun latest(): AttitudeSnapshot? = items.lastOrNull()

    @Synchronized fun clear() { items.clear() }

    /** Never extrapolate stale IMU samples over camera startup, sleep or a stopped sensor. */
    @Synchronized fun sampleElapsed(elapsedMs: Long, maxGapMs: Long = 250): AttitudeSnapshot? {
        val before = items.lastOrNull { (it.elapsedMs ?: Long.MAX_VALUE) <= elapsedMs } ?: return null
        val after = items.firstOrNull { (it.elapsedMs ?: Long.MIN_VALUE) >= elapsedMs } ?: return null
        val a = requireNotNull(before.elapsedMs); val b = requireNotNull(after.elapsedMs)
        if (elapsedMs - a > maxGapMs || b - elapsedMs > maxGapMs) return null
        if (b == a) return before
        val t = (elapsedMs - a).toDouble() / (b - a)
        return AttitudeSnapshot(before.timestampMs + ((after.timestampMs - before.timestampMs) * t).toLong(),
            mix(before.opticalAxis, after.opticalAxis, t),
            if (before.imageUp != null && after.imageUp != null) mix(before.imageUp, after.imageUp, t) else null, elapsedMs)
    }

    @Synchronized fun motionDuring(startElapsedMs: Long, endElapsedMs: Long): Double? {
        if (endElapsedMs < startElapsedMs) return null
        val first = sampleElapsed(startElapsedMs) ?: return null
        val last = sampleElapsed(endElapsedMs) ?: return null
        val snapshots = items.filter { it.elapsedMs != null && it.elapsedMs in startElapsedMs..endElapsedMs } + last
        if (first.imageUp == null || snapshots.any { it.imageUp == null }) return null
        val firstPose = first.imuPose() ?: return null
        var previous = startElapsedMs
        for (sample in snapshots) {
            val elapsed = sample.elapsedMs ?: return null
            if (elapsed - previous > 250 || elapsed < previous) return null
            previous = elapsed
        }
        return snapshots.map { rotationDistanceDeg(firstPose, it.imuPose() ?: return null) }.maxOrNull()
    }

    @Synchronized
    fun sample(timestampMs: Long): AttitudeSnapshot? {
        if (items.isEmpty()) return null
        var before: AttitudeSnapshot? = null
        var after: AttitudeSnapshot? = null
        for (item in items) {
            if (item.timestampMs <= timestampMs) before = item
            if (item.timestampMs >= timestampMs) {
                after = item
                break
            }
        }
        val start = before ?: return after
        val end = after ?: return start
        if (end.timestampMs == start.timestampMs) return start
        val t = ((timestampMs - start.timestampMs).toDouble() / (end.timestampMs - start.timestampMs))
            .coerceIn(0.0, 1.0)
        return AttitudeSnapshot(
            timestampMs = timestampMs,
            opticalAxis = mix(start.opticalAxis, end.opticalAxis, t),
            imageUp = if (start.imageUp != null && end.imageUp != null) {
                mix(start.imageUp, end.imageUp, t)
            } else {
                start.imageUp ?: end.imageUp
            }
        )
    }

    private fun mix(a: Direction3, b: Direction3, t: Double): Direction3 =
        (a * (1.0 - t) + b * t).unit()
}
