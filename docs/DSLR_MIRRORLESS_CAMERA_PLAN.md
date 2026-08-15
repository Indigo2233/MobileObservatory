# 单反 / 无反相机接入方案

> 状态：**M1–M3 代码已接入，真机验收未做。** 对应 `TODO.md` 事项 18。增益/ISO 通用模型已落地（见 `docs/CAMERA_NATIVE_GAIN_AND_ISO_PLAN.md`）；本方案覆盖机身协议、Live View、静帧与 UI 接入。
>
> 状态约定与 `TODO.md` 一致：`[ ]` 待办、`[~]` 进行中、`[x]` 已完成、`[-]` 暂不实施。
>
> 锚点机：**Nikon D5100**。先打通一台再复制到 Canon / Sony，不宣称「凡 PTP 都能连」。

## 1. 目标

让可换镜头机身成为 Indigo Observatory 的**主成像相机**之一，走与天文相机相同的设备选择器、预览页和控制面板，而不是另做一套「相机遥控 App」。

典型用法：机身经 T 接环装在望远镜上（常常**没有镜头**），手机 OTG 连接 USB，Live View 构图/对焦，然后按张数 × 曝光拍 RAW 作为 lights。

必须做到：

1. USB 插入后出现在主相机设备列表，授权、连接、断开与现有品牌一致。
2. Live View 进入现有预览管线，足以构图和对焦。
3. 静帧拍摄把机身文件（JPEG / NEF 等）落到图库；ISO 写入 FITS/EXIF 时用 `ISOSPEED`，不写天文 `GAIN`。
4. ISO 复用已有 `GainControlKind.ISO` 与离散滑条。
5. 超过机身定时快门上限的曝光走 B 门 + 软件计时。
6. 不出现在导星相机列表。
7. 与事项 17 的手机 Camera2 板解路径互不抢设备。

## 2. 非目标（本期不做）

- 把单反当导星相机（Live View 帧率、快门时滞和滚动快门都不适合 PHD 式导星）。
- Wi-Fi / PTP-IP / USB 网络（Imaging Edge、Canon CCAPI 等）。
- 厂商闭源 SDK（Nikon SDK、EDSDK、Sony Camera Remote SDK）及其 Windows/桌面绑定。
- `libgphoto2` 及其 camlib 插件（体积、GPL/LGPL 插件加载、Android USB 与现有 libusb SONAME 冲突）。
- 额外的 libusb `.so`（PTP 只用 Android `UsbDeviceConnection`）。
- 闪光灯、自动对焦、机身录像、机内 HDR、焦点包围。
- 从 Live View 流录 SER / 科学 FITS（Live View 是预览，不是 lights）。
- 把 CHDK、Magic Lantern、第三方固件当作支持面。
- 工业 overlay、制冷、ROI 裁切传感器、硬件 bin。
- 事项 17 的 `PhoneCamera` / Camera2。

## 3. 产品：两条路径，不要合成一条

天文相机的 `Camera.startCapture` 是连续科学帧。单反不是这样。

```text
连接
  ├─ Live View（构图 / 对焦 / 可选板解预览）
  │     startCapture → JPEG 小图 → 预览管线
  │     禁止当作 lights 写入 SER/FITS
  └─ 静帧（lights）
        停 Live View → 快门或 B 门 → 从机身存储下载 JPEG/RAW
        现有「拍摄 / 录制」在单反上映射为「拍 N 张」，不是连续码流
```

Nikon 等机身在静帧时必须先停 Live View。ViewModel 要显式切换，不能一边取流一边 `InitiateCapture`。

| 路径 | 分辨率 / 格式 | 用途 |
|---|---|---|
| Live View | D5100 约 640×424 JPEG，十余 fps | 构图、对焦、确认是否在目标上 |
| 静帧 JPEG | 机身设定尺寸 | 快速确认、直方图 |
| 静帧 RAW（NEF/CR2/CR3/ARW） | 全分辨率 | 真正的科学数据 |

## 4. 边界

