# WF 第零批与第一批：入口基线、原型和验收记录

2026-10-14。改造前基线：`main / 7485d04`。规则依据：[交互规范](INTERACTION_GUIDELINES.md)、[观测流程](OBSERVING_WORKFLOW_DESIGN.md)、[TODO](../TODO.md)。本记录服务 WF-00、WF-01–04、WF-12，并为每批 WF-11 提供证据位置。

**第零批已补齐入口/返回/状态清单与可走通原型。真人首次点击测试尚未进行；源码、原型和 Android 验收必须分别记录。** 后续手机标定与真实到位验证仍属于 WF-05–09，不能把原型中的模拟状态写成已经实现。

## 1. 检查范围与证据边界

清单覆盖发布构建的全部一级工作区、完整子页面、设备控制、文件/素材入口，以及会改变返回顺序的浮层和编辑器。普通参数行、图像显示开关等不形成新目的地，在其所属页面中统一检查。开发构建的序列和手机诊断单列，不能因此出现在发布包。

改造前源码入口：

- 壳层与全局状态：[CameraScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/CameraScreen.kt)、[状态带](../app/src/main/java/com/indigo/mobileobservatory/ui/components/ObservatoryStatusBar.kt)、[主导航](../app/src/main/java/com/indigo/mobileobservatory/ui/components/ObservatoryNavigation.kt)。基线用 `selectedTab`、多个 `show*` 和 `phoneNav`；一级为相机/赤道仪/星图/器材/工具。
- 目标与观测：[MountControlScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/MountControlScreen.kt)、[StarMapScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt)、[PhonePlateSolveNav](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/PhonePlateSolveNav.kt)、[TargetLibraryScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/TargetLibraryScreen.kt)、[PushToScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/PushToScreen.kt)、[PushToExperienceScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/PushToExperienceScreen.kt)。基线星图回调写死到赤道仪，目标库与手机导航另存目标；无 Stellarium 构建只有资源缺失提示。
- 采集与处理：[预览工具](../app/src/main/java/com/indigo/mobileobservatory/ui/components/CameraPreviewTools.kt)、[StackingScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/StackingScreen.kt)、[BackgroundEditor](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/BackgroundEditor.kt)、[CalibrationPanel](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/CalibrationPanel.kt)、[PlayerScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/PlayerScreen.kt)、[MediaLibrary](../app/src/main/java/com/indigo/mobileobservatory/recording/MediaLibrary.kt)。素材库、保存后查看、运行中源排除已经存在，应迁移入口并保留这些能力。
- 器材与辅助：[AccessoriesScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/AccessoriesScreen.kt)、[SettingsScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/SettingsScreen.kt)、[GuideScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/GuideScreen.kt)、[PolarAlignmentScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/PolarAlignmentScreen.kt)、[PlateSolveScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/PlateSolveScreen.kt)、[ToolsScreen](../app/src/main/java/com/indigo/mobileobservatory/ui/screens/ToolsScreen.kt)。基线器材页有第二排 `DeviceTab`；多页只有工具栏返回，没有应用壳层的系统返回处理。

源码链接会随本轮实施更新；上列“基线”结论均指 `7485d04`，可用 `git show 7485d04:<路径>`复核。静态发现没有被表述为真机手势复现；下文记录改造目标，同轮实现结果另列。

## 2. 完整入口、前置、返回与状态清单

统一状态简称：**O**＝候选目标/光路/地点；**T**＝运行任务自己的目标与参数版本；**D**＝ViewModel/adapter 的设备状态；**M**＝当前文件/分类/结果；**E**＝编辑草稿。快捷入口引用唯一归属，不能复制一套状态。切换目标只修改 O，不能写入运行中的 T。

