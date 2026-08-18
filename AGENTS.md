# Mobile Observatory engineering guide

This repository contains the standalone Android application **Indigo Observatory**
(显示名：靛空台). The git/Android package identity remains `mobileobservatory`
for continuity.

## Product identity

- Display name: Indigo Observatory / 靛空台
- Android namespace and application ID: `com.indigo.mobileobservatory`
- Main source root: `app/src/main/java/com/indigo/mobileobservatory`
- APK output: `bin/Installer/IndigoObservatory_android.apk`

## Build

Use the project script from PowerShell:

```powershell
.\Build.ps1 -NonCommercial
```

The `-NonCommercial` switch includes the Stellarium Web assets. Keep
`AGPL_SOURCE_DELIVERY.md` synchronized with any redistributed Stellarium changes.

### libusb Android FD patch

`app/src/main/cpp/libusb` is an upstream submodule. The Android SELinux
workaround (`libusb_set_android_fd`) lives in
`patches/libusb-android-fd.patch` and is applied automatically by
`Build.ps1` and the Gradle `applyLibusbAndroidFdPatch` task (via
`git apply`, so Linux CI works without PowerShell) before native builds.
After a clean `git submodule update`, the first build re-applies the patch.

### Frozen prebuilt libraries

`app/src/main/jniLibs/arm64-v8a/libusb-1.0.so` is a community-built custom libusb
carrying private APIs (`libusb_wrap_fd`) that ZWO's `libASICamera2.so` links
against. ZWO no longer updates its Android SDK, so **never upgrade, replace or
rebuild that file** — there is no upstream equivalent and no way back.

Three libusb copies coexist in the APK, isolated only by distinct SONAMEs
(`libusb-1.0.so` for ZWO, `libusb1.0.so` for QHY, `libusb-1.0-po.so` for Player
One). After upgrading any vendor SDK, re-verify the SONAMEs do not collide
(`llvm-readelf -d`). Never paper over such a collision with
`packaging.jniLibs.pickFirsts`. See `app/src/main/jniLibs/README.md`.

`libASICamera2.so` must remain the official ASISDK_ANDROID arm64 build
(7,042,880 bytes, contains the model table for ASI662/585/676/678/715 etc.).
A stripped 1,173,184-byte snapshot without that table was accidentally
committed in April 2026 and breaks post-2020 ZWO cameras; never restore it.

## Native package changes

The camera JNI functions use package-qualified exported names. Any future Android
package rename must update Kotlin packages and every `Java_com_indigo_mobileobservatory`
symbol under `app/src/main/cpp`.

## Device architecture

Camera, mount and accessory connections are independent. Keep protocol-specific
behavior behind the existing adapters and preserve the unified ViewModel/UI state.
