package com.indigo.mobileobservatory.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class StarMapMosaicPanel(
    val number: Int,
    val row: Int,
    val column: Int,
    val raHours: Double,
    val decDegrees: Double
)

object StarMapMosaicPlanner {
    fun panels(
        centerRaHours: Double,
        centerDecDegrees: Double,
        widthDeg: Double,
        heightDeg: Double,
        positionAngleDeg: Double,
        config: StarMapMosaicConfig
    ): List<StarMapMosaicPanel> {
        val normalized = config.normalized()
        val overlap = normalized.overlapPercent / 100.0
        val halfWidth = tan(widthDeg.coerceIn(0.000001, 179.0) * DEG_TO_RAD / 2.0)
        val halfHeight = tan(heightDeg.coerceIn(0.000001, 179.0) * DEG_TO_RAD / 2.0)
        val stepRight = 2.0 * halfWidth * (1.0 - overlap)
        val stepUp = 2.0 * halfHeight * (1.0 - overlap)
        return orderedCells(normalized).mapIndexed { index, cell ->
            val row = cell.first
            val column = cell.second
            val centerUp = ((normalized.rows - 1) / 2.0 - row) * stepUp
            val centerRight = (column - (normalized.columns - 1) / 2.0) * stepRight
            val point = tangentPlanePoint(
                centerRaHours,
                centerDecDegrees,
                positionAngleDeg,
                centerRight,
                centerUp
            )
            StarMapMosaicPanel(index + 1, row, column, point.first, point.second)
        }
    }

    fun orderedCells(config: StarMapMosaicConfig): List<Pair<Int, Int>> {
        val normalized = config.normalized()
        val rows = if (normalized.startCorner.startsAtTop) {
            0 until normalized.rows
        } else {
            (normalized.rows - 1 downTo 0)
        }.toList()
        val columns = if (normalized.startCorner.startsAtLeft) {
            0 until normalized.columns
        } else {
            (normalized.columns - 1 downTo 0)
        }.toList()
        return when (normalized.traversal) {
            MosaicTraversal.ROWS -> rows.flatMap { row -> columns.map { column -> row to column } }
            MosaicTraversal.SNAKE -> rows.flatMapIndexed { index, row ->
                val scan = if (index % 2 == 0) columns else columns.reversed()
                scan.map { column -> row to column }
            }
            MosaicTraversal.COLUMNS -> columns.flatMap { column -> rows.map { row -> row to column } }
        }
    }

    private fun tangentPlanePoint(
        raHours: Double,
        decDegrees: Double,
        positionAngleDeg: Double,
        right: Double,
        up: Double
    ): Pair<Double, Double> {
        val longitude = raHours * PI / 12.0
        val latitude = decDegrees * DEG_TO_RAD
        val angle = positionAngleDeg * DEG_TO_RAD
        val center = doubleArrayOf(
            cos(latitude) * cos(longitude),
            cos(latitude) * sin(longitude),
            sin(latitude)
        )
        val east = doubleArrayOf(-sin(longitude), cos(longitude), 0.0)
        val north = doubleArrayOf(
            -sin(latitude) * cos(longitude),
            -sin(latitude) * sin(longitude),
            cos(latitude)
        )
        val sensorRight = DoubleArray(3) { -east[it] * cos(angle) + north[it] * sin(angle) }
        val sensorUp = DoubleArray(3) { north[it] * cos(angle) + east[it] * sin(angle) }
        val vector = DoubleArray(3) { center[it] + sensorRight[it] * right + sensorUp[it] * up }
        val length = sqrt(vector.sumOf { it * it }).coerceAtLeast(1e-12)
        val x = vector[0] / length
        val y = vector[1] / length
        val z = (vector[2] / length).coerceIn(-1.0, 1.0)
        val longitudeDeg = Math.toDegrees(kotlin.math.atan2(y, x))
        val normalizedRaDeg = ((longitudeDeg % 360.0) + 360.0) % 360.0
        return normalizedRaDeg / 15.0 to Math.toDegrees(asin(z))
    }

    private const val DEG_TO_RAD = PI / 180.0
}
