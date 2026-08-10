package com.indigo.mobileobservatory.accessories.focuser

import android.content.Context
import android.hardware.usb.UsbDevice
import com.indigo.mobileobservatory.accessories.oasis.OasisFocuserConfig
import com.indigo.mobileobservatory.accessories.oasis.OasisFocuserGeneration
import com.indigo.mobileobservatory.accessories.oasis.OasisFocuserProtocol
import com.indigo.mobileobservatory.accessories.oasis.OasisHidProtocol
import com.indigo.mobileobservatory.accessories.oasis.OasisHidTransport
import com.indigo.mobileobservatory.accessories.oasis.OasisUsbIds
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

class OasisHidFocuserController : FocuserController {
    companion object {
        private const val tag = "OasisFocuser"
        private const val defaultMaxPosition = 1_000_000
    }

    private val transport = OasisHidTransport()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var generation = OasisFocuserGeneration.FIRST
    private var config = OasisFocuserConfig(
        maxStep = defaultMaxPosition,
        backlash = 0,
        speed = 0,
        reverseDirection = 0,
        backlashDirection = 0,
        beepOnMove = 0,
        beepOnStartup = 0,
        bluetoothOn = 0
    )

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
        if (device.vendorId != OasisUsbIds.vendorId || !OasisUsbIds.isFocuser(device.productId)) {
            FileLogger.w(tag, "Unsupported USB device VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
            return false
        }
        close()
        if (!transport.open(context, device)) {
            FileLogger.w(tag, "HID transport open failed")
            return false
        }
        val detectedGeneration = detectGeneration() ?: run {
            FileLogger.w(tag, "Neither Rose nor first-generation status query succeeded")
            transport.close()
            return false
        }
        generation = detectedGeneration
        config = readConfig() ?: config
        val status = readStatus() ?: run {
            FileLogger.w(tag, "Initial status query failed for generation=$generation")
            transport.close()
            return false
        }
        updateInfo()
        updateStatus(status.position, status.moving, status.temperatureC)
        _isConnected.value = true
        startPolling()
        FileLogger.i(tag, "Connected generation=$generation position=${status.position} maxStep=${config.maxStep}")
        return true
    }

    override fun moveTo(position: Int) {
        val maxPosition = _eafInfo.value?.maxPosition ?: config.maxStep
        val target = position.coerceIn(0, maxPosition.coerceAtLeast(0))
        execute { transport.command(OasisFocuserProtocol.commandMoveTo, OasisHidProtocol.int32(target)) }
        _isMoving.value = true
    }

    override fun moveRelative(steps: Int) {
        if (steps == 0) return
        FileLogger.d(tag, "Relative move steps=$steps position=${_currentPosition.value}")
        execute {
            transport.command(
                OasisFocuserProtocol.commandMoveRelative,
                OasisFocuserProtocol.relativeMovePayload(steps)
            )
        }
        _isMoving.value = true
    }

    override fun halt() {
        execute { transport.command(OasisFocuserProtocol.commandStop) }
    }

    override fun setZero() {
        execute { transport.command(OasisFocuserProtocol.commandSetZero) }
        _currentPosition.value = 0
    }

    override fun setDirection(direction: Int) {
        updateConfig(OasisFocuserProtocol.maskReverseDirection) {
            it.copy(reverseDirection = direction.coerceIn(0, 1))
        }
    }

    override fun setFineStep(step: Int) {
        val fineStep = step.coerceAtLeast(1)
        _eafInfo.value = _eafInfo.value?.copy(
            fineStep = fineStep,
            coarseStep = fineStep * 5
        )
    }

    override fun setCoarseStep(step: Int) {
        val coarseStep = step.coerceAtLeast(1)
        _eafInfo.value = _eafInfo.value?.copy(coarseStep = coarseStep)
    }

    override fun setMaxStep(maxStep: Int) {
        updateConfig(OasisFocuserProtocol.maskMaxStep) {
            it.copy(maxStep = maxStep.coerceAtLeast(1))
        }
    }

