# 导星与 PHD2 源码对齐（2026-10-27）

上游：<https://github.com/OpenPHDGuiding/phd2>，固定提交
`70e9fd178b3122b6553646c60f462cb09335dcc5`。本轮从已合并 PR #26、#27 的
`a8f20a9` 开始，覆盖校准状态机、运动/曝光时序和全部七种导星算法选项。

## 校准

依据上游 `src/scope.cpp`、`src/scope.h` 与 `src/calstep_dialog.cpp`。

- RA 向西采样、向东返回；DEC 向北消隙、向北采样、向南返回及有限南向微调。
- 校准脉冲固定。步数表示计算脉冲时的期望值，正式采样达到位移目标即结束；
  最多 60 步。新配置使用期望 12 步和目标 25 px，已有持久化设置保留。
- 已知光学尺度时按恒星时速率 15 arcsec/s、用户核对的导星倍率和赤纬计算脉冲；
  赤纬计算限于 ±60°，脉冲向上取整到 50 ms，同时保留至少四个 DEC 步长。
  RA/DEC 使用相同脉冲。相机或焦距未知时仍使用备用脉冲。
- 响应向量按向外累计位移 / 累计脉冲时间计算。额外记录多点回归残差用于诊断。
- RA 按向外累计脉冲时间返回；DEC 按正式采样累计时间返回，随后允许有限南向微调。
  DEC 消隙的最后一次有效移动复用为北向首个样本。消隙门限采用 RA 响应的 60%，
  查找三个同向有效移动；限额为 `max(8, 60000 / pulseMs)`，达到限额但移动 ≥3 px
  时允许带提示继续。
- 快速返回单次预计位移上限为 RA 20 px、DEC 16 px，受 SDK 5000 ms 限制。
  南向微调使用 2 px 目标、40° 方向条件，每步最多为原校准脉冲。
  上游 `counter <= MAX_NUDGES` 从零开始，实际最多四次，本实现保持该计数边界。
- 移除二维 0.75 px 硬性回位、返回时追逐原点和 0.8–1.25 倍反向速率硬性失败。
  返回偏移、弱反向响应、少步数、轴不垂直和拟合波动成为结果提示，日志保留数值。
  无有效星、星点失配、位移不足、无有效速率、退化矩阵及设备错误仍使校准失败。
- 返回后的实际星点作为闭环锁定位置。自然漂移可以留下位置残差。

当前仍保留可关闭的 DEC 回差估计扩展。仅在反向响应可信时使用；不确定时归零并提示。
这与 PHD2 Guiding Assistant 的专门回差测量及 `backlash_comp.cpp` 自适应补偿有区别。

## 曝光与脉冲

依据 `src/worker_thread.cpp`、`src/myframe_events.cpp`、`src/scope_ascom.cpp` 与
`src/cam_zwo.cpp`。校准和正常导星共用顺序：完整测量 → 相机停采确认 →
RA/DEC 脉冲及物理停止清理 → 相机恢复采集 → 新帧测量。

`GuideAcquisition.withMotion` 与相机参数写入共用互斥锁，在 IO 线程停采并检查
`Camera.isCaptureStopped`，覆盖 SDK 状态和采集线程退出。相机更换或停止曝光后不恢复旧会话。
取消时先完成脉冲停止，再按用户曝光请求恢复预览；停止失败阻止后续运动。

Android 各厂商适配器的连续采集入口继续保留，每次运动前停止，运动后重启。
重启使用原有启动队列清理和采集代次隔离。ZWO Android 尚未接入 PHD2 USB3 的
`ASIStartExposure` 单帧路径，因此一次周期可能包含额外启动/弃帧开销。
本轮没有变更任何厂商 SDK 或冻结的预编译库。

每张合格新帧至多触发一次修正，移除原来独立的 500–2500 ms 修正间隔门槛。
主相机采集保持独立。硬件真实运动、SDK 停采延迟和 USB 并发需现场验收。

## 导星算法

所有滤波器以校准轴上的导星像素运算，之后才换算脉冲。RA/DEC 的算法、MinMove、
修正强度及滤波参数独立，默认 RA Hysteresis、DEC ResistSwitch，新配置 MinMove 0.2 px。
已有 RA 算法与共享 MinMove 保存值保留；DEC 初次加载继承已有 MinMove。

| 选项 | 对齐来源及行为 |
|---|---|
| Identity | `guide_algorithm_identity.cpp`，直接返回输入 |
| Hysteresis | `guide_algorithm_hysteresis.cpp`，`aggression * (0.9 * input + 0.1 * lastOutput)`，MinMove 后更新输出历史 |
| Lowpass | `guide_algorithm_lowpass.cpp`，10 点零填充历史；11 点中位数、移除最旧点后拟合斜率，默认斜率权重 5 |
| Lowpass2 | `guide_algorithm_lowpass2.cpp`，10 点历史、四点预热、斜率输出、离群重置和连续拒绝重置 |
| ResistSwitch | `guide_algorithm_resistswitch.cpp`，10 点方向历史、三票与趋势确认、快速换向、反向过冲否决 |
| Z-filter | `guide_algorithm_zfilter.cpp` 的 IIR 运算；原版 `zfilterfactory` 生成四阶 Bessel/Butterworth 系数，默认曝光系数 2 |
| Predictive PEC | 直接编译上游 `GaussianProcessGuider`、协方差函数、GP 和 FFT 工具；输入真实 SNR、曝光时间及单调时钟 |

