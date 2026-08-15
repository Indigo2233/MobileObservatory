# Player One（头号玩家）相机接入方案

> 状态：**M0–M4 代码已落地。** 2026-08-15 单机真机：出图、长跑、热拔插、供电 Hub、Android 版本基本通过；双路因无第二台暂缓。ZWO / QHY 实现未改动，不做额外回归。
>
> 状态约定与 `TODO.md` 一致：`[ ]` 待办、`[~]` 进行中、`[x]` 已完成、`[-]` 暂不实施。
>
> 厂商交付物位置：`app/libs/playerOne_AndroidSdk/`（AAR + API 文档 + demo zip），SDK 版本标注为 Android Phase 3 / 2026-08-05（修订版，SONAME=`libusb-1.0-po.so`，`minCompileSdk=34`）。

## 1. 目标

让 Player One 相机成为 Indigo Observatory 的**一等相机品牌**，与现有 ToupTek / QHYCCD / ZWO 三家享受同一套 UI、预览管线、导星与拍摄流程：

- 主相机与导星相机都能选择 Player One 设备。
- 枚举、USB 授权、连接、断连恢复走统一的设备选择器，用户感知不到品牌差异。
- 曝光、增益、ROI、bin、像素格式、Sensor Mode、制冷全部接入现有控制面板。
- **不引入新的 UI 分支**：`Camera` 接口之外的能力（例如制冷）沿用现有扩展模式或抽出通用能力接口。

非目标（本轮不做）：Player One 电动调焦轮 / 滤镜轮等配件、ST4 导星口直连、RGB24 预览格式。

## 2. 厂商交付物盘点（已实测）

> **2026-08-07 更新**：厂商已针对第 3 节的两个阻塞项发布修订版 SDK，位于 `app/libs/playerOne_AndroidSdk/`。以下为**修订版**的实测结果，两个阻塞项均已解除。旧版 `app/libs/android-sdk/` 已作废，可删除。

| 项 | 实测结果 |
|---|---|
| AAR | `app/libs/playerOne_AndroidSdk/playerone-camera-sdk-release.aar`，504 KB |
| Java 包 | `com.playeroneastronomy.camera`（19 个公开类 + 2 个 internal JNI bridge）。**修订版 `classes.jar` 与初版 SHA256 完全一致，Java API 无任何变化** |
| ABI | `arm64-v8a`、`armeabi-v7a`（项目只出 arm64，v7a 会被 `abiFilters` 过滤） |
| 打包的 native | `libPlayerOneCamera.so`、**`libusb-1.0-po.so`**（每 ABI 各一份） |
| libusb SONAME | **`libusb-1.0-po.so`**（已改，两个 ABI 均已验证）；`libPlayerOneCamera.so` 的 `DT_NEEDED` 同步指向它 |
| `libPlayerOneCamera.so` 依赖 | 仅 `libdl.so`、`liblog.so`、`libm.so`、`libc.so` 与上面那份 libusb——**C++ 运行时已静态链接**，不需要 `libc++_shared.so` |
| fd 绑定方式 | 上游官方 API `libusb_wrap_sys_device`（libusb 1.0.23+），**不需要任何 SELinux 绕过**，见 3.3 |
| minSdk | 24（项目 26，满足） |
| AAR metadata | **`minCompileSdk=34`**（与项目一致，可直接作为标准 AAR 依赖） |
| ProGuard | AAR 自带 consumer rules，keep 两个 native bridge |
| Manifest | 仅声明 `android.hardware.usb.host` feature，无 Activity/Receiver |
| USB Vendor ID | `0xA0A0`（十进制 41120），**支持 Player One 全系相机**（厂商确认） |
| 最大活动设备数 | 16（native registry 上限） |
| demo 工程 | `PlayerOneCameraJni.zip`，`MainActivity.java` 覆盖全部公开 API，是最完整的调用参考 |

demo 额外依赖 `org.opencv:opencv:4.9.0`，**仅用于 demo 自己的预览 debayer**，SDK 本身不依赖 OpenCV，我们不需要引入。

### 厂商答复要点（2026-08-07）

| 问题 | 答复 |
|---|---|
| ROI 对齐约束 | **宽整除 4、高整除 2；最小 ROI 16×16** |
| Gain 单位 | **1 gain = 0.1 dB**（`db = gain × 0.1`，例如 100 → 10 dB） |
| Android 16 崩溃 | 并非 SDK 缺陷，是测试时 USB 供电不足；改用独立供电 Hub 后通过。文档已更新为"Galaxy Tab S7+ / Android 10 与 Xiaomi 15 / Android 16 均已用多台相机跑通 Phase 3 流程" |
| `PlayerOneUsbManager` 单例 | **应全进程共享一个长期实例**；一个 manager 可同时枚举、授权并维护主相机与导星相机，不同设备使用不同 registration ID 与 fd |
| 支持的 PID | 全系相机均支持 |

