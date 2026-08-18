# jniLibs 预编译库说明

本目录存放厂商提供或社区定制的预编译 native 库。**其中部分文件不可再生，升级前务必先读本文件。**

## 冻结资产：`arm64-v8a/libusb-1.0.so`

**禁止升级、替换或用上游版本重新编译这个文件。**

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

- `libtoupcam.so`：图谱官方库，自带 USB 实现，不依赖外部 libusb。
- `libqhyccd.a`：QHY 静态库，由 `cpp/CMakeLists.txt` 链接进 `libqhyccd_jni.so`。
- `libASICamera2.so` / `libzwo_camera.so`：ZWO 官方库。`libASICamera2.so` 必须保持官方
  ASISDK_ANDROID 包（2024-11 下载版）的 arm64 文件（7,042,880 字节），内含 ASI662 / 585 /
  676 / 678 / 715 等 2021+ 新机型表。⚠ 2026-04 曾误被一个 1,173,184 字节的旧版快照覆盖
  （缺新机型表，导致 662 等无法识别），已恢复，切勿再从旧快照/旧工程还原。
  `libzwo_camera.so`（JNI 胶水）新旧导出一致，可保持现有文件。
- `libastap_cli.so`：ASTAP 板解命令行。
- `libc++_shared.so` / `libomp.so`：NDK 运行时。
