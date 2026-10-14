#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#include <MNN/MNNForwardType.h>
#include <MNN/Tensor.hpp>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <map>
#include <memory>
#include <numeric>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;

struct Mode {
    MNNForwardType backend;
    MNN::BackendConfig::PrecisionMode precision;
    int gpuMode;
};

long readStatusKb(const std::string& key);

class MemorySampler {
public:
    MemorySampler() : peakRssKb(readStatusKb("VmRSS")), peakHwmKb(readStatusKb("VmHWM")) {
        worker = std::thread([this]() {
            while (sampling.load(std::memory_order_relaxed)) {
                peakRssKb.store(std::max(peakRssKb.load(), readStatusKb("VmRSS")));
                peakHwmKb.store(std::max(peakHwmKb.load(), readStatusKb("VmHWM")));
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            }
        });
    }

    ~MemorySampler() { stop(); }

    void stop() {
        sampling.store(false, std::memory_order_relaxed);
        if (worker.joinable()) worker.join();
    }

    std::atomic<long> peakRssKb;
    std::atomic<long> peakHwmKb;

private:
    std::atomic<bool> sampling{true};
    std::thread worker;
};

double elapsedMs(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

long readStatusKb(const std::string& key) {
    std::ifstream stream("/proc/self/status");
    std::string line;
    while (std::getline(stream, line)) {
        if (line.rfind(key + ":", 0) != 0) {
            continue;
        }
        std::istringstream parser(line.substr(key.size() + 1));
        long value = 0;
        parser >> value;
        return value;
    }
    return 0;
}

bool readFloats(std::ifstream& input, float* destination, size_t count) {
    input.read(reinterpret_cast<char*>(destination), static_cast<std::streamsize>(count * sizeof(float)));
    return input.good() || input.gcount() == static_cast<std::streamsize>(count * sizeof(float));
}

bool writeFloats(const std::string& path, const float* source, size_t count) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output.write(reinterpret_cast<const char*>(source), static_cast<std::streamsize>(count * sizeof(float)));
    return output.good();
}

Mode parseMode(const std::string& name, int threads) {
    if (name == "opencl-image") {
        return {MNN_FORWARD_OPENCL, MNN::BackendConfig::Precision_Low,
                MNN_GPU_TUNING_FAST | MNN_GPU_MEMORY_IMAGE};
    }
    if (name == "opencl-buffer") {
        return {MNN_FORWARD_OPENCL, MNN::BackendConfig::Precision_Low,
                MNN_GPU_TUNING_FAST | MNN_GPU_MEMORY_BUFFER};
    }
    if (name == "opencl-buffer-fp32") {
        return {MNN_FORWARD_OPENCL, MNN::BackendConfig::Precision_High,
                MNN_GPU_TUNING_FAST | MNN_GPU_MEMORY_BUFFER};
    }
    if (name == "vulkan-image") {
        return {MNN_FORWARD_VULKAN, MNN::BackendConfig::Precision_Low, MNN_GPU_TUNING_WIDE};
    }
    if (name == "vulkan-fp32") {
        return {MNN_FORWARD_VULKAN, MNN::BackendConfig::Precision_High, MNN_GPU_TUNING_WIDE};
    }
    if (name == "cpu-fp16") {
        return {MNN_FORWARD_CPU, MNN::BackendConfig::Precision_Low, threads};
    }
    if (name == "cpu-fp32") {
        return {MNN_FORWARD_CPU, MNN::BackendConfig::Precision_High, threads};
    }
    throw std::runtime_error("unknown backend mode: " + name);
}

std::string jsonArray(const std::vector<double>& values) {
    std::ostringstream output;
    output << '[';
    for (size_t index = 0; index < values.size(); ++index) {
        if (index > 0) output << ',';
        output << std::fixed << std::setprecision(4) << values[index];
    }
    output << ']';
    return output.str();
}

