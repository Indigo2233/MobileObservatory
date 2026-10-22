package com.indigo.mobileobservatory.stacking

import java.io.File
import java.util.Random
import kotlin.math.*

/** Desktop synthetic allocation diagnostics. Legacy reproduces the former owned-read plus
 * copying-snapshot call pattern with the same science engine. Neither arm measures Android
 * heap/RSS peaks, SER I/O or native decoder allocations. No full movie is cached. */
object DeepSkyBufferBenchmark {
    private open class Source(override val width: Int, override val height: Int,
                              override val frameCount: Long) : PlanetaryFrameSource {
        override val channels = 3
        private val random = Random(901)
        private val stars = List(70) { Triple(12 + random.nextDouble() * (width - 24),
            12 + random.nextDouble() * (height - 24), .1 + random.nextDouble() * .4) }
        override fun readFrame(index: Long) = fill(index, FloatArray(width * height * channels))
        override fun exposureSeconds(index: Long) = 3.0
        override fun close() = Unit
        protected fun fill(index: Long, destination: FloatArray): LinearImage {
            for (p in 0 until width * height) {
                val value = .03f + (((p * 1664525 + index.toInt() * 1013904223) ushr 16) and 1023) / 1023f * .002f
                destination[p * 3] = value; destination[p * 3 + 1] = value; destination[p * 3 + 2] = value
            }
            for ((cx, cy, amplitude) in stars) {
                val sx = cx + index * .03; val sy = cy - index * .02
                for (y in max(0, sy.toInt() - 6)..min(height - 1, sy.toInt() + 6))
                    for (x in max(0, sx.toInt() - 6)..min(width - 1, sx.toInt() + 6))
                        for (c in 0..2) destination[(y * width + x) * 3 + c] +=
                            (amplitude * (1 - c * .15) * exp(-((x - sx).pow(2) + (y - sy).pow(2)) / 3.38)).toFloat()
            }
            return LinearImage(width, height, channels, destination)
        }
    }

    private class BufferedSource(w: Int, h: Int, count: Long) : Source(w, h, count), BufferedPlanetaryFrameSource {
        override fun readFrameInto(index: Long, destination: FloatArray) = fill(index, destination)
        override fun readLuminanceInto(index: Long, destination: FloatArray): LinearImage = error("Not used")
    }

    @JvmStatic fun main(args: Array<String>) {
        val mode = args.getOrNull(0) ?: "buffered"
        require(mode in listOf("legacy", "owned", "buffered"))
        val width = args.getOrNull(1)?.toInt() ?: 1024
        val height = args.getOrNull(2)?.toInt() ?: 768
        val frames = args.getOrNull(3)?.toLong() ?: 6L
        val counter = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val counterType = Class.forName("com.sun.management.ThreadMXBean")
        require(counterType.getMethod("isThreadAllocatedMemorySupported").invoke(counter) == true)
        counterType.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(counter, true)
        val allocatedBytes = counterType.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val tid = Thread.currentThread().id
        fun allocated() = allocatedBytes.invoke(counter, tid) as Long
        fun run(w: Int, h: Int, count: Long): String {
            val source = if (mode == "buffered") BufferedSource(w, h, count) else Source(w, h, count)
            val runtime = Runtime.getRuntime()
            val config = DeepSkyConfig(memoryBudgetBytes = runtime.maxMemory() * 9 / 10)
            var sampledUsed = 0L
            fun sample() { sampledUsed = maxOf(sampledUsed, runtime.totalMemory() - runtime.freeMemory()) }
            val before = allocated(); val started = System.nanoTime()
            val result = if (mode == "legacy") {
                val engine = DeepSkyStacker(config, livePreviewOverlap = false)
                try {
                    for (i in 0 until count) {
                        check(engine.add(source.readFrame(i), 3.0).accepted)
                        sample()
                    }
                    engine.snapshot()!!
                } finally { engine.reset() }
            } else {
                val stacked = OfflineDeepSkyStacker().stack(source, config, progress = { _, _, _ -> sample() })
                DeepSkySnapshot(stacked.image, stacked.weights, stacked.stats.stack)
            }
            val bytes = allocated() - before
            val ms = (System.nanoTime() - started) / 1_000_000
            sample()
            check(result.stats.accepted.toLong() == count)
            return "mode=$mode ${w}x$h RGB frames=$count allocatedBytes=$bytes elapsedMs=$ms " +
                "sampledJvmUsedBytes=$sampledUsed outputBytes=${result.image.pixels.size * 4L} " +
                "pixelSum=${result.image.pixels.sumOf { it.toDouble() }} weightSum=${result.weights.sumOf { it.toDouble() }} " +
                "estimatedEngineBytes=${OfflineDeepSkyMemory.estimate(w, h, 3, mode == "buffered").peakBytes}"
        }
        run(192, 160, 3)
        val result = run(width, height, frames)
        println(result)
        File("build/reports/stacking/deep-sky-buffer-$mode-${width}x$height-$frames.txt").apply {
            parentFile?.mkdirs()
            writeText("Desktop synthetic cumulative allocations and phase-boundary heap samples, not Android peak memory.\n$result\n")
        }
    }
}