PPEC 保留上游默认周期 200 s、预测权重 0.5、推断/周期估计至少两个周期、100 个近似点及
完整核参数。反应权重按所选算法默认值初始化：Hysteresis 0.7、Lowpass2 0.8、
ResistSwitch 1.0、PPEC 0.6，并逐算法保存用户修正强度。
PPEC 仅用于 RA；反应项受 MinMove 限制，已经学习到的预测项继续生效。
Lowpass、Z-filter、Identity 不使用修正强度，界面相应控件禁用。

脉冲基础上限对齐 2500 ms，DEC 回差附加量保留 Android 当前 2000 ms 上限，
总时长不超过 SDK 5000 ms。方向反转和实际命令接受后的方向记录继续保留。

## 彩色输入与星体测量（2026-10-27 追加）

已对齐 `image_math.cpp::QuickLRecon` 和 `star.cpp::Star::Find(FIND_CENTROID)`：
同尺寸滑动 2×2 亮度重建、7–12 px 环形背景、迭代 2σ 剔除、半径 7 px 孔径质心、
总通量 SNR 与 HFD。跟踪 SNR 默认 3，自动选星默认 6，优先低于 90% 饱和值的有效星。
已选星单独执行局部测量；完整全图候选策略继续使用 Android 入口。

公开坐标补回重建窗口中心的半像素偏移；保留原始通道饱和检查与原始 PSF/HFR 诊断。
QLR 按需计算像素，RAW 保守预筛选减少天空背景区域的重建；局部测量复用小块缓存。
默认搜索半径下缓存约 3.8 KiB，RAW 筛选另用两行布尔数组，SDK 已确认的合并单色帧直接进入测量。
原版 C++ 生成的 82 组参考、位相扫描前后对比和 IMX585 七算法回放见
[彩色导星验证](validation/guiding-color/README.md)。

## 验证方法

通量历史与异常帧：已对齐 `guider_multistar.cpp::MassChecker` 的 45 秒窗口、五样本预热、
默认 50% 容差及高/低水位判断。短暂丢帧保留滤波器与 PPEC 模型，主星锚点维持上一次
认可的位置；当前帧有效主星是正常修正的必要条件。Android 环形数组与选择算法降低分配，
使用单调时钟管理窗口。1,786 组原版 C++ 参考和原始剖面性能验证见
[通量历史与图像诊断](validation/guiding-quality/README.md)。

1. 新增自然漂移和 RA 固定脉冲回归；修改前两个用例均失败。
2. `scripts/Generate-Phd2FilterReference.py` 从固定上游提交提取原始 C++ `result()`
   方法体，使用只替代容器、日志与统计接口的独立程序生成 196 个参考结果。
   覆盖上述六种非 GP 算法、ResistSwitch 快速换向开/关、MinMove 边界和方向变化。
   CSV 和上游文件哈希保存于 `app/src/test/resources/guide/phd2-filter-reference.*`。
3. JVM 测试编译并加载相同 GP/Z-filter C++ 核心，避免以模拟预测器代替生产核心。
   PPEC 使用可注入单调时钟回放多个周期，并验证反应死区内的预测输出。
4. 原 IMX585 星场回放扩展至七种算法，使用相同生产修正求解器，检查亚像素测量和闭环误差。
5. 采集测试覆盖停采后发脉冲、整组命令结束后恢复、采集线程未退出、停止请求及会话更换。
   Android 流程使用真实 ViewModel、采集管线及本机 OnStep 端点，断言曝光与脉冲无重叠。
   `Phd2NativeInstrumentedTest` 执行 APK 内的 PPEC 与 Z-filter 原生库。

开发验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug -PstellariumNonCommercial=true -PsequenceEnabled=false -PemulatorTest=false
```

APK 按安装、实机测试或发布需求另行生成，常规算法修改执行编译和测试。

主机 JVM 原生测试要求 PATH 中有支持 C++11 的 `g++` 和 JDK JNI 头文件；Android 构建使用 NDK。
固定 Eigen 3.4.0，归档 SHA-256 为
`8586084f71f9bde545ee7fa6d00288b264a2b7ac3607b974e54d13e7162c1c72`，启用 `EIGEN_MPL2_ONLY`。

## 保留的差异与现场范围

- PHD2 的持久化校准、过中天/赤纬变换、DEC 单向模式、Guiding Assistant 专门回差测量、
  自适应回差、AO、盲导和暂停后保留 GP 模型尚未整体接入。
- 局部星体测量及主星通量历史已移植；全图候选生成、完整多星匹配、距离恢复、丢星重获
  和导星暗场/坏点图校正继续单列。后续顺序见上述通量与图像诊断记录。
- 已有用户显式设置保留，Android 数值范围、SDK 停采和单次脉冲上限沿用设备约束。
- 自动回放验证数值和调度；实际极轴误差、风扰、机械回差及长曝光星形需要真实器材确认。

许可及对应源码见 `app/src/main/assets/THIRD_PARTY_PHD2.txt` 与
`app/src/main/cpp/phd2/README.md`。PHD2 为 BSD 许可，Eigen 为 MPL-2.0；保留原始文件声明。
