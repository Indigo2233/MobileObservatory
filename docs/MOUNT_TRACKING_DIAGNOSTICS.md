# 赤道仪跟踪状态诊断

日期：2026-10-31。版本号与 build 60 保持不变。

## 已复现的问题

本地 TCP 模拟器接入真实 `MountModule`、运动任务与协议控制器：

- 设备连接前已跟踪，应用显示关闭。
- 设备关闭跟踪时执行 GOTO，到达后仍关闭。

修改前两个 Android 回归均失败，记录在 `build/diagnostics/tracking-red-tests.log`。原实现以应用发送的最后一条跟踪命令维护显示值，连接和回零位缺少查询，GOTO 的恢复逻辑依赖该缓存。

## 状态与动作

`MountTrackingState` 分别表达实际启停状态和可识别的跟踪速率；缺少有效信息时返回 `null`。已知正在跟踪、速率为 King 或自定义值时，保留开启状态，速率预设不选中。

连接完成、动作结束和空闲轮询调用适配器查询。动作确认关闭后最多发送一次开启命令，随后最多读取三次；查询重复不产生重复开启。应用发起跟踪的所有权单独保存，包括等待命令应答时切换只读的情形。只读协议白名单允许状态查询与安全停止。

自动三点对极轴在首张曝光前进行检查，RA 移动在开始和停止后检查；GOTO 到达后检查。已支持的查询不可用或开启后仍无法确认跟踪时，动作失败，后续曝光或 RA 移动不会启动。通用 LX200 未提供本项目已实现的启停查询时显示未知，用户仍可显式发送跟踪命令。

自动对极轴的检查也覆盖重新拍摄、解析失败后的重试和连续调整阶段的每次曝光。

## 协议依据

- [OnStep 官方源码 `Command.ino`](https://github.com/hjd1964/OnStep/blob/master/Command.ino)：`:GU#` 中 `n` 表示未跟踪；`:GT#` 提供速率。启停与速率分别解析。
- [iOptron V3.10 官方协议](https://www.ioptron.com/v/ASCOM/RS-232_Command_Language2014V310.pdf)：`:GLS#` 第 18 个数字表示系统状态，1/3/5 表示跟踪或导星，0/6/7 表示停止或停泊；2/4 表示转动中，保留未知。第 19 个数字提供速率。`GLS/GEP/GUT` 消费完整 `#` 结束符，避免连续查询错位。
- [SynScan V3.3 官方协议](https://inter-static.skywatcher.com/downloads/synscanserialcommunicationprotocol_version33.pdf)：`t` 返回单字节跟踪模式，0 关闭、1/2/3 开启；该查询不提供跟踪速率。
- [Sky-Watcher 电机协议](https://inter-static.skywatcher.com/downloads/skywatcher_motor_controller_command_set.pdf)：直接电机模式同时检查 `f` 的运行/速度模式、方向与 `i` 的实际步进周期，避免把 GOTO 或普通手动转动当作天文跟踪。经纬模式需要应用持有的动态跟踪上下文。

## 回归范围

- JVM：协议字段、速率、未知/无效状态、iOptron 连续查询与结束符、SynScan 模式、直接电机状态与周期、只读白名单；同时运行 mount、polar、simulation 现有用例。
- Android：连接已开启/已关闭、GOTO 开启并验证、回零位后关闭、RA 前后检查、虚假开启应答、外部状态变化、只读保留/停止和应答期间切换只读。
- 对极轴实际页面：首张曝光前检查跟踪，检查失败时禁止曝光并显示原因。

所有运动指令均进入本地模拟器。实体赤道仪仍需现场验证各型号的固件回报与实际轴运动。

2026-10-31 最终代码验证：133 项 JVM 用例、24 项 Android 用例及 lint 全部通过。Android 用例包含 13 项跟踪专项、2 项实际对极轴页面用例和 9 项 GOTO/自动校准现有用例。日志分别为 `build/diagnostics/tracking-final-validation.log` 与 `build/diagnostics/tracking-final-android-tests.log`。`Build.ps1 -NonCommercial -EmulatorTest` 构建成功，仅生成模拟器测试包；未更新实体手机或发布正式版本。临时模拟器已关闭，任务生成的磁盘文件已清理。
