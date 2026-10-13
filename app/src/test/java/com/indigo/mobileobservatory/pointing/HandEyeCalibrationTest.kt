package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.ObserverSite
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.math.*
import kotlin.random.Random

class HandEyeCalibrationTest {
    private val time = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
    private val site = ObserverSite(31.0, 121.0, 14.0)
    private val identity = OpticalIdentity("phone", "0/2", "phone", 4, 2)
    private val geometry = CameraGeometry(1000, 800, parity = ImageParity.MIRRORED, intrinsicsFingerprint = "lens-2:crop:actual-intrinsics")
    private val world = rotation(SkyVector(1.0, 2.0, -1.0), 118.0)
    private val extrinsic = rotation(SkyVector(-2.0, 1.0, 1.0), 137.0)
    private val imu = listOf(rotation(SkyVector(1.0, 0.0, 0.0), 0.0),
        rotation(SkyVector(1.0, 0.0, 0.0), 37.0), rotation(SkyVector(0.0, 1.0, 0.0), 42.0),
        rotation(SkyVector(0.0, 0.0, 1.0), 31.0).apply(rotation(SkyVector(1.0, 0.0, 0.0), 12.0)))

    private fun samples(a: SkyPose = world, x: SkyPose = extrinsic, noise: Double = 0.0,
                        sensors: List<SkyPose> = imu): List<HandEyeSample> = sensors.mapIndexed { index, sensor ->
        // Exposures are minutes apart, so a direct fit in J2000 would have substantial Earth-rotation error.
        val utc = time + index * 120_000L; val elapsed = 10_000L + index * 120_000L
        val enu = a.apply(sensor).apply(x).apply(rotation(SkyVector(1.0, index + 1.0, -2.0), if (index % 2 == 0) noise else -noise))
        val optical = SkyPoseFrames.trueEnuToJ2000(enu, utc, site)
        val observation = OpticalObservation("sample-$index", identity, ObservationStamp(utc, elapsed, 1000, 2),
            optical.forward.direction(), optical, geometry, .01, OpticalObservationKind.PHONE_PLATE)
        HandEyeSample(observation, sensor, utc, elapsed, site, "synthetic-session")
    }

    private fun fit(samples: List<HandEyeSample>) = HandEyeCalibrationFitter.fit(samples.take(3), samples.last())
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Invalid calibration was accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun mirroredLargeOffsetRecoversThePhysicalInstallationAcrossExposureTimes() {
        val inputs = samples()
        val model = fit(inputs)
        assertTrue(model.valid)
        assertEquals(1L, model.version)
        assertEquals(identity, model.source)
        assertEquals(inputs.take(3), model.trainingSamples)
        assertSame(inputs.last(), model.validationSample)
        assertTrue(rotationDistanceDeg(extrinsic, model.opticalInImu) < .0001)
        assertTrue(model.fitMaximumResidualDeg < .0001)
        assertTrue(model.validationResidualDeg < .0001)
        assertTrue(rotationDistanceDeg(extrinsic, rotation(SkyVector(1.0, 0.0, 0.0), 0.0)) > 100)
    }

