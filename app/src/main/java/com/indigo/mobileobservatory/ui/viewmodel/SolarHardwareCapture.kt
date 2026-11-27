package com.indigo.mobileobservatory.ui.viewmodel

import android.os.SystemClock
import com.indigo.mobileobservatory.media.MediaUsageRegistry
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.SolarSweepRate
import com.indigo.mobileobservatory.mount.SolarMountMotion
import com.indigo.mobileobservatory.pointing.MountModelRevision
import com.indigo.mobileobservatory.solar.SOLAR_EAST_OR_WEST_START
import com.indigo.mobileobservatory.solar.SOLAR_MAX_TRIAL_MS
import com.indigo.mobileobservatory.solar.SOLAR_MAX_WEST_OFFSET
import com.indigo.mobileobservatory.solar.SOLAR_NORTH_TOLERANCE
import com.indigo.mobileobservatory.solar.SolarPoint
import com.indigo.mobileobservatory.solar.SolarNativeObserver
import com.indigo.mobileobservatory.solar.SolarScanNative
import com.indigo.mobileobservatory.solar.SolarSpectrumAnalysis
import com.indigo.mobileobservatory.solar.SolarSweepTiming
import com.indigo.mobileobservatory.solar.SolarTrialCoverage
import com.indigo.mobileobservatory.solar.SolarTrialMotion
import com.indigo.mobileobservatory.solar.solarSign
import com.indigo.mobileobservatory.stacking.StackSessionKey
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class SolarCaptureSetup(
    val key: StackSessionKey,
    val mount: MountModelRevision,
    val rate: SolarSweepRate,
    val pier: Char
)

internal data class SolarCapturedSweep(
    val motion: SolarTrialMotion,
    val file: File,
    val coverage: SolarTrialCoverage,
    val startedAt: Instant,
    val timing: SolarSweepTiming,
    val analysis: SolarSpectrumAnalysis
)