    override fun setBacklash(steps: Int, direction: Int) {
        updateConfig(OasisFocuserProtocol.maskBacklash or OasisFocuserProtocol.maskBacklashDirection) {
            it.copy(
                backlash = steps.coerceAtLeast(0),
                backlashDirection = direction.coerceIn(0, 1)
            )
        }
    }

    override fun close() {
        pollingJob?.cancel()
        pollingJob = null
        transport.close()
        _isConnected.value = false
        _eafInfo.value = null
        _isMoving.value = false
        _temperature.value = null
    }

    override fun destroy() {
        close()
        scope.cancel()
    }

    private fun detectGeneration(): OasisFocuserGeneration? {
        val rose = transport.query(OasisFocuserProtocol.commandGetStatusRose)
        if (rose?.payload?.size == OasisFocuserProtocol.expectedStatusLength(OasisFocuserGeneration.ROSE)) {
            FileLogger.i(tag, "Detected Rose status length=${rose.payload.size}")
            return OasisFocuserGeneration.ROSE
        }
        FileLogger.w(tag, "Rose status response length=${rose?.payload?.size}")
        val first = transport.query(OasisFocuserProtocol.commandGetStatusFirst)
        if (first?.payload?.size == OasisFocuserProtocol.expectedStatusLength(OasisFocuserGeneration.FIRST)) {
            FileLogger.i(tag, "Detected first-generation status length=${first.payload.size}")
            return OasisFocuserGeneration.FIRST
        }
        FileLogger.w(tag, "First-generation status response length=${first?.payload?.size}")
        return null
    }

    private fun readConfig(): OasisFocuserConfig? {
        val response = transport.query(OasisFocuserProtocol.configCommand(generation)) ?: return null
        return OasisFocuserProtocol.parseConfig(response.payload)
    }

    private fun readStatus() = transport.query(OasisFocuserProtocol.statusCommand(generation))
        ?.let { OasisFocuserProtocol.parseStatus(it.payload) }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var failures = 0
            while (isActive && _isConnected.value) {
                val status = readStatus()
                if (status == null) {
                    failures++
                    if (failures >= 3) {
                        FileLogger.w(tag, "Status polling failed repeatedly")
                        close()
                        break
                    }
                } else {
                    failures = 0
                    updateStatus(status.position, status.moving, status.temperatureC)
                }
                delay(if (_isMoving.value) 200 else 500)
            }
        }
    }

    private fun updateStatus(position: Int, moving: Boolean, temperatureC: Float?) {
        _currentPosition.value = position.coerceAtLeast(0)
        _isMoving.value = moving
        _temperature.value = temperatureC
    }

    private fun updateConfig(mask: Int, transform: (OasisFocuserConfig) -> OasisFocuserConfig) {
        val updated = transform(config)
        execute {
            transport.command(
                OasisFocuserProtocol.setConfigCommand(generation),
                OasisFocuserProtocol.configPayload(generation, mask, updated)
            )
        }
        config = updated
        updateInfo()
    }

    private fun updateInfo() {
        val existing = _eafInfo.value
        _eafInfo.value = EAFInfo(
            name = if (generation == OasisFocuserGeneration.ROSE) "Oasis Focuser Rose" else "Oasis Focuser",
            minPosition = 0,
            maxPosition = config.maxStep.coerceAtLeast(1),
            maxStep = config.maxStep.coerceAtLeast(1),
            stepSize = 1,
            fineStep = existing?.fineStep ?: OasisFocuserProtocol.defaultFineStep,
            coarseStep = existing?.coarseStep ?: OasisFocuserProtocol.defaultCoarseStep,
            direction = config.reverseDirection,
            backlashSteps = config.backlash,
            backlashDirection = config.backlashDirection
        )
    }

    private fun execute(action: () -> Boolean) {
        if (!_isConnected.value) return
        scope.launch {
            if (!action()) FileLogger.w(tag, "Oasis focuser command failed")
        }
    }
}
