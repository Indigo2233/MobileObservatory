package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import org.junit.Assert.*
import org.junit.Test

class PolarAstapFailureTest {
    @Test fun focalLengthInformationCannotHideTheSolversActualStarFailure() {
        repeat(2) {
            val sample = PolarSample(phase = PolarSamplePhase.FAILED, failedPhase = PolarSamplePhase.SOLVING,
                failure = "ASTAP failed with exit code 1.", result = PlateSolveResult(false, "ASTAP failed with exit code 1.",
                    log = "command=astap -fov 1.8\nFocal length: 200 mm\nDetected 7 stars.\nNot enough stars to solve the image."))
            assertEquals(PolarSampleAdvice.STARS, sample.advice())
        }
    }

    @Test fun nativeFailuresAreClassifiedFromTheirOutput() {
        val cases = listOf(
            "Only 7 stars found in image. Abort" to PolarFailureReason.STARS,
            "Error, no star database found at /db ! Download and install a star database." to PolarFailureReason.DATABASE,
            "Not enough memory for binning!" to PolarFailureReason.MEMORY,
            "Cannot read image" to PolarFailureReason.IMAGE,
            "Focal length: 200 mm\nNo solution found" to PolarFailureReason.NO_SOLUTION
        )
        for ((log, reason) in cases) {
            val details = PolarSample(phase = PolarSamplePhase.FAILED, failedPhase = PolarSamplePhase.SOLVING,
                result = PlateSolveResult(false, "ASTAP failed with exit code 2.", log = log)).failureDetails()
            assertEquals(log, reason, details.reason)
            assertTrue(details.nativeMessage.isNotBlank())
            if (reason == PolarFailureReason.STARS) assertEquals(7, details.detectedStars)
        }
    }

    @Test fun diagnosticHeadersCannotBeMistakenForTheSolverFailure() {
        val details = PolarSample(phase = PolarSamplePhase.FAILED, failedPhase = PolarSamplePhase.SOLVING,
            result = PlateSolveResult(false, "ASTAP solve timed out.", log = "command=astap --focal length=200\ninput=/storage/error.fits\nDetected 12 stars.")).failureDetails()
        assertEquals(PolarFailureReason.TIMEOUT, details.reason)
        assertEquals(12, details.detectedStars)
        assertFalse(details.nativeMessage.contains("command="))
        assertFalse(details.nativeMessage.contains("input="))
    }

    @Test fun sum2RetryReportsItsFinalStarCountAndFailure() {
        val details = PolarSample(phase = PolarSamplePhase.FAILED, failedPhase = PolarSamplePhase.SOLVING,
            result = PlateSolveResult(false, "ASTAP failed with exit code 1.", log =
                "Only 4 stars found in image. Abort\nNo solution found!\n" +
                    "attempt=uint16_sum2 originalDims=3856x2180 solverDims=1928x1090\n" +
                    "7 stars, 35 quads\nNo solution found!\noutputBase=/cache/error/result\n" +
                    "PLTSOLVD=F\nsolutionSource=/cache/failed.ini")).failureDetails()

        assertEquals(PolarFailureReason.NO_SOLUTION, details.reason)
        assertEquals(7, details.detectedStars)
        assertEquals("No solution found!", details.nativeMessage)
    }

    @Test fun sum2RetryCanStillReportTooFewStars() {
        val details = PolarSample(phase = PolarSamplePhase.FAILED, failedPhase = PolarSamplePhase.SOLVING,
            result = PlateSolveResult(false, "ASTAP failed with exit code 1.", log =
                "Only 4 stars found in image. Abort\n" +
                    "attempt=uint16_sum2 originalDims=3856x2180 solverDims=1928x1090\n" +
                    "Only 5 stars found in image. Abort\nNo solution found!")).failureDetails()

        assertEquals(PolarFailureReason.STARS, details.reason)
        assertEquals(5, details.detectedStars)
        assertFalse(details.nativeMessage.contains("Only 4 stars"))
    }
}
