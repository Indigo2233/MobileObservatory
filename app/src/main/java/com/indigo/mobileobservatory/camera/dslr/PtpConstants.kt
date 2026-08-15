package com.indigo.mobileobservatory.camera.dslr

object PtpConstants {
    const val CONTAINER_COMMAND = 1
    const val CONTAINER_DATA = 2
    const val CONTAINER_RESPONSE = 3
    const val CONTAINER_EVENT = 4

    const val HEADER_SIZE = 12

    const val OC_GET_DEVICE_INFO = 0x1001
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_STORAGE_IDS = 0x1004
    const val OC_GET_NUM_OBJECTS = 0x1006
    const val OC_GET_OBJECT_HANDLES = 0x1007
    const val OC_GET_OBJECT_INFO = 0x1008
    const val OC_GET_OBJECT = 0x1009
    const val OC_GET_DEVICE_PROP_DESC = 0x1014
    const val OC_GET_DEVICE_PROP_VALUE = 0x1015
    const val OC_SET_DEVICE_PROP_VALUE = 0x1016
    const val OC_INITIATE_CAPTURE = 0x100E

    /** Nikon Live View (D5100-class vendor operations). */
    const val OC_NIKON_START_LIVE_VIEW = 0x9201
    const val OC_NIKON_END_LIVE_VIEW = 0x9202
    const val OC_NIKON_GET_LIVE_VIEW_IMAGE = 0x9203

    const val EVENT_OBJECT_ADDED = 0x4002

    const val OFC_EXIF_JPEG = 0x3801
    const val OFC_NEF = 0x300D

    const val RC_OK = 0x2001
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_INVALID_TRANSACTION_ID = 0x2004
    const val RC_SESSION_ALREADY_OPEN = 0x201E
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_NIKON_OUT_OF_FOCUS = 0xA002

    const val PROP_F_NUMBER = 0x5007
    const val PROP_EXPOSURE_TIME = 0x500D
    const val PROP_EXPOSURE_INDEX = 0x500F

    const val TYPE_INT8 = 0x0001
    const val TYPE_UINT8 = 0x0002
    const val TYPE_INT16 = 0x0003
    const val TYPE_UINT16 = 0x0004
    const val TYPE_INT32 = 0x0005
    const val TYPE_UINT32 = 0x0006

    const val FORM_NONE = 0
    const val FORM_RANGE = 1
    const val FORM_ENUM = 2

    const val USB_CLASS_STILL_IMAGE = 6
    const val USB_STILL_IMAGE_SUBCLASS = 1
    const val USB_STILL_IMAGE_PROTOCOL = 1

    const val NIKON_VENDOR_ID = 0x04B0
    const val CANON_VENDOR_ID = 0x04A9
    const val SONY_VENDOR_ID = 0x054C
}
