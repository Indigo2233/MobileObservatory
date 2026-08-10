package com.indigo.mobileobservatory.camera.toupcam

object ToupcamJni {

    const val EVENT_IMAGE = 0x0004
    const val EVENT_DISCONNECTED = 0x0081
    const val EVENT_ERROR = 0x0080

    const val OPTION_RAW = 0x04
    const val OPTION_BITDEPTH = 0x06
    const val OPTION_BLACKLEVEL = 0x15
    const val OPTION_FRAMERATE = 0x11
    const val OPTION_BINNING = 0x17
    const val OPTION_PIXEL_FORMAT = 0x1a
    const val OPTION_FAN = 0x07
    const val OPTION_TEC = 0x08
    const val OPTION_TECTARGET = 0x0f
    const val OPTION_TEC_VOLTAGE = 0x20
    const val OPTION_TEC_VOLTAGE_MAX = 0x21
    const val OPTION_TECTARGET_RANGE = 0x6d
    const val OPTION_CG = 0x19
    const val OPTION_LOW_NOISE = 0x38
    const val OPTION_FLUSH = 0x3f

    const val PIXELFORMAT_RAW8 = 0x00
    const val PIXELFORMAT_RAW10 = 0x01
    const val PIXELFORMAT_RAW12 = 0x02
    const val PIXELFORMAT_RAW14 = 0x03
    const val PIXELFORMAT_RAW16 = 0x04
    const val PIXELFORMAT_HDR8HL = 0x0e
    const val PIXELFORMAT_HDR10HL = 0x0f
    const val PIXELFORMAT_HDR11HL = 0x10
    const val PIXELFORMAT_HDR12HL = 0x11
    const val PIXELFORMAT_HDR14HL = 0x12

    const val OPTION_AWB_CONTINUOUS = 0x6c
    const val OPTION_RGB = 0x0c

    const val FLAG_MONO = 0x00000010L
    const val FLAG_RAW10 = 0x00001000L
    const val FLAG_RAW12 = 0x00002000L
    const val FLAG_RAW14 = 0x00004000L
    const val FLAG_RAW16 = 0x00008000L
    const val FLAG_BLACKLEVEL = 0x00400000L
    const val FLAG_RAW8 = 0x80000000L
    const val FLAG_TEC = 0x00000080L
    const val FLAG_GETTEMPERATURE = 0x00000400L
    const val FLAG_FAN = 0x00010000L
    const val FLAG_TEC_ONOFF = 0x00020000L
    const val FLAG_CG = 0x04000000L
    const val FLAG_CGHDR = 0x0000000800000000L
    const val FLAG_GLOBALSHUTTER = 0x0000001000000000L
    const val FLAG_LOW_NOISE = 0x0000010000000000L
    const val FLAG_FILTERWHEEL = 0x0000100000000000L
    const val FLAG_AUTOFOCUSER = 0x0002000000000000L

    const val AAF_SETPOSITION     = 0x01
    const val AAF_GETPOSITION     = 0x02
    const val AAF_SETZERO         = 0x03
    const val AAF_SETDIRECTION    = 0x05
    const val AAF_GETDIRECTION    = 0x06
    const val AAF_SETMAXINCREMENT = 0x07
    const val AAF_GETMAXINCREMENT = 0x08
    const val AAF_SETFINE         = 0x09
    const val AAF_GETFINE         = 0x0a
    const val AAF_SETCOARSE       = 0x0b
    const val AAF_GETCOARSE       = 0x0c
    const val AAF_SETBACKLASH     = 0x0f
    const val AAF_GETBACKLASH     = 0x10
    const val AAF_GETAMBIENTTEMP  = 0x12
    const val AAF_GETTEMP         = 0x14
    const val AAF_ISMOVING        = 0x16
    const val AAF_HALT            = 0x17
    const val AAF_SETMAXSTEP      = 0x1b
    const val AAF_GETMAXSTEP      = 0x1c
    const val AAF_GETSTEPSIZE     = 0x1e
    const val AAF_RANGEMIN        = 0xfd
    const val AAF_RANGEMAX        = 0xfe
    const val AAF_RANGEDEF        = 0xff

    init {
        System.loadLibrary("toupcam")
        System.loadLibrary("toupcam_jni")
    }

    fun getModelName(vid: Int, pid: Int): String? = nGetModelName(vid, pid)
    fun getModelFlag(vid: Int, pid: Int): Long = nGetModelFlag(vid, pid)
    fun getModelPixelSize(vid: Int, pid: Int): Float = nGetModelPixelSize(vid, pid)
    fun getModelMaxResolution(vid: Int, pid: Int): IntArray = nGetModelMaxResolution(vid, pid)

    fun open(fd: Int, vid: Int, pid: Int): Boolean = nOpen(fd, vid, pid)
    fun close() = nClose()
    fun startPull(callback: NativeEventCallback): Boolean = nStartPull(callback)
    fun stop() = nStop()

    fun pullImageRaw(buf: ByteArray, bits: Int): Int = nPullImageRaw(buf, bits)

    fun getSize(): IntArray = nGetSize()
    fun putSize(w: Int, h: Int): Boolean = nPutSize(w, h)

