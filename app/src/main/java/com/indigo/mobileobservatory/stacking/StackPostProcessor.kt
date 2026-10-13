package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException
import kotlin.math.*

enum class ProcessingPreset { RAW, NATURAL, INTENSE }
data class StackProcessingParameters(
    val preset: ProcessingPreset = ProcessingPreset.NATURAL,
    val blackPoint: Double = 0.0, val whitePoint: Double = 1.0, val midtone: Double = .5,
    val curve: Double = 4.0, val brightness: Double = 1.0, val localContrast: Double = 0.0,
    val highlightCompression: Double = .3, val sharpening: Double = 0.0,
    val autoWhiteBalance: Boolean = false, val redBalance: Double = 1.0, val blueBalance: Double = 1.0,
    val saturation: Double = 1.0, val background: BackgroundParameters = BackgroundParameters(),
    val stretchEnabled: Boolean = true, val whiteBalanceEnabled: Boolean = true,
    val saturationEnabled: Boolean = true
) {
    companion object {
        fun preset(preset: ProcessingPreset): StackProcessingParameters = when(preset) {
            ProcessingPreset.RAW -> StackProcessingParameters(preset,curve = 0.0,highlightCompression = 0.0)
            ProcessingPreset.NATURAL -> StackProcessingParameters(preset)
            ProcessingPreset.INTENSE -> StackProcessingParameters(preset,midtone = .25,curve = 80.0,localContrast = .15,highlightCompression = .65)
        }
    }
}

/** Immutable source + small model + optional full-resolution filter planes. Float export and
 * bounded-row Bitmap rendering share spatial and color processing, including original filter
 * halos; display rows may interpolate the nonlinear tone curve through a bounded LUT. */
