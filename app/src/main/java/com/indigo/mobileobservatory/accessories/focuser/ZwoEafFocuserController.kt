package com.indigo.mobileobservatory.accessories.focuser

import android.content.Context
import android.hardware.usb.UsbDevice
import com.indigo.mobileobservatory.accessories.zwo.ZwoEafHidTransport
import com.indigo.mobileobservatory.accessories.zwo.ZwoEafIdentity
import com.indigo.mobileobservatory.accessories.zwo.ZwoEafProtocol
import com.indigo.mobileobservatory.accessories.zwo.ZwoEafStatus
import com.indigo.mobileobservatory.accessories.zwo.ZwoEafUsbIds
import com.indigo.mobileobservatory.camera.toupcam.EAFInfo
import com.indigo.mobileobservatory.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ZwoEafFocuserController : FocuserController {
    companion object {
        private const val tag = "ZWO-EAF"
    }

    private val transport = ZwoEafHidTransport()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val operationLock = Any()
    private var pollingJob: Job? = null
    private var identity: ZwoEafIdentity? = null
    private var lastStatus: ZwoEafStatus? = null
    private var fineStep = ZwoEafProtocol.defaultFineStep
    private var coarseStep = ZwoEafProtocol.defaultCoarseStep
    private var backlashDirection = 0

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _eafInfo = MutableStateFlow<EAFInfo?>(null)
    override val eafInfo: StateFlow<EAFInfo?> = _eafInfo.asStateFlow()
    private val _currentPosition = MutableStateFlow(0)
    override val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()
    private val _isMoving = MutableStateFlow(false)
    override val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()
    private val _temperature = MutableStateFlow<Float?>(null)
    override val temperature: StateFlow<Float?> = _temperature.asStateFlow()

    fun open(context: Context, device: UsbDevice): Boolean {
        if (!ZwoEafUsbIds.isEaf(device.vendorId, device.productId)) return false
        close()
        if (!transport.open(context, device)) return false
        val opened = synchronized(operationLock) {
            val identityReport = transport.query(ZwoEafProtocol.selectorIdentity)
            val serialReport = transport.query(ZwoEafProtocol.selectorSerial)
            val detected = if (identityReport != null && serialReport != null) {
                ZwoEafProtocol.parseIdentity(identityReport, serialReport)
            } else null
            if (detected == null) {
                FileLogger.w(tag, "Identity query failed")
                false
            } else if (!ZwoEafProtocol.supportsVerifiedProtocol(detected)) {
                FileLogger.w(
                    tag,
                    "Unsupported model=${detected.model} firmware=${detected.firmware} serial=${detected.serial}"
                )
                false
            } else {
                val status = readStatusLocked()
                if (status == null) {
                    FileLogger.w(tag, "Initial status query failed")
                    false
                } else {
                    identity = detected
                    updateStatus(status)
                    updateInfo(status)
                    true
                }
            }
        }
        if (!opened) {
            transport.close()
            return false
        }
        _isConnected.value = true
        startPolling()
        val detected = identity ?: return false
        FileLogger.i(
            tag,
            "Connected model=${detected.model} firmware=${detected.firmware} " +
                "serial=${detected.serial} position=${_currentPosition.value} " +
                "maxStep=${_eafInfo.value?.maxStep}"
        )
        return true
    }

    override fun moveTo(position: Int) {
        execute("moveTo target=$position") { status ->
            val target = position.coerceIn(0, status.maxStep)
            if (status.moving || status.handControllerActive || status.errorCode != 0) {
                FileLogger.w(
                    tag,
                    "Move rejected moving=${status.moving} hand=${status.handControllerActive} " +
                        "error=${status.errorCode}"
                )
                return@execute false
            }
            val success = transport.command(
                ZwoEafProtocol.moveCommand(status, target),
                "move target=$target"
            )
            if (success) _isMoving.value = true
            success
        }
    }

    override fun moveRelative(steps: Int) {
        if (steps == 0) return
        execute("moveRelative steps=$steps") { status ->
            val target = (status.position.toLong() + steps.toLong())
                .coerceIn(0L, status.maxStep.toLong())
                .toInt()
            val success = transport.command(
                ZwoEafProtocol.moveCommand(status, target),
                "relative steps=$steps target=$target"
            )
            if (success) _isMoving.value = true
            success
        }
    }

    override fun halt() {
        execute("halt", allowMoving = true) { status ->
            if (status.handControllerActive) {
                FileLogger.w(tag, "Halt rejected while hand controller is active")
                return@execute false
            }
            transport.command(ZwoEafProtocol.stopCommand(status), "halt")
        }
    }

    override fun setZero() {
        execute("setZero") { status ->
            val success = transport.command(
                ZwoEafProtocol.resetPositionCommand(status, 0),
                "reset position=0"
            )
            if (success) _currentPosition.value = 0
            success
        }
    }

    override fun setDirection(direction: Int) {
        updateSettings("reverse=$direction") { status ->
            ZwoEafProtocol.settingsCommand(status, reverse = direction != 0)
        }
    }

    override fun setFineStep(step: Int) {
        fineStep = step.coerceAtLeast(1)
        _eafInfo.value = _eafInfo.value?.copy(fineStep = fineStep)
    }

    override fun setCoarseStep(step: Int) {
        coarseStep = step.coerceAtLeast(1)
        _eafInfo.value = _eafInfo.value?.copy(coarseStep = coarseStep)
    }

    override fun setMaxStep(maxStep: Int) {
        updateSettings("maxStep=$maxStep") { status ->
            val value = maxStep.coerceIn(status.position.coerceAtLeast(1), ZwoEafProtocol.maxTravel)
            ZwoEafProtocol.settingsCommand(status, maxStep = value)
        }
    }

    override fun setBacklash(steps: Int, direction: Int) {
        backlashDirection = direction.coerceIn(0, 1)
        updateSettings("backlash=$steps direction=$backlashDirection") { status ->
            ZwoEafProtocol.settingsCommand(status, backlash = steps.coerceIn(0, 0xFF))
        }
    }

    override fun close() {
        pollingJob?.cancel()
        pollingJob = null
        synchronized(operationLock) { transport.close() }
        identity = null
        lastStatus = null
        _isConnected.value = false
        _eafInfo.value = null
        _currentPosition.value = 0
        _isMoving.value = false
        _temperature.value = null
    }

    override fun destroy() {
        close()
        scope.cancel()
    }

    private fun execute(
        description: String,
        allowMoving: Boolean = false,
        action: (ZwoEafStatus) -> Boolean
    ) {
        if (!_isConnected.value) return
        scope.launch {
            val success = synchronized(operationLock) {
                val status = readStatusLocked() ?: return@synchronized false
                if (!allowMoving && status.moving) {
                    FileLogger.w(tag, "$description rejected while device is moving")
                    return@synchronized false
                }
                runCatching { action(status) }
                    .onFailure { FileLogger.e(tag, "$description failed", it) }
                    .getOrDefault(false)
            }
            if (!success) FileLogger.w(tag, "$description command failed or was rejected")
        }
    }

    private fun updateSettings(
        description: String,
        command: (ZwoEafStatus) -> ByteArray
    ) {
        execute(description) { status ->
            if (status.handControllerActive || status.errorCode != 0) return@execute false
            if (!transport.command(command(status), description)) return@execute false
            val actual = readStatusLocked() ?: return@execute false
            updateStatus(actual)
            updateInfo(actual)
            true
        }
    }

    private fun readStatusLocked(): ZwoEafStatus? {
        val report = transport.query(ZwoEafProtocol.selectorStatus) ?: return null
        val status = ZwoEafProtocol.parseStatus(report)
        if (status == null) FileLogger.w(tag, "Invalid status report")
        return status
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var failures = 0
            while (isActive && _isConnected.value) {
                val status = synchronized(operationLock) { readStatusLocked() }
                if (status == null) {
                    failures++
                    if (failures >= 5) {
                        FileLogger.w(tag, "Status polling failed five times; disconnecting")
                        close()
                        break
                    }
                } else {
                    failures = 0
                    updateStatus(status)
                    if (_eafInfo.value?.maxStep != status.maxStep ||
                        _eafInfo.value?.direction != (if (status.reverse) 1 else 0) ||
                        _eafInfo.value?.backlashSteps != status.backlash
                    ) updateInfo(status)
                }
                delay(if (_isMoving.value) 200 else 1_000)
            }
        }
    }

    private fun updateStatus(status: ZwoEafStatus) {
        lastStatus = status
        _currentPosition.value = status.position
        _isMoving.value = status.moving
        _temperature.value = status.temperatureC
        if (status.errorCode != 0) {
            FileLogger.w(tag, "Hardware error=${status.errorCode} position=${status.position}")
        }
    }

    private fun updateInfo(status: ZwoEafStatus) {
        _eafInfo.value = EAFInfo(
            name = "ZWO ${identity?.model ?: "EAF"}",
            minPosition = 0,
            maxPosition = status.maxStep,
            maxStep = status.maxStep,
            stepSize = 1,
            fineStep = fineStep,
            coarseStep = coarseStep,
            direction = if (status.reverse) 1 else 0,
            backlashSteps = status.backlash,
            backlashDirection = backlashDirection
        )
    }
}
