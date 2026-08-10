# 曝光控制修复方案（输入框 + 长曝模式）

> 状态：**M1–M4 已落地**；公开树 `compileDebugKotlin` 通过。工业相机 overlay 已加 `supportsSoftwareStacking`；待真机验收 LX 量程与输入框。
>
> 状态约定与 `TODO.md` 一致：`[ ]` 待办、`[~]` 进行中、`[x]` 已完成、`[-]` 暂不实施。
>
> 触发来源：用户反馈「曝光时间只能用对数滑条、无法精确调整」+「长曝/短曝切换疑似有 bug」。

## 1. 背景与目标

主相机控制面板的曝光只有一条对数滑条（`ExposureSlider`）加一排预设按钮，没有数值输入。
排查时又发现长曝开关在天文相机上行为是**反的**（开了上限反而变小），且公开树里 ZWO 还带着一份工业相机软件叠加死代码。

目标：

- 曝光支持**直接输入数值**（带单位），滑条保留作粗调。
- 「长曝光」开关对齐 **SharpCap LX Mode**：只切换 UI 量程与预设，不改变相机本身。
- 天文相机长曝上限 = 硬件上报值；工业相机长曝上限暂定 300 s（背后用软件叠加）。
- 自动曝光开启时不再与手动输入互相打架。

非目标（本轮不做）：导星相机曝光 UI 改造、曝光值持久化到 prefs、长曝叠加算法从平均改累加。

## 2. 现状代码索引

| 位置 | 职责 |
|---|---|
| `ui/components/ExposureSlider.kt` | 对数滑条 + 预设按钮，唯一的曝光输入入口 |
| `ui/components/ControlPanel.kt:899` | 调用 `ExposureSlider`，只传 `exposureUs` / `maxUs`，**不传 `minUs`** |
| `ui/components/ControlPanel.kt:905-932` | 长曝 `Switch` + `longExposureProgress` 文本 |
| `ui/screens/CameraScreen.kt:718` | `exposureMax = viewModel.getExposureMax()`，在 composition 中直接调普通函数 |
| `ui/viewmodel/CameraViewModel.kt:1317-1344` | `setExposure` / `toggleLongExposure` / `getExposureMax` |
| `camera/zwo/ZwoAsiCamera.kt:228-248, 384-443` | **误拷**了工业相机的软件叠加（见 §3.0），对本品不可达 |
| `camera/playerone/PlayerOneCamera.kt:292` | 只做 300 s 裁剪，无叠加 |
| `camera/qhyccd/QhyCamera.kt:40, 550-553` | `longExposureEnabled` 声明后**从未使用** |
| `camera/toupcam/ToupcamCamera.kt:58, 271-276` | 同上，`longExposureEnabled` 未使用 |
| `MobileObservatory-private/.../DahengCamera.kt` 等 | 工业相机（大恒/海康/度申）的软件叠加实现 |
| `camera/AutoExposureController.kt:48-63` | CONTINUOUS 模式持续覆写曝光 |

## 3.0 两层概念必须拆开

| 概念 | 是什么 | 谁需要 |
|---|---|---|
| **长曝模式（LX Mode）** | UI 开关：短量程 ↔ 长量程 + 对应预设。**不对相机做任何特殊事** | 所有相机（天文 + 工业） |
| **软件叠加（Software stacking）** | 实现细节：hwMax 不够时，多帧平均凑出更长有效曝光 | 仅工业相机（hwMax ≈ 1 s） |

SharpCap 文档原文：「The LX Mode checkbox does nothing to the camera, but it changes the range of the slider from short exposures (up to 5s) with the box unchecked to long exposures (1s and up) with the box checked.」
还可直接往数值框里键入 `10.5s` / `30ms` / `5m`。

公开树里 `ZwoAsiCamera` 那套 `handleLongExposureFrame` / `accumBuffer` / `longExpoSub*` 与 `DahengCamera` 几乎同构，是接口对齐时带过来的死代码——**不是**「开长曝 = 软件叠加」。天文相机不需要软件叠加。

## 3. 已确认的缺陷