    fun getExpoTime(): Int = nGetExpoTime()
    fun putExpoTime(us: Int): Boolean = nPutExpoTime(us)
    fun getExpoTimeRange(): LongArray = nGetExpoTimeRange()

    fun getExpoAGain(): Int = nGetExpoAGain()
    fun putExpoAGain(gain: Int): Boolean = nPutExpoAGain(gain)
    fun getExpoAGainRange(): IntArray = nGetExpoAGainRange()

    fun putOption(opt: Int, value: Int): Boolean = nPutOption(opt, value)
    fun getOption(opt: Int): Int = nGetOption(opt)

    fun putRoi(x: Int, y: Int, w: Int, h: Int): Boolean = nPutRoi(x, y, w, h)
    fun getRoi(): IntArray = nGetRoi()

    fun getMaxBitDepth(): Int = nGetMaxBitDepth()
    fun getRawFormat(): IntArray = nGetRawFormat()
    fun putAutoExpoEnable(mode: Int): Boolean = nPutAutoExpoEnable(mode)
    fun getAutoExpoEnable(): Int = nGetAutoExpoEnable()
    fun getResolutionNumber(): Int = nGetResolutionNumber()
    fun getResolution(index: Int): IntArray = nGetResolution(index)

    fun getTemperature(): Int = nGetTemperature()
    fun putTemperature(tenthDegC: Int): Boolean = nPutTemperature(tenthDegC)

    fun isFilterWheel(vid: Int, pid: Int): Boolean = nIsFilterWheel(vid, pid)
    fun isAutoFocuser(vid: Int, pid: Int): Boolean = nIsAutoFocuser(vid, pid)

    fun eafOpen(fd: Int, vid: Int, pid: Int): Boolean = nEafOpen(fd, vid, pid)
    fun eafClose() = nEafClose()
    fun eafGet(action: Int): Int = nEafAAF(action, 0)
    fun eafSet(action: Int, value: Int): Boolean = nEafAAFSet(action, value)
    fun fwOpen(fd: Int, vid: Int, pid: Int): Boolean = nFwOpen(fd, vid, pid)
    fun fwClose() = nFwClose()
    fun fwGetSlotCount(): Int = nFwGetSlotCount()
    fun fwSetSlotCount(count: Int): Boolean = nFwSetSlotCount(count)
    fun fwGetPosition(): Int = nFwGetPosition()
    fun fwSetPosition(pos: Int, bidirectional: Boolean = true): Boolean = nFwSetPosition(pos, bidirectional)

    private external fun nGetModelName(vid: Int, pid: Int): String?
    private external fun nGetModelFlag(vid: Int, pid: Int): Long
    private external fun nGetModelPixelSize(vid: Int, pid: Int): Float
    private external fun nGetModelMaxResolution(vid: Int, pid: Int): IntArray
    private external fun nOpen(fd: Int, vid: Int, pid: Int): Boolean
    private external fun nClose()
    private external fun nStartPull(callback: NativeEventCallback): Boolean
    private external fun nStop()
    private external fun nPullImageRaw(buf: ByteArray, bits: Int): Int
    private external fun nGetSize(): IntArray
    private external fun nPutSize(w: Int, h: Int): Boolean
    private external fun nGetExpoTime(): Int
    private external fun nPutExpoTime(us: Int): Boolean
    private external fun nGetExpoTimeRange(): LongArray
    private external fun nGetExpoAGain(): Int
    private external fun nPutExpoAGain(gain: Int): Boolean
    private external fun nGetExpoAGainRange(): IntArray
    private external fun nPutOption(opt: Int, value: Int): Boolean
    private external fun nGetOption(opt: Int): Int
    private external fun nPutRoi(x: Int, y: Int, w: Int, h: Int): Boolean
    private external fun nGetRoi(): IntArray
    private external fun nGetMaxBitDepth(): Int
    private external fun nGetRawFormat(): IntArray
    private external fun nPutAutoExpoEnable(mode: Int): Boolean
    private external fun nGetAutoExpoEnable(): Int
    private external fun nGetResolutionNumber(): Int
    private external fun nGetResolution(index: Int): IntArray
    private external fun nGetTemperature(): Int
    private external fun nPutTemperature(tenthDegC: Int): Boolean
    private external fun nIsFilterWheel(vid: Int, pid: Int): Boolean
    private external fun nFwOpen(fd: Int, vid: Int, pid: Int): Boolean
    private external fun nFwClose()
    private external fun nFwGetSlotCount(): Int
    private external fun nFwSetSlotCount(count: Int): Boolean
    private external fun nFwGetPosition(): Int
    private external fun nFwSetPosition(pos: Int, bidirectional: Boolean): Boolean
    private external fun nIsAutoFocuser(vid: Int, pid: Int): Boolean
    private external fun nEafOpen(fd: Int, vid: Int, pid: Int): Boolean
    private external fun nEafClose()
    private external fun nEafAAF(action: Int, outVal: Int): Int
    private external fun nEafAAFSet(action: Int, outVal: Int): Boolean
}

interface NativeEventCallback {
    fun onNativeEvent(event: Int)
}
