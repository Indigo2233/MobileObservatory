#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstdint>
#include <limits>
#include <new>

namespace {

struct Accumulator {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t channels;
    float* mean;
    float* m2;
    std::uint16_t* coverage;
};

Accumulator* from(jlong handle) {
    return reinterpret_cast<Accumulator*>(static_cast<std::uintptr_t>(handle));
}

template <typename T>
T* checked_allocate(std::size_t count) {
    if (count == 0 || count > std::numeric_limits<std::size_t>::max() / sizeof(T)) return nullptr;
    return static_cast<T*>(std::malloc(count * sizeof(T)));
}

void destroy(Accumulator* accumulator) {
    if (accumulator == nullptr) return;
    std::free(accumulator->mean);
    std::free(accumulator->m2);
    std::free(accumulator->coverage);
    delete accumulator;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_create(
    JNIEnv*, jobject, jint width, jint height, jint channels) {
    if (width <= 0 || height <= 0 || (channels != 1 && channels != 3)) return 0;
    const std::uint64_t pixels = static_cast<std::uint64_t>(width) * height;
    const std::uint64_t samples = pixels * channels;
    if (pixels > static_cast<std::uint64_t>(std::numeric_limits<jint>::max()) ||
        samples > static_cast<std::uint64_t>(std::numeric_limits<jint>::max())) return 0;
    auto* accumulator = new (std::nothrow) Accumulator{
        static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height),
        static_cast<std::uint32_t>(channels), nullptr, nullptr, nullptr};
    if (accumulator == nullptr) return 0;
    accumulator->mean = checked_allocate<float>(samples);
    accumulator->m2 = checked_allocate<float>(samples);
    accumulator->coverage = checked_allocate<std::uint16_t>(pixels);
    if (accumulator->mean == nullptr || accumulator->m2 == nullptr || accumulator->coverage == nullptr) {
        destroy(accumulator);
        return 0;
    }
    std::fill(accumulator->mean, accumulator->mean + samples, 0.0f);
    std::fill(accumulator->m2, accumulator->m2 + samples, 0.0f);
    std::fill(accumulator->coverage, accumulator->coverage + pixels, 0);
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(accumulator));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_accumulate(
    JNIEnv* env, jobject, jlong handle, jfloatArray frame_array, jint width, jint height,
    jint channels, jdouble cosine, jdouble sine, jdouble dx, jdouble dy,
    jdouble sigma_threshold, jdouble noise_floor, jint warmup_samples, jobject cancelled) {
    Accumulator* accumulator = from(handle);
    if (accumulator == nullptr || frame_array == nullptr || width <= 0 || height <= 0 ||
        (channels != 1 && channels != 3) ||
        accumulator->width != static_cast<std::uint32_t>(width) ||
        accumulator->height != static_cast<std::uint32_t>(height) ||
        accumulator->channels != static_cast<std::uint32_t>(channels)) return -1;
    const jsize frame_size = env->GetArrayLength(frame_array);
    const std::uint64_t expected = static_cast<std::uint64_t>(width) * height * channels;
    if (expected > static_cast<std::uint64_t>(frame_size)) return -1;
    float* frame = env->GetFloatArrayElements(frame_array, nullptr);
    if (frame == nullptr) return -1;
    jint rejected_pixels = 0;
    const jclass cancel_class = env->GetObjectClass(cancelled);
    const jmethodID is_cancelled = env->GetMethodID(cancel_class, "isCancelled", "()Z");
    if (is_cancelled == nullptr) {
        env->DeleteLocalRef(cancel_class);
        env->ReleaseFloatArrayElements(frame_array, frame, JNI_ABORT);
        return -1;
    }
    for (int y = 0; y < height; ++y) {
        if (y % 128 == 0 && env->CallBooleanMethod(cancelled, is_cancelled)) {
            env->DeleteLocalRef(cancel_class);
            env->ReleaseFloatArrayElements(frame_array, frame, JNI_ABORT);
            return -3;
        }
        for (std::uint32_t x = 0; x < accumulator->width; ++x) {
            const double sx = cosine * (x - dx) + sine * (y - dy);
            const double sy = -sine * (x - dx) + cosine * (y - dy);
            if (sx < -1e-7 || sy < -1e-7 || sx > width - 1.0 + 1e-7 ||
                sy > height - 1.0 + 1e-7) continue;
            const double fx = std::max(0.0, std::min(sx, width - 1.0));
            const double fy = std::max(0.0, std::min(sy, height - 1.0));
            const int ix = static_cast<int>(std::floor(fx));
            const int iy = static_cast<int>(std::floor(fy));
            const int ix1 = std::min(ix + 1, width - 1);
            const int iy1 = std::min(iy + 1, height - 1);
            const double ax = fx - ix;
            const double ay = fy - iy;
            const int p = y * width + x;
            const int weight = accumulator->coverage[p];
            if (weight >= 65535) {
                env->DeleteLocalRef(cancel_class);
                env->ReleaseFloatArrayElements(frame_array, frame, JNI_ABORT);
                return -2;
            }
            bool reject = false;
            float sampled[3];
            for (std::uint32_t ch = 0; ch < accumulator->channels; ++ch) {
                const int a = (iy * width + ix) * channels + ch;
                const int b = (iy * width + ix1) * channels + ch;
                const int c = (iy1 * width + ix) * channels + ch;
                const int d = (iy1 * width + ix1) * channels + ch;
                sampled[ch] = static_cast<float>(
                    ((frame[a] * (1 - ax) + frame[b] * ax) * (1 - ay) +
                     (frame[c] * (1 - ax) + frame[d] * ax) * ay));
                const int i = p * channels + ch;
                if (weight >= warmup_samples) {
                    float local_low = accumulator->mean[i];
                    float local_high = accumulator->mean[i];
                    for (int oy = -1; oy <= 1; ++oy) {
                        const int ny = std::max(0, std::min(y + oy, height - 1));
                        for (int ox = -1; ox <= 1; ++ox) {
                            const int nx = std::max(0, std::min<int>(x + ox, width - 1));
                            const float neighbor = accumulator->mean[
                                (ny * width + nx) * channels + ch];
                            local_low = std::min(local_low, neighbor);
                            local_high = std::max(local_high, neighbor);
                        }
                    }
                    const double gradient_allowance = (local_high - local_low) * 0.35;
                    const double measuredVariance = weight > 1
                        ? accumulator->m2[i] / static_cast<double>(weight - 1) : 0.0;
                    const double sigma = std::max(noise_floor, std::sqrt(std::max(0.0, measuredVariance)));
                    if (std::abs(sampled[ch] - accumulator->mean[i]) >
                        sigma_threshold * sigma + gradient_allowance + 1e-5) reject = true;
                }
            }
            if (reject) {
                ++rejected_pixels;
                continue;
            }
            const double next = weight + 1;
            for (std::uint32_t ch = 0; ch < accumulator->channels; ++ch) {
                const int i = p * channels + ch;
                const float delta = sampled[ch] - accumulator->mean[i];
                accumulator->mean[i] += static_cast<float>(delta / next);
                accumulator->m2[i] += static_cast<float>(
                    delta * (sampled[ch] - accumulator->mean[i]));
            }
            accumulator->coverage[p] = static_cast<std::uint16_t>(weight + 1);
        }
    }
    env->DeleteLocalRef(cancel_class);
    env->ReleaseFloatArrayElements(frame_array, frame, JNI_ABORT);
    return rejected_pixels;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_readRows(
    JNIEnv* env, jobject, jlong handle, jint first_row, jint row_count,
    jfloatArray mean_array, jfloatArray m2_array, jshortArray coverage_array) {
    Accumulator* accumulator = from(handle);
    if (accumulator == nullptr || first_row < 0 || row_count <= 0 ||
        static_cast<std::uint32_t>(first_row + row_count) > accumulator->height)
        return -1;
    const jsize samples = static_cast<jsize>(
        static_cast<std::uint64_t>(accumulator->width) * row_count * accumulator->channels);
    const jsize pixels = static_cast<jsize>(
        static_cast<std::uint64_t>(accumulator->width) * row_count);
    if (mean_array == nullptr || env->GetArrayLength(mean_array) < samples ||
        coverage_array == nullptr || env->GetArrayLength(coverage_array) < pixels)
        return -1;
    float* mean = env->GetFloatArrayElements(mean_array, nullptr);
    if (m2_array != nullptr && accumulator->m2 == nullptr) return -1;
    float* m2 = m2_array == nullptr ? nullptr : env->GetFloatArrayElements(m2_array, nullptr);
    auto* coverage = env->GetShortArrayElements(coverage_array, nullptr);
    if (mean == nullptr || coverage == nullptr || (m2_array != nullptr && m2 == nullptr)) {
        if (mean != nullptr) env->ReleaseFloatArrayElements(mean_array, mean, JNI_ABORT);
        if (m2 != nullptr) env->ReleaseFloatArrayElements(m2_array, m2, JNI_ABORT);
        if (coverage != nullptr) env->ReleaseShortArrayElements(coverage_array, coverage, JNI_ABORT);
        return -1;
    }
    const std::size_t row_samples =
        static_cast<std::size_t>(accumulator->width) * accumulator->channels;
    const std::size_t offset = static_cast<std::size_t>(first_row) * row_samples;
    std::copy(accumulator->mean + offset, accumulator->mean + offset + samples, mean);
    if (m2 != nullptr) std::copy(accumulator->m2 + offset, accumulator->m2 + offset + samples, m2);
    const std::size_t pixel_offset = static_cast<std::size_t>(first_row) * accumulator->width;
    for (jsize i = 0; i < pixels; ++i)
        coverage[i] = static_cast<jshort>(accumulator->coverage[pixel_offset + i]);
    env->ReleaseFloatArrayElements(mean_array, mean, 0);
    if (m2 != nullptr) env->ReleaseFloatArrayElements(m2_array, m2, 0);
    env->ReleaseShortArrayElements(coverage_array, coverage, 0);
    return samples;
}

extern "C" JNIEXPORT void JNICALL
Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_releaseM2(
    JNIEnv*, jobject, jlong handle) {
    Accumulator* accumulator = from(handle);
    if (accumulator == nullptr) return;
    std::free(accumulator->m2);
    accumulator->m2 = nullptr;
}


extern "C" JNIEXPORT void JNICALL
Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_release(
    JNIEnv*, jobject, jlong handle) {
    destroy(from(handle));
}