| 事项 | 是什么 | 与本方案 |
|---|---|---|
| 17 手机板解 | 手机内置 Camera2 | 不是 USB 机身；枚举层分开 |
| 增益 M1–M4 | 原生 Gain / 离散 ISO 控件 | **直接复用**；本方案只提供 `isoCapability()` 的数据 |
| Player One 等天文相机 | 连续 RAW 帧 | 接口尽量兼容，能力用扩展接口表达差异 |
| 赤道仪 / 滤镜轮 / 电调焦 | 已有配件栈 | 单反不替代电调焦；无镜头时不提供光圈 |

## 5. 现状代码索引

| 位置 | 与单反的关系 |
|---|---|
| `camera/Camera.kt` | 已加 `supportsHostRoi`、`recordsLiveViewAsScience`、`CameraStillCaptureCapable`；离散快门/B 门仍是 M4–M5 |
| `camera/GainControl.kt` | `GainControlKind.ISO`、`isoCapability()`、`writable`、按档 AE **已够用** |
| `camera/CameraModels.kt` | 已有 `PixelFormat.RGB24` |
| `camera/FrameProcessor.kt` | RGB24 直通预览与直方图，不走 Bayer |
| `camera/DahengCameraManager.kt` | `NIKON/CANON/SONY`；Nikon VID + Still Image 类；导星会话跳过 |
| `res/xml/usb_device_filter.xml` | Nikon VID `0x04B0`；代码侧仍要求类 6/1/1 |
| `ui/components/GainSlider.kt` | 离散 ISO 滑条已按档位索引；`writable=false` 时禁用 |
| `ui/components/ExposureSlider.kt` | 连续对数微秒；离散快门档仍是 M5 |
| `camera/ExposureLimits.kt` | LX 只改 UI 量程；与 B 门不是一回事 |
| `recording/FITSWriter.kt` | `kind == ISO` 时已写 `ISOSPEED`；拒绝 RGB24 Live View |
| `ui/viewmodel/CameraViewModel.kt` | Live View 禁止 SER/FITS；JPG 走 `captureStill` |
| `camera/PhoneSkyCapture.kt` | Camera2 静帧，**禁止**与 PTP 适配器混用 |

## 6. 设计决策

### 6.1 自研 Kotlin PTP，走 Android USB Host

PTP（ISO 15740）用 Bulk 命令/数据/响应 + Interrupt 事件即可。Android `UsbManager` / `UsbDeviceConnection` 已经能 claim 静止图像类接口（class 6 / subclass 1 / protocol 1）。

不引入 libgphoto2：camlib 体量大、插件加载不适合 APK，且其 USB 后端会再抢 libusb，与项目里 ZWO/QHY/Player One 三份隔离 SONAME 冲突。不引入第二份 libusb。

许可：对照公开规范与 opcode 列表**自行实现**，不复制 libgphoto2 源码。

### 6.2 先 D5100，再按品牌复制

D5100 是原计划锚点：PTP 资料多、ISO/快门表干净、Live View opcode 明确、最大定时快门 30 s、B 门可做深空。

Canon EOS 与 Sony 的「PTP」是不同方言（事件模型、Live View、PC 连接模式）。公共层只放传输与会话，品牌逻辑分文件，避免过早做成万能 gphoto。

### 6.3 `Camera` 承担 Live View；静帧用能力接口

`DslrCamera` 实现 `Camera`：`startCapture` / `stopCapture` = Live View。不要为预览再开一套页面。

静帧、快门档、B 门、可选光圈用扩展接口（与 `CameraOffsetCapable`、`CameraUsbBandwidthCapable` 同一模式），ViewModel `as?` 后决定按钮含义。天文相机不受影响。

### 6.4 ISO 不另做控件

```kotlin
GainValueNormalizer.isoCapability(allowedIsoValues, currentIso)
```

`setGain` 写入 PTP ISO。Auto ISO、Hi1/Hi2 见 §8.2，不塞进 `allowedValues` 的魔法数字。

### 6.5 快门不是增益

曝光控件对单反显示档位（`1/125`、`30"`、`Bulb`），内部仍可存微秒便于日志。B 门是独立能力，不要把 `hwExposureMaxUs` 假造成 900 s。LX 开关对单反的含义是「UI 是否露出 1 s 以上及 B 门」，不启动工业相机软件叠加。

### 6.6 光圈可选；T 接环是一等场景

