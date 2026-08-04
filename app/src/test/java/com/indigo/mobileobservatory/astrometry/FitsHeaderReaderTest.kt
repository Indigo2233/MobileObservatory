package com.indigo.mobileobservatory.astrometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class FitsHeaderReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun writeFits(vararg cards: Pair<String, String>): File {
        val file = folder.newFile("frame.fits")
        val header = StringBuilder()
        cards.forEach { (key, value) ->
            header.append("${key.padEnd(8).take(8)}= ${value.padStart(20)} / test".padEnd(80).take(80))
        }
        header.append("END".padEnd(80))
        val block = header.toString().padEnd(2880, ' ')
        file.writeBytes(block.toByteArray(Charsets.US_ASCII))
        return file
    }

    @Test
    fun midExposureIsHalfAnExposureAfterDateObs() {
        val file = writeFits(
            "SIMPLE" to "T",
            "EXPOSURE" to "10.000000",
            "DATE-OBS" to "'2026-03-21T22:15:30Z'"
        )
        assertEquals(Instant.parse("2026-03-21T22:15:35Z"), FitsHeaderReader.midExposureTime(file))
    }

    @Test
    fun acceptsExptimeAndDateObsWithoutZoneSuffix() {
        val file = writeFits(
            "SIMPLE" to "T",
            "EXPTIME" to "4.0",
            "DATE-OBS" to "'2026-03-21T22:15:30.000'"
        )
        assertEquals(Instant.parse("2026-03-21T22:15:32Z"), FitsHeaderReader.midExposureTime(file))
    }

    @Test
    fun missingDateObsHasNoUsableTime() {
        val file = writeFits("SIMPLE" to "T", "EXPOSURE" to "4.0")
        assertNull(FitsHeaderReader.midExposureTime(file))
    }

    @Test
    fun missingExposureFallsBackToTheStartTime() {
        val file = writeFits("SIMPLE" to "T", "DATE-OBS" to "'2026-03-21T22:15:30Z'")
        assertEquals(Instant.parse("2026-03-21T22:15:30Z"), FitsHeaderReader.midExposureTime(file))
    }
}
