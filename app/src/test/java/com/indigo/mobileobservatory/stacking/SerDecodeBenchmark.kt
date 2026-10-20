package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Optional desktop measurement of the SER decode path: cumulative bytes allocated on HotSpot and
 * the native row reads issued per frame. These are host counters, never Android peak heap or RSS.
 */
object SerDecodeBenchmark {
    private const val WIDTH = 3856
    private const val HEIGHT = 2180
    private const val FRAMES = 3

    private fun recording(format: PixelFormat): File {
        val file = File.createTempFile("ser-decode-bench", ".ser")
        val channels = if (format.isRgb) 3 else 1
        val max = (1 shl format.nativeBits) - 1
        SERWriter(file).apply {
            open(WIDTH, HEIGHT, format)
            repeat(FRAMES) { index ->
                val bytes = ByteBuffer.allocate(WIDTH * HEIGHT * format.bytesPerPixel).order(ByteOrder.LITTLE_ENDIAN)
                for (y in 0 until HEIGHT) for (x in 0 until WIDTH) for (c in 0 until channels) {
                    val value = ((.35 + .1 * sin((x + index * 7) * .013) + .05 * sin(y * .021)) * max).toInt()
                    if (format.bytesPerPixel == 2) bytes.putShort(value.toShort()) else bytes.put(value.toByte())
                }
                writeFrame(FrameData(bytes.array(), WIDTH, HEIGHT, format, (index + 1).toLong(), 0))
            }
            close()
        }
        return file
    }

    @JvmStatic fun main(args: Array<String>) {
        val format = if (args.firstOrNull() == "bayer") PixelFormat.BAYER_RG16 else PixelFormat.MONO16
        val file = recording(format)
        try {
            SERReader(file).use { reader ->
                reader.sampleAlignment = SampleAlignment.RIGHT
                check(reader.open())
                val plan = OfflineRegionPlan(WIDTH, HEIGHT, format, PixelRoi(0, 0, WIDTH, HEIGHT),
                    DemosaicAlgorithm.DDFAPD, StackTarget.PLANETARY)
                val geometry = CalibrationGeometry.from(WIDTH, HEIGHT, format, reader.sensorTraits)
                val destination = FloatArray(plan.width * plan.height * plan.channels)
                val workspace = SerRegionDecoder.Workspace()
                try {
                    SerRegionDecoder.readInto(reader, 0, plan, CalibrationSelection(), geometry,
                        CalibrationCapture(), workspace, destination) {}
                    val reads = workspace.rowReads
                    val allocations = workspace.arrayAllocations
                    val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
                    val type = Class.forName("com.sun.management.ThreadMXBean")
                    type.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
                    val allocated = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                    val id = Thread.currentThread().id
                    val before = allocated.invoke(bean, id) as Long
                    val started = System.nanoTime()
                    for (index in 0 until FRAMES)
                        SerRegionDecoder.readInto(reader, index, plan, CalibrationSelection(), geometry,
                            CalibrationCapture(), workspace, destination) {}
                    val elapsed = (System.nanoTime() - started) / 1000000
                    val total = (allocated.invoke(bean, id) as Long) - before
                    val tiles = ((plan.width + OfflineRegionPlan.TILE - 1) / OfflineRegionPlan.TILE) *
                        ((plan.height + OfflineRegionPlan.TILE - 1) / OfflineRegionPlan.TILE)
                    val tileSide = OfflineRegionPlan.TILE + 2 * 16
                    println("SerDecodeBenchmark ${plan.width}x${plan.height} $format frames=$FRAMES " +
                        "rowReadsPerFrame=$reads tiledReadsPerFrame=${tiles.toLong() * tileSide} " +
                        "allocatedBytesPerFrame=${total / FRAMES} decodeMs=$elapsed " +
                        "workspaceAllocations=$allocations bandBytes=${workspace.bandBytes} " +
                        "engineBytes=${plan.workingBytes}")
                } finally { workspace.release() }
            }
        } finally { file.delete() }
    }
}