深空常见「机身 + T 环、无镜头」。连接后若 PTP 报无光圈或光圈不可写，**隐藏光圈控件**，不报错、不阻塞拍摄。有镜头时再走可选 `CameraApertureCapable`。

### 6.7 科学数据是机身文件

Live View 禁止写 FITS/SER。静帧下载机身 JPEG/RAW。后续若要 FITS，对已落盘的 RAW 另开转换，不在取流线程里编造 Bayer 帧。

### 6.8 VID 白名单 **且** 静止图像类

| 厂商 | VID | 注意 |
|---|---|---|
| Nikon | `0x04B0` | 相对干净 |
| Canon | `0x04A9` | 与打印机同 VID |
| Sony | `0x054C` | 与手柄等消费电子同 VID |

只靠 VID 会误认打印机和 DualShock。枚举条件：VID ∈ 白名单 **并且** 存在 Still Image 接口。PID 表在 M0 插机后写入，不在文档里猜。

`usb_device_filter.xml` 同样按 VID 加项，避免系统把打印机插入当成打开本 App；仍以代码侧类检查为准。

### 6.9 导星列表不出现单反

`DahengCameraManager(sessionName = "guide")` 枚举时跳过 `CameraBrand.NIKON/CANON/SONY`。用户把单反插错口时，主列表可见、导星列表不可选。

## 7. 模块划分

```text
camera/dslr/
  PtpTransport.kt       USB bulk + interrupt，超时、取消、claim
  PtpSession.kt         OpenSession / CloseSession / GetDeviceInfo / GetStorage
  PtpContainer.kt       command / data / response 编解码
  PtpConstants.kt       标准 opcode、response、object format
  DslrCamera.kt         实现 Camera + 扩展接口，品牌无关编排
  NikonPtpBackend.kt    D5100：属性、Live View、快门、下载
  CanonPtpBackend.kt    后期
  SonyPtpBackend.kt     后期
  JpegLiveViewDecoder.kt
```

`DahengCameraManager` 只负责：VID+类识别、权限、打开 `DslrCamera(backend)`。PTP 会话不要写进 Manager。

建议线程模型：

- 专用 `DslrPtp` 单线程串行化所有 PTP 命令（机身几乎都不能并发）。
- Live View 取图循环在该线程或与其互斥的采集线程；与静帧互斥。
- 禁止在主线程做 USB 传输。

## 8. 接口扩展

### 8.1 静帧

```kotlin
enum class DslrStillFormat { JPEG, RAW, JPEG_PLUS_RAW }

data class DslrStillResult(
    val jpegFile: File?,
    val rawFile: File?,
    val iso: Int,
    val exposureUs: Long,
    val bulb: Boolean
)

interface CameraStillCaptureCapable {
    val stillCaptureSupported: Boolean
    val supportedStillFormats: List<DslrStillFormat>
    suspend fun captureStill(format: DslrStillFormat): DslrStillResult
}
```

`captureStill` 内部：停 LV → 设快门/ISO → `InitiateCapture` 或品牌等价命令 → 等 `ObjectAdded` → `GetObject` → 可选恢复 LV。

### 8.2 ISO 能力缺口（增益模型已审查过）

标准数字档（100/125/160… 或 100/200/400…）现有模型足够。接入时仍要补：

| 缺口 | 做法 |
|---|---|
| Auto ISO | 适配器布尔状态，**不要**把 `0` 或 `min==max` 当成 AUTO；FITS 不写 AUTO |
| Hi1 / Hi2 / Lo1 | 映射为等效 ISO + `GainPreset` 标签；或给离散项加显示名 |
| 暂时锁定 | `GainCapability` 增加 `writable`（默认 true）。Live View 或 P/Auto 挡锁 ISO 时列表仍在、控件禁用。`isReadOnly == (min == max)` 不够 |
| `gainRange` 双源 | 适配器同时更新 `gainRange` 与 `gainCapability` |

### 8.3 快门

```kotlin
enum class ShutterKind { TIMED, BULB }

data class ShutterCapability(
    val allowedTimesUs: List<Long>,   // 不含 B 门
    val bulbSupported: Boolean,
    val timedMaxUs: Long              // D5100 = 30_000_000
)
```

