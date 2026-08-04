#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include "toupcam.h"

#define TAG "ToupcamJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static HToupcam g_hcam = nullptr;
static HToupcam g_hfw  = nullptr;
static HToupcam g_heaf = nullptr;
static JavaVM*  g_jvm  = nullptr;
static jobject  g_callbackRef = nullptr;

static void __stdcall eventCallback(unsigned nEvent, void*) {
    if (!g_jvm || !g_callbackRef) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) < 0) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) < 0) return;
        attached = true;
    }
    jclass cls = env->GetObjectClass(g_callbackRef);
    jmethodID mid = env->GetMethodID(cls, "onNativeEvent", "(I)V");
    if (mid) env->CallVoidMethod(g_callbackRef, mid, (jint)nEvent);
    if (attached) g_jvm->DetachCurrentThread();
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

#define JNI_FN(name) Java_com_indigo_mobileobservatory_camera_toupcam_ToupcamJni_##name

JNIEXPORT jstring JNICALL JNI_FN(nGetModelName)(JNIEnv* env, jobject, jint vid, jint pid) {
    const ToupcamModelV2* model = Toupcam_get_Model((unsigned short)vid, (unsigned short)pid);
    if (!model || !model->name) return nullptr;
    return env->NewStringUTF(model->name);
}

JNIEXPORT jlong JNICALL JNI_FN(nGetModelFlag)(JNIEnv*, jobject, jint vid, jint pid) {
    const ToupcamModelV2* model = Toupcam_get_Model((unsigned short)vid, (unsigned short)pid);
    return model ? (jlong)model->flag : 0;
}

JNIEXPORT jfloat JNICALL JNI_FN(nGetModelPixelSize)(JNIEnv*, jobject, jint vid, jint pid) {
    const ToupcamModelV2* model = Toupcam_get_Model((unsigned short)vid, (unsigned short)pid);
    return model ? model->xpixsz : 0.0f;
}

JNIEXPORT jintArray JNICALL JNI_FN(nGetModelMaxResolution)(JNIEnv* env, jobject, jint vid, jint pid) {
    const ToupcamModelV2* model = Toupcam_get_Model((unsigned short)vid, (unsigned short)pid);
    jint res[2] = {0, 0};
    if (model && model->preview > 0) {
        res[0] = (jint)model->res[0].width;
        res[1] = (jint)model->res[0].height;
    }
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, res);
    return arr;
}

JNIEXPORT jboolean JNICALL JNI_FN(nOpen)(JNIEnv* env, jobject, jint fd, jint vid, jint pid) {
    if (g_hcam) {
        Toupcam_Stop(g_hcam);
        Toupcam_Close(g_hcam);
        g_hcam = nullptr;
    }
    if (g_callbackRef) {
        env->DeleteGlobalRef(g_callbackRef);
        g_callbackRef = nullptr;
    }
    char camId[128];
    snprintf(camId, sizeof(camId), "fd-%d-%04x-%04x", fd, (unsigned)vid, (unsigned)pid);
    g_hcam = Toupcam_Open(camId);
    if (g_hcam) {
        LOGI("Opened camera: %s", camId);
        return JNI_TRUE;
    }
    LOGE("Failed to open camera: %s", camId);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nClose)(JNIEnv* env, jobject) {
    if (g_hcam) {
        Toupcam_Close(g_hcam);
        g_hcam = nullptr;
    }
    if (g_callbackRef) {
        env->DeleteGlobalRef(g_callbackRef);
        g_callbackRef = nullptr;
    }
    LOGI("Camera closed");
}

JNIEXPORT jboolean JNICALL JNI_FN(nStartPull)(JNIEnv* env, jobject, jobject callback) {
    if (!g_hcam) return JNI_FALSE;
    if (g_callbackRef) env->DeleteGlobalRef(g_callbackRef);
    g_callbackRef = env->NewGlobalRef(callback);

    LOGI("StartPullModeWithCallback ...");
    HRESULT hr = Toupcam_StartPullModeWithCallback(g_hcam, eventCallback, nullptr);
    LOGI("StartPullModeWithCallback -> hr=0x%08x", hr);
    if (SUCCEEDED(hr)) {
        LOGI("Pull mode started");
        return JNI_TRUE;
    }
    LOGE("StartPullModeWithCallback failed: 0x%08x", hr);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nStop)(JNIEnv*, jobject) {
    if (g_hcam) Toupcam_Stop(g_hcam);
}

