package com.indigo.mobileobservatory.camera.qhyccd

import android.util.Log

object QhyccdJni {

    private const val TAG = "QhyccdJni"

    const val CONTROL_GAIN = 6
    const val CONTROL_OFFSET = 7
    const val CONTROL_EXPOSURE = 8
    const val CONTROL_SPEED = 9
    const val CONTROL_TRANSFERBIT = 10
    const val CONTROL_USBTRAFFIC = 12
    const val CONTROL_CURTEMP = 14
    const val CONTROL_CURPWM = 15
    const val CONTROL_COOLER = 18
    const val CONTROL_WBR = 2
    const val CONTROL_WBB = 3
    const val CONTROL_WBG = 4
    const val CAM_BIN1X1MODE = 21
    const val CAM_BIN2X2MODE = 22
    const val CAM_BIN3X3MODE = 23
    const val CAM_BIN4X4MODE = 24

    const val QHYCCD_SUCCESS = 0
    const val QHYCCD_ERROR = 0xFFFFFFFF.toInt()

    const val BAYER_GB = 1
    const val BAYER_GR = 2
    const val BAYER_BG = 3
    const val BAYER_RG = 4

    const val QHY_VID = 0x1618  // 5656 decimal

    // PIDs of cameras with firmware already loaded (FX3 loaded state)
    // Cameras with these PIDs are ready for SDK operations
    // Format: 0xCxxx = firmware loaded, 0x0xxx = bootloader mode (needs firmware upload)
    // Reference: https://github.com/indigo-astronomy/indigo/blob/master/indigo_drivers/ccd_qhy/bin_externals/qhyccd/85-qhyccd.rules
    private val FX3_LOADED_PIDS = setOf(
        // Legacy cameras (FX2/FX2LP based, firmware already in EEPROM)
        0x0921, 0x8311, 0x6741, 0x6941, 0x6005, 0x1001, 0x1201, 0x8301, 0x6003,
        0x1111, 0x8141, 0x2851, 0x025a, 0x6001, 0x0931, 0x1611, 0x296d, 0x4023,
        0x2971, 0xa618, 0x1501, 0x1651, 0x8321, 0x1621, 0x1671, 0x8303, 0x1631,
        0x29a1, 0x29a3, 0x2951, 0x00f1, 0x0941, 0x8323, 0x1623, 0x0237,
        0x0186, 0x6953, 0x8614, 0x1601, 0x1633, 0x9001, 0x2021, 0x6061, 0x6063,

        // QHY5III series (FX3 based) - firmware loaded PIDs (0xCxxx pattern)
        0xC175,  // QHY5III174
        0xC179,  // QHY5III178 (also 0x0179)
        0xC185,  // QHY5III185
        0xC224,  // QHY5III224
        0xC225,  // QHY5III224
        0xC290,  // QHY5III290
        0xC291,  // QHY5III290
        0xC236,  // QHY5III236
        0xC462,  // QHY5III462
        0xC464,  // QHY5III464
        0xC482,  // QHY5III482
        0xC485,  // QHY5III485
        0xC334,  // QHY5III334
        0xC415,  // QHY5III415
        0xC200,  // QHY5III200
        0xC585,  // QHY5III585
        0xC678,  // QHY5III678
        0xC715,  // QHY5III715
        0xC568,  // QHY5III568

        // Cooled cameras - firmware loaded PIDs
        0xC174,  // QHY174
        0xC178,  // QHY178
        0xC183,  // QHY183
        0xD183,  // QHY183A
        0xC163,  // QHY163
        0xC165,  // QHY165
        0xC167,  // QHY168
        0xC168,  // QHY168
        0xC247,  // QHY247
        0xC128,  // QHY128
        0xC12A,  // QHY128PRO
        0xC12B,  // QHY128PRO
        0xC294,  // QHY294
        0xC295,  // QHY294
        0xC296,  // QHY294PRO
        0xC297,  // QHY294PRO
        0xC367,  // QHY367
        0xC368,  // QHY367
        0xC369,  // QHY367PRO
        0xC36A,  // QHY367PRO
        0xC268,  // QHY268
        0xC269,  // QHY268
        0xC533,  // QHY533
        0xC534,  // QHY533
        0xC492,  // QHY492
        0xC493,  // QHY492
        0xC495,  // QHY492MT
        0xC550,  // QHY550
        0xC551,  // QHY550
        0xC530,  // QHY530
        0xC531,  // QHY530

        // Large format cameras
        0xC600,  // QHY600
        0xC601,  // QHY600
        0xC603,  // QHY600
        0xC411,  // QHY411
        0xC412,  // QHY411
        0xC413,  // QHY411ERIS
        0xC414,  // QHY411ERIS
        0xC461,  // QHY461
        0x4201,  // QHY42
        0x4203,  // QHY42PRO
        0x4041,  // QHY4040
        0x4043,  // QHY4040PRO

        // Scientific cameras
        0xC990,  // QHY990
        0xC991,  // QHY990/991
        0xC992,  // QHY992
        0xD991,  // QHY991
        0xD992,  // QHY991/992
        0xC487,  // QHY487
        0xC488,  // QHY487
        0x9702,  // QHY9701
        0xC192,  // QHY1920
        0xC193,  // QHY1920

        // miniCAM8
        0xC587,  // miniCAM8
        0xC588,  // miniCAM8

        // Other models
        0x0175, 0x0179, 0x0201, 0x0205, 0x0335, 0x0342, 0x0343, 0x0344, 0x0345,
        0x0410, 0x0411, 0x0416, 0x0432, 0x0433, 0x0463, 0x0465, 0x0483, 0x0486,
        0x0569, 0x0586, 0x0588, 0x0679, 0x0716, 0x0768, 0x0769, 0x807C,
        0x5301, 0xA815, 0xC248, 0xC254, 0xC271, 0xC275, 0xC536, 0xC540,
        0xC605, 0xC661, 0xC662, 0xC811, 0xC993, 0xD184, 0xF368
    )