| 入口 ID / 用户任务 | 唯一归属与迁移来源 | 前置条件 | 返回与应保留状态 |
|---|---|---|---|
| E-01 找目标、看天区 | 星图根页；原一级星图与赤道仪快捷入口合流 | 浏览不要求任何器材；Stellarium 缺资源时有原生选目标路径 | 根页无 Up；系统 Back 回拍摄起始页。保留 O、视场/显示配置 |
| E-02 搜索天体 | 星图 → 选目标；星图搜索与目标库共用对象 | 离线目录可用；无匹配给换词操作 | 回实际发起页；保留搜索词/滚动及 O |
| E-03 目标库、今夜推荐 | 同一个选目标流程；原赤道仪/手机列表迁入 | 推荐需地点/时间；未知时提示补齐，不阻断全目录搜索 | 选中后回发起页/同一目标卡；T 不变 |
| E-04 目标卡与详情 | 星图当前目标；拍摄目标名称可打开同一目标选择 | 已选目录或星图对象 | 先关详情/卡，保留 O；空白关闭不清空目标，也不启动动作 |
| E-05 推镜引导 | 星图 → 目标任务；原赤道仪推镜迁入 | 选目标；真实指向关系/有效性由后续 WF 负责 | 回原星图/目标选择；保留任务快照。临时开设备不丢目标；不假宣告到位 |
| E-06 普通 GOTO | 同一目标任务，器材页提供就近入口 | 架台支持 GOTO 且没有冲突运动；明确将移动 | 确认关闭回当前目标；运行后返回不取消运动。T 固定，全局 STOP 可达 |
| E-07 指向并居中 | 同一目标任务；复用现有精确 GOTO | 合格参考相机/光路、GOTO 及相关能力 | 回发起页，保留进度/失败原因；机械完成与光学校验到位分开 |
| E-08 同步赤道仪 | 当前目标的次要操作或赤道仪完整详情 | SYNC 能力、用户选定参考；不能只看“已连接” | 完成后留当前任务；明确写入控制器，与手机标定区分 |
| E-09 星图图层/显示/时间 | 星图就地面板 | 无硬件前置 | 系统 Back/空白/关闭退最上层，保留 O 和画布位置；内部点击不穿透 |
| E-10 视场/光路/位置角/马赛克 | 星图观测面板 → 对应参数；器材维护相同光路 | 主/辅助配置独立；当前框需要可信来源，否则标构图 | 面板→星图；保留 O/草稿，当前框和目标框不能混淆。科学来源扩展随 WF-09 |
| E-11 观测准备 | 星图的完整子页；拍摄可就近进入 | 按任务列条件，无架台可浏览 | 回实际来源；保留 O、配置与已知条件；不用“工具”总页绕行 |
| E-12 极轴校准 | 观测准备 → 极轴校准；原赤道仪/工具入口迁入 | 赤道式任务；按现有流程需要地点、可解析图像/相机。手动 Dob 不强制 | 子页→准备→发起工作区。活动运动 STOP 可达；步骤/参数/结果不无声丢失 |
| E-13 手机与镜筒指向校准 | 观测准备 → 指向校准；引导缺条件时引用 | 刚性安装、主镜目镜或参考相机路径；不要求同轴/电控架台 | 上一步/实际来源，草稿保留；当前批明确尚未形成真实标定，后续 WF-06 |
| E-14 拍照/录像/预览 | 拍摄根页，起始工作区保持拍摄 | 对应相机/格式/采集所有权可用 | 根页无 Up；切页不暗中启动/断开采集。T、D、已写素材按原协调规则保留 |
| E-15 对焦辅助/放大/中心标记 | 拍摄预览的就地控制 | 有图像；机械调焦另由调焦器控制 | Back 先退放大/信息层；保留预览参数。主动对焦与 EAA 的暂停规则不绕过 |
| E-16 EAA / 实时叠加 | 拍摄 → EAA；原工具台与预览快捷入口合流 | 满足现有 `liveStackStartIssue()`；可选校准帧，不强制 BIAS/DARK/FLAT | 回原拍摄/目标流程。保留结果与处理参数；离开页面的运行政策依现有协调器，提供恢复入口 |
| E-17 EAA 大图观测 | EAA 内的显示模式 | 当前单帧/累积预览可用 | Back 先退出大图，再离开任务；T/图像/参数不因关模式重置 |
| E-18 导星 | 拍摄准备/任务；原工具与状态快捷入口合流 | 指定导星相机/架台能力，采集不能与主镜争用 | 回实际来源。关闭页面与明确停止导星区分，D 与运行状态一致 |
| E-19 单张解析与离线星表 | 影像 → 图像解析；已打开图像带入当前文件，拍摄“更多”、观测准备“解析与离线库”就近进入 | D20/D50 选择和下载不需要连接相机；明确图像来源与光路，手机宽场与 ASTAP 适用范围按原路由 | 回实际影像/拍摄/观测准备来源；取消文件选择不退出任务。补充复核见 [发布版入口审查](FEATURE_ENTRY_AUDIT.md) |
| E-20 最近拍摄/影像库 | 影像根页；原工具文件浏览迁入 | 自动读取共享 MediaLibrary，不要求用户知道路径 | 根页无 Up；系统 Back 回拍摄。保留分类/滚动，不修改采集暂停标记 |
| E-21 照片/录像/叠加结果分类 | 同一影像库的筛选，属于内容分类 | 支持格式；正在写的文件仍排除 | 筛选不新增页面层；保留 M，空状态可拍摄/导入 |
| E-22 查看照片/FITS/PNG | 影像 → 当前文件；保存后直接查看引用此目的地 | 文件存在且可读；格式/处理尺寸按既有科学边界 | 普通库打开回列表；保存后直接打开回原任务。文件选择/旋转不丢来源 |
| E-23 SER/PSER 播放/逐帧 | 影像 → 当前录像 | 使用有界预览读取；正在写的源不可打开 | 先关信息/放大，再回列表/实际来源；停止播放器资源不停止相机采集 |
| E-24 MP4 播放 | 同一录像动作，使用平台外部播放器 | 临时只读 FileProvider URI；无播放器有明确提示 | 系统遵循外部任务栈；回应用恢复原文件列表；不请求写权限 |
| E-25 行星后处理 | 影像 → 录像 → 后处理；原工具行星入口迁入 | 受支持且完成写入的科学录像；选择帧/ROI/质量分析条件有效 | 回源文件/列表，保留源与处理结果；只读原文件不额外复制 |
| E-26 我的录像/外部导入 | 后处理任务内的素材选择，影像提供次要导入 | 素材库自动归集；外部使用系统选择器，取消有回路 | 关闭 picker 回当前任务；参数/源/结果保留，换源需符合既有重置规则 |
| E-27 保存/查看/导出/分享 | 当前图像任务的结果动作，结果统一归影像 | 有真实结果且任务不忙；科学 FITS/PNG/参数可重现 | 保存留原工作区，1 次点按查看；分享使用只读 URI，返回保留 T/M |
| E-28 GC / DBE / 选点放大 | 当前图像处理 → 背景调整；默认自动 GC | 适用处理状态/素材；手动点位按有效规则 | Back 先关放大/编辑层，回处理工作区；草稿/明确应用规则统一，不让卡片隐藏丢采样 |
| E-29 去马赛克/高级参数 | 叠加/图像任务的高级设置 | Bayer/数据布局兼容；双线性、VNG、DDFAPD 沿用既有科学实现 | 展开组不是新一级入口；改变算法按既有重置确认，保留用户当前值 |
| E-30 校准帧库/拍摄引导 | 使用校准帧的叠加任务 → 校准帧 | BIAS/DARK/FLAT 全部可省略；选用时兼容条件有效 | 关闭回原叠加；拍摄引导按步骤返回，写入/取消清理保留原规则；与手机标定命名区分 |
| E-31 器材连接/设备详情 | 器材清单 → 当前设备；替代整排 DeviceTab | 各设备独立；连接失败可重试/取消；多设备身份可辨 | 详情回清单或实际来源，D 复用；不重复全设备二级导航 |
| E-32 顶部相机/架台/滤镜轮/调焦器状态 | 对应单设备快捷面板；完整能力仍归器材详情 | 已连接直接控制，未连接仅当前类型选择/连接 | 1 次打开、关闭回原工作区，O/T/M 不变；当前设备身份明确 |
| E-33 调焦/手动方向按住移动 | 当前设备控制组件，快捷与完整详情复用 | 对应能力；运动互斥 | 松手、取消、失焦/控件销毁停止本次按住动作；关面板不冒充全局 STOP |
| E-34 平场盖/电源/旋转器控制与设置 | 器材 → 该设备详情 | 实际协议/型号能力，未知不假启用 | 回器材/来源；副作用动作明确。继承设备安全与冻结 SDK 规则 |
| E-35 光路/镜筒/目镜配置 | 器材光路；星图配置复用同一记录 | 已有信息带入，不要求新手理解矩阵 | 有草稿的编辑明确保存/放弃，回发起处；移动支架/换镜头的真实失效随 WF-05/06 |
| E-36 全局设置/红光/更新/诊断 | 全局入口；设备专属设置可从详情进入 | 一般无设备前置，更新状态有取消/恢复 | 设置回来源，红光不切页；日志分享/安装按平台边界，取消不退出应用 |
| E-37 删除/回收站/恢复 | 影像任务动作 | 删除有真实后果确认；在原素材库内恢复 | Back 先回普通列表；文件详情返回清楚，不能根页误退出；恢复能在对应分类找回 |
| E-38 序列编辑/运行/历史 | 开发构建中拍摄高级任务 | `SequenceFeature.ENABLED`；发布包隐藏；运行动作采用既有协调规则 | 草稿/步骤/面板逐层，运行可恢复/STOP。独立编辑器不以改导航为由全量重写 |
| E-39 手机相机诊断/实验解算 | 开发/诊断入口 | 权限和机型能力；明确实验状态 | 回实际来源；不向正常新手入口暴露虚假残差/有效标定 |
| E-40 外部打开文件/链接 | 若以后注册，仍进入影像对应目的地 | `content:` 权限与实际入口声明 | Back 可回外部调用者，Up 留应用合理父页。基线未注册通用文件接收，不声称已支持 |

