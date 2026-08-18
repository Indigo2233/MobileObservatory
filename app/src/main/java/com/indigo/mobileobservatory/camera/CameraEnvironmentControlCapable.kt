package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.flow.StateFlow

/**
 * Optional anti-dew heater and cooling-fan controls exposed by some camera SDKs.
 * Levels are native integer values in [0, maxLevel]; maxLevel == 1 means a plain
 * on/off switch. [CameraViewModel] binds these through this interface only.
 */
interface CameraEnvironmentControlCapable {
    val heaterSupported: Boolean
    val heaterLevel: StateFlow<Int>
    val heaterMaxLevel: Int

    val fanSupported: Boolean
    val fanLevel: StateFlow<Int>
    val fanMaxLevel: Int

    fun setHeaterLevel(level: Int)
    fun setFanLevel(level: Int)
}
