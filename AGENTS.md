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

## Native package changes

The camera JNI functions use package-qualified exported names. Any future Android
package rename must update Kotlin packages and every `Java_com_indigo_mobileobservatory`
symbol under `app/src/main/cpp`.

## Device architecture

Camera, mount and accessory connections are independent. Keep protocol-specific
behavior behind the existing adapters and preserve the unified ViewModel/UI state.
