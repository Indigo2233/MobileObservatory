package com.indigo.mobileobservatory.accessories.focuser

import com.indigo.mobileobservatory.camera.toupcam.EAFController
import com.indigo.mobileobservatory.camera.toupcam.EAFInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

interface FocuserController {
    val isConnected: StateFlow<Boolean>
    val eafInfo: StateFlow<EAFInfo?>
    val currentPosition: StateFlow<Int>
    val isMoving: StateFlow<Boolean>
    val temperature: StateFlow<Float?>

    fun moveTo(position: Int)
    fun moveRelative(steps: Int)
    fun halt()
    fun setZero()
    fun setDirection(direction: Int)
    fun setFineStep(step: Int)
    fun setCoarseStep(step: Int)
    fun setMaxStep(maxStep: Int)
    fun setBacklash(steps: Int, direction: Int)
    fun close()
    fun destroy()
}

class ToupTekFocuserAdapter(
    private val controller: EAFController
) : FocuserController {
    override val isConnected = controller.isConnected
    override val eafInfo = controller.eafInfo
    override val currentPosition = controller.currentPosition
    override val isMoving = controller.isMoving
    override val temperature = controller.temperature

    override fun moveTo(position: Int) = controller.moveTo(position)
    override fun moveRelative(steps: Int) = controller.moveRelative(steps)
    override fun halt() = controller.halt()
    override fun setZero() = controller.setZero()
    override fun setDirection(direction: Int) = controller.setDirection(direction)
    override fun setFineStep(step: Int) = controller.setFineStep(step)
    override fun setCoarseStep(step: Int) = controller.setCoarseStep(step)
    override fun setMaxStep(maxStep: Int) = controller.setMaxStep(maxStep)
    override fun setBacklash(steps: Int, direction: Int) =
        controller.setBacklash(steps, direction)
    override fun close() = controller.close()
    override fun destroy() = controller.destroy()
}

class FocuserControllerRouter(
    scope: CoroutineScope,
    private val toupTek: ToupTekFocuserAdapter,
    private val efucoser: EFucoserSerialFocuserController,
    private val geminiEaf: GeminiEafSerialFocuserController,
    private val oasis: OasisHidFocuserController
) : FocuserController {
    private val active = MutableStateFlow<FocuserController?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val isConnected = active
        .flatMapLatest { it?.isConnected ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val eafInfo = active
        .flatMapLatest { it?.eafInfo ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val currentPosition = active
        .flatMapLatest { it?.currentPosition ?: flowOf(0) }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val isMoving = active
        .flatMapLatest { it?.isMoving ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val temperature = active
        .flatMapLatest { it?.temperature ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun useToupTek() {
        if (active.value !== toupTek) {
            active.value?.close()
            active.value = toupTek
        }
    }

    fun useEfucoser() {
        if (active.value !== efucoser) {
            active.value?.close()
            active.value = efucoser
        }
    }

    fun useGeminiEaf() {
        if (active.value !== geminiEaf) {
            active.value?.close()
            active.value = geminiEaf
        }
    }

    fun useOasis() {
        if (active.value !== oasis) {
            active.value?.close()
            active.value = oasis
        }
    }

    override fun moveTo(position: Int) = active.value?.moveTo(position) ?: Unit
    override fun moveRelative(steps: Int) = active.value?.moveRelative(steps) ?: Unit
    override fun halt() = active.value?.halt() ?: Unit
    override fun setZero() = active.value?.setZero() ?: Unit
    override fun setDirection(direction: Int) = active.value?.setDirection(direction) ?: Unit
    override fun setFineStep(step: Int) = active.value?.setFineStep(step) ?: Unit
    override fun setCoarseStep(step: Int) = active.value?.setCoarseStep(step) ?: Unit
    override fun setMaxStep(maxStep: Int) = active.value?.setMaxStep(maxStep) ?: Unit
    override fun setBacklash(steps: Int, direction: Int) =
        active.value?.setBacklash(steps, direction) ?: Unit

    override fun close() {
        active.value?.close()
        active.value = null
    }

    override fun destroy() {
        active.value = null
        toupTek.destroy()
        efucoser.destroy()
        geminiEaf.destroy()
        oasis.destroy()
    }
}
