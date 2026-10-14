# InDeConv MNN Android benchmark

This harness measures the local InDeConv model on Android with one real FITS-derived input. The model and fixtures stay outside the APK and Git repository.

Prepared configuration:

- MNN 3.6.1 at commit `d407447ed56c4121a11ccbd266dc184ca1ead0c2`
- arm64 Android API 26 runtime
- OpenCL Image FP16
- OpenCL Buffer FP16
- OpenCL Buffer FP32
- Vulkan Image FP16
- Vulkan Image FP32
- ARMv8.2 CPU FP16
- CPU FP32
- one cold inference, one warmup, and four measured inferences per mode
- each timed inference includes host input upload, model execution, synchronization, and host output readback
- process RSS/HWM, MNN session memory, output tensor, and device thermal snapshots
- ten seconds of cooldown before each mode, configurable with `-CooldownSeconds`

The release benchmark disables `MNN_GPU_TIME_PROFILE`; per-operator GPU timing adds substantial synchronization overhead and changes wall-clock results. KleidiAI is also disabled for a conservative Android baseline.

The benchmark uses these fixed external locations:

```text
D:\Unity\MobileObservatory-BenchmarkCache\fixtures
D:\Unity\MobileObservatory-BenchmarkCache\runtime
D:\Unity\MobileObservatory-BenchmarkCache\results
```

Prepare or rebuild the environment:

```powershell
.\tools\indeconv-mnn-benchmark\Prepare-MnnBenchmark.ps1
```

After connecting and authorizing one arm64 Android phone, run the complete matrix:

```powershell
.\tools\indeconv-mnn-benchmark\Run-MnnBenchmark.ps1
```

For a selected device or a smaller matrix:

```powershell
.\tools\indeconv-mnn-benchmark\Run-MnnBenchmark.ps1 `
    -Serial 10AE6L10NS000LC `
    -Modes cpu-fp16,cpu-fp32,vulkan-image
```

Each result directory contains `comparison.json`, device metadata, thermal snapshots, one `summary.json` and `output.bin` per mode, and complete MNN logs. `Compare-MnnResults.py` checks every output against the desktop ORT FP32 reference and reports MAE, maximum error, RMSE, and PSNR.

## MT6989 baseline

Measured on a vivo V2324HA (`MT6989`, Mali GPU, Android 16) with the real 1024 x 1024 H-alpha FITS crop. The FP16 MNN model is 95.5 MB and remains outside the APK.

| Backend | Threads | Mean | Peak RSS | MAE vs ORT FP32 | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| CPU FP16 | 4 | 1.92 s | 776 MB | 3.80e-4 | Recommended |
| CPU FP32 | 4 | 3.03 s | 1,325 MB | 8.80e-5 | Accuracy fallback |
| Vulkan Image FP16 | GPU | 2.84 s | 1,576 MB | 3.16e-4 | Slower and larger |

CPU FP16 thread scaling on this device was approximately 4.10 s (1 thread), 2.60 s (2 threads), 1.92 s (4 threads), and 3.40 s (8 threads). Four threads are the default baseline.

MNN may alias session inputs with reusable internal buffers. The harness therefore copies both image and parameter tensors before every inference. Reusing a session without refreshing its inputs produces a correct first output followed by corrupted outputs.

The ADB shell process on this device reports `CL_PLATFORM_NOT_FOUND_KHR`, so OpenCL Image and Buffer modes cannot be evaluated from `/data/local/tmp`. OpenCL remains an APK-process follow-up because vendor library visibility can differ inside the application process.

## APK-process OpenCL result

The standalone debug probe under `android-probe` packages the MNN runtime while keeping the model and image fixture in the app-private files directory. Its manifest declares optional access to `libOpenCL.so`. On the same vivo V2324HA, the APK process successfully loaded `libOpenCL.so`, and `clGetPlatformIDs` returned one platform. MNN reported OpenCL (`3`) as the actual primary backend for both GPU modes.

Each row below was measured in a fresh foreground app process. This avoids carrying the OpenCL Image allocator peak into the following mode.

| Backend | Mean | Session creation | Peak RSS | MAE vs ORT FP32 | Current accuracy gate |
| --- | ---: | ---: | ---: | ---: | --- |
| CPU FP16, 4 threads | 1.027 s | 1.27 s | 945 MB | 3.80e-4 | Pass |
| OpenCL Buffer FP16 | 0.893 s | 12.25 s | 984 MB | 8.74e-4 | Fail |
| OpenCL Image FP16 | 1.208 s | 11.49 s | 2,200 MB | 8.32e-4 | Fail |

OpenCL Buffer is 13% faster than CPU FP16 for repeated inference and uses about 39 MB more peak RSS. Its cold session creation is about 11 seconds longer, and its output exceeds the current MAE limit of `5e-4`. OpenCL Image is slower than CPU FP16 and has excessive peak memory use. The next device run should test OpenCL Buffer FP32 and persistent tuning-cache reuse before selecting a production GPU path.

The foreground APK CPU result is substantially faster than the ADB-shell CPU result. Product decisions should use foreground-app measurements because Android schedules the two process classes differently.

Full APK-process results are stored outside the repository at:

```text
D:\Unity\MobileObservatory-BenchmarkCache\results\20261014-113945-10AE6L10NS000LC-apk\comparison.json
```

Do not enable `MNN_PIPELINE_DEBUG` in the Android benchmark build. It caused a repeatable exit 139 during session resize on this model.
