# Contributing to Indigo Observatory

Contributions are welcome through GitHub issues and pull requests.

## Before opening an issue

- Search existing issues and plans under `docs/` and `TODO.md`.
- Remove precise observing locations, serial numbers, account data, and private
  image metadata from logs and attachments.
- For hardware defects, include Android version, phone model, device model,
  firmware/SDK version, connection transport, reproduction steps, and relevant logs.
- Use the hardware-validation issue form for successful or failed matrix runs.

Security vulnerabilities follow `SECURITY.md` and should not be reported publicly.

## Development setup

Install JDK 17, Android SDK, NDK `25.1.8937393`, Git, and PowerShell. Initialize
submodules before the first build.

```powershell
git submodule update --init --recursive
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebugAndroidTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

Use `.\Build.ps1 -NonCommercial` only when the Stellarium assets and AGPL source
delivery requirements are in scope. Release builds follow `docs/RELEASE_PROCESS.md`.

## Pull requests

- Keep protocol-specific behavior behind the existing adapters.
- Preserve independent camera, mount, guide-camera, and accessory connections.
- Add deterministic tests for parsing, state transitions, math, and failure paths.
- Record hardware evidence for changes that depend on USB, Bluetooth, vendor SDKs,
  Camera2 behavior, device timing, or physical motion.
- Update user-facing documentation and both English and Chinese strings together.
- State remaining untested hardware explicitly in the pull request.
- Keep proprietary keys, private SDKs, customer data, and industrial overlays out of
  the public repository.

All CI checks must pass. Maintainers may request a smaller change or additional
hardware evidence when a pull request spans unrelated device families.

## Licensing

Unless a file states another license, contributions are submitted under
AGPL-3.0-only as described in `LICENSE`. Contributors must have the right to submit
their code, data, firmware descriptions, and test artifacts. Third-party materials
must include source, copyright, license, and redistribution information.