最后一项是平台边界验收，基线 [MainActivity](../app/src/main/java/com/indigo/mobileobservatory/MainActivity.kt) 的 `onNewIntent()`仅记录日志；[Manifest](../app/src/main/AndroidManifest.xml)没有通用影像 `ACTION_VIEW` 入口。第一批不可用一段人为注入 URI 的单元测试冒充外部文件流程验收。

## 3. 全应用交互巡检：改造前缺口

本表是 WF-12 的静态基线；关闭缺口需要实际行为证据。已有能力应保留，不能把迁移入口当成重新实现算法。

| 缺口 | IG / 场景 | 静态证据与影响 | 本批处理/剩余验收 |
|---|---|---|---|
| A-01 一级分类与用户任务不匹配 | IG-01/03，UX-01/09/11 | 五标签中目标/推镜在赤道仪、EAA/行星/影像在工具；新手要记模块 | WF-01 四工作区与唯一目的地；真人首次点击单列 |
| A-02 返回由分散布尔值决定 | IG-04/06，UX-03/05 | 基线仅 StarMap 与 Stacking 注册显式 BackHandler，多个完整页只有工具栏回调 | WF-03 壳层栈；系统返回、取消边缘手势、旋转另测 |
| A-03 星图根页伪上级 | IG-04，UX-03 | 星图工具栏/Back 回调固定到赤道仪 | 根页去伪 Up；非起始根回拍摄，子页回实际来源 |
| A-04 卡片可见依赖目标非空 | IG-05/06，UX-04 | target 非空且无角落面板就显示，缺少独立主动关闭状态 | WF-04 分离目标与可见性；空白短按、拖动/缩放、刷新不重弹 |
| A-05 状态带造成器材绕行 | IG-03/06，UX-02/12 | 滤镜轮/调焦器统一路由 ACCESSORIES；页内再选 DeviceTab | WF-02 快捷控制/定向连接；按住停止和同类型身份另测 |
| A-06 无 Stellarium 仅缺资源提示 | IG-07/08，UX-01/10 | 星图 fallback 缺选目标/推镜/准备的原生入口 | WF-01 原生可用路径，资源可用/不可用两构建覆盖 |
| A-07 候选目标与手机目标分离 | IG-06/08，UX-05/09 | PhonePlateSolveNav 独立 RA/Dec；星图目标经序列回调 | 单一候选目标，运行任务目标固定；真实指向来源契约后续 WF-05 |
| A-08 EAA 大图 Back 越级 | IG-04/06，UX-13 | StackingScreen Back 直接 onBack，observingOnly 独立于返回回调 | 大图先退出模式，编辑/ROI/选图逐层；保留结果/参数 |
| A-09 文件库系统返回缺完整层级 | IG-04，UX-03/13 | PlayerScreen 主要靠子视图工具栏，列表/文件/解析/垃圾箱的 Back 分散 | 媒体根、详情、解析、垃圾箱各自返回顺序；direct result 回任务 |
| A-10 库分类暴露格式多于任务 | IG-01/09，UX-09/11 | 格式分类与“找照片/录像”心智不一致，已有正确 MediaLibrary | 根页最近/照片/录像/结果；格式细节展开，旧读回/恢复回归 |
| A-11 缺条件的恢复不统一 | IG-07/08，UX-13 | EAA 已有就地恢复按钮；其他页连接/不可用的表达各自实现 | 复用清楚状态和具体恢复动作，避免灰按钮死路；设备任务逐项验收 |
| A-12 编辑保存模型混杂 | IG-01/03/04/06/09，UX-13 | 全局 SettingsSection 仍含设备分页，即改即生效行和文本/按钮保存行混用，DBE/光路/序列各自草稿 | 本批保存极轴表单标量、序列数值未提交文本、旋转器步数草稿；设备专属默认值归入器材详情、CameraDefaults/FocuserDefaults/CoverDefaults 由异步加载覆盖复杂草稿的问题仍待完整保存/失效契约，不宣称全编辑器完成 |
| A-13 运行任务与顶部空间 | IG-02/03/10，UX-10 | 全局 STOP/EAA/序列状态各有实现，浮层与横竖屏需实际确认 | 保留运行摘要和恢复；STOP、手动取消、小屏/红光/大字体持续验收 |
| A-14 真标定能力尚未闭环 | IG-08，UX-06–08 | 演示残差/单方向对齐不能证明手机与镜筒关系 | 第一批入口诚实说明；不得仅迁移向导就宣告已校准；真实闭环后续 |