class PreparedStack internal constructor(
    private val source: BackgroundPixels,
    override val parameters: StackProcessingParameters,
    override val background: BackgroundModel?,
    private val gains: DoubleArray,
    private val luma: FloatArray?,
    private val detailBlur: FloatArray?,
    private val contrastBlur: FloatArray?,
    private val noise: Double,
    private val started: Long
): StackProcessingResult {
    val width: Int get() = source.image.width
    val height: Int get() = source.image.height
    val channels: Int get() = source.image.channels
    override val whiteBalanceGains: List<Double> = gains.toList()
    override var elapsedMs: Long = (System.nanoTime()-started)/1_000_000
        private set
    internal val workingPlaneCount: Int = listOf(luma,detailBlur,contrastBlur).count { it != null }
    internal val noiseEstimated: Boolean = luma != null
    private val range = parameters.whitePoint-parameters.blackPoint
    private val curveNorm = if (parameters.curve > 0) asinh(parameters.curve) else 1.0
    private val saturation = if (parameters.saturationEnabled) parameters.saturation else 1.0
    @Volatile private var previewToneLut: FloatArray? = null
    internal val previewToneLutAllocated: Boolean get() = previewToneLut != null

    /** Write one native-coordinate row, optionally sampling every [xStep] pixels. */
    fun writeRow(y: Int,output: FloatArray,xStep: Int = 1) {
        require(y in 0 until height && xStep > 0)
        val count = ((width.toLong()+xStep-1)/xStep).toInt()
        require(output.size.toLong() >= count.toLong()*channels)
        for (x in 0 until count) writePixel(y*width+x*xStep,output,x*channels)
    }

    /** Display-oriented row with interpolated nonlinear tone mapping. Export and [writeRow]
     * retain the exact scalar path. RAW and disabled stretch avoid creating the LUT.
     */
    fun writePreviewRow(y: Int,output: FloatArray,xStep: Int = 1) {
        require(y in 0 until height && xStep > 0)
        val count = ((width.toLong()+xStep-1)/xStep).toInt()
        require(output.size.toLong() >= count.toLong()*channels)
        val toneLut = previewToneLut()
        for (x in 0 until count) writePixel(y*width+x*xStep,output,x*channels,toneLut)
    }

    internal fun finishRender() { elapsedMs = (System.nanoTime()-started)/1_000_000 }

    internal fun writePixel(p: Int,output: FloatArray,out: Int,toneLut: FloatArray? = null) {
        // The quadratic background shares coordinates across channels. Keep the exact
        // division/order from BackgroundPixels.sample; reciprocal multiplication can round differently.
        val backgroundX = if (source.hasCorrection) 2.0*(p%width+.5)/width-1 else 0.0
        val backgroundY = if (source.hasCorrection) 2.0*(p/width+.5)/height-1 else 0.0
        val r = source.sample(p,0,backgroundX,backgroundY)
        val g = if (channels == 3) source.sample(p,1,backgroundX,backgroundY) else r
        val b = if (channels == 3) source.sample(p,2,backgroundX,backgroundY) else r
        if (parameters.preset == ProcessingPreset.RAW) {
            output[out] = r
            if (channels == 3) { output[out+1] = g; output[out+2] = b }
            return
        }
        // Retain the old Float luminance rounding even on the allocation-free path.
        val original = luma?.get(p)?.toDouble() ?: (if (channels == 1) r.toDouble()
            else r*gains[0]*.2126+g*gains[1]*.7152+b*gains[2]*.0722).toFloat().toDouble()
        var value = original
        if (detailBlur != null) {
            val detail = value-detailBlur[p]
            val gated = sign(detail)*max(0.0,abs(detail)-noise*1.5)
            var lo = original; var hi = original
            val x = p%width; val y = p/width
            for (oy in -1..1) for (ox in -1..1) {
                val neighbor = luma!![(y+oy).coerceIn(0,height-1)*width+(x+ox).coerceIn(0,width-1)].toDouble()
                lo = min(lo,neighbor); hi = max(hi,neighbor)
            }
            value = (value+parameters.sharpening*gated).coerceIn(lo,hi)
        }
        if (contrastBlur != null) {
            val detail = value-contrastBlur[p]
            value += sign(detail)*max(0.0,abs(detail)-noise*1.5)*parameters.localContrast/(1+8*max(value,0.0))
        }
        val mapped = if (toneLut == null) mapToneExact(value) else mapTonePreview(value,toneLut)
        if (channels == 1) output[out] = mapped.toFloat()
        else {
            val colorScale = if (original > 1e-10) mapped/original else 0.0
            var chroma = 1.0
            for (c in 0..2) {
                val sample = when (c) { 0 -> r; 1 -> g; else -> b }
                val delta = (sample*gains[c]*colorScale-mapped)*saturation
                if (delta > 0) chroma = min(chroma,(1-mapped)/delta)
                if (delta < 0) chroma = min(chroma,-mapped/delta)
            }
            for (c in 0..2) {
                val sample = when (c) { 0 -> r; 1 -> g; else -> b }
                output[out+c] = (mapped+(sample*gains[c]*colorScale-mapped)*saturation*chroma.coerceIn(0.0,1.0)).coerceIn(0.0,1.0).toFloat()
            }
        }
    }

    private fun mapToneExact(value: Double): Double {
        var mapped = value.coerceIn(0.0,1.0)
        if (parameters.stretchEnabled) {
            mapped = ((value-parameters.blackPoint)/range).coerceIn(0.0,1.0)
            mapped = mapNormalizedToneExact(mapped)
        }
        return mapped.coerceIn(0.0,1.0)
    }

    private fun mapNormalizedToneExact(normalized: Double): Double {
        var mapped = if (parameters.curve > 0) asinh(parameters.curve*normalized)/curveNorm else normalized
        val m = parameters.midtone
        mapped = if (mapped <= 0 || mapped >= 1) mapped else ((m-1)*mapped)/((2*m-1)*mapped-m)
        mapped = 1.0-(1.0-mapped).pow(parameters.brightness)
        val shoulder = parameters.highlightCompression
        return (1-shoulder)*mapped+shoulder*(1-exp(-2*mapped))/(1-exp(-2.0))
    }

    private fun mapTonePreview(value: Double,toneLut: FloatArray): Double {
        val normalized = ((value-parameters.blackPoint)/range).coerceIn(0.0,1.0)
        if (normalized <= 0.0) return toneLut[0].toDouble()
        if (normalized >= 1.0) return toneLut[PREVIEW_TONE_LUT_INTERVALS].toDouble()
        val position = normalized*PREVIEW_TONE_LUT_INTERVALS
        val index = position.toInt()
        val fraction = position-index
        return toneLut[index]+(toneLut[index+1]-toneLut[index])*fraction
    }

    private fun previewToneLut(): FloatArray? {
        if (parameters.preset == ProcessingPreset.RAW || !parameters.stretchEnabled) return null
        previewToneLut?.let { return it }
        return synchronized(this) {
            previewToneLut ?: FloatArray(PREVIEW_TONE_LUT_INTERVALS+1) { index ->
                mapNormalizedToneExact(index.toDouble()/PREVIEW_TONE_LUT_INTERVALS).coerceIn(0.0,1.0).toFloat()
            }.also { previewToneLut = it }
        }
    }

    private companion object {
        const val PREVIEW_TONE_LUT_INTERVALS = 8192
    }
}
interface StackProcessingResult {
    val parameters: StackProcessingParameters
    val background: BackgroundModel?
    val whiteBalanceGains: List<Double>
    val elapsedMs: Long
}
data class ProcessedStack(val image: LinearImage, override val parameters: StackProcessingParameters,
    override val background: BackgroundModel?, override val whiteBalanceGains: List<Double>, override val elapsedMs: Long): StackProcessingResult

