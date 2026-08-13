package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import java.time.Instant
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

enum class WideFieldSolveFailure {
    CAPTURE_FAILED, INSUFFICIENT_STARS, INVALID_LENS_METADATA, NO_CANDIDATE,
    HIGH_RESIDUAL, AMBIGUOUS_CANDIDATE, DEVICE_MOTION
}

internal data class WideFieldSolveRequest(
    val extraction: StarExtractionResult,
    val frameWidth: Int,
    val frameHeight: Int,
    val initialFovWidthDeg: Double,
    val initialFovHeightDeg: Double,
    val observationTime: Instant,
    val site: ObserverSite,
    val catalog: PhoneBrightStarCatalog,
    val imuDirection: Direction3? = null
)

internal data class WideFieldSolveQuality(
    val matchedStars: Int = 0,
    val rmsResidualDeg: Double? = null,
    val confidence: Double = 0.0,
    val usedImuPrior: Boolean = false,
    val blindFallbackUsed: Boolean = false,
    val elapsedMs: Long = 0L
)

internal data class WideFieldSolveResult(
    val success: Boolean,
    val message: String,
    val raDeg: Double? = null,
    val decDeg: Double? = null,
    val rotationDeg: Double? = null,
    val fovWidthDeg: Double? = null,
    val fovHeightDeg: Double? = null,
    val arcsecPerPixel: Double? = null,
    val quality: WideFieldSolveQuality = WideFieldSolveQuality(),
    val failure: WideFieldSolveFailure? = null
)

/**
 * Unified phone wide-field solver. IMU is an optional local-search hint; a failed local search
 * always proceeds to the geometric lost-in-space matcher.
 */
internal object WideFieldSolver {
    private const val MINIMUM_STARS = 4

    fun solve(request: WideFieldSolveRequest): WideFieldSolveResult {
        val started = System.nanoTime()
        if (request.frameWidth <= 0 || request.frameHeight <= 0 ||
            request.initialFovWidthDeg !in 5.0..140.0 || request.initialFovHeightDeg !in 5.0..140.0
        ) return failure("Invalid camera field metadata", WideFieldSolveFailure.INVALID_LENS_METADATA, started)
        if (request.extraction.stars.size < MINIMUM_STARS) {
            return failure("Need at least $MINIMUM_STARS detected stars", WideFieldSolveFailure.INSUFFICIENT_STARS, started)
        }

        request.imuDirection?.let { prior ->
            val local = ImuAssistedWideFieldSolver.solve(
                extraction = request.extraction,
                frameWidth = request.frameWidth,
                frameHeight = request.frameHeight,
                fovWidthDeg = request.initialFovWidthDeg,
                fovHeightDeg = request.initialFovHeightDeg,
                imuDirection = prior,
                instant = request.observationTime,
                site = request.site,
                catalog = request.catalog
            )
            if (local.success && local.center != null) {
                val equatorial = CoordinateTransform.topocentricToJ2000(local.center, request.observationTime, request.site, null)
                return success(
                    message = local.message,
                    equatorial = equatorial,
                    rotationDeg = local.rotationDeg ?: 0.0,
                    request = request,
                    quality = WideFieldSolveQuality(
                        matchedStars = local.matchedStars,
                        rmsResidualDeg = local.residualDeg,
                        confidence = confidence(local.matchedStars, local.residualDeg),
                        usedImuPrior = true,
                        elapsedMs = elapsedMs(started)
                    )
                )
            }
        }

        val blind = BlindWideFieldMatcher.solve(request)
            ?: return failure("No unambiguous all-sky bright-star candidate", WideFieldSolveFailure.NO_CANDIDATE, started,
                usedImu = request.imuDirection != null, blind = true)
        return success(
            message = "Matched ${blind.matches} stars by all-sky geometric index",
            equatorial = blind.center,
            rotationDeg = blind.rotationDeg,
            request = request,
            quality = WideFieldSolveQuality(
                matchedStars = blind.matches,
                rmsResidualDeg = blind.residualDeg,
                confidence = confidence(blind.matches, blind.residualDeg),
                usedImuPrior = request.imuDirection != null,
                blindFallbackUsed = request.imuDirection != null,
                elapsedMs = elapsedMs(started)
            )
        )
    }

