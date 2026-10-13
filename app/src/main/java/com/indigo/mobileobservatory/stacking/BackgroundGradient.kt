package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException
import kotlin.math.*

/** Normalized image rectangle; excluded only from background estimation, never from output. */
data class TargetMask(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun contains(x: Double, y: Double): Boolean = x >= left && x <= right && y >= top && y <= bottom
}
enum class BackgroundMode { AUTO, MANUAL }
/** Center in image coordinates normalized to 0..1; a DBE sample, not an exclusion mask. */
data class BackgroundSamplePoint(val x: Double, val y: Double)
data class BackgroundParameters(
    val enabled: Boolean = true,
    val strength: Double = 0.8,
    val gridSize: Int = 12,
    val targetMasks: List<TargetMask> = emptyList(),
    val mode: BackgroundMode = BackgroundMode.AUTO,
    /** Circular patch radius as a fraction of the shorter image side. */
    val sampleRadius: Double = .025,
    val samplePoints: List<BackgroundSamplePoint> = emptyList()
)
data class BackgroundStatistics(val median: Double, val gridSpread: Double, val noise: Double)
data class BackgroundModel(
    val applied: Boolean, val reason: String, val gridSize: Int, val acceptedSamples: Int,
    val coefficients: List<List<Double>>, val channelAnchors: List<Double>, val strength: Double,
    val before: BackgroundStatistics, val after: BackgroundStatistics,
    val targetMasks: List<TargetMask>, val method: String = "Robust masked quadratic; tile 20th percentile; positive residual clipping",
    val mode: BackgroundMode = BackgroundMode.AUTO, val sampleRadius: Double = .025,
    val samplePoints: List<BackgroundSamplePoint> = emptyList()
)
data class BackgroundResult(val image: LinearImage, val model: BackgroundModel)

/** Conservative smooth additive gradient correction before any nonlinear display operation. */
object BackgroundGradient {
    private data class Sample(val x: Double, val y: Double, val values: DoubleArray)

    fun correct(image: LinearImage, parameters: BackgroundParameters, cancelled: () -> Boolean = { false }): BackgroundResult {
        val model = estimate(image,parameters,cancelled)
        if (!model.applied) return BackgroundResult(image,model)
        val values = BackgroundPixels(image,model)
        val out = FloatArray(image.pixels.size)
        for (y in 0 until image.height) {
            if (cancelled()) throw CancellationException("Background correction cancelled")
            for (x in 0 until image.width) for (c in 0 until image.channels) {
                val p = y*image.width+x
                out[p*image.channels+c] = values.sample(p,c)
            }
        }
        return BackgroundResult(LinearImage(image.width,image.height,image.channels,out),model)
    }

