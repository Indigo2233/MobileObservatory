package com.indigo.mobileobservatory.pointing

import kotlin.math.hypot

/**
 * Picks a spatially spread, high-SNR subset so wide-field triangles are not all
 * taken from one bright streetlight cluster.
 */
internal object MatchStarSelector {
    fun spread(
        stars: List<ExtractedStar>,
        limit: Int,
        minSeparationPx: Float = 8f,
        columns: Int = 4,
        rows: Int = 3
    ): List<ExtractedStar> {
        if (stars.isEmpty() || limit <= 0) return emptyList()
        if (stars.size <= limit) {
            return stars.filterIndexed { index, star ->
                stars.take(index).none { hypot(it.x - star.x, it.y - star.y) < minSeparationPx }
            }.take(limit)
        }
        val minX = stars.minOf { it.x }
        val maxX = stars.maxOf { it.x }.coerceAtLeast(minX + 1f)
        val minY = stars.minOf { it.y }
        val maxY = stars.maxOf { it.y }.coerceAtLeast(minY + 1f)
        val cells = Array(columns * rows) { ArrayList<ExtractedStar>() }
        val ranked = stars.sortedByDescending { it.snr }
        for (star in ranked) {
            val col = (((star.x - minX) / (maxX - minX)) * columns).toInt().coerceIn(0, columns - 1)
            val row = (((star.y - minY) / (maxY - minY)) * rows).toInt().coerceIn(0, rows - 1)
            cells[row * columns + col] += star
        }
        val selected = ArrayList<ExtractedStar>(limit)
        fun accept(star: ExtractedStar): Boolean {
            if (selected.any { hypot(it.x - star.x, it.y - star.y) < minSeparationPx }) return false
            selected += star
            return true
        }
        var round = 0
        val maxRound = cells.maxOf { it.size }
        while (selected.size < limit && round < maxRound) {
            for (cell in cells) {
                if (selected.size >= limit) break
                cell.getOrNull(round)?.let { accept(it) }
            }
            round++
        }
        if (selected.size < limit) {
            for (star in ranked) {
                if (selected.size >= limit) break
                accept(star)
            }
        }
        return selected
    }
}
