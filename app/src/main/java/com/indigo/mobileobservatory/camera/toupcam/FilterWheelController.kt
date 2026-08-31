package com.indigo.mobileobservatory.camera.toupcam

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FilterWheelInfo(
    val name: String,
    val slotCount: Int
)

internal fun validFilterWheelSlotCount(reportedSlotCount: Int): Int? =
    reportedSlotCount.takeIf { it in 1..16 }

class FilterWheelController {

    companion object {
        private const val TAG = "FilterWheel"
        private const val PREFS_NAME = "filter_wheel_prefs"
        private const val KEY_SLOT_NAMES = "slot_names"
        private const val KEY_BIDIRECTIONAL = "bidirectional"
        private val DEFAULT_SLOT_NAMES = listOf("L", "R", "G", "B", "R+", "UV", "CH4", "R+610")
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _wheelInfo = MutableStateFlow<FilterWheelInfo?>(null)
    val wheelInfo: StateFlow<FilterWheelInfo?> = _wheelInfo.asStateFlow()

    private val _currentPosition = MutableStateFlow(-2)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    private val _slotNames = MutableStateFlow<List<String>>(emptyList())
    val slotNames: StateFlow<List<String>> = _slotNames.asStateFlow()

    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private val _bidirectional = MutableStateFlow(true)
    val bidirectional: StateFlow<Boolean> = _bidirectional.asStateFlow()

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var prefs: SharedPreferences? = null
    private var usbConnection: UsbDeviceConnection? = null

    fun open(context: Context, usbDevice: UsbDevice): Boolean {
        try {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val connection = usbManager.openDevice(usbDevice) ?: run {
                Log.e(TAG, "Failed to open USB device for filter wheel")
                return false
            }

            val fd = connection.fileDescriptor
            val vid = usbDevice.vendorId
            val pid = usbDevice.productId

            if (!ToupcamJni.fwOpen(fd, vid, pid)) {
                Log.e(TAG, "Failed to open filter wheel via SDK")
                connection.close()
                return false
            }
            usbConnection = connection

            val modelName = ToupcamJni.getModelName(vid, pid) ?: "ToupTek Filter Wheel"
            val rawSlotCount = ToupcamJni.fwGetSlotCount()
            val slotCount = validFilterWheelSlotCount(rawSlotCount) ?: run {
                Log.e(TAG, "Invalid filter wheel slot count reported by SDK: $rawSlotCount")
                ToupcamJni.fwClose()
                connection.close()
                usbConnection = null
                return false
            }
            Log.i(TAG, "Using SDK-reported slot count: $slotCount")
            
            val position = ToupcamJni.fwGetPosition()

            _wheelInfo.value = FilterWheelInfo(modelName, slotCount)
            _currentPosition.value = position
            _isMoving.value = position == -1
            
            val savedNames = prefs?.getString(KEY_SLOT_NAMES, null)
            _slotNames.value = if (savedNames != null) {
                val names = savedNames.split("|")
                List(slotCount) { i -> names.getOrElse(i) { DEFAULT_SLOT_NAMES.getOrElse(i) { "${i + 1}" } } }
            } else {
                List(slotCount) { i -> DEFAULT_SLOT_NAMES.getOrElse(i) { "${i + 1}" } }
            }
            
            _bidirectional.value = prefs?.getBoolean(KEY_BIDIRECTIONAL, true) ?: true
            _isConnected.value = true

            startPolling()
            Log.i(TAG, "Filter wheel connected: $modelName, $slotCount slots, position=$position, bidirectional=${_bidirectional.value}")
            return true
        } catch (e: Throwable) {
            ToupcamJni.fwClose()
            usbConnection?.close()
            usbConnection = null
            Log.e(TAG, "Filter wheel open failed", e)
            return false
        }
    }

    fun close() {
        pollingJob?.cancel()
        pollingJob = null
        if (usbConnection != null) {
            ToupcamJni.fwClose()
        }
        if (_isConnected.value) {
            _isConnected.value = false
            _wheelInfo.value = null
            _currentPosition.value = -2
            _isMoving.value = false
            Log.i(TAG, "Filter wheel disconnected")
        }
        usbConnection?.close()
        usbConnection = null
    }

    fun setPosition(pos: Int) {
        if (!_isConnected.value) return
        val info = _wheelInfo.value ?: return
        val targetPos = pos.coerceIn(0, info.slotCount - 1)
        val useBidirectional = _bidirectional.value
        
        if (ToupcamJni.fwSetPosition(targetPos, useBidirectional)) {
            _isMoving.value = true
            Log.i(TAG, "Moving to position $targetPos (bidirectional=$useBidirectional)")
        } else {
            Log.w(TAG, "Failed to set position $targetPos")
        }
    }

    fun resetWheel() {
        if (!_isConnected.value) return
        if (ToupcamJni.fwSetPosition(-1)) {
            _isMoving.value = true
            Log.i(TAG, "Resetting filter wheel")
        }
    }

    fun setSlotName(index: Int, name: String) {
        val names = _slotNames.value.toMutableList()
        if (index in names.indices) {
            names[index] = name
            _slotNames.value = names
            saveSlotNames(names)
        }
    }

    fun setSlotNames(names: List<String>) {
        _slotNames.value = names
        saveSlotNames(names)
    }

    fun setBidirectional(enabled: Boolean) {
        _bidirectional.value = enabled
        prefs?.edit()?.putBoolean(KEY_BIDIRECTIONAL, enabled)?.apply()
        Log.i(TAG, "Bidirectional rotation: $enabled")
    }

    fun setSlotCount(count: Int) {
        if (count < 1 || count > 16) return
        if (ToupcamJni.fwSetSlotCount(count)) {
            val info = _wheelInfo.value
            if (info != null) {
                _wheelInfo.value = info.copy(slotCount = count)
                _slotNames.value = List(count) { i -> 
                    _slotNames.value.getOrElse(i) { DEFAULT_SLOT_NAMES.getOrElse(i) { "${i + 1}" } }
                }
            }
            Log.i(TAG, "Slot count set to: $count")
        } else {
            Log.w(TAG, "Failed to set slot count to: $count")
        }
    }

    private fun saveSlotNames(names: List<String>) {
        prefs?.edit()?.putString(KEY_SLOT_NAMES, names.joinToString("|"))?.apply()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && _isConnected.value) {
                try {
                    val pos = ToupcamJni.fwGetPosition()
                    if (pos == -2) {
                        Log.w(TAG, "Filter wheel handle lost, disconnecting")
                        withContext(Dispatchers.Main) { close() }
                        break
                    }
                    _currentPosition.value = pos
                    _isMoving.value = pos == -1
                } catch (e: Throwable) {
                    Log.e(TAG, "Polling error: ${e.message}")
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
