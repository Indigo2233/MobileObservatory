package com.indigo.mobileobservatory.simulation

import android.content.Context
import com.indigo.mobileobservatory.astrometry.*
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.recording.FITSWriter
import kotlinx.coroutines.*
import java.io.File

/** Image solutions are captured with each simulated exposure, never inferred from a later mount position. */
class VirtualObservatorySession(context: Context) : ImagePlateSolver {
    val model = VirtualObservatory()
    // AAPT expands .gz assets and drops the suffix when packaging the APK.
    private val sky = context.assets.open("simulation/imx585-field.raw8").use { it.readBytes() }.also { raw ->
        require(raw.size == VirtualObservatory.WIDTH * VirtualObservatory.HEIGHT)
        // Collapse each CFA cell to luminance before subpixel translation.
        for (y in 0 until VirtualObservatory.HEIGHT step 2) for (x in 0 until VirtualObservatory.WIDTH step 2) {
            val i = y * VirtualObservatory.WIDTH + x
            val mean = listOf(i, i+1, i+VirtualObservatory.WIDTH, i+VirtualObservatory.WIDTH+1).sumOf { raw[it].toInt() and 255 } / 4
            raw[i] = mean.toByte(); raw[i+1] = mean.toByte(); raw[i+VirtualObservatory.WIDTH] = mean.toByte(); raw[i+VirtualObservatory.WIDTH+1] = mean.toByte()
        }
    }
    val primary = VirtualCamera(model, sky, "Virtual main / 模拟主相机")
    val guide = VirtualCamera(model, sky, "Virtual guide / 模拟导星相机")
    val mount = VirtualMountSession(model)
    private val directory = File(context.cacheDir, "virtual-observatory/${java.util.UUID.randomUUID()}").apply { mkdirs() }
    private val solutions = linkedMapOf<String, PlateSolveResult>()
    @Volatile private var active = true
    override val ready get() = active

    suspend fun capture(exposureUs: Float, gain: Float? = null): File = withContext(Dispatchers.Default) {
        check(active && primary.isOpen.value) { "Virtual observation ended" }
        primary.stopCapture()
        primary.setExposureTime(exposureUs)
        gain?.let(primary::setGain)
        val half = (primary.currentExposureUs / 2000).toLong()
        delay(half)
        val snapshot = model.snapshot()
        val pixels = primary.render(snapshot)
        delay(half)
        currentCoroutineContext().ensureActive()
        check(active) { "Virtual observation ended" }
        val file = File(directory, "SIMULATION-${java.util.UUID.randomUUID()}.fits")
        FITSWriter().write(file, FrameData(pixels, VirtualObservatory.WIDTH, VirtualObservatory.HEIGHT, PixelFormat.MONO8, 0,
            snapshot.at.toEpochMilli() + half), primary.currentExposureUs / 1_000_000, primary.currentGain,
            cameraName = "SIMULATION ONLY", pixelSizeUm = VirtualObservatory.PIXEL_UM, focalLengthMm = VirtualObservatory.FOCAL_MM,
            exposureStartedAt = snapshot.at.minusMillis(half), imageType = "SIMULATION")
        val scale = VirtualObservatory.SCALE_ARCSEC / 3600
        val header = mapOf("CTYPE1" to "RA---TAN", "CTYPE2" to "DEC--TAN", "CRVAL1" to snapshot.center.raDeg.toString(),
            "CRVAL2" to snapshot.center.decDeg.toString(), "CRPIX1" to "512.5", "CRPIX2" to "384.5",
            "CD1_1" to scale.toString(), "CD1_2" to "0", "CD2_1" to "0", "CD2_2" to (-scale).toString())
        val result = PlateSolveResult(true, "模拟解析 / Synthetic solution", snapshot.center.raDeg, snapshot.center.decDeg,
            fovWidthDeg = scale * VirtualObservatory.WIDTH, fovHeightDeg = scale * VirtualObservatory.HEIGHT,
            rotationDeg = 0.0, arcsecPerPixel = VirtualObservatory.SCALE_ARCSEC,
            imageWcs = requireNotNull(SolvedImageWcs.parse(header, VirtualObservatory.WIDTH, VirtualObservatory.HEIGHT)),
            log = "SIMULATION: known camera pose. ASTAP was not executed.")
        synchronized(solutions) {
            if (!active) { file.delete(); directory.delete(); error("Virtual observation ended") }
            solutions[file.absolutePath] = result
            while (solutions.size > 32) { val oldest = solutions.keys.first(); solutions.remove(oldest); File(oldest).delete() }
        }
        file
    }
    override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?): PlateSolveResult {
        delay(200)
        check(active) { "Virtual observation ended" }
        if (model.consumeSolveFailure()) return PlateSolveResult(false, "模拟解析失败：星点不足 / Simulated insufficient stars")
        return synchronized(solutions) { solutions[inputFile.absolutePath] }
            ?: PlateSolveResult(false, "This image is outside the active virtual observation session")
    }
    fun close() {
        active = false; primary.close(); guide.close(); model.stop()
        synchronized(solutions) {
            solutions.keys.forEach { File(it).delete() }
            solutions.clear()
            directory.delete()
        }
    }
}
