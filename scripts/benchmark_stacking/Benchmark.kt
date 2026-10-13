package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.LockSupport
import kotlin.math.*

private const val W=1024
private const val H=768
private val allocated=ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
private val memory=ManagementFactory.getMemoryMXBean()
private val gc=ManagementFactory.getGarbageCollectorMXBeans()
@Volatile private var blackhole=0.0

private class Meter {
    private val subjects=allocated.allThreadIds.filter { id ->
        val name=allocated.getThreadInfo(id)?.threadName.orEmpty()
        name=="main" || name.startsWith("BenchScience")
    }.toLongArray()
    private fun bytes()=allocated.getThreadAllocatedBytes(subjects).filter { it>=0 }.sum()
    private val beforeBytes=bytes()
    private val beforeGcCount=gc.sumOf { it.collectionCount.coerceAtLeast(0) }
    private val beforeGcMs=gc.sumOf { it.collectionTime.coerceAtLeast(0) }
    private val running=AtomicBoolean(true)
    private val peak=AtomicLong(memory.heapMemoryUsage.used)
    private val started=System.nanoTime()
    private val sampler=Thread({
        while(running.get()) {
            peak.accumulateAndGet(memory.heapMemoryUsage.used,Math::max)
            LockSupport.parkNanos(2_000_000)
        }
    },"BenchHeapSampler").also { it.isDaemon=true;it.start() }
    fun finish(samples:List<Double>,operations:Int):JSONObject {
        val elapsed=(System.nanoTime()-started)/1e9
        val allocatedBytes=bytes()-beforeBytes
        running.set(false);sampler.join()
        peak.accumulateAndGet(memory.heapMemoryUsage.used,Math::max)
        val sorted=samples.sorted()
        fun percentile(q:Double)=if(sorted.isEmpty()) JSONObject.NULL else sorted[(ceil(sorted.size*q).toInt()-1).coerceIn(sorted.indices)]
        return JSONObject().apply {
            put("operations",operations);put("wallSeconds",elapsed)
            put("frameMsMean",if(samples.isEmpty()) JSONObject.NULL else samples.average())
            put("frameMsP50",percentile(.5));put("frameMsP95",percentile(.95));put("timingSamples",samples.size)
            put("allocatedBytes",allocatedBytes);put("allocatedBytesPerFrame",allocatedBytes.toDouble()/operations)
            put("allocatedBytesPerSecond",allocatedBytes/elapsed);put("operationsPerSecond",operations/elapsed)
            put("gcCollections",gc.sumOf { it.collectionCount.coerceAtLeast(0) }-beforeGcCount)
            put("gcCollectionTimeMs",gc.sumOf { it.collectionTime.coerceAtLeast(0) }-beforeGcMs)
            put("heapPeakSampledBytes",peak.get());put("heapSampleIntervalMs",2)
            put("queueDropRate",JSONObject.NULL);put("queueDropped",JSONObject.NULL)
        }
    }
}

private fun cool() { System.gc(); Thread.sleep(120) }
private fun micro(warmup:Int,count:Int,call:()->Double):JSONObject {
    repeat(warmup) { blackhole=call() }
    cool()
    val meter=Meter();val times=ArrayList<Double>(count)
    repeat(count) {
        val start=System.nanoTime();blackhole=call();times+=(System.nanoTime()-start)/1e6
    }
    return meter.finish(times,count).put("warmupOperations",warmup).put("checksum",blackhole)
}

private fun target():LinearImage {
    val p=FloatArray(W*H*3)
    for(y in 0 until H) for(x in 0 until W) {
        val radius=hypot((x-W*.5)/(W*.38),(y-H*.5)/(H*.37))
        val value=if(radius<1) .25+.07*sin(x*.17)*cos(y*.13)+.05*cos((x+y)*.29) else .015+.005*x/W
        val i=(y*W+x)*3;p[i]=(value*1.13).toFloat();p[i+1]=value.toFloat();p[i+2]=(value*.82).toFloat()
    }
    return LinearImage(W,H,3,p)
}

private fun stars():LinearImage {
    val p=FloatArray(W*H*3)
    for(y in 0 until H) for(x in 0 until W) {
        val v=.018+.015*x/W+.008*y/H
        val i=(y*W+x)*3;p[i]=(v*1.1).toFloat();p[i+1]=v.toFloat();p[i+2]=(v*.85).toFloat()
    }
    val random=java.util.Random(20261013)
    repeat(36) {
        val sx=25+random.nextInt(W-50);val sy=25+random.nextInt(H-50)
        val amplitude=.16+random.nextDouble()*.20
        for(y in sy-8..sy+8) for(x in sx-8..sx+8) {
            val v=amplitude*exp(-((x-sx)*(x-sx)+(y-sy)*(y-sy))/(2*1.6*1.6))
            for(c in 0..2) p[(y*W+x)*3+c]+=v.toFloat()
        }
    }
    return LinearImage(W,H,3,p)
}

