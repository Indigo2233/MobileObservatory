# PHD2 guiding numerical core

Upstream: https://github.com/OpenPHDGuiding/phd2

Pinned commit: `70e9fd178b3122b6553646c60f462cb09335dcc5`.

- `src/gaussian_process*`, `src/covariance_functions*`, `tools/math_tools*`:
  `contributions/MPI_IS_gaussian_process` in upstream PHD2.
- `src/circbuf.h`, `src/zfilterfactory.*`: upstream `src/`.
- `LICENSE.txt`: upstream BSD license. Original per-file notices are retained.

Local changes are limited to an injectable monotonic clock (`Now()` and
`SetMonotonicSeconds`) for acquisition timestamps/deterministic replay, standard
includes/error type instead of the wxWidgets application header in ZFilterFactory,
and an `M_PI` definition for strict host compilers. Numerical GP, kernel, FFT,
period estimation and filter coefficient calculations are unchanged.

`eigen/` contains Eigen 3.4.0 `Eigen/`, the unsupported FFT module, and COPYING files.
Source: https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz

Archive SHA-256: `8586084f71f9bde545ee7fa6d00288b264a2b7ac3607b974e54d13e7162c1c72`.
Compile with `EIGEN_MPL2_ONLY` and `EIGEN_DONT_PARALLELIZE`.

The Android target `phd2_guiding` has its own SONAME and does not link or replace
vendor camera/USB libraries. Host JVM tests compile the same sources as a JNI
library using `g++` and the current JDK headers. See `docs/GUIDING_PHD2_ALIGNMENT.md`.
