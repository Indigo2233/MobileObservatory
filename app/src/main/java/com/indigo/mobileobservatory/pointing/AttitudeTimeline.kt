package com.indigo.mobileobservatory.pointing

internal data class AttitudeSnapshot(
    val timestampMs: Long,
    val opticalAxis: Direction3,
    val imageUp: Direction3? = null
)

/** Ring buffer of rotation-vector samples used for burst alignment and mid-exposure updates. */
internal class AttitudeTimeline(private val capacity: Int = 240) {
    private val items = ArrayDeque<AttitudeSnapshot>(capacity)

    @Synchronized
    fun add(snapshot: AttitudeSnapshot) {
        items.addLast(snapshot)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized
    fun latest(): AttitudeSnapshot? = items.lastOrNull()

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
