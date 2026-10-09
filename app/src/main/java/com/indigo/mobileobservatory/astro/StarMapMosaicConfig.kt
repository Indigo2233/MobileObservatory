package com.indigo.mobileobservatory.astro

data class StarMapMosaicConfig(
    val rows: Int = 1,
    val columns: Int = 1,
    val overlapPercent: Int = 10,
    val showPanelNumbers: Boolean = true
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
    }
}
