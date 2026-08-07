package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.flow.StateFlow

data class CoolingInfo(
    val hasTec: Boolean,
    val canSetTarget: Boolean,
    val targetMinTenths: Int,
    val targetMaxTenths: Int,
    val tecVoltageMaxTenths: Int = 0
)

data class TempHistoryPoint(val timestampMs: Long, val sensorTenths: Int, val powerPct: Float)

/**
 * Optional cooling capability shared by ToupTek and Player One (and future brands).
 * [CameraViewModel] binds cooling UI through this interface, never a concrete camera class.
 */
interface CoolingCapable {
    val coolingInfo: StateFlow<CoolingInfo?>
    val coolerOn: StateFlow<Boolean>
    val targetTempTenths: StateFlow<Int>
    val sensorTempTenths: StateFlow<Int>
    val tecVoltageTenths: StateFlow<Int>
    val coolingPowerPct: StateFlow<Float>
    val tempHistory: StateFlow<List<TempHistoryPoint>>
    val rampStatus: StateFlow<String>

    fun setCoolerOn(on: Boolean)
    fun setTargetTemperature(tenthsDegC: Int)
    fun startCoolDown(targetTenths: Int, durationMinutes: Int)
    fun startWarmUp(durationMinutes: Int)
    fun stopRamp()
}