private fun key(format:PixelFormat)=StackSessionKey("Synthetic JVM",W,H,format,Roi(0,0,W,H),1,1,100_000.0,10f,"L","raw")
private fun mosaic(image:LinearImage):FrameData {
    val data=ByteArray(W*H*2)
    for(y in 0 until H) for(x in 0 until W) {
        val c=if(y%2==0&&x%2==0)0 else if(y%2==1&&x%2==1)2 else 1
        val i=y*W+x;val v=(image.pixels[i*3+c]*65535).roundToInt()
        data[i*2]=v.toByte();data[i*2+1]=(v ushr 8).toByte()
    }
    return FrameData(data,W,H,PixelFormat.BAYER_RG16,0,0)
}

private fun rejectedSnapshots():JSONObject {
    val image=stars();val blank=LinearImage(W,H,3,FloatArray(W*H*3))
    val raw=FrameData(ByteArray(W*H*6),W,H,PixelFormat.RGB48,0,0)
    val dispatcher=Executors.newSingleThreadExecutor { Thread(it,"BenchScienceReject") }.asCoroutineDispatcher()
    val scope=CoroutineScope(SupervisorJob()+dispatcher)
    val delivered=AtomicReference(CountDownLatch(1));val clock=AtomicLong();var id=0L
    val session=LiveStackSession(scope,{LiveDeepSkyEngine(256L*1024*1024)},
        { frame,_->if(frame.frameId==0L)image else blank },{ if(it!=null) delivered.get().countDown() },
        nowNanos={clock.addAndGet(1_100_000_000L)})
    val k=key(raw.pixelFormat)
    fun offer():Double {
        val signal=CountDownLatch(1);delivered.set(signal)
        session.offer(raw.copy(frameId=id++),k)
        check(signal.await(30,TimeUnit.SECONDS)) { "Snapshot timed out: ${session.state.value}" }
        return session.latestSnapshot!!.image.pixels[0].toDouble()
    }
    try {
        session.start(k);offer();check(session.state.value.accepted==1L) { session.state.value.toString() }
        return micro(10,40,::offer).put("acceptedSetupFrames",1).put("rejectedFramesTotal",session.state.value.rejected)
            .put("note","Real LiveStackSession + LiveDeepSkyEngine; fixed decoded RGB input; forced publish eligibility each rejected frame; wall time, not synthetic clock, is measured")
    } finally { session.close();runBlocking { session.awaitClosed() };scope.cancel();dispatcher.close() }
}

private fun queue30():JSONObject {
    val raw=mosaic(stars());val k=key(raw.pixelFormat);val decode=decoderOperation()
    val dispatcher=Executors.newSingleThreadExecutor { Thread(it,"BenchScienceQueue") }.asCoroutineDispatcher()
    val scope=CoroutineScope(SupervisorJob()+dispatcher)
    val session=LiveStackSession(scope,{LiveDeepSkyEngine(256L*1024*1024)},
        { frame,_->decode(frame) },{if(it!=null)blackhole=it.image.pixels[0].toDouble()})
    val times=java.util.Collections.synchronizedList(mutableListOf<Double>())
    var lastCompleted=0L
    val observer=CoroutineScope(Dispatchers.Default).launch {
        session.state.collect { s ->
            val completed=s.accepted+s.rejected
            if(completed>lastCompleted) { lastCompleted=completed;times+=s.processingMs }
        }
    }
    try {
        session.start(k);session.offer(raw,k)
        val warmDeadline=System.nanoTime()+30_000_000_000L
        while(session.state.value.accepted+session.state.value.rejected<1) {
            check(System.nanoTime()<warmDeadline);Thread.sleep(1)
        }
        check(session.state.value.accepted==1L) { session.state.value.toString() }
        Thread.sleep(10);times.clear();cool()
        val before=session.state.value;val meter=Meter();val start=System.nanoTime()
        val offered=150
        for(i in 1..offered) {
            val at=start+(i-1)*1_000_000_000L/30
            while(System.nanoTime()<at) LockSupport.parkNanos((at-System.nanoTime()).coerceAtMost(1_000_000))
            session.offer(raw.copy(frameId=i.toLong()),k)
        }
        while(System.nanoTime()<start+5_000_000_000L) LockSupport.parkNanos(1_000_000)
        val drainDeadline=System.nanoTime()+60_000_000_000L
        while(session.state.value.let { it.accepted+it.rejected+it.dropped<it.received }) {
            check(System.nanoTime()<drainDeadline) { "Queue did not finish: ${session.state.value}" };Thread.sleep(1)
        }
        session.pause()
        while(session.state.value.phase!=LiveStackPhase.PAUSED) Thread.sleep(1)
        Thread.sleep(10)
        val after=session.state.value;val dropped=after.dropped-before.dropped
        return meter.finish(synchronized(times){times.toList()},offered)
            .put("queueDropRate",dropped.toDouble()/offered).put("queueDropped",dropped)
            .put("accepted",after.accepted-before.accepted).put("rejected",after.rejected-before.rejected)
            .put("producerFps",30).put("producerDurationSeconds",5).put("queueCapacity",2)
            .put("note","Real LiveStackSession, raw decode+DDFAPD+LiveDeepSkyEngine; 150 offered frames, 30 FPS. Processing P95 from observed state events; allocation throughput includes final drain; no Android UI, USB, camera or Bitmap")
    } finally { observer.cancel();session.close();runBlocking { session.awaitClosed() };scope.cancel();dispatcher.close() }
}

