# 相机原生增益与 ISO 控制改造计划

> 状态：M1–M4 的公开树实现已完成；工业相机读取原生范围、步长和写后回读，dB 信息仅在 SDK 明确提供时显示；DSLR/PTP 适配与硬件验证待接入。
>
> 触发来源：现有相机接口将各厂商增益统一换算为 dB，界面数值与厂商文档、推荐增益和相机原生设置存在偏差；后续单反相机还需要接入离散 ISO 档位。

## 1. 背景

当前 `Camera` 接口通过 `gainRange`、`currentGain` 和 `setGain(db)` 暴露增益。主相机界面固定以 dB 显示，导星界面固定使用 `0..30 dB` 范围，自动曝光也按照固定 dB 增量调节。

各厂商 SDK 的原生参数具有不同语义：

| 厂商/类型 | SDK 原生值 | 当前实现 | 用户常用资料 |
| --- | --- | --- | --- |
| ToupTek/图谱 | 百分比增益，常见 `100..10000`，`100 = 1×` | 换算为 dB | 厂商范围和软件通常使用 `100..10000` |
| ZWO | `ASI_GAIN` 整数，常见 `0..600` | 按 `10 units = 1 dB` 换算 | 推荐增益通常写作 `100`、`120` 等原生值 |
| Player One | `PoaConfig.GAIN` 整数 | 按 `10 units = 1 dB` 换算 | HDR、HCG、Unity、最低读噪预设均使用原生值 |
| QHY | `CONTROL_GAIN`，范围和步长由 SDK 返回 | 数值直接使用，接口和界面仍标记为 dB | 厂商资料使用型号对应的原生值 |
| 工业相机 | GenICam/厂商 SDK 原生值 | 私有 overlay 适配器提供范围 | 厂商取值、步长和推荐参数 |
| DSLR/单反 | ISO 离散列表 | 尚未接入 | 用户和相机菜单均使用 ISO 档位 |

统一显示 dB 会隐藏厂商推荐值与当前设置的直接关系。QHY 当前还存在单位标注与实际值不一致的问题。该改造将用户可见值统一调整为设备原生值，并为自动曝光保留独立的曝光响应尺度。

## 2. 目标

1. 从相机 SDK 或设备协议读取增益/ISO 的范围、步长、默认值、当前值和合法离散值。
2. 主相机、导星和设置页面显示设备原生增益或 ISO。
3. 同时提供数值输入框和线性滑动条。
4. 输入值按设备范围裁剪，并按步长或离散列表吸附。
5. 显示 Unity Gain、HCG、HDR、最低读噪等厂商推荐档位。
6. 自动曝光通过设备能力执行具有一致曝光意义的增益调整。
7. 历史 dB 设备默认值直接废弃，后续仅保存原生值。
8. FITS 文件准确记录原生增益、ISO 和可选 dB 等效值。
9. 为 Nikon D5100 等 PTP 相机的 ISO 控制预留统一能力接口。

## 3. 范围

本计划覆盖：

- `Camera` 增益能力接口和数值模型。
- ToupTek、QHY、ZWO、Player One 四个现有相机适配器。
- 工业相机私有 overlay 适配器。
- 后续 DSLR/PTP 相机的 ISO 能力。
- 主相机控制面板、导星界面和相机默认设置。
- 自动曝光增益调整。
- 设备配置重置和 FITS 元数据。
- 单元测试、UI 测试和真机验收。

本期暂缓：

- 跨型号建立统一的信噪比、满阱容量或电子/ADU 标定曲线。
- 根据目标类型自动选择厂商推荐增益。
- 从在线数据库下载型号推荐参数。
- 改造 Offset、USB 带宽和 Gamma 控件；新能力模型需允许这些参数后续复用。

## 4. 现状代码索引

