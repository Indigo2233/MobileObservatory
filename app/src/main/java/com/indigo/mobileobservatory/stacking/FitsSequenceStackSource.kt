package com.indigo.mobileobservatory.stacking

import android.graphics.Bitmap
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.media.MediaImageEditing
import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.util.FitsHeader
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import org.json.JSONArray

internal interface OfflineStackSource : BufferedPlanetaryFrameSource {
    val info: StackSourceInfo
    val calibration: CalibrationSelection
    val plan: OfflineRegionPlan
    val algorithm: DemosaicAlgorithm get() = plan.algorithm
    val interpretation: SerCfaInterpretation get() = SerCfaInterpretation.AUTO
    val processingOverheadBytes: Long get() = plan.overheadBytes
    val demosaicScratchBytes: Long get() = plan.demosaicScratchBytes
    val inputFormat: String
    val exposureSource: String
    override val width get() = plan.width
    override val height get() = plan.height
    override val channels get() = plan.channels
    override val frameCount get() = info.frames
    override val physicalEdges get() = PlanetaryImageEdges(plan.region.left == 0, plan.region.top == 0,
        plan.region.right == info.width, plan.region.bottom == info.height)
    fun calibrationMetadata(): Map<String, String>
}

internal fun File.isFitsImage(): Boolean = extension.equals("fit", true) || extension.equals("fits", true)

internal fun fitsSequenceMembers(directory: File): List<File> = directory.listFiles().orEmpty()
    .filter { it.isFitsImage() && MediaLibrary.describe(it) != null }
    .sortedWith(compareBy<File> { it.name.lowercase() }.thenBy { it.name })

internal fun previewFitsFrame(file: File, checkCancelled: () -> Unit = {}): Bitmap =
    MediaImageEditing.previewFitsFrame(file, checkCancelled)

