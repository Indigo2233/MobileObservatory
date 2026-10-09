# 深空摄影序列执行计划

> 状态：**M1–M6 代码已落地，M7 夜间验收未做。** 追踪 [issue #4](https://github.com/Indigo2233/MobileObservatory/issues/4)。
>
> 高级序列的编辑、目录和执行规则改由 [`NINA_ADVANCED_SEQUENCER_PLAN.md`](NINA_ADVANCED_SEQUENCER_PLAN.md) 定义，替代本文第 3.2 节的高级序列部分、第 4 节和第 5.2–5.3 节。
>
> 序列文件的字段和兼容规则以 NINA `develop` 的 `[JsonProperty]`、`$type`、`$id`、`$ref` 为准。格式定下来之后，不再自己设计字段名，也不再另写一套兼容规则。
>
> 状态约定与 `TODO.md` 一致：`[ ]` 待办、`[~]` 进行中、`[x]` 已完成、`[-]` 暂不实施。
>
> 开发分支：`feature/dso-sequence`，从默认分支拉出。本功能的代码和本文档都进这个分支，不混进无关改动。

## 1. 目标

在现有设备连接之上，增加对标 NINA 的深空序列：简单序列、高级序列、触发器。相机页保持现在的 SharpCap 式实时操作（预览、对焦、试拍、录像），序列不塞进预览旁边。

界面是两个全屏页：

1. **编辑**：编排简单序列或高级序列，保存模板，启动一次拍摄。
2. **状态**：一次拍摄进行中和结束后要盯的信息。对标 NINA Imaging 页里和「今夜拍得怎么样」有关的部分：历史图像、自动对焦历史、HFR 与星点数折线、望远镜姿态、目标高度，以及相机温度、滤镜、导星 RMS、当前指令。

设备仍然各自连接。序列只编排已经连上的相机、赤道仪、导星和配件。

## 2. 非目标

- 改掉相机页的预览、直方图、ROI、单帧拍摄和录像。
- 再做一个包办 GOTO、滤镜、调焦的「总主控台」。那些操作留在赤道仪、器材、导星页。
- 圆顶、气象安全监控、并行列。
- 把序列步骤写进 `CameraViewModel` 的一长串新方法。
- 在自动对焦例程存在之前，启用依赖它的触发器和自动对焦曲线。面板先留着。
- 赤道仪 park / unpark。当前协议没有这条指令，结束步骤用停止跟踪和已有的回零位。
- 工业 overlay 设备专属指令。分支上只使用公开树里的相机、赤道仪、导星和配件接口。
- 把 NINA 的 C# 源码拷进本仓库。格式按它的 JSON 读写，执行用本应用的设备端口。NINA 是 MPL-2.0，拷贝源文件会把对应文件变成 MPL。
- 执行并行列、圆顶、气象安全监控。这些类型若出现在文件里，保留 JSON，执行到该节点时暂停并说明。

## 3. 页面

### 3.1 入口

顶栏现有：相机、赤道仪、星图、器材、设置（`MainControlTab`，`CameraScreen.kt`）。增加一个 **序列**。点进去是两个全屏页，页内切换 **编辑 | 状态**，记住上次停在哪一页。

顶栏不再为状态单独占一个标签。手机横屏已经有五个入口，两个新标签会挤。

一次运行开始时自动进入状态页。编辑页在运行中改为只读，并显示「正在拍摄」和回到状态页的入口。停止或跑完后可以再编辑。

其他顶栏页面在有活动运行时显示一条细进度：当前目标、已拍/计划张数、暂停、停止。点这条进入状态页。全局 **STOP** 仍然只负责停赤道仪运动，见第 5.4 节。

夜间红光沿用现有开关。状态页上的曲线在红光下用单一红色深浅，不用彩色图例。

### 3.2 编辑页

页顶：模板名称、打开、保存、另存、简单 / 高级切换、开始。

**简单序列**（一个目标、一张曝光表）：

| 区块 | 内容 |
|---|---|
| 目标 | 名称、赤经赤纬（可从星图或天体库带入）、旋转角（已连接 CAA 时） |
| 曝光表 | 每行：启用、滤镜、曝光时间、增益、偏置（相机支持时）、binning、张数 |
| 抖动 | 每 N 张一次，半径（角秒或导星像素） |
| 开始前 | 制冷到目标温度、GOTO、板解居中、转到旋转角、开始导星、开始时自动对焦、换滤镜后自动对焦 |
| 结束 | 拍完；或目标高度低于阈值；或到达指定时刻。结束后可选：停导星、升温、停止跟踪、回零位、关镜盖 |

开始前的自动对焦勾选，在对焦例程落地前禁用。

**高级序列**（三段）：

1. 开始指令集：整段只跑一次。
2. 目标容器：一个或多个。每个有坐标、条件、指令、触发器。
3. 结束指令集：正常完成或用户停止时跑一次。

编辑方式：左侧或底部是指令种类，中间是三段列表。选中一条可改参数。循环、等待、条件是容器，可以套住别的指令。目标容器内提供一条 **智能曝光**：按滤镜连续拍摄并按间隔抖动，避免手搓「切滤镜 → 曝光 → 抖动」。

触发器挂在整段序列或某个目标上，和指令一起存进模板。触发器种类和优先级见第 4.3 节。依赖自动对焦的触发器，例程未就绪时不出现在可选列表里。

设备没连上时，对应指令可以标成「可跳过」或「失败即暂停」。默认是失败即暂停。

### 3.3 状态页

这一页只读。运行中从引擎和设备状态收集；跑完后读本次会话记录，仍然可以回看。

上半部分是进度和当前图像，下半部分是一组卡片。横屏时图像在左、卡片在右。

| 卡片 | 内容 | 数据从哪来 |
|---|---|---|
| 进度 | 当前目标、当前指令、滤镜、已拍/计划、暂停 / 继续 / 停止、跳过当前帧 | 序列引擎 |
| 当前图像 | 最近一张已保存的 light，自动拉伸。点开放大 | 会话目录里的文件 |
| 历史图像 | 本次会话的胶片条。点选替换「当前图像」。标出滤镜和序号 | 会话索引 |
| HFR / 星点 | 按时间的双折线，每张 light 一个点。换滤镜处打标记 | 保存时后台计算，写入会话索引 |
| 自动对焦 | 历次对焦：时间、温度、滤镜、位置、HFR。点一次可看该次的位置–HFR 曲线 | 对焦例程的记录；没有记录时卡片写明尚未对焦 |
| 望远镜姿态 | 赤经、赤纬、时角、高度、方位、跟踪开/关。适配器能提供镜筒朝向时显示东/西。小字给出站点纬度 | 赤道仪当前坐标和 `SkyWatcherEquatorialMath` 一类换算；没有的字段留空 |
| 目标高度 | 活动目标从今夜黄昏到黎明的高度曲线，当前时刻一条竖线，用户设的最低高度一条横线，中天时刻一个标记 | 目标坐标 + 站点 + 时间 |
| 相机 | 传感器温度、目标温度、制冷功率。复用已有温度历史 | `CoolingCapable` |
| 导星 | 开/关、RMS、是否锁定。点按进入现有导星页 | `GuideModule` / `setGuideRunning` |
| 滤镜与调焦 | 当前槽位、调焦器位置与温度（有的话） | 滤镜轮、电调焦控制器 |

姿态卡片不是三维望远镜模型。它回答「现在指向哪、在子午圈哪一侧、还在不在跟踪」。

目标高度曲线用活动目标的坐标，不用赤道仪的瞬时指向。指向和目标分开，才能看出还没居中，或已经跟丢。

### 3.4 和现有页面的分工

| 页面 | 深空夜里干什么 |
|---|---|
| 相机 | 连接、对焦、构图、试拍、看实时直方图 |
| 导星 | 校准、调算法、看完整导星曲线 |
| 赤道仪 / 星图 | 连接、同步、手动 GOTO、极轴 |
| 器材 | 连接滤镜轮、电调焦、镜盖、CAA，给槽位起名 |
| 序列 · 编辑 | 编排今晚的计划 |
| 序列 · 状态 | 计划跑起来之后盯进度和结果 |

状态卡片上的「打开导星」「打开相机」只做跳转，不在状态页里再放一套调节控件。

## 4. 序列模型

磁盘上的模板就是 NINA 高级序列 JSON，见第 5.5 节。内存里是这棵树，不另做一套字段名。简单序列在保存时写成这棵树的一个固定形状（开始区 + 一个 `DeepSkyObjectContainer` + 结束区，曝光表展开成 `SmartExposure`）。编辑页仍用曝光表，用户不必看见指令树。引擎只跑这一种结构。

### 4.1 指令

缺失设备时按该指令的失败策略处理（第 5.3 节）。

类型名是 `$type` 里逗号前的类名，程序集是 `NINA.Sequencer`。旧文件里程序集写成 `NINA` 时同样认。

| `$type` 类名 | 行为 | 已有入口 |
|---|---|---|
| `SequenceItem.Camera.CoolCamera` | 设目标温度并打开制冷，等到容差或超时 | `CoolingCapable` |
| `SequenceItem.Camera.WarmCamera` | 升温 | `CoolingCapable` |
| `SequenceItem.Imaging.TakeExposure` | 按该条的曝光、增益、滤镜拍一帧 FITS，写入会话目录 | `captureFits()` |
| `SequenceItem.Imaging.SmartExposure` | 按滤镜列表连续拍摄，按它自己的抖动间隔抖动，每张之后评估触发器 | 由拍摄、切滤镜、抖动组成 |
| `SequenceItem.FilterWheel.SwitchFilter` | 转到槽位并等待停止 | `setFilterWheelPosition` |
| `SequenceItem.Telescope.SlewScopeToRaDec` | GOTO | `gotoMountTarget` |
| `SequenceItem.Platesolving.Center` | 板解居中 | `startPrecisionGoto` |
| `SequenceItem.Platesolving.CenterAndRotate` | 居中并转到旋转角 | 居中 + `RotatorController.moveTo` |
| `SequenceItem.Focuser.MoveFocuserAbsolute` | 等到到位或超时 | `FocuserController.moveTo` |
| `SequenceItem.Autofocus.RunAutofocus` | 调用对焦例程，写入自动对焦历史 | **尚无**，见第 8 节 |
| `SequenceItem.Guider.StartGuiding` / `StopGuiding` | 已校准才能开始 | `setGuideRunning` |
| `SequenceItem.Guider.Dither` | 导星偏移一个小量并等待稳定 | **尚无**，见第 8 节 |
| `SequenceItem.Telescope.SetTracking` | 跟踪开/关 | 赤道仪现有跟踪开关 |
| `SequenceItem.Telescope.FindHome` | 回零位 | `goHome` |
| `SequenceItem.Telescope.ParkScope` / `UnparkScope` | 认得出。当前赤道仪没有停放指令，执行时暂停并说明 | 无 |
| `SequenceItem.FlatDevice.OpenCover` / `CloseCover` | 镜盖 / 平场板 | 配件控制器 |
| `SequenceItem.Utility.WaitForTime` / `WaitForTimeSpan` | 等到时刻或时间间隔 | 引擎 |
| `SequenceItem.Utility.WaitForAltitude` | 等到目标高度跨过阈值 | 引擎 + 站点 |
| `Container.DeepSkyObjectContainer` | 一个目标：坐标、条件、触发器、内部指令 | 引擎 |
| `Container.SequentialContainer` | 顺序执行子指令 | 引擎 |

### 4.2 条件

条件挂在容器的 `Conditions` 上，在每一帧之后检查。成立则结束该容器，继续后面的目标或进入结束指令集。

| `$type` 类名 | 含义 |
|---|---|
| `Conditions.LoopCondition` | 拍满给定次数 |
| `Conditions.AltitudeCondition` | 目标高度低于阈值 |
| `Conditions.TimeCondition` | 本地时间到达 |
| `Conditions.TimeSpanCondition` | 经过给定时长 |

### 4.3 触发器

触发器挂在整段序列或某个目标上。在指令之间、以及每一帧保存之后评估。条件成立时插入一组指令，做完再回到原来的进度，不结束当前目标。

| `$type` 类名 | 插入的指令 | 优先级 |
|---|---|---|
| `Trigger.MeridianFlip.MeridianFlipTrigger` | 停导星 → 重新 GOTO 同一坐标（允许过子午圈）→ 板解居中 → 恢复导星；文件里若带翻转后对焦，再跑 `RunAutofocus` | 1（最高） |
| `Trigger.Platesolving.CenterAfterDriftTrigger` | 板解居中 | 2 |
| `Trigger.Autofocus.AutofocusAfterTemperatureChangeTrigger` | `RunAutofocus` | 3 |
| `Trigger.Autofocus.AutofocusAfterTimeTrigger` | `RunAutofocus` | 3 |
| `Trigger.Autofocus.AutofocusAfterFilterChange` | `RunAutofocus` | 3 |
| `Trigger.Autofocus.AutofocusAfterHFRIncreaseTrigger` | `RunAutofocus` | 3 |
| `Trigger.Guider.DitherAfterExposures` | 按张数间隔抖动。`SmartExposure` 自带的抖动间隔与这条同时存在时，只执行先到期的那一次 | 4 |

同一时刻只跑优先级最高的一个，其余留到下一帧再评估。每个触发器有冷却时间，冷却期内不再触发。中天翻转对同一目标只翻转一次，除非用户把目标重新开始。

赤道仪适配器不能越过子午圈时，中天翻转暂停序列并提示，不假装已经翻过去。

自动对焦类触发器在对焦例程落地前不可添加。触发机制、中天翻转、间隔重新居中不依赖对焦例程。

用户暂停或停止时，正在执行的触发器指令也停下。用户停止时仍执行结束指令集。暂停不执行结束指令集。

## 5. 运行时

### 5.1 包和边界

新建 `com.indigo.mobileobservatory.sequence`：

| 类型 | 职责 |
|---|---|
| `NinaSequence` | 模板：一棵 NINA `SequenceRootContainer` 树，含原始 JSON 节点 |
| `SequenceEngine` | 解释执行。协程，支持暂停、继续、跳过当前帧、停止 |
| `SequenceDevicePort` | 引擎看见的设备端口。测试用假实现 |
| `SequenceRunState` | 给界面的状态：阶段、当前指令、张数、最近一次错误、会话 id |
| `SessionLog` | 本次拍摄的索引：每张图的路径、滤镜、HFR、星点数、触发器记录 |

`CameraViewModel` 只持有一个 `SequenceRuntime`（引擎 + 真机端口适配器），并把 `SequenceRunState` 以 `StateFlow` 交给两个页面。端口适配器内部调用现有的拍摄、GOTO、导星、滤镜、制冷方法。

引擎不引用 Compose，不打开 USB。

### 5.2 主循环

```text
执行开始指令集
对每个目标:
    若目标条件已成立 → 跳过
    执行目标内指令
    每保存一帧:
        写会话索引
        后台算 HFR 和星点数
        按优先级评估触发器，最多执行一个
        再检查目标条件
执行结束指令集
```

暂停：当前曝光尽量等它结束再停（避免半帧 FITS）。用户停止：丢掉尚未写完的帧，整段取消，结束区不跑。要跑结束区，点「跳到结束指令」。结束指令集里的单条失败记在状态页上，不再嵌套新的结束流程。

### 5.3 失败

指令失败按文件里的 `ErrorBehavior` 和 `Attempts` 处理，枚举顺序与 `NINA.Sequencer.Utility.InstructionErrorBehavior` 一致：

| 值 | 名称 | 行为 |
|---|---|---|
| 0 | `ContinueOnError` | 重试到 `Attempts` 次后继续下一条。这是 NINA 的默认值 |
| 1 | `SkipInstructionSetOnError` | 跳过当前指令所在容器里剩余的指令 |
| 2 | `AbortOnError` | 中断整段序列，不再执行结束区 |
| 3 | `SkipToSequenceEndInstructions` | 跳到结束区 |

认不出的 `$type` 不是这四种之一：节点原样留在文件里，执行到它时暂停，等用户跳过或停止。导星丢失时暂停是运行时安全行为，不写进序列文件。

### 5.4 全局 STOP

`docs/DESIGN_NOTES.md` 里的全局 STOP 在 GOTO、回零、定距移动时停轴。序列运行中如果用户按了全局 STOP：

- 赤道仪动作立即停。
- 序列进入暂停，不自动继续下一帧。
- 不因此去跑结束指令集。要跑结束区，点运行栏的「跳到结束指令」；序列上的停止按 NINA 只取消、不跑结束区（见 `NINA_ADVANCED_SEQUENCER_PLAN.md` 第 11 节）。

### 5.5 模板和会话放哪

模板就是 NINA 高级序列那一份 JSON，放在应用私有目录 `sequences/*.json`。用户从 NINA 拷出来的文件可以直接打开。本应用保存的文件，NINA 应能打开其中我们已经实现的指令；我们没实现的节点要原样留在文件里，不能保存时丢掉。

格式以 [isbeorn/nina](https://github.com/isbeorn/nina) `develop` 上的 `NINA.Sequencer/Serialization/SequenceJsonConverter.cs` 为准：

- `TypeNameHandling.All`：每个对象有 `$type`，集合还有 `$id`。引用处可以是 `$ref`。
- `PreserveReferencesHandling.All`：列表写成 `{ "$type": "...ObservableCollection...", "$values": [ ... ] }`。
- 类上是 `JsonObject(MemberSerialization.OptIn)`，所以文件里只有标了 `JsonProperty` 的字段，外加序列化器加的 `$type` / `$id` / `$ref`。字段名跟那些 `JsonProperty`，不另起短名字。
- 根是 `NINA.Sequencer.Container.SequenceRootContainer, NINA.Sequencer`。它的 `Items` 固定三条：`StartAreaContainer`、`TargetAreaContainer`、`EndAreaContainer`。目标放在目标区里的 `DeepSkyObjectContainer`。
- 程序集后缀 `, NINA` 与 `, NINA.Sequencer` 都接受。匹配时只看类名。

读写规则：

- 每个节点保留原始 JSON 对象。已知类型读出要执行的字段；未知类型整段保留，编辑页显示类名，执行到它时暂停。
- 保存时先写回已知字段，再把没改过的原始属性附回去。这样圆顶、插件、表达式不会因为我们没实现就被抹掉。
- `$ref` 按 NINA 的方式解析。写回时若结构没变成共享引用，可以展开成完整对象，但同一份文件读进再原样保存时，`$id` / `$ref` 保持不变。
- 旧的简单序列 XML 不读。简单序列只是编辑页，保存出去仍是上面这棵 JSON 树。

会话记录不是 NINA 文件：

- 一次运行：图库下 `Sequences/<模板名>_<本地时间>/`。
- 文件名：`<目标>_<滤镜>_<曝光秒>s_<温度>C_<序号>.fits`。没有的字段省略。
- 同目录写 `session.json`：帧列表、HFR、星点数、触发器、自动对焦记录，外加当时那份序列 JSON 的副本。

状态页回看的是 `session.json` 加这些 FITS，不把图像再复制一份。

实现时不要把 NINA 仓库放进本工程。某个指令的字段以该类的 `[JsonProperty]` 为准，在 GitHub 上打开对应 `.cs` 即可。测试夹具放一份手写的最小 `SequenceRootContainer`，再放一份从 NINA 导出的真实文件（有的话）。没有真实文件也能开工。

## 6. 测量

HFR 和星点数在帧已经写入磁盘之后、在后台线程上算。用降采样后的检测，不阻塞下一帧曝光。算失败就让这个点空着，折线上断开，不写成 0。

星点检测可以先复用导星星点或宽场星点提取里已有的阈值检测，取半峰全宽的中位数作为 HFR。计划在 M6 定一个函数并给它单元测试（人造高斯星，HFR 落在期望区间）。

目标高度曲线不拍照。用目标赤道坐标、站点纬度和当地时间，从当晚天文昏影到晨光每 10 分钟一个点。站点用赤道仪页已经在用的站点。

## 7. 要复用的现有代码

| 现有 | 序列里怎么用 |
|---|---|
| `CameraViewModel.capture()` / `captureFits()` | 科学帧。序列强制 FITS |
| `CoolingCapable` | 制冷、升温、状态页温度 |
| `setFilterWheelPosition` | 切滤镜 |
| `FocuserController`、`RotatorController` | 到位和旋转角 |
| `gotoMountTarget`、`startPrecisionGoto` | GOTO 和板解居中 |
| `setGuideRunning`、`startGuideCalibration` 的结果 | 开始导星前检查已校准 |
| `GuideModule` 的 RMS、历史 | 状态页导星摘要 |
| `SkyWatcherEquatorialMath` | 时角、高度、镜筒朝向（仅该适配器有朝向时） |
| `AssetDeepSkyCatalog`、星图选目标 | 编辑页带入坐标 |
| `MainControlTab` / `CameraScreen` | 挂上序列入口 |
| 全局 STOP | 第 5.4 节，不改它的含义 |

## 8. 本分支里要新做的设备能力

这些不是新页面，是引擎要调用、而现在还没有的动作。

| 能力 | 做法 | 挡住哪一里程碑 |
|---|---|---|
| 抖动 | `GuideModule` 增加一次偏移请求：在设定半径内取随机导星像素，打脉冲，等到误差回到死区或超时。序列不自己算脉冲 | M3 |
| 中天越过 | 端口上一个「GOTO 且允许过子午圈」。适配器做不到就返回明确失败 | M5 |
| 自动对焦 | 独立小循环：若干调焦位置各拍一帧短曝光，算 HFR，拟合后走到最佳位置，把曲线写入会话。不在本计划里展开算法细节；M6 只要求能被触发器调用并留下历史 | M6 的对焦类触发器 |
| 跟踪开关的统一调用 | 若各赤道仪适配器的跟踪入口不一致，端口里收成一个方法 | M2 |

自动对焦算法可以在本分支用一个保守版本（V 曲线、三点足够就停），真机手感放到夜间验收，不阻塞编辑页和状态页骨架。

## 9. 分支

```text
feature/dso-sequence
```

- 从默认分支当前提交拉出。工作区里已有的 libusb 改动、构建日志、其他未提交文档不要带上这个分支。
- 本文档是该分支第一笔提交的内容。
- 不在这个分支上做工业 overlay 的同步或打包。
- 合回默认分支前跑 `app` 的单元测试，以及第 11 节里不依赖真机的项。
- issue #4 保持开着，直到 M7 的夜间验收有结论。

## 10. 里程碑

### M0 模型和引擎骨架 `[x]`

- 读写 NINA `SequenceRootContainer` JSON：三个子容器、已知指令、未知节点原样往返（含 `$id` / `$ref` 和 `, NINA` 旧后缀）。
- 简单序列展开成这棵树的纯函数，带测试。字段只用已经核对过的 `JsonProperty`。
- `SequenceEngine` + 假端口：`LoopCondition`、暂停、停止（停时跑结束区）、跳过、`ErrorBehavior`。
- 尚无界面。

### M1 编辑页和只拍曝光 `[x]`

- 顶栏「序列」，编辑 / 状态两个页能切换。
- 简单序列曝光表可编辑、可保存模板。
- 开始后只执行拍摄（相机已连接、参数已在相机页设好的前提下），状态页有进度和暂停 / 停止。
- 运行中编辑页只读。其他页面出现细进度条。

### M2 状态页骨架 `[x]`

- 当前图像和历史图像胶片条（读会话目录）。
- 相机温度卡片（已有制冷数据）。
- 望远镜姿态卡片。
- 目标高度曲线。
- 导星、滤镜只显示状态，跳转仍进原页面。

### M3 简单序列的设备步骤 `[x]`

- 开始前：制冷、GOTO、板解居中、开始导星、切滤镜。
- 抖动。
- 结束：高度或时刻、停导星、升温、停止跟踪、回零位、关镜盖。
- 导星丢失时暂停。

### M4 高级序列 `[x]`

- 三段编辑、循环、条件、多目标。
- 智能曝光指令。
- 模板含触发器字段（可以先存盘，M5 再执行）。

### M5 触发器 `[x]`

- 评估时机、优先级、冷却、中天只翻一次。
- 中天翻转和间隔重新居中真的会插入指令再回到曝光。
- 对焦类触发器在例程未就绪时不可添加。

### M6 测量和对焦历史 `[x]`

- 每帧 HFR、星点数写入 `session.json`，状态页折线。
- 自动对焦例程的最小版本，历史卡片和单次曲线。
- 温度、时间、滤镜、HFR 四类对焦触发器改为可添加。

### M7 夜间验收 `[ ]`

见第 11 节真机项。代码里程碑可以在 M6 合并，M7 的结论写回 issue #4。

## 11. 验收

不依赖真机：

- 简单曝光表展开后，引擎用假端口按滤镜和张数调用拍摄，顺序稳定。
- 暂停、继续、停止之后，状态里的张数和假端口已完成的帧一致。停止不会调用结束指令；「跳到结束指令」会。
- 高度条件成立时结束当前目标，后面的目标仍会跑。
- 中天翻转在假时钟越过阈值时插入一次，冷却和「只翻一次」生效；同时成立的对焦触发器要等到下一帧。
- 一份含未知指令和 `$ref` 的 NINA JSON 读入再保存，未知节点和引用关系不变；已知指令的字段仍按 `JsonProperty` 的名字。
- HFR 函数对人造星点的结果落在期望区间。

真机（M7）：

- 一个目标、两个滤镜、每滤镜至少两张，能无人值守拍完。暂停后再继续，文件没有半张损坏的 FITS。
- 导星已校准时，序列能开导星并按间隔抖动，抖动后导星能重新锁定。
- 状态页能看到这两张以上的历史图、HFR 点、姿态数字和目标高度曲线。
- 把中天阈值调到「很快就会越过」时，序列会停导星、重新居中、再继续下一帧，而不是直接结束目标。
- 拔掉滤镜轮再跑一条需要切滤镜且策略为暂停的指令，状态页出现明确失败，序列停住。

## 12. 测试位置

| 测试 | 覆盖 |
|---|---|
| `sequence/NinaSequenceTest` | NINA JSON 往返、旧程序集后缀、未知节点保留、简单序列展开 |
| `sequence/SequenceEngineTest` | 循环、暂停、停止、条件、触发器优先级和冷却 |
| `sequence/HfrTest` | 人造星点 |
| `ui/ObservingUiWiringTest` | 顶栏有序列入口；编辑页和状态页是两个全屏页，而不是塞进相机预览 |

真机步骤补进 `docs/testing/HARDWARE_SMOKE_TESTS.md` 的时机是 M3 之后，不在 M0 改那份清单。
