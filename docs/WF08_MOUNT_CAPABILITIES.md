# WF-08：架台能力、只读访问与真实轴读数

2026-10-14。本轮以 [PointingContracts](../app/src/main/java/com/indigo/mobileobservatory/pointing/PointingContracts.kt) 的独立能力、模型代次和轴观测承载现有协议。[MountModule](../app/src/main/java/com/indigo/mobileobservatory/mount/MountModule.kt) 的精确指向流程由主任务维护；本文只说明适配器事实、访问策略和观测边界。

## 1. 能力来自实际接口，不来自型号

`PointingMountCapabilities` 分开描述 readCoordinates、GOTO、SYNC、tracking、manualMove、home、setHome、readSite/writeSite、readTime/writeTime、axisReading、observesReleasedClutch，以及站点是否只在应用配置。连接指示不代替这些字段。

| 当前协议 | SYNC | 轴读取 | 地点/时间来源 | 边界 |
|---|---|---|---|---|
| LX200/OnStep、iOptron V3 | 现有协议写入支持 | 未实现独立轴接口，NONE | 通过现有协议读写 | 不根据 EC 型号名推断绝对码盘 |
| 高层 SynScan/手控器 | 不支持，保持 false | NONE | 通过现有 w/W、h/H 读写 | 已对齐后才能接入；e 是模型天球坐标，不是机械轴 |
| Sky-Watcher 直连电机板/UDP | 通过 E 重写电机位置计数 | j → MOTOR_COUNTER | 地点为应用配置；没有板卡时间读写 | 不能把电机计数叫辅助/绝对编码器，不证明松离合的运动可见 |

其他现有动作仍按实际 session 接口申报；home 与 setHome 各自独立，不用 GOTO 推断归零。高层 SynScan/直连 motor 的 setHomeHere 保存应用的回位参考，不能冒充有机械回零传感器。新增协议或发现命令不支持时修改该协议声明，不能给全部型号强行同一档能力。

AZ-EQ6 硬件辅助编码器的说明不等于当前接口已经读取它；本实现没有新增可读 AUXILIARY_ENCODER 或 ABSOLUTE_ENCODER 路径。所有现有 session 的 `observesReleasedClutch=false`。夹持/离合释放下的手机矫正仍需真实协议与器材证据。

## 2. 统一只读访问

[MountAccessMode](../app/src/main/java/com/indigo/mobileobservatory/mount/MountPointingAccess.kt) 默认 `READ_WRITE`，新 `READ_ONLY` 是软件对已实现协议的访问策略，没有伪造一套只读硬件协议。

- 只读保留实际 readCoordinates/readSite/readTime 和已实现的轴读数；GOTO/SYNC/跟踪/手动/归零/写站点/写时间均关闭。
- [Lx200MountController](../app/src/main/java/com/indigo/mobileobservatory/mount/Lx200MountController.kt) 的动作入口检查权限，最终串口/TCP/UDP 发送处再检查实现过的读命令/安全停止白名单。未知写默认拒绝；已在传输中的命令不能被“撤回”。
- 直连 motor 只读建立 session 时不发 F 初始化写。查询几何和 j 计数仍可用；未初始化板卡拒绝读数时按协议错误处理，不能为“连上”偷偷写初始化。
- STOP 和手动松手始终允许。只读下的 motor 停止清除内部持续跟踪状态，避免松手后又偷偷恢复速度。
- 切只读先阻止新写、取消本软件拥有的运动；如果本软件开启过跟踪，另走明确停跟踪安全命令。只有协议应答成功才标 OFF；未确认时保留原已知状态并显示原因，不把 abortMotion 当成关闭跟踪的证据。

精确指向/GOTO UI 按 capability 禁用并解释条件；按钮隐藏不代替底层拒写。普通 `setTrackingRate(OFF)` 仍属于可写设置，只有专门 `stopTrackingForSafety()` 允许在只读中执行安全停跟踪。

## 3. 本地地点与板卡写入分开

