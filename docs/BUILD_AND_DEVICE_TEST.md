# Android 构建与设备安装流程

本流程用于生成并安装 Indigo Observatory 的非商业 Debug APK。一次操作只允许存在一个 Gradle 构建，避免守护进程、native 编译与 APK 输出目录互相占用。

## 常规构建

在仓库根目录的 PowerShell 中执行：

```powershell
.\Build.ps1 -NonCommercial
```

该命令会应用 libusb Android FD 补丁，构建 Debug APK，并写入以下交付物：

- `bin/Installer/IndigoObservatory_android.apk`
- `bin/Installer/IndigoObservatory_android_v<version>-build<code>.apk`
- 同名 `.sha256` 与 `.build-info.txt`

首次构建、删除 `app/build` 后的构建，以及 native 源码或 NDK 变更后的构建，需要重新编译三个 arm64 JNI 库。此阶段通常耗时数分钟。构建日志持续推进时，保持单个构建进程运行即可。

## 安装到设备

使用 `local.properties` 中的 SDK 路径确认 ADB 位置，再检测设备：

```powershell
$adb = 'D:\Unity\AndroidSDK34\platform-tools\adb.exe'
& $adb devices -l
```

列表出现一台状态为 `device` 的设备后安装：

```powershell
& $adb install -r .\bin\Installer\IndigoObservatory_android.apk
```

安装完成后可启动应用：

```powershell
& $adb shell monkey -p com.indigo.mobileobservatory 1
```

## ADB 无法列出设备

先停止当前 ADB 服务，再由同一 SDK 目录的 ADB 重启服务：

```powershell
& $adb kill-server
& $adb start-server
& $adb devices -l
```

`unauthorized` 表示需要在设备上确认 USB 调试授权；`offline` 表示需要重新连接 USB 或重启 ADB 服务。

## 构建异常处置

1. 先等待当前构建输出；不要在构建尚未结束时再次运行 Gradle、`Build.ps1` 或 `Build.ps1 -Clean`。
2. 若任务超过十分钟没有新增日志，执行 ` .\gradlew.bat --status` 检查守护进程状态。
3. 仅在确认没有其他开发者或 IDE 构建运行时，执行 ` .\gradlew.bat --stop`，等待该命令结束。
4. 重新执行常规构建。只有 `app/build` 删除失败、CMake 缓存损坏或常规构建重复失败时，才执行：

```powershell
.\Build.ps1 -Clean -NonCommercial
```

5. 清理构建失败且提示文件被占用时，关闭占用 `app/build` 的 IDE、Gradle、Java、CMake 或 Ninja 进程后，再执行一次干净构建。

发布包使用 ` .\Build.ps1 -Release -NonCommercial`，并遵循 `AGPL_SOURCE_DELIVERY.md` 的源码交付要求。
