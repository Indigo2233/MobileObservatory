# jniLibs 预编译库说明

本目录存放厂商提供或社区定制的预编译 native 库。**其中部分文件不可再生，升级前务必先读本文件。**

## 冻结资产：`arm64-v8a/libusb-1.0.so`

**禁止升级、替换或用上游版本重新编译这个文件。**

打包时也通过 `keepDebugSymbols` 保留原始字节，避免通用 strip 工具重新排列其 ELF 节表。验收可直接比较 APK 内该库与本目录文件的 SHA-256。

- 它不是官方交付物，而是社区定制编译的旧版 libusb（约 1.0.21 基线），用于绕开 Android 10+ 的
  SELinux 限制——上游同期版本通过 `opendir("/sys/bus/usb/devices")` + `open("/dev/bus/usb/BBB/DDD")`
  访问设备，在 app 域会被直接拦死。
- 它带有**上游没有**的私有 API：`libusb_wrap_fd`、`libusb_open_device_with_vid_pid_index`。
  ZWO 的 `libASICamera2.so` 直接引用这两个符号。
- **ZWO 已确认不再更新 Android SDK**，不会有官方修复版，我们手里也没有对应源码。

因此一旦换成上游 libusb（哪怕是最新的 1.0.27），`libASICamera2.so` 会因缺符号而失效，
**且没有任何人能修复**。上游对应的官方 API 是 1.0.23 才引入的 `libusb_wrap_sys_device`，
名字和签名都不同，不能直接顶替。

## 同一进程内的三份 libusb

APK 里同时存在三份 libusb，靠**互不相同的 SONAME** 隔离，各自独立加载、各自持有 context：

| 文件 | SONAME | 代际 | 使用者 |
|---|---|---|---|
| `libusb-1.0.so` | `libusb-1.0.so` | ~1.0.21 定制版（冻结） | `libASICamera2.so`、`libzwo_camera.so` |
| `libusb1.0.so` | `libusb1.0.so` | 1.0.27 代 | `libqhyccd_jni.so` |
| （AAR 内）`libusb-1.0-po.so` | `libusb-1.0-po.so` | 1.0.27 代 | `libPlayerOneCamera.so`（Player One SDK） |

**升级任何一家相机 SDK 后，都要重新确认 SONAME 没有互相撞名**，否则一个进程只会加载一份，
另一家必然在真机崩溃，而编译期没有任何告警。**绝对不要用 `packaging.jniLibs.pickFirsts`
掩盖这类冲突**——那只会把编译错误变成运行时崩溃。

验收命令（NDK 自带）：

```bash
llvm-readelf -d <库文件> | grep -E "SONAME|NEEDED"
```

## 其他文件

### ToupTek SDK 60.33015.20261024 / GPM585M

2026-10-29 从维护者提供的 `E:\download\toupcamsdk.20261024` 原样更新：
`inc/toupcam.h` → `cpp/toupcam.h`；`android/arm64`、`android/arm`、
`android/x86`、`android/x64` 分别对应本目录的 `arm64-v8a`、`armeabi-v7a`、
`x86`、`x86_64`。此前版本为 `59.30405.20251228`。

