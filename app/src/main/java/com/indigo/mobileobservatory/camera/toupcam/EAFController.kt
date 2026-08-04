package com.indigo.mobileobservatory.camera.toupcam

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EAFInfo(
    val name: String,
    val minPosition: Int,
    val maxPosition: Int,
    val maxStep: Int,
    val stepSize: Int,
    val fineStep: Int = 1,
    val coarseStep: Int = 5,
    val direction: Int = 0,
    val backlashSteps: Int = 0,
    val backlashDirection: Int = 0
)

class EAFController {

    companion object {
        private const val TAG = "EAF"
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _eafInfo = MutableStateFlow<EAFInfo?>(null)
    val eafInfo: StateFlow<EAFInfo?> = _eafInfo.asStateFlow()

    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private val _temperature = MutableStateFlow<Float?>(null)
    val temperature: StateFlow<Float?> = _temperature.asStateFlow()

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var backlashPending = false

    fun open(context: Context, usbDevice: UsbDevice): Boolean {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val connection = usbManager.openDevice(usbDevice) ?: run {
                Log.e(TAG, "Failed to open USB device for EAF")
                return false
            }

            val fd = connection.fileDescriptor
            val vid = usbDevice.vendorId
            val pid = usbDevice.productId

            if (!ToupcamJni.eafOpen(fd, vid, pid)) {
                Log.e(TAG, "Failed to open EAF via SDK")
                connection.close()
                return false
            }

            val modelName = ToupcamJni.getModelName(vid, pid) ?: "ToupTek EAF"
            var minPos = ToupcamJni.eafGet(ToupcamJni.AAF_RANGEMIN)
            var maxPos = ToupcamJni.eafGet(ToupcamJni.AAF_RANGEMAX)
            val maxStep = ToupcamJni.eafGet(ToupcamJni.AAF_GETMAXSTEP).let { if (it <= 0) 100000 else it }
            val stepSize = ToupcamJni.eafGet(ToupcamJni.AAF_GETSTEPSIZE).coerceAtLeast(1)
            val position = ToupcamJni.eafGet(ToupcamJni.AAF_GETPOSITION)
            val tempRaw = ToupcamJni.eafGet(ToupcamJni.AAF_GETTEMP)
            val direction = ToupcamJni.eafGet(ToupcamJni.AAF_GETDIRECTION).let { if (it < 0) 0 else it }
            val fineStep = ToupcamJni.eafGet(ToupcamJni.AAF_GETFINE).let { if (it <= 0) stepSize else it }
            val coarseStep = fineStep * 5

            if (minPos < 0 || maxPos < 0 || maxPos <= minPos) {
                Log.w(TAG, "RANGEMIN/RANGEMAX unsupported (got $minPos..$maxPos), falling back to 0..$maxStep")
                minPos = 0
                maxPos = maxStep
            }

            _eafInfo.value = EAFInfo(modelName, minPos, maxPos, maxStep, stepSize,
                fineStep, coarseStep, direction)
            _currentPosition.value = position
            _temperature.value = if (tempRaw != -1) tempRaw / 10f else null
            _isConnected.value = true

            startPolling()
            Log.i(TAG, "EAF connected: $modelName, range=$minPos..$maxPos, maxStep=$maxStep, step=$stepSize, fine=$fineStep, dir=$direction, pos=$position")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "EAF open failed", e)
            return false
        }
    }

    fun close() {
        pollingJob?.cancel()
        pollingJob = null
        if (_isConnected.value) {
            ToupcamJni.eafClose()
            _isConnected.value = false
            _eafInfo.value = null
            _currentPosition.value = 0
            _isMoving.value = false
            _temperature.value = null
            Log.i(TAG, "EAF disconnected")
        }
    }

    fun moveTo(position: Int) {
        if (!_isConnected.value) return
        val info = _eafInfo.value ?: return
        val clamped = position.coerceIn(info.minPosition, info.maxPosition)
        Log.i(TAG, "moveTo: requested=$position, clamped=$clamped, range=${info.minPosition}..${info.maxPosition}")

        val blSteps = info.backlashSteps
        if (blSteps > 0) {
            val currentPos = _currentPosition.value
            val movingInward = clamped < currentPos
            val shouldCompensate = (info.backlashDirection == 0 && movingInward) ||
                    (info.backlashDirection == 1 && !movingInward)

            if (shouldCompensate) {
                val overshoot = if (movingInward) {
                    (clamped - blSteps).coerceAtLeast(info.minPosition)
                } else {
                    (clamped + blSteps).coerceAtMost(info.maxPosition)
                }
                Log.i(TAG, "Backlash: overshoot to $overshoot then to $clamped (bl=$blSteps dir=${info.backlashDirection})")
                val ok1 = ToupcamJni.eafSet(ToupcamJni.AAF_SETPOSITION, overshoot)
                if (ok1) {
                    _isMoving.value = true
                    backlashPending = true
                    scope.launch {
                        while (_isMoving.value && backlashPending) {
                            delay(100)
                            val moving = ToupcamJni.eafGet(ToupcamJni.AAF_ISMOVING)
                            if (moving == 0) {
                                backlashPending = false
                                val ok2 = ToupcamJni.eafSet(ToupcamJni.AAF_SETPOSITION, clamped)
                                if (ok2) Log.i(TAG, "Backlash: now moving to final $clamped")
                                else Log.e(TAG, "Backlash: final move to $clamped failed")
                                break
                            }
                        }
                    }
                }
                return
            }
        }

        val ok = ToupcamJni.eafSet(ToupcamJni.AAF_SETPOSITION, clamped)
        if (ok) {
            _isMoving.value = true
            Log.i(TAG, "Moving to position $clamped")
        } else {
            Log.e(TAG, "AAF_SETPOSITION($clamped) failed")
        }
    }

    fun moveRelative(steps: Int) {
        if (!_isConnected.value) return
        val target = _currentPosition.value + steps
        moveTo(target)
    }

    fun halt() {
        if (!_isConnected.value) return
        backlashPending = false
        ToupcamJni.eafSet(ToupcamJni.AAF_HALT, 0)
        _isMoving.value = false
        Log.i(TAG, "Halted")
    }

    fun setZero() {
        if (!_isConnected.value) return
        ToupcamJni.eafSet(ToupcamJni.AAF_SETZERO, 0)
        _currentPosition.value = 0
        Log.i(TAG, "Zero position set")
    }

    fun setDirection(dir: Int) {
        if (!_isConnected.value) return
        val ok = ToupcamJni.eafSet(ToupcamJni.AAF_SETDIRECTION, dir)
        if (ok) {
            val info = _eafInfo.value ?: return
            _eafInfo.value = info.copy(direction = dir)
            Log.i(TAG, "Direction set to $dir")
        } else {
            Log.e(TAG, "AAF_SETDIRECTION($dir) failed")
        }
    }

    fun setFineStep(step: Int) {
        if (!_isConnected.value) return
        val clamped = step.coerceAtLeast(1)
        val ok = ToupcamJni.eafSet(ToupcamJni.AAF_SETFINE, clamped)
        if (ok) {
            val coarse = clamped * 5
            ToupcamJni.eafSet(ToupcamJni.AAF_SETCOARSE, coarse)
            val info = _eafInfo.value ?: return
            _eafInfo.value = info.copy(fineStep = clamped, coarseStep = coarse)
            Log.i(TAG, "FineStep=$clamped, CoarseStep=$coarse")
        } else {
            Log.e(TAG, "AAF_SETFINE($clamped) failed")
        }
    }

    fun setMaxStep(maxStep: Int) {
        if (!_isConnected.value) return
        val clamped = maxStep.coerceAtLeast(100)
        val ok = ToupcamJni.eafSet(ToupcamJni.AAF_SETMAXSTEP, clamped)
        if (ok) {
            val info = _eafInfo.value ?: return
            _eafInfo.value = info.copy(maxStep = clamped, maxPosition = clamped)
            Log.i(TAG, "MaxStep set to $clamped")
        } else {
            Log.e(TAG, "AAF_SETMAXSTEP($clamped) failed")
        }
    }

    fun setBacklash(steps: Int, direction: Int) {
        val info = _eafInfo.value ?: return
        _eafInfo.value = info.copy(backlashSteps = steps.coerceAtLeast(0), backlashDirection = direction)
        Log.i(TAG, "Backlash compensation: steps=$steps direction=$direction")
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && _isConnected.value) {
                try {
                    val pos = ToupcamJni.eafGet(ToupcamJni.AAF_GETPOSITION)
                    if (pos == -1) {
                        Log.w(TAG, "EAF handle lost, disconnecting")
                        withContext(Dispatchers.Main) { close() }
                        break
                    }
                    _currentPosition.value = pos

                    val moving = ToupcamJni.eafGet(ToupcamJni.AAF_ISMOVING)
                    _isMoving.value = moving != 0

                    val tempRaw = ToupcamJni.eafGet(ToupcamJni.AAF_GETTEMP)
                    if (tempRaw != -1) _temperature.value = tempRaw / 10f
                } catch (e: Throwable) {
                    Log.e(TAG, "EAF polling error: ${e.message}")
                    withContext(Dispatchers.Main) { close() }
                    break
                }
                delay(if (_isMoving.value) 200 else 1000)
            }
        }
    }

    fun destroy() {
        close()
        scope.cancel()
    }
}
