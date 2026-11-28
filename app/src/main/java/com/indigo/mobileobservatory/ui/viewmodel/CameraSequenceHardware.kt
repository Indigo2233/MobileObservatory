package com.indigo.mobileobservatory.ui.viewmodel

import com.indigo.mobileobservatory.camera.CoolingCapable
import com.indigo.mobileobservatory.camera.CameraEnvironmentControlCapable
import com.indigo.mobileobservatory.camera.CameraUsbBandwidthCapable
import com.indigo.mobileobservatory.guide.GuideCalibrationState
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountMotionStopped
import com.indigo.mobileobservatory.mount.MountTrackingRate
import com.indigo.mobileobservatory.accessories.cover.CoverState
import com.indigo.mobileobservatory.accessories.cover.CalibratorState
import android.os.SystemClock
import com.indigo.mobileobservatory.mount.PrecisionGotoPhase
import com.indigo.mobileobservatory.sequence.AutofocusRun
import com.indigo.mobileobservatory.sequence.DeviceUnavailable
import com.indigo.mobileobservatory.sequence.SequenceHardware
import com.indigo.mobileobservatory.sequence.SessionFrame
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class CameraSequenceHardware(
    private val viewModel: CameraViewModel
) : SequenceHardware {
    override suspend fun awaitExposureReady() = viewModel.awaitPendingDither()

    override suspend fun takeExposure(
        seconds: Double,
        gain: Int,
        offset: Int,
        destDir: File,
        binning: Int,
        imageType: String
    ): SessionFrame = viewModel.captureSequenceLight(
        exposureSeconds = seconds,
        gain = gain,
        offset = offset,
        destDir = destDir,
        binning = binning,
        imageType = imageType
    )

    override suspend fun switchFilter(name: String) = switchFilter(name, null)

    override suspend fun switchFilter(name: String, fallbackPosition: Int?) {
        if (!viewModel.filterWheelConnected.value) throw DeviceUnavailable("filter wheel")
        val names = viewModel.filterWheelSlotNames.value
        val index = names.indexOfFirst { name.isNotBlank() && it.equals(name, ignoreCase = true) }.takeIf { it >= 0 }
            ?: fallbackPosition?.takeIf { it in names.indices } ?: throw DeviceUnavailable("filter $name")
        viewModel.setFilterWheelPosition(index)
        awaitDevice("filter wheel", 60_000, { viewModel.filterWheelConnected.value }) {
            !viewModel.filterWheelMoving.value && viewModel.filterWheelPosition.value == index
        }
    }

    override suspend fun cool(targetC: Double, durationMinutes: Double) {
        val camera = viewModel.cameraManager.activeCamera as? CoolingCapable
            ?: throw DeviceUnavailable("cooler")
        if (camera.coolingInfo.value?.canSetTarget != true) throw DeviceUnavailable("cooler")
        val startedAt = SystemClock.elapsedRealtime()
        val safeDurationMinutes = durationMinutes.coerceAtLeast(0.0)
        val durationMs = (safeDurationMinutes * 60_000.0).toLong()
        camera.startCoolDown(
            targetTenths = (targetC * 10.0).roundToInt(),
            durationMinutes = safeDurationMinutes.roundToInt().coerceAtLeast(0)
        )
        val deadline = startedAt + durationMs + 10 * 60_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (viewModel.cameraManager.activeCamera !== camera) throw DeviceUnavailable("cooler")
            val sensor = viewModel.sensorTempTenths.value / 10.0
            val durationComplete = SystemClock.elapsedRealtime() - startedAt >= durationMs
            if (durationComplete && abs(sensor - targetC) <= 1.0) return
            delay(1_000)
        }
        throw DeviceUnavailable("Cooling target timed out")
    }

    override suspend fun warm(durationMinutes: Double) {
        val camera = viewModel.cameraManager.activeCamera as? CoolingCapable
            ?: throw DeviceUnavailable("cooler")
        if (camera.coolingInfo.value?.canSetTarget != true) throw DeviceUnavailable("cooler")
        val startedAt = SystemClock.elapsedRealtime()
        val safeDurationMinutes = durationMinutes.coerceAtLeast(0.0)
        val durationMs = (safeDurationMinutes * 60_000.0).toLong()
        camera.startWarmUp(safeDurationMinutes.roundToInt().coerceAtLeast(0))
        val deadline = startedAt + durationMs + 2 * 60_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val durationComplete = SystemClock.elapsedRealtime() - startedAt >= durationMs
            if (viewModel.cameraManager.activeCamera !== camera) throw DeviceUnavailable("cooler")
            if (durationComplete && !camera.coolerOn.value) return
            delay(1_000)
        }
        throw DeviceUnavailable("Camera warming timed out")
    }

    override suspend fun slew(raHours: Double, decDeg: Double) {
        if (viewModel.mountConnectionState.value !is MountConnectionState.Connected) {
            throw DeviceUnavailable("mount")
        }
        viewModel.slewSequenceMount(raHours, decDeg)
    }

    override suspend fun center(raHours: Double, decDeg: Double) {
        val ownerToken = UUID.randomUUID().toString()
        val previous = viewModel.precisionGotoProgress.value
        val started = viewModel.startPrecisionGoto("sequence", raHours, decDeg, "J2000", ownerToken = ownerToken)
        if (!started) throw DeviceUnavailable(viewModel.precisionGotoProgress.value.message.ifBlank { "center" })
        try {
            val deadline = SystemClock.elapsedRealtime() + 300_000
            while (viewModel.mountMotionState.value.ownerToken == ownerToken) {
                check(SystemClock.elapsedRealtime() < deadline) { "Center timed out" }
                delay(200)
            }
            val progress = viewModel.precisionGotoProgress.value
            if (viewModel.mountConnectionState.value !is MountConnectionState.Connected ||
                viewModel.cameraManager.activeCamera?.isOpen?.value != true
            ) throw DeviceUnavailable(progress.message.ifBlank { "Center device disconnected" })
            if (progress === previous || progress.isActive || progress.phase == PrecisionGotoPhase.CANCELLED) {
                throw MountMotionStopped()
            }
            check(progress.phase == PrecisionGotoPhase.SUCCEEDED) { progress.message.ifBlank { "Center failed" } }
        } finally {
            withContext(NonCancellable) { viewModel.stopSequenceCenter(ownerToken) }
        }
    }

    override suspend fun plateSolve(): Pair<Double, Double> = viewModel.sequencePlateSolve()

    override suspend fun syncMount(raHours: Double, decDeg: Double) {
        viewModel.syncSequenceMount(raHours, decDeg)
    }

    override suspend fun guide(enabled: Boolean, forceCalibration: Boolean) {
        if (enabled && forceCalibration) {
            try {
                viewModel.startGuideCalibration()
                val deadline = SystemClock.elapsedRealtime() + 180_000L
                while (viewModel.guideCalibrating.value && SystemClock.elapsedRealtime() < deadline) {
                    delay(500)
                }
                when (viewModel.guideCalibrationState.value) {
                    GuideCalibrationState.COMPLETE -> Unit
                    GuideCalibrationState.FAILED -> throw IllegalStateException("guide calibration")
                    else -> throw IllegalStateException("guide calibration timeout")
                }
            } finally {
                if (viewModel.guideCalibrating.value) viewModel.setGuideRunning(false)
            }
        }
        viewModel.setGuideRunning(enabled)
        if (viewModel.guideRunning.value != enabled) throw DeviceUnavailable(viewModel.guideStatus.value)
    }

    override suspend fun dither(radiusPx: Double) {
        if (!guidingLocked()) throw DeviceUnavailable("guide lock")
        viewModel.ditherSequenceAndWait(radiusPx)
    }

    override suspend fun tracking(mode: Int) {
        val rate = when (mode) {
            1 -> MountTrackingRate.LUNAR
            2 -> MountTrackingRate.SOLAR
            5 -> MountTrackingRate.OFF
            3 -> throw DeviceUnavailable("king tracking")
            0 -> MountTrackingRate.SIDEREAL
            else -> throw DeviceUnavailable("tracking mode $mode")
        }
        viewModel.trackSequenceMount(rate)
    }

    override suspend fun goHome() {
        if (viewModel.mountConnectionState.value !is MountConnectionState.Connected) {
            throw DeviceUnavailable("mount")
        }
        viewModel.homeSequenceMount()
    }

    override suspend fun cover(open: Boolean) {
        if (!viewModel.coverConnected.value) throw DeviceUnavailable("cover")
        val target = if (open) CoverState.OPEN else CoverState.CLOSED
        if (open) viewModel.openCover() else viewModel.closeCover()
        try {
            awaitDevice("cover", 90_000, { viewModel.coverConnected.value }) {
                val state = viewModel.coverState.value
                if (state == CoverState.ERROR || state == CoverState.NOT_PRESENT) throw DeviceUnavailable("cover")
                state == target
            }
        } finally {
            if (viewModel.coverState.value == CoverState.MOVING) viewModel.haltCover()
        }
    }

    override suspend fun dewHeater(on: Boolean) {
        val camera = viewModel.cameraManager.activeCamera as? CameraEnvironmentControlCapable
            ?: throw DeviceUnavailable("dew heater")
        if (!camera.heaterSupported || camera.heaterMaxLevel <= 0) throw DeviceUnavailable("dew heater")
        camera.setHeaterLevel(if (on) camera.heaterMaxLevel else 0)
    }

    override suspend fun usbLimit(value: Int) {
        val camera = viewModel.cameraManager.activeCamera as? CameraUsbBandwidthCapable
            ?: throw DeviceUnavailable("usb limit")
        val range = camera.usbBandwidthRange ?: throw DeviceUnavailable("usb limit")
        if (!camera.setUsbBandwidth(value.coerceIn(range))) throw IllegalStateException("usb limit")
    }

    override suspend fun flatLight(on: Boolean) {
        if (!viewModel.coverConnected.value) throw DeviceUnavailable("flat panel")
        val max = viewModel.calibratorMaxBrightness.value
        if (max <= 0) throw DeviceUnavailable("flat panel")
        if (on) {
            val current = viewModel.calibratorBrightness.value
            viewModel.setCalibratorBrightness(current.takeIf { it > 0 } ?: max.coerceAtLeast(1))
        } else {
            viewModel.calibratorOff()
        }
        awaitDevice("flat panel", 30_000, { viewModel.coverConnected.value }) {
            viewModel.calibratorState.value == if (on) CalibratorState.READY else CalibratorState.OFF
        }
    }

    override suspend fun flatBrightness(value: Int) {
        if (!viewModel.coverConnected.value) throw DeviceUnavailable("flat panel")
        val max = viewModel.calibratorMaxBrightness.value
        if (max <= 0) throw DeviceUnavailable("flat panel")
        require(value in 0..max) { "Flat brightness outside 0..$max" }
        viewModel.setCalibratorBrightness(value)
        awaitDevice("flat brightness", 30_000, { viewModel.coverConnected.value }) {
            viewModel.calibratorBrightness.value == value &&
                viewModel.calibratorState.value in setOf(CalibratorState.READY, CalibratorState.OFF)
        }
    }

    override suspend fun moveFocuser(position: Int) {
        viewModel.moveFocuserAndWait(position)
    }

    override suspend fun rotateTo(angleDeg: Double) {
        if (!viewModel.rotatorConnected.value) throw DeviceUnavailable("rotator")
        viewModel.moveRotatorTo(angleDeg)
        try {
            awaitDevice("rotator", 60_000, { viewModel.rotatorConnected.value }) {
                val difference = Math.IEEEremainder(viewModel.rotatorAngle.value - angleDeg, 360.0)
                !viewModel.rotatorMoving.value && abs(difference) < 0.5
            }
        } finally {
            if (viewModel.rotatorMoving.value) viewModel.haltRotator()
        }
    }

    override fun isRotatorConnected(): Boolean = viewModel.rotatorConnected.value

    override suspend fun autofocus(destDir: File): AutofocusRun =
        viewModel.runSequenceAutofocus(destDir)

    override suspend fun waitUntil(epochMillis: Long) {
        while (System.currentTimeMillis() < epochMillis) delay(1_000)
    }

    override fun guidingLocked(): Boolean = viewModel.guideLocked()

    override fun raHours(): Double? = viewModel.mountCoordinates.value?.raHours

    override fun decDeg(): Double? = viewModel.mountCoordinates.value?.decDeg

    override fun focuserPosition(): Int = viewModel.eafPosition.value

    private suspend fun awaitDevice(
        name: String, timeoutMs: Long, connected: () -> Boolean, reached: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (!connected()) throw DeviceUnavailable("$name disconnected")
            if (reached()) return
            if (SystemClock.elapsedRealtime() >= deadline) throw DeviceUnavailable("$name timed out")
            delay(200)
        }
    }
}