| 位置 | 当前职责与问题 |
| --- | --- |
| `camera/Camera.kt` | `gainRange`、`currentGain`、`setGain(db)` 固定采用 dB 语义，缺少步长、单位、离散值和推荐档位 |
| `camera/CameraModels.kt` | `FloatRange` 只有最小值、最大值和当前值 |
| `camera/toupcam/ToupcamCamera.kt` | 在百分比增益与 dB 之间换算 |
| `camera/zwo/ZwoAsiCamera.kt` | 将 `ASI_GAIN` 除以 10 后暴露给上层 |
| `camera/playerone/PlayerOneCamera.kt` | 将原生增益除以 10；已经读取厂商推荐预设 |
| `camera/qhyccd/QhyCamera.kt` | 直接使用原生值；读取范围时遗漏 SDK 返回的步长 |
| `camera/AutoExposureController.kt` | 使用固定 `±0.5/1.5/3 dB` 增量 |
| `ui/components/GainSlider.kt` | 仅有连续滑动条，固定显示一位小数和 dB |
| `ui/components/ControlPanel.kt` | 只传递最大增益，最小值默认固定为零 |
| `ui/screens/GuideScreen.kt` | 增益范围硬编码为 `0..30 dB` |
| `ui/viewmodel/CameraViewModel.kt` | 状态只包含单个 `Float`；主相机和导星分别转发增益写入 |
| `settings/DeviceSettingsRepository.kt` | 默认增益只保存 `Float`，缺少编码版本和单位语义 |
| `recording/FITSWriter.kt` | `GAIN` 注释固定为 `gain in dB` |

## 5. 设计决策

### 5.1 用户可见值采用设备原生语义

- ToupTek 显示 SDK 百分比增益整数。
- ZWO 显示 `ASI_GAIN` 原生整数。
- Player One 显示 `PoaConfig.GAIN` 原生整数。
- QHY 显示 `CONTROL_GAIN` 原生值。
- DSLR 显示 ISO 合法档位。
- dB 等效值仅在换算关系明确时作为辅助文本显示，例如 `Gain 100（10.0 dB）`。

原生值使界面、厂商文档、推荐档位和用户已有经验保持一致。

### 5.2 控制描述与运行状态分离

控制描述来自设备能力读取，连接期间保持稳定；当前值来自相机实际回读。建议新增：

```kotlin
enum class GainControlKind {
    NATIVE_GAIN,
    ISO
}

data class GainPreset(
    val value: Float,
    val label: String
)

data class GainCapability(
    val kind: GainControlKind,
    val label: String,
    val unit: String?,
    val min: Float,
    val max: Float,
    val step: Float,
    val defaultValue: Float,
    val allowedValues: List<Float> = emptyList(),
    val presets: List<GainPreset> = emptyList(),
    val decimalPlaces: Int = 0,
    val continuous: Boolean = false
)
```

`allowedValues` 非空时表示离散控制。ISO、部分相机枚举参数以及未来的特殊增益档位使用该形式。

`Camera` 接口调整为：

```kotlin
val gainCapability: GainCapability
val currentGain: Float

fun setGain(value: Float)
fun gainDbEquivalent(value: Float): Float? = null
fun adjustGainForExposure(stops: Float): Float
```

`setGain` 的参数始终采用设备原生值。`adjustGainForExposure` 返回已按设备规则裁剪和吸附的目标值，自动曝光通过该方法请求调整。

### 5.3 数值归一化规则集中管理

新增无 Android 依赖的 `GainValueNormalizer`，统一处理：

- 有效范围裁剪。
- 连续范围按 `step` 吸附。
- 离散值匹配到最近合法档位。
- 根据 `decimalPlaces` 格式化。
- 过滤 `NaN`、无穷值和空输入。
- 最小值等于最大值时生成只读控制状态。

适配器和 UI 均复用该逻辑，避免相同输入在不同入口产生不同结果。

## 6. 厂商适配方案

### 6.1 ToupTek/图谱

1. `getExpoAGainRange()` 返回最小值、最大值和默认值，全部保留为 SDK 原生整数。
2. `getExpoAGain()` 作为当前值。
3. `putExpoAGain()` 直接接收吸附后的原生值。
4. 默认步长使用 `1`；若后续 SDK 提供型号步长，则采用设备值。
5. dB 等效值使用 `20 × log10(gain / 100)`。
6. 自动曝光按 dB/曝光级目标反算原生百分比增益。

### 6.2 ZWO

1. `ASI_GAIN` 的 `minValue`、`maxValue`、`defaultValue` 直接形成能力描述。
2. 步长使用 `1`。
3. `setControlValue(ASI_GAIN, value)` 直接写入原生整数。
4. dB 等效值按 SDK 约定使用 `value / 10`。
5. 已知 Unity Gain 等推荐值可通过型号表或未来 SDK 能力补充；首期允许预设为空。

### 6.3 Player One

1. `PoaConfig.GAIN` 属性的最小值、最大值、默认值和步长直接暴露。
2. SDK 未提供有效步长时使用 `1`。
3. `GainOffsetPreset` 映射为以下推荐档位：
   - Highest Dynamic Range
   - High Conversion Gain
   - Unity Gain
   - Lowest Read Noise
