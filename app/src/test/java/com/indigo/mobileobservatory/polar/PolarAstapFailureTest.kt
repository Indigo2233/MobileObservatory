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
}