## 3. 阻塞项与 native 层前提

> **状态：3.1 与 3.2 两个阻塞项均已由厂商在 2026-08-07 的修订版中解决**（见每节末尾的"结果"）。下面保留完整分析，因为它解释了为什么必须这样打包，也是后续升级 SDK 时的验收依据——**每次厂商更新 AAR，都要重跑 3.1 的 SONAME 验收命令**。

### 3.1 阻塞项 A：`libusb-1.0.so` 同名冲突（严重）—— 已解决

项目已在 `app/src/main/jniLibs/arm64-v8a/` 打包一份 `libusb-1.0.so`（77.8 KB），**供 ZWO 的 `libASICamera2.so` 使用**；AAR 又带来一份同名 `libusb-1.0.so`（101.3 KB）。两者 SONAME 都是 `libusb-1.0.so`，一个 APK 里只能存在一份，一个进程里也只会加载一份。

实测两份库不可互相替代：

| 符号 | 项目版本 | PlayerOne 版本 | 用途 |
|---|:---:|:---:|---|
| `libusb_wrap_fd` | 有 | **无** | `libASICamera2.so` 引用 |
| `libusb_open_device_with_vid_pid_index` | 有 | **无** | `libASICamera2.so` 引用 |
| `libusb_init_context` / `libusb_wrap_sys_device` / `libusb_set_option` | 无 | 有 | 上游 1.0.27+ 新 API |

也就是说：项目那份是**打过定制补丁的旧版 libusb**（缺 `libusb_set_option`(1.0.22)、`libusb_wrap_sys_device`(1.0.23)，`libASICamera2.so` 还依赖早期 NDK 的 `libstdc++.so`，推断 fork 基线在 1.0.21 前后），PlayerOne 那份是接近上游的 1.0.27。任选一份保留，都会让另一家相机在运行期崩掉（保留 PlayerOne 版 → ZWO 加载 `libASICamera2.so` 时缺符号；保留项目版 → PlayerOne 缺 `libusb_init_context` 等新 API）。用 `packaging.jniLibs.pickFirsts` 掩盖冲突只会把编译期错误变成真机崩溃，**明确禁止**。

#### 那份 ZWO libusb 是冻结资产，不可升级、不可替换

`app/src/main/jniLibs/arm64-v8a/libusb-1.0.so` 不是官方交付物，而是**社区同好私下提供的定制编译版**，用于绕开 Android 10+ 的 SELinux 限制——老版 libusb 通过 `opendir("/sys/bus/usb/devices")` + `open("/dev/bus/usb/BBB/DDD")` 访问设备，在 app 域被 SELinux 直接拦死；ZWO 官方 demo 附带的 libusb 同样被拦。`libusb_wrap_fd` 就是这份 fork 为此加的私有 API（对应上游 1.0.23 才有的 `libusb_wrap_sys_device`）。

关键事实：**ZWO 已确认不再更新 Android SDK**（"不符合商业利益"）。因此：

- 不会有官方修复版，也不会有新的 `libASICamera2.so`；
- 这份 `.so` 没有对应的可维护源码在我们手里；
- 一旦被"顺手升级"成上游版本，ZWO 相机会立刻失效且无人能修。

**结论：该文件是冻结资产。任何方案都必须绕开它，而不是动它。** 需要在 `jniLibs` 目录旁留下说明文件（并同步进 `AGENTS.md`），明确禁止升级、替换或重编译该库，防止后续维护者或 AI agent 好心改动。

#### 项目里已有一个成功隔离的先例：QHY

APK 里其实已经并存着**两份** libusb，只是此前一直相安无事。实测三份库的 ELF 元数据：

| 来源 | 文件名 | **SONAME** | libusb 代际 | 谁依赖它 |
|---|---|---|---|---|
| ZWO | `libusb-1.0.so` | `libusb-1.0.so` | ~1.0.21 fork（含私有 `libusb_wrap_fd`） | `libASICamera2.so`、`libzwo_camera.so` |
| QHY | `libusb1.0.so` | **`libusb1.0.so`** | 1.0.27 代（含 `libusb_wrap_sys_device`） | `libqhyccd_jni.so` |
| PlayerOne | `libusb-1.0.so` | `libusb-1.0.so` | 1.0.27 代 | `libPlayerOneCamera.so` |

QHY 那份的 SONAME 是 `libusb1.0.so`（比 ZWO 少一个横线），实测 `libqhyccd_jni.so` 的 `DT_NEEDED` 也确实指向 `libusb1.0.so`。**两份 libusb 因此在同一进程里各自独立加载、各自持有 context，已经在生产版本里长期和平共存。**

这条对方案选择很重要：A2（给 PlayerOne 的 libusb 改 SONAME）不是新实验，而是项目里**已经验证过的模式**。而且 QHY 与 PlayerOne 属于同一代 libusb、都用上游 `libusb_wrap_sys_device` 接收 fd，PlayerOne 要走的路 QHY 已经走通了。冲突之所以现在才出现，只是因为 PlayerOne 恰好和 ZWO 撞了同一个 SONAME。