    /** Fits/validates only the small model. No corrected image is materialized or retained. */
    fun estimate(image: LinearImage, parameters: BackgroundParameters, cancelled: () -> Boolean = { false }): BackgroundModel {
        require(parameters.strength.isFinite() && parameters.strength in 0.0..1.0)
        val grid = parameters.gridSize.coerceIn(6, 24)
        val manual = parameters.mode == BackgroundMode.MANUAL
        val samples = ArrayList<Sample>()
        val differences = ArrayList<Double>()
        val invalidPoints = if (manual) validatePoints(image, parameters) else null
        // Star masks need no full-frame bitmap. Detect once and mask sample points within PSF wings.
        val stars = if (invalidPoints == null) StarRegistration.detect(image, cancelled = cancelled).stars else emptyList()
        if (manual && invalidPoints == null) {
            val radius = max(2.0, min(image.width, image.height) * parameters.sampleRadius)
            for (point in parameters.samplePoints) {
                if (cancelled()) throw CancellationException("Background sample cancelled")
                val cx = point.x * image.width - .5; val cy = point.y * image.height - .5
                val x0 = max(0, floor(cx - radius).toInt()); val x1 = min(image.width - 1, ceil(cx + radius).toInt())
                val y0 = max(0, floor(cy - radius).toInt()); val y1 = min(image.height - 1, ceil(cy + radius).toInt())
                val stride = max(1, ceil(sqrt((x1-x0+1).toDouble()*(y1-y0+1)/256)).toInt())
                val values = Array(image.channels) { ArrayList<Double>() }
                var total = 0; var sumX = 0.0; var sumY = 0.0
                for (y in y0..y1 step stride) for (x in x0..x1 step stride) {
                    if ((x-cx).pow(2)+(y-cy).pow(2)>radius*radius) continue
                    total++
                    val nx = (x+.5)/image.width; val ny = (y+.5)/image.height
                    if (parameters.targetMasks.any { it.contains(nx,ny) }) continue
                    if (stars.any { (x-it.x).pow(2)+(y-it.y).pow(2)<max(4.0,it.radius*3).pow(2) }) continue
                    val p = y*image.width+x
                    if ((0 until image.channels).any { !image.pixels[p*image.channels+it].isFinite() }) continue
                    for (c in 0 until image.channels) values[c] += image.pixels[p*image.channels+c].toDouble()
                    sumX += 2*nx-1; sumY += 2*ny-1
                    if (x+1<image.width) {
                        val difference=abs(StarRegistration.luminance(image,p)-StarRegistration.luminance(image,p+1))
                        if (difference.isFinite()) differences += difference
                    }
                }
                val used=values[0].size
                // The fitted location follows valid pixel centers, including clipped edge patches.
                if (used>=max(8,total/2)) samples += Sample(sumX/used,sumY/used,
                    DoubleArray(image.channels) { c -> StarRegistration.median(values[c]) })
            }
        }
        if (!manual) for (gy in 0 until grid) for (gx in 0 until grid) {
            if (cancelled()) throw CancellationException("Background model cancelled")
            val x0 = gx * image.width / grid; val x1 = (gx + 1) * image.width / grid
            val y0 = gy * image.height / grid; val y1 = (gy + 1) * image.height / grid
            val channelSamples = Array(image.channels) { ArrayList<Double>() }
            val stride = max(1, sqrt(((x1-x0).toDouble() * (y1-y0)) / 256).toInt())
            var total = 0
            for (y in y0 until y1 step stride) for (x in x0 until x1 step stride) {
                total++
                if (parameters.targetMasks.any { it.contains(x.toDouble() / image.width, y.toDouble() / image.height) }) continue
                if (stars.any { (x - it.x).pow(2) + (y - it.y).pow(2) < max(4.0, it.radius * 3).pow(2) }) continue
                val p = y * image.width + x
                for (c in 0 until image.channels) channelSamples[c] += image.pixels[p * image.channels + c].toDouble()
                if (x + 1 < image.width) differences += abs(StarRegistration.luminance(image,p) - StarRegistration.luminance(image,p+1))
            }
            if (channelSamples[0].size >= max(8, total / 3)) {
                samples += Sample((x0 + x1).toDouble() / image.width - 1, (y0 + y1).toDouble() / image.height - 1,
                    DoubleArray(image.channels) { c -> channelSamples[c].sort(); channelSamples[c][(channelSamples[c].size * .20).toInt()] })
            }
        }
        fun summary(s: List<Sample>): BackgroundStatistics {
            val values = s.map { it.values.average() }.sorted()
            return BackgroundStatistics(StarRegistration.median(values), if (values.isEmpty()) 0.0 else values[(values.size * .90).toInt()] - values[(values.size * .10).toInt()], StarRegistration.median(differences) / .9538726)
        }
        val before = summary(samples)
        val method = if (manual) "Robust masked quadratic; manual patch medians; positive residual clipping"
            else "Robust masked quadratic; tile 20th percentile; positive residual clipping"
        fun unchanged(reason: String, coefficients: List<List<Double>> = emptyList(), anchors: List<Double> = emptyList()) =
            BackgroundModel(false, reason, grid, samples.size, coefficients, anchors, parameters.strength, before, before, parameters.targetMasks,
                method, parameters.mode, parameters.sampleRadius, parameters.samplePoints)
        if (!parameters.enabled || parameters.strength == 0.0) return unchanged("DISABLED")
        if (invalidPoints != null) return unchanged(invalidPoints)
        if (samples.size < if (manual) 12 else max(24, grid * grid / 3))
            return unchanged(if (manual) "INSUFFICIENT_BACKGROUND_POINTS" else "INSUFFICIENT_UNMASKED_BACKGROUND")
        if (manual && !manualCoverage(samples)) return unchanged("UNBALANCED_BACKGROUND_COVERAGE")
        var selected = samples.toList()
        var coefficients = List(image.channels) { DoubleArray(6) }
        repeat(4) {
            coefficients = (0 until image.channels).map { c -> solve(selected,c,if (manual) 1e-5 else 0.0) ?: return unchanged("SINGULAR_BACKGROUND_MODEL") }
            val residuals = selected.map { sample -> sample.values.average() - coefficients.map { evaluate(it, sample.x, sample.y) }.average() }
            val center = StarRegistration.median(residuals)
            val mad = StarRegistration.median(residuals.map { abs(it - center) }) * 1.4826
            // Bright extended structure is excluded from the fit instead of forced into the background.
            val gate = max(before.noise * .7, max(.00005, mad * 2.5))
            selected = selected.filterIndexed { i, _ -> residuals[i] < center + gate && residuals[i] > center - gate * 2 }
            if (selected.size < if (manual) 12 else max(18, grid * grid / 4)) return unchanged("EXTENDED_SIGNAL_OR_POOR_BACKGROUND_COVERAGE")
        }
        // Reject fits supported only by one corner, even if the matrix is numerically solvable.
        if (manual) {
            if (!manualCoverage(selected)) return unchanged("UNBALANCED_BACKGROUND_COVERAGE")
        } else for (qy in 0..1) for (qx in 0..1) if (selected.count { (it.x >= 0) == (qx == 1) && (it.y >= 0) == (qy == 1) } < 3) return unchanged("UNBALANCED_BACKGROUND_COVERAGE")
        coefficients = (0 until image.channels).map { c -> solve(selected,c,if (manual) 1e-5 else 0.0) ?: return unchanged("SINGULAR_BACKGROUND_MODEL") }
        val residuals = selected.map { sample -> sample.values.average() - coefficients.map { evaluate(it, sample.x, sample.y) }.average() }
        val fitError = sqrt(residuals.sumOf { it * it } / residuals.size)
        val anchors = coefficients.map { coef -> StarRegistration.median(selected.map { evaluate(coef,it.x,it.y) }) }
        val coeffList = coefficients.map { it.toList() }
        if (!fitError.isFinite() || fitError > max(.003, before.noise * 3 + before.gridSpread * .18)) return unchanged("IRREGULAR_BACKGROUND_MODEL",coeffList,anchors)
        val extrema = coefficients.flatMap { coef -> (-2..2).flatMap { y -> (-2..2).map { x -> evaluate(coef,x/2.0,y/2.0) } } }
        if (extrema.any { !it.isFinite() } || extrema.maxOrNull()!! - extrema.minOrNull()!! > max(.25,before.gridSpread*3.5)) return unchanged("UNSAFE_MODEL_EXTRAPOLATION",coeffList,anchors)
        var belowZero = 0
        for (y in 0 until image.height) {
            if (cancelled()) throw CancellationException("Background correction cancelled")
            val ny = 2.0 * (y + .5) / image.height - 1
            for (x in 0 until image.width) {
                val nx = 2.0 * (x + .5) / image.width - 1
                for (c in 0 until image.channels) {
                    val i = (y * image.width + x) * image.channels + c
                    val v = image.pixels[i] - parameters.strength * (evaluate(coefficients[c],nx,ny) - anchors[c])
                    if (v < -3*before.noise) belowZero++
                }
            }
        }
        if (belowZero > image.pixels.size / 50) return unchanged("CORRECTION_WOULD_CRUSH_SHADOWS",coeffList,anchors)
        val correctedSamples = samples.map { sample -> sample.copy(values = DoubleArray(image.channels) { c -> sample.values[c] - parameters.strength * (evaluate(coefficients[c],sample.x,sample.y)-anchors[c]) }) }
        differences.clear()
        // Preserve the previous Float rounding before computing luminance statistics.
        fun correctedLuminance(p: Int): Double {
            val nx = 2.0*(p%image.width+.5)/image.width-1
            val ny = 2.0*(p/image.width+.5)/image.height-1
            fun value(c: Int) = (image.pixels[p*image.channels+c]-parameters.strength*
                (evaluate(coefficients[c],nx,ny)-anchors[c])).toFloat()
            return if (image.channels == 1) value(0).toDouble()
                else value(0)*.2126+value(1)*.7152+value(2)*.0722
        }
        val noiseStride = max(1,sqrt(image.width.toDouble()*image.height/12000).toInt())
        for (y in 0 until image.height step noiseStride) for(x in 0 until image.width-1 step noiseStride) {
            if(parameters.targetMasks.any { it.contains(x.toDouble()/image.width,y.toDouble()/image.height) }) continue
            val p=y*image.width+x
            differences+=abs(correctedLuminance(p)-correctedLuminance(p+1))
        }
        val after = summary(correctedSamples)
        return BackgroundModel(true,
            if (manual) "APPLIED_MANUAL_BACKGROUND" else "APPLIED_MASK_LARGE_NEBULAE_MANUALLY",
            grid,selected.size,coeffList,anchors,parameters.strength,before,after,parameters.targetMasks,
            method,parameters.mode,parameters.sampleRadius,parameters.samplePoints)
    }

