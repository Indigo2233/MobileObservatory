# Indigo Observatory v1.0.7 validation record

- Validation date: 2026-10-10
- Source commit: `4e77c652b685f6d96aabf82cffa833cc446a71c2`
- Version: `1.0.7` (`versionCode 43`)
- Release ABI: `arm64-v8a`
- Sequence entry points: disabled
- Stellarium assets: included

## Automated validation

| Check | Environment | Result |
| --- | --- | --- |
| JVM unit tests | Windows, JDK 17/Gradle | Passed |
| Android lint | Debug and release vital lint | Passed |
| UI instrumentation compile | Android test APK | Passed |
| Star-map projection tests | Node.js, 8 tests | Passed |
| Portrait sequence UI | Nox Android 9/API 28, x86_64, 5 tests, 19 seconds | Passed |
| Release build | Android SDK 34, ARM64, Stellarium assets, 3 minutes 54 seconds | Passed |
| APK alignment | Build Tools 34.0.0 `zipalign` | Passed |

## Signing and upgrade validation

The release APK was verified for both supported signing paths:

- API 26–27: APK Signature Scheme v2, certificate SHA-256
  `bc4b926780ae1826eb2b66c3c2bea99a546bac91f5bc9f6274547680cc86c374`;
- API 28–32: APK Signature Scheme v3 lineage, certificate SHA-256
  `46a82d0f1ba2989f42448b7ef9845a8283a9a2cb8e8938bb49a7bae3a27aaa3a`.

The physical upgrade-install check from the public `v1.0.6` APK is unavailable.

## Hardware coverage

Physical astronomy hardware was unavailable for this validation session. Camera,
mount, guiding, accessory, Bluetooth, reconnect, global STOP, and 30-minute
preview rows remain unverified on the release commit. Phone plate solving also
remains experimental and has no field accuracy report for this release.

The release notes must retain these coverage limits.