每个受影响任务都检查空/缺条件、就绪、忙、失败、完成、暂不可精确判断六类状态。任务忙时显示真实阶段/取消或 STOP；失败保留参数与源，提供重试；保存完成给查看；空库给拍摄/导入；未知硬件能力不给有副作用动作。按钮按下后能力变化不得把原动作换成另一项命令。

## 4. 可走通的本地原型

[打开四工作区原型](prototypes/observing-workflow.html)。这是单个 HTML 文件，离线可打开，无外部字体、脚本或联网请求。按钮、编辑、列表、浮层、步骤、返回和模拟任务都有实际状态变化；刷新重新开始，不代表 Android 生命周期恢复。

默认从拍摄开始；滤镜轮/调焦器模拟已连接，相机/赤道仪未连接。原型外部的“走查工具”可切换条件/失败/空库；新手首次点击测试时保持收起，也不要预先讲解各入口位置。

| 试走任务 | 路径 / 观察点 |
|---|---|
| 找目标与推镜 | 星图 → 搜索目标 → M42 → 设置推镜；无赤道仪也能到达。缺真标定时解释条件，试走按钮不生成有效标定 |
| 连续目视/EAA 流程 | 引导试走 → 停稳 → 模拟验证 → EAA → 连接模拟相机 → 开始 → 加入模拟帧 → 保存 → 查看 → 返回 EAA |
| 按来源返回 | 星图 → 观测准备 → 极轴 → 下一步；模拟 Back 依次退步骤、极轴页、准备页；拍摄发起也回拍摄 |
| 空白关闭 | 选 M42 后点空白关卡，目标仍可恢复；拖动不关；详情返回先回卡，再收卡 |
| 配件就地控制 | 任一工作区点状态滤镜/调焦；面板原地出现，控制或关闭后工作区不变；完整详情有来源 |
| 找回已有素材 | 影像 → 最近/照片/录像/结果 → 当前素材 → 适用处理；后处理 Back 回源素材。空库可前往拍摄/导入 |
| 结果、编辑与大图 | 保存后 1 次点查看；Back 回原任务。光路未保存返回显示继续/放弃；大图先退模式 |
| 固定任务目标 | 运行 M42 引导时浏览 M31，候选更新但任务仍是 M42；切工作区有恢复入口 |
| 停止与异常恢复 | 模拟 GOTO 有持续 STOP，设备面板打开仍可停止；调焦按住松手/取消停止；任务模拟失败后可重试 |

