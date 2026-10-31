package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.util.FitsHeader
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.pow

/** Lossless 2x2 sums: byte inputs use UInt16; UInt16 inputs use Int32 to retain the full sum. */
internal class AstapSum2Input private constructor(val source: File, val header: FitsHeader, val width: Int, val height: Int) {
    private val sourceBytesPerPixel = if (header.intValue("BITPIX") == 16) 2 else 1
    val outputBitpix = if (sourceBytesPerPixel == 2) 32 else 16
    companion object {
        fun inspect(source: File): AstapSum2Input? {
            if (source.extension.lowercase() !in setOf("fit", "fits")) return null
            val h = FitsHeaderParser.read(source)
            val w = h.intValue("NAXIS1") ?: return null
            val height = h.intValue("NAXIS2") ?: return null
            val bitpix = h.intValue("BITPIX") ?: return null
            val expectedOffset = if (bitpix == 16) 32768.0 else 0.0
            if (bitpix !in listOf(8, 16) || h.intValue("NAXIS") != 2 ||
                (h.doubleValue("BSCALE") ?: 1.0) != 1.0 || (h.doubleValue("BZERO") ?: 0.0) != expectedOffset ||
                w !in 2..32768 || height < 2 || w % 2 != 0 || height % 2 != 0) return null
            require(source.length() >= h.dataOffset + w.toLong() * height * (bitpix / 8)) { "Truncated FITS image" }
            return AstapSum2Input(source, h, w, height)
        }
    }

    fun write(target: File, check: () -> Unit = {}) {
        require(source.canonicalFile != target.canonicalFile && !target.exists()) { "Independent solver input required" }
        val numericKeys = setOf("EXPOSURE", "EXPTIME", "GAIN", "GAINDB", "XPIXSZ", "YPIXSZ", "FOCALLEN", "FOVH", "FOVW", "RA", "DEC")
        val stringKeys = setOf("DATE-OBS", "DATE_OBS", "INSTRUME", "SOFTWARE", "OBJECT", "FILTER", "TELESCOP", "OBJCTRA", "OBJCTDEC")
        val cards = mutableListOf(fitsCard("SIMPLE", "T"), fitsCard("BITPIX", outputBitpix.toString()), fitsCard("NAXIS", "2"),
            fitsCard("NAXIS1", (width / 2).toString()), fitsCard("NAXIS2", (height / 2).toString()),
            fitsCard("BSCALE", "1"), fitsCard("BZERO", if (outputBitpix == 16) "32768" else "0"))
        header.cards.forEach { (key, value) ->
            if (key in numericKeys && header.doubleValue(key)?.isFinite() == true) cards += fitsCard(key, value)
            if (key in stringKeys) cards += fitsCard(key, "'${value.replace("'", "''")}'")
        }
        for (key in listOf("XBINNING", "YBINNING")) cards += fitsCard(key, ((header.intValue(key) ?: 1) * 2).toString())
        header.doubleValue("PIXSCALE")?.let { cards += fitsCard("PIXSCALE", (it * 2).toString()) }
        cards += fitsCard("SOLVEBIN", "2")
        require(target.createNewFile()) { "Solver input already exists" }
        try {
            RandomAccessFile(source, "r").use { input ->
                input.seek(header.dataOffset)
                DataOutputStream(BufferedOutputStream(target.outputStream(), 65536)).use { output ->
                    val text = (cards + "END".padEnd(80)).joinToString("")
                    output.write(text.padEnd(((text.length + 2879) / 2880) * 2880).toByteArray(Charsets.US_ASCII))
                    val first = ByteArray(width * sourceBytesPerPixel)
                    val second = ByteArray(width * sourceBytesPerPixel)
                    fun value(row: ByteArray, x: Int): Int = if (sourceBytesPerPixel == 1) row[x].toInt() and 255
                        else (((row[x * 2].toInt() and 255) shl 8) or
                            (row[x * 2 + 1].toInt() and 255)).toShort().toInt() + 32768
                    repeat(height / 2) {
                        check(); input.readFully(first); input.readFully(second)
                        for (x in 0 until width step 2) {
                            val sum = value(first, x) + value(first, x + 1) + value(second, x) + value(second, x + 1)
                            if (outputBitpix == 32) output.writeInt(sum) else output.writeShort(sum - 32768)
                        }
                    }
                    val bytes = width.toLong() * height / 4 * (outputBitpix / 8)
                    output.write(ByteArray(((2880 - bytes % 2880) % 2880).toInt()))
                }
            }
            check()
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    fun restore(result: PlateSolveResult): PlateSolveResult {
        if (!result.success) return result
        val solved = File(requireNotNull(result.wcsHeaderPath))
        val restored = restoreSum2Wcs(FitsHeaderReader.read(solved), width, height)
        val file = File(solved.parentFile, "solution-original.wcs")
        file.writeText(restored.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }, Charsets.US_ASCII)
        val parsed = requireNotNull(FitsWcsParser.parseCandidates(listOf(file), width, height)) { "Invalid restored WCS" }
        return result.copy(raDeg = parsed.raDeg, decDeg = parsed.decDeg, fovWidthDeg = parsed.fovWidthDeg,
            fovHeightDeg = parsed.fovHeightDeg, rotationDeg = parsed.rotationDeg, arcsecPerPixel = parsed.arcsecPerPixel,
            wcsHeaderPath = file.absolutePath, imageWcs = SolvedImageWcs.parse(restored, width, height),
            log = result.log + "\nrestoredOriginalDims=${width}x$height\nsolutionSource=${file.absolutePath}\n")
    }

    private fun fitsCard(key: String, value: String) =
        (key.padEnd(8) + "= " + if (value.startsWith("'")) value else value.padStart(20)).padEnd(80).take(80)
}

/** FITS coordinates obey p_original = 2*p_sum - 0.5, including reference pixels and SIP offsets. */
internal fun restoreSum2Wcs(solved: Map<String, String>, width: Int, height: Int): Map<String, String> {
    val result = solved.toMutableMap()
    fun number(key: String) = solved[key]?.replace('D', 'E', true)?.toDoubleOrNull()?.takeIf { it.isFinite() }
    for (axis in 1..2) {
        val key = "CRPIX$axis"
        result[key] = (2 * requireNotNull(number(key)) - .5).toString()
        number("CDELT$axis")?.let { result["CDELT$axis"] = (it / 2).toString() }
        for (other in 1..2) {
            val cd = "CD${axis}_$other"
            number(cd)?.let { result[cd] = (it / 2).toString() }
        }
    }
    result["NAXIS"] = "2"; result["NAXIS1"] = width.toString(); result["NAXIS2"] = height.toString()
    result["SOLVEBIN"] = "2"
    for ((key, _) in solved) {
        val sip = Regex("(?:A|B|AP|BP)_([0-9]+)_([0-9]+)").matchEntire(key) ?: continue
        number(key)?.let { result[key] = (it * 2.0.pow(1 - sip.groupValues[1].toInt() - sip.groupValues[2].toInt())).toString() }
    }
    number("PIXSCALE")?.let { result["PIXSCALE"] = (it / 2).toString() }
    for (key in listOf("XBINNING", "YBINNING")) number(key)?.let { result[key] = (it.toInt() / 2).coerceAtLeast(1).toString() }
    return result
}

internal fun astapNeedsSum2Retry(result: PlateSolveResult): Boolean = !result.success &&
    (Regex("Only\\s+[0-9]+\\s+stars?\\s+found\\s+in\\s+image", RegexOption.IGNORE_CASE).containsMatchIn(result.log) ||
        result.log.contains("Not enough stars", true))
