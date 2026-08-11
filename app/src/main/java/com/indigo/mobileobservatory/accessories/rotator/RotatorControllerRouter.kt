package com.indigo.mobileobservatory.accessories.rotator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

enum class RotatorAdapterKind { ECAA, WANDERER }

class RotatorControllerRouter(
    scope: CoroutineScope,
    private val ecaa: EcaaSerialRotatorAdapter,
    private val wanderer: WandererSerialRotatorAdapter
) : RotatorController {
    private val active = MutableStateFlow<RotatorController?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val isConnected = active.flatMapLatest { it?.isConnected ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val angle = active.flatMapLatest { it?.angle ?: flowOf(0.0) }
        .stateIn(scope, SharingStarted.Eagerly, 0.0)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val positionSteps = active.flatMapLatest { it?.positionSteps ?: flowOf(0) }
        .stateIn(scope, SharingStarted.Eagerly, 0)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val isMoving = active.flatMapLatest { it?.isMoving ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val stepsPerDegree = active.flatMapLatest { it?.stepsPerDegree ?: flowOf(100) }
        .stateIn(scope, SharingStarted.Eagerly, 100)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val stepsPerDegreeFromBoard = active.flatMapLatest {
        it?.stepsPerDegreeFromBoard ?: flowOf(false)
    }.stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val supportsStepConfiguration = active.flatMapLatest {
        it?.supportsStepConfiguration ?: flowOf(false)
    }.stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val reversed = active.flatMapLatest { it?.reversed ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val hold = active.flatMapLatest { it?.hold ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val supportsHold = active.flatMapLatest { it?.supportsHold ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val deviceInfo = active.flatMapLatest { it?.deviceInfo ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val lastError = active.flatMapLatest { it?.lastError ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val connectedDeviceId: Int?
        get() = when (active.value) {
            ecaa -> ecaa.connectedDeviceId
            wanderer -> wanderer.connectedDeviceId
            else -> null
        }

    val activeKind: RotatorAdapterKind?
        get() = when (active.value) {
            ecaa -> RotatorAdapterKind.ECAA
            wanderer -> RotatorAdapterKind.WANDERER
            else -> null
        }

    fun useEcaa() = use(ecaa)
    fun useWanderer() = use(wanderer)

    override fun moveTo(angleDegrees: Double) = active.value?.moveTo(angleDegrees) ?: Unit
    override fun moveRelative(deltaDegrees: Double) = active.value?.moveRelative(deltaDegrees) ?: Unit
    override fun halt() = active.value?.halt() ?: Unit
    override fun home() = active.value?.home() ?: Unit
    override fun setZero() = active.value?.setZero() ?: Unit
    override fun setReversed(reversed: Boolean) = active.value?.setReversed(reversed) ?: Unit
    override fun setHold(enabled: Boolean) = active.value?.setHold(enabled) ?: Unit
    override fun setStepsPerDegree(value: Int) = active.value?.setStepsPerDegree(value) ?: Unit

    override fun close() {
        active.value?.close()
        active.value = null
    }

    override fun destroy() {
        active.value = null
        ecaa.destroy()
        wanderer.destroy()
    }

    private fun use(controller: RotatorController) {
        if (active.value !== controller) {
            active.value?.close()
            active.value = controller
        }
    }
}