原型只演示交互，所有星图位置、帧数、图片和状态明确为模拟。没有科学残差、到位角距、硬件调用、真实文件读写或控制器同步。浏览器 Escape/按钮用于试走层级，不能替代 Android 输入法、三键/预测性边缘返回。

原型自动化走查使用系统 Chrome headless / Playwright，**18 条实际 DOM 操作全部通过**：四工作区、无架台到达引导、空白/拖动、极轴步骤/来源、滤镜就地控制、连接后继续 EAA、设备完整详情来源、保存后返回且亮度保留、大图返回、录像后处理来源、四工作区设置返回、未保存编辑、任务目标固定/恢复、失败重试、STOP、调焦松手/关闭停止、空库恢复、键盘关闭后焦点回入口。没有 JS 异常或外部联网请求；另检查 390×1000 红光界面无整页水平溢出。

证据在本地产物 `build/reports/wf/prototype-verification.json`、`prototype-desktop.png`、`prototype-mobile-red.png`、`prototype-mobile-device.png`。这些是原型行为证据，不是 Android 代码验收，也不是新手测试。可复跑脚本为 `scripts/tests/wf-prototype.test.cjs`（Node + Playwright，默认系统 Chrome，可用 `CHROME_PATH` 指定浏览器）。

## 5. Android 环境与测试策略

