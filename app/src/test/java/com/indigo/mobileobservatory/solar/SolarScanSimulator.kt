package com.indigo.mobileobservatory.solar

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.SERWriter
import java.io.File
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class SolarSimulationResult(
    val source: File,
    val trace: SolarTrace,
    val coverage: SolarScanCoverageReport,
    val frames: Int,
    val frameRate: Double
)

/**
 * Generates a deterministic slit-scan SER with a curved absorption line and an
 * asymmetric solar-disk marker. The output is suitable for reconstruction and
 * alternation-direction tests.
 */
object SolarScanSimulator {
    fun generate(
        destination: File,
        configuration: SolarScanConfiguration,
        direction: SolarScanDirection,
        width: Int = 320,
        height: Int = 48,
        seed: Long = 0x534F4C4152L,
        cancelCheck: () -> Unit = {}
    ): SolarSimulationResult {
        require(width in 32..2_048 && height in 12..512)
        require(!destination.exists()) { "Simulation output already exists." }
        require(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true)
        require(configuration.framesPerLeg <= SolarScanPreflightEvaluator.MAX_FRAMES_PER_LEG)
        val frames = configuration.framesPerLeg.coerceAtLeast(1).toInt()
        val path = configuration.pathArcsec
        val sweep = SolarSweepModel(
            diskRadiusArcsec = configuration.diskDiameterArcsec / 2.0,
            startCenterArcsec = if (direction == SolarScanDirection.A_TO_B) -path / 2.0 else path / 2.0,
            endCenterArcsec = if (direction == SolarScanDirection.A_TO_B) path / 2.0 else -path / 2.0
        )
        val trace = SolarTrace(
            width = width,
            constantRow = height * 0.47,
            linear = 0.25,
            quadratic = 1.45,
            illuminatedFirst = 2,
            illuminatedLast = width - 3
        )
        val random = Random(seed xor direction.ordinal.toLong())
        val arcsecPerPixel = configuration.diskDiameterArcsec / width
        val writer = SERWriter(destination)
        try {
            writer.open(
                w = width,
                h = height,
                format = PixelFormat.MONO16,
                cameraName = "Indigo solar simulator",
                filterName = "SYNTHETIC"
            )
            val pixels = ShortArray(width * height)
            for (frame in 0 until frames) {
                cancelCheck()
                val fraction = if (frames == 1) 0.0 else frame.toDouble() / (frames - 1)
                val center = sweep.centerAt(fraction)
                val diskMask = projectedSlitIllumination(
                    centerOffsetPixels = sin(fraction * PI * 1.5) * 1.5,
                    centerDistancePixels = kotlin.math.abs(center) / arcsecPerPixel,
                    diskRadiusPixels = configuration.diskDiameterArcsec / 2.0 / arcsecPerPixel,
                    slitLengthPixels = width.toDouble()
                )
                val directionMarker = if (direction == SolarScanDirection.A_TO_B) 1.0 else -1.0
                for (x in 0 until width) {
                    val lineRow = trace.rowAt(x)
                    val spectral = exp(-0.5 * ((x - width * 0.52) / (width * 0.34)).let { it * it })
                    for (y in 0 until height) {
                        val inDisk = x + 0.5 >= diskMask.startPixel && x + 0.5 <= diskMask.endPixel
                        val limbDarkening = if (inDisk) {
                            val positionAlongSlit = (x + 0.5) / width - 0.5
                            (
                                0.86 -
                                    0.18 * kotlin.math.abs(positionAlongSlit) +
                                    0.16 * directionMarker * positionAlongSlit
                                ).coerceIn(0.45, 0.9)
                        } else 0.035
                        val absorptionOffset = (y - lineRow) / 0.85
                        val absorption = 1.0 - 0.58 * exp(-0.5 * absorptionOffset * absorptionOffset)
                        val cloud = if ((y == height / 3 + 2 && x in width / 5..width / 3) ||
                            (y == height * 2 / 3 - 3 && x in width * 2 / 3..width * 4 / 5)
                        ) -0.10 else 0.0
                        val noise = (random.nextDouble() - 0.5) * 0.025
                        val value = (limbDarkening * absorption * (0.82 + 0.18 * spectral) +
                            cloud + noise).coerceIn(0.0, 1.0)
                        pixels[y * width + x] = (value * 65_535.0).toInt().toShort()
                    }
                }
                writer.writeFrame(
                    FrameData(
                        data = shortArrayToLittleEndian(pixels),
                        width = width,
                        height = height,
                        pixelFormat = PixelFormat.MONO16,
                        frameId = frame.toLong(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } finally {
            writer.close()
        }
        require(destination.isFile && destination.length() > 178) { "Simulated SER was not written." }
        return SolarSimulationResult(
            source = destination,
            trace = trace,
            coverage = sweep.report(),
            frames = frames,
            frameRate = configuration.frameRate
        )
    }

    private fun shortArrayToLittleEndian(pixels: ShortArray): ByteArray {
        val bytes = ByteArray(pixels.size * 2)
        for (index in pixels.indices) {
            val value = pixels[index].toInt() and 65_535
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value ushr 8).toByte()
        }
        return bytes
    }
}

internal fun incompleteSimulationSourcesToDelete(
    createdSources: Collection<File>,
    sealedSources: Collection<File>
): List<File> = createdSources.filter { it.exists() && it !in sealedSources }

internal fun publishSealedSimulationSource(
    partial: File,
    destination: File,
    readIdentity: (File, String) -> SolarSourceIdentity = { file, name -> SolarSourceIdentity.of(file, name) }
): SolarSourceIdentity {
    require(partial.isFile) { "Simulation source was not finalized." }
    require(!destination.exists()) { "Simulation source already exists." }
    check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true)
    val identity = try {
        readIdentity(partial, destination.name)
    } catch (failure: Exception) {
        try {
            moveSealedSimulationSource(partial, destination)
        } catch (publishFailure: Exception) {
            failure.addSuppressed(publishFailure)
        }
        throw failure
    }
    moveSealedSimulationSource(partial, destination)
    return identity
}

private fun moveSealedSimulationSource(partial: File, destination: File) {
    try {
        Files.move(
            partial.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(partial.toPath(), destination.toPath())
    }
}