    private fun success(message: String, equatorial: EquatorialCoordinates, rotationDeg: Double,
        request: WideFieldSolveRequest, quality: WideFieldSolveQuality): WideFieldSolveResult {
        val scale = ((request.initialFovWidthDeg / request.frameWidth) +
            (request.initialFovHeightDeg / request.frameHeight)) * 1800.0
        return WideFieldSolveResult(true, message, equatorial.raDeg, equatorial.decDeg,
            normalize360(rotationDeg), request.initialFovWidthDeg, request.initialFovHeightDeg, scale, quality)
    }

    private fun failure(message: String, reason: WideFieldSolveFailure, started: Long,
        usedImu: Boolean = false, blind: Boolean = false) = WideFieldSolveResult(
        success = false, message = message, failure = reason,
        quality = WideFieldSolveQuality(usedImuPrior = usedImu, blindFallbackUsed = blind, elapsedMs = elapsedMs(started))
    )

    private fun confidence(matches: Int, residual: Double?): Double {
        val residualScore = 1.0 - ((residual ?: 1.0) / 0.5).coerceIn(0.0, 1.0)
        return ((matches - 3) / 8.0).coerceIn(0.0, 1.0) * residualScore
    }
    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000
    private fun normalize360(value: Double) = ((value % 360.0) + 360.0) % 360.0
}

/**
 * Lost-in-space matcher using bright anchor triangles and all extracted stars for verification.
 * The compact runtime index deliberately uses catalog stars through magnitude 3.5 as anchors;
 * the full magnitude-six catalog remains available for candidate verification.
 */
internal object BlindWideFieldMatcher {
    private const val ANCHOR_MAGNITUDE = 3.5
    private const val DETECTED_LIMIT = 12
    private const val TRIANGLE_TOLERANCE_RAD = Math.PI / 180.0 * 0.35
    private const val VERIFY_TOLERANCE_RAD = Math.PI / 180.0 * 0.55
    private const val MINIMUM_MATCHES = 4

    fun solve(request: WideFieldSolveRequest): BlindCandidate? {
        val image = request.extraction.stars.sortedByDescending { it.snr }.take(DETECTED_LIMIT)
            .map { imageVector(it, request.frameWidth, request.frameHeight, request.initialFovWidthDeg, request.initialFovHeightDeg) }
        if (image.size < MINIMUM_MATCHES) return null
        val catalog = request.catalog.stars.filter { it.magnitude <= ANCHOR_MAGNITUDE }
        if (catalog.size < MINIMUM_MATCHES) return null
        val catalogVectors = catalog.map { it to vector(it.raDeg, it.decDeg) }
        val index = buildTriangleIndex(catalogVectors)
        val imageTriangles = triangles(image)
        val clusters = HashMap<CandidateCluster, BlindCandidate>()
        for (triangle in imageTriangles) {
            for (target in index.candidates(triangle.vectors)) {
                for (permutation in permutations(target)) {
                    if (!sameTriangle(triangle.vectors, permutation)) continue
                    val rotation = rotationFromPairs(triangle.vectors, permutation) ?: continue
                    val candidate = verify(rotation, image, request.catalog.stars) ?: continue
                    val score = candidate.matches * 10.0 - candidate.residualDeg * 10.0
                    val scored = candidate.copy(score = score)
                    val key = CandidateCluster.of(scored.center)
                    if (score > (clusters[key]?.score ?: Double.NEGATIVE_INFINITY)) clusters[key] = scored
                }
            }
        }
        val best = clusters.values.maxByOrNull { it.score } ?: return null
        return best.takeIf { it.matches >= MINIMUM_MATCHES && it.residualDeg <= 0.45 }
    }

