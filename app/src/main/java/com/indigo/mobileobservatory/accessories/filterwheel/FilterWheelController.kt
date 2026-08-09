package com.indigo.mobileobservatory.accessories.filterwheel

import android.content.Context
import android.hardware.usb.UsbDevice
import com.indigo.mobileobservatory.camera.toupcam.FilterWheelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

interface FilterWheelController {
    val isConnected: StateFlow<Boolean>
    val wheelInfo: StateFlow<FilterWheelInfo?>
    val currentPosition: StateFlow<Int>
    val slotNames: StateFlow<List<String>>
    val isMoving: StateFlow<Boolean>
    val bidirectional: StateFlow<Boolean>

    fun open(context: Context, usbDevice: UsbDevice): Boolean
    fun close()
    fun setPosition(position: Int)
    fun resetWheel()
    fun setSlotName(index: Int, name: String)
    fun setSlotNames(names: List<String>)
    fun setBidirectional(enabled: Boolean)
    fun setSlotCount(count: Int)
    fun destroy()
}

class ToupTekFilterWheelAdapter(
    private val controller: com.indigo.mobileobservatory.camera.toupcam.FilterWheelController
) : FilterWheelController {
    override val isConnected = controller.isConnected
    override val wheelInfo = controller.wheelInfo
    override val currentPosition = controller.currentPosition
    override val slotNames = controller.slotNames
    override val isMoving = controller.isMoving
    override val bidirectional = controller.bidirectional

    override fun open(context: Context, usbDevice: UsbDevice) = controller.open(context, usbDevice)
    override fun close() = controller.close()
    override fun setPosition(position: Int) = controller.setPosition(position)
    override fun resetWheel() = controller.resetWheel()
    override fun setSlotName(index: Int, name: String) = controller.setSlotName(index, name)
    override fun setSlotNames(names: List<String>) = controller.setSlotNames(names)
    override fun setBidirectional(enabled: Boolean) = controller.setBidirectional(enabled)
    override fun setSlotCount(count: Int) = controller.setSlotCount(count)
    override fun destroy() = controller.destroy()
}

class FilterWheelControllerRouter(
    scope: CoroutineScope,
    private val toupTek: ToupTekFilterWheelAdapter,
    private val oasis: OasisHidFilterWheelController
) : FilterWheelController {
    private val active = MutableStateFlow<FilterWheelController?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val isConnected = active.flatMapLatest { it?.isConnected ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val wheelInfo = active.flatMapLatest { it?.wheelInfo ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val currentPosition = active.flatMapLatest { it?.currentPosition ?: flowOf(-2) }
        .stateIn(scope, SharingStarted.Eagerly, -2)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val slotNames = active.flatMapLatest { it?.slotNames ?: flowOf(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val isMoving = active.flatMapLatest { it?.isMoving ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val bidirectional = active.flatMapLatest { it?.bidirectional ?: flowOf(true) }
        .stateIn(scope, SharingStarted.Eagerly, true)

    fun useToupTek() = use(toupTek)
    fun useOasis() = use(oasis)

    override fun open(context: Context, usbDevice: UsbDevice): Boolean = false
    override fun close() {
        active.value?.close()
        active.value = null
    }
    override fun setPosition(position: Int) = active.value?.setPosition(position) ?: Unit
    override fun resetWheel() = active.value?.resetWheel() ?: Unit
    override fun setSlotName(index: Int, name: String) = active.value?.setSlotName(index, name) ?: Unit
    override fun setSlotNames(names: List<String>) = active.value?.setSlotNames(names) ?: Unit
    override fun setBidirectional(enabled: Boolean) = active.value?.setBidirectional(enabled) ?: Unit
    override fun setSlotCount(count: Int) = active.value?.setSlotCount(count) ?: Unit
    override fun destroy() {
        active.value = null
        toupTek.destroy()
        oasis.destroy()
    }

    private fun use(controller: FilterWheelController) {
        if (active.value !== controller) {
            active.value?.close()
            active.value = controller
        }
    }
}
