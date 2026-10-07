# Sequence emulator testing

The sequence emulator build exercises the production sequence editor, engine,
FITS writer, and session writer with deterministic virtual devices. It has a
separate application ID and cannot replace the production APK.

## Build identity

Build from PowerShell:

```powershell
.\Build.ps1 -NonCommercial -EmulatorTest
```

The output is `bin/Installer/IndigoObservatory_emulator_test.apk` with:

- application ID `com.indigo.mobileobservatory.emulatortest`;
- minimum API 26;
- ABIs `x86` and `x86_64`;
- the sequence feature enabled;
- virtual sequence devices enabled.

Production builds continue to use `arm64-v8a` and the frozen vendor libraries.
The emulator build is restricted to the debug variant.

## Nox instance

Use an Android 9 or newer Nox instance. Android 7 is API 25 and cannot install
this application. The Nox guest normally reports `x86` or `x86_64`; the emulator
APK contains both native variants.

Create an Android 9 instance with a descriptive name when the installed Nox
console supports it:

```powershell
& 'D:\Program Files\Nox\bin\NoxConsole.exe' add -name:sequence_api28 -systemtype:9
& 'D:\Program Files\Nox\bin\NoxConsole.exe' launch -name:sequence_api28
```

Nox releases that use the legacy VirtualBox kernel can stop during boot when
Windows VBS or HVCI owns hardware virtualization. `VBox.log` then contains
`VERR_SUPDRV_NO_RAW_MODE_HYPER_V_ROOT`. Changing VBS, memory integrity, or boot
settings requires an explicit system decision and a restart.

## Safe installation

List devices and identify the Nox forwarded serial, commonly
`127.0.0.1:62025` or a nearby port:

```powershell
adb devices -l
```

Install through the checked script. The serial is mandatory:

```powershell
.\scripts\Install-EmulatorTestApk.ps1 -Serial 127.0.0.1:62025
```

The script rejects API levels below 26 and ABIs outside x86/x86_64. It always
passes `-s <serial>` to ADB so another connected Android device is not selected.
For forwarded `host:port` serials, it reconnects the emulator after switching
between the Nox ADB server and the Android SDK ADB server.

## Instrumentation tests

Some Nox versions keep an older ADB server on the default port. Run Android SDK
instrumentation through a separate server port so Gradle and Nox can remain
connected at the same time:

```powershell
$env:ANDROID_ADB_SERVER_PORT = '5038'
$env:ANDROID_SERIAL = '127.0.0.1:62025'
& 'D:\Unity\AndroidSDK34\platform-tools\adb.exe' -P 5038 start-server
& 'D:\Unity\AndroidSDK34\platform-tools\adb.exe' -P 5038 connect $env:ANDROID_SERIAL
.\gradlew.bat connectedDebugAndroidTest `
  -PemulatorTest=true `
  -PsequenceEnabled=true `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.indigo.mobileobservatory.ui.SequencePortraitUsabilityTest'
```

Use the Android SDK path from `local.properties` when it differs from the example.

## Sequence checks

The sequence page displays an emulator test banner and reports the virtual
camera, mount, guider, filter wheel, focuser, rotator, and cover as connected.
The status page also displays live virtual values for the dew heater, USB limit,
flat-panel light and brightness, camera cooling durations, and guide calibration.

Run these checks:

1. Edit every simple exposure field, disable one row, save it, and convert it to
   an advanced sequence.
2. Run a short sequence with filter changes, two exposures, dithering, and end
   instructions.
3. Exercise pause, resume, skip, skip to end, and stop in separate runs.
4. Inspect the app external files under `captures/Sequences`. Each completed
   exposure has a FITS file and the run has `session.json`.
5. Verify FITS headers including `EXPOSURE`, `GAIN`, `FILTER`, `XBINNING`,
   `YBINNING`, and `IMAGETYP`.

Virtual exposures are accelerated and complete in at most 1.2 seconds. Device
state transitions use short deterministic delays to keep UI controls observable.

Some Nox Android 9 images open the bundled Amaze file manager directly when it
is the only share target. Amaze may request superuser access in that image.
Root access is unnecessary for sequence sharing; reject that request or install
another ordinary share target for the chooser test.