/** Recomputed from the original every time. Full-image processing retains exact scalar tone mapping. */
object StackPostProcessor {
    fun process(linear: LinearImage, parameters: StackProcessingParameters, cancelled: () -> Boolean = { false }): ProcessedStack {
        val start = System.nanoTime()
        val prepared = prepare(linear,parameters,cancelled=cancelled)
        if (parameters.preset == ProcessingPreset.RAW)
            return ProcessedStack(linear,parameters,null,prepared.whiteBalanceGains,(System.nanoTime()-start)/1_000_000)
        val output = FloatArray(linear.pixels.size)
        for (y in 0 until linear.height) {
            if (cancelled()) throw CancellationException("Postprocessing cancelled")
            for (x in 0 until linear.width) {
                val p = y*linear.width+x
                prepared.writePixel(p,output,p*linear.channels)
            }
        }
        return ProcessedStack(LinearImage(linear.width,linear.height,linear.channels,output),parameters,
            prepared.background,prepared.whiteBalanceGains,(System.nanoTime()-start)/1_000_000)
    }

    /** Prepare reusable tone mapping without an output image. Only enabled spatial filters
     * allocate full-resolution luminance/blur planes; the common no-filter path allocates none. */
    fun prepare(linear: LinearImage, parameters: StackProcessingParameters,
                backgroundCache: BackgroundModelCache? = null, cancelled: () -> Boolean = { false }): PreparedStack {
        val start = System.nanoTime()
        require(parameters.whitePoint > parameters.blackPoint && parameters.whitePoint.isFinite() && parameters.blackPoint.isFinite())
        require(parameters.midtone in .01.. .99 && parameters.curve in 0.0..200.0 && parameters.brightness in .05..8.0)
        require(parameters.sharpening in 0.0..2.0 && parameters.localContrast in 0.0..1.0 && parameters.highlightCompression in 0.0..1.0)
        require(parameters.redBalance in .25..4.0 && parameters.blueBalance in .25..4.0 && parameters.saturation in 0.0..2.0)
        if (cancelled()) throw CancellationException("Processing cancelled")
        if (parameters.preset == ProcessingPreset.RAW) return PreparedStack(BackgroundPixels(linear,null),parameters,
            null,doubleArrayOf(1.0,1.0,1.0),null,null,null,0.0,start)
        val bg = if (parameters.background.enabled) backgroundCache?.model(linear,parameters.background,cancelled)
            ?: BackgroundGradient.estimate(linear,parameters.background,cancelled) else null
        val source = BackgroundPixels(linear,bg)
        val count = linear.pixelCount
        val gains = whiteBalance(source,parameters)
        val filters = parameters.sharpening > 0 || parameters.localContrast > 0
        val luma = if (filters) FloatArray(count) else null
        if (luma != null) for (p in 0 until count) {
            if (p % 32768 == 0 && cancelled()) throw CancellationException("Stretch cancelled")
            luma[p] = balancedLuminance(source,gains,p).toFloat()
        }
        // Separable bounded-radius blur is only allocated when requested. Detail threshold tracks
        // high frequency noise, and range clamping prevents overshoot at planetary/stellar edges.
        val detailBlur = if (parameters.sharpening > 0) blur(luma!!,linear.width,linear.height,1,cancelled) else null
        val contrastBlur = if (parameters.localContrast > 0) blur(luma!!,linear.width,linear.height,max(2,min(linear.width,linear.height)/80).coerceAtMost(16),cancelled) else null
        var noise = 0.0
        if (luma != null) {
            val noiseSamples = ArrayList<Double>()
            val stride = max(1,count/12000)
            for (p in 0 until count - 1 step stride) if (p % linear.width != linear.width-1) noiseSamples += abs(luma[p+1]-luma[p]).toDouble()
            noise = StarRegistration.median(noiseSamples)/.9538726
        }
        return PreparedStack(source,parameters,bg,gains,luma,detailBlur,contrastBlur,noise,start)
    }