4. 重复数值合并为一个标记，并组合标签。
5. dB 等效值按 SDK 约定使用 `value / 10`。

### 6.4 QHY

1. `getParamRange(CONTROL_GAIN)` 的前三项映射为最小值、最大值和步长。
2. 当前值通过 `getParam(CONTROL_GAIN)` 回读。
3. 写入值按 SDK 步长吸附。
4. 单位显示为空，辅助说明显示为“厂商原生增益”。
5. 缺少可靠响应换算的型号保持当前增益，自动曝光仅调整曝光时间，直至完成型号验证。

### 6.5 DSLR/PTP

1. 从 PTP 配置枚举读取 ISO 合法值和当前值。
2. `kind = ISO`，`label = ISO`，`allowedValues` 保存合法档位。
3. 滑动条按照档位索引均匀分布，例如 `100 → 200 → 400 → 800`。
4. 自动曝光根据曝光级增减 ISO 档位；每一级对应 ISO 倍增或减半，具体目标吸附到相机合法列表。
5. 输入框只接受合法 ISO，提交时吸附到最近合法档位并回填实际值。

### 6.6 工业相机

1. 公共 `Camera.gainCapability` 默认由既有 `gainRange` 生成，私有 overlay 中的工业相机无需立即修改即可使用原生范围和默认步长。
2. 工业适配器后续可覆写 `gainCapability`，提供 GenICam/厂商 SDK 返回的精确步长、单位和推荐预设。
3. 缺少明确 dB 转换时，界面只显示原生值；自动曝光保持当前增益，避免把原生值错误解释为曝光级。
4. 工业相机的既有软件叠加与长曝光能力保持独立于增益控制。

## 7. UI 与交互

### 7.1 通用组件

将 `GainSlider` 扩展为 `GainControl`，结构如下：

```text
增益                         [ 100 ]
|----------●----------------------|
100         Unity 120           600
辅助信息：10.0 dB
```

组件接收 `GainCapability`、当前值、启用状态和提交回调。主相机与导星页面共享该组件。

### 7.2 输入框

- 默认显示设备当前实际值。
- 输入期间保留文本草稿，避免相机状态回读覆盖正在编辑的内容。
- 回车、键盘完成动作或失去焦点时提交。
- 提交时执行解析、裁剪、步长吸附和离散值匹配。
- 提交完成后以相机回读值覆盖草稿。
- 无效文本恢复到最近一次有效值，并显示简短错误提示。
- 整数控制使用整数键盘和整数格式；具有小数步长的设备按 `decimalPlaces` 显示。

### 7.3 滑动条

- 连续增益使用 `[min, max]` 线性位置映射。
- ISO 使用离散列表索引映射。
- 拖动期间更新本地草稿值和数值输入框。
- 松开滑块时调用相机 SDK，避免拖动过程中产生大量 USB 控制请求。
- SDK 写入耗时明显的设备在提交期间显示忙碌状态。
- 自动曝光启用期间延续现有控制锁定策略，界面持续显示相机实际值。

### 7.4 推荐档位

- 推荐档位以滑动条刻度或紧凑按钮显示。
- 点击后直接提交对应原生值。
- 位于设备范围外的预设自动过滤。
- 多个标签对应同一数值时合并显示。
- 推荐档位来源需标记为 SDK、厂商固定表或用户自定义；首期实现 SDK 来源。

## 8. ViewModel 与状态流

主相机新增以下状态：

```kotlin
val gainCapability: StateFlow<GainCapability?>
val gain: StateFlow<Float>
val gainWriteInProgress: StateFlow<Boolean>
```

导星相机提供独立的同类状态，范围来自导星相机实例。移除 `GuideScreen` 的 `0..30 dB` 硬编码。

连接流程调整为：

```text
相机连接成功
  → 读取增益能力
  → 读取当前原生值
  → 应用原生设备默认值
  → 回读实际值
  → 发布 UI 状态
```

读取模式切换、像素格式切换或相机重新初始化后，应重新读取增益能力和当前值。部分相机会在模式切换后改变有效增益范围。

## 9. 自动曝光策略

自动曝光继续使用曝光级 `stops` 作为内部调节尺度。增益适配器负责将曝光级请求映射为设备原生值。

现有调节等级可调整为：