    private fun validatePoints(image: LinearImage, parameters: BackgroundParameters): String? {
        if (!parameters.sampleRadius.isFinite() || parameters.sampleRadius !in .005.. .10 ||
            parameters.samplePoints.size>128 || parameters.samplePoints.any {
                !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0
            }) return "INVALID_BACKGROUND_POINTS"
        val separation=max(1.5,min(image.width,image.height)*parameters.sampleRadius*.25)
        for (i in parameters.samplePoints.indices) for (j in 0 until i) {
            val a=parameters.samplePoints[i];val b=parameters.samplePoints[j]
            if (((a.x-b.x)*image.width).pow(2)+((a.y-b.y)*image.height).pow(2)<separation*separation)
                return "DUPLICATE_BACKGROUND_POINTS"
        }
        if (parameters.samplePoints.size<12) return "INSUFFICIENT_BACKGROUND_POINTS"
        if (!manualCoverage(parameters.samplePoints.map { Sample(2*it.x-1,2*it.y-1,DoubleArray(0)) }))
            return "UNBALANCED_BACKGROUND_COVERAGE"
        return null
    }

    private fun manualCoverage(samples: List<Sample>): Boolean {
        if (samples.size<12 || samples.minOf { it.x }>-.5 || samples.maxOf { it.x }<.5 ||
            samples.minOf { it.y }>-.5 || samples.maxOf { it.y }<.5) return false
        // Twelve points overdetermine six coefficients. At least two must survive in each
        // quadrant, permitting a useful 4x3 arrangement while rejecting one-sided extrapolation.
        for (qy in 0..1) for (qx in 0..1)
            if (samples.count { (it.x>=0)==(qx==1) && (it.y>=0)==(qy==1) }<2) return false
        return true
    }