internal class SolarHardwareCapture(
    private val camera: CameraViewModel,
    val setup: SolarCaptureSetup,
    private val mount: SolarMountMotion,
    private val inspectSpectrum: (File, SolarNativeObserver) -> SolarSpectrumAnalysis
) {
    suspend fun capture(
        direction: MountDirection,
        calibration: SolarTrialMotion?,
        finishTrial: CompletableDeferred<Unit>?,
        onMoving: () -> Unit,
        onSettling: () -> Unit,
        onChecking: () -> Unit
    ): SolarCapturedSweep {
        mount.prepare()
        val recording = camera.startSolarSer(setup.key)
        val startedAt: Instant
        val motion = try {
            awaitBlankFrames(recording)
            recording.cadence.finishBaseline()
            recording.cadence.checkContinuous()
            val start = readPoint()
            check(-start.west * direction.solarSign in SOLAR_EAST_OR_WEST_START && abs(start.north) <= 600.0) {
                "Position the slit just outside the starting limb near the center declination."
            }
            check(calibration == null || calibration.matchesStart(start)) {
                "The mount is outside the calibrated starting position."
            }
            val motionLimit = calibration?.adjustedMotionMs(start) ?: SOLAR_MAX_TRIAL_MS
            startedAt = Instant.now()
            val during = sweep(recording, direction, start, calibration, motionLimit, finishTrial, onMoving)
            onSettling()
            check(withTimeoutOrNull(3_000) {
                while (!mount.isStill()) {
                    camera.checkSolarSer(recording, setup.key)
                    delay(100)
                }
                true
            } == true) { "OnStep did not confirm a stop." }
            mount.verifyTracking()
            val end = readPoint()
            check(calibration == null || calibration.meetsEnd(end)) {
                "The stopped position differs from the calibrated endpoint."
            }
            awaitBlankFrames(recording)
            camera.checkSolarSer(recording, setup.key)
            SolarTrialMotion(direction, start, end, during)
        } finally {
            withContext(NonCancellable) { camera.finishSolarSer(recording) }
        }
        currentCoroutineContext().ensureActive()
        onChecking()
        check(recording.droppedAtStop.get() == 0L) { "The SER writer dropped frames. Reduce the ROI or frame rate." }
        recording.cadence.checkContinuous()
        val recordingJob = checkNotNull(currentCoroutineContext()[Job])
        val observer = object : SolarNativeObserver {
            override fun isCancelled() = !recordingJob.isActive
            override fun onProgress(value: Float) = Unit
        }
        return MediaUsageRegistry.acquire(recording.file, waitForExclusive = false).use {
            val coverage = SolarScanNative.trialCoverage(recording.file, observer)
            check(coverage.hasSlitMargin) {
                "The illuminated slit is too close to the ROI edge (left ${coverage.leftMargin} px, right ${coverage.rightMargin} px)."
            }
            val analysis = inspectSpectrum(recording.file, observer)
            currentCoroutineContext().ensureActive()
            SolarCapturedSweep(motion, recording.file, coverage, startedAt, recording.cadence.timing(coverage), analysis)
        }
    }

    private suspend fun sweep(
        recording: CameraViewModel.SolarSerRecording,
        direction: MountDirection,
        start: SolarPoint,
        calibration: SolarTrialMotion?,
        motionLimit: Long,
        finishTrial: CompletableDeferred<Unit>?,
        onMoving: () -> Unit
    ): Long = coroutineScope {
        var startedAt: Long? = null
        var stoppedAt: Long? = null
        try {
            currentCoroutineContext().ensureActive()
            camera.checkSolarSer(recording, setup.key)
            val started = mount.start(direction, motionLimit.toInt())
            startedAt = started
            onMoving()
            val monitor = launch {
                var lastFrames = camera.recordingFrameCount.value
                var lastFrameAt = SystemClock.elapsedRealtime()
                while (true) {
                    camera.checkSolarSer(recording, setup.key)
                    recording.cadence.checkContinuous()
                    val frames = camera.recordingFrameCount.value
                    if (frames != lastFrames) {
                        lastFrames = frames
                        lastFrameAt = SystemClock.elapsedRealtime()
                    }
                    check(SystemClock.elapsedRealtime() - lastFrameAt < 1_500) { "No new SER frames." }
                    val position = readPoint()
                    check(abs(position.west) <= SOLAR_MAX_WEST_OFFSET &&
                        (position.west - start.west) * direction.solarSign >= -45.0 &&
                        abs(position.north - start.north) <= SOLAR_NORTH_TOLERANCE &&
                        (calibration == null || calibration.withinSweep(position))
                    ) { "The mount left the allowed sweep corridor." }
                    if (SystemClock.elapsedRealtime() - started >= 2_000) {
                        check((position.west - start.west) * direction.solarSign >= mount.rate.minimumTravelArcsec) {
                            "OnStep did not reach sweep speed. Use shared pulse and manual rates."
                        }
                    }
                    delay(100)
                }
            }
            try {
                if (finishTrial == null) {
                    // Let the firmware finish the calibrated pulse. The acknowledgement can
                    // arrive after motion begins, so do not shorten the pulse by its latency.
                    delay(motionLimit)
                } else {
                    val remaining = (motionLimit - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0)
                    check(withTimeoutOrNull(remaining) { finishTrial.await() } != null) {
                        "The trial reached its firmware time limit. Start closer to the limb or select a faster rate."
                    }
                }
            } finally {
                monitor.cancel()
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    stoppedAt = mount.stop(direction)
                } catch (stopFailure: Exception) {
                    runCatching { mount.abort() }
                    throw stopFailure
                }
            }
        }
        if (finishTrial == null) motionLimit else {
            val duration = checkNotNull(stoppedAt) - checkNotNull(startedAt)
            check(duration < motionLimit) { "The trial reached its firmware time limit." }
            duration
        }
    }

    private suspend fun awaitBlankFrames(recording: CameraViewModel.SolarSerRecording) {
        val begin = SystemClock.elapsedRealtime()
        val target = camera.recordingFrameCount.value + 8
        check(withTimeoutOrNull(4_000) {
            do {
                camera.checkSolarSer(recording, setup.key)
                if (camera.recordingFrameCount.value >= target && SystemClock.elapsedRealtime() - begin >= 1_000) {
                    return@withTimeoutOrNull true
                }
                delay(40)
            } while (true)
        } == true) { "Timed out waiting for SER frames." }
    }

    private suspend fun readPoint(): SolarPoint = SolarPoint.from(mount.read(), Instant.now())
}
