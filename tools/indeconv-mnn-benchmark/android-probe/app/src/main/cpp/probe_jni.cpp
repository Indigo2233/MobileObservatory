#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>

#include <cstdio>
#include <string>
#include <vector>

int indeconvBenchmarkMain(int argc, char** argv);

namespace {

using ClInt = int;
using ClUint = unsigned int;
using ClPlatformId = void*;
using ClGetPlatformIDs = ClInt (*)(ClUint, ClPlatformId*, ClUint*);

std::string fromJava(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void probeOpenCl() {
    dlerror();
    void* library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    const char* loadError = dlerror();
    std::fprintf(stderr, "APK_OPENCL dlopen=%s error=%s\n",
                 library == nullptr ? "failed" : "ok",
                 loadError == nullptr ? "none" : loadError);
    if (library == nullptr) return;

    dlerror();
    auto getPlatformIDs = reinterpret_cast<ClGetPlatformIDs>(dlsym(library, "clGetPlatformIDs"));
    const char* symbolError = dlerror();
    if (getPlatformIDs == nullptr) {
        std::fprintf(stderr, "APK_OPENCL clGetPlatformIDs_symbol=failed error=%s\n",
                     symbolError == nullptr ? "none" : symbolError);
        dlclose(library);
        return;
    }

    ClUint platformCount = 0;
    const ClInt status = getPlatformIDs(0, nullptr, &platformCount);
    std::fprintf(stderr, "APK_OPENCL clGetPlatformIDs_status=%d platform_count=%u\n",
                 status, platformCount);
    dlclose(library);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_indeconvprobe_ProbeActivity_runNative(
        JNIEnv* env,
        jclass,
        jstring modelPath,
        jstring inputPath,
        jstring outputDirectory,
        jstring mode,
        jint warmups,
        jint runs,
        jint threads,
        jstring logPath) {
    const std::string model = fromJava(env, modelPath);
    const std::string input = fromJava(env, inputPath);
    const std::string output = fromJava(env, outputDirectory);
    const std::string modeValue = fromJava(env, mode);
    const std::string log = fromJava(env, logPath);

    const int logFd = open(log.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (logFd < 0) return 70;
    const int savedStdout = dup(STDOUT_FILENO);
    const int savedStderr = dup(STDERR_FILENO);
    dup2(logFd, STDOUT_FILENO);
    dup2(logFd, STDERR_FILENO);
    close(logFd);

    probeOpenCl();
    std::fflush(stdout);
    std::fflush(stderr);

    std::vector<std::string> values = {
            "indeconv_bench",
            model,
            input,
            output,
            modeValue,
            std::to_string(warmups),
            std::to_string(runs),
            std::to_string(threads)};
    std::vector<char*> argv;
    argv.reserve(values.size());
    for (std::string& value : values) argv.push_back(value.data());
    const int result = indeconvBenchmarkMain(static_cast<int>(argv.size()), argv.data());

    std::fflush(stdout);
    std::fflush(stderr);
    dup2(savedStdout, STDOUT_FILENO);
    dup2(savedStderr, STDERR_FILENO);
    close(savedStdout);
    close(savedStderr);
    __android_log_print(ANDROID_LOG_INFO, "InDeConvProbe", "%s finished with %d",
                        modeValue.c_str(), result);
    return result;
}
