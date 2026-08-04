package com.indigo.mobileobservatory.accessories.cover

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class CoverCalibratorControllerRouter(
    scope: CoroutineScope,
    private val dlc: DlcSerialCoverCalibratorAdapter,
    private val gemini: GeminiFlatpanelSerialAdapter
) : CoverCalibratorController {
    private val active = MutableStateFlow<CoverCalibratorController?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val isConnected = active
        .flatMapLatest { it?.isConnected ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val coverState = active
        .flatMapLatest { it?.coverState ?: flowOf(CoverState.UNKNOWN) }
        .stateIn(scope, SharingStarted.Eagerly, CoverState.UNKNOWN)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val calibratorState = active
        .flatMapLatest { it?.calibratorState ?: flowOf(CalibratorState.UNKNOWN) }
        .stateIn(scope, SharingStarted.Eagerly, CalibratorState.UNKNOWN)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val brightness = active
        .flatMapLatest { it?.brightness ?: flowOf(0) }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val maxBrightness = active
        .flatMapLatest { it?.maxBrightness ?: flowOf(255) }
        .stateIn(scope, SharingStarted.Eagerly, 255)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val deviceInfo = active
        .flatMapLatest { it?.deviceInfo ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val lastError = active
        .flatMapLatest { it?.lastError ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val connectedDeviceId: Int?
        get() = when (active.value) {
            dlc -> dlc.connectedDeviceId
            gemini -> gemini.connectedDeviceId
            else -> null
        }

    fun useDlc() {
        if (active.value !== dlc) {
            active.value?.close()
            active.value = dlc
        }
    }

    fun useGemini() {
        if (active.value !== gemini) {
            active.value?.close()
            active.value = gemini
        }
    }

    override fun openCover() = active.value?.openCover() ?: Unit
    override fun closeCover() = active.value?.closeCover() ?: Unit
    override fun halt() = active.value?.halt() ?: Unit
    override fun setBrightness(value: Int) = active.value?.setBrightness(value) ?: Unit
    override fun calibratorOff() = active.value?.calibratorOff() ?: Unit

    override fun close() {
        active.value?.close()
        active.value = null
    }

    override fun destroy() {
        active.value = null
        dlc.destroy()
        gemini.destroy()
    }
}
