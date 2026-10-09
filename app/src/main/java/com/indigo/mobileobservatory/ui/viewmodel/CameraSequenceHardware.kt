package com.indigo.mobileobservatory.ui.viewmodel

import com.indigo.mobileobservatory.camera.CoolingCapable
import com.indigo.mobileobservatory.camera.CameraEnvironmentControlCapable
import com.indigo.mobileobservatory.camera.CameraUsbBandwidthCapable
import com.indigo.mobileobservatory.guide.GuideCalibrationState
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountTrackingRate
import com.indigo.mobileobservatory.mount.MountMotionType
import com.indigo.mobileobservatory.mount.PrecisionGotoPhase
import com.indigo.mobileobservatory.sequence.AutofocusRun
import com.indigo.mobileobservatory.sequence.DeviceUnavailable
import com.indigo.mobileobservatory.sequence.SequenceHardware
import com.indigo.mobileobservatory.sequence.SessionFrame
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

class CameraSequenceHardware(
    private val viewModel: CameraViewModel
) : SequenceHardware {
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

    override suspend fun switchFilter(name: String) {
        val names = viewModel.filterWheelSlotNames.value
        val index = names.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (index < 0) throw DeviceUnavailable("filter $name")
        viewModel.setFilterWheelPosition(index)
        delay(1_500)
    }

    override suspend fun cool(targetC: Double, durationMinutes: Double) {
        val camera = viewModel.cameraManager.activeCamera as? CoolingCapable
            ?: throw DeviceUnavailable("cooler")
        if (camera.coolingInfo.value?.canSetTarget != true) throw DeviceUnavailable("cooler")
        val startedAt = System.currentTimeMillis()
        val safeDurationMinutes = durationMinutes.coerceAtLeast(0.0)
        val durationMs = (safeDurationMinutes * 60_000.0).toLong()
        camera.startCoolDown(
            targetTenths = (targetC * 10.0).roundToInt(),
            durationMinutes = safeDurationMinutes.roundToInt().coerceAtLeast(0)
        )
        val deadline = startedAt + durationMs + 10 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val sensor = viewModel.sensorTempTenths.value / 10.0
            val durationComplete = System.currentTimeMillis() - startedAt >= durationMs
            if (durationComplete && abs(sensor - targetC) <= 1.0) return
            delay(1_000)
        }
    }

    override suspend fun warm(durationMinutes: Double) {
        val camera = viewModel.cameraManager.activeCamera as? CoolingCapable
            ?: throw DeviceUnavailable("cooler")
        if (camera.coolingInfo.value?.canSetTarget != true) throw DeviceUnavailable("cooler")
        val startedAt = System.currentTimeMillis()
        val safeDurationMinutes = durationMinutes.coerceAtLeast(0.0)
        val durationMs = (safeDurationMinutes * 60_000.0).toLong()
        camera.startWarmUp(safeDurationMinutes.roundToInt().coerceAtLeast(0))
        val deadline = startedAt + durationMs + 2 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val durationComplete = System.currentTimeMillis() - startedAt >= durationMs
            if (durationComplete && !camera.coolerOn.value) return
            delay(1_000)
        }
    }

    override suspend fun slew(raHours: Double, decDeg: Double) {
        if (viewModel.mountConnectionState.value !is MountConnectionState.Connected) {
            throw DeviceUnavailable("mount")
        }
        viewModel.gotoMountTarget("sequence", raHours, decDeg, "J2000")
        awaitNear(raHours, decDeg, 180_000L)
    }

    override suspend fun center(raHours: Double, decDeg: Double) {
        val started = viewModel.startPrecisionGoto("sequence", raHours, decDeg, "J2000")
        if (!started) throw DeviceUnavailable("center")
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline) {
            val progress = viewModel.precisionGotoProgress.value
            if (!progress.isActive && progress.phase != PrecisionGotoPhase.IDLE) break
            delay(400)
        }
        val progress = viewModel.precisionGotoProgress.value
        if (progress.phase == PrecisionGotoPhase.FAILED) {
            throw IllegalStateException(progress.message.ifBlank { "center" })
        }
    }

    override suspend fun plateSolve(): Pair<Double, Double> = viewModel.sequencePlateSolve()

    override suspend fun syncMount(raHours: Double, decDeg: Double) {
        viewModel.syncMountToTarget("sequence", raHours, decDeg, "J2000")
    }

    override suspend fun guide(enabled: Boolean, forceCalibration: Boolean) {
        if (enabled && forceCalibration) {
            viewModel.startGuideCalibration()
            val deadline = System.currentTimeMillis() + 180_000L
            while (viewModel.guideCalibrating.value && System.currentTimeMillis() < deadline) {
                delay(500)
            }
            when (viewModel.guideCalibrationState.value) {
                GuideCalibrationState.COMPLETE -> Unit
                GuideCalibrationState.FAILED -> throw IllegalStateException("guide calibration")
                else -> throw IllegalStateException("guide calibration timeout")
            }
        }
        viewModel.setGuideRunning(enabled)
    }

    override suspend fun dither(radiusPx: Double) {
        viewModel.requestGuideDither(radiusPx)
    }

    override suspend fun tracking(mode: Int) {
        val rate = when (mode) {
            1 -> MountTrackingRate.LUNAR
            2 -> MountTrackingRate.SOLAR
            5 -> MountTrackingRate.OFF
            3 -> throw DeviceUnavailable("king tracking")
            else -> MountTrackingRate.SIDEREAL
        }
        viewModel.setMountTrackingRate(rate)
    }

    override suspend fun goHome() {
        if (viewModel.mountConnectionState.value !is MountConnectionState.Connected) {
            throw DeviceUnavailable("mount")
        }
        viewModel.goMountHome()
        awaitMotion(MountMotionType.HOME, 120_000L)
    }

    override suspend fun cover(open: Boolean) {
        if (open) viewModel.openCover() else viewModel.closeCover()
        delay(1_000)
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
        delay(500)
    }

    override suspend fun flatBrightness(value: Int) {
        if (!viewModel.coverConnected.value) throw DeviceUnavailable("flat panel")
        val max = viewModel.calibratorMaxBrightness.value
        if (max <= 0) throw DeviceUnavailable("flat panel")
        viewModel.setCalibratorBrightness(value.coerceIn(0, max))
        delay(500)
    }

    override suspend fun moveFocuser(position: Int) {
        viewModel.moveFocuserAndWait(position)
    }

    override suspend fun rotateTo(angleDeg: Double) {
        if (!viewModel.rotatorConnected.value) throw DeviceUnavailable("rotator")
        viewModel.moveRotatorTo(angleDeg)
        val deadline = System.currentTimeMillis() + 60_000L
        var sawMove = false
        while (System.currentTimeMillis() < deadline) {
            if (viewModel.rotatorMoving.value) sawMove = true
            if (sawMove && !viewModel.rotatorMoving.value) return
            if (!sawMove && abs(viewModel.rotatorAngle.value - angleDeg) < 0.5) return
            delay(200)
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

    private suspend fun awaitNear(raHours: Double, decDeg: Double, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawBusy = false
        while (System.currentTimeMillis() < deadline) {
            val motion = viewModel.mountMotionState.value.type
            if (motion != MountMotionType.IDLE) sawBusy = true
            val coords = viewModel.mountCoordinates.value
            if (coords != null && separationDeg(coords.raHours, coords.decDeg, raHours, decDeg) < 0.2) return
            if (sawBusy && motion == MountMotionType.IDLE && coords != null) return
            delay(400)
        }
        throw IllegalStateException("slew")
    }

    private suspend fun awaitMotion(kind: MountMotionType, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawBusy = false
        while (System.currentTimeMillis() < deadline) {
            val motion = viewModel.mountMotionState.value.type
            if (motion == kind) sawBusy = true
            if (sawBusy && motion == MountMotionType.IDLE) return
            delay(400)
        }
    }

    private fun separationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        var deltaRa = (ra1 - ra2) * 15.0
        while (deltaRa > 180.0) deltaRa -= 360.0
        while (deltaRa < -180.0) deltaRa += 360.0
        val raArc = deltaRa * cos(Math.toRadians(dec2))
        return hypot(raArc, dec1 - dec2)
    }
}
