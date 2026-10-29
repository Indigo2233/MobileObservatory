#include <jni.h>
#include <cmath>
#include <memory>
#include <stdexcept>
#include "phd2/src/gaussian_process_guider.h"
#include "phd2/src/zfilterfactory.h"

namespace {
GaussianProcessGuider::guide_parameters defaults() {
    GaussianProcessGuider::guide_parameters p;
    p.control_gain_ = .6; p.min_move_ = .2; p.prediction_gain_ = .5;
    p.min_periods_for_inference_ = 2; p.min_periods_for_period_estimation_ = 2;
    p.points_for_approximation_ = 100; p.compute_period_ = true;
    p.SE0KLengthScale_ = 700; p.SE0KSignalVariance_ = 20;
    p.PKLengthScale_ = 10; p.PKSignalVariance_ = 20; p.PKPeriodLength_ = 200;
    p.SE1KLengthScale_ = 25; p.SE1KSignalVariance_ = 10;
    return p;
}

GaussianProcessGuider* model(jlong handle) {
    if (!handle) throw std::runtime_error("PHD2 predictive model is closed");
    return reinterpret_cast<GaussianProcessGuider*>(handle);
}
void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_indigo_mobileobservatory_guide_NativePhd2_create(JNIEnv* env, jobject) {
    try { return reinterpret_cast<jlong>(new GaussianProcessGuider(defaults())); }
    catch (const std::exception& error) { fail(env, error); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_indigo_mobileobservatory_guide_NativePhd2_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<GaussianProcessGuider*>(handle);
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_indigo_mobileobservatory_guide_NativePhd2_zfilterCoefficients(JNIEnv* env, jobject, jdouble exposureFactor) {
    try {
        if (!std::isfinite(exposureFactor) || exposureFactor < 1 || exposureFactor > 20)
            throw std::runtime_error("Invalid PHD2 Z-filter exposure factor");
        const double corner = exposureFactor * 4.0;
        ZFilterFactory filter(corner < 6.0 ? BUTTERWORTH : BESSEL, 4, corner);
        std::vector<double> coefficients(1, filter.gain());
        coefficients.insert(coefficients.end(), filter.xcoeffs.begin(), filter.xcoeffs.end());
        coefficients.insert(coefficients.end(), filter.ycoeffs.begin(), filter.ycoeffs.end());
        for (double v : coefficients) if (!std::isfinite(v)) throw std::runtime_error("Invalid PHD2 Z-filter coefficient");
        jdoubleArray result = env->NewDoubleArray(coefficients.size());
        if (result) env->SetDoubleArrayRegion(result, 0, coefficients.size(), coefficients.data());
        return result;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_indigo_mobileobservatory_guide_NativePhd2_configure(JNIEnv* env, jobject, jlong handle,
    jdouble gain, jdouble period, jboolean estimate) {
    try {
        if (!std::isfinite(gain) || gain < 0 || gain > 1 || !std::isfinite(period) || period < 10 || period > 2000)
            throw std::runtime_error("Invalid PHD2 predictive parameters");
        auto* g = model(handle);
        g->SetPredictionGain(gain); g->SetBoolComputePeriod(estimate);
        auto parameters = g->GetGPHyperparameters(); parameters[PKPeriodLength] = period;
        g->SetGPHyperparameters(parameters);
    } catch (const std::exception& error) { fail(env, error); }
}
extern "C" JNIEXPORT jdouble JNICALL
Java_com_indigo_mobileobservatory_guide_NativePhd2_result(JNIEnv* env, jobject, jlong handle,
    jdouble input, jdouble snr, jdouble exposure, jdouble timestamp, jdouble aggression, jdouble minMove) {
    try {
        if (!std::isfinite(input) || !std::isfinite(snr) || !std::isfinite(exposure) || exposure <= 0 ||
            !std::isfinite(timestamp) || timestamp < 0 || !std::isfinite(aggression) || aggression < 0 || aggression > 1 ||
            !std::isfinite(minMove) || minMove < 0) throw std::runtime_error("Invalid PHD2 guide measurement");
        auto* g = model(handle);
        g->SetMonotonicSeconds(timestamp); g->SetControlGain(aggression); g->SetMinMove(minMove);
        const double output = g->result(input, snr, exposure);
        if (!std::isfinite(output)) throw std::runtime_error("Non-finite PHD2 predictive correction");
        return output;
    } catch (const std::exception& error) { fail(env, error); return 0; }
}