> **附带发现（不影响本方案，但需单独确认）**：`patches/libusb-android-fd.patch` 引入的 `libusb_set_android_fd` 在上述三份 libusb 里**都不存在**，且 `app/src/main/cpp/libusb` 子模块并未被 `CMakeLists.txt` 引用。因此 `usb_helper_jni.cpp:552` 那次 `dlsym(RTLD_DEFAULT, "libusb_set_android_fd")` 永远走 "not available" 分支——该 patch 目前对 APK 没有实际作用，ZWO 真正依赖的是 fork 的 `libusb_wrap_fd` 加 PLT hook 与假 sysfs。建议单独立项确认它是否为遗留物（`AGENTS.md` 与 `TODO.md` 事项 15 仍把它描述为在用机制）。

候选解法：

| 方案 | 做法 | 评价 |
|---|---|---|
| **A1（推荐，向厂商提）** | 请头号玩家重编 libusb 时把 SONAME 改成 `libusb-1.0-poa.so`（保持动态链接），或退一步静态链接进 `libPlayerOneCamera.so` | 最干净，一次性根治，后续升级 AAR 无需再处理。**优先改 SONAME 而非静态链接**：libusb 是 LGPL-2.1，静态链接进闭源 `.so` 会引入重新链接义务，而改 SONAME 效果相同且无许可负担 |
| **A2（可立即自助落地）** | 我们解包 AAR，用 `patchelf` 把 libusb 的 SONAME 改成 `libusb-1.0-poa.so`，同时把 `libPlayerOneCamera.so` 的 `DT_NEEDED` 一起改名，产物放进 `jniLibs/` | 不依赖厂商排期，**与 QHY 现有的隔离方式同构**（见上）；需要一个可重复执行的脚本并纳入构建校验 |
| A3 | 统一到同一份 libusb：给上游 1.0.27 补上 `libusb_wrap_fd` 与 `libusb_open_device_with_vid_pid_index` 两个 shim，两家共用 | 表面上很诱人（还能省掉 APK 里约 180 KB 的重复库），实际**否决**：ZWO 官方停更、`libASICamera2.so` 是 2017 年构建的二进制黑盒，一旦兼容性出问题没有任何人能修，而收益只是省几百 KB。不值得拿唯一一条不可再生的 ZWO 通路去赌 |

#### 结果：厂商采纳 A1，已验收通过

厂商在修订版中把 SONAME 改为 **`libusb-1.0-po.so`**（微信沟通时定的名字，与本文档早期建议的 `libusb-1.0-poa.so` 略有差别，不影响效果）。实测两个 ABI 均正确：

```
[arm64-v8a]   libusb-1.0-po.so       SONAME : libusb-1.0-po.so
[arm64-v8a]   libPlayerOneCamera.so  NEEDED : libusb-1.0-po.so
[armeabi-v7a] libusb-1.0-po.so       SONAME : libusb-1.0-po.so
[armeabi-v7a] libPlayerOneCamera.so  NEEDED : libusb-1.0-po.so
```

不是简单的文件重命名——ELF 里的 `DT_SONAME` 与 `DT_NEEDED` 都已同步修改，与 ZWO 的 `libusb-1.0.so`、QHY 的 `libusb1.0.so` 三者互不冲突。**A2（自助 patchelf）与 A3 均作废，不需要任何本地脚本。**

### 3.2 阻塞项 B：`minCompileSdk=36` 与当前构建链不匹配 —— 已解决

AAR 元数据要求 `compileSdk ≥ 36`。项目当前 `compileSdk = 34`、AGP `8.2.2`、Kotlin `1.9.22`。直接 `implementation(files(...aar))` 会在配置阶段直接失败。

| 方案 | 做法 | 评价 |
|---|---|---|
| B1 | 升级 AGP 到 8.9+、Gradle、compileSdk 36 | 牵动 Compose 编译器、Kotlin 版本与全部依赖，回归面过大，不应为接一个相机而做 |
| **B2（向厂商提）** | 请厂商用 `compileSdk 34` 重打 AAR | classes.jar 仅 34 KB 且 minSdk 24，几乎确定没用到 API 36 的能力，厂商改一行即可 |
| **B3（推荐，自助落地）** | **不以 AAR 形式依赖**：解包后 `classes.jar` 放 `app/libs/`，`.so` 放 `app/src/main/jniLibs/arm64-v8a/`，consumer ProGuard 规则手工并入 `proguard-rules.pro`，manifest 的 usb.host feature 项目已有 | 绕过 aar-metadata 校验，**且与 A2 的改名操作天然合并成同一步**，无需升级构建链 |

#### 结果：厂商采纳 B2，已验收通过