int run(int argc, char** argv) {
    if (argc != 8) {
        std::cerr << "usage: indeconv_bench MODEL INPUT OUTPUT_DIR MODE WARMUPS RUNS THREADS\n";
        return 2;
    }

    const std::string modelPath = argv[1];
    const std::string inputPath = argv[2];
    const std::string outputDir = argv[3];
    const std::string modeName = argv[4];
    const int warmups = std::max(0, std::stoi(argv[5]));
    const int measuredRuns = std::max(1, std::stoi(argv[6]));
    const int threads = std::max(1, std::stoi(argv[7]));
    const Mode mode = parseMode(modeName, threads);

    constexpr size_t imageValues = 1ULL * 1024 * 1024 * 3;
    constexpr size_t parameterValues = 6;
    constexpr size_t outputValues = 1ULL * 512 * 512 * 3;

    std::vector<float> parameters(parameterValues);
    std::vector<float> image(imageValues);
    {
        std::ifstream input(inputPath, std::ios::binary);
        if (!input || !readFloats(input, parameters.data(), parameters.size()) ||
            !readFloats(input, image.data(), image.size())) {
            throw std::runtime_error("invalid input fixture: " + inputPath);
        }
        input.peek();
        if (!input.eof()) {
            throw std::runtime_error("input fixture has trailing data");
        }
    }

    MemorySampler memorySampler;

    const auto modelStart = Clock::now();
    std::shared_ptr<MNN::Interpreter> net(
        MNN::Interpreter::createFromFile(modelPath.c_str()), MNN::Interpreter::destroy);
    const double modelLoadMs = elapsedMs(modelStart);
    if (!net) throw std::runtime_error("MNN could not load model");

    const std::string cachePath = outputDir + "/tuning.cache";
    net->setCacheFile(cachePath.c_str());
    MNN::BackendConfig backendConfig;
    backendConfig.precision = mode.precision;
    backendConfig.power = MNN::BackendConfig::Power_High;
    backendConfig.memory = MNN::BackendConfig::Memory_Normal;

    MNN::ScheduleConfig schedule;
    schedule.type = mode.backend;
    schedule.backupType = MNN_FORWARD_CPU;
    schedule.numThread = mode.gpuMode;
    schedule.backendConfig = &backendConfig;

    const auto sessionStart = Clock::now();
    MNN::Session* session = net->createSession(schedule);
    const double sessionCreateMs = elapsedMs(sessionStart);
    if (!session) throw std::runtime_error("MNN could not create session");

    int resizeStatus = -1;
    net->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatus);
    if (resizeStatus != 0) {
        throw std::runtime_error("MNN session resize failed: " + std::to_string(resizeStatus));
    }

    float sessionMemoryMb = 0.0f;
    float flopsM = 0.0f;
    int backendTypes[8];
    std::fill(std::begin(backendTypes), std::end(backendTypes), -1);
    net->getSessionInfo(session, MNN::Interpreter::MEMORY, &sessionMemoryMb);
    net->getSessionInfo(session, MNN::Interpreter::FLOPS, &flopsM);
    net->getSessionInfo(session, MNN::Interpreter::BACKENDS, backendTypes);

    MNN::Tensor* inputTensor = net->getSessionInput(session, "input");
    MNN::Tensor* parameterTensor = net->getSessionInput(session, "parameters");
    if (!inputTensor || !parameterTensor) throw std::runtime_error("required model inputs are missing");
    if (static_cast<size_t>(inputTensor->elementSize()) != imageValues ||
        static_cast<size_t>(parameterTensor->elementSize()) != parameterValues) {
        throw std::runtime_error("unexpected input tensor shape");
    }

    // Match the PyMNN reference path exactly.  Constructing these host tensors
    // from the device tensors rewrites the logical NHWC shapes as NCHW and
    // causes copyFromHostTensor/copyToHostTensor to permute the model data.
    std::unique_ptr<MNN::Tensor> imageHost(
        MNN::Tensor::create<float>({1, 1024, 1024, 3}, nullptr, MNN::Tensor::CAFFE));
    std::unique_ptr<MNN::Tensor> parameterHost(
        MNN::Tensor::create<float>({1, 6}, nullptr, MNN::Tensor::CAFFE));
    if (!imageHost || !parameterHost) throw std::runtime_error("could not allocate host input tensors");
    std::memcpy(imageHost->host<float>(), image.data(), image.size() * sizeof(float));
    std::memcpy(parameterHost->host<float>(), parameters.data(), parameters.size() * sizeof(float));
    inputTensor->copyFromHostTensor(imageHost.get());
    parameterTensor->copyFromHostTensor(parameterHost.get());

    std::unique_ptr<MNN::Tensor> imageRoundTrip(
        MNN::Tensor::create<float>({1, 1024, 1024, 3}, nullptr, MNN::Tensor::CAFFE));
    std::unique_ptr<MNN::Tensor> parameterRoundTrip(
        MNN::Tensor::create<float>({1, 6}, nullptr, MNN::Tensor::CAFFE));
    inputTensor->copyToHostTensor(imageRoundTrip.get());
    parameterTensor->copyToHostTensor(parameterRoundTrip.get());
    double imageRoundTripMae = 0.0;
    double parameterRoundTripMae = 0.0;
    for (size_t index = 0; index < imageValues; ++index) {
        imageRoundTripMae += std::abs(imageRoundTrip->host<float>()[index] - image[index]);
    }
    for (size_t index = 0; index < parameterValues; ++index) {
        parameterRoundTripMae += std::abs(parameterRoundTrip->host<float>()[index] - parameters[index]);
    }
    imageRoundTripMae /= static_cast<double>(imageValues);
    parameterRoundTripMae /= static_cast<double>(parameterValues);
    std::cout << "INPUT_CHECK image_mae=" << imageRoundTripMae
              << " parameter_mae=" << parameterRoundTripMae
              << " input_dim_type=" << static_cast<int>(inputTensor->getDimensionType())
              << " output_expected_dim_type=" << static_cast<int>(MNN::Tensor::CAFFE)
              << std::endl;

    MNN::Tensor* outputTensor = net->getSessionOutput(session, "output");
    if (!outputTensor || static_cast<size_t>(outputTensor->elementSize()) != outputValues) {
        throw std::runtime_error("unexpected output tensor shape");
    }
    std::unique_ptr<MNN::Tensor> outputHost(
        MNN::Tensor::create<float>({1, 512, 512, 3}, nullptr, MNN::Tensor::CAFFE));
    if (!outputHost) throw std::runtime_error("could not allocate host output tensor");

    auto timedRun = [&]() {
        // MNN may alias session inputs with reusable internal buffers. Refresh
        // both inputs before every invocation, as the production tile loop does.
        const auto start = Clock::now();
        inputTensor->copyFromHostTensor(imageHost.get());
        parameterTensor->copyFromHostTensor(parameterHost.get());
        const MNN::ErrorCode status = net->runSession(session);
        if (status != MNN::NO_ERROR) {
            throw std::runtime_error("MNN inference failed: " + std::to_string(static_cast<int>(status)));
        }
        outputTensor->copyToHostTensor(outputHost.get());
        return elapsedMs(start);
    };

    const double firstRunMs = timedRun();
    std::vector<double> warmupTimes;
    for (int index = 0; index < warmups; ++index) warmupTimes.push_back(timedRun());

    std::vector<double> runTimes;
    for (int index = 0; index < measuredRuns; ++index) {
        const double value = timedRun();
        runTimes.push_back(value);
        std::cout << "BENCH_RUN mode=" << modeName << " index=" << index + 1
                  << " wall_ms=" << std::fixed << std::setprecision(4) << value
                  << " rss_mb=" << readStatusKb("VmRSS") / 1024.0 << std::endl;
    }

    const float* outputData = outputHost->host<float>();
    if (!writeFloats(outputDir + "/output.bin", outputData, outputValues)) {
        throw std::runtime_error("could not write output tensor");
    }

    size_t finiteValues = 0;
    double sampledMean = 0.0;
    size_t sampledCount = 0;
    float outputMin = std::numeric_limits<float>::infinity();
    float outputMax = -std::numeric_limits<float>::infinity();
    for (size_t index = 0; index < outputValues; ++index) {
        const float value = outputData[index];
        if (std::isfinite(value)) {
            ++finiteValues;
            outputMin = std::min(outputMin, value);
            outputMax = std::max(outputMax, value);
        }
        if (index % 4096 == 0 && std::isfinite(value)) {
            sampledMean += value;
            ++sampledCount;
        }
    }
    if (sampledCount > 0) {
        sampledMean /= static_cast<double>(sampledCount);
    }

    net->updateCacheFile(session);
    const double meanMs = std::accumulate(runTimes.begin(), runTimes.end(), 0.0) / runTimes.size();
    std::vector<double> sortedTimes = runTimes;
    std::sort(sortedTimes.begin(), sortedTimes.end());
    const double medianMs = sortedTimes[sortedTimes.size() / 2];

    memorySampler.stop();

    std::ofstream summary(outputDir + "/summary.json", std::ios::trunc);
    summary << std::fixed << std::setprecision(6)
            << "{\n"
            << "  \"mode\": \"" << modeName << "\",\n"
            << "  \"requested_backend\": " << static_cast<int>(mode.backend) << ",\n"
            << "  \"actual_primary_backend\": " << backendTypes[0] << ",\n"
            << "  \"precision\": " << static_cast<int>(mode.precision) << ",\n"
            << "  \"gpu_mode_or_threads\": " << mode.gpuMode << ",\n"
            << "  \"model_load_ms\": " << modelLoadMs << ",\n"
            << "  \"session_create_ms\": " << sessionCreateMs << ",\n"
            << "  \"first_run_ms\": " << firstRunMs << ",\n"
            << "  \"warmup_ms\": " << jsonArray(warmupTimes) << ",\n"
            << "  \"measured_ms\": " << jsonArray(runTimes) << ",\n"
            << "  \"mean_ms\": " << meanMs << ",\n"
            << "  \"median_ms\": " << medianMs << ",\n"
            << "  \"session_memory_mb\": " << sessionMemoryMb << ",\n"
            << "  \"flops_m\": " << flopsM << ",\n"
            << "  \"peak_rss_mb\": " << memorySampler.peakRssKb.load() / 1024.0 << ",\n"
            << "  \"vm_hwm_mb\": " << memorySampler.peakHwmKb.load() / 1024.0 << ",\n"
            << "  \"finite_values\": " << finiteValues << ",\n"
            << "  \"output_values\": " << outputValues << ",\n"
            << "  \"output_min\": ";
    if (finiteValues > 0) summary << outputMin; else summary << "null";
    summary << ",\n  \"output_max\": ";
    if (finiteValues > 0) summary << outputMax; else summary << "null";
    summary << ",\n  \"sampled_mean\": ";
    if (sampledCount > 0) summary << sampledMean; else summary << "null";
    summary << "\n}\n";
    summary.close();

    std::cout << "BENCH_DONE mode=" << modeName << " mean_ms=" << meanMs
              << " peak_rss_mb=" << memorySampler.peakRssKb.load() / 1024.0
              << " finite_values=" << finiteValues << '/' << outputValues << std::endl;
    return finiteValues == outputValues ? 0 : 3;
}

}  // namespace

int indeconvBenchmarkMain(int argc, char** argv) {
    try {
        return run(argc, argv);
    } catch (const std::exception& error) {
        std::cerr << "BENCH_FAILED error=" << error.what() << std::endl;
        return 1;
    }
}

#ifndef INDECONV_BENCH_NO_MAIN
int main(int argc, char** argv) {
    return indeconvBenchmarkMain(argc, argv);
}
#endif