    internal fun balancedLuminance(source: BackgroundPixels,gains: DoubleArray,p: Int): Double =
        if (source.image.channels == 1) source.sample(p,0).toDouble()
        else source.sample(p,0)*gains[0]*.2126+source.sample(p,1)*gains[1]*.7152+source.sample(p,2)*gains[2]*.0722

    private fun whiteBalance(source:BackgroundPixels,p:StackProcessingParameters):DoubleArray {
        val image = source.image
        if (!p.whiteBalanceEnabled) return doubleArrayOf(1.0,1.0,1.0)
        val gains=doubleArrayOf(p.redBalance,1.0,p.blueBalance)
        if(!p.autoWhiteBalance || image.channels!=3) return gains
        val ratiosR=ArrayList<Double>(); val ratiosB=ArrayList<Double>()
        val count=image.width*image.height; val stride=max(1,count/20000)
        val levels=ArrayList<Double>(); for(i in 0 until count step stride) levels+=source.luminance(i)
        levels.sort(); val low=levels[(levels.size*.30).toInt()]; val high=levels[(levels.size*.95).toInt()]
        for(i in 0 until count step stride) {
            val r=source.sample(i,0).toDouble(); val g=source.sample(i,1).toDouble(); val b=source.sample(i,2).toDouble(); val l=source.luminance(i)
            if(l>max(.0001,low) && l<=high && max(r,max(g,b))<.95 && min(r,min(g,b))>.0001) { ratiosR+=g/r; ratiosB+=g/b }
        }
        if(ratiosR.size>=16) { gains[0]*=StarRegistration.median(ratiosR).coerceIn(.5,2.0); gains[2]*=StarRegistration.median(ratiosB).coerceIn(.5,2.0) }
        return gains
    }

    private fun blur(input:FloatArray,w:Int,h:Int,radius:Int,cancelled:()->Boolean):FloatArray {
        val horizontal=FloatArray(input.size); val out=FloatArray(input.size); val divisor=radius*2+1
        for(y in 0 until h) {
            if(cancelled()) throw CancellationException("Detail filter cancelled")
            var sum=0.0; for(k in -radius..radius) sum+=input[y*w+k.coerceIn(0,w-1)]
            for(x in 0 until w) { horizontal[y*w+x]=(sum/divisor).toFloat(); sum-=input[y*w+(x-radius).coerceIn(0,w-1)]; sum+=input[y*w+(x+radius+1).coerceIn(0,w-1)] }
        }
        for(x in 0 until w) {
            if(x%32==0 && cancelled()) throw CancellationException("Detail filter cancelled")
            var sum=0.0; for(k in -radius..radius) sum+=horizontal[k.coerceIn(0,h-1)*w+x]
            for(y in 0 until h) { out[y*w+x]=(sum/divisor).toFloat(); sum-=horizontal[(y-radius).coerceIn(0,h-1)*w+x]; sum+=horizontal[(y+radius+1).coerceIn(0,h-1)*w+x] }
        }
        return out
    }
}