    /**
     * Compact all-sky index: each bright anchor contributes triangles from its nearest fourteen
     * bright neighbours. This retains local sky geometry while keeping the runtime candidate set
     * in the tens of thousands rather than all C(n,3) combinations.
     */
    private fun buildTriangleIndex(stars: List<Pair<PhoneCatalogStar, Vec>>): TriangleIndex {
        val map = HashMap<TriangleKey, MutableList<List<Vec>>>()
        for (anchor in stars.indices) {
            val neighbours = stars.indices.asSequence().filter { it != anchor }
                .sortedBy { angularDistance(stars[anchor].second, stars[it].second) }.take(14).toList()
            for (a in 0 until neighbours.size - 1) for (b in a + 1 until neighbours.size) {
                val triangle = listOf(stars[anchor].second, stars[neighbours[a]].second, stars[neighbours[b]].second)
                map.getOrPut(TriangleKey.of(triangle)) { mutableListOf() }.add(triangle)
            }
        }
        return TriangleIndex(map)
    }

    private fun verify(rotation: Mat3, image: List<Vec>, catalog: List<PhoneCatalogStar>): BlindCandidate? {
        val stars = catalog.map { vector(it.raDeg, it.decDeg) }
        var matches = 0
        var square = 0.0
        for (point in image) {
            val sky = rotation * point
            val distance = stars.minOf { angularDistance(sky, it) }
            if (distance <= VERIFY_TOLERANCE_RAD) { matches++; square += distance * distance }
        }
        if (matches == 0) return null
        val center = rotation * Vec(0.0, 0.0, 1.0)
        val yAxis = rotation * Vec(0.0, 1.0, 0.0)
        val north = Vec(-sin(center.ra), cos(center.ra), 0.0)
        val east = cross(north, center).unit()
        val rotationDeg = Math.toDegrees(atan2(dot(yAxis, east), dot(yAxis, north)))
        return BlindCandidate(EquatorialCoordinates(center.raDeg, center.decDeg), rotationDeg, matches,
            Math.toDegrees(kotlin.math.sqrt(square / matches)), 0.0)
    }

