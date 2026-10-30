package com.indigo.mobileobservatory.astrometry

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AstapDenseJpegRetryTest {
    private val unmatched = PlateSolveResult(false, "ASTAP failed with exit code 1.", elapsedMs = 9900,
        log = "501 stars, 389 quads selected in the image.\nNo solution found!  :(\nexitCode=1")

    @Test fun denseJpegRetriesOnceAndReturnsTheNewSolution() = runTest {
        val limits = mutableListOf<Int>()
        val result = retryDenseJpegSolve(File("shark.JPG"), unmatched) { limit ->
            limits += limit
            PlateSolveResult(true, "Solved", raDeg = 331.43, elapsedMs = 9400, log = "19 of 20 quads selected matching")
        }
        assertEquals(listOf(1000), limits)
        assertTrue(result.success)
        assertEquals(331.43, result.raDeg!!, 0.0)
        assertEquals(19300L, result.elapsedMs)
        assertTrue(result.log.contains("No solution found"))
        assertTrue(result.log.contains("attempt=dense_jpeg"))
        assertTrue(result.log.contains("19 of 20 quads"))
    }

    @Test fun otherFailuresAndOtherFormatsDoNotRetry() = runTest {
        val inputs = listOf(
            File("frame.fits") to unmatched,
            File("frame.jpg") to unmatched.copy(success = true),
            File("frame.jpg") to unmatched.copy(log = "45 stars, 34 quads selected in the image.\nNo solution found!"),
            File("frame.jpg") to unmatched.copy(log = "ASTAP solve timed out."),
            File("frame.jpg") to unmatched.copy(log = "No solution found!")
        )
        inputs.forEach { (file, original) ->
            val result = retryDenseJpegSolve(file, original) { error("unexpected retry") }
            assertEquals(original, result)
        }
    }

    @Test fun retryCommandPreservesInputAndSearchParameters() {
        val command = buildAstapCommand(File("astap"), File("shark.jpg"), 1.492522,
            File("database"), AstapDatabase.D20, null, 180.0, File("output"), maxStars = 1000)
        assertEquals("1000", command[command.indexOf("-s") + 1])
        assertEquals("1.492522", command[command.indexOf("-fov") + 1])
        assertEquals("180.000000", command[command.indexOf("-r") + 1])
        assertFalse(command.contains("-ra"))
    }
}