### B1（严重）长曝开关方向反了

`getExposureMax()` 在长曝开启时硬编码返回 `300_000_000f`（300 s），关闭时返回 `cam.hwExposureMaxUs`。

| 品牌 | hwExposureMaxUs（典型） | 开长曝后的上限 | 结果 |
|---|---|---|---|
| ZWO | ~2000 s | 300 s | **变小** |
| QHY | ~3600 s | 300 s | **变小** |
| Player One | 同量级 | 300 s | **变小** |
| ToupTek | 常见 ~1 s | 300 s | 变大，但无叠加则假量程 |
| 工业相机 | ~1 s | 300 s | 方向对，但依赖叠加实现 |

天文相机用户看到的现象：打开「长曝光」后滑条最大值从 2000 s 掉到 300 s。

> 实施前插机确认 `FileLogger` 的 `Exposure range` 日志；表中天文三行按 SDK 常见值推断。

### B2（中）ZWO 上有工业相机叠加死代码

`ZwoAsiCamera` 拷了 `softMax = 300s` + `handleLongExposureFrame`。
因 `hwMax` ≫ 300 s，路径不可达；同时参与 B1 的反向裁切。应**删除**，不是修通。

### B3（中）工业相机以外的「假量程」

QHY / ToupTek 声明了 `longExposureEnabled` 却从不读取；Player One 只裁到 300 s。
若 UI 允许超出硬件上限，就会「拖了弹回」。长曝上限必须按相机能力计算，禁止对天文相机硬套 300 s。

### B4（中）切换长曝后滑块位置错位

`ExposureSlider` 的 `remember(exposureUs)` 不含 `minUs`/`maxUs`。
切量程时曝光值不变，`sliderPos` 停在旧比例，轻碰会突变。

### B5（中）跨相机状态不同步

`_longExposureEnabled` 是 ViewModel 级状态，连接时不同步到新相机对象。

### B6（中）自动曝光与手动输入互抢

CONTINUOUS 每 10 帧覆写曝光；UI 不禁用滑条。

### B7（轻）滑条量程本身难精调 + 无输入框

七个数量级压一条对数滑条；无 SharpCap 式数值输入。

### B8（轻）工业相机叠加进度形同虚设

`_longExposureProgress` 只显示「长曝光」三字，无 `3/8` 子帧进度。

## 4. 设计决策

### 4.1 长曝模式 = SharpCap LX Mode（所有相机）

开关对**所有相机**都可用，语义：

| 模式 | 滑条量程 | 预设示例 |
|---|---|---|
| **短曝**（关） | `[hwMin, min(hwMax, SHORT_MAX)]`，`SHORT_MAX = 5 s` | 1 ms、10 ms、33 ms、100 ms、500 ms、1 s、5 s（滤掉超上限的） |
| **长曝**（开） | `[LONG_MIN, effectiveMax]`，`LONG_MIN = 1 s` | 1 s、5 s、10 s、20 s、30 s、60 s、120 s、300 s、600 s（滤掉超上限的） |

`effectiveMax`（长曝上限）：

```
if (supportsSoftwareStacking)   // 工业相机
    min(max(hwMax, STACKING_MAX), STACKING_MAX)   // 暂定 STACKING_MAX = 300 s
else                            // 天文相机
    hwMax                       // 与硬件功能一致，可达数百～数千秒
```

要点：

- 开关**不改变相机**，只改 UI 量程与预设；真正下发仍走 `setExposureTime`。
- 从长曝切回短曝时：若当前曝光 > 短曝上限，夹到短曝上限（与现有 `toggleLongExposure` 行为一致，但夹的是 `SHORT_MAX`，不是莫名其妙的 300 s）。
- 从短曝切到长曝时：若当前曝光 < `LONG_MIN`，可保持原值（滑条会停在下限附近），或夹到 `LONG_MIN`——实施时取「保持原值、仅扩展上限」更少打扰。
- **不对天文相机置灰**。之前文档写「置灰」是错的，已废止。

### 4.2 软件叠加 = 工业相机实现细节