本轮检查时，SDK 路径由 `local.properties` 指向 `D:/Project_SEDIRK/.android-toolchain/sdk`，JDK17 位于同目录 `jdk17/jdk-17.0.20.1+1`，API34 x86_64 system image 已安装。自定义 AVD 路径 `D:/Project_SEDIRK/.android-toolchain/avd` 中有 `indigo-stacking-api34`；已用隐藏窗口/无界面方式启动 `emulator-5560`，`sys.boot_completed=1`。没有连接真实 Android 或 USB 器材。

用 `ANDROID_AVD_HOME` / `ANDROID_USER_HOME` 指向该项目工具链目录后可发现 AVD；默认个人 Android 目录没有列出它。这个机器路径是本次执行记录，不是仓库的新运行依赖。

已有 Android 测试：[AccessibilityFlowsTest](../app/src/androidTest/java/com/indigo/mobileobservatory/ui/AccessibilityFlowsTest.kt)、[StackingUsabilityInstrumentedTest](../app/src/androidTest/java/com/indigo/mobileobservatory/ui/StackingUsabilityInstrumentedTest.kt)、[MediaLibraryInstrumentedTest](../app/src/androidTest/java/com/indigo/mobileobservatory/ui/MediaLibraryInstrumentedTest.kt)、播放器/校准/叠加回归。它们能证明局部能力；[ObservingUiWiringTest](../app/src/test/java/com/indigo/mobileobservatory/ui/ObservingUiWiringTest.kt)的源码字符串检查不能证明系统返回。新增第一批壳层测试应使用真实 `CameraScreen(vm)` 与 Activity Back dispatcher，验证页间行为而非重复导航数据类实现。

本轮已新增 [ObservatoryWorkflowInstrumentedTest](../app/src/androidTest/java/com/indigo/mobileobservatory/ui/ObservatoryWorkflowInstrumentedTest.kt) 的 11 个行为场景：四工作区与根页；准备/极轴逐层；设置覆盖极轴未提交字段保持；四工作区设置来源；`StateRestorationTester` 恢复设置顶层及栈下极轴草稿；真实方向配置变化保留极轴草稿；滤镜轮/调焦器定向 sheet；媒体根/垃圾箱；设置覆盖 EAA 详情组仍展开；两种星图构建的目录→实验推镜；红光 + 200% 字体的准备/设备 sheet 实际可操作。编译和模拟器结果由本轮统一测试追加，测试文件已写入不等于已经通过。

测试宿主使用 [debug-only WorkflowTestActivity](../app/src/debug/java/com/indigo/mobileobservatory/ui/WorkflowTestActivity.kt) 与 [debug Manifest](../app/src/debug/AndroidManifest.xml)，配置变化策略与真实 MainActivity 相同；发布构建不包含该宿主，没有 launcher 或外部导出入口。测试允许真实横竖屏改变，没有用锁定测试方向掩盖导航重建问题。

极轴表单的字符串、布尔选项、传感器选择和文件槽选择共 18 项已改为 `rememberSaveable`。正在运行的 Job/运动、已解析 WCS/误差结果、图像和文件对象未写入 Bundle；进程恢复只能保留表单和导航，必须重新获得有效采样后才有科学结果。`StateRestorationTester` 证明 Compose 的保存/恢复契约，不等于验证所有设备状态在真正杀进程后都能恢复。

验证分层：

1. JVM：返回栈序列化/选择根页规则及领域数据行为；不代替 UI。
2. Compose instrumentation：四工作区、准备/极轴层级、设置来源、设备 sheet、媒体根/回收站、已有叠加与文件读回回归。
3. Android 系统：三键、边缘返回完成/取消、输入法、横竖屏、200% 字体、红光、无 Stellarium 构建、STOP/按住释放；ADB dispatch 结果与实际手势分别记录。
4. 真机/器材与新手：设备控制身份、物理运动、USB 断连、真实采集状态，以及至少两名未参与开发者的首次找入口与完整任务。