JNIEXPORT jint JNICALL JNI_FN(nPullImageRaw)(JNIEnv* env, jobject, jbyteArray buf, jint bits) {
    if (!g_hcam) return -1;
    jbyte* data = env->GetByteArrayElements(buf, nullptr);
    if (!data) return -1;
    ToupcamFrameInfoV2 info = {};
    HRESULT hr = Toupcam_PullImageV2(g_hcam, data, bits, &info);
    env->ReleaseByteArrayElements(buf, data, 0);
    if (SUCCEEDED(hr)) return 0;
    return (jint)hr;
}

JNIEXPORT jintArray JNICALL JNI_FN(nGetSize)(JNIEnv* env, jobject) {
    jint sz[2] = {0, 0};
    if (g_hcam) Toupcam_get_Size(g_hcam, &sz[0], &sz[1]);
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, sz);
    return arr;
}

JNIEXPORT jboolean JNICALL JNI_FN(nPutSize)(JNIEnv*, jobject, jint w, jint h) {
    if (!g_hcam) return JNI_FALSE;
    return SUCCEEDED(Toupcam_put_Size(g_hcam, w, h)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_FN(nGetExpoTime)(JNIEnv*, jobject) {
    unsigned t = 0;
    if (g_hcam) Toupcam_get_ExpoTime(g_hcam, &t);
    return (jint)t;
}

JNIEXPORT jboolean JNICALL JNI_FN(nPutExpoTime)(JNIEnv*, jobject, jint us) {
    if (!g_hcam) return JNI_FALSE;
    return SUCCEEDED(Toupcam_put_ExpoTime(g_hcam, (unsigned)us)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL JNI_FN(nGetExpoTimeRange)(JNIEnv* env, jobject) {
    unsigned nMin = 0, nMax = 0, nDef = 0;
    LOGI("getExpoTimeRange ...");
    if (g_hcam) Toupcam_get_ExpTimeRange(g_hcam, &nMin, &nMax, &nDef);
    LOGI("getExpoTimeRange -> min=%u max=%u def=%u", nMin, nMax, nDef);
    jlong vals[3] = { (jlong)nMin, (jlong)nMax, (jlong)nDef };
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

JNIEXPORT jint JNICALL JNI_FN(nGetExpoAGain)(JNIEnv*, jobject) {
    unsigned short g = 0;
    if (g_hcam) Toupcam_get_ExpoAGain(g_hcam, &g);
    return (jint)g;
}

JNIEXPORT jboolean JNICALL JNI_FN(nPutExpoAGain)(JNIEnv*, jobject, jint gain) {
    if (!g_hcam) return JNI_FALSE;
    return SUCCEEDED(Toupcam_put_ExpoAGain(g_hcam, (unsigned short)gain)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL JNI_FN(nGetExpoAGainRange)(JNIEnv* env, jobject) {
    unsigned short nMin = 0, nMax = 0, nDef = 0;
    LOGI("getExpoAGainRange ...");
    if (g_hcam) Toupcam_get_ExpoAGainRange(g_hcam, &nMin, &nMax, &nDef);
    LOGI("getExpoAGainRange -> min=%u max=%u def=%u", nMin, nMax, nDef);
    jint vals[3] = { nMin, nMax, nDef };
    jintArray arr = env->NewIntArray(3);
    env->SetIntArrayRegion(arr, 0, 3, vals);
    return arr;
}

JNIEXPORT jboolean JNICALL JNI_FN(nPutOption)(JNIEnv*, jobject, jint opt, jint val) {
    if (!g_hcam) return JNI_FALSE;
    LOGI("putOption: opt=0x%02x val=%d ...", opt, val);
    HRESULT hr = Toupcam_put_Option(g_hcam, (unsigned)opt, val);
    LOGI("putOption: opt=0x%02x val=%d -> hr=0x%08x", opt, val, hr);
    return SUCCEEDED(hr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_FN(nGetOption)(JNIEnv*, jobject, jint opt) {
    int val = 0;
    if (!g_hcam) return 0;
    LOGI("getOption: opt=0x%02x ...", opt);
    HRESULT hr = Toupcam_get_Option(g_hcam, (unsigned)opt, &val);
    LOGI("getOption: opt=0x%02x -> val=%d hr=0x%08x", opt, val, hr);
    return val;
}

JNIEXPORT jboolean JNICALL JNI_FN(nPutRoi)(JNIEnv*, jobject, jint x, jint y, jint w, jint h) {
    if (!g_hcam) return JNI_FALSE;
    HRESULT hr = Toupcam_put_Roi(g_hcam, (unsigned)x, (unsigned)y, (unsigned)w, (unsigned)h);
    if (SUCCEEDED(hr)) {
        LOGI("putRoi(%d,%d,%d,%d) OK", x, y, w, h);
        return JNI_TRUE;
    }
    LOGE("putRoi(%d,%d,%d,%d) failed: 0x%08x", x, y, w, h, hr);
    return JNI_FALSE;
}

JNIEXPORT jintArray JNICALL JNI_FN(nGetRoi)(JNIEnv* env, jobject) {
    unsigned x = 0, y = 0, w = 0, h = 0;
    if (g_hcam) Toupcam_get_Roi(g_hcam, &x, &y, &w, &h);
    jint vals[4] = { (jint)x, (jint)y, (jint)w, (jint)h };
    jintArray arr = env->NewIntArray(4);
    env->SetIntArrayRegion(arr, 0, 4, vals);
    return arr;
}

JNIEXPORT jint JNICALL JNI_FN(nGetMaxBitDepth)(JNIEnv*, jobject) {
    if (!g_hcam) return 8;
    LOGI("getMaxBitDepth ...");
    int depth = Toupcam_get_MaxBitDepth(g_hcam);
    LOGI("getMaxBitDepth -> %d", depth);
    return (depth > 0 && depth <= 32) ? (jint)depth : 8;
}

JNIEXPORT jboolean JNICALL JNI_FN(nPutAutoExpoEnable)(JNIEnv*, jobject, jint mode) {
    if (!g_hcam) return JNI_FALSE;
    LOGI("putAutoExpoEnable: %d ...", mode);
    HRESULT hr = Toupcam_put_AutoExpoEnable(g_hcam, mode);
    LOGI("putAutoExpoEnable: %d -> hr=0x%08x", mode, hr);
    return SUCCEEDED(hr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_FN(nGetAutoExpoEnable)(JNIEnv*, jobject) {
    if (!g_hcam) return 0;
    int mode = 0;
    Toupcam_get_AutoExpoEnable(g_hcam, &mode);
    return (jint)mode;
}

JNIEXPORT jintArray JNICALL JNI_FN(nGetRawFormat)(JNIEnv* env, jobject) {
    unsigned fourcc = 0, bpp = 0;
    if (g_hcam) Toupcam_get_RawFormat(g_hcam, &fourcc, &bpp);
    jint vals[2] = { (jint)fourcc, (jint)bpp };
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}

JNIEXPORT jint JNICALL JNI_FN(nGetResolutionNumber)(JNIEnv*, jobject) {
    if (!g_hcam) return 0;
    HRESULT hr = Toupcam_get_ResolutionNumber(g_hcam);
    return SUCCEEDED(hr) ? (jint)hr : 0;
}

JNIEXPORT jintArray JNICALL JNI_FN(nGetResolution)(JNIEnv* env, jobject, jint index) {
    jint sz[2] = {0, 0};
    if (g_hcam) Toupcam_get_Resolution(g_hcam, (unsigned)index, &sz[0], &sz[1]);
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, sz);
    return arr;
}

JNIEXPORT jint JNICALL JNI_FN(nGetTemperature)(JNIEnv*, jobject) {
    if (!g_hcam) return -2730;
    LOGI("getTemperature ...");
    short temp = 0;
    HRESULT hr = Toupcam_get_Temperature(g_hcam, &temp);
    LOGI("getTemperature -> temp=%d hr=0x%08x", (int)temp, hr);
    if (SUCCEEDED(hr)) return (jint)temp;
    LOGE("get_Temperature failed: 0x%08x", hr);
    return -2730;
}

JNIEXPORT jboolean JNICALL JNI_FN(nPutTemperature)(JNIEnv*, jobject, jint tenthDegC) {
    if (!g_hcam) return JNI_FALSE;
    HRESULT hr = Toupcam_put_Temperature(g_hcam, (short)tenthDegC);
    if (SUCCEEDED(hr)) {
        LOGI("put_Temperature(%d) OK", tenthDegC);
        return JNI_TRUE;
    }
    LOGE("put_Temperature(%d) failed: 0x%08x", tenthDegC, hr);
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nIsFilterWheel)(JNIEnv*, jobject, jint vid, jint pid) {
    const ToupcamModelV2* model = Toupcam_get_Model((unsigned short)vid, (unsigned short)pid);
    if (!model) return JNI_FALSE;
    return (model->flag & TOUPCAM_FLAG_FILTERWHEEL) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nFwOpen)(JNIEnv* env, jobject, jint fd, jint vid, jint pid) {
    if (g_hfw) {
        Toupcam_Close(g_hfw);
        g_hfw = nullptr;
    }
    char fwId[128];
    snprintf(fwId, sizeof(fwId), "fd-%d-%04x-%04x", fd, (unsigned)vid, (unsigned)pid);
    g_hfw = Toupcam_Open(fwId);
    if (g_hfw) {
        LOGI("Opened filter wheel: %s", fwId);
        return JNI_TRUE;
    }
    LOGE("Failed to open filter wheel: %s", fwId);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nFwClose)(JNIEnv*, jobject) {
    if (g_hfw) {
        Toupcam_Close(g_hfw);
        g_hfw = nullptr;
    }
    LOGI("Filter wheel closed");
}

JNIEXPORT jint JNICALL JNI_FN(nFwGetSlotCount)(JNIEnv*, jobject) {
    if (!g_hfw) return 0;
    int val = 0;
    Toupcam_get_Option(g_hfw, TOUPCAM_OPTION_FILTERWHEEL_SLOT, &val);
    return (jint)val;
}

JNIEXPORT jboolean JNICALL JNI_FN(nFwSetSlotCount)(JNIEnv*, jobject, jint count) {
    if (!g_hfw) return JNI_FALSE;
    return SUCCEEDED(Toupcam_put_Option(g_hfw, TOUPCAM_OPTION_FILTERWHEEL_SLOT, count)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_FN(nFwGetPosition)(JNIEnv*, jobject) {
    if (!g_hfw) return -2;
    int val = 0;
    Toupcam_get_Option(g_hfw, TOUPCAM_OPTION_FILTERWHEEL_POSITION, &val);
    return (jint)val;
}

JNIEXPORT jboolean JNICALL JNI_FN(nFwSetPosition)(JNIEnv*, jobject, jint pos, jboolean bidirectional) {
    if (!g_hfw) return JNI_FALSE;
    int val = pos;
    if (pos >= 0 && bidirectional) {
        val = (pos & 0xff) | (1 << 8);
    }
    LOGI("FwSetPosition: pos=%d, bidirectional=%d, val=0x%x", pos, bidirectional, val);
    HRESULT hr = Toupcam_put_Option(g_hfw, TOUPCAM_OPTION_FILTERWHEEL_POSITION, val);
    LOGI("FwSetPosition result: 0x%x", hr);
    return SUCCEEDED(hr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nIsAutoFocuser)(JNIEnv*, jobject, jint vid, jint pid) {
    const ToupcamModelV2* model = Toupcam_get_Model((unsigned short)vid, (unsigned short)pid);
    if (!model) return JNI_FALSE;
    return (model->flag & TOUPCAM_FLAG_AUTOFOCUSER) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nEafOpen)(JNIEnv*, jobject, jint fd, jint vid, jint pid) {
    if (g_heaf) {
        Toupcam_Close(g_heaf);
        g_heaf = nullptr;
    }
    char eafId[128];
    snprintf(eafId, sizeof(eafId), "fd-%d-%04x-%04x", fd, (unsigned)vid, (unsigned)pid);
    g_heaf = Toupcam_Open(eafId);
    if (g_heaf) {
        LOGI("Opened EAF: %s", eafId);
        return JNI_TRUE;
    }
    LOGE("Failed to open EAF: %s", eafId);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nEafClose)(JNIEnv*, jobject) {
    if (g_heaf) {
        Toupcam_Close(g_heaf);
        g_heaf = nullptr;
    }
    LOGI("EAF closed");
}

JNIEXPORT jint JNICALL JNI_FN(nEafAAF)(JNIEnv*, jobject, jint action, jint outVal) {
    if (!g_heaf) return -1;
    int inVal = 0;
    HRESULT hr = Toupcam_AAF(g_heaf, action, outVal, &inVal);
    if (SUCCEEDED(hr)) return inVal;
    LOGE("AAF action 0x%02x failed: 0x%08x", action, hr);
    return -1;
}

JNIEXPORT jboolean JNICALL JNI_FN(nEafAAFSet)(JNIEnv*, jobject, jint action, jint outVal) {
    if (!g_heaf) {
        LOGE("EAF AAFSet: handle is null");
        return JNI_FALSE;
    }
    HRESULT hr = Toupcam_AAF(g_heaf, action, outVal, nullptr);
    if (SUCCEEDED(hr)) {
        LOGI("EAF AAFSet OK: action=0x%02x, val=%d", action, outVal);
        return JNI_TRUE;
    }
    LOGE("EAF AAFSet FAILED: action=0x%02x, val=%d, hr=0x%08x", action, outVal, hr);
    return JNI_FALSE;
}

} // extern "C"