- 定时快门：写入 PTP 曝光时间枚举。
- 请求值 > `timedMaxUs` 且允许 B 门：开 B 门、协程计时、关快门。进度用类似 `softwareStackingProgress` 的「已曝 / 目标」展示，但**不是**软件叠加。
- 自动曝光在 Live View 下可调 ISO 和/或快门档；静帧序列进行中不要让 AE 改参数。

### 8.4 对现有 `Camera` 成员的映射

| 成员 | 单反行为 |
|---|---|
| `startCapture` | 开 Live View，回调 JPEG 解码后的 `FrameData` |
| `setExposureTime` | 吸附到快门档；超出定时上限走 B 门（仅静帧路径使用该值） |
| `setGain` | PTP ISO |
| `supportedPixelFormats` | Live View 固定一种预览格式（见 §9），不暴露机身 RAW 位深当「像素格式」 |
| `setRoi` / `resetRoi` | no-op 或映射 LV 数字变焦；`roiMin*` 可等于全幅 |
| `setReadoutMode` | 不用；机内 Picture Control / RAW 压缩不是读出模式 |
| `hwExposureMaxUs` | 定时快门最大值（D5100：30 s），不是 B 门上限 |
| `supportsSoftwareStacking` | `false` |
| `longExposureEnabled` | 只切换 UI 是否显示长档 + B 门 |
| `gainDbEquivalent` | `null` |

## 9. 预览与 `PixelFormat`

Live View 是 JPEG，不是 Bayer。`FrameProcessor` 的 debayer 路径不能用。

建议：

1. 增加 `PixelFormat.RGB24`（每像素 3 字节，packed）。
2. `JpegLiveViewDecoder`：`BitmapFactory.decodeByteArray` → 抽出 RGB24 `ByteArray`。
3. `FrameProcessor` 增加 RGB24 直通（转预览 Bitmap、直方图），不走 Bayer。
4. 导星算法、SER、科学 FITS **拒绝** RGB24 来源，避免把 LV 误存成 lights。

若 RGB24 改动面太大，M2 可临时把 JPEG 解成 `MONO8` 只为出图，M3 再补彩色预览。不得把 JPEG 比特流塞进现有 `FrameData` 充 Bayer。

## 10. UI

不新开「单反页」。控制面板按能力显示：

- ISO：现有 `GainControl`。
- 快门：离散档 + 输入框（`30"` / `1/125` / `45s`）；B 门显示倒计时。
- 光圈：仅当 `CameraApertureCapable` 且可写。
- 像素格式 / ROI / USB 带宽 / Offset / 制冷：隐藏。
- 主按钮：预览中为「开始拍摄序列」；序列中为「停止」（先停后续张，当前一张尽量曝光完或发终止快门）。
- 张数、格式（JPEG / RAW / 双记）放在现有拍摄设置里，仅 `CameraStillCaptureCapable` 时可见。

机身挡位建议：用户设 **M**（或 B）。连接后若检测到 Auto/P/S 且 ISO 不可写，提示切到 M，不要静默失败。

## 11. 品牌差异（实施时按此后端拆分）

### 11.1 Nikon（第一品牌）

- 会话：标准 `OpenSession`。
- 属性：`ExposureTime`、`ExposureIndex`（ISO）、可选 `FNumber`。
- Live View：厂商 opcode（Start/End/GetLiveViewImage）。D5100 在 LV 期间部分属性只读——用 `writable`。
- 拍摄：`InitiateCapture` 或 Nikon Capture；B 门用厂商 B 门或快门保持。
- 下载：`ObjectAdded` → `GetObject`。NEF 可能数 MB，按块读、给进度。
- 菜单：USB 须为 MTP/PTP，不能是 Mass Storage。

M0 必须在真机上确认：PID、GetDeviceInfo 字符串、ISO 列表、能否 LV、30 s 与 B 门。

### 11.2 Canon EOS（第二品牌）

- 进入「PC 连接 / EOS 远程」类模式后才有完整控制。
- 事件靠轮询 EOS 专用事件，不是单纯等 PTP interrupt。
- Live View 与 Evf 模式独立。
- RAW 为 CR2/CR3，体积更大。
- 不引入 EDSDK。