正常 ARM64 构建仍用 `./Build.ps1 -NonCommercial`。本轮模拟器用 Gradle 显式组合参数，**没有使用会强制打开序列的 `Build.ps1 -EmulatorTest`**：

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest -PstellariumNonCommercial=true -PemulatorTest=true -PsequenceEnabled=false
.\gradlew.bat assembleDebug assembleDebugAndroidTest -PstellariumNonCommercial=false -PemulatorTest=true -PsequenceEnabled=false
```

安装两份输出到 `emulator-5560` 后，仅对隔离的 `.emulatortest` 包授予相机/定位权限，使用 `am instrument -w -r -e class com.indigo.mobileobservatory.ui.ObservatoryWorkflowInstrumentedTest com.indigo.mobileobservatory.emulatortest.test/androidx.test.runner.AndroidJUnitRunner` 运行壳层行为。权限提前授予只用于避免系统授权弹窗遮挡该组导航断言；拒绝权限路径、真实系统边缘返回和发布 APK 仍须各自验证。

Stellarium 构建中的 11 项壳行为已通过；无 Stellarium 构建以相同 11 项独立验证，**11/11 通过，27.739 秒**。原生构建安装保留模拟器原数据；使用与原测试包相同的默认 debug 签名。构建与测试日志分别为本机 `D:/Project_SEDIRK/.android-toolchain/wf-native-build.log`、`wf-native-ui-tests.log`。这些运行均设置 `sequenceEnabled=false`，没有用带开发序列入口的 APK 冒充默认发布布局。

恢复含 Stellarium 构建后，[StarMapPanelInteractionTest](../app/src/androidTest/java/com/indigo/mobileobservatory/ui/StarMapPanelInteractionTest.kt) **3/3 通过，7.023 秒**：面板 padding 消费点击、真实 StarMap 的 STOP 控件、其他非关键控件超时后仍能打开目标库/观测准备。最后一项的计时依赖 Compose 测试时钟；修正测试推进时钟后重跑整组通过，没有改生产传感器或科学算法来满足测试。

两构建测试汇总在 `build/reports/wf/android-variant-verification.json`，日志副本在同目录 `native-build.log`、`native-ui-tests.log`、`stellarium-retest-build.log`、`stellarium-panel-retest.log`。含 Stellarium 全组最终回归与本轮临时插入的相机颜色/CFA 修复由统一验证报告补充；此处只声明已实际运行的范围。

### 2026-10-14 最终组合回归

本批追加的 [相机色彩与 SER CFA](CAMERA_COLOR_AND_SER_CFA.md) 已合入同一工作树。最终运行全量 JVM **753 项通过、0 失败/错误**；含 Stellarium、`sequenceEnabled=false` 的相关 Android 回归 **42 项通过，80.787 秒**。42 项覆盖上面的 WF 入口/返回、真实布局/状态恢复、星图面板和 STOP、预览缩放、SER 播放→后处理→返回继续播放，以及新增 3536² 色彩数值、AWB、预览工作线程生命周期、CFA/算法/导出/校准兼容性。无 Stellarium 的 11 项壳回归是单独构建验证，不与 42 项混为一个构建。

JavaScript 星图交互 **9 项通过**；离线原型实际 DOM **18 项通过**。新加的未知 CFA 外部 URI 恢复、负标记拒绝与显式 LP750 两阶段数值参考也在最终回归内。旧 LP750 测试漏算既有背景白平衡，已补完整参考，保留逐通道误差≤1，未放宽阈值。

标准 ARM64 测试 APK 已按 `./Build.ps1 -NonCommercial` 构建成功：`bin/Installer/IndigoObservatory_android.apk`，包含 Stellarium，`sequenceEnabled=false`、`emulatorTest=false`。签名/构建信息与 SHA-256 随包由脚本生成；本轮未发布 GitHub Release。

组合结果：`build/reports/wf/delivery-verification.json`；最终 Android 日志 `build/reports/wf/wf-delivery-ui.log`；Gradle JVM XML 在 `app/build/test-results/testDebugUnitTest/`。设备物理运动、真实 AE676C 色卡和真人首次点击仍没有现场证据，不能由这些自动化代替。

## 6. 第一批验收状态与剩余工作

本节按证据增补，不能把整行 UX 场景因为部分自动化通过就勾选。

| 场景 | 第零批原型 | 第一批 Android / 代码证据 | 真人/真机与依赖 |
|---|---|---|---|
| UX-01 无架台发现推镜 | 路径走查通过，缺真标定说明可见 | 两种星图构建的目标库→实验引导通过 | 真实手机/镜筒方向后续 WF-05–07 |
| UX-02 设备就地控制 | 滤镜/调焦/连接原地返回通过 | 未连接滤镜轮/调焦器定向 sheet 与原影像工作区保持通过 | 多设备身份、已连接物理控制/断连真机待测 |
| UX-03 逐层返回 | 子页/步骤/设置/影像来源通过 | Back dispatcher 的准备/极轴、设置来源、媒体根/垃圾箱通过 | 实际边缘完成/取消、输入法、外部任务边界另验 |
| UX-04 空白与手势 | 短按关卡、保留目标、拖动不关通过 | Android 面板 padding、真实 StarMap STOP/入口保持 3 项通过；目标卡/空白桥接的完整链路仍须实际手势验收 | 真机双指缩放、边缘冲突待测 |
| UX-05 状态保持 | 目标固定、临时设备与任务恢复通过 | 设置覆盖极轴/叠加、真实方向变化、保存状态恢复的表单/返回栈通过 | USB 断连、实际杀进程后的设备/科学任务恢复另验 |
| UX-06–08 真标定、到位、来源 | 不提供真实科学结果 | 本批不声称完成 | WF-05–09 依赖待完成 |
| UX-09 找回结果/处理 | 保存查看、影像找回/素材返回通过 | 壳层媒体返回已通过，已有文件/叠加回归按本轮最终报告追加 | 真人完整观测→保存→找回待测 |
| UX-10 布局/红光/STOP/fallback | 390px 红光无水平溢出，STOP 面板上可达 | 红光+200%字体准备/定向 sheet、模拟运动 STOP 在真实 StarMap 上可操作、无 Stellarium 的同 11 项通过 | 实机触控/真实运动 STOP/红光观察待测 |
| UX-11 首次发现入口 | 原型可测试，**未进行真人首次点击** | 实现入口可达不等于易发现 | 至少 2 名新手，按下一节记录 |
| UX-12 路径预算 | 已连接控制 1 次；选目标后引导 1 次；准备极轴 2 次；保存查看 1 次 | 设备状态一次打开 sheet，星图→准备→极轴两次点按已实测；保存查看依现有回归 | 真人绕行/求助需记录；必要物理步骤不计点按预算 |
| UX-13 空/忙/失败/编辑/大图 | 空库恢复、失败重试、编辑选择、大图先退通过 | 极轴未提交字段、EAA 详情展开、媒体垃圾箱/根页返回已通过；其他按最终报告追加 | 真实 SDK/计算失败与 Android 生命周期另验 |

WF-00 的清单与原型交付完成，首次点击验收待真人；WF-12 静态巡检完成，迁移修复与每条剩余验收按本轮代码结果更新。不要把“有原型”或“已接入共享壳”写成“全应用交互已经通过”。

## 7. 新手首次点击与任务记录模板

参与者至少 2 名，未参与本项目设计/开发；记录熟悉手机程度、天文经验，不提前讲入口。先做找入口，再按能力成熟程度做完整任务。只用自然任务句，不说“去星图/影像页”。可以让参与者说出理由；卡住时记录后再帮助，不删除失败记录。

**首次点击任务卡：**“想把望远镜手动指向 M42，你会从哪里开始？”“想稍微调焦/切滤镜，你会在哪里找？”“想检查赤道仪极轴，你会在哪里找？”“想找回刚才拍的录像并做后处理，你会在哪里找？”“刚保存的 EAA 图片在哪里，怎样再回观测？”

| 参与者/日期/版本 | 任务 | 首次选择 | 成功目的地 | 点按/绕行 | 求助点/原话 | 意外退出/丢状态 | 结论/修复 ID |
|---|---|---|---|---|---|---|---|
| 待测试者 A | 待执行 | 未记录 | 未记录 | 未记录 | 未记录 | 未记录 | 未验收 |
| 待测试者 B | 待执行 | 未记录 | 未记录 | 未记录 | 未记录 | 未记录 | 未验收 |

**完整任务记录：**手机/Android 版本、器材/模式、软件提交、构建是否含 Stellarium、字体/红光/方向、起点、每一步、预期/实际返回、任务状态保持、截图/视频/日志位置。观察是否找得到、是否需要解释、是否误入器材、是否意外退出，以及保存结果能否找回；小样本只用于形成性改进，不宣称总体成功率。

**阻断缺陷：**错误设备控制、隐藏 STOP、意外退出导致未保存丢失、运行目标被浏览改写、未经真实验证宣告校准或到位。发现后先修，再扩大入口迁移。