在 `Camera` 接口加 `val supportsSoftwareStacking: Boolean get() = false`。
仅私有 overlay 的大恒/海康/度申覆盖为 `true`（已有叠加逻辑保留，上限 300 s）。

公开树：

- **删除** `ZwoAsiCamera` 的叠加死代码；`setExposureTime` 只按 `hwExposureMaxUs` 裁剪。
- `PlayerOneCamera` 去掉 300 s 硬编码裁剪。
- QHY / ToupTek 保持按硬件裁剪。

### 4.3 输入框形态（对齐 SharpCap）

把顶部只读数值换成可编辑输入：

- 支持 `10.5s` / `30ms` / `500us` / 纯数字（纯数字按当前单位解释，默认秒或跟随单位下拉）。
- 输入过程中**不**回调，只在 `onDone` / 失焦时提交。
- 提交后按 `[hwMin, effectiveMax]` 裁剪；裁剪则回填并提示。
- 外部 `exposureUs` 变化时，仅在输入框未获焦时同步文本。
- 组件接收真实 `minUs`/`maxUs`（由当前 LX 模式算出）。

## 5. 实施步骤

### M1 修复长曝语义（B1 / B2 / B3 / B5）

- [x] `Camera` 接口新增 `val supportsSoftwareStacking: Boolean get() = false`。
- [x] **删除** `ZwoAsiCamera` 软件叠加死代码；曝光只按硬件上限裁。
- [x] `PlayerOneCamera` 去掉 300 s 硬编码裁剪。
- [x] 私有 overlay 工业相机覆盖 `supportsSoftwareStacking = true`，叠加逻辑与 300 s 上限保留。
- [x] `CameraViewModel` 按 §4.1 计算 `exposureMin` / `exposureMax` / 预设列表，
      改成由 `_longExposureEnabled` + 相机能力派生的 `StateFlow`（不再在 composition 里调普通函数）。
- [x] `toggleLongExposure`：切回短曝时若超 `SHORT_MAX` 则夹到 `SHORT_MAX`；
      天文/工业都允许切换，不置灰。
- [x] 连接时同步 `_longExposureEnabled` 与相机对象（跟随相机更安全）。

### M2 曝光输入框 + 量程修复（B4 / B7）

- [x] `ExposureSlider`：`remember` key 含 `minUs`/`maxUs`；传入真实量程。
- [x] 数值改为可编辑输入（§4.3）；单位下拉或解析 `s`/`ms`/`us` 后缀。
- [x] 短曝/长曝两套预设（§4.1 表），按 `effectiveMax` 过滤。
- [x] 裁剪提示文案进 `values` / `values-zh-rCN`。
- [x] `ImageUtils.formatExposure`：≥100 s 用整数秒。

### M3 自动曝光互斥（B6）

- [x] `autoExposureMode != OFF` 时禁用曝光输入与滑条。
- [x] `AutoExposureController` 裁剪上限改由调用方传入有效上限。

### M4 工业相机叠加进度（B8）

- [x] 工业相机暴露 `subsDone / subCount`；UI 显示 `3/8`。
- [x] 天文相机不显示叠加进度（它们走硬件长曝）。

## 6. 验收

| 场景 | 期望 |
|---|---|
| 天文相机关长曝 | 滑条 ≤ 5 s，预设为短曝组 |
| 天文相机开长曝 | 上限 = 硬件值（可达数百～数千秒）；预设含 10/20/30/60/120/300/600…（滤超限项） |
| 天文相机开长曝 | **不会**被裁到 300 s；**不会**走软件叠加 |
| 工业相机开长曝设 60 s | 软件叠加生效，进度 `n/N`，上限 300 s |
| 输入 `12.5s` / `30ms` | 精确下发，输入框不在打字中被回写 |
| 输入超上限 | 裁剪回填 + 提示 |
| 切短↔长 | 滑块位置与数值一致，无错位突变 |
| 换相机 / 重连 | 开关与量程跟新相机一致 |
| 自动曝光 CONTINUOUS | 曝光控件禁用 |

回归：导星曝光独立滑条本轮不动；确认 `ImageUtils.formatExposure` 改动无副作用。
