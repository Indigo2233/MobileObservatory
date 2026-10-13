package com.indigo.mobileobservatory.stacking

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.*

/** Reproducible desktop diagnostics, never included in the Android APK. */
object StackingDiagnostics {
    @JvmStatic fun main(args:Array<String>) {
        val directory=File(args.firstOrNull() ?: "build/reports/stacking").apply { mkdirs() }
        val w=256;val h=256;val random=Random(2701)
        val input=LinearImage(w,h,3,FloatArray(w*h*3){ i ->
            val p=i/3;val x=p%w;val y=p/w;val nx=2.0*(x+.5)/w-1;val ny=2.0*(y+.5)/h-1
            val nebula=.012*exp(-((x-130.0).pow(2)+(y-121.0).pow(2))/(2*26.0*26.0))
            val color=doubleArrayOf(1.2,.75,.5)[i%3]
            (.1+.025*nx-.018*ny-.015*(nx*nx+ny*ny)+nebula*color+random.nextGaussian()*.0008).toFloat()
        })
        // Add unsaturated stars of known colors independently of the gradient model.
        for (s in 0 until 35) {
            val cx=8+random.nextInt(w-16);val cy=8+random.nextInt(h-16);val amplitude=.15+random.nextDouble()*.35
            for(y in cy-5..cy+5) for(x in cx-5..cx+5) for(c in 0..2)
                input.pixels[(y*w+x)*3+c]+=(amplitude*exp(-((x-cx).toDouble().pow(2)+(y-cy).toDouble().pow(2))/(2*1.3*1.3))).toFloat()
        }
        val background=BackgroundParameters(true,1.0,12,listOf(TargetMask(.25,.2,.8,.78)))
        val params=StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(blackPoint=.08,whitePoint=.7,background=background)
        val before=StackPostProcessor.process(input,params.copy(background=BackgroundParameters(enabled=false)))
        val after=StackPostProcessor.process(input,params)
        png(input,File(directory,"deep-sky-raw.png"))
        png(before.image,File(directory,"deep-sky-before.png"));png(after.image,File(directory,"deep-sky-after.png"))
        png(StackPostProcessor.process(input,StackProcessingParameters.preset(ProcessingPreset.NATURAL).copy(background=background)).image,File(directory,"deep-sky-natural.png"))
        png(after.image,File(directory,"deep-sky-intense.png"))
        val output=ArrayList<String>()
        for((width,height,channels) in listOf(Triple(1024,768,1),Triple(1024,768,3),Triple(2048,2048,1),Triple(1600,1200,3))) {
            System.gc()
            val runtime=Runtime.getRuntime();val peak=AtomicLong(runtime.totalMemory()-runtime.freeMemory());val running=AtomicBoolean(true)
            val sampler=Thread {
                while(running.get()) { peak.accumulateAndGet(runtime.totalMemory()-runtime.freeMemory(),::maxOf);Thread.sleep(5) }
            }.apply { isDaemon=true;start() }
            val engine=DeepSkyStacker(DeepSkyConfig(memoryBudgetBytes=256L*1024*1024))
            val times=ArrayList<Long>()
            val rng=Random(901)
            val stars=List(70) { Triple(12+rng.nextDouble()*(width-24),12+rng.nextDouble()*(height-24),.1+rng.nextDouble()*.4) }
            val totalStart=System.nanoTime()
            repeat(8) { f ->
                val data=FloatArray(width*height*channels) { (.03+rng.nextGaussian()*.002).toFloat() }
                for((cx,cy,amplitude) in stars) for(y in cy.toInt()-5..cy.toInt()+5) for(x in cx.toInt()-5..cx.toInt()+5) for(c in 0 until channels)
                    data[(y*width+x)*channels+c]+=(amplitude*exp(-((x-cx-f*.03).pow(2)+(y-cy+f*.02).pow(2))/(2*1.3*1.3))).toFloat()
                val result=engine.add(LinearImage(width,height,channels,data),3.0)
                check(result.accepted) { "Synthetic benchmark frame rejected: ${result.reason}" }
                times+=result.stats.elapsedMs
            }
            val snapshot=engine.snapshot()!!
            val processed=StackPostProcessor.process(snapshot.image,StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(sharpening=.3))
            val totalMs=(System.nanoTime()-totalStart)/1_000_000
            running.set(false);sampler.join()
            output+="""{"width":$width,"height":$height,"channels":$channels,"frames":8,"frameTimesMs":$times,"totalMsIncludingSyntheticGeneration":$totalMs,"postprocessingMs":${processed.elapsedMs},"sampledJvmPeakBytes":${peak.get()},"estimatedWorkingBytes":${engine.stats.estimatedWorkingBytes},"accepted":${engine.stats.accepted},"registrationRmsPx":${engine.stats.registrationRmsPx}}"""
        }
        val model=after.background
        val report="""
            {
              "environment": "Desktop JVM ${System.getProperty("java.version")}; ${System.getProperty("os.name")}; synthetic data; no Android device or native allocation measurement",
              "seed": 2701,
              "input": {"width":256,"height":256,"channels":3,"noiseSigma":0.0008,"nebulaPeak":0.012,"stars":35},
              "parameters": ${json(params)},
              "backgroundModel": {"applied":${model?.applied},"reason":"${model?.reason}","method":"${model?.method}","acceptedSamples":${model?.acceptedSamples},"coefficients":${model?.coefficients},"anchors":${model?.channelAnchors},"before":${statsJson(model?.before)},"after":${statsJson(model?.after)}},
              "benchmarks": [${output.joinToString(",\n")}],
              "limitations": ["No device timing/native peak measurements available", "Online temporal rejection warms up over 5 per-pixel samples and cannot guarantee removal of early/persistent defects", "Additive quadratic gradient; large nebulae require explicit target masks; no flat-field calibration"]
            }
        """.trimIndent()
        File(directory,"deep-sky-diagnostics.json").writeText(report)
        println(report)
        println("Wrote comparison PNGs and complete parameters to ${directory.absolutePath}")
    }

