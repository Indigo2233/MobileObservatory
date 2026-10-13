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
data class ProcessedStack(val image: LinearImage, val parameters: StackProcessingParameters, val background: BackgroundModel?, val whiteBalanceGains: List<Double>, val elapsedMs: Long)

/** Recomputed from the original every time. Same image and parameters give bit-identical preview/export. */
object StackPostProcessor {
    fun process(linear: LinearImage, parameters: StackProcessingParameters, cancelled: () -> Boolean = { false }): ProcessedStack {
        val start = System.nanoTime()
        require(parameters.whitePoint > parameters.blackPoint && parameters.whitePoint.isFinite() && parameters.blackPoint.isFinite())
        require(parameters.midtone in .01.. .99 && parameters.curve in 0.0..200.0 && parameters.brightness in .05..8.0)
        require(parameters.sharpening in 0.0..2.0 && parameters.localContrast in 0.0..1.0 && parameters.highlightCompression in 0.0..1.0)
        require(parameters.redBalance in .25..4.0 && parameters.blueBalance in .25..4.0 && parameters.saturation in 0.0..2.0)
        if (cancelled()) throw CancellationException("Processing cancelled")
        if (parameters.preset == ProcessingPreset.RAW) return ProcessedStack(linear,parameters,null,listOf(1.0,1.0,1.0),(System.nanoTime()-start)/1_000_000)
        val bg = if (parameters.background.enabled) BackgroundGradient.correct(linear,parameters.background,cancelled) else null
        val image = bg?.image ?: linear
        val count = image.width * image.height
        val gains = whiteBalance(image,parameters)
        val saturation = if (parameters.saturationEnabled) parameters.saturation else 1.0
        val luma = FloatArray(count)
        val output = FloatArray(image.pixels.size)
        for (p in 0 until count) {
            if (p % 32768 == 0 && cancelled()) throw CancellationException("Stretch cancelled")
            var lum = 0.0
            if (image.channels == 1) lum = image.pixels[p].toDouble()
            else lum = image.pixels[p*3] * gains[0] * .2126 + image.pixels[p*3+1] * gains[1] * .7152 + image.pixels[p*3+2] * gains[2] * .0722
            luma[p] = lum.toFloat()
        }
        // Separable bounded-radius blur is only allocated when requested. Detail threshold tracks
        // high frequency noise, and range clamping prevents overshoot at planetary/stellar edges.
        val detailBlur = if (parameters.sharpening > 0) blur(luma,image.width,image.height,1,cancelled) else null
        val contrastBlur = if (parameters.localContrast > 0) blur(luma,image.width,image.height,max(2,min(image.width,image.height)/80).coerceAtMost(16),cancelled) else null
        val noiseSamples = ArrayList<Double>()
        val stride = max(1,count/12000)
        for (p in 0 until count - 1 step stride) if (p % image.width != image.width-1) noiseSamples += abs(luma[p+1]-luma[p]).toDouble()
        val noise = StarRegistration.median(noiseSamples)/.9538726
        val range = parameters.whitePoint - parameters.blackPoint
        val curveNorm = if (parameters.curve > 0) asinh(parameters.curve) else 1.0
        for (y in 0 until image.height) {
            if (cancelled()) throw CancellationException("Postprocessing cancelled")
            for (x in 0 until image.width) {
                val p = y*image.width+x; val original = luma[p].toDouble()
                var value = original
                if (detailBlur != null) {
                    val detail = value - detailBlur[p]
                    val gated = sign(detail)*max(0.0,abs(detail)-noise*1.5)
                    var lo = original; var hi = original
                    for (oy in -1..1) for (ox in -1..1) {
                        val neighbor=luma[(y+oy).coerceIn(0,image.height-1)*image.width+(x+ox).coerceIn(0,image.width-1)].toDouble()
                        lo=min(lo,neighbor); hi=max(hi,neighbor)
                    }
                    value=(value+parameters.sharpening*gated).coerceIn(lo,hi)
                }
                if (contrastBlur != null) {
                    val detail=value-contrastBlur[p]
                    value+=sign(detail)*max(0.0,abs(detail)-noise*1.5)*parameters.localContrast/(1+8*max(value,0.0))
                }
                var mapped=value.coerceIn(0.0,1.0)
                if (parameters.stretchEnabled) {
                    mapped=((value-parameters.blackPoint)/range).coerceIn(0.0,1.0)
                    mapped=if(parameters.curve>0) asinh(parameters.curve*mapped)/curveNorm else mapped
                    val m=parameters.midtone
                    mapped=if(mapped<=0 || mapped>=1) mapped else ((m-1)*mapped)/((2*m-1)*mapped-m)
                    // Exposure uses a compressive shoulder instead of a hard gain-and-clip.
                    mapped=1.0-(1.0-mapped).pow(parameters.brightness)
                    val shoulder=parameters.highlightCompression
                    mapped=(1-shoulder)*mapped+shoulder*(1-exp(-2*mapped))/(1-exp(-2.0))
                }
                mapped=mapped.coerceIn(0.0,1.0)
                if (image.channels == 1) output[p]=mapped.toFloat()
                else {
                    val colorScale=if(original>1e-10) mapped/original else 0.0
                    // Gamut compression toward equal-luminance neutral protects hue without clipping
                    // one channel first; chroma fades smoothly as luminance approaches white.
                    var chroma=1.0
                    for(c in 0..2) {
                        val delta=(image.pixels[p*3+c]*gains[c]*colorScale-mapped)*saturation
                        if(delta>0) chroma=min(chroma,(1-mapped)/delta)
                        if(delta<0) chroma=min(chroma,-mapped/delta)
                    }
                    for(c in 0..2) output[p*3+c]=(mapped+(image.pixels[p*3+c]*gains[c]*colorScale-mapped)*saturation*chroma.coerceIn(0.0,1.0)).coerceIn(0.0,1.0).toFloat()
                }
            }
        }
        return ProcessedStack(LinearImage(image.width,image.height,image.channels,output),parameters,bg?.model,gains.toList(),(System.nanoTime()-start)/1_000_000)
    }

    private fun whiteBalance(image:LinearImage,p:StackProcessingParameters):DoubleArray {
        if (!p.whiteBalanceEnabled) return doubleArrayOf(1.0,1.0,1.0)
        val gains=doubleArrayOf(p.redBalance,1.0,p.blueBalance)
        if(!p.autoWhiteBalance || image.channels!=3) return gains
        val ratiosR=ArrayList<Double>(); val ratiosB=ArrayList<Double>()
        val count=image.width*image.height; val stride=max(1,count/20000)
        val levels=ArrayList<Double>(); for(i in 0 until count step stride) levels+=StarRegistration.luminance(image,i)
        levels.sort(); val low=levels[(levels.size*.30).toInt()]; val high=levels[(levels.size*.95).toInt()]
        for(i in 0 until count step stride) {
            val r=image.pixels[i*3].toDouble(); val g=image.pixels[i*3+1].toDouble(); val b=image.pixels[i*3+2].toDouble(); val l=StarRegistration.luminance(image,i)
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
