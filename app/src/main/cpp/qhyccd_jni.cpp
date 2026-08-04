#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include "qhyccd/qhyccd.h"
#include "qhyccd/libusb-1.0/libusb.h"

#define TAG "QhyccdJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static qhyccd_handle *g_camhandle = nullptr;
static uint8_t *g_imgbuf = nullptr;
static uint32_t g_imgbuf_len = 0;

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nInitResource(JNIEnv *, jobject) {
    return InitQHYCCDResource();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nReleaseResource(JNIEnv *, jobject) {
    ReleaseQHYCCDResource();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nInitFirmware(JNIEnv *, jobject,
                                                                jint vid, jint pid, jint fd) {
    return OSXInitQHYCCDAndroidFirmwareArray(vid, pid, fd);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nScan(JNIEnv *, jobject) {
    return ScanQHYCCD();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetId(JNIEnv *env, jobject, jint index) {
    char id[64] = {0};
    uint32_t ret = GetQHYCCDId(index, id);
    if (ret == QHYCCD_SUCCESS)
        return env->NewStringUTF(id);
    return env->NewStringUTF("");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nOpen(JNIEnv *env, jobject, jstring jId) {
    const char *id = env->GetStringUTFChars(jId, nullptr);
    char idBuf[64] = {0};
    strncpy(idBuf, id, sizeof(idBuf) - 1);
    env->ReleaseStringUTFChars(jId, id);
    g_camhandle = OpenQHYCCD(idBuf);
    if (g_camhandle == nullptr) {
        LOGE("OpenQHYCCD failed");
        return JNI_FALSE;
    }
    LOGI("OpenQHYCCD success");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nClose(JNIEnv *, jobject) {
    if (g_camhandle) {
        CloseQHYCCD(g_camhandle);
        g_camhandle = nullptr;
    }
    if (g_imgbuf) {
        free(g_imgbuf);
        g_imgbuf = nullptr;
        g_imgbuf_len = 0;
    }
}

// Called when the USB device has been physically unplugged. CloseQHYCCD would
// issue USB control transfers to a dead fd and can corrupt the SDK's internal
// state, so just drop the handle. ReleaseQHYCCDResource() (called afterwards
// from Java) tears down the SDK-side bookkeeping for a clean re-init.
extern "C"
JNIEXPORT void JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nMarkDisconnected(JNIEnv *, jobject) {
    LOGI("MarkDisconnected: dropping camera handle without USB I/O");
    // Deliberately do NOT free g_imgbuf here: the capture thread might still be
    // blocked inside a native Get*Frame call using it. The buffer is reused or
    // reallocated by the next BeginLive/ExpSingleFrame, and freed by nClose.
    g_camhandle = nullptr;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nSetStreamMode(JNIEnv *, jobject, jint mode) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return SetQHYCCDStreamMode(g_camhandle, (uint8_t)mode);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nSetReadMode(JNIEnv *, jobject, jint mode) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return SetQHYCCDReadMode(g_camhandle, (uint32_t)mode);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nInitCamera(JNIEnv *, jobject) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return InitQHYCCD(g_camhandle);
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetChipInfo(JNIEnv *env, jobject) {
    if (!g_camhandle) return nullptr;
    double chipw = 0, chiph = 0, pixelw = 0, pixelh = 0;
    uint32_t imagew = 0, imageh = 0, bpp = 0;
    uint32_t ret = GetQHYCCDChipInfo(g_camhandle, &chipw, &chiph, &imagew, &imageh, &pixelw, &pixelh, &bpp);
    if (ret != QHYCCD_SUCCESS) return nullptr;

    // Return [imagew, imageh, bpp, pixelw*1000, pixelh*1000]
    jint buf[5];
    buf[0] = (jint) imagew;
    buf[1] = (jint) imageh;
    buf[2] = (jint) bpp;
    buf[3] = (jint) (pixelw * 1000.0);
    buf[4] = (jint) (pixelh * 1000.0);
    jintArray result = env->NewIntArray(5);
    env->SetIntArrayRegion(result, 0, 5, buf);
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nSetParam(JNIEnv *, jobject,
                                                            jint controlId, jdouble value) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return SetQHYCCDParam(g_camhandle, (CONTROL_ID)controlId, value);
}

extern "C"
JNIEXPORT jdouble JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetParam(JNIEnv *, jobject, jint controlId) {
    if (!g_camhandle) return -1.0;
    return GetQHYCCDParam(g_camhandle, (CONTROL_ID)controlId);
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetParamRange(JNIEnv *env, jobject,
                                                                 jint controlId) {
    if (!g_camhandle) return nullptr;
    double min = 0, max = 0, step = 0;
    uint32_t ret = GetQHYCCDParamMinMaxStep(g_camhandle, (CONTROL_ID) controlId, &min, &max, &step);
    if (ret != QHYCCD_SUCCESS) return nullptr;
    jdouble buf[3] = {min, max, step};
    jdoubleArray result = env->NewDoubleArray(3);
    env->SetDoubleArrayRegion(result, 0, 3, buf);
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nIsControlAvailable(JNIEnv *, jobject,
                                                                      jint controlId) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return IsQHYCCDControlAvailable(g_camhandle, (CONTROL_ID)controlId);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nSetBinMode(JNIEnv *, jobject,
                                                              jint binx, jint biny) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return SetQHYCCDBinMode(g_camhandle, (uint32_t) binx, (uint32_t) biny);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nSetResolution(JNIEnv *, jobject,
                                                                 jint x, jint y, jint w, jint h) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return SetQHYCCDResolution(g_camhandle, (uint32_t) x, (uint32_t) y, (uint32_t) w, (uint32_t) h);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nSetDebayerOnOff(JNIEnv *, jobject,
                                                                   jboolean on) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return SetQHYCCDDebayerOnOff(g_camhandle, on ? true : false);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetMemLength(JNIEnv *, jobject) {
    if (!g_camhandle) return 0;
    return (jint)GetQHYCCDMemLength(g_camhandle);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nBeginLive(JNIEnv *, jobject) {
    if (!g_camhandle) return QHYCCD_ERROR;
    uint32_t memLen = GetQHYCCDMemLength(g_camhandle);
    LOGI("BeginLive: memLen=%u bytes (%.1f MB)", memLen, memLen / (1024.0 * 1024.0));
    if (memLen > g_imgbuf_len) {
        if (g_imgbuf) free(g_imgbuf);
        g_imgbuf = (uint8_t *) malloc(memLen);
        if (!g_imgbuf) {
            LOGE("Failed to allocate image buffer (%u bytes)", memLen);
            g_imgbuf_len = 0;
            return QHYCCD_ERROR;
        }
        g_imgbuf_len = memLen;
        LOGI("Allocated native image buffer: %u bytes", memLen);
    }
    uint32_t ret = BeginQHYCCDLive(g_camhandle);
    LOGI("BeginQHYCCDLive returned %u", ret);
    return (jint) ret;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetLiveFrame(JNIEnv *env, jobject,
                                                                jbyteArray outBuf,
                                                                jintArray outInfo) {
    if (!g_camhandle || !g_imgbuf) return QHYCCD_ERROR;
    uint32_t w = 0, h = 0, bpp = 0, channels = 0;
    uint32_t ret = GetQHYCCDLiveFrame(g_camhandle, &w, &h, &bpp, &channels, g_imgbuf);
    if (ret != QHYCCD_SUCCESS) return (jint) ret;

    if (w == 0 || h == 0 || w > 20000 || h > 20000) {
        LOGE("GetLiveFrame: invalid dimensions %ux%u", w, h);
        return QHYCCD_ERROR;
    }

    static int frameLogCount = 0;
    if (frameLogCount < 5) {
        LOGI("GetLiveFrame: %ux%u bpp=%u ch=%u", w, h, bpp, channels);
        frameLogCount++;
    }

    uint32_t ch = (channels == 0) ? 1 : channels;
    uint32_t bytesPerPx = (bpp + 7) / 8;
    uint64_t dataLen64 = (uint64_t)w * h * bytesPerPx * ch;
    if (dataLen64 > g_imgbuf_len) dataLen64 = g_imgbuf_len;
    uint32_t dataLen = (uint32_t) dataLen64;

    jint arrLen = env->GetArrayLength(outBuf);
    if ((jint) dataLen > arrLen) {
        LOGE("GetLiveFrame: Java buffer too small (%d < %u)", arrLen, dataLen);
        dataLen = (uint32_t) arrLen;
    }

    env->SetByteArrayRegion(outBuf, 0, (jsize) dataLen, (jbyte *) g_imgbuf);

    jint info[4] = {(jint)w, (jint)h, (jint)bpp, (jint)ch};
    env->SetIntArrayRegion(outInfo, 0, 4, info);
    return QHYCCD_SUCCESS;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nStopLive(JNIEnv *, jobject) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return StopQHYCCDLive(g_camhandle);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nExpSingleFrame(JNIEnv *, jobject) {
    if (!g_camhandle) return QHYCCD_ERROR;
    if (!g_imgbuf) {
        uint32_t memLen = GetQHYCCDMemLength(g_camhandle);
        g_imgbuf = (uint8_t *) malloc(memLen);
        if (!g_imgbuf) return QHYCCD_ERROR;
        g_imgbuf_len = memLen;
    }
    return (jint) ExpQHYCCDSingleFrame(g_camhandle);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetSingleFrame(JNIEnv *env, jobject,
                                                                   jbyteArray outBuf,
                                                                   jintArray outInfo) {
    if (!g_camhandle || !g_imgbuf) return QHYCCD_ERROR;
    uint32_t w = 0, h = 0, bpp = 0, channels = 0;
    uint32_t ret = GetQHYCCDSingleFrame(g_camhandle, &w, &h, &bpp, &channels, g_imgbuf);
    if (ret != QHYCCD_SUCCESS) {
        LOGE("GetSingleFrame failed: %u", ret);
        return (jint) ret;
    }

    LOGI("GetSingleFrame: %ux%u bpp=%u ch=%u", w, h, bpp, channels);

    uint32_t ch = (channels == 0) ? 1 : channels;
    uint32_t bytesPerPx = (bpp + 7) / 8;
    uint64_t dataLen64 = (uint64_t)w * h * bytesPerPx * ch;
    if (dataLen64 > g_imgbuf_len) dataLen64 = g_imgbuf_len;
    uint32_t dataLen = (uint32_t) dataLen64;

    jint arrLen = env->GetArrayLength(outBuf);
    if ((jint) dataLen > arrLen) {
        LOGE("GetSingleFrame: Java buffer too small (%d < %u)", arrLen, dataLen);
        dataLen = (uint32_t) arrLen;
    }

    env->SetByteArrayRegion(outBuf, 0, (jsize) dataLen, (jbyte *) g_imgbuf);

    jint info[4] = {(jint)w, (jint)h, (jint)bpp, (jint)ch};
    env->SetIntArrayRegion(outInfo, 0, 4, info);
    return QHYCCD_SUCCESS;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nCancelExposing(JNIEnv *, jobject) {
    if (!g_camhandle) return QHYCCD_ERROR;
    return (jint) CancelQHYCCDExposing(g_camhandle);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetNumberOfReadModes(JNIEnv *, jobject) {
    if (!g_camhandle) return 0;
    uint32_t num = 0;
    uint32_t ret = GetQHYCCDNumberOfReadModes(g_camhandle, &num);
    if (ret != QHYCCD_SUCCESS) return 0;
    return (jint) num;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetReadModeName(JNIEnv *env, jobject,
                                                                    jint index) {
    if (!g_camhandle) return env->NewStringUTF("");
    char name[64] = {0};
    uint32_t ret = GetQHYCCDReadModeName(g_camhandle, (uint32_t) index, name);
    if (ret != QHYCCD_SUCCESS) return env->NewStringUTF("");
    return env->NewStringUTF(name);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_qhyccd_QhyccdJni_nGetBayerType(JNIEnv *, jobject) {
    if (!g_camhandle) return -1;

    uint32_t ret = IsQHYCCDControlAvailable(g_camhandle, CAM_COLOR);
    LOGI("GetBayerType: CAM_COLOR(20) ret=%u", ret);

    if (ret == QHYCCD_ERROR) {
        ret = IsQHYCCDControlAvailable(g_camhandle, CAM_IS_COLOR);
        LOGI("GetBayerType: CAM_IS_COLOR(59) ret=%u", ret);
    }

    if (ret == QHYCCD_ERROR) {
        LOGI("GetBayerType: mono camera");
        return -1;
    }

    if (ret >= 1 && ret <= 4) {
        LOGI("GetBayerType: pattern from IsAvailable = %u", ret);
        return (jint) ret;
    }

    // ret == QHYCCD_SUCCESS (0): camera is color but pattern unknown from IsAvailable
    // Query the actual Bayer type via GetQHYCCDParam
    double bayerVal = GetQHYCCDParam(g_camhandle, CAM_COLOR);
    int bayerInt = (int) bayerVal;
    LOGI("GetBayerType: GetParam(CAM_COLOR) = %.0f (int=%d)", bayerVal, bayerInt);
    if (bayerInt >= 1 && bayerInt <= 4) {
        return (jint) bayerInt;
    }

    // Also try CAM_IS_COLOR via GetParam
    double bayerVal2 = GetQHYCCDParam(g_camhandle, CAM_IS_COLOR);
    int bayerInt2 = (int) bayerVal2;
    LOGI("GetBayerType: GetParam(CAM_IS_COLOR) = %.0f (int=%d)", bayerVal2, bayerInt2);
    if (bayerInt2 >= 1 && bayerInt2 <= 4) {
        return (jint) bayerInt2;
    }

    // Color camera but pattern still unknown, default to BAYER_GB (1 = GBRG)
    LOGI("GetBayerType: color camera, pattern unknown, defaulting to BAYER_GB (1)");
    return 1;
}