/** Reads physical linear samples directly from uncompressed FITS, with one reusable native tile. */
internal class FitsSequenceStackSource(
    file: File,
    private val frames: List<File>,
    identity: String,
    algorithm: DemosaicAlgorithm,
    region: PixelRoi?,
    softwareBin: Int,
    target: StackTarget,
    private val checkCancelled: () -> Unit
) : OfflineStackSource {
    private val layout = Layout(FitsHeaderParser.read(frames.first()))
    override val calibration = CalibrationSelection()
    override val plan = OfflineRegionPlan(layout.width, layout.height, layout.format,
        region ?: PixelRoi(0, 0, layout.width, layout.height), algorithm, target, softwareBin)
    override val inputFormat get() = "FITS BITPIX=${layout.bitpix}"
    override val exposureSource get() = "fits_exptime_or_exposure"
    override val info = StackSourceInfo(file, layout.width, layout.height, channels, kotlin.math.abs(layout.bitpix),
        frames.size.toLong(), identity, isBayer = layout.format.isBayer, effectiveLayout = layout.format.name,
        decodingIdentity = "$identity|${plan.region}|bin=$softwareBin|demosaic=$algorithm",
        canOverrideCfa = false, fitsFiles = frames, sensorFormat = layout.format)
    private val demosaic = DdfapdDemosaicer.Workspace()
    private var sensor = FloatArray(0)
    private var rgb = FloatArray(0)
    private var row = ByteArray(0)
    private var bandBytes = ByteArray(0)
    private var lastIndex = -1L
    private var lastExposure = 0.0

    override fun readFrame(index: Long): LinearImage {
        require(width >= 12 && height >= 12 && width.toLong() * height <= LinearImage.MAX_PIXELS) { "processing_size" }
        return readFrameInto(index, FloatArray(width * height * channels))
    }
    override fun readFrameInto(index: Long, destination: FloatArray) = decode(index, destination, false)
    override fun readLuminanceInto(index: Long, destination: FloatArray) = decode(index, destination, true)
    override fun exposureSeconds(index: Long): Double =
        if (index == lastIndex) lastExposure else exposure(FitsHeaderParser.read(frames[index.toInt()]))

    private fun decode(index: Long, destination: FloatArray, luminance: Boolean): LinearImage {
        require(index in 0 until frameCount)
        val count = if (luminance) 1 else channels
        require(width >= 12 && height >= 12 && width.toLong() * height <= LinearImage.MAX_PIXELS &&
            destination.size.toLong() == width.toLong() * height * count) { "processing_size" }
        checkCancelled()
        RandomAccessFile(frames[index.toInt()], "r").use { input ->
            val header = FitsHeaderParser.read(input)
            val frame = Layout(header)
            require(frame == layout) { "stack_fits_layout" }
            require(frame.dataBytes <= input.length() - header.dataOffset) { "stack_fits_invalid" }
            val bin = plan.softwareBin
            for (y in 0 until height step OfflineRegionPlan.TILE) {
                // Integer capture frames share the planner's native band budget.
                val band = if (layout.bytes <= 2) plan.bandRegion(y) else null
                if (band != null) readBand(input, header.dataOffset, band)
                for (x in 0 until width step OfflineRegionPlan.TILE) {
                    val tileWidth = minOf(OfflineRegionPlan.TILE, width - x)
                    val tileHeight = minOf(OfflineRegionPlan.TILE, height - y)
                    val tile = PixelRoi(plan.region.left + x * bin, plan.region.top + y * bin,
                        tileWidth * bin, tileHeight * bin)
                    val read = plan.readRegion(tile)
                    val raw = readTile(input, header.dataOffset, read, band)
                    val linear = if (layout.format.isBayer) {
                        if (rgb.size != read.width * read.height * 3) rgb = FloatArray(read.width * read.height * 3)
                        LinearPixelDecoder.demosaicSensorInto(raw, layout.format, algorithm, rgb, demosaic,
                            PixelTraits(roiX = read.left + layout.xPhase, roiY = read.top + layout.yPhase,
                                bayerPatternAtOutput = false), checkCancelled)
                    } else raw
                    for (dy in 0 until tileHeight) {
                        checkCancelled()
                        for (dx in 0 until tileWidth) {
                            val output = ((y + dy) * width + x + dx) * count
                            var gray = 0f
                            for (channel in 0 until channels) {
                                var sum = 0.0
                                for (by in 0 until bin) for (bx in 0 until bin) {
                                    val sx = tile.left - read.left + dx * bin + bx
                                    val sy = tile.top - read.top + dy * bin + by
                                    sum += linear.pixels[(sy * read.width + sx) * channels + channel]
                                }
                                val value = (sum / (bin * bin)).toFloat()
                                if (!luminance) destination[output + channel] = value
                                else gray += when (channels) {
                                    1 -> value
                                    else -> when (channel) { 0 -> value * .2126f; 1 -> value * .7152f; else -> value * .0722f }
                                }
                            }
                            if (luminance) destination[output] = gray
                        }
                    }
                }
            }
            lastIndex = index
            lastExposure = exposure(header)
        }
        return LinearImage(width, height, count, destination)
    }

    private fun readBand(input: RandomAccessFile, offset: Long, band: PixelRoi) {
        val stride = Math.multiplyExact(band.width, layout.bytes)
        val length = Math.multiplyExact(stride, Math.multiplyExact(band.height, layout.planes))
        if (bandBytes.size != length) bandBytes = ByteArray(length)
        for (plane in 0 until layout.planes) for (y in 0 until band.height) {
            checkCancelled()
            val first = ((plane.toLong() * layout.height + band.top + y) * layout.width + band.left)
            input.seek(offset + first * layout.bytes)
            input.readFully(bandBytes, (plane * band.height + y) * stride, stride)
        }
    }

    private fun readTile(input: RandomAccessFile, offset: Long, region: PixelRoi, band: PixelRoi?): LinearImage {
        val length = region.width * layout.bytes
        if (band == null && row.size != length) row = ByteArray(length)
        val buffer = ByteBuffer.wrap(if (band == null) row else bandBytes)
        val samples = region.width * region.height * layout.planes
        if (sensor.size != samples) sensor = FloatArray(samples)
        for (plane in 0 until layout.planes) for (y in 0 until region.height) {
            checkCancelled()
            if (band == null) {
                val first = ((plane.toLong() * layout.height + region.top + y) * layout.width + region.left)
                input.seek(offset + first * layout.bytes)
                input.readFully(row)
                buffer.rewind()
            } else {
                val start = ((plane * band.height + region.top - band.top + y) * band.width + region.left - band.left)
                buffer.position(start * layout.bytes)
            }
            for (x in 0 until region.width) {
                val raw = when (layout.bitpix) {
                    8 -> (buffer.get().toInt() and 255).toDouble()
                    16 -> buffer.short.toDouble()
                    32 -> buffer.int.toDouble()
                    else -> buffer.float.toDouble()
                }
                val value = ((raw * layout.scale + layout.zero) / layout.white).toFloat()
                require(value.isFinite()) { "stack_fits_invalid" }
                require(layout.bitpix != -32 || layout.normalizedFloat || value in -1f..1f) { "stack_fits_float_scale" }
                sensor[(y * region.width + x) * layout.planes + plane] = value
            }
        }
        return LinearImage(region.width, region.height, layout.planes, sensor)
    }

    override fun calibrationMetadata() = mapOf(
        "sourceWidth" to info.width.toString(), "sourceHeight" to info.height.toString(),
        "sourceRegion" to JSONArray(listOf(plan.region.left, plan.region.top, plan.region.width, plan.region.height)).toString(),
        "softwareBin" to plan.softwareBin.toString(), "processingDomain" to "linear_fits_samples_then_bin",
        "calibrationEnabled" to "false"
    )

    override fun close() { demosaic.release() }

    private data class Layout(
        val width: Int, val height: Int, val planes: Int, val bitpix: Int, val validBits: Int,
        val scale: Double, val zero: Double, val format: PixelFormat, val xPhase: Int, val yPhase: Int,
        val normalizedFloat: Boolean
    ) {
        constructor(header: FitsHeader) : this(
            header.intValue("NAXIS1") ?: 0, header.intValue("NAXIS2") ?: 0,
            if (header["NAXIS"] == "3") header.intValue("NAXIS3") ?: 0 else 1,
            header.intValue("BITPIX") ?: 0, header.intValue("VALIDBIT") ?: 0,
            header.doubleValue("BSCALE") ?: 1.0, header.doubleValue("BZERO") ?: 0.0,
            format(header), (header.intValue("XBAYROFF") ?: 0) and 1, (header.intValue("YBAYROFF") ?: 0) and 1,
            header["BUNIT"]?.equals("normalized linear", true) == true
        ) {
            require(header["SIMPLE"] == "T" && (header.intValue("NAXIS") ?: 0) in 2..3) { "stack_fits_invalid" }
            require(header["EDITED"] != "T") { "stack_fits_display_only" }
        }
        init {
            require(width > 0 && height > 0 && (planes == 1 || planes == 3) &&
                bitpix in listOf(8, 16, 32, -32) && scale.isFinite() && scale != 0.0 && zero.isFinite()) { "stack_fits_invalid" }
        }
        val bytes = kotlin.math.abs(bitpix) / 8
        val dataBytes = Math.multiplyExact(width.toLong() * height, (planes * bytes).toLong())
        val white = if (bitpix == -32) 1.0 else {
            val bits = validBits.takeIf { it in 1..bitpix } ?: bitpix
            val effectiveBits = if (bitpix > 8 && zero == 0.0 && bits == bitpix) bits - 1 else bits
            ((1L shl effectiveBits) - 1).toDouble() * kotlin.math.abs(scale)
        }
    }

    companion object {
        private fun exposure(header: FitsHeader): Double =
            sequenceOf(header.doubleValue("EXPTIME"), header.doubleValue("EXPOSURE"))
                .filterNotNull().firstOrNull { it.isFinite() && it > 0.0 } ?: 0.0

        private fun format(header: FitsHeader): PixelFormat {
            val eightBit = header["BITPIX"] == "8"
            val pattern = header["BAYERPAT"]?.trim()?.uppercase()
            if (header["NAXIS"] == "3" && header["NAXIS3"] == "3") {
                require(pattern.isNullOrEmpty()) { "stack_fits_invalid" }
                return if (eightBit) PixelFormat.RGB24 else PixelFormat.RGB48
            }
            val prefix = when (pattern) {
                null, "" -> "MONO"
                "RGGB" -> "BAYER_RG"
                "GRBG" -> "BAYER_GR"
                "GBRG" -> "BAYER_GB"
                "BGGR" -> "BAYER_BG"
                else -> error("stack_fits_invalid")
            }
            return PixelFormat.valueOf(prefix + if (eightBit) "8" else "16")
        }
    }
}
