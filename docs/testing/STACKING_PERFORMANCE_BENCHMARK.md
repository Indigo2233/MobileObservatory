# Windows JVM allocation and latency comparison

Baseline: b081540d484fdcec4edb5e2dda548489b665e497. Current: this PR follow-up, pinned by the source SHA-256 manifest in stacking-performance.json. Baseline files were extracted with git show, not rewritten approximations.

Environment: Windows 11, Intel Core Ultra 9 285H, 16 logical processors, 31.43 GiB installed memory; OpenJDK 17.0.20.1 HotSpot, G1, fixed 512 MiB heap, ActiveProcessorCount=2. These are synthetic desktop measurements, not Android phone, camera USB, or thermal measurements. Other root-agent Gradle/emulator/native build work could overlap and affect elapsed time.

All inputs are 1024 x 768. Final runs 2 and 3 use reversed order (current then baseline, followed by baseline then current). Each case is a fresh JVM. Normalization has 30 warmups and 300 timed calls; DDFAPD has 20 warmups and 40 calls; planetary and both postprocessing cases have 12 warmups and 40 calls; rejected-frame publication has 10 warmups and 40 calls after one accepted setup frame. Exploratory run 1 is retained but excluded from the tables because it had fewer warmups/samples.

## Comparison

Allocation is weighted mean bytes per operation, shown in decimal MB. P95 is the range of the two independently measured per-run P95 values, not a confidence interval. Queue allocation is per offered frame, including dropped frames.

| Case | Baseline MB/op | Current MB/op | Allocation change | Baseline P95 ms | Current P95 ms |
|---|---:|---:|---:|---:|---:|
| Owned recording normalization | 1.573769 | 0.000889 | -99.94% | 1.33-1.49 | 0.88-1.00 |
| DDFAPD workspace | 35.394686 | 9.442335 | -73.32% | 33.63-34.19 | 29.83-30.63 |
| Planetary shared preparation | 6.366055 | 3.283691 | -48.42% | 28.47-30.95 | 26.97-27.60 |
| Default one-shot process | 35.815414 | 22.604372 | -36.89% | 120.59-198.12 | 130.91-163.21 |
| Repeated cached preview | 35.770462 | 0.001933 | -99.99% | 134.83-235.41 | 84.79-93.88 |
| Rejected-frame publication | 21.096293 | 8.677003 | -58.87% | 10.57-10.82 | 9.31-9.52 |
| 30 FPS bounded queue | 11.596765 | 5.074678 | -56.24% | 253.62-293.38 | 205.83-222.07 |

The allocation reductions are consistent with removal of full-frame intermediate arrays. They do not establish that every path is faster: the one-shot default process path is slower here, while the cached repeated-preview path is faster. The 30 FPS producer saturates this single science worker in both versions; a roughly 75% drop rate is a capacity observation, not an Android performance claim.

## Throughput, GC, heap and drops

Allocation throughput is total measured bytes / elapsed wall time for both final runs. GC figures are the sum of the two measured intervals. Heap is the maximum sampled used JVM heap across them (includes retained inputs/workspace and uncollected garbage).

| Case | Version | Allocation MB/s | GC collections | GC collection ms | Peak sampled MiB | Queue dropped / offered |
|---|---|---:|---:|---:|---:|---:|
| Owned recording normalization | baseline | 1518.844 | 4 | 2 | 229.06 | N/A |
| Owned recording normalization | current | 1.191 | 0 | 0 | 5.06 | N/A |
| DDFAPD workspace | baseline | 1156.560 | 14 | 8 | 219.03 | N/A |
| DDFAPD workspace | current | 328.324 | 4 | 2 | 226.02 | N/A |
| Planetary shared preparation | baseline | 236.617 | 2 | 0 | 233.04 | N/A |
| Planetary shared preparation | current | 128.422 | 0 | 0 | 191.04 | N/A |
| Default one-shot process | baseline | 297.214 | 8 | 13 | 362.05 | N/A |
| Default one-shot process | current | 172.638 | 2 | 2 | 498.06 | N/A |
| Repeated cached preview | baseline | 272.864 | 8 | 12 | 359.05 | N/A |
| Repeated cached preview | current | 0.025 | 0 | 0 | 12.07 | N/A |
| Rejected-frame publication | baseline | 2338.838 | 8 | 4 | 259.38 | N/A |
| Rejected-frame publication | current | 1119.600 | 2 | 1 | 343.61 | N/A |
| 30 FPS bounded queue | baseline | 329.419 | 24 | 11 | 246.34 | 224/300 (74.67%) |
| 30 FPS bounded queue | current | 144.268 | 10 | 5 | 261.36 | 224/300 (74.67%) |

