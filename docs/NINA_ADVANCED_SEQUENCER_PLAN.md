# 高级序列对齐 NINA 执行计划

> 状态：**N0、N1 已落地，N2 进行中（主树/抽屉/撤销/锁定），N3–N6 未开始。** 追踪 [issue #4](https://github.com/Indigo2233/MobileObservatory/issues/4)。开发分支 `feature/dso-sequence`。
>
> 本文替代 [`DSO_SEQUENCE_PLAN.md`](DSO_SEQUENCE_PLAN.md) 第 3.2 节里的高级序列部分、第 4 节（序列模型）和第 5.2–5.3 节（主循环与失败）。简单序列、状态页、会话记录、测量仍以那份文档为准。
>
> 状态约定与 `TODO.md` 一致：`[ ]` 待办、`[~]` 进行中、`[x]` 已完成、`[-]` 暂不实施。

## 0. 参考来源

| 项 | 值 |
|---|---|
| 仓库 | [isbeorn/nina](https://github.com/isbeorn/nina) `develop` |
| 版本 | 提交 `5160395`，NINA 3.3.0.1058 |
| 本地参考副本 | `D:\Unity\nina-reference`（仓库外，只读） |
| 中文名称 | `NINA.Core/Locale/Locale.zh-CN.resx`。NINA 没翻译的条目，本文给出暂译并标注 |

NINA 是 MPL-2.0。本仓库只按它的 JSON 格式读写，按它的规则执行，界面按它的结构重画。不拷贝任何 `.cs`、`.xaml`、`.resx` 文件或其中的代码段。中文显示名是单个词条，可以照用。

本地副本只用来查字段名、默认值和执行规则。不要把它加进本工程，也不要作为 git 子模块。

## 1. 为什么重做

第一版高级序列是自己拼的一套目录，只覆盖 NINA 的一小部分，而且有几处和 NINA 不一样。继续在上面修补，会离 NINA 越来越远。这次先把「对齐」定义清楚，再按阶段改。

对齐指四件事：

1. **文件**：同一份 JSON，NINA 和本应用都能打开、保存，不丢字段。
2. **目录**：指令、循环条件、触发器、指令集的种类、中文名、分类、参数名和默认值与 NINA 一致。
3. **执行**：循环、条件、触发器、出错、停止的规则与 NINA 一致。
4. **界面**：结构和操作与 NINA 高级序列一致，布局按手机竖屏和横屏改。

设备能力做不到的指令照样能打开、编辑、保存，执行到它时暂停并说明原因。

## 2. 现有实现与 NINA 的差异

下表每一行都已对照 NINA 源码核实，列出要改的方向。

| 项 | 现在 | NINA | 改成 |
|---|---|---|---|
| 用户停止 | 停止后仍跑结束区 | 取消整段序列，结束区不跑 | 按 NINA，另有「跳到结束指令」 |
| 触发器顺序 | 自定优先级，每帧只跑一个 | 没有优先级。每条指令开始前，先当前指令集、再逐级外层，按列表顺序把所有满足条件的触发器都跑完。单个触发器出错只记日志 | 按 NINA |
| 触发器时机 | 只在每张曝光之后 | 下一条指令开始前检查一次（`ShouldTrigger`），一条指令结束后还检查一次（`ShouldTriggerAfter`，只有少数触发器用） | 按 NINA |
| 触发器插入的指令 | 代码里硬编码 | 触发器自带 `TriggerRunner`（一个顺序指令集），写在 JSON 里 | 读写并执行 `TriggerRunner` |
| 中天翻转参数 | JSON 里自造 `MinutesAfterMeridian` | 触发器没有参数，翻转参数在 NINA 配置文件里 | 移到应用设置，见第 8.4 节 |
| 触发器冷却 | 自造 `CooldownMinutes` | 没有这个字段 | 删除 |
| 抖动像素 | 自造 `Pixels` | 抖动指令和抖动触发器都没有这个字段，抖动幅度在导星设置里 | 移到导星设置 |
| 抖动触发器类型 | `SimpleSequence.kt` 写成 `NINA.Sequencer.Trigger.DitherAfterExposures` | `NINA.Sequencer.Trigger.Guider.DitherAfterExposures` | 修正命名空间 |
| 智能曝光 | 目录里的 `preset:smart` 拼成普通指令集 | `SequenceItem.Imaging.SmartExposure` 本身是容器：切换滤镜、开始曝光两条指令，一个循环条件，一个曝光之后抖动触发器 | 用真正的 `SmartExposure` |
| 表达式参数 | 部分参数只写 `X` 或 `XDefinition` | 每个可写表达式的参数写三项：`XExpression`（对象，只含 `Definition`）、`X`（数值快照）、`XDefinition`（字符串） | 统一写三项，读取时三种都认 |
| 高度条件 | 高度不低于阈值就继续 | 目标还在升起，或高度不低于阈值，就继续 | 按 NINA |
| 条件检查时机 | 每张曝光之后 | 每次挑下一条指令前检查。时间、高度等条件另有后台看门狗，条件不成立时立即打断当前指令集，包括正在进行的曝光 | 按 NINA |
| 默认值 | 各处自定 | 循环次数 2，抖动每 3 张，曝光 60 秒，增益和偏置 −1（相机默认），高度条件阈值 30° | 按 NINA |
| 禁用状态 | 写 `Status: 5` 进文件 | 不写进文件，重新打开后恢复为未开始 | 保留现状，NINA 打开时忽略 |
| 认不出的类型 | 原样保留全部字段 | 替换成占位节点，原字段丢失 | 保留现状，比 NINA 稳 |

## 3. 指令目录

### 3.1 支持级别

| 级别 | 添加面板 | 执行 |
|---|---|---|
| **执行** | 显示 | 调用本应用的设备或引擎 |
| **暂停** | 显示，标「本机暂不支持」 | 执行到时暂停，说明缺什么 |
| **保留** | 默认隐藏，可在「显示全部指令」里打开 | 从文件读入时照常显示和保存。执行到时暂停 |

「保留」对应 NINA 侧栏齿轮里的隐藏功能：没有这类设备的用户看不到它们。

表中 `$type` 省略前缀 `NINA.Sequencer.` 和后缀 `, NINA.Sequencer`。字段标 ⓔ 的是可写表达式的参数，按第 6.2 节写三项。所有指令都有基类字段 `ErrorBehavior`（默认 0）、`Attempts`（默认 1）、`Parent`。

### 3.2 指令集（容器）

| `$type` | 中文名 | 字段（默认） | 级别 | 阶段 |
|---|---|---|---|---|
| `Container.SequentialContainer` | 顺序指令集 | `Strategy`、`Name`、`IsExpanded`、`Conditions`、`Triggers`、`Items` | 执行 | N1 |
| `Container.DeepSkyObjectContainer` | 深空目标指令集 | 同上，加 `Target`（第 6.4 节）、`ExposureInfoList`、`ExposureInfoListExpanded` | 执行 | N1 |
| `Container.ParallelContainer` | 并行指令集 | 同顺序指令集。条件和触发器不评估 | 暂停，N5 执行 | N5 |
| `Container.ConditionalContainer` | 条件指令集（暂译） | 加 `PredicateExpression` | 保留（依赖表达式求值） | — |
| `Container.LinkedTemplateContainer` | 链接模板（暂译） | `TemplateReference`、`TargetOverride`，不写子项 | 保留 | N5 视情况 |
| `Container.SequenceRootContainer` / `StartAreaContainer` / `TargetAreaContainer` / `EndAreaContainer` | 序列 / 开始 / 目标 / 结束 | 根只有全局触发器；三区可挂条件和触发器 | 执行 | N1 |

### 3.3 相机

| `$type` | 中文名 | 字段（默认） | 本应用 | 级别 |
|---|---|---|---|---|
| `SequenceItem.Camera.CoolCamera` | 冷却相机 | ⓔ`Temperature`=0 °C，ⓔ`Duration`=0 分钟（最短降温时长） | `CoolingCapable` | 执行 |
| `SequenceItem.Camera.WarmCamera` | 相机回温 | ⓔ`Duration`=0 分钟 | `CoolingCapable` | 执行 |
| `SequenceItem.Camera.DewHeater` | 防结露加热 | `OnOff`=false | 相机有防露加热控制时执行 | 暂停→执行 |
| `SequenceItem.Camera.SetReadoutMode` | 设置读出模式 | `Mode`=0 | 无统一接口 | 保留 |
| `SequenceItem.Camera.SetUSBLimit` | 设置 USB 限制 | `USBLimit` | ZWO 带宽参数可对应 | 暂停 |

### 3.4 拍摄（NINA 侧栏归在「相机」）

| `$type` | 中文名 | 字段（默认） | 本应用 | 级别 |
|---|---|---|---|---|
| `SequenceItem.Imaging.TakeExposure` | 开始曝光 | ⓔ`ExposureTime`=60 秒 [0,3600]，ⓔ`Gain`=−1，ⓔ`Offset`=−1（−1 表示用相机当前值），`Binning`={X:1,Y:1}，`ImageType`="LIGHT"，`ExposureCount`=0 | `captureSequenceLight` | 执行 |
| `SequenceItem.Imaging.TakeManyExposures` | 多次曝光 | 容器，内含一条开始曝光和一个循环条件；ⓔ`Iterations`=1 | 同上 | 执行 |
| `SequenceItem.Imaging.SmartExposure` | 智能曝光 | 容器：切换滤镜、开始曝光、循环条件、曝光之后抖动；ⓔ`Iterations`=1 | 同上 | 执行 |
| `SequenceItem.Imaging.TakeSubframeExposure` | 开始子帧曝光 | 开始曝光的字段，加 ⓔ`ROIPct`=100 等 | 相机页已有 ROI | 暂停，N4 执行 |

`ImageType` 取值：`LIGHT`、`FLAT`、`DARK`、`BIAS`、`SNAPSHOT`。开始曝光本身没有滤镜和张数，滤镜靠前面的切换滤镜，张数靠外层的循环条件。

### 3.5 滤镜轮

| `$type` | 中文名 | 字段 | 本应用 | 级别 |
|---|---|---|---|---|
| `SequenceItem.FilterWheel.SwitchFilter` | 切换滤镜 | 只写 `ComboBoxText`（滤镜名）。读入时也认旧文件里的 `Filter` 对象（`_name`、`_position`） | 按名称匹配滤镜轮槽位名，找不到时按位置 | 执行 |

### 3.6 电调

| `$type` | 中文名 | 字段（默认） | 本应用 | 级别 |
|---|---|---|---|---|
| `SequenceItem.Focuser.MoveFocuserAbsolute` | 移动电调 | ⓔ`Position` | `FocuserController.moveTo` | 执行 |
| `SequenceItem.Focuser.MoveFocuserRelative` | 相对移动电调 | ⓔ`RelativePosition`=0 | 同上 | 执行 |
| `SequenceItem.Focuser.MoveFocuserByTemperature` | 根据温度移动电调 | ⓔ`Slope`=1，ⓔ`Intercept`=0，`Absolute` | 需电调温度 | 暂停 |
| `SequenceItem.Autofocus.RunAutofocus` | 自动对焦 | 无 | `runSequenceAutofocus` | 执行 |

### 3.7 导星

| `$type` | 中文名 | 字段（默认） | 本应用 | 级别 |
|---|---|---|---|---|
| `SequenceItem.Guider.StartGuiding` | 开始导星 | `ForceCalibration`=false | `setGuideRunning`；为 true 时先校准 | 执行 |
| `SequenceItem.Guider.StopGuiding` | 停止导星 | 无 | 同上 | 执行 |
| `SequenceItem.Guider.Dither` | 抖动 | 无（幅度取导星设置） | `requestGuideDither` | 执行 |

### 3.8 望远镜与板解

| `$type` | 中文名 | 字段（默认） | 本应用 | 级别 |
|---|---|---|---|---|
| `SequenceItem.Telescope.SlewScopeToRaDec` | 指向赤经/赤纬 | 坐标字段（第 6.5 节），`Inherited` | `gotoMountTarget` | 执行 |
| `SequenceItem.Telescope.SlewScopeToAltAz` | 指向高度角/方位角 | 地平坐标 `Coordinates`，ⓔ`Alt`、ⓔ`Az` | 换算成赤道坐标后 GOTO | 执行（N3） |
| `SequenceItem.Telescope.SetTracking` | 设置跟踪 | `TrackingMode`=0。0 恒星速、1 月球速、2 太阳速、3 King、5 停止 | 适配器支持的模式执行，其余暂停 | 执行 |
| `SequenceItem.Telescope.FindHome` | 回零位 | 无 | `goHome` | 执行 |
| `SequenceItem.Telescope.ParkScope` / `UnparkScope` | 停放望远镜 / 解除停放望远镜 | 无 | 协议无停放 | 暂停 |
| `SequenceItem.Platesolving.Center` | 指向并居中 | 坐标字段，`Inherited` | `startPrecisionGoto` | 执行 |
| `SequenceItem.Platesolving.CenterAndRotate` | 指向，居中并旋转 | 坐标字段，ⓔ`PositionAngle`。旧文件的 `Rotation` 读作 `360 − Rotation` | 居中 + 旋转器 | 有旋转器时执行 |
| `SequenceItem.Platesolving.SolveAndSync` | 解析并同步 | 无 | 拍一张、解析、同步 | 执行（N3） |
| `SequenceItem.Platesolving.SolveAndRotate` | 解析并旋转 | ⓔ`PositionAngle`=0，`Inherited` | 旋转器 | 有旋转器时执行 |
| `SequenceItem.Rotator.MoveRotatorMechanical` | 转到机械角（暂译） | ⓔ`MechanicalAngle`=0 | `RotatorController.moveTo` | 有旋转器时执行 |

### 3.9 平场设备

| `$type` | 中文名 | 字段 | 本应用 | 级别 |
|---|---|---|---|---|
| `SequenceItem.FlatDevice.OpenCover` / `CloseCover` | 打开 / 关闭平场镜头盖 | 无 | 镜盖控制器 | 执行 |
| `SequenceItem.FlatDevice.ToggleLight` | 切换灯光 | `OnOff` | 有平场板时 | 暂停→执行 |
| `SequenceItem.FlatDevice.SetBrightness` | 设置亮度 | ⓔ`Brightness` | 有平场板时 | 暂停→执行 |
| `SequenceItem.FlatDevice.TrainedFlatExposure` 等 5 个平场向导容器 | 受过训练的平场曝光、经过训练的暗场曝光、自动调节平场曝光、自动调节平场板亮度、暮光天光平场 | 各自的容器结构 | 平场向导不在本计划 | 保留 |

### 3.10 工具

| `$type` | 中文名 | 字段（默认） | 本应用 | 级别 |
|---|---|---|---|---|
| `SequenceItem.Utility.Annotation` | 批注 | `Text` | 不做事，只显示 | 执行 |
| `SequenceItem.Utility.MessageBox` | 消息框 | `Text` | 弹窗并暂停，点继续后往下走 | 执行 |
| `SequenceItem.Utility.WaitForTimeSpan` | 等待时间 | ⓔ`Time`=60 秒 | 引擎 | 执行 |
| `SequenceItem.Utility.WaitForTime` | 等待到达时刻 | `Hours`、`Minutes`、`Seconds`、`MinutesOffset`、`SelectedProvider`（第 6.6 节） | 引擎 + 太阳位置 | 执行 |
| `SequenceItem.Utility.WaitForAltitude` | 等待到达高度 | 坐标字段，`AboveOrBelow`（`">"` 或 `"<"`），`Data.Offset` | 引擎 | 执行 |
| `SequenceItem.Utility.WaitUntilAboveHorizon` | 等待至目标高于地平线 | 坐标字段，`Data.Offset`=0 | 平地平线 + 偏移。自定义地平线文件不读 | 执行 |
| `SequenceItem.Utility.WaitForSunAltitude` / `WaitForMoonAltitude` | 等待太阳 / 月球达到高度 | `Data`（`Offset`、`Comparator`） | 需新增日月位置 | 执行（N3） |
| `SequenceItem.Utility.WaitUntil` | 等到条件成立（暂译） | `Predicate` 表达式 | 需表达式求值 | 保留 |
| `SequenceItem.Utility.ExternalScript`、`SaveSequence`、`LoadImagingLayout` | 外部脚本、保存序列、加载成像布局（暂译） | 路径字段 | 桌面功能 | 保留 |

### 3.11 其余类别

圆顶（8 条）、安全监视器（等待到安全）、开关（设置开关值）、连接（连接/断开设备、切换配置）、符号（定义常量、定义变量、设置变量等 6 条）全部为 **保留**。

NINA 语言文件里还有「发送命令」「设备操作」两条名称，`develop` 上没有对应的类，本应用不列。

## 4. 循环条件目录

条件挂在指令集的 `Conditions` 上。一个指令集的所有条件都成立才继续（AND），外层指令集的条件也要同时成立。一个条件都没有时，指令集只跑一轮。

| `$type`（前缀 `Conditions.`） | 中文名 | 字段（默认） | 继续的条件 | 级别 |
|---|---|---|---|---|
| `LoopCondition` | 循环指定次数 | ⓔ`Iterations`=2，`CompletedIterations`=0 | 已完成轮数 < 次数。每跑完一轮加一，写进文件 | 执行 |
| `TimeCondition` | 循环到某时刻 | `Hours`、`Minutes`、`Seconds`、`MinutesOffset`、`SelectedProvider` | 现在加上下一条指令的预计时长不超过截止时刻 | 执行 |
| `TimeSpanCondition` | 循环一段时间 | `Hours`、`Minutes`=1、`Seconds` | 从指令集开始算起的剩余时长足够跑下一条 | 执行 |
| `AltitudeCondition` | 循环至目标低于高度 | `Data`（`Offset`=30、`Comparator`）、坐标字段、`HasDsoParent` | 目标还在升起，或高度 ≥ 阈值 | 执行 |
| `AboveHorizonCondition` | 目标高于地平线时循环 | `Data.Offset`=0、坐标字段 | 高度 ≥ 地平线 + 偏移 | 执行 |
| `SunAltitudeCondition` | 循环至太阳到达高度 | `Data`（`Offset`=0、`Comparator`） | 太阳还没越过阈值 | 执行（N3） |
| `MoonAltitudeCondition` | 循环至月球到达高度 | 同上 | 月球还没越过阈值 | 执行（N3） |
| `MoonIlluminationCondition` | 月相 | ⓔ`UserMoonIllumination`=0，`Comparator`=3 | 当前月相照度还没超出阈值 | 执行（N3） |
| `SafetyMonitorCondition` / `LoopWhileUnsafe` | 安全时循环 / 不安全时循环 | 无 | 需安全监视器 | 保留 |
| `LoopWhile` | 条件成立时循环（暂译） | `PredicateExpression` | 需表达式求值 | 保留 |

`Comparator` 的整数值：0 等于、1 小于、2 小于等于、3 大于、4 大于等于、5 不等于。

## 5. 触发器目录

每个触发器都带 `TriggerRunner`：一个顺序指令集，里面是触发时要执行的指令，写在 JSON 里。新建触发器时按下表放入默认指令。

| `$type`（前缀 `Trigger.`） | 中文名 | 字段（默认） | `TriggerRunner` 默认内容 | 何时触发 | 级别 |
|---|---|---|---|---|---|
| `MeridianFlip.MeridianFlipTrigger` | 中天翻转 | 无 | 空。翻转流程固定，见第 8.4 节 | 跟踪中，且过中天分钟数进入设置的窗口 | 执行 |
| `Platesolving.CenterAfterDriftTrigger` | 偏移后居中 | `Coordinates`，ⓔ`DistanceArcMinutes`=10，ⓔ`AfterExposures`=1 | 指向并居中 | 下一条是 LIGHT，每 N 张后台解析一次，偏移 ≥ 阈值 | 执行（N4） |
| `Autofocus.AutofocusAfterExposures` | 拍摄 # 张后自动对焦 | ⓔ`AfterExposures`=5 | 自动对焦 | 自上次对焦后的 LIGHT 张数是 N 的倍数 | 执行 |
| `Autofocus.AutofocusAfterFilterChange` | 切换滤镜后自动对焦 | 无 | 自动对焦 | 滤镜和上次对焦时不同 | 执行 |
| `Autofocus.AutofocusAfterHFRIncreaseTrigger` | HFR增加后自动对焦 | ⓔ`Amount`=5 %，ⓔ`SampleSize`=10，`TrendPerFilter`=true | 自动对焦 | 最近 N 张 HFR 趋势比对焦后第一张高出百分比 | 执行 |
| `Autofocus.AutofocusAfterTemperatureChangeTrigger` | 温度变化后自动对焦 | ⓔ`Amount`=5 °C | 自动对焦 | 温度比上次对焦变化超过阈值 | 执行 |
| `Autofocus.AutofocusAfterTimeTrigger` | 特定时间后自动对焦 | ⓔ`Amount`=30 分钟 | 自动对焦 | 距上次对焦（或开始）超过时长 | 执行 |
| `Guider.DitherAfterExposures` | 曝光之后抖动 | ⓔ`AfterExposures`=3 [0,32] | 抖动 | 下一条是 LIGHT，且已拍 LIGHT 张数是 N 的倍数 | 执行 |
| `Guider.RestoreGuiding` | 恢复导星 | 无 | 开始导星（不强制校准） | 下一条是 LIGHT 且导星没在运行 | 执行 |
| `MeridianFlip.ProgrammableMeridianFlipTrigger` | 可编程中天翻转（暂译） | `BeforeFlipActions`、`AfterFlipActions` | 两段可编辑的指令 | 同中天翻转 | 保留，N5 视情况 |
| `Connect.ReconnectTrigger`、`Connect.ReconnectOnDownloadFailure`、`Dome.SynchronizeDomeTrigger`、`SafetyMonitor.TriggerOnUnsafe`、`Utility.CustomTrigger` | 重连设备、下载失败时重连相机、同步圆顶（触发器）、不安全时触发、自定义触发器（部分暂译） | 各自字段 | — | — | 保留 |

## 6. 文件格式

### 6.1 总体

沿用 `DSO_SEQUENCE_PLAN.md` 第 5.5 节：`TypeNameHandling.All` 与 `PreserveReferencesHandling.All`，对象带 `$type` 和 `$id`，引用写 `$ref`，列表写成 `$values`。程序集后缀 `, NINA` 和 `, NINA.Sequencer` 都认。

| 文件 | 扩展名 | 位置 |
|---|---|---|
| 整段序列 | `.json` | 应用私有 `sequences/`，可导入导出 |
| 模板（一个指令集） | `.template.json` | 应用私有 `sequences/templates/` |
| 目标（一个深空目标指令集） | `.json` | 应用私有 `sequences/targets/` |

扩展名与 NINA 一致，模板和目标文件可以在两边互相拷贝。

### 6.2 表达式参数

NINA 3.3 起，标了表达式的参数在 JSON 里写三项。以曝光时间为例：

| 键 | 内容 |
|---|---|
| `ExposureTimeExpression` | `{"$id": "…", "$type": "NINA.Sequencer.Logic.Expression, NINA.Sequencer", "Definition": "120"}` |
| `ExposureTime` | `120.0`（求值后的快照） |
| `ExposureTimeDefinition` | `"120"` |

少数参数带代理（例如等待太阳高度的 `Offset`，真实值在 `Data.Offset`）：只写 `XExpression` 和被代理的字段，不写 `X` 与 `XDefinition`。

读写规则：

- 读：优先 `XExpression.Definition`，其次 `XDefinition`，最后 `X`。3.3 以前的文件只有 `X`，照常读。
- 写：三项一起写，`Definition` 与数值一致。
- `Definition` 为空字符串表示「用默认值」，例如增益为空就是相机当前增益。
- `Definition` 不是纯数字时就是表达式。本应用不求值：编辑页显示原文并标红「含表达式，本应用暂不支持」，执行到这条时暂停。保存时原文不动。

### 6.3 共用对象

| 对象 | 形状 |
|---|---|
| `Binning` | `{"X": 1, "Y": 1}` |
| 滤镜（旧文件里的 `Filter`） | 字段名带下划线：`_name`、`_position`、`_focusOffset` 等 |
| 执行策略 | `{"$type": "NINA.Sequencer.Container.ExecutionStrategy.SequentialStrategy, NINA.Sequencer"}`，并行用 `ParallelStrategy` |
| 时间来源 | 见第 6.6 节 |

### 6.4 深空目标

`Target` 是 `NINA.Astrometry.InputTarget, NINA.Astrometry`：

- `TargetName`
- `PositionAngle`（度，默认 0）。旧文件只有 `Rotation` 时读作 `360 − Rotation`
- `Expanded`（目标块是否展开）
- `InputCoordinates`：`RAHours`、`RAMinutes`、`RASeconds`、`NegativeDec`、`DecDegrees`、`DecMinutes`、`DecSeconds`

### 6.5 坐标类指令与继承

指向并居中、指向赤经/赤纬、等待到达高度、高度条件等带坐标字段：`Coordinates`（同上 `InputCoordinates`）、ⓔ`Ra`、ⓔ`Dec`、ⓔ`PositionAngle`、`Inherited`。

放在深空目标指令集里时，这些指令自动使用目标坐标，`Inherited` 为 true，编辑页隐藏坐标输入，只显示「使用目标坐标」。拖出深空目标指令集后恢复自己的坐标。

### 6.6 时间来源

`SelectedProvider` 是带 `$type` 的对象，类名在 `NINA.Sequencer.Utility.DateTimeProvider` 下：

| 类名 | 显示 |
|---|---|
| `TimeProvider` | 指定时刻 |
| `SunsetProvider` / `SunriseProvider` | 日落 / 日出 |
| `CivilDuskProvider` / `CivilDawnProvider` | 民用昏影终 / 晨光始 |
| `NauticalDuskProvider` / `NauticalDawnProvider` | 航海昏影终 / 晨光始 |
| `DuskProvider` / `DawnProvider` | 天文昏影终 / 晨光始 |
| `MeridianProvider` | 目标过中天 |

选天文事件时，`MinutesOffset` 是相对该事件的偏移分钟。

### 6.7 状态和计数

- `Status` NINA 不写。本应用只在禁用时写 `Status: 5`，其他状态不写（第 11 节）。
- 循环条件的 `CompletedIterations` 和开始曝光的 `ExposureCount` 会写进文件，与 NINA 一致。开始新一次运行前，引擎把它们清零。

## 7. 执行规则

### 7.1 顺序指令集

对照 NINA 的 `SequentialStrategy`：

```text
轮数 = 0
当 下一条未开始的指令存在 且 可以继续:
    当 下一条存在 且 可以继续:
        检查触发器（当前指令集 → 逐级外层），满足的全部按列表顺序执行
        执行下一条
        取下一条
        检查「指令之后」触发器
    本轮结束: 轮数 + 1，循环条件的已完成轮数 + 1
    若 可以继续: 把本指令集里的子项重置为未开始，进入下一轮
剩下仍未开始的指令标为「已跳过」
```

「可以继续」：本指令集未禁用的条件全部成立（没有条件时为 轮数 < 1），并且外层指令集也可以继续。

### 7.2 条件看门狗

时间、时长、高度、日月高度、月相这些条件，在指令集运行期间每隔几秒后台检查一次。一旦不成立，立即打断当前指令集：正在跑的指令被取消，指令集结束，然后继续它后面的内容。

打断曝光时，未完成的帧丢弃，不写半张 FITS。

### 7.3 出错

| 值 | 名称 | 编辑页显示 | 行为 |
|---|---|---|---|
| 0 | `ContinueOnError` | 继续 | 标为失败，继续下一条 |
| 1 | `SkipInstructionSetOnError` | 跳过当前指令集 | 打断所在指令集 |
| 2 | `AbortOnError` | 中止 | 打断整段序列，结束区不跑 |
| 3 | `SkipToSequenceEndInstructions` | 跳到结束指令 | 打断开始区和目标区，然后跑结束区 |

`Attempts` 是总尝试次数，用尽后才按上表处理。

设备缺失、参数不合法（例如增益超出相机范围）这类问题，编辑页在该行标红。NINA 遇到这类指令直接跳过并标错。本应用改为执行到时暂停，给用户接好设备再继续的机会。

导星丢失时暂停，是本应用的运行时安全行为，不写进文件，与 `DSO_SEQUENCE_PLAN.md` 第 5.3 节一致。

### 7.4 运行控制

| 操作 | 行为 |
|---|---|
| 暂停 / 继续 | 本应用增加的功能。当前曝光尽量拍完再停 |
| 跳过当前 | 当前指令标为已跳过，继续下一条（NINA 运行栏同名按钮） |
| 跳到结束指令 | 打断开始区和目标区，跑结束区（NINA 运行栏下拉里的同名操作） |
| 停止 | 取消整段序列，结束区不跑 |
| 全局 STOP | 只停赤道仪运动，序列进入暂停。与 `DSO_SEQUENCE_PLAN.md` 第 5.4 节一致 |
| 重置进度 | 该行或该指令集及其子项恢复为未开始，循环计数清零 |

### 7.5 并行指令集

N5 实现：子项同时启动，全部结束后指令集结束，不评估条件和触发器。在那之前执行到它时暂停。

## 8. 设备与设置

### 8.1 设备端口

`SequenceHardware` 按第 3 节补方法：回温时长、相对调焦、跟踪模式（五种）、解析并同步、旋转器机械角与位置角、平场板灯光与亮度、防露加热、子帧曝光。适配器做不到的返回明确的「不支持」，引擎据此暂停。

### 8.2 新增计算

| 能力 | 用途 | 阶段 |
|---|---|---|
| 太阳位置（低精度历表） | 日落日出、三种晨昏、太阳高度条件与等待 | N3 |
| 月球位置和照度 | 月球高度条件与等待、月相条件 | N3 |
| 目标升降判断 | 高度条件的「还在升起」 | N0 |
| 目标过中天时刻 | 时间来源里的「过中天」、中天翻转 | N3 |

现有 `tonightWindow` 用固定时刻近似夜晚，N3 改用太阳位置。

### 8.3 验证

每种指令一条验证函数，输入节点和当前设备状态，输出问题列表。编辑页在行名旁显示红色感叹号，点开看问题。运行前把全部问题列一次，与 NINA 开始前的检查单一致。

### 8.4 中天翻转

参数放在应用设置里，字段对照 NINA 配置里的 `MeridianFlipSettings`：

| 设置 | NINA 字段 |
|---|---|
| 过中天后几分钟开始翻转 | `MinutesAfterMeridian` |
| 过中天后最多等几分钟 | `MaxMinutesAfterMeridian` |
| 中天前暂停几分钟 | `PauseTimeBeforeMeridian` |
| 翻转后重新居中 | `Recenter` |
| 翻转后自动对焦 | `AutoFocusAfterFlip` |
| 翻转后稳定几秒 | `SettleTime` |
| 用镜筒朝向判断 | `UseSideOfPier` |

翻转流程：停导星 → 等到翻转窗口 → 越过中天重新 GOTO → 按设置居中 → 稳定 → 按设置对焦 → 恢复导星。赤道仪适配器不能过中天时暂停并提示。

抖动幅度和稳定判据放在导星设置，自动对焦步数和步长放在对焦设置。都不写进序列文件。

## 9. 界面

### 9.1 整体结构（对照 NINA）

| NINA | 本应用 |
|---|---|
| 左侧主树：根标题、全局触发器、开始 / 目标 / 结束三区 | 主树，竖屏占满，横屏在左约 70% |
| 右侧侧栏：指令、模板、目标、符号等标签页，带搜索和分组 | 添加面板：竖屏是底部抽屉，横屏常驻右侧。标签页：指令、模板、目标 |
| 底栏：打开、保存、另存、撤销、重做、锁定、关闭拖放、垃圾桶 | 顶栏溢出菜单放打开、保存、另存、锁定、显示全部指令；撤销、重做是两个图标；拖动时底部出现垃圾桶 |
| 侧栏底部：开始；运行中是跳过、停止 | 底部运行栏：开始；运行中是暂停/继续、跳过（长按出「跳到结束指令」）、停止 |

简单序列保留。简单序列页上有「转为高级序列」，把当前曝光表展开成一棵树进入高级编辑，这是单向转换，与 NINA 一致。高级序列不再自动生成固定模板：新建时就是空的三区。

### 9.2 主树

```text
┌ 序列名（可改）                 撤销 重做 ⋯
│ 全局触发器  ⚡                          ＋
│   曝光之后抖动 · 每 3 张           ○  ⋯
├─ 开始 ─────────────────────────────── ＋
│   冷却相机 · -10 °C · 最短 5 分钟    ○  ⋯
├─◆ 目标 ─────────────────── ＋指令集 ＋目标
│ ┃ 深空目标指令集 · M42            ○  ⋯
│ ┃ 目标  M42 | 05h35m17s | -05°23′ | 0°  ▾
│ ┃ ⚡ 触发器                            ＋
│ ┃ ☰ 循环条件                          ＋
│ ┃ ▤ 指令                              ＋
│ ┃   ≡ 切换滤镜 · Ha                  ✓  ⋯
│ ┃   ≡ 智能曝光 · 3/20               ◌  ⋯
├─◆ 结束 ─────────────────────────────── ＋
└ ［开始］                         运行栏
```

- 三区之间用横线和菱形分隔，与 NINA 一致。区为空时显示淡色提示「序列开始区」「目标区」「序列结束区」。
- 指令集是卡片，左侧一条彩色竖条，颜色按嵌套深度轮换，替代桌面的缩进。
- 指令集内固定三段，顺序是触发器、循环条件、指令，每段有自己的「＋」。段为空时显示「添加触发器」「添加循环条件」，这也是拖放落点。并行指令集和条件指令集不显示前两段。
- 指令集标题可折叠，折叠后只剩一行标题和进度。

### 9.3 指令行

一行从左到右：拖动柄、图标、名称、红色感叹号（有问题时）、参数摘要、状态、`⋯`。

- **参数摘要**：一行小字，例如「120 秒 · LIGHT · 1×1 · 增益 相机」。点行本身在下方展开参数编辑区。桌面上参数直接在行内编辑，手机上改成点开展开。
- **状态**：未开始（空）、运行中（转圈，整行高亮）、完成（勾）、失败（红叉）、已跳过（跳过图标）。
- **`⋯`**：在行下方展开一条操作栏，与 NINA 一致。内容：尝试次数、出错时（四选一）、禁用/启用、重置进度、复制、上移、下移、删除。指令集的操作栏另有「存为模板」，深空目标指令集再加「存为目标」。
- **禁用**：名称半透明加删除线，参数摘要隐藏，`⋯` 换成一个「启用」按钮。
- **运行中**：正在跑的行高亮，主树自动滚动跟随，用户手动滚动后暂停跟随，点「回到当前」恢复。NINA 主树不跟随，这是为手机屏幕做的补充。

### 9.4 参数编辑

编辑控件由第 10 节的字段规格生成，按类型选：

| 类型 | 控件 |
|---|---|
| 数值 | 数字键盘输入框，右侧显示单位（秒、°C、分钟、度、步），超范围标红 |
| 可空数值（增益、偏置） | 输入框，留空显示「相机」 |
| 枚举 | 下拉：图像类型、跟踪模式、出错时、比较方式 |
| 滤镜 | 下拉，列出滤镜轮槽位名，也可手输 |
| Binning | 下拉，列出相机支持的 1×1、2×2… |
| 坐标 | 赤经 时/分/秒、赤纬 度/分/秒三段输入，加「从星图取」「从当前指向取」 |
| 时间来源 | 下拉（第 6.6 节）+ 时分秒 + 偏移分钟，旁边显示今晚算出的具体时刻 |
| 表达式 | 显示原文，只读，标「含表达式」 |

### 9.5 深空目标指令集

- 目标块折叠时一行摘要：名称、赤经、赤纬、位置角。
- 展开后：名称，「从星图取」「从已存目标载入」，赤经赤纬三段输入，位置角，今晚高度曲线（沿用状态页的曲线，标出当前时刻和昏影/晨光）。
- 曝光汇总：列出指令集里各滤镜的张数、单张秒数、总时长，只读。对应 NINA 的 `ExposureInfoList`。

### 9.6 添加面板

- 标签页：指令、模板、目标。
- 顶部搜索框，按名称过滤。
- 指令按 NINA 分类分组，组名用 NINA 中文：`* 指令集 *`、相机、滤镜轮、电调、导星、望远镜、旋转器、平场设备、工具、循环条件、触发器。
- 图标区分种类：指令集是盒子，触发器是闪电，循环条件是便签。
- 从哪个「＋」打开，就只列那一段能放的种类：从触发器段打开只列触发器。
- 「暂停」级别的项显示为灰色并写原因，仍可添加。「保留」级别的项默认不显示。
- 竖屏：点一项就插到打开面板的那个位置的末尾。横屏：还可以按住一项拖进主树的任意位置。

### 9.7 拖放

- 按住拖动柄立即开始拖，按住行的其他位置长按 0.4 秒后开始拖。
- 落点显示一条插入线。空段整块高亮。
- 种类限制：指令和指令集只能进指令段，条件只能进循环条件段，触发器只能进触发器段。指令集不能拖进它自己内部。
- 靠近屏幕上下边缘时主树自动滚动。
- 拖动时底部出现垃圾桶，拖进去即删除，可撤销。
- NINA 按住 Alt 拖动是复制。手机没有这个键，复制用 `⋯` 里的「复制」。
- 锁定或运行中不能拖动。

### 9.8 撤销与锁定

- 每次编辑前把整棵树的 JSON 压栈，撤销、重做各最多 50 步。
- 锁定后主树只读，与 NINA 底栏的锁一致。运行中自动锁定。

## 10. 代码结构

| 模块 | 内容 |
|---|---|
| `sequence/NinaJson.kt` | 保持：原始 JSON 树与读写 |
| `sequence/catalog/` | 新增。每种类型一条规格：`$type`、种类（指令 / 指令集 / 条件 / 触发器）、分类、中英文名、图标、字段规格、默认节点工厂、支持级别、依赖设备、验证函数。按第 3–5 节的分类分文件 |
| `sequence/catalog/FieldSpec.kt` | 字段规格：键、类型、单位、默认值、范围、是否表达式、代理路径 |
| `sequence/NinaExpression.kt` | 表达式三项的读写 |
| `sequence/engine/` | 顺序策略、并行策略、条件看门狗、触发器执行、出错处理、运行控制。替代现在的 `SequenceEngine` 与 `SequencePolicy` |
| `sequence/engine/Executors.kt` | 类名到设备调用的映射 |
| `sequence/astro/` | 太阳、月球位置与晨昏时刻 |
| `ui/screens/sequence/` | 主树、指令行、指令集卡片、目标块、添加面板、拖放、参数编辑器。替代现在的 `SequenceAdvancedEditor.kt` |

编辑器不认具体指令，只按字段规格画控件。新增一种指令只需要加一条规格和一个执行函数。

## 11. 已确认的决定

2026-09-23 确认：

| 项 | 决定 |
|---|---|
| 用户停止 | 按 NINA：停止只取消整段序列，结束区不跑。要跑结束区就点运行栏的「跳到结束指令」 |
| 禁用状态 | 写进文件，`Status: 5`（NINA `SequenceEntityStatus.DISABLED`）。NINA 打开时忽略这个字段。这是本应用唯一多写的字段 |
| 表达式 | 只读显示原文，执行到含表达式的参数时暂停。求值放到本计划之后 |

## 12. 里程碑

### N0 格式与语义纠正 `[x]`

- 修正抖动触发器命名空间。删掉自造字段 `MinutesAfterMeridian`、`CooldownMinutes`、`Pixels`，对应设置移到应用设置。
- 表达式参数三项读写。
- 默认值按 NINA。
- 触发器改成列表顺序、全部执行、指令前后两个时机，删除优先级。
- 高度条件加「还在升起」。
- 停止改为只取消、不跑结束区；运行栏加「跳到结束指令」。禁用继续写 `Status: 5`。

### N1 目录与字段规格 `[x]`

- 第 3–5 节所有类型的规格、中文名、默认节点工厂，包括「保留」级别。
- 触发器写 `TriggerRunner`。智能曝光和多次曝光按 NINA 的容器结构生成。
- 验证函数的骨架：设备是否连接、参数是否在范围内。

### N2 编辑器重做 `[~]`

- 第 9 节的主树、指令行、指令集卡片、目标块、添加面板、拖放、撤销、锁定。
- 横屏双栏，竖屏底部抽屉。
- 简单序列「转为高级序列」。

### N3 执行对齐 `[ ]`

- 新引擎：顺序策略、条件看门狗、触发器、出错、运行控制。
- 第 3–5 节所有「执行」级别的指令、条件、触发器。
- 太阳、月球位置与晨昏时刻；时间来源。
- 中天翻转流程与设置页。

### N4 板解与子帧 `[ ]`

- 偏移后居中触发器（后台解析）、解析并同步、子帧曝光。

### N5 模板、目标与并行 `[ ]`

- 存为模板、存为目标；添加面板的模板页和目标页。
- 星图选中天体后「加入序列」，选一个深空目标模板生成目标。
- 并行指令集执行。
- 视情况：链接模板、可编程中天翻转。

### N6 真机验收 `[ ]`

- 在 NINA 里做一份含开始区、两个目标、智能曝光、循环条件、中天翻转、抖动触发器的序列，拷到手机上打开、执行、保存，再拷回 NINA 打开，字段不丢。
- 反向：本应用做的序列在 NINA 里打开，结构和参数一致。
- 其余夜间项沿用 `DSO_SEQUENCE_PLAN.md` 第 11 节和 `docs/testing/HARDWARE_SMOKE_TESTS.md`。

## 13. 测试

| 测试 | 覆盖 |
|---|---|
| `sequence/NinaSequenceTest` | 表达式三项读写；旧文件只有 `X` 时能读；`Rotation` 转 `PositionAngle`；`SwitchFilter` 只写 `ComboBoxText`；未知类型原样往返 |
| `sequence/catalog/CatalogTest` | 每条规格的 `$type` 与本文一致；默认节点字段齐全；中文名非空 |
| `sequence/engine/StrategyTest` | 无条件跑一轮；多个条件 AND；外层条件打断内层；重置后下一轮 |
| `sequence/engine/TriggerTest` | 同一时刻多个触发器按列表顺序全部执行；先内层后外层；单个触发器出错不影响其他 |
| `sequence/engine/ErrorBehaviorTest` | 四种出错处理；尝试次数；跳到结束指令会跑结束区 |
| `sequence/engine/WatchdogTest` | 假时钟越过截止时刻时打断正在进行的等待 |
| `sequence/astro/SunMoonTest` | 已知日期和站点的日落、天文昏影、月球高度落在公开历表的误差范围内 |
| `ui/SequenceEditorWiringTest` | 三段顺序、添加面板按段过滤、拖放种类限制 |

测试夹具手写，不从 NINA 仓库拷 JSON 文件。