来源可核对[官方 SDK 下载中心](https://www.touptekphotonics.com.cn/download/?category=SDK)
和[2026-10-24 SDK 包](https://www.touptekphotonics.com.cn/downloads/beta/toupcamsdk.20261024.zip)。
该版本位于官方 Latest 通道；下载路径为 `beta`，当日 Stable 通道为 2026-05-19。
升级时应同时替换四种 Android ABI 与公共头文件。

| 文件 | SHA-256 |
|---|---|
| `arm64-v8a/libtoupcam.so` | `4a9adee403b79f0ba39ff0bc2d49e62e6403934f44444b3243541a8d7d901f58` |
| `armeabi-v7a/libtoupcam.so` | `ec089b514cf2602bdcfb14a0bc40c71dfdeab61c28a141c25adf21735064ea6f` |
| `x86/libtoupcam.so` | `47d620c361638a1bcc8ad1a1d9c46102bad28c5182dc16818b0db714ae57b357` |
| `x86_64/libtoupcam.so` | `f37c9a0139c4bccb51358de259e842d0a16873f3942b52b063a0eb200c290fcc` |
| `../cpp/toupcam.h` | `05ad780f5be89f32dbcccfbc9592fad325f9d86b97c6b5cb673dc09f23386b72` |

官方包 `win/drivers/usb/x64/toupcam.inf` 将 GPM585M 关联到 `0547:1697`。
在 Android ARM64 上实际调用 `Toupcam_get_Model(0x0547, 0x1697)` 返回
`GPM585M`、3840×2160、2.9×2.9 μm、flags `0x885688219`，含单色、硬件 ROI、
ST4、RAW8/RAW16、CGHDR。产品页的传感器 ADC 规格为 12 位；应用继续通过
`get_MaxBitDepth` / `get_RawFormat` 获取采集格式，避免将 ADC 规格写死为输出位深。
`ToupTekDevices` 识别 GPM585M USB 名称，先使用设备实际 VID/PID，再按官方 PID 回退。

四种 ABI 均保留当前 JNI 使用的全部 30 个 SDK 导出，SONAME 为 `libtoupcam.so`，
仅依赖系统 `libm.so`、`libdl.so`、`libc.so`。三份 libusb 的 SONAME 仍分别为
`libusb-1.0.so`、`libusb1.0.so`、`libusb-1.0-po.so`；其他相机 SDK 二进制保持原字节。

`scripts/tests/toupcam_sdk_probe.c` 在 Android 上通过 `RTLD_NOW` 实际加载库，检查版本、
GPM585M 参数、GPM462M/C 型号及无效 USB ID。2026-10-29 已在 V2324HA 上通过。
该检查仅执行库加载和型号查询；GPM585M USB 连接、首帧、曝光/增益、ROI、断开重连、
导星与已有图谱相机采集仍需硬件验收。

本次 `Build.ps1 -NonCommercial` 构建通过（1.0.9 / build 59），APK 内图谱库与上表
ARM64 文件字节一致。设备识别、帧尺寸和 USB connection 保持共 13 项测试通过；
Gradle 测试入口遇到 Kotlin 堆内存不足及共享缓存文件锁，最终使用 Kotlin 1.9.22 / JUnit 4
在独立输出目录编译并运行对应现有测试源，结果为 13 tests / 0 failures。

```powershell
$ndkBin = 'D:/Unity/AndroidSDK34/ndk/25.1.8937393/toolchains/llvm/prebuilt/windows-x86_64/bin'
$adb = 'D:/Unity/AndroidSDK34/platform-tools/adb.exe'
$serial = '<adb devices 中的设备序列号>'
New-Item -ItemType Directory -Path build/toupcam-sdk-upgrade -Force | Out-Null
& "$ndkBin/clang.exe" --target=aarch64-linux-android26 -Wall -Wextra -Werror -fPIE -pie `
    -I app/src/main/cpp scripts/tests/toupcam_sdk_probe.c -ldl `
    -o build/toupcam-sdk-upgrade/toupcam_sdk_probe
& $adb -s $serial shell mkdir -p /data/local/tmp/indigo-toupcam-20261024
& $adb -s $serial push build/toupcam-sdk-upgrade/toupcam_sdk_probe `
    app/src/main/jniLibs/arm64-v8a/libtoupcam.so /data/local/tmp/indigo-toupcam-20261024/
& $adb -s $serial shell chmod 755 /data/local/tmp/indigo-toupcam-20261024/toupcam_sdk_probe
& $adb -s $serial shell /data/local/tmp/indigo-toupcam-20261024/toupcam_sdk_probe `
    /data/local/tmp/indigo-toupcam-20261024/libtoupcam.so
```

### ASI676MM 的 PID 兼容修正

2026-10-14 在上述官方 Android 库中确认：`ASIOpenCamera` 将 USB PID `0x676C`
分派给 `CCameraS676MM_DDR`，该构造函数在 `0x14E2E0` 却将对象偏移 680 的
打开用 PID 写成 `0x676D`（676MC）。后续 `CCameraBase::OpenCamera` 将此值
传入 `ASIUSBManager.getUsbFD`，按 VID/PID 精确查找时无法找到 676MM，返回
`ASI_ERROR_CAMERA_REMOVED (5)`。型号表中的 676MM 条目不足以证明连接可用。

`UsbHelper.prepareZwoModel` 在连接 `0x676C`、调用 SDK 枚举之前安装兼容处理。
`usb_helper_jni.cpp` 仅替换 SDK 内 676MM 构造函数的 PLT/GOT 调用：先运行原始
构造函数，再通过 `zwo_676mm_compat.h` 校正该对象的 PID 为 `0x676C`。原始
`libASICamera2.so` 和冻结的 `libusb-1.0.so` 均保持字节不变。676MC、715MC、
220MM Mini 的构造函数和 PID 保持原有处理；SDK 的 MM/MC 构造路径分别使用各自
PID 发起 USB 打开请求。

该处理依赖官方 ARM64 对象布局，安装前同时校验 GNU Build ID
`3f4410b217f90b86f4d51298f9f53a5e504b6379`、构造函数地址及 PID 写入指令。
校验失败时拒绝启用 676MM 兼容处理并记录 `[ZWO676MM]`，避免对未知布局写入。
常规 `app.log` 会记录兼容处理是否准备成功；native 日志记录具体对象校正。

回归脚本 `scripts/tests/zwo_676mm_sdk_test.py` 使用 Unicorn 执行真实 ARM64 SDK
及编译后的 `libusb_helper_jni.so`，覆盖安装、型号分派、构造函数及 USB 打开请求。
操作系统、配置读写、内存分配与 USB 访问由测试替代，因此不代表实机连接或出图验收。

```powershell
python -m pip install --target build/zwo-test-deps unicorn==2.1.3 pyelftools
# 原始 SDK：676MM 相关断言应失败，其他型号应通过。
python scripts/tests/zwo_676mm_sdk_test.py --baseline
.\Build.ps1 -NonCommercial
# 使用本次构建生成的实际 ARM64 native 库。
$zwoHelper = Get-ChildItem app/build/intermediates/cxx/Debug -Recurse -Filter libusb_helper_jni.so |
    Where-Object { $_.FullName -match 'arm64-v8a' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
python scripts/tests/zwo_676mm_sdk_test.py --helper $zwoHelper.FullName
```

实机验收仍需在同一手机上验证 676MM 的连接、第一帧、断开重连，并回归原已正常的
715C、220mini。报告中应区分指令模拟检查与真实 USB/图像采集结果。

### 预编译文件清单

- `libtoupcam.so`：图谱官方库，自带 USB 实现，不依赖外部 libusb。
  Java 必须一直持有对应的 `UsbDeviceConnection`；只把 `fileDescriptor` 传给 SDK 后丢掉
  Java 引用，GC 会关掉 fd，表现为连上立刻失败或无法出图。滤镜轮已照此处理。
- `libqhyccd.a`：QHY 静态库，由 `cpp/CMakeLists.txt` 链接进 `libqhyccd_jni.so`。
- `libASICamera2.so` / `libzwo_camera.so`：ZWO 官方库。`libASICamera2.so` 必须保持官方
  ASISDK_ANDROID 包（2024-11 下载版）的 arm64 文件（7,042,880 字节），内含 ASI662 / 585 /
  676 / 678 / 715 等 2021+ 新机型表。⚠ 2026-04 曾误被一个 1,173,184 字节的旧版快照覆盖
  （缺新机型表，导致 662 等无法识别），已恢复，切勿再从旧快照/旧工程还原。
  `libzwo_camera.so`（JNI 胶水）新旧导出一致，可保持现有文件。
- `libastap_cli.so`：ASTAP 板解命令行。
- `libc++_shared.so` / `libomp.so`：NDK 运行时。