在 D5100 的传输层稳定之后再开；不要与 Nikon opcode 写在同一 `when`。

### 11.3 Sony（第三品牌，最难）

- 部分机身是 MTP 为主、遥控走另一接口；新机常用 Wi-Fi SDK。
- USB 遥控 opcode 随年代变化大。
- M0 对 Sony 单独做一次「能否 USB 开 Live View」探测，不行就标 `[-]` 改 Wi-Fi，不拖住 Nikon/Canon。

## 12. 枚举、权限、电源

1. `CameraBrand` 增加 `NIKON`、`CANON`、`SONY`（或先一个 `DSLR` + 内部品牌，推荐显式三枚举，选择器显示「Nikon D5100」）。
2. `enumerateDevices`：VID + Still Image 类；`GetDeviceInfo` 失败则不当相机。
3. 权限 action 与现有 ToupTek/ZWO 一样按 session 分开，避免主/导星抢广播。
4. 断开：`CloseSession`、release interface、停 LV 线程。
5. 电源：OTG 往往**不能**给单反供电。文档与 UI 提示使用假电池/外电；电量过低只报 PTP 错误会很难查。
6. 线材：短、带供电的 OTG + 数据 USB；与天文相机相同，Hub 供电不足会表现为随机 stall。

## 13. 实施里程碑

| 阶段 | 产出 | 离线可写 | 必须真机 |
|---|---|:---:|:---:|
| M0 可行性 | 枚举、OpenSession、Dump 属性 | 传输层骨架 | **是（go/no-go）** |
| M1 连接 + ISO | 选择器里能连；ISO 控件可读写 | 大部分 | 验收 |
| M2 Live View | 预览出图 | RGB24/解码 | 验收 |
| M3 静帧 JPEG | 单张落到图库 | 下载状态机 | 验收 |
| M4 RAW + B 门 + 序列 | NEF、>30 s、N 张 | 计时/UI | 验收 |
| M5 快门档 UI | 离散快门替代纯对数滑条 | 是 | 验收 |
| M6 稳定 | 长跑、拔线、权限、切 LV/静帧 | 否 | **是** |
| M7 Canon | 一台 EOS 重复 M1–M4 | 后端 | 验收 |
| M8 Sony | 探测后再决定做不做 | — | 探测 |

没有 D5100 时可以写传输层与单元测试（容器编解码、ISO 吸附、B 门计时），**不能**宣称 Live View 或下载已完成。

### M0 —— PTP 可行性 `[~]`

- [x] `PtpTransport` + `PtpSession`：claim 接口、OpenSession、GetDeviceInfo、CloseSession。
- [x] 诊断日志：VID/PID、厂商/型号字符串、ISO 枚举、快门枚举、是否支持 LV opcode。
- [x] `usb_device_filter.xml` 增加 Nikon VID；代码侧类检查。
- [ ] **go/no-go**：手机能稳定 OpenSession 并读到 ISO 列表。失败则停，不进入 UI 接入。插机时看 `DslrCamera` / `FileLogger` 的 DeviceInfo 与 ISO 枚举。

### M1 —— 设备选择器与 ISO `[~]`

- [x] `CameraBrand.NIKON`，主列表可见，导星列表不可见。
- [x] `DslrCamera` 实现 `Camera` 的打开/关闭；`gainCapability = isoCapability(...)`。
- [x] 写 ISO 后回读；`gainWriteInProgress` 覆盖 PTP 延迟。
- [ ] 模式/LV 切换后重读 ISO 列表（沿用增益方案已有同步点）。
- [x] 单元测试：ISO 吸附、非法值、Auto 不进入 `allowedValues`。

### M2 —— Live View 预览 `[~]`

- [x] Start/Stop Live View；JPEG → `FrameData`（代码路径；opcode 以 DeviceInfo 为准）。
- [x] `PixelFormat.RGB24` + `FrameProcessor` 直通。
- [x] 预览背压沿用 `PreviewPipeline`：生产者不阻塞、丢旧保新。
- [x] 录制/SER/FITS 对 LV 帧禁用。
- [ ] 真机：构图可用、切前后台不泄漏 USB。

### M3 —— 静帧 JPEG `[~]`