| 亮度比例 | 请求增益变化 |
| --- | --- |
| 严重欠曝 | `+0.5 stop` |
| 中度欠曝 | `+0.25 stop` |
| 轻度欠曝 | `+0.1 stop` |
| 严重过曝 | `-0.5 stop` |
| 中度过曝 | `-0.25 stop` |
| 轻度过曝 | `-0.1 stop` |

映射规则：

- ToupTek：通过百分比增益与幅度比换算。
- ZWO/Player One：通过原生单位与 dB 约定换算，`6.0206 dB = 1 stop`。
- DSLR：按 ISO 比例换算并吸附到合法档位。
- QHY：首期使用适配器提供的范围比例步进；真机标定后可增加型号响应曲线。

自动曝光测试需验证单次调节方向、范围边界、步长吸附和连续模式收敛。

## 10. 配置持久化与历史值处理

### 10.1 新存储格式

设备默认设置增加：

```text
camera.<device>.gain_value
```

`gain_value` 保存设备原生值。保留现有设备身份隔离规则。

### 10.2 历史值处理

现有 `camera.<device>.gain` 使用 dB 语义。本次发布直接废弃该键：读取流程仅使用 `gain_value`，保存流程删除旧键。用户在更新后重新设置各相机的默认原生增益或 ISO。

## 11. FITS 与记录元数据

当前 FITS `GAIN` 卡片固定声明 dB，改造后按以下规则写入：

- 天文相机：`GAIN` 保存厂商原生设置，注释写明品牌原生增益。
- 具有可靠 dB 换算时：增加 `GAINDB` 保存 dB 等效值。
- DSLR：使用 ISO 专用卡片，并在注释中明确 ISO speed。
- 日志、状态信息和错误信息采用与 UI 相同的原生值及单位。
- 文件命名规则保持现状。

FITS 头字段最终命名需在实施阶段结合现有下游软件兼容性验证。验收至少覆盖 Siril、PixInsight 或项目当前使用的 FITS 查看工具。

## 12. 分阶段实施

### M1：能力模型与纯逻辑测试

- [x] 新增 `GainControlKind`、`GainCapability` 和 `GainPreset`。
- [x] 新增 `GainValueNormalizer`，覆盖裁剪、步长吸附、离散匹配和格式化。
- [x] 调整 `Camera` 接口，明确原生值语义。
- [x] 完成旧 dB 接口调用替换。
- [x] 添加纯 JVM 单元测试。

交付结果：上层可以获取完整增益能力，并以设备原生值读写。

### M2：现有四家相机适配

- [x] ToupTek 保留百分比原生增益并实现 dB 等效换算。
- [x] ZWO 保留 `ASI_GAIN` 原生值。
- [x] Player One 保留原生值并暴露四类推荐预设。
- [x] QHY 读取并应用 SDK 步长。
- [x] 工业相机 overlay 通过默认能力接入；精确步长和单位由 overlay 后续覆写。
- [x] 模式切换和重连后重新同步能力与当前值。
- [~] 为各适配器添加映射测试；已覆盖通用规范化逻辑，待真机 SDK 验证。

交付结果：四家相机均以厂商原生值工作，范围和步长来自设备能力。

### M3：主相机、导星与设置 UI

- [x] 将 `GainSlider` 改造为输入框与线性滑动条组合控件。
- [x] 支持整数、小数和离散值显示。
- [x] 增加推荐档位标记和点击行为。
- [x] 主相机控制面板传递完整增益能力。
- [x] 导星界面移除固定范围和 dB 文案。
- [x] 相机默认设置显示原生值和设备单位。
- [x] 输入值在提交时完成裁剪和吸附，并回填实际提交值。

交付结果：所有相机入口使用一致控件，用户可输入精确值或通过滑动条线性调整。

### M4：自动曝光、配置重置与 FITS

- [x] 自动曝光改用曝光级请求和适配器映射。
- [x] 使用 `gain_value` 保存原生值，并删除旧 `gain` 键。
- [x] 更新 FITS 增益/ISO 元数据。
- [x] 更新日志中的单位与格式。
- [~] 增加旧键废弃、自动曝光和 FITS 头测试；通用规范化测试已完成，设备与文件回归待真机阶段。

交付结果：自动功能、配置保存和元数据使用原生值语义。

### M5：DSLR ISO 接入准备