    private fun basis(x:Double,y:Double) = doubleArrayOf(1.0,x,y,x*x,x*y,y*y)
    private fun evaluate(coef:DoubleArray,x:Double,y:Double):Double = coef[0]+coef[1]*x+coef[2]*y+coef[3]*x*x+coef[4]*x*y+coef[5]*y*y
    private fun solve(samples:List<Sample>,channel:Int,minimumRelativePivot:Double=0.0):DoubleArray? {
        val matrix = Array(6) { DoubleArray(7) }
        for (sample in samples) {
            val b = basis(sample.x,sample.y)
            for (i in 0..5) { for (j in 0..5) matrix[i][j] += b[i]*b[j]; matrix[i][6] += b[i]*sample.values[channel] }
        }
        val scale=(0..5).maxOf { abs(matrix[it][it]) }
        for (i in 0..5) {
            val pivot = (i..5).maxByOrNull { abs(matrix[it][i]) } ?: return null
            // Manual points may all lie on a circle/conic: a quadratic would then be
            // underconstrained despite apparent full-frame coverage. Reject near singularity.
            if (abs(matrix[pivot][i]) < max(1e-8,scale*minimumRelativePivot)) return null
            val row = matrix[i]; matrix[i] = matrix[pivot]; matrix[pivot] = row
            val divisor = matrix[i][i]; for (j in i..6) matrix[i][j] /= divisor
            for (r in 0..5) if (r != i) { val f = matrix[r][i]; for (j in i..6) matrix[r][j] -= f*matrix[i][j] }
        }
        return DoubleArray(6) { matrix[it][6] }
    }
}