    private fun triangles(points: List<Vec>): List<ImageTriangle> = buildList {
        for (a in 0 until points.size - 2) for (b in a + 1 until points.size - 1) for (c in b + 1 until points.size) {
            add(ImageTriangle(listOf(points[a], points[b], points[c])))
        }
    }
    private fun sameTriangle(a: List<Vec>, b: List<Vec>): Boolean {
        val aa = sideLengths(a); val bb = sideLengths(b)
        return aa.indices.all { kotlin.math.abs(aa[it] - bb[it]) <= TRIANGLE_TOLERANCE_RAD }
    }
    private fun sideLengths(p: List<Vec>) = doubleArrayOf(angularDistance(p[0], p[1]), angularDistance(p[0], p[2]), angularDistance(p[1], p[2])).also { it.sort() }
    private fun rotationFromPairs(source: List<Vec>, target: List<Vec>): Mat3? {
        val s = basis(source) ?: return null; val t = basis(target) ?: return null
        // [basis] stores basis vectors as rows. R = TᵀS maps source vectors onto target vectors.
        fun row(a: Double, b: Double, c: Double) = Vec(
            a * s.x.x + b * s.y.x + c * s.z.x,
            a * s.x.y + b * s.y.y + c * s.z.y,
            a * s.x.z + b * s.y.z + c * s.z.z
        )
        return Mat3(row(t.x.x, t.y.x, t.z.x), row(t.x.y, t.y.y, t.z.y), row(t.x.z, t.y.z, t.z.z))
    }
    private fun basis(p: List<Vec>): Mat3? {
        val x = p[0].unit(); val temp = p[1] - x * dot(p[1], x); val y = temp.unitOrNull() ?: return null
        return Mat3(x, y, cross(x, y).unit())
    }
    private fun permutations(values: List<Vec>) = listOf(
        listOf(values[0], values[1], values[2]), listOf(values[0], values[2], values[1]),
        listOf(values[1], values[0], values[2]), listOf(values[1], values[2], values[0]),
        listOf(values[2], values[0], values[1]), listOf(values[2], values[1], values[0])
    )
    private fun imageVector(star: ExtractedStar, w: Int, h: Int, fovW: Double, fovH: Double) = Vec(
        ((star.x / w) * 2.0 - 1.0) * tan(Math.toRadians(fovW / 2.0)),
        (1.0 - (star.y / h) * 2.0) * tan(Math.toRadians(fovH / 2.0)), 1.0
    ).unit()
    private fun vector(raDeg: Double, decDeg: Double): Vec { val ra = Math.toRadians(raDeg); val dec = Math.toRadians(decDeg); return Vec(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec)) }
    private fun angularDistance(a: Vec, b: Vec) = acos(dot(a, b).coerceIn(-1.0, 1.0))
    private fun angularDistance(a: EquatorialCoordinates, b: EquatorialCoordinates) = angularDistance(vector(a.raDeg, a.decDeg), vector(b.raDeg, b.decDeg))

    internal data class BlindCandidate(val center: EquatorialCoordinates, val rotationDeg: Double, val matches: Int, val residualDeg: Double, val score: Double)
    private data class ImageTriangle(val vectors: List<Vec>)
    private data class CandidateCluster(val raBin: Int, val decBin: Int) {
        companion object {
            fun of(center: EquatorialCoordinates) = CandidateCluster(
                (center.raDeg / 2.0).toInt(), ((center.decDeg + 90.0) / 2.0).toInt()
            )
        }
    }
    private data class TriangleKey(val a: Int, val b: Int, val c: Int) {
        companion object {
            private const val STEP_DEG = 0.35
            fun of(points: List<Vec>): TriangleKey {
                val values = sideLengths(points)
                return TriangleKey(
                    (Math.toDegrees(values[0]) / STEP_DEG).toInt(),
                    (Math.toDegrees(values[1]) / STEP_DEG).toInt(),
                    (Math.toDegrees(values[2]) / STEP_DEG).toInt()
                )
            }
        }
    }
    private class TriangleIndex(private val map: Map<TriangleKey, List<List<Vec>>>) {
        fun candidates(points: List<Vec>): Sequence<List<Vec>> {
            val key = TriangleKey.of(points)
            return sequence {
                for (da in -1..1) for (db in -1..1) for (dc in -1..1) {
                    map[TriangleKey(key.a + da, key.b + db, key.c + dc)]?.forEach { yield(it) }
                }
            }
        }
    }
    private data class Vec(val x: Double, val y: Double, val z: Double) {
        val ra get() = atan2(y, x); val raDeg get() = ((Math.toDegrees(ra) % 360.0) + 360.0) % 360.0
        val decDeg get() = Math.toDegrees(atan2(z, hypot(x, y)))
        fun unit() = this * (1.0 / kotlin.math.sqrt(dot(this, this)))
        fun unitOrNull(): Vec? = kotlin.math.sqrt(dot(this, this)).takeIf { it > 1e-8 }?.let { this * (1.0 / it) }
        operator fun minus(other: Vec) = Vec(x - other.x, y - other.y, z - other.z)
        operator fun times(scale: Double) = Vec(x * scale, y * scale, z * scale)
    }
    private data class Mat3(val x: Vec, val y: Vec, val z: Vec) {
        operator fun times(v: Vec) = Vec(dot(x, v), dot(y, v), dot(z, v))
        operator fun times(other: Mat3) = Mat3(this * other.x, this * other.y, this * other.z)
        fun transpose() = Mat3(Vec(x.x, y.x, z.x), Vec(x.y, y.y, z.y), Vec(x.z, y.z, z.z))
    }
    private fun dot(a: Vec, b: Vec) = a.x * b.x + a.y * b.y + a.z * b.z
    private fun cross(a: Vec, b: Vec) = Vec(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
}
