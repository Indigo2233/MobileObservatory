package com.indigo.mobileobservatory.accessories.cover

import kotlinx.coroutines.flow.StateFlow

enum class CoverState { NOT_PRESENT, CLOSED, MOVING, OPEN, UNKNOWN, ERROR }
enum class CalibratorState { NOT_PRESENT, OFF, NOT_READY, READY, UNKNOWN, ERROR }

interface CoverCalibratorController {
    val isConnected: StateFlow<Boolean>
    val coverState: StateFlow<CoverState>
    val calibratorState: StateFlow<CalibratorState>
    val brightness: StateFlow<Int>
    val maxBrightness: StateFlow<Int>
    val deviceInfo: StateFlow<String?>
    val lastError: StateFlow<String?>

    fun openCover()
    fun closeCover()
    fun halt()
    fun setBrightness(value: Int)
    fun calibratorOff()
    fun close()
    fun destroy()
}