/** An immutable-image view applying a validated model on demand, with identical Float rounding. */
internal class BackgroundPixels(val image: LinearImage, model: BackgroundModel?) {
    private val coefficients = model?.takeIf { it.applied }?.coefficients?.map { it.toDoubleArray() }
    private val anchors = model?.channelAnchors
    private val strength = model?.strength ?: 0.0
    val hasCorrection: Boolean get() = coefficients != null
    fun sample(p: Int,c: Int): Float {
        if (coefficients == null) return image.pixels[p*image.channels+c]
        val x = 2.0*(p%image.width+.5)/image.width-1
        val y = 2.0*(p/image.width+.5)/image.height-1
        return sample(p,c,x,y)
    }
    /** Reuse one pixel's coordinates across RGB without changing arithmetic or Float rounding. */
    fun sample(p: Int,c: Int,x: Double,y: Double): Float {
        val value = image.pixels[p*image.channels+c]
        val coef = coefficients?.get(c) ?: return value
        val background = coef[0]+coef[1]*x+coef[2]*y+coef[3]*x*x+coef[4]*x*y+coef[5]*y*y
        return (value-strength*(background-anchors!![c])).toFloat()
    }
    fun luminance(p: Int): Double = if (image.channels == 1) sample(p,0).toDouble()
        else sample(p,0)*.2126+sample(p,1)*.7152+sample(p,2)*.0722
}

/** One small model per immutable image identity and recipe; no full-image output cache.
 * The weak key does not extend the lifetime of discarded live snapshots. */
class BackgroundModelCache {
    private var imageRef = java.lang.ref.WeakReference<LinearImage>(null)
    private var parameters: BackgroundParameters? = null
    private var cached: BackgroundModel? = null
    private var generation = 0L
    @Volatile var fitCount: Long = 0L
        private set

    fun clear() = synchronized(this) { generation++; imageRef.clear(); parameters = null; cached = null }

    fun model(image: LinearImage, requested: BackgroundParameters, cancelled: () -> Boolean = { false }): BackgroundModel {
        if (cancelled()) throw CancellationException("Background model cancelled")
        // Freeze list contents so later edits cannot mutate the stored cache key.
        val key = requested.copy(targetMasks=requested.targetMasks.toList(),samplePoints=requested.samplePoints.toList())
        val startedGeneration = synchronized(this) {
            if (imageRef.get() === image && parameters == key) cached?.let { return it }
            generation
        }
        val result = BackgroundGradient.estimate(image,key,cancelled)
        if (cancelled()) throw CancellationException("Background model cancelled")
        synchronized(this) {
            if (generation == startedGeneration) {
                imageRef = java.lang.ref.WeakReference(image); parameters = key; cached = result
            }
            fitCount++
        }
        return result
    }
}