fun main(args:Array<String>) {
    val variant=args[0];val case=args[1];val output=File(args[2]);allocated.isThreadAllocatedMemoryEnabled=true
    val result=when(case) {
        "normalizer" -> {
            val original=ByteArray(W*H*2) { i->if(i%2==0)0x10 else ((i/2)%256).toByte() }
            val owned=FrameData(ByteArray(original.size),W,H,PixelFormat.MONO16,1,0)
            val traits=PixelTraits(alignment=SampleAlignment.RIGHT)
            micro(30,300) {
                System.arraycopy(original,0,owned.data,0,original.size)
                val converted=normalizeOperation(owned,traits)
                (converted.data[0].toInt() and 255)+(converted.data.last().toInt() and 255).toDouble()
            }.put("note","Both paths include the same camera-to-owned-buffer copy; baseline then allocates normalization output, current normalizes the owned copy in place")
        }
        "ddfapd" -> {
            val image=target();val src=FloatArray(W*H) { p->image.pixels[p*3+(if(p/W%2==0&&p%W%2==0)0 else if(p/W%2==1&&p%W%2==1)2 else 1)] }
            val operation=demosaicOperation(src,W,H)
            micro(20,40) { val out=operation();out.pixels[101].toDouble()+out.pixels[out.pixels.size-100] }
        }
        "planetary" -> {
            val image=target();val shifted=image.copy(pixels=image.pixels.copyOf())
            val options=PlanetaryOptions(maxAlignmentPoints=24,maxGlobalShift=8,maxLocalShift=3)
            val registration=PlanetaryRegistration(image,PixelRoi(64,64,W-128,H-128),options)
            micro(12,40) { registrationOperation(registration,shifted) }
                .put("alignmentPoints",registration.points.size).put("note","Actual align plus warp plus edgeResidual, 24 max local points, identical textured RGB target; only current shares prepared luminance")
        }
        "post_default" -> {
            val image=stars();val p=StackProcessingParameters.preset(ProcessingPreset.NATURAL)
            micro(12,40) { val result=StackPostProcessor.process(image,p);result.image.pixels[123].toDouble() }
                .put("note","Public process API materializes RGB output on both versions; default NATURAL with automatic background correction")
        }
        "post_preview_cached" -> {
            val image=stars();val operation=previewOperation(image)
            micro(12,40,operation).put("note","Baseline process materializes RGB; current prepare+reused background model+writePreviewRow LUT; row checksum substitutes 8-bit Bitmap upload. Native Bitmap allocation/upload excluded")
        }
        "rejected_snapshot" -> rejectedSnapshots()
        "queue_30fps" -> queue30()
        else -> error("Unknown benchmark $case")
    }
    result.put("capturedAt",java.time.Instant.now().toString()).put("variant",variant).put("case",case).put("width",W).put("height",H)
        .put("javaVersion",System.getProperty("java.version")).put("vm",System.getProperty("java.vm.name"))
        .put("os",System.getProperty("os.name")).put("heapMaxBytes",Runtime.getRuntime().maxMemory())
        .put("gcCollectorNames",gc.joinToString { it.name }).put("blackhole",blackhole)
    output.parentFile.mkdirs();output.writeText(result.toString(2));println(result.toString())
}
