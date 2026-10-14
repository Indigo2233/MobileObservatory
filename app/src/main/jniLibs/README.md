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