GC timing limitation: GarbageCollectorMXBean.collectionTime is a coarse integer-millisecond cumulative collection-time counter. It is not an exact stop-the-world pause trace; parallel/concurrent activity and sub-millisecond work are not represented as individual pauses. A zero result does not mean zero pauses. Exact Android ART pause comparisons require device traces.

Heap limitation: MemoryMXBean used heap is sampled every 2 ms by a separate daemon. Short spikes may be missed; lower allocation need not lower this peak because collection thresholds change. For example, a retained workspace and less-frequent GC can produce a larger sampled heap despite fewer newly allocated bytes.

Thread allocation is measured using HotSpot ThreadMXBean for the producer/main and the named science worker threads. Harness timing, state objects and callback overhead on those threads are included. Sampler, JVM collector/JIT threads and the small Flow-observer thread are excluded. Explicit GC is requested before each interval, outside timing/counters; there is no forced GC within an interval.

## Workload boundaries

- Recording normalization: both versions copy the same MONO16 camera input into a reused exclusively owned recording buffer. Baseline calls the real safe allocating normalize API; current calls normalizeOwnedInPlace. The camera input itself is never changed. The optimization avoids one 1,572,864-byte output array per call, not all object allocation.
- DDFAPD: real old source allocates scratch per call; current holds one warmed workspace. Both still allocate an owned RGB result. Input/output digests match. Workspace startup allocation is outside the steady-state interval, but its retained memory is included in heap samples.
- Planetary: actual align, warp and edgeResidual on a textured RGB planet with 18 selected local alignment points, maximum 24. Baseline separately prepares luminance through the old image overloads; current shares one prepareFrame. This isolates preparation reuse, not full-file decode/quality analysis/export.
- One-shot postprocessing: both versions call the public process API with NATURAL preset and automatic background correction and return a complete RGB Float image. Repeated preview: the same immutable RGB star field is reused while brightness cycles; baseline materializes full output, current uses BackgroundModelCache plus PreparedStack.writeRow into a reused row. A common row checksum stands in for Bitmap upload, so Android native Bitmap costs are excluded.
- Rejected snapshots: actual LiveStackSession and LiveDeepSkyEngine receive one accepted star field then black rejected images. The decoder intentionally returns fixed immutable decoded RGB fixtures to isolate publication. Real raw callback buffer copying remains included. An injected publication clock makes every rejection eligible; elapsed time is measured with the real clock. Current shares the last immutable image/weight arrays while changing statistics.
- Queue: actual LiveStackSession, LinearPixelDecoder DDFAPD and LiveDeepSkyEngine; two waiting slots, one science worker, raw Bayer16 frame every 1/30 s for 150 offered frames. One accepted frame initializes the session. Measurement includes final drain and pause. Processing P95 uses observed StateFlow processingMs updates; timingSamples in each JSON records exactly how many completed frames were observed. No USB, camera, Android display or Bitmap work is simulated.

All paired microbenchmark checksums match. Separate pre-existing postprocessing reference artifacts build/post-reference/baseline.txt and after.txt cover ten mono/RGB/filter/background combinations; their hashes should be checked independently when reproducing. This benchmark is performance evidence, not a replacement for precision and lifecycle tests.

## Reproduce

Run from repository PowerShell with JAVA_HOME configured; see scripts/benchmark_stacking for dependency-path parameters. It directly invokes the installed Kotlin compiler jar; it does not invoke Gradle.

    ./scripts/benchmark_stacking/Run-Benchmark.ps1 -Variant current -Repeat 2
    ./scripts/benchmark_stacking/Run-Benchmark.ps1 -Variant baseline -Repeat 2
    ./scripts/benchmark_stacking/Run-Benchmark.ps1 -Variant baseline -Repeat 3 -SkipCompile
    ./scripts/benchmark_stacking/Run-Benchmark.ps1 -Variant current -Repeat 3 -SkipCompile


The committed stacking-performance.json contains the measured final runs, environment and source hashes. Raw generated reports remain under build/performance.
