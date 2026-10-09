package com.indigo.mobileobservatory.astro

enum class MosaicTraversal {
    ROWS,
    SNAKE,
    COLUMNS;

    companion object {
        fun fromPref(value: String?): MosaicTraversal =
            entries.firstOrNull { it.name == value } ?: SNAKE
    }
}

enum class MosaicStartCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT;

    val startsAtTop: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT
    val startsAtLeft: Boolean get() = this == TOP_LEFT || this == BOTTOM_LEFT

    companion object {
        fun fromPref(value: String?): MosaicStartCorner =
            entries.firstOrNull { it.name == value } ?: TOP_LEFT
    }
}

data class StarMapMosaicConfig(
    val rows: Int = 1,
    val columns: Int = 1,
    val overlapPercent: Int = 10,
    val showPanelNumbers: Boolean = true,
    val traversal: MosaicTraversal = MosaicTraversal.SNAKE,
    val startCorner: MosaicStartCorner = MosaicStartCorner.TOP_LEFT
) {
    val panelCount: Int get() = rows * columns

    fun normalized(): StarMapMosaicConfig = copy(
        rows = rows.coerceIn(MIN_PANELS_PER_AXIS, MAX_PANELS_PER_AXIS),
        columns = columns.coerceIn(MIN_PANELS_PER_AXIS, MAX_PANELS_PER_AXIS),
        overlapPercent = overlapPercent.coerceIn(MIN_OVERLAP_PERCENT, MAX_OVERLAP_PERCENT)
    )

    companion object {
        const val MIN_PANELS_PER_AXIS = 1
        const val MAX_PANELS_PER_AXIS = 10
        const val MIN_OVERLAP_PERCENT = 0
        const val MAX_OVERLAP_PERCENT = 90

        const val ROWS_PREF = "star_map_mosaic_rows"
        const val COLUMNS_PREF = "star_map_mosaic_columns"
        const val OVERLAP_PREF = "star_map_mosaic_overlap_percent"
        const val SHOW_NUMBERS_PREF = "star_map_mosaic_show_panel_numbers"
        const val TRAVERSAL_PREF = "star_map_mosaic_traversal"
        const val START_CORNER_PREF = "star_map_mosaic_start_corner"
    }
}
