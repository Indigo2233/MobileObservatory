package com.indigo.mobileobservatory.stacking

import java.io.File
import java.nio.file.Files
import kotlin.math.*

/** Optional desktop benchmark; deterministic synthetic source generates one frame at a time, never a movie array. */
object PlanetaryStreamingBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val runs = listOf(Triple(640, 480, 12L), Triple(2048, 2048, 2L))
        val records = arrayListOf<String>()
        for ((width, height, frames) in runs) {
            val source = object : PlanetaryFrameSource {
                override val width = width
                override val height = height
                override val channels = 1
                override val frameCount = frames
                override fun readFrame(index: Long): LinearImage {
                    val scale = min(width, height) / 128.0
                    val shiftX = (index % 3 - 1) * 1.31
                    val shiftY = (index % 2) * .73
                    return LinearImage(width, height, 1, FloatArray(width * height) { p ->
                        val x = (p % width - width / 2.0 - shiftX) / scale
                        val y = (p / width - height / 2.0 - shiftY) / scale
                        val r = hypot(x, y)
                        val limb = (1 - tanh((r - 43) / .8)) * .5
                        (.012 + limb * (.4 + .055 * sin(y * .47) + .025 * cos(x * .53 + y * .3))).toFloat()
                    })
                }
                override fun exposureSeconds(index: Long) = .01
                override fun close() = Unit
            }
            val directory = Files.createTempDirectory("planetary-bench-").toFile()
            var heapPeak = 0L
            val runtime = Runtime.getRuntime()
            val control = PlanetaryControl(progress = { heapPeak = max(heapPeak, runtime.totalMemory() - runtime.freeMemory()) })
            try {
                val stacker = PlanetaryStacker()
                stacker.analyze(source, PlanetaryOptions(maxAlignmentPoints = 64), directory, control).use { analysis ->
                    val result = stacker.stack(source, analysis, PlanetarySelection(percent = 100.0), control)
                    check(result.stats.stackedFrames == frames)
                    val record = "${width}x$height mono frames=$frames analyzeMs=${analysis.elapsedMillis} stackMs=${result.stats.elapsedMillis} " +
                        "observedJvmHeapPeakBytes=$heapPeak estimatedWorkspaceBytes=${analysis.estimatedPeakBytes} " +
                        "qualityDiskBytes=${analysis.indexFile.length()} workers=${result.stats.cpuWorkers} modes=${result.stats.modeCounts}"
                    println(record); records += record
                }
            } finally { directory.deleteRecursively() }
        }
        val report = File("build/reports/stacking/planetary-benchmark.txt")
        report.parentFile.mkdirs()
        report.writeText("Desktop synthetic benchmark. Java=${System.getProperty("java.version")} OS=${System.getProperty("os.name")} ${System.getProperty("os.arch")}\n" +
            "JVM heap sampled at phase boundaries; not true allocation/native peak. Engine creates no native buffers. Android device performance is unverified.\n" + records.joinToString("\n") + "\n")
    }
}