    /**
     * Check if the camera firmware is already loaded (FX3 loaded state).
     * If true, the camera is ready for SDK scan/open operations.
     * If false, firmware needs to be uploaded first via initFirmware().
     */
    fun isFirmwareLoaded(pid: Int): Boolean {
        val normalizedPid = pid and 0xFFFF
        return normalizedPid in FX3_LOADED_PIDS ||
            (normalizedPid and 0xF000) == 0xC000 ||
            (normalizedPid and 0xF000) == 0xD000
    }

    @JvmField
    var nativeAvailable: Boolean = false

    init {
        nativeAvailable = try {
            System.loadLibrary("usb1.0")
            System.loadLibrary("qhyccd_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "QHYCCD native library unavailable (not arm64?): ${e.message}")
            false
        }
    }

    fun initResource(): Int = nInitResource()
    fun releaseResource() = nReleaseResource()
    fun initFirmware(vid: Int, pid: Int, fd: Int): Int = nInitFirmware(vid, pid, fd)
    fun scan(): Int = nScan()
    fun getId(index: Int): String = nGetId(index)
    fun open(id: String): Boolean = nOpen(id)
    fun close() = nClose()
    /** Drop the native camera handle without any USB I/O (device already unplugged). */
    fun markDisconnected() = nMarkDisconnected()
    fun setStreamMode(mode: Int): Int = nSetStreamMode(mode)
    fun setReadMode(mode: Int): Int = nSetReadMode(mode)
    fun initCamera(): Int = nInitCamera()
    fun getChipInfo(): IntArray? = nGetChipInfo()
    fun setParam(controlId: Int, value: Double): Int = nSetParam(controlId, value)
    fun getParam(controlId: Int): Double = nGetParam(controlId)
    fun getParamRange(controlId: Int): DoubleArray? = nGetParamRange(controlId)
    fun isControlAvailable(controlId: Int): Int = nIsControlAvailable(controlId)
    fun setBinMode(binx: Int, biny: Int): Int = nSetBinMode(binx, biny)
    fun setResolution(x: Int, y: Int, w: Int, h: Int): Int = nSetResolution(x, y, w, h)
    fun setDebayerOnOff(on: Boolean): Int = nSetDebayerOnOff(on)
    fun getMemLength(): Int = nGetMemLength()
    fun beginLive(): Int = nBeginLive()
    fun getLiveFrame(outBuf: ByteArray, outInfo: IntArray): Int = nGetLiveFrame(outBuf, outInfo)
    fun stopLive(): Int = nStopLive()
    fun expSingleFrame(): Int = nExpSingleFrame()
    fun getSingleFrame(outBuf: ByteArray, outInfo: IntArray): Int = nGetSingleFrame(outBuf, outInfo)
    fun cancelExposing(): Int = nCancelExposing()
    fun getNumberOfReadModes(): Int = nGetNumberOfReadModes()
    fun getReadModeName(index: Int): String = nGetReadModeName(index)
    fun getBayerType(): Int = nGetBayerType()

    private external fun nInitResource(): Int
    private external fun nReleaseResource()
    private external fun nInitFirmware(vid: Int, pid: Int, fd: Int): Int
    private external fun nScan(): Int
    private external fun nGetId(index: Int): String
    private external fun nOpen(id: String): Boolean
    private external fun nClose()
    private external fun nMarkDisconnected()
    private external fun nSetStreamMode(mode: Int): Int
    private external fun nSetReadMode(mode: Int): Int
    private external fun nInitCamera(): Int
    private external fun nGetChipInfo(): IntArray?
    private external fun nSetParam(controlId: Int, value: Double): Int
    private external fun nGetParam(controlId: Int): Double
    private external fun nGetParamRange(controlId: Int): DoubleArray?
    private external fun nIsControlAvailable(controlId: Int): Int
    private external fun nSetBinMode(binx: Int, biny: Int): Int
    private external fun nSetResolution(x: Int, y: Int, w: Int, h: Int): Int
    private external fun nSetDebayerOnOff(on: Boolean): Int
    private external fun nGetMemLength(): Int
    private external fun nBeginLive(): Int
    private external fun nGetLiveFrame(outBuf: ByteArray, outInfo: IntArray): Int
    private external fun nStopLive(): Int
    private external fun nExpSingleFrame(): Int
    private external fun nGetSingleFrame(outBuf: ByteArray, outInfo: IntArray): Int
    private external fun nCancelExposing(): Int
    private external fun nGetNumberOfReadModes(): Int
    private external fun nGetReadModeName(index: Int): String
    private external fun nGetBayerType(): Int
}
