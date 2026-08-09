package com.indigo.mobileobservatory.accessories.filterwheel

import android.content.Context
import android.hardware.usb.UsbDevice
import com.indigo.mobileobservatory.accessories.oasis.OasisFilterWheelProtocol
import com.indigo.mobileobservatory.accessories.oasis.OasisHidTransport
import com.indigo.mobileobservatory.accessories.oasis.OasisUsbIds
import com.indigo.mobileobservatory.camera.toupcam.FilterWheelInfo
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

class OasisHidFilterWheelController : FilterWheelController {
    companion object {
        private const val tag = "OasisFilterWheel"
    }

    private val transport = OasisHidTransport()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _wheelInfo = MutableStateFlow<FilterWheelInfo?>(null)
    override val wheelInfo: StateFlow<FilterWheelInfo?> = _wheelInfo.asStateFlow()
    private val _currentPosition = MutableStateFlow(-2)
    override val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()
    private val _slotNames = MutableStateFlow<List<String>>(emptyList())
    override val slotNames: StateFlow<List<String>> = _slotNames.asStateFlow()
    private val _isMoving = MutableStateFlow(false)
    override val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()
    private val _bidirectional = MutableStateFlow(false)
    override val bidirectional: StateFlow<Boolean> = _bidirectional.asStateFlow()

    override fun open(context: Context, usbDevice: UsbDevice): Boolean {
        if (usbDevice.vendorId != OasisUsbIds.vendorId || !OasisUsbIds.isFilterWheel(usbDevice.productId)) {
            return false
        }
        close()
        if (!transport.open(context, usbDevice)) return false
        val slotCount = transport.query(OasisFilterWheelProtocol.commandGetSlotCount)
            ?.payload?.firstOrNull()?.toInt()?.and(0xFF)
            ?.takeIf { it > 0 } ?: run {
            transport.close()
            return false
        }
        val status = readStatus() ?: run {
            transport.close()
            return false
        }
        _wheelInfo.value = FilterWheelInfo("Oasis Filter Wheel", slotCount)
        _slotNames.value = List(slotCount) { index -> readSlotName(index + 1) ?: "${index + 1}" }
        updateStatus(status.state, status.position)
        _isConnected.value = true
        startPolling()
        return true
    }

    override fun close() {
        pollingJob?.cancel()
        pollingJob = null
        transport.close()
        _isConnected.value = false
        _wheelInfo.value = null
        _currentPosition.value = -2
        _slotNames.value = emptyList()
        _isMoving.value = false
    }

    override fun setPosition(position: Int) {
        val slotCount = _wheelInfo.value?.slotCount ?: return
        val target = position.coerceIn(0, slotCount - 1)
        execute {
            transport.command(OasisFilterWheelProtocol.commandSetPosition, byteArrayOf((target + 1).toByte()))
        }
        _isMoving.value = true
    }

    override fun resetWheel() {
        execute { transport.command(OasisFilterWheelProtocol.commandCalibrate, byteArrayOf(0)) }
        _isMoving.value = true
    }

    override fun setSlotName(index: Int, name: String) {
        val names = _slotNames.value.toMutableList()
        if (index !in names.indices) return
        names[index] = name
        _slotNames.value = names
        execute {
            transport.command(
                OasisFilterWheelProtocol.commandSetSlotName,
                OasisFilterWheelProtocol.slotNamePayload(index + 1, name)
            )
        }
    }

    override fun setSlotNames(names: List<String>) {
        names.take(_slotNames.value.size).forEachIndexed(::setSlotName)
    }

    override fun setBidirectional(enabled: Boolean) {
        _bidirectional.value = false
    }

    override fun setSlotCount(count: Int) = Unit

    override fun destroy() {
        close()
        scope.cancel()
    }

    private fun readStatus() = transport.query(OasisFilterWheelProtocol.commandGetStatus)
        ?.let { OasisFilterWheelProtocol.parseStatus(it.payload) }

    private fun readSlotName(slot: Int): String? = transport.query(
        OasisFilterWheelProtocol.commandGetSlotName,
        OasisFilterWheelProtocol.slotNameQueryPayload(slot)
    )?.takeIf { it.payload.firstOrNull()?.toInt()?.and(0xFF) == slot }
        ?.let { OasisFilterWheelProtocol.parseSlotName(it.payload) }

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
                    updateStatus(status.state, status.position)
                }
                delay(if (_isMoving.value) 200 else 500)
            }
        }
    }

    private fun updateStatus(state: Int, protocolPosition: Int) {
        _isMoving.value = state == OasisFilterWheelProtocol.statusMoving ||
            state == OasisFilterWheelProtocol.statusCalibrating ||
            state == OasisFilterWheelProtocol.statusBenchmarking
        _currentPosition.value = if (protocolPosition > 0) protocolPosition - 1 else -2
    }

    private fun execute(action: () -> Boolean) {
        if (!_isConnected.value) return
        scope.launch {
            if (!action()) FileLogger.w(tag, "Oasis filter wheel command failed")
        }
    }
}
