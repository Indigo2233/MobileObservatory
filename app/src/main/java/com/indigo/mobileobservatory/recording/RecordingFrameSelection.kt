package com.indigo.mobileobservatory.recording

/** Zero-based inclusive excluded ranges. Storage grows with edits, never with a recording's length. */
internal data class RecordingFrameSelection private constructor(val total: Long, val excluded: List<LongRange>) {
    constructor(total: Long) : this(total, emptyList())

    init {
        require(total > 0)
        var previous = -2L
        for (range in excluded) {
            require(!range.isEmpty() && range.first > previous + 1 && range.last < total)
            previous = range.last
        }
    }

    val kept = buildList {
        var start = 0L
        for (range in excluded) {
            if (start < range.first) add(start until range.first)
            start = range.last + 1
        }
        if (start < total) add(start until total)
    }
    val count = kept.sumOf { it.last - it.first + 1 }

    fun isExcluded(frame: Long): Boolean =
        excluded.binarySearch { range -> when {
            range.last < frame -> -1
            range.first > frame -> 1
            else -> 0
        } } >= 0

    fun exclude(range: LongRange): RecordingFrameSelection {
        require(!range.isEmpty() && range.first >= 0 && range.last < total)
        val merged = mutableListOf<LongRange>()
        for (entry in (excluded + listOf(range)).sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && entry.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, entry.last)
            } else merged.add(entry)
        }
        return RecordingFrameSelection(total, merged.toList())
    }

    fun restore(range: LongRange): RecordingFrameSelection {
        require(!range.isEmpty() && range.first >= 0 && range.last < total)
        val remaining = buildList {
            for (entry in excluded) {
                if (entry.last < range.first || entry.first > range.last) add(entry)
                else {
                    if (entry.first < range.first) add(entry.first until range.first)
                    if (entry.last > range.last) add((range.last + 1)..entry.last)
                }
            }
        }
        return RecordingFrameSelection(total, remaining)
    }

    companion object {
        fun fromExcluded(total: Long, ranges: List<LongRange>) = RecordingFrameSelection(total, ranges.toList())
    }
}
