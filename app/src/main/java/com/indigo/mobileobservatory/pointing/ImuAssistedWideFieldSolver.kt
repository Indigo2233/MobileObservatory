package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

data class ImuAssistedSolveResult(
    val success: Boolean,
    val message: String,
    val center: TopocentricCoordinates? = null,
    val matchedStars: Int = 0,
    val residualDeg: Double? = null,
    val rotationDeg: Double? = null
)

/**
 * Local wide-field solver for the normal phone camera path.
 *
 * It starts from the rotation-vector prior and matches triangles from detected image stars to
 * bright catalog stars projected around that prior. The solver deliberately refuses candidates
 * outside [maximumPriorErrorDeg]; full-sky lost-in-space solving belongs to the later tetra3
 * index path.
 */
internal object ImuAssistedWideFieldSolver {
    private const val DETECTED_LIMIT = 12
    private const val CATALOG_LIMIT = 48
    private const val TRIANGLE_RATIO_TOLERANCE = 0.035
    private const val MAXIMUM_PRIOR_ERROR_DEG = 18.0
    private const val MINIMUM_MATCHES = 4
    private const val MAXIMUM_RESIDUAL_DEG = 0.45

    fun solve(
        extraction: StarExtractionResult,
        frameWidth: Int,
        frameHeight: Int,
        fovWidthDeg: Double,
        fovHeightDeg: Double,
        imuDirection: Direction3,
        instant: Instant,
        site: ObserverSite,
        catalog: PhoneBrightStarCatalog
    ): ImuAssistedSolveResult {
        val imageStars = extraction.stars.sortedByDescending { it.snr }.take(DETECTED_LIMIT)
        if (imageStars.size < 4) return ImuAssistedSolveResult(false, "Need at least 4 detected stars")
        val prior = imuDirection.toAltAz().let { TopocentricCoordinates(it.first, it.second) }
        val radius = diagonalFovDeg(fovWidthDeg, fovHeightDeg) / 2.0 + MAXIMUM_PRIOR_ERROR_DEG
        val basis = TangentBasis.of(imuDirection)
        val catalogStars = catalog.around(prior, radius, instant, site, maximumMagnitude = 5.5)
            .map { star ->
                val h = CoordinateTransform.j2000ToTopocentric(
                    com.indigo.mobileobservatory.astro.EquatorialCoordinates(star.raDeg, star.decDeg),
                    instant, site, refraction = null
                )
                CatalogPoint(star, basis.project(Direction3.fromAltAz(h.altitudeDeg, h.azimuthDeg)))
            }
            .filter { hypot(it.point.x, it.point.y) <= tan(Math.toRadians(radius)) }
            .sortedBy { it.star.magnitude }
            .take(CATALOG_LIMIT)
        if (catalogStars.size < 4) return ImuAssistedSolveResult(false, "Too few catalog stars near IMU prior")

        val imagePoints = imageStars.map { star ->
            Point2(
                ((star.x / frameWidth) * 2.0 - 1.0) * tan(Math.toRadians(fovWidthDeg / 2.0)),
                (1.0 - (star.y / frameHeight) * 2.0) * tan(Math.toRadians(fovHeightDeg / 2.0))
            )
        }
        var best: Candidate? = null
        for (a in 0 until imagePoints.size - 2) {
            for (b in a + 1 until imagePoints.size - 1) {
                for (c in b + 1 until imagePoints.size) {
                    val imageTriangle = Triangle.of(imagePoints[a], imagePoints[b], imagePoints[c])
                    if (imageTriangle == null) continue
                    for (i in 0 until catalogStars.size - 2) {
                        for (j in i + 1 until catalogStars.size - 1) {
                            for (k in j + 1 until catalogStars.size) {
                                val catalogTriangle = Triangle.of(
                                    catalogStars[i].point, catalogStars[j].point, catalogStars[k].point
                                ) ?: continue
                                if (!imageTriangle.similarTo(catalogTriangle)) continue
                                val matched = fitCandidate(
                                    imagePoints,
                                    listOf(a, b, c),
                                    catalogStars,
                                    listOf(i, j, k),
                                    basis
                                ) ?: continue
                                if (best == null || matched.residual < best.residual) best = matched
                            }
                        }
                    }
                }
            }
        }
        val result = best ?: return ImuAssistedSolveResult(false, "No catalog triangle matched IMU prior")
        val centerDirection = basis.unproject(result.translation.x, result.translation.y)
        val center = centerDirection.toAltAz().let { TopocentricCoordinates(it.first, it.second) }
        val priorError = angularDistanceDeg(imuDirection, centerDirection)
        val residualDeg = Math.toDegrees(atan2(result.residual, 1.0))
        return if (result.matches >= MINIMUM_MATCHES &&
            residualDeg <= MAXIMUM_RESIDUAL_DEG && priorError <= MAXIMUM_PRIOR_ERROR_DEG
        ) {
            ImuAssistedSolveResult(
                success = true,
                message = "Matched ${result.matches} stars from IMU prior",
                center = center,
                matchedStars = result.matches,
                residualDeg = residualDeg,
                rotationDeg = Math.toDegrees(result.rotation)
            )
        } else {
            ImuAssistedSolveResult(
                success = false,
                message = "Candidate rejected: ${result.matches} matches, %.2f° residual".format(residualDeg),
                matchedStars = result.matches,
                residualDeg = residualDeg
            )
        }
    }