    private fun statsJson(s:BackgroundStatistics?):String = if(s==null) "null" else """{"median":${s.median},"gridSpread":${s.gridSpread},"noise":${s.noise}}"""
    private fun json(p:StackProcessingParameters):String = """{"preset":"${p.preset}","blackPoint":${p.blackPoint},"whitePoint":${p.whitePoint},"midtone":${p.midtone},"curve":${p.curve},"brightness":${p.brightness},"localContrast":${p.localContrast},"highlightCompression":${p.highlightCompression},"sharpening":${p.sharpening},"autoWhiteBalance":${p.autoWhiteBalance},"redBalance":${p.redBalance},"blueBalance":${p.blueBalance},"saturation":${p.saturation},"stretchEnabled":${p.stretchEnabled},"whiteBalanceEnabled":${p.whiteBalanceEnabled},"saturationEnabled":${p.saturationEnabled},"background":{"enabled":${p.background.enabled},"strength":${p.background.strength},"gridSize":${p.background.gridSize},"mode":"${p.background.mode}","sampleRadius":${p.background.sampleRadius},"samplePoints":[${p.background.samplePoints.joinToString(",") { """[${it.x},${it.y}]""" }}],"targetMasks":[${p.background.targetMasks.joinToString(",") { """{"left":${it.left},"top":${it.top},"right":${it.right},"bottom":${it.bottom}}""" } }]}}"""
    private fun png(image:LinearImage,file:File) {
        val compressed=ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { stream ->
            for(y in 0 until image.height) {
                stream.write(0)
                for(x in 0 until image.width) {
                    val p=y*image.width+x
                    for(c in 0..2) stream.write((image.pixels[p*image.channels+if(image.channels==1)0 else c].coerceIn(0f,1f)*255).roundToInt())
                }
            }
        }
        DataOutputStream(file.outputStream().buffered()).use { stream ->
            stream.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            fun chunk(name:String,data:ByteArray) {
                val type=name.toByteArray(Charsets.US_ASCII)
                stream.writeInt(data.size);stream.write(type);stream.write(data)
                val crc=CRC32().apply { update(type);update(data) };stream.writeInt(crc.value.toInt())
            }
            val header=ByteArrayOutputStream()
            DataOutputStream(header).use { it.writeInt(image.width);it.writeInt(image.height);it.write(byteArrayOf(8,2,0,0,0)) }
            chunk("IHDR",header.toByteArray());chunk("IDAT",compressed.toByteArray());chunk("IEND",ByteArray(0))
        }
    }
}