直连 motor 板卡没有站点存储接口，原 `setSite()` 只是更新应用经纬度，用于计数→天球换算。现在声明 `siteUsesLocalConfiguration=true`、`writeSite=false`，不会给用户假“已写入架台”反馈。

`MountModule.setMountLocalSite(latitudeDeg,longitudeDeg)` → `controller.setLocalSiteConfiguration(MountSite)` 只改应用配置，不发送位置命令；只读中也可管理这个本地模型。模型代次随之刷新、旧轴观测清除。真实高层 SynScan/LX200/iOptron 的设备站点同步继续走独立 writeSite capability。

连接期间不能把直连电机的 EQ/AZ 配置静默换成另一套换算；需要断开后用新模式建立 session。重新连接的模型代次与旧连接不同。

## 4. 轴观测与代次隔离

直连 adapter 新 `readCoordinatesWithAxes()` 用同一轮 j1/j2 响应返回模型天球坐标与机械计数角，避免为验证再读一次完整轴数据。角度由 `(count−POSITION_OFFSET)/stepsPerTurn×360` 归一化；分辨率取两轴较粗的一项。

Controller 标记 `SystemClock.elapsedRealtime()` 查询前后中点，生成 `MountAxisObservation`；这不是光学曝光或机内编码器的硬件时间戳，也不证明轴同时采样。用于快速移动时应把串行读取间隔纳入误差。

`MountModelRevision(connection,model,zero,mode)` 与每个轴观测绑定：

| 变化 | 处理 |
|---|---|
| 断开/重连 | 新 connection generation，清旧轴值 |
| SYNC、设备地点/时间、本地地点 | 写入前更新 model，失败/部分写也不能继续使用旧增量 |
| 设置回位/零位参考 | model/zero 更新，清旧轴值 |
| 有效坐标模式改变 | model/mode 改变；实际 direct session 通过重连换模式 |
| 访问策略改变 | 更新模型代次并清旧观测，能力立即降级 |
| 查询过程中代次改变 | 丢弃这次轴观测，不能跨模型拼增量 |

`deltaFrom()` 只接受相同 revision/kind 且时间严格更新的两次样本。MOTOR_COUNTER 能描述受电机控制的轴运动；不能观测离合释放的推镜，也不能把刚 SYNC 后的模型回读当独立光学验证。写回后的效果仍由新的合格参考图像验证。

## 5. 状态接口与手动所有权

Controller 暴露 `accessMode`、`pointingCapabilities`、`modelRevision`、`axisObservation`；Module 暴露 `mountAccessMode` 及其余同名 StateFlow，新增 `setMountAccessMode()`、`setMountLocalSite()`。VM/UI 复用这些事实，协议逻辑留在 adapter/controller。

`MountMotionState.manualDirection` 记录实际被 runner 接受的按住方向。`stopMountManualMove(direction)` 仅释放匹配方向或显式 null 的手动任务；第二个方向启动被拒绝后，其松手不能停止第一个方向，也不能停止 GOTO。全局 STOP 保持独立入口和安全语义。

## 6. 自动化与现场门槛

新增 [MountPointingAccessTest](../app/src/test/java/com/indigo/mobileobservatory/mount/MountPointingAccessTest.kt) 的 8 项 fake 协议/状态测试：只读降级、代次隔离、j 计数/不初始化、controller 全动作拒写与停止放行、SYNC/回位/重连、高层 SynScan unsupported、最终 wire 白名单、手动方向释放隔离。

新增 [MountReadOnlyLoopbackTest](../app/src/test/java/com/indigo/mobileobservatory/mount/MountReadOnlyLoopbackTest.kt) 使用实际 TCP controller 与本机 LX200 fake endpoint，验证只读读 RA/Dec/time、API 拒 GOTO、绕过 facade 后 wire 仍拒手动启动、STOP/确认停跟踪允许。它不连接任何物理器材。

统一 Gradle/Android 执行结果由本轮最终报告补充。真实 OnStep/iOptron/SynScan 读写/停止、直连 motor 分辨率/模式、离合释放、时钟/连接切换与独立光学校验仍需要设备记录；不能用协议 fake 的绿色断言勾选真机或编码器验收。
