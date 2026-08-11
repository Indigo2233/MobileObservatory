package com.indigo.mobileobservatory.accessories.rotator

import kotlinx.coroutines.flow.StateFlow

interface RotatorController {
    val isConnected: StateFlow<Boolean>
    val angle: StateFlow<Double>
    val positionSteps: StateFlow<Int>
    val isMoving: StateFlow<Boolean>
    val stepsPerDegree: StateFlow<Int>
    /** True when steps/degree was read from the control board (I#). */
    val stepsPerDegreeFromBoard: StateFlow<Boolean>
    val supportsStepConfiguration: StateFlow<Boolean>
    val reversed: StateFlow<Boolean>
    val hold: StateFlow<Boolean>
    val supportsHold: StateFlow<Boolean>
    val deviceInfo: StateFlow<String?>
    val lastError: StateFlow<String?>

    fun moveTo(angleDegrees: Double)
    fun moveRelative(deltaDegrees: Double)
    fun halt()
    fun home()
    fun setZero()
    fun setReversed(reversed: Boolean)
    fun setHold(enabled: Boolean)
    fun setStepsPerDegree(value: Int)
    fun close()
    fun destroy()
}
