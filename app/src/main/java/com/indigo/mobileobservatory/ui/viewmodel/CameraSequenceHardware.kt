package com.indigo.mobileobservatory.ui.viewmodel

import com.indigo.mobileobservatory.camera.CoolingCapable
import com.indigo.mobileobservatory.mount.MountConnectionState
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
import kotlinx.coroutines.delay

class CameraSequenceHardware(
    private val viewModel: CameraViewModel
) : SequenceHardware {
    override suspend fun takeExposure(seconds: Double, gain: Int, offset: Int, destDir: File): SessionFrame =
        viewModel.captureSequenceLight(seconds, gain, offset, destDir)

    override suspend fun switchFilter(name: String) {
        val names = viewModel.filterWheelSlotNames.value
        val index = names.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (index < 0) throw DeviceUnavailable("filter $name")
        viewModel.setFilterWheelPosition(index)
        delay(1_500)
    }

    override suspend fun cool(targetC: Double) {
        val camera = viewModel.cameraManager.activeCamera as? CoolingCapable
            ?: throw DeviceUnavailable("cooler")
        camera.setCoolerOn(true)
        camera.setTargetTemperature((targetC * 10.0).toInt())
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val sensor = viewModel.sensorTempTenths.value / 10.0
            if (abs(sensor - targetC) <= 1.0) return
            delay(1_000)
        }
    }

    override suspend fun warm() {
        val camera = viewModel.cameraManager.activeCamera as? CoolingCapable
            ?: throw DeviceUnavailable("cooler")
        camera.startWarmUp(10)
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

    override suspend fun guide(enabled: Boolean) {
        viewModel.setGuideRunning(enabled)
    }

    override suspend fun dither(radiusPx: Double) {
        viewModel.requestGuideDither(radiusPx)
    }

    override suspend fun tracking(enabled: Boolean) {
        viewModel.setMountTracking(enabled)
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

    override suspend fun moveFocuser(position: Int) {
        viewModel.moveFocuserAndWait(position)
    }

    override suspend fun autofocus(destDir: File): AutofocusRun =
        viewModel.runSequenceAutofocus(destDir)

    override suspend fun waitUntil(epochMillis: Long) {
        while (System.currentTimeMillis() < epochMillis) delay(1_000)
    }

    override fun guidingLocked(): Boolean = viewModel.guideLocked()

    override fun raHours(): Double? = viewModel.mountCoordinates.value?.raHours

    override fun decDeg(): Double? = viewModel.mountCoordinates.value?.decDeg

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