    @Test fun randomizedWorldAndExtrinsicRotationsAreIdentifiedIncludingNearHalfTurns() {
        val random = Random(741)
        repeat(24) {
            fun axis() = SkyVector(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
            val a = rotation(axis(), random.nextDouble(0.0, 179.9))
            val x = rotation(axis(), random.nextDouble(0.0, 179.9))
            val model = fit(samples(a, x))
            assertTrue(rotationDistanceDeg(x, model.opticalInImu) < .0001)
            assertTrue(model.validationResidualDeg < .0001)
        }
    }

    @Test fun reflectionIsNormalizedBeforeAFixedRotationCanBeFitted() {
        val p = world.apply(imu[1]).apply(extrinsic)
        val reflectedRight = p.right * -1.0
        rejected { SkyPose(reflectedRight, p.up, p.forward) }
        // Undoing the image reflection restores SO(3), but does not identify X by itself.
        val normalized = SkyPose(reflectedRight * -1.0, p.up, p.forward)
        assertTrue(rotationDistanceDeg(normalized, p) < 1e-8)
        val normalInputs = samples().map { s -> s.copy(observation = s.observation.copy(
            geometry = geometry.copy(parity = ImageParity.NORMAL))) }
        assertTrue(rotationDistanceDeg(fit(normalInputs).opticalInImu, fit(samples()).opticalInImu) < 1e-8)
    }

    @Test fun independentFullPoseVerificationRejectsWrongRollEvenWhenTheAxisIsExact() {
        val inputs = samples()
        val last = inputs.last(); val original = last.observation.pose!!
        val rolled = original.apply(rotation(SkyVector(0.0, 0.0, 1.0), 6.0))
        assertTrue(original.forward.angleDeg(rolled.forward) < 1e-8)
        rejected { fit(inputs.dropLast(1) + last.copy(observation = last.observation.copy(pose = rolled))) }
        val training = inputs[1]; val bad = training.observation.pose!!.apply(rotation(SkyVector(0.0, 0.0, 1.0), 8.0))
        rejected { fit(inputs.mapIndexed { i, s -> if (i == 1) s.copy(observation = s.observation.copy(pose = bad)) else s }) }
    }

    @Test fun noisyMeasurementsProduceMeasuredResidualsAndRetainAnIndependentHoldout() {
        val model = fit(samples(noise = .08))
        assertTrue(model.fitResidualDeg > .001)
        assertTrue(model.fitMaximumResidualDeg < .3)
        assertTrue(model.validationResidualDeg > .001)
        assertTrue(model.validationResidualDeg < .3)
        assertTrue(rotationDistanceDeg(extrinsic, model.opticalInImu) < .3)
        assertTrue(model.trainingObservationIds.intersect(model.validationObservationIds).isEmpty())
    }

    @Test fun singleAxisSmallOrNearlyParallelRotationsCannotConstrainAnExtrinsic() {
        rejected { fit(samples(sensors = listOf(0.0, 20.0, 45.0, 70.0).map { rotation(SkyVector(0.0, 0.0, 1.0), it) })) }
        rejected { fit(samples(sensors = listOf(imu[0], rotation(SkyVector(1.0, 0.0, 0.0), 1.0),
            rotation(SkyVector(0.0, 1.0, 0.0), 2.0), imu[3]))) }
        rejected { fit(samples(sensors = listOf(imu[0], imu[1], rotation(SkyVector(1.0, .01, 0.0), 42.0), imu[3]))) }
        rejected { HandEyeCalibrationFitter.fit(samples().take(2), samples().last()) }
    }

    @Test fun weakRotationalExcitationIncreasesTheInstallationErrorAllowance() {
        val weak = fit(samples(sensors = listOf(imu[0], rotation(SkyVector(1.0, 0.0, 0.0), 7.0),
            rotation(SkyVector(0.0, 1.0, 0.0), 8.0), rotation(SkyVector(0.0, 0.0, 1.0), 15.0))))
        val strong = fit(samples())
        assertTrue(weak.uncertaintyDeg > strong.uncertaintyDeg * 2)
        assertTrue(weak.fitMaximumResidualDeg < .0001)
    }

    @Test fun incompleteRollClockGapsAndReusedValidationEvidenceAreRejected() {
        val inputs = samples(); val last = inputs.last()
        rejected { fit(inputs.dropLast(1) + last.copy(observation = last.observation.copy(pose = null))) }
        rejected { fit(inputs.dropLast(1) + last.copy(sensorElapsedMs = last.sensorElapsedMs - 501)) }
        rejected { fit(inputs.dropLast(1) + last.copy(sensorUtcMs = last.sensorUtcMs + 1000)) }
        rejected { fit(inputs.dropLast(1) + last.copy(sensorSessionId = "after-sensor-restart")) }
        rejected { fit(inputs.dropLast(1) + last.copy(observation = last.observation.copy(stamp =
            last.observation.stamp.copy(utcMidpointMs = last.observation.stamp.utcMidpointMs + 1000)), sensorUtcMs = last.sensorUtcMs + 1000)) }
        rejected { fit(inputs.dropLast(1) + last.copy(observation = last.observation.copy(stamp = last.observation.stamp.copy(clockUncertaintyMs = 251)))) }
        rejected { fit(inputs.dropLast(1) + last.copy(observation = last.observation.copy(independentReferenceId = inputs.first().observation.id))) }
        rejected { fit(inputs.take(3) + inputs.first()) }
    }

    @Test fun installationLensParityCropAndSiteChangesRejectCalibrationAndConsumption() {
        val inputs = samples(); val last = inputs.last(); val model = fit(inputs)
        val changes = listOf(last.observation.copy(identity = identity.copy(physicalLensId = "0/3")),
            last.observation.copy(identity = identity.copy(installationRevision = 5)),
            last.observation.copy(identity = identity.copy(opticsRevision = 3)),
            last.observation.copy(geometry = geometry.copy(parity = ImageParity.NORMAL)),
            last.observation.copy(geometry = geometry.copy(cropWidth = 990)),
            last.observation.copy(geometry = geometry.copy(intrinsicsFingerprint = "unknown")))
        changes.forEach { changed ->
            rejected { fit(inputs.dropLast(1) + last.copy(observation = changed)) }
            assertFalse(model.appliesTo(changed))
        }
        rejected { fit(inputs.dropLast(1) + last.copy(site = site.copy(latitudeDeg = 30.0))) }
        assertFalse(model.invalidated("Phone moved in its holder").appliesTo(last.observation))
    }

    @Test fun persistenceRefitsOriginalEvidenceAndRejectsClaimedMatricesOrResiduals() {
        val original = fit(samples(noise = .04))
        val json = HandEyeCalibrationJson.encode(original)
        val restored = HandEyeCalibrationJson.decode(JSONObject(json.toString()))
        assertEquals(original.source, restored.source)
        assertEquals(original.trainingSamples, restored.trainingSamples)
        assertEquals(original.validationSample, restored.validationSample)
        assertEquals(original.fitResidualDeg, restored.fitResidualDeg, 1e-8)
        assertEquals(original.validationResidualDeg, restored.validationResidualDeg, 1e-8)
        assertTrue(rotationDistanceDeg(original.opticalInImu, restored.opticalInImu) < 1e-8)
        rejected { HandEyeCalibrationJson.decode(JSONObject(json.toString()).put("validationResidualDeg", 0.0)) }
        val matrix = JSONObject(json.toString())
        matrix.put("opticalInImu", HandEyeCalibrationJson.encode(fit(samples(x = imu.first()))).getJSONArray("opticalInImu"))
        rejected { HandEyeCalibrationJson.decode(matrix) }
        val corruptEvidence = JSONObject(json.toString())
        corruptEvidence.getJSONObject("validation").put("sensorElapsedMs", 0)
        rejected { HandEyeCalibrationJson.decode(corruptEvidence) }
        assertFalse(HandEyeCalibrationJson.decode(HandEyeCalibrationJson.encode(original.invalidated("Lens changed"))).valid)
    }

    @Test fun validatedMirrorPropagatesAboutTheMeasuredAxesAndKeepsAllFreshnessGates() {
        val inputs = samples(); val model = fit(inputs); val anchor = inputs.last()
        fun snapshot(p: SkyPose, utc: Long, elapsed: Long) = AttitudeSnapshot(utc,
            Direction3(p.forward.x, p.forward.y, p.forward.z), Direction3(p.up.x, p.up.y, p.up.z), elapsed)
        val alignment = PhonePoseAlignment()
        alignment.align(anchor.observation, snapshot(anchor.sensorPose, anchor.sensorUtcMs, anchor.sensorElapsedMs), site, model)
        val nextSensor = rotation(SkyVector(1.0, 2.0, 3.0), 71.0)
        val nextUtc = anchor.sensorUtcMs + 7000; val nextElapsed = anchor.sensorElapsedMs + 7000
        val result = alignment.pose(snapshot(nextSensor, nextUtc, nextElapsed), nextUtc, nextElapsed)!!
        val expected = SkyPoseFrames.trueEnuToJ2000(world.apply(nextSensor).apply(extrinsic), nextUtc, site)
        assertTrue(rotationDistanceDeg(expected, result) < .0001)
        assertEquals(anchor.observation, alignment.observation)
        assertNull(alignment.pose(snapshot(nextSensor, nextUtc, nextElapsed - 501), nextUtc, nextElapsed))
        assertNull(alignment.pose(snapshot(nextSensor, nextUtc, nextElapsed), nextUtc + 1000, nextElapsed))
        assertNull(alignment.pose(snapshot(nextSensor, nextUtc + 61_000, nextElapsed + 61_000), nextUtc + 61_000, nextElapsed + 61_000))
        alignment.align(anchor.observation, snapshot(anchor.sensorPose, anchor.sensorUtcMs, anchor.sensorElapsedMs), site, model.invalidated("Moved"))
        assertNull(alignment.pose(snapshot(nextSensor, nextUtc, nextElapsed), nextUtc, nextElapsed))
        alignment.align(anchor.observation.copy(geometry = geometry.copy(cropWidth = 990)),
            snapshot(anchor.sensorPose, anchor.sensorUtcMs, anchor.sensorElapsedMs), site, model)
        assertNull(alignment.pose(snapshot(nextSensor, nextUtc, nextElapsed), nextUtc, nextElapsed))
    }

    private fun rotation(axis: SkyVector, degrees: Double): SkyPose {
        val n = axis.unit(); val angle = Math.toRadians(degrees)
        fun apply(v: SkyVector) = (v * cos(angle) + n.cross(v) * sin(angle) + n * (n.dot(v) * (1-cos(angle)))).unit()
        return SkyPose(apply(SkyVector(1.0, 0.0, 0.0)), apply(SkyVector(0.0, 1.0, 0.0)), apply(SkyVector(0.0, 0.0, 1.0)))
    }
}