    private fun fitCandidate(
        image: List<Point2>, imageIndices: List<Int>, catalog: List<CatalogPoint>,
        catalogIndices: List<Int>, basis: TangentBasis
    ): Candidate? {
        var best: Candidate? = null
        for (permutation in permutations(catalogIndices)) {
            val fit = rigidFit(
                imageIndices.map { image[it] },
                permutation.map { catalog[it].point }
            ) ?: continue
            val center = basis.unproject(fit.translation.x, fit.translation.y)
            if (angularDistanceDeg(basis.center, center) > MAXIMUM_PRIOR_ERROR_DEG) continue
            var matches = 0
            var squared = 0.0
            for (source in image) {
                val transformed = fit.transform(source)
                val distance = catalog.minOf { hypot(transformed.x - it.point.x, transformed.y - it.point.y) }
                if (distance <= tan(Math.toRadians(0.7))) {
                    matches++
                    squared += distance * distance
                }
            }
            if (matches == 0) continue
            val candidate = Candidate(fit.rotation, fit.translation, matches, kotlin.math.sqrt(squared / matches))
            if (best == null || candidate.residual < best.residual) best = candidate
        }
        return best
    }

    private fun rigidFit(source: List<Point2>, target: List<Point2>): RigidFit? {
        if (source.size != target.size || source.size < 2) return null
        val sourceMean = Point2(source.map { it.x }.average(), source.map { it.y }.average())
        val targetMean = Point2(target.map { it.x }.average(), target.map { it.y }.average())
        var dot = 0.0
        var cross = 0.0
        for (index in source.indices) {
            val a = source[index] - sourceMean
            val b = target[index] - targetMean
            dot += a.x * b.x + a.y * b.y
            cross += a.x * b.y - a.y * b.x
        }
        val rotation = atan2(cross, dot)
        val rotatedMean = sourceMean.rotate(rotation)
        return RigidFit(rotation, targetMean - rotatedMean)
    }

    private fun permutations(values: List<Int>): List<List<Int>> = listOf(
        listOf(values[0], values[1], values[2]), listOf(values[0], values[2], values[1]),
        listOf(values[1], values[0], values[2]), listOf(values[1], values[2], values[0]),
        listOf(values[2], values[0], values[1]), listOf(values[2], values[1], values[0])
    )

    private fun diagonalFovDeg(width: Double, height: Double): Double =
        Math.toDegrees(2.0 * atan2(hypot(tan(Math.toRadians(width / 2.0)), tan(Math.toRadians(height / 2.0))), 1.0))

    private fun angularDistanceDeg(a: Direction3, b: Direction3): Double =
        Math.toDegrees(kotlin.math.acos((a.east * b.east + a.north * b.north + a.up * b.up).coerceIn(-1.0, 1.0)))

    private data class CatalogPoint(val star: PhoneCatalogStar, val point: Point2)
    private data class Candidate(val rotation: Double, val translation: Point2, val matches: Int, val residual: Double)
    private data class Point2(val x: Double, val y: Double) {
        operator fun minus(other: Point2) = Point2(x - other.x, y - other.y)
        operator fun plus(other: Point2) = Point2(x + other.x, y + other.y)
        fun rotate(angle: Double) = Point2(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))
    }
    private data class RigidFit(val rotation: Double, val translation: Point2) {
        fun transform(point: Point2) = point.rotate(rotation) + translation
    }
    private data class Triangle(private val ratios: DoubleArray) {
        fun similarTo(other: Triangle): Boolean = ratios.indices.all { abs(ratios[it] - other.ratios[it]) <= TRIANGLE_RATIO_TOLERANCE }
        companion object {
            fun of(a: Point2, b: Point2, c: Point2): Triangle? {
                val sides = doubleArrayOf(hypot(a.x - b.x, a.y - b.y), hypot(a.x - c.x, a.y - c.y), hypot(b.x - c.x, b.y - c.y))
                sides.sort()
                if (sides[0] < 1e-4 || sides[2] / sides[0] > 20.0) return null
                return Triangle(doubleArrayOf(sides[0] / sides[2], sides[1] / sides[2]))
            }
        }
    }
    private data class TangentBasis(val center: Direction3, val xAxis: Direction3, val yAxis: Direction3) {
        fun project(direction: Direction3): Point2 {
            val denominator = dot(direction, center)
            return Point2(dot(direction, xAxis) / denominator, dot(direction, yAxis) / denominator)
        }
        fun unproject(x: Double, y: Double): Direction3 = Direction3(
            center.east + xAxis.east * x + yAxis.east * y,
            center.north + xAxis.north * x + yAxis.north * y,
            center.up + xAxis.up * x + yAxis.up * y
        ).unit()
        companion object {
            fun of(center: Direction3): TangentBasis {
                val reference = if (abs(center.up) < 0.9) Direction3(0.0, 0.0, 1.0) else Direction3(0.0, 1.0, 0.0)
                val xAxis = cross(reference, center).unit()
                return TangentBasis(center.unit(), xAxis, cross(center, xAxis).unit())
            }
            private fun cross(a: Direction3, b: Direction3) = Direction3(
                a.north * b.up - a.up * b.north,
                a.up * b.east - a.east * b.up,
                a.east * b.north - a.north * b.east
            )
        }
    }

    private fun dot(a: Direction3, b: Direction3): Double = a.east * b.east + a.north * b.north + a.up * b.up
    private fun cross(a: Direction3, b: Direction3) = Direction3(
        a.north * b.up - a.up * b.north, a.up * b.east - a.east * b.up, a.east * b.north - a.north * b.east
    )
}