- [x] `CameraStillCaptureCapable.captureStill(JPEG)`。
- [x] 停 LV → 拍 → 下载 → 图库 → 可选恢复 LV。
- [x] EXIF 含 ISO；若写 FITS 头则 `ISOSPEED`，无 `GAIN`（FITS 仍拒绝 LV RGB24）。
- [x] 失败路径：对焦失败（无镜头忽略 AF 错误）、超时（代码路径）。
- [ ] 真机：存储满、无镜头快门、下载成功。

### M4 —— RAW、B 门、序列 `[ ]`

- [ ] 下载 NEF（或 JPEG+RAW）。
- [ ] B 门：请求 >30 s 时开 B 门、计时、关闭；可取消。
- [ ] 「拍摄 N 张」替代连续录制；张间可恢复短 LV 确认（可选，D5100 上先允许张间黑屏）。
- [ ] 进度：当前张、总张、本张已曝时间。

### M5 —— 快门档 UI `[ ]`

- [ ] 离散快门控件（交互对齐 `GainControl`）。
- [ ] 输入 `1/125`、`30"`、`45s` 的解析与回填。
- [ ] LX 开关只控制是否显示长档/B 门。

### M6 —— 稳定 `[ ]`

- [ ] 30 分钟 LV 长跑：不 stall、不泄漏、回连。
- [ ] 拍摄中拔线：停止、关会话、UI 回到断开。
- [ ] 与一台天文主相机互斥（同时只开一个主相机，与现状一致）。
- [ ] 不回归 ToupTek / ZWO / Player One / QHY 连接。

### M7 / M8 —— Canon / Sony `[ ]`

- [ ] 各选一台锚点机重复 M1–M4。
- [ ] Sony 若 USB LV 不可行，文档改为 `[-]`，不在同一里程碑里硬做 Wi-Fi。

## 14. 测试

### 14.1 单元测试（无相机）

- PTP 容器编解码、endian、transaction id。
- ISO 列表吸附、Hi 标签映射、Auto 排除。
- 快门字符串解析；> timedMax → B 门。
- FITS：ISO 只出 `ISOSPEED`。
- 导星枚举过滤品牌。

### 14.2 真机矩阵

| 机身 | 最低要求 |
|---|---|
| Nikon D5100 | M0–M6 全部 |
| 另一台 Nikon（可选） | 确认 opcode 是否同代 |
| Canon 一台 | M7 |
| Sony 一台 | M8 探测 |

每台另测：MTP/PTP 菜单、无镜头 T 环、有镜头时可选光圈、假电池、OTG 直连与带电 Hub。

## 15. 风险

| 风险 | 处理 |
|---|---|
| 手机 PTP 不稳定 / SELinux | M0 go/no-go；失败则停，不靠猜 |
| 品牌方言分裂 | 传输共享、后端拆分；不抄 gphoto 大插件 |
| Live View 与静帧互斥 | 状态机强制停 LV |
| 误把 LV 当科学帧 | 格式拒绝 + UI 禁用录 SER/FITS |
| Sony/Canon 打印机、手柄误枚举 | VID + 类双过滤 |
| 与 ZWO libusb 冲突 | PTP 不用 libusb |
| 无镜头报 AF 错 | 忽略对焦失败，仍允许快门 |
| 30 s 后 B 门机械磨损 | UI 标明 B 门；不默认连拍极长 B 门 |
| 电量与供电 | 文案 + 错误码区分 USB stall 与电量 |
| `writable` 缺失导致锁 ISO 时仍能拖滑条 | M1 一并加 `GainCapability.writable` |

## 16. 推荐顺序

1. M0 插 D5100：能会话、能读 ISO 再写代码接到选择器。
2. M1 ISO（复用增益控件）→ M2 预览 → M3 JPEG。这三步已经是「能用的遥控取景」。
3. M4 RAW + B 门才是深空主路径。
4. M5 快门档打磨可与 M4 并行。
5. M6 稳定后再考虑 Canon；Sony 单独探测。

预计 Nikon 锚点（M0–M6）明显重于 Player One 那种「厂商已给 Android SDK」的接入，工作量在协议与状态机，不在 ISO 控件。