- [ ] 在 DSLR/PTP 适配器中映射 ISO 枚举能力。
- [ ] 验证 Nikon D5100 返回的合法 ISO 列表、当前值和写入行为。
- [ ] 验证 Live View 与单张拍摄期间 ISO 修改限制。
- [ ] 在 FITS/图像元数据中记录 ISO。

交付结果：通用增益控件可直接承载 D5100 等单反相机的 ISO 控制。

## 13. 测试计划

### 13.1 单元测试

- 连续范围最小值、最大值和中间值。
- `step = 1`、`5`、`0.1` 的吸附结果。
- 范围起点非零时的步长计算。
- ISO 离散列表的最近值匹配。
- 空列表、单值列表和无效范围。
- ToupTek 百分比与 dB 双向换算。
- ZWO、Player One 原生值与 dB 换算。
- 旧 dB 配置键在读取时被忽略，在保存时被删除。
- 自动曝光在上下边界停止调整。
- 推荐档位去重和越界过滤。

### 13.2 UI 测试

- 输入整数并提交。
- 输入范围外数值后裁剪回填。
- 输入非法文本后恢复有效值。
- 拖动滑块期间更新草稿，松开后提交一次。
- 外部相机值变化时同步界面。
- 编辑期间外部回读不会覆盖文本草稿。
- ISO 滑动条只产生合法档位。
- 自动曝光启用期间控件状态正确。
- 主相机与导星页面范围分别来自对应设备。

### 13.3 真机矩阵

| 厂商 | 最低要求 |
| --- | --- |
| ToupTek/图谱 | 验证 `100`、中间值、最大值和设备回读 |
| ZWO | 验证厂商推荐增益 `100` 与 SDK/桌面软件一致 |
| Player One | 验证 HCG、Unity、HDR、最低读噪预设 |
| QHY | 验证范围、步长和模式切换后的范围更新 |
| 工业相机 | 验证既有原生范围、步长吸附、长曝光叠加和重连后的能力同步 |
| Nikon D5100 | 验证 ISO 枚举、写入、拍摄文件元数据和重连恢复 |

每台设备还需验证连接、断开、重新连接、像素格式切换、读出模式切换、自动曝光和默认值恢复。

## 14. 验收标准

1. 界面显示值与厂商 SDK、官方软件或相机菜单中的值一致。
2. 范围、默认值和步长来自当前设备能力；设备缺少步长信息时采用明确的适配器默认策略。
3. 输入框可以精确设置增益，滑动条保持线性原生值映射。
4. SDK 每次写入值均位于合法范围并符合步长或枚举约束。
5. 松开滑块后最多产生一次参数写入。
6. Player One 推荐档位与 SDK 返回值一致。
7. 导星界面使用导星相机自身范围和单位。
8. 自动曝光在四家现有相机上保持正确调节方向并可收敛。
9. 更新后的默认增益仅从原生值键读取，旧 dB 键不会影响相机设置。
10. FITS 文件不再把 QHY 原生值错误标记为 dB。
11. ISO 控件只显示并写入相机支持的合法档位。
12. 相关 JVM 测试、Compose UI 测试和硬件冒烟测试通过。

## 15. 风险与处理

| 风险 | 处理方式 |
| --- | --- |
| 厂商 SDK 对增益单位说明不完整 | 主显示采用 SDK 原生值，辅助换算仅在关系明确时启用 |
| 相机模式切换改变增益范围 | 模式切换完成后重新读取能力和当前值 |
| 连续拖动产生大量 USB 请求 | 使用本地草稿，`onValueChangeFinished` 时提交 |
| 历史 dB 配置含义不明确 | 直接忽略旧键并要求用户重新保存原生默认值 |
| QHY 型号间响应差异较大 | 自动曝光策略保留适配器扩展点，首期按步长和范围调节 |
| ISO 档位并非连续数列 | 使用离散列表索引滑动条和最近合法值吸附 |
| FITS 下游软件对字段解释不同 | 同时保留原生值、单位说明和可选等效字段，并进行兼容性验证 |
| 厂商推荐档位重复或越界 | 去重、组合标签并按当前能力范围过滤 |

## 16. 推荐实施顺序

1. 先完成 M1，使原生值语义和吸附规则稳定。
2. 按 ToupTek、ZWO、Player One、QHY 顺序完成 M2，并逐台真机验证。
3. 完成主相机和导星 UI，统一移除固定 dB 文案。
4. 完成旧配置清理、自动曝光和 FITS 元数据。
5. 在 DSLR/PTP 接入时实现 ISO 枚举映射，无需再次修改通用控件模型。
