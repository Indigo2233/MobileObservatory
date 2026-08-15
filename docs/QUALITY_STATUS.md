# Quality and validation status

Last reviewed: 2026-08-14

This document distinguishes automated evidence, hardware evidence, and pending
product claims. A compiled implementation is not recorded as hardware-validated.

## Automated evidence

The `Android CI` workflow performs the following checks on every pull request and
push to `main`:

- JVM unit tests;
- compilation of Compose instrumentation tests;
- debug APK compilation;
- signed release APK compilation with an ephemeral non-debug certificate;
- release certificate identity verification;
- Android lint;
- report upload.

Instrumentation tests are compiled in CI and still require an Android device or a
compatible emulator for execution. The APK contains arm64 vendor native libraries,
which prevents treating a standard x86_64 hosted emulator as representative.

## Hardware evidence

Current public evidence remains incomplete across the full supported matrix.
Contributors can submit de-identified results with the `Hardware validation result`
issue form. Every record must identify the tested commit, Android device, equipment,
firmware or SDK, transport, procedure, duration, outcome, and measurements.

Known pending gates include:

- cross-vendor camera preview, capture, reconnect, and long-duration runs;
- mount motion and physical STOP behavior across LX200/OnStep, iOptron, and SynScan;
- main-camera plus guide-camera combinations;
- Android 12–15 permission and reconnect coverage;
- accessibility at 200% font scale on physical small-screen devices;
- production-signed release upgrade testing.

## Phone plate-solving maturity

The minimal solver path and synthetic regression tests are implemented. The public
de-identified real-capture result set currently contains no completed 30-group P6
report. Success rate, false-solve rate, center error, rotation error, FOV error, and
runtime therefore remain unverified product metrics.

The acceptance target stays:

- at least 30 capture groups across focal lengths and sky conditions;
- zero accepted false solves;
- explicit diagnostic failures;
- published aggregate accuracy and runtime statistics.

Until that report exists, documentation and release notes must describe phone
plate solving as experimental.

## Release readiness

Release builds require an independent signing identity and generate checksums,
build information, application source, and Stellarium source. Public release still
requires the go/no-go gates in `docs/RELEASE_PROCESS.md`. R8 remains deferred until
vendor SDK and JNI paths complete the hardware matrix with shrinking enabled.