修订版 AAR 的 `aar-metadata.properties` 现为 `minCompileSdk=34`，与项目一致。**B1（升级构建链）与 B3（解包自集成）均作废**，可以直接用标准 AAR 依赖：

```kotlin
implementation(files("libs/playerOne_AndroidSdk/playerone-camera-sdk-release.aar"))
```

不需要 `scripts/Unpack-PlayerOneSdk.ps1`，不需要动 AGP / Gradle / compileSdk，也不需要手工搬运 ProGuard 规则（AAR 自带 consumer rules 会自动生效）。

### 3.3 好消息：Player One 不需要任何 SELinux 绕过

项目为了让 ZWO 与工业相机在现代 Android 上工作，在 `app/src/main/cpp/usb_helper_jni.cpp` 里堆了一整套相当重的绕过设施：

- **假 usbfs**：`cacheDir/fake_usb/BBB/DDD` symlink 到 `/proc/self/fd/N`；
- **假 sysfs**：`cacheDir/fake_sysfs/<bus>-<dev>/` 下手工伪造 `busnum`、`devnum`、`speed`、`bNumInterfaces`，并把从 fd 读出的真实 USB 描述符写成 `descriptors` 文件，专门喂给 libusb 的 sysfs 扫描逻辑；
- **PLT/GOT hook**：向 `libusb-1.0.so`、`libASICamera2.so`（以及工业相机的 `libMvUsb3vTL.so` 等）注入 `open`/`opendir`/`stat`/`access`/`readdir`/`fstat`/`fopen`/`popen` 八个劫持函数，把所有 `/dev/bus/usb/...` 与 `/sys/bus/usb/devices/...` 访问重定向到上面的假目录和已登记 fd；
- **`libusb_set_android_fd` 探测**：`dlsym(RTLD_DEFAULT, ...)` 试探当前加载的 libusb 是否带 `patches/libusb-android-fd.patch`，有就直接调用。

也就是说 ZWO 那条路上叠了三层兜底（fork 的 `libusb_wrap_fd`、patch 的 `libusb_set_android_fd`、PLT hook + 假 sysfs），属于历史遗留的"不确定哪层在生效"状态。

**Player One 完全不需要走进这套设施。** 实测 `libPlayerOneCamera.so` 引用的是 `libusb_init` / `libusb_set_option` / `libusb_wrap_sys_device`——`libusb_wrap_sys_device` 正是上游 libusb 1.0.23 为 Android 提供的官方方案：Java 层用 `UsbManager.openDevice()` 拿到 fd，直接交给 libusb，不碰 usbfs 路径、不扫 sysfs。这和 `PlayerOneUsbManager` 文档描述的"打开 `UsbDeviceConnection` 并把 fd 绑定到 native registration"完全吻合。

对本次接入的三点实际影响：

1. **不要给 Player One 套用 `UsbHelper`**。不需要 `registerUsbFd`、不需要 `installZwoHooks`、不需要假 sysfs。SDK 自己管 fd，文档也明确要求"不要自行关闭或复制 SDK 内部持有的 USB fd"。
2. **改名方案与现有 hook 天然隔离**。`hook_plt` 用 `strstr(dlpi_name, lib_name)` 按库名匹配，而 `"libusb-1.0.so"` **不是** `"libusb-1.0-po.so"` 的子串（`libusb-1.0` 之后一个是 `.`、一个是 `-`），因此 PlayerOne 的 libusb 不会被 ZWO 的 hook 意外劫持。反过来看：如果两家共用同一份同名 libusb，ZWO 的 hook 会污染 Player One 的全部文件访问——这是 A3 方案之外**又一条**必须做隔离的理由。现成证据是 QHY：它的 `libusb1.0.so` 同样不在 hook 的库名列表里，因此从未被这套劫持波及。
3. **注意共享的全局 fd 表**。`usb_helper_jni.cpp` 的 `g_usb_fds` 上限只有 `MAX_FDS = 4`，且 `nativeClearUsbFds()` 会一次清空全部。Player One 不往这张表里登记，所以不占额度；但 M4 做多相机并存时要确认清理时序不会互相打断（见风险表）。

## 4. 架构设计

### 4.1 SDK 宿主必须是进程级单例

`CameraViewModel` 会创建**两个** `DahengCameraManager`（`main` 与 `guide`）。而 `PlayerOneUsbManager`：

- 自己注册 USB 权限与 detach 广播；
- 自己持有 `UsbDeviceConnection` 并把 fd 绑定到 native registry；
- native registry 是**进程全局**的，且"为同一 registration 替换不同 fd"会直接返回错误码 15（`ACCESS_DENIED`）。

