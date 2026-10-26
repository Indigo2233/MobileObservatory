package com.indigo.mobileobservatory.astrometry

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.polar.PolarSamplePhase
import com.indigo.mobileobservatory.polar.PolarSampleSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import com.indigo.mobileobservatory.mount.MountCoordinates

/** Executes the packaged native CLI against a starless FITS and seeds stale successful results. */
class AstapSolveResultInstrumentedTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var input: File

    @Before fun prepare() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(app.cacheDir.toPath(), "astap-provenance-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        // No catalog tile will be read: the uniform image fails star detection first.
        File(context.filesDir, "astap/d20_0101.1476").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(0)) }
        input = File(context.cacheDir, "current.fits")
        val cards = listOf("SIMPLE" to "T", "BITPIX" to "16", "NAXIS" to "2", "NAXIS1" to "384", "NAXIS2" to "256",
            "PLTSOLVD" to "T", "CRVAL1" to "15", "CRVAL2" to "20", "CRPIX1" to "192.5", "CRPIX2" to "128.5",
            "CD1_1" to "-0.0004", "CD1_2" to "0", "CD2_1" to "0", "CD2_2" to "0.0004")
        val header = cards.joinToString("") { (key, value) -> (key.padEnd(8) + "= " + value).padEnd(80) } + "END".padEnd(80)
        input.outputStream().use {
            it.write(header.padEnd(((header.length + 2879) / 2880) * 2880).toByteArray(Charsets.US_ASCII))
            val bytes = 384 * 256 * 2
            it.write(ByteArray(((bytes + 2879) / 2880) * 2880))
        }
        assertNotNull(FitsWcsParser.parseCandidates(listOf(input), 384, 256))
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun nativeFailureCannotReuseOldSidecarsOrTheInputsExistingWcs(): Unit = runBlocking {
        val original = input.readBytes()
        val old = File(context.cacheDir, "previous.wcs").apply { writeText(solvedHeader()) }
        File(context.filesDir, "astap/older.ini").writeText(solvedHeader())
        val result = withTimeout(30_000) { AstapRunner(context).solve(input, 1.8, null, null) }
        assertFalse(result.log, result.success)
        assertNull(result.raDeg); assertNull(result.decDeg); assertNull(result.wcsHeaderPath); assertNull(result.imageWcs)
        assertTrue(result.log, Regex("exitCode=[1-9][0-9]*").containsMatchIn(result.log))
        assertTrue(result.log, result.log.contains("PLTSOLVD=F"))
        assertTrue(result.log, result.log.contains(" -o "))
        assertTrue(old.isFile)
        assertArrayEquals(original, input.readBytes())
    }

    @Test fun repeatedNativeFailureUsesANewOutputAndLeavesThePolarPointFailed(): Unit = runBlocking {
        val runner = AstapRunner(context)
        val first = withTimeout(30_000) { runner.solve(input, 1.8, null, null) }
        assertFalse(first.log, first.success)
        val firstFolder = context.cacheDir.listFiles()!!.single { it.isDirectory && it.name.startsWith("astap_solve_") }
        File(firstFolder, "solution.ini").writeText(solvedHeader())
        File(firstFolder, "solution.wcs").writeText(solvedHeader())
        val session = PolarSampleSession()
        val result = withTimeout(30_000) {
            session.execute(0, { input }, { 1.8 }, { file, fov -> runner.solve(file, fov, null, null) })
        }
        assertFalse(result.log, result.success)
        assertEquals(PolarSamplePhase.FAILED, session.samples.value[0].phase)
        assertEquals(PolarSamplePhase.SOLVING, session.samples.value[0].failedPhase)
        assertEquals(input, session.samples.value[0].file)
        assertEquals(2, context.cacheDir.listFiles()!!.count { it.isDirectory && it.name.startsWith("astap_solve_") })
        assertTrue(File(firstFolder, "solution.wcs").isFile)
    }

    @Test fun weakCfaFrameUsesTheSameUInt16RetryAndFindsSevenStars(): Unit = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        GZIPInputStream(assets.open("weak-cfa.fits.gz")).use { source -> input.outputStream().use { source.copyTo(it) } }
        val original = input.readBytes()
        val result = withTimeout(30_000) {
            AstapRunner(context).solve(input, .804943, MountCoordinates(26.33455630945 / 15, -13.69720215663), .2)
        }
        assertTrue(result.log, result.log.contains("Only 4 stars found in image"))
        assertTrue(result.log, result.log.contains("attempt=uint16_sum2"))
        assertTrue(result.log, result.log.contains("7 stars, 35 quads"))
        // This isolated test has no catalog tiles for the field; finding stars cannot imply a solve.
        assertFalse(result.log, result.success)
        assertNull(result.wcsHeaderPath)
        assertArrayEquals(original, input.readBytes())
        assertFalse(context.cacheDir.walkTopDown().any { it.isFile && it.name == "input-sum2.fits" })
    }

    private fun solvedHeader() = "PLTSOLVD=T\nCRVAL1=230\nCRVAL2=50\nCRPIX1=192.5\nCRPIX2=128.5\nCD1_1=-0.0004\nCD1_2=0\nCD2_1=0\nCD2_2=0.0004"
}
