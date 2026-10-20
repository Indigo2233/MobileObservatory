package com.indigo.mobileobservatory.stacking

import java.io.File
import java.nio.file.Files
import kotlin.math.*

/** Optional desktop allocation benchmark; no movie is cached. This measures allocation volume
 * on HotSpot and phase-boundary heap samples, never Android peak heap/RSS or real SER I/O. */
object PlanetaryBufferBenchmark {
    private open class Source(override val width: Int, override val height: Int,
                              override val frameCount: Long) : PlanetaryFrameSource {
        override val channels = 3
        override val boundedDecode = true
        override fun readFrame(index: Long) = fill(FloatArray(width * height * 3), false)
        override fun exposureSeconds(index: Long) = .01
        override fun close() = Unit
        protected fun fill(destination: FloatArray, gray: Boolean): LinearImage {
            for (y in 0 until height) for (x in 0 until width) {
                val dx = (x - width / 2.0) / (width * .38)
                val dy = (y - height / 2.0) / (height * .38)
                val limb = .5 * (1 - tanh((hypot(dx, dy) - 1) * 30))
                val r = (.01 + limb * (.4 + .06 * sin(y * .12) + .04 * cos(x * .09 + y * .06))).toFloat()
                val g = r * .88f; val b = r * .76f
                val p = y * width + x
                if (gray) destination[p] = r * .2126f + g * .7152f + b * .0722f
                else { destination[p * 3] = r; destination[p * 3 + 1] = g; destination[p * 3 + 2] = b }
            }
            return LinearImage(width, height, if (gray) 1 else 3, destination)
        }
    }

    private class BufferedSource(width: Int, height: Int, count: Long) : Source(width, height, count), BufferedPlanetaryFrameSource {
        override val boundedDecode = true
        override fun readFrameInto(index: Long, destination: FloatArray) = fill(destination, false)
        override fun readLuminanceInto(index: Long, destination: FloatArray) = fill(destination, true)
    }

    @JvmStatic fun main(args: Array<String>) {
        val mode = args.getOrNull(0) ?: "buffered"
        require(mode in listOf("owned", "buffered"))
        val width = args.getOrNull(1)?.toInt() ?: 1024
        val height = args.getOrNull(2)?.toInt() ?: 768
        val frames = args.getOrNull(3)?.toLong() ?: 6L
        // Reflection keeps host-only management APIs out of Android's test compile classpath.
        val counter = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val counterType = Class.forName("com.sun.management.ThreadMXBean")
        require(counterType.getMethod("isThreadAllocatedMemorySupported").invoke(counter) == true)
        counterType.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(counter, true)
        val allocatedBytes = counterType.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        fun run(w: Int, h: Int, count: Long): String {
            val source = if (mode == "buffered") BufferedSource(w, h, count) else Source(w, h, count)
            val directory = Files.createTempDirectory("planetary-allocation-bench-").toFile()
            val tid = Thread.currentThread().id
            val runtime = Runtime.getRuntime()
            var sampledUsed = 0L
            val control = PlanetaryControl(progress = {
                sampledUsed = maxOf(sampledUsed, runtime.totalMemory() - runtime.freeMemory())
            })
            try {
                val stacker = PlanetaryStacker()
                val before = allocatedBytes.invoke(counter, tid) as Long
                stacker.analyze(source, PlanetaryOptions(localAlignment = false,
                    memoryBudgetBytes = runtime.maxMemory() * 9 / 10), directory, control).use { analysis ->
                    val analyzeAllocated = (allocatedBytes.invoke(counter, tid) as Long) - before
                    val stackBefore = allocatedBytes.invoke(counter, tid) as Long
                    val result = stacker.stack(source, analysis, PlanetarySelection(100.0), control)
                    val stackAllocated = (allocatedBytes.invoke(counter, tid) as Long) - stackBefore
                    check(result.stats.stackedFrames == count)
                    return "mode=$mode ${w}x$h RGB frames=$count analyzeAllocatedBytes=$analyzeAllocated " +
                        "stackAllocatedBytes=$stackAllocated sampledJvmUsedBytes=$sampledUsed " +
                        "estimatedEngineBytes=${analysis.estimatedPeakBytes} outputBytes=${result.image.pixels.size * 4L} " +
                        "analyzeMs=${analysis.elapsedMillis} stackMs=${result.stats.elapsedMillis}"
                }
            } finally { source.close(); directory.deleteRecursively() }
        }
        run(160, 128, 3) // Warm up class loading and the hot path outside the measured run.
        val result = run(width, height, frames)
        println(result)
        val report = File("build/reports/stacking/planetary-buffer-$mode-${width}x$height-$frames.txt")
        report.parentFile?.mkdirs()
        report.writeText("Desktop synthetic allocation volume and phase-boundary heap samples; not live/peak Android memory.\n$result\n")
    }
}