厂商已明确确认这一点：*"同一进程应共享一个长期存在的 `PlayerOneUsbManager`，不要为每台相机、Activity 或 Fragment 分别创建实例。一个 manager 可以同时枚举、授权并维护主相机和导星相机；不同 USB 设备会使用不同 registration ID 和 fd。多个 manager 操作同一设备时会获得相同 registration ID，但可能尝试绑定不同 fd，或者在其中一个 manager 关闭时注销另一个仍在使用的全局 registration。"*

因此**不能**让两个 Manager 各建一个 `PlayerOneUsbManager`。新增进程级单例：

```
object PlayerOneSdkHost {
    fun ensureStarted(context: Context)      // application context，幂等
    fun refreshDevices(): List<UsbCameraDevice>
    fun requestPermission(device, onResult: (Boolean) -> Unit)
    fun cameras(): List<CameraProperties>    // PlayerOneCameraSdk.getCameraCount/getCameraProperties
    fun claim(cameraId: Int): Boolean        // 主/导星互斥占用，避免两路同时 open 同一台
    fun release(cameraId: Int)
}
```

`claim/release` 是新增的、现有三家没有的机制——因为 Player One 的相机句柄由全局 SDK 分发而非各 Manager 独立枚举，必须显式防止主相机与导星相机抢同一台设备。

### 4.2 与现有 Manager 的集成点

| 位置 | 改动 |
|---|---|
| `DahengCameraManager.kt:25` | `CameraBrand` 增加 `PLAYERONE` |
| `DahengCameraManager.kt:52-57` | 增加 `PLAYERONE_VENDOR_ID = 0xA0A0` |
| `usbReceiver`（约 86-130 行） | attach/detach 的 VID 判断加入 Player One；detach 时**不要**自行拆 native 状态，SDK 已经在 detach 广播里做了协调清理，我们只需清 UI 状态与 `activeCamera` |
| `enumerateDevices()`（约 244-352 行） | 新增分支：调用 `PlayerOneSdkHost.refreshDevices()`，把结果映射为 `DeviceEntry`。注意**未授权设备也会出现在列表里**，与现有品牌行为一致 |
| `openCamera()` / `openCameraBySn()`（约 398-447 行） | 新增分支：走 `PlayerOneSdkHost.requestPermission()`，callback **可能同步回调**（系统已授权时），实现必须两种时序都正确 |
| `app/src/main/res/xml/usb_device_filter.xml` | 增加 `<usb-device vendor-id="41120" />` |

设备身份：`DeviceEntry.serialNumber` 用 `CameraProperties.getSerialNumber()`。**不得**使用 `deviceName`、Android device id 或 `registrationId`——文档明确它们只在本次 attach 会话内有效，不能作为 `openCameraBySn` 的持久身份。

### 4.3 `Camera` 接口映射

新增 `app/src/main/java/com/indigo/mobileobservatory/camera/playerone/PlayerOneCamera.kt`（类名与 SDK 的 `com.playeroneastronomy.camera.PlayerOneCamera` 重名，import 时需别名，例如 `import com.playeroneastronomy.camera.PlayerOneCamera as PoaCamera`）。

| `Camera` 成员 | Player One 实现 | 备注 |
|---|---|---|
| `isOpen` / `isCapturing` | 自维护 `MutableStateFlow`，以 `getState()` 兜底校验 | |
| `cameraInfo` | `CameraProperties`：`cameraModelName`、`serialNumber`、`maxWidth/maxHeight`、`bitDepth`、`sensorModelName`、`pixelSizeMicrometers` | 字段一一对应，无需 `SENSOR_LOOKUP` 猜测 |
| `exposureRange` / `hwExposureMaxUs` | `getConfigAttributes(PoaConfig.EXPOSURE)` 的 min/max（INTEGER，单位 μs） | |
| `gainRange` / `setGain` | `PoaConfig.GAIN`（INTEGER） | **1 gain = 0.1 dB**（厂商确认）。项目侧是 `Float` dB 语义，转换为 `db = gain × 0.1`、`gain = round(db × 10)` |
| `setExposureTime(us)` | `setConfig(EXPOSURE, ConfigValue.ofInteger(us), auto=false)` | |
| `currentPixelFormat` / `supportedPixelFormats` | `PoaImageFormat` ↔ `PixelFormat`，见 4.4 | |
| `currentRoi` / `setRoi` / `resetRoi` | `setImageStartPosition(x,y)` + `setImageSize(w,h)` | 厂商确认：**宽必须整除 4、高必须整除 2，最小 ROI 16×16**。据此设 `roiMinWidth = roiMinHeight = 16`，并在 `setRoi` 内做向下对齐修正 |
| `cropInfo` | 由 ROI 与 `getImageBin()` 推导 | |
| `setReadoutMode` | 首版固定 `NORMAL`；后续可映射 Sensor Mode 与 HCG 配置 | |
| `longExposureEnabled` | Player One 曝光上限由硬件给出，预期不需要软件长曝路径，以实测 `EXPOSURE` max 为准 | |
| `startCapture` / `stopCapture` | `startExposure(false)` 连续曝光 + 专用采集线程轮询 | 见 4.5 |
| `recycleBuffer` | 复用现有 `ByteArray` 池 | 见 4.5 |

