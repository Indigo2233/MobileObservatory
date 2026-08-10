# 致 Player One Camera Android SDK 开发者：两个集成阻塞项

> 对应版本：`playerone-camera-sdk-release.aar`（Android Phase 3，2026-08-05）。
>
> 我们是一款 Android 天文摄影 App，已集成 ToupTek、QHYCCD、ZWO 三家 USB 相机，现在希望加入 Player One。SDK 的 Java API 很清晰，用 `libusb_wrap_sys_device` 绑定 fd 也是最规范的做法。目前有两个**打包配置**层面的问题阻塞集成，都不涉及 SDK 代码逻辑。

## 阻塞项 1：`libusb-1.0.so` 的 SONAME 冲突

AAR 内 `libusb-1.0.so` 的 `DT_SONAME` 是 `libusb-1.0.so`，与我们已有的另一份 libusb 撞名。那份是为绕过 Android SELinux 而定制编译的旧版 libusb（约 1.0.21 基线），供 ZWO 的 `libASICamera2.so` 使用；ZWO 已停止维护 Android SDK，我们无法替换它。

一个 APK 不能有两个同名 `.so`，一个进程也只加载一份，而两者符号不兼容：

| 符号 | 我们已有那份 | 你们那份 |
|---|:---:|:---:|
| `libusb_wrap_fd`、`libusb_open_device_with_vid_pid_index` | 有（ZWO 依赖） | 无 |
| `libusb_init_context`、`libusb_wrap_sys_device`、`libusb_set_option` | 无 | 有（你们依赖） |

结果是两个品牌只能二选一，另一家必然在真机崩溃。

**请求：重编 libusb 时把 SONAME 改成带厂商标识的名字**，例如：

```bash
# CMake
cmake -DBUILD_SHARED_LIBS=ON \
      -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-soname,libusb-1.0-poa.so" ...
# autotools 追加 LDFLAGS="-Wl,-soname,libusb-1.0-poa.so"
```

然后让 `libPlayerOneCamera.so` 链接这一份。**必须改 ELF 里的 `DT_SONAME`，只重命名文件无效**——动态链接器按 SONAME 查找。验收：

```bash
llvm-readelf -d libusb-1.0-poa.so     | grep SONAME   # 期望 libusb-1.0-poa.so
llvm-readelf -d libPlayerOneCamera.so | grep NEEDED   # 期望 libusb-1.0-poa.so
```

也可以改为把 libusb 静态链接进 `libPlayerOneCamera.so`，效果相同；但 libusb 是 LGPL-2.1，静态链接进闭源库会带来重新链接义务，所以我们建议优先改 SONAME。

> 这不是我们的特殊需求：我们集成的 QHYCCD 那份 libusb 的 SONAME 就是 `libusb1.0.so`，因此和 ZWO 长期稳定共存。任何厂商 SDK 都不宜占用 `libusb-1.0.so` 这个通用 SONAME——改掉之后，你们的 SDK 对所有多品牌 App 都能直接集成。

## 阻塞项 2：AAR 的 `minCompileSdk=36` 过高

AAR 的 `META-INF/com/android/build/gradle/aar-metadata.properties` 声明 `minCompileSdk=36`（来自打包模块自身的 `compileSdk`）。我们是 `compileSdk 34` + AGP `8.2.2`，直接添加依赖会在 Gradle 配置阶段失败；为接一个相机而升级整条构建链回归成本过高。

SDK 的 `classes.jar` 只有 34 KB、`minSdk` 24，应该没有用到 API 36 才有的能力。**请把模块 `compileSdk` 降到 34 后重新出 AAR**（降到 33 可覆盖更多存量 App）。如果确有 API 必须停留在 36，请告知是哪一个。

## 技术确认（不阻塞）

1. **ROI 对齐约束**：`setImageSize` / `setImageStartPosition` 是否要求宽高按 4 / 2 的倍数对齐？最小 ROI 是多少？（文档未说明，我们需要在 UI 层做对齐修正）
2. **Gain 单位**：`PoaConfig.GAIN` 的整数值与 dB 如何换算？我们界面按 dB 展示。
3. **Android 16 崩溃**：文档提到"Android 16 已知崩溃待复测"，具体现象、复现条件与修复排期？我们需要据此决定是否在该系统版本上限制此品牌。
4. **`PlayerOneUsbManager` 是否必须全进程单例**？我们要同时接主相机与导星相机，目前按"native registry 全局、重复绑 fd 返回错误码 15"的假设设计成单例。
5. **型号与 PID 对照表**（优先级最低）：我们从 `libPlayerOneCamera.so` 的 `.rodata` 里已经取到 39 个型号名，够用于兼容性文案；如果有现成的型号↔PID 对照表，可用于在设备未连接时展示更准确的机型提示。没有也不影响集成。

两个阻塞项都只是打包配置改动，不影响已有集成方。修改后我们可以立刻开始接入，并提供多品牌、多机型的真机交叉验证反馈。
