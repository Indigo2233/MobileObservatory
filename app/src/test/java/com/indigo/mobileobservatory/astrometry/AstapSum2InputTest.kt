package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.util.FitsHeaderParser
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class AstapSum2InputTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sumsPreserveFractionalAveragesAndTenBitRange() {
        val file = source(4, 4, byteArrayOf(1, 2, 10, 20, 3, 4, 30, 40, 100, 101, 200.toByte(), 201.toByte(), 102, 103, 202.toByte(), 203.toByte()))
        val before = sha(file)
        val output = File(temp.root, "sum.fits")
        requireNotNull(AstapSum2Input.inspect(file)).write(output)
        val header = FitsHeaderParser.read(output)
        assertEquals(16, header.intValue("BITPIX")); assertEquals(32768, header.intValue("BZERO"))
        assertEquals(2, header.intValue("NAXIS1")); assertEquals(2, header.intValue("XBINNING"))
        assertNull(header["BAYERPAT"]); assertNull(header["PLTSOLVD"])
        RandomAccessFile(output, "r").use { input ->
            input.seek(header.dataOffset)
            assertEquals(listOf(10, 100, 406, 806), List(4) { input.readShort() + 32768 })
        }
        assertEquals(before, sha(file))
    }

    @Test fun realFrameMatchesThePreviouslyVerifiedUInt16Sums() {
        val source = temp.newFile("weak.fits")
        GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/astrometry/weak-cfa.fits.gz"))).use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        assertEquals("e3522dbf8b8ecacdc55b7738508ea8a772cecaa1e92a237057fb35463c7cbf47", sha(source))
        val output = File(temp.root, "weak-sum.fits")
        val spec = requireNotNull(AstapSum2Input.inspect(source))
        spec.write(output)
        val header = FitsHeaderParser.read(output)
        assertEquals(1928, header.intValue("NAXIS1")); assertEquals(1090, header.intValue("NAXIS2"))
        assertEquals(.804943, header.doubleValue("FOVH")!!, 1e-12)
        assertEquals(2.658526, header.doubleValue("PIXSCALE")!!, 1e-12)
        assertEquals(REFERENCE_PIXELS_SHA256, sha(output, header.dataOffset, 1928L * 1090 * 2))
    }

    @Test fun unsupportedImagesAndOddDimensionsAreLeftForTheOriginalSolver() {
        assertNull(AstapSum2Input.inspect(source(4, 4, ByteArray(16), bitpix = 16)))
        assertNull(AstapSum2Input.inspect(source(5, 4, ByteArray(20))))
        assertNull(AstapSum2Input.inspect(source(4, 5, ByteArray(20))))
        assertNull(AstapSum2Input.inspect(source(4, 4, ByteArray(16), scale = .5)))
    }

    @Test fun cancelledPreparationDeletesOnlyThePartialOutput() {
        val input = source(4, 4, ByteArray(16))
        val before = sha(input); val output = File(temp.root, "cancelled.fits")
        try {
            requireNotNull(AstapSum2Input.inspect(input)).write(output) { throw CancellationException("stop") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(output.exists()); assertEquals(before, sha(input))
    }

    @Test fun fallbackIsLimitedToNativeStarShortageFailures() {
        assertTrue(astapNeedsSum2Retry(PlateSolveResult(false, "failed", log = "Only 4 stars found in image. Abort")))
        assertFalse(astapNeedsSum2Retry(PlateSolveResult(true, "Solved", log = "Only 4 stars found in image. Abort")))
        assertFalse(astapNeedsSum2Retry(PlateSolveResult(false, "timed out", log = "No solution found")))
        assertFalse(astapNeedsSum2Retry(PlateSolveResult(false, "catalog missing")))
    }

    @Test fun restoredWcsMapsEveryOriginalPixelToTheSameSkyPosition() {
        val header = mapOf("PLTSOLVD" to "T", "CTYPE1" to "RA---TAN", "CTYPE2" to "DEC--TAN",
            "CRVAL1" to "26.33455630945", "CRVAL2" to "-13.69720215663", "CRPIX1" to "200.5", "CRPIX2" to "150.5",
            "CD1_1" to ".002", "CD1_2" to ".0002", "CD2_1" to "-.0002", "CD2_2" to ".002",
            "XBINNING" to "2", "YBINNING" to "2", "PIXSCALE" to "7.2")
        val restored = restoreSum2Wcs(header, 800, 600)
        assertEquals("400.5", restored["CRPIX1"]); assertEquals("300.5", restored["CRPIX2"])
        assertEquals("800", restored["NAXIS1"]); assertEquals("600", restored["NAXIS2"])
        assertEquals("1", restored["XBINNING"]); assertEquals(3.6, restored["PIXSCALE"]!!.toDouble(), 0.0)
        val small = OpticalWcs.parse(header); val full = OpticalWcs.parse(restored)
        for (x in listOf(1.0, 400.5, 800.0)) for (y in listOf(1.0, 300.5, 600.0)) {
            val a = small.direction((x + .5) / 2, (y + .5) / 2); val b = full.direction(x, y)
            assertEquals(a.raDeg, b.raDeg, 1e-11); assertEquals(a.decDeg, b.decDeg, 1e-11)
        }
        val sip = restoreSum2Wcs(header + mapOf("A_2_0" to ".1", "BP_0_3" to ".2"), 800, 600)
        assertEquals(.05, sip["A_2_0"]!!.toDouble(), 1e-12); assertEquals(.05, sip["BP_0_3"]!!.toDouble(), 1e-12)
    }

    @Test fun acceptedRetryExportsOriginalSizeWcsForEveryCaller() {
        val original = source(4, 4, ByteArray(16))
        val sidecar = temp.newFile("solution.wcs").apply {
            writeText("PLTSOLVD=T\nCRVAL1=26\nCRVAL2=-13\nCRPIX1=1.5\nCRPIX2=1.5\nCD1_1=.002\nCD1_2=0\nCD2_1=0\nCD2_2=.002")
        }
        val result = requireNotNull(AstapSum2Input.inspect(original)).restore(
            PlateSolveResult(true, "Solved", raDeg = 26.0, decDeg = -13.0, wcsHeaderPath = sidecar.absolutePath))
        assertTrue(result.success)
        assertEquals(.004, result.fovWidthDeg!!, 1e-12)
        assertEquals(3.6, result.arcsecPerPixel!!, 1e-12)
        assertEquals(4, result.imageWcs!!.width); assertEquals(4, result.imageWcs!!.height)
        val header = FitsHeaderReader.read(File(result.wcsHeaderPath!!))
        assertEquals("2.5", header["CRPIX1"]); assertEquals("4", header["NAXIS1"])
        assertEquals(26.0, result.raDeg!!, 0.0); assertEquals(-13.0, result.decDeg!!, 0.0)
    }

    private fun source(width: Int, height: Int, pixels: ByteArray, bitpix: Int = 8, scale: Double = 1.0): File {
        val file = temp.newFile("source-${System.nanoTime()}.fits")
        val cards = linkedMapOf("SIMPLE" to "T", "BITPIX" to "$bitpix", "NAXIS" to "2", "NAXIS1" to "$width", "NAXIS2" to "$height",
            "BSCALE" to "$scale", "BAYERPAT" to "'RGGB'", "XBINNING" to "1", "PLTSOLVD" to "T")
        val text = cards.entries.joinToString("") { (key, value) -> (key.padEnd(8) + "= " + value).padEnd(80) } + "END".padEnd(80)
        file.outputStream().use { it.write(text.padEnd(((text.length + 2879) / 2880) * 2880).toByteArray(Charsets.US_ASCII)); it.write(pixels) }
        return file
    }

    private fun sha(file: File, offset: Long = 0, length: Long = file.length() - offset): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset); var remaining = length; val buffer = ByteArray(65536)
            while (remaining > 0) { val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()); require(count > 0); digest.update(buffer, 0, count); remaining -= count }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { private const val REFERENCE_PIXELS_SHA256 = "4265d556ea58ba5c813c4cac6ce0821f864e0310d2b732e2ac12c15376468726" }
}