### 4.4 像素格式映射

Player One 只给 4 种格式，项目枚举更细，需要靠 `CameraProperties.getBayerPattern()` 补齐：

| `PoaImageFormat` | Bayer 图案 | 项目 `PixelFormat` |
|---|---|---|
| `RAW8` | RG / BG / GR / GB | `BAYER_RG8` / `BAYER_BG8` / `BAYER_GR8` / `BAYER_GB8` |
| `RAW8` | MONO | `MONO8` |
| `RAW16` | RG / BG / GR / GB | `BAYER_RG16` / … |
| `RAW16` | MONO | `MONO16` |
| `MONO8` | — | `MONO8`（彩色相机取灰度） |
| `RGB24` | — | **无对应**（项目只有 16bit/通道的 `RGB48`），首版不暴露 |

`supportedPixelFormats` 由 `CameraProperties.getImageFormats()` 过滤后给出，不硬编码。

### 4.5 采集线程与缓冲

SDK 没有帧回调，只有"轮询 `isImageReady()` + `getImageData(DirectByteBuffer, timeoutMs)`"，与 QHY / ZWO 的模式一致，沿用同款结构：

- 专用高优先级线程 `"PlayerOneCapture"`，`startExposure(false)` 后循环：`isImageReady()` 为假则 `sleep(1~10ms)`，为真则取图。
- **必须** `ByteBuffer.allocateDirect()`，heap buffer 会直接抛 `IllegalArgumentException`；容量不足返回 `SIZE_LESS`。按文档建议**复用 2~3 个 direct buffer 轮转**，禁止每帧分配。
- 项目 `FrameData` 用 `ByteArray`，因此存在一次 direct → `ByteArray` 拷贝。这是本次接入唯一的额外开销，用现有 `recycleBuffer` 池摊平分配成本；若实测帧率受限，再评估给 `FrameData` 增加 direct buffer 通道。
- `onFrame` 后立即交给 `PreviewPipeline.submit()`，遵守"生产者永不阻塞、丢旧保新"的既有约定。
- 所有 SDK 调用（含 `open`/`initialize`/`getImageData`）都不得在主线程执行；`refreshDevices` 与权限申请从主线程发起。

### 4.6 制冷能力

Player One 制冷走 `PoaConfig.COOLER`（BOOLEAN）等配置项，能力由 `CameraProperties.hasCooler()` 声明。当前项目的制冷 UI 通过 `cam as? ToupcamCamera` 硬绑到具体类，接第二家制冷相机时应抽出能力接口（例如 `CoolingCapable`），让 `CameraViewModel.bindCoolingFlows` 面向接口而非具体类。这是本次接入顺带偿还的一笔技术债，放在 M4。

## 5. 实施计划

### 5.0 总览与依赖关系

| 阶段 | 产出 | 能否离线开发 | 是否需要真机 |
|---|---|:---:|:---:|
| M0 SDK 落地 | 构建通过、单帧诊断跑通 | 部分 | **是** |
| M1 枚举授权连接 | 设备出现在列表并能连上 | 可写完 | **是**（验收） |
| M2 采集与预览 | 预览出图 | 可写完 | **是**（验收） |
| M3 参数面板 | ROI / bin / 格式 / Sensor Mode | 可写完 | **是**（验收） |
| M4 制冷与双路 | 制冷接口抽象 + 主导星并存 | 可写完 | **是**（需两台相机） |
| M5 稳定性 | 长跑与异常场景 | 否 | **是** |

**没有相机也能推进**：M1–M4 的代码可以全部写完，纯映射逻辑（像素格式、ROI 对齐、增益换算）有单元测试覆盖；只有验收环节必须插上真机。因此建议一次写到 M2 再集中上机验证，避免反复插拔调试。

### M0 —— SDK 落地与冒烟 `[x]`

> 厂商已解决两个阻塞项，本阶段从"写解包与 patchelf 脚本"缩减为"加一行依赖"。

- [x] 删除已作废的旧版目录 `app/libs/android-sdk/`
- [x] `app/build.gradle.kts` 增加 `implementation(files("libs/playerOne_AndroidSdk/playerone-camera-sdk-release.aar"))`
- [x] `usb_device_filter.xml` 增加 VID 41120
- [x] `app/src/main/jniLibs/README.md` 冻结说明（ZWO 的 `libusb-1.0.so` 来源、`libusb_wrap_fd` 私有 API、官方停更、禁止升级），并在 `AGENTS.md` 同步一条
- [x] 构建验证：`:app:assembleDebug` 通过，APK 内三份 libusb（`libusb-1.0.so` / `libusb1.0.so` / `libusb-1.0-po.so`）并存
- [x] 真机：临时/正式连接路径跑通单帧或预览（2026-08-15 简单测试通过）

**验收**：真机插入 Player One 相机 → 授权 → 出图。ZWO / QHY 代码路径未改，不做额外回归。

### M1 —— 枚举、授权、连接 `[x]`

**已新增** `camera/playerone/PlayerOneSdkHost.kt` + `PlayerOneClaimRegistry.kt`：

- 进程级单例，持有唯一 `PlayerOneUsbManager`（application context），`ensureStarted()` 幂等。
- 封装 `refreshDevices()` / `requestPermission()` / `enumerate()`（属性含 SN）。
- 权限回调兼容同步/异步两种时序。
- `claim/release` 经 `PlayerOneClaimRegistry` 互斥。
- 不做 `close()` 暴露：manager 生命周期跟随进程。

**已修改** `camera/DahengCameraManager.kt`：`CameraBrand.PLAYERONE`、VID、usbReceiver、enumerate、openCamera/openCameraBySn、detach 只清 UI。

### M2 —— 采集与预览 `[x]`

**已新增** `camera/playerone/PoaMapping.kt`、`PlayerOneCamera.kt`：采集线程、direct buffer 轮转、曝光/增益/像素格式。

### M3 —— 完整参数面板 `[x]`

- [x] `setRoi` + `alignRoi`，`resetRoi`，`roiMinWidth/Height = 16`
- [x] bin（`setBin` / `getSupportedBins`），改 bin 后重读 image size
- [x] 像素格式由 `getImageFormats()` 映射，不暴露 RGB24
- [x] Sensor Mode → `ReadoutMode` 名称启发式映射；切换时应用 `getGainsAndOffsets` 预设
- [x] 错误按 `PoaException.getError()` 判类型

> 注：项目尚无独立 Gain/Offset 预设 UI；预设在切换读出模式时自动应用。bin 亦无独立控件，API 已就绪。

### M4 —— 制冷与导星双路 `[x]`

- [x] `camera/CoolingCapable.kt`（含 `CoolingInfo` / `TempHistoryPoint`）
- [x] `ToupcamCamera` 实现该接口；`CameraViewModel.bindCoolingFlows` 改为 `as? CoolingCapable`
- [x] `PlayerOneCamera` 在 `hasCooler()` 时走 `COOLER` / `TARGET_TEMPERATURE` / `TEMPERATURE` / `COOLER_POWER`
- [-] 真机：主+导星两台 Player One 验证 `claim/release`（代码路径已具备；无第二台，暂缓）

### M5 —— 稳定性与长跑 `[x]`

- [x] 单机连续采集，2026-08-15 基本稳定
- [x] 采集中热拔插、反复插拔、重连
- [ ] 两小时级内存曲线（RSS / profiler）：未留记录。长跑现场未观察到越用越卡或崩溃
- [x] Android 版本：基本没问题
- [x] 供电：直连与供电 Hub 基本没问题

**验收：** 单机稳定性按现场使用通过。双路见 M4。内存泄漏没有定量曲线，不作为发布阻断。

## 6. 测试

单元测试（纯 JVM，沿用 `app/src/test/` 现有风格，不碰 native）。`PoaMapping.kt` 刻意做成无 Android 依赖的纯函数，就是为了让下面前三项能在没有相机、没有设备的情况下先写先测：

- `PoaMapping.toPixelFormat`：`PoaImageFormat` × `PoaBayerPattern` 映射表全覆盖
- `PoaMapping.alignRoi`：宽 4 / 高 2 对齐、16×16 下限、越界裁剪的边界值
- `PoaMapping.gainToDb` / `dbToGain`：往返一致性与取整行为
- `PlayerOneSdkHost.claim/release` 的互斥语义（manager 用假实现注入）

真机测试清单（无法自动化，M0/M5 各跑一次）：

- [x] Player One 单机：授权 → 连接 → 预览（2026-08-15 简单测试）
- [ ] Player One 双路：主 + 导星（无第二台，暂缓）
- [x] ~~ZWO / QHY 额外回归~~：本次未改其实现与 JNI，跳过
- [ ] ToupTek 制冷 UI（`CoolingCapable` 抽取后顺手点一下即可，非阻塞）
- [ ] Player One + 其他品牌同时插入并分别作为主/导星相机

## 7. 厂商沟通记录

发出的请求见 [`docs/PLAYERONE_SDK_VENDOR_REQUEST.md`](PLAYERONE_SDK_VENDOR_REQUEST.md)，厂商逐条答复见
`app/libs/playerOne_AndroidSdk/PLAYERONE_SDK_VENDOR_REPLY.md`。答复要点已归入第 2 节，两个阻塞项均已解决。

仍然开放、可在实现过程中再问的问题：

1. 连续采集下 `getImageData` 的 timeout 推荐值，以及 `getDroppedImageCount` 增长的正常阈值（可先用实测值，M2 阶段确定）。
2. `OFFSET` 与 gain 预设（`getGainsAndOffsets`）的推荐用法。
3. Sensor Mode 与我们"读出模式"（Normal / HCG / HDR / 低噪声）的对应关系，M3 再确认。
4. 是否计划提供电动调焦轮 / 滤镜轮的 Android SDK（本轮非目标）。

## 8. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 未来某版 AAR 又退回 `libusb-1.0.so` 这个 SONAME | **ZWO 相机在真机上崩溃**，且编译期无告警 | 每次升级 SDK 都重跑 3.1 的 SONAME 验收；M0 强制 ZWO 真机回归；永远禁止用 `pickFirsts` 掩盖 |
| 后续维护者"顺手升级"ZWO 那份 libusb | ZWO 相机永久失效且无法修复（官方已停更、无源码） | jniLibs 冻结说明 + `AGENTS.md` 明令禁止（M0 任务） |
| `usb_helper_jni.cpp` 全局 fd 表（`MAX_FDS=4`）与 `nativeClearUsbFds()` 一次清空 | 多品牌相机并存时清理时序互相打断 | Player One 不登记该表；M4 多相机并存时验证清理时序 |
| direct buffer → `ByteArray` 拷贝拉低帧率 | 高分辨率下预览掉帧 | M2 实测；必要时给 `FrameData` 增加 direct buffer 通道 |
| 主/导星抢占同一台相机 | 连接失败或状态错乱 | `PlayerOneSdkHost.claim/release` 互斥 |
| USB 供电不足 | 相机中途掉线、曝光失败，**表现酷似 SDK 缺陷**（厂商初期误报的 Android 16 崩溃即为此因） | 测试与用户文档都强调使用独立供电 USB Hub；排查掉线问题时先排除供电 |
| 只有 arm64-v8a 参与构建 | 32 位设备无 Player One 支持 | 与项目现状一致（abiFilters 仅 arm64-v8a），不额外处理 |

## 9. 附录：从 SDK 二进制提取的型号清单

demo 工程与 API 文档都没有给出受支持的 VID/PID 列表。实测确认判断逻辑的位置：`PlayerOneUsbManager` 在 Java 层**不做任何白名单过滤**，而是直接把 `vendorId` / `productId` 交给 `NativeUsbBridge.register(String, int, int)`，由 native 决定是否受支持（不支持返回 `POA_ERROR_INVALID_ARGUMENT = 4`）。PID 表编译进了 `libPlayerOneCamera.so` 的指令立即数中，没有可直接读取的数据表。

不过 `libPlayerOneCamera.so` 的 `.rodata` 里有完整的型号名字符串，共 39 个：

```
Apollo-C / Apollo-M / Apollo-M MINI / Apollo-M MAX / Apollo-M MAX PRO
Apollo 428M MAX / Apollo 428M MAX PRO
Ares-C PRO / Ares-M PRO
Artemis-C PRO / Artemis-M PRO
Ceres-C / Ceres-M / Ceres 462M
Mars-C / Mars-M / Mars-C II / Mars-M II / Mars 662M
Neptune-C / Neptune-M / Neptune-C II / Neptune 664C / Neptune 678C / Neptune 678M
Poseidon-C PRO / Poseidon-M PRO
Saturn-C SQR / Saturn-M SQR
Sedna-C / Sedna-M
Uranus-C / Uranus-M / Uranus-C PRO / Uranus-M PRO
Xena-M / Xena 585M
Zeus 455C PRO / Zeus 455M PRO
```

同时在指令流里定位到一组连续 PID `0x5850`–`0x5855`（`0x5850` 已知是 Uranus-C），推测为 Uranus 系列。完整对照表未能可靠还原，但**这不影响实现**：

- 设备过滤按 VID `0xA0A0` 即可（与现有三家品牌做法一致）；
- `refreshDevices()` 只返回 SDK 认识的设备，不支持的 PID 会被自动忽略；
- 型号名在连接后由 `CameraProperties.getCameraModelName()` 直接给出，无需本地对照表。

因此型号↔PID 对照表只在"未连接时展示兼容机型清单"这种文案场景才有用，属于低优先级需求。上面这 39 个名字已足够写兼容性说明（其中可能包含内部或未发布型号）。

## 10. 参考

- 厂商 API 文档：`app/libs/playerOne_AndroidSdk/PLAYERONE_CAMERA_SDK_API.html`
- Demo 工程：`app/libs/playerOne_AndroidSdk/PlayerOneCameraJni.zip`
- 项目相机接口：`app/src/main/java/com/indigo/mobileobservatory/camera/Camera.kt`
- 实现：`camera/playerone/PlayerOneCamera.kt`、`PlayerOneSdkHost.kt`、`PoaMapping.kt`
- 同类实现参考（轮询式采集）：`camera/qhyccd/QhyCamera.kt`、`camera/zwo/ZwoAsiCamera.kt`
