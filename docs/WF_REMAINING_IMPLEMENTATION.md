# WF 剩余批次实施与验证账本

日期：2026-10-14。分支 `codex/wf-interaction-phase-0-1`，承接 [第零、第一批验收](WF_PHASE_0_1_ACCEPTANCE.md)。本轮目标仍是 **完成全部 WF，完成后提交 PR**。以下是实施过程记录，不能用“代码存在”代替真机或新手验收。

**当前结论：本轮代码与自动化回归已完成；独立实拍仍为 0/30，S23 Ultra/真实器材及两名新手验收未完成。WF 总目标因此仍未闭合，PR 保留为待现场验收的草稿。**

## 新报告：相机／制冷入口闪退

- 用户设备：Samsung S23 Ultra。当前 adb 仅连接 `emulator-5560`，未取得 S23 Ultra 日志。
- 用完整壳层注入已连接 AE676C + TEC 状态，分别打开相机和制冷，包含横屏、200% 字体及温度输入：修复前 **3/3 复现** `ArrayIndexOutOfBoundsException: length=0; index=-5`，堆栈位于 Compose SlotTable / endGroup / SubcomposeLayout。
- 根因定位：`DeviceDetailContent` 的内联 Compose Column 中 `return@Column` 提前返回。两入口共用该分支；改为完整分支结构并提取配件内容。
- 同三项修复后 **3/3 通过，7.954 秒**。生产改动与测试见 `AccessoriesScreen.kt`、`CameraQuickControlInstrumentedTest.kt`。测试日志位于本机工具链目录 `wf-camera-crash-tests.log`、`wf-camera-crash-retest.log`。S23 Ultra 现场确认仍待进行。

## 当前实施范围和未闭合项

| 项目 | 本轮正在落地的内容 | 仍需证明或完成 |
|---|---|---|
| WF-00/01/02/03/04 | 四工作区独立返回栈、草稿/任务恢复、就地控制；已连接相机分支；真实系统边缘返回/IME及跨应用文件边界通过 | S23 Ultra 与新手首次入口/全流程验收 |
| WF-05 | 原始观测、完整姿态、物理镜头、光路、裁切、奇偶性、时间/版本/误差；配置局部失效与拒绝在途旧结果；来源冲突及协议能力回归通过 | 不同真实设备的时序和协议组合验证 |
| WF-06 | 真实目镜／主相机／辅助相机配对；非同轴关系、独立验证和原子保存；镜面 hand-eye；共享光路配置就地入口与旧草稿失效回归通过 | 多机型夜间实拍；镜面模型必须实测合格，parity 不是外参 |
| WF-07 | 完整姿态与 hand-eye、球面角距、慢推/重试/周日更新；辅助快照及光学运动监测；多帧诊断不能冒充单帧定时验证 | 真机低速／微调／滚转；USB 时间上界较宽时拒绝到位并提示重拍 |
| WF-09 | 有效光路来源/年龄/冲突与跟随；共享观测地点；未知地点、轴或 roll 不冒充当前框；偏心 ROI 使用真实轴与保守验证域；双构建回归通过 | 真实光路/裁切/镜面及夜间现场验证 |
| WF-08 | 新鲜参考/ROI/bin；协议能力与模型版本；自动采样与 SYNC 后光学复验；STOP 完成前保留任务所有权，启动前取消不会挂起调用方；七项真实 TCP 回环通过 | 真实协议设备、运动范围、停止效果与光学精度 |
| WF-10 | 预生成带指纹／校验的宽场索引、按桶加载；解算／资源采样及严格实拍汇总工具 | ≥30 组独立实拍参考 WCS、误解率／镜筒残差／P95／功耗温度／主相机及导星并发证据。合成数据不计入 |
| WF-11 | 自动化逐批扩展，不替代现场记录 | 至少两名未参与开发的新手独立完成整条流程，及全部 UX-01–13 现场验收 |
| WF-12 | 唯一设备设置入口、共享草稿、保存／放弃、旋转／失败恢复、跨工作区守卫与独立返回栈；调焦 STOP 与设零分离；相应壳层回归通过，详见[实现清单](WF12_SETTINGS_IMPLEMENTATION.md) | 真实设备写入/拔插、S23 手势与大字体、新手现场巡检 |
| PR | 保留首批、色彩/CFA与后续 WF 的完整整合，提交草稿供审查和现场验证 | 不得把未获得的现场证据标完成，也不自动合并/发布 |

## 科学数据边界

- 手机 Camera2 只有声明 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` 才与 `elapsedRealtime` 对齐；UNKNOWN 时间戳可解算图像，但不作为已定时的校准／到位证据。不能用 `System.nanoTime` 猜测手机休眠前后的传感器时间基准。
- 局部 IMU 引导求解器的旧二维旋转属于地平切平面；改为导出真实 image-up 射线并转换到 J2000 位置角，使局部和盲解采用同一姿态约定。
- 主镜 USB 回调时刻不冒充精确曝光中点。参考采样在空闲相机上开始一个新采集代次，保留起止时间范围和误差；配对保持原始观测不变，另记录时间补偿方向、手机监测到的运动量与保守周日误差。
- 主镜参考点是传感器中心，ROI 不覆盖该点就拒绝；TAN WCS 使用 CRPIX/CD 显式投影，不把 CRVAL 默认当任意 ROI 的镜筒轴。含未支持畸变或错误历元的参考拒绝作为校准输入。
- ASTAP 未提供测量 RMS 时，参考使用明确记录的保守操作误差界，不能把该数值标成实测 RMS；实际安装残差来自另一方向的独立验证。实测基线仍须验证此误差模型。
- 原始手机／参考观测与派生方向分开；关系保存包含两组观测 ID、时刻、设备／镜头／光路、像素几何、实际验证残差。失败不能覆盖旧关系；已到位和当前方向不跨启动恢复。
- 镜面反射不能用一个四元数或“镜像”开关冒充 IMU 外参。镜面路径只有在真实 hand-eye 拟合和独立验证后使用测得的外参传播姿态；缺模型或传感器会话中断则回到绝对光学定位。详见 [镜面标定](MIRROR_HAND_EYE_CALIBRATION.md)。
- 辅助 USB 帧同时记录交付间隔和最早可能曝光时刻。没有 SDK 逐帧时间来源时，曝光范围保守取本次新采集开始到该帧交付，不用相邻交付间隔缩窄时间误差；不丢弃范围内首帧来掩盖缺失光学覆盖。到位过龄检查使用最早可能观测时刻。
- 光路变化与架台运动会撤销在途解析的发布资格。局部配置失效只移除对应光路的直接观测；解析在旧配置下开始、在新配置下结束时也不能重新点亮旧指向。没有参考相机物理身份的旧完整姿态记录恢复为无效。
- RAW/YUV 逐行归并到有界单色图，保存精确有效像素覆盖范围，原 DNG 不改写；短曝光堆栈直接采样变换后的像素，不为每帧生成完整对齐副本。堆、相机缓冲和存储预算不足时停止当前请求，资源错误不提高曝光／帧数重试。

## 整合执行记录（持续追加）

### 最终自动化结果

- 全量 JVM：**917 项通过，0 失败/错误/跳过**，见 `wf-stop-verified-build.log` 和 Gradle XML。
- 含 Stellarium：最终覆盖 **91 项通过、1 项原生星图专属跳过**。`wf-stellarium-accepted-ui.log` 的最后一项回环断言误将控制器既有的“双 STOP”（即时急停＋协议同步停止）计作重复调用；按实际协议修正后，七项回环在相同生产代码下独立重跑 **7/7**，见 `wf-stellarium-protocol-final.log`。其他场景没有失败。
- 不含 Stellarium：**83/83 通过，116.187 秒，无跳过**，见 `wf-native-accepted-ui.log`。含完整壳层、原生光路面板、共享地点、相机/制冷、草稿/恢复、颜色/CFA、外部文件、真实边缘手势及七项协议回环。
- 星图 JavaScript **13/13**；离线原型 DOM **18/18**；Python 基线工具 **16/16**；索引 `--check` 一致。
- 正常 ARM64 构建使用 `Build.ps1 -NonCommercial`，Toup 新 JNI 已实际编译/链接；序列入口关闭。冻结 SDK 源文件未改，三份 libusb SONAME 互不相同；`libusb-1.0.so` 打包也保留原字节。
- 修复的额外科学/设备边界：Toup 的 `get_Size` 与实际 FrameInfo/FinalSize 分离；私有大缓冲防旧尺寸溢出、紧凑交付不带尾部旧像素；偏心 ROI 不假绿；多帧结果不伪造第一帧时刻；安装变化后不能把旧训练样本保存为有效；真实停止发送与任务释放之间没有新运动插入窗口。

以下为过程记录，失败项已由上述最终复测覆盖，不能单独当作当前状态：

- `wf-resource-and-platform-build.log`：完整 JVM **889 项、0 失败/错误/跳过**，模拟器主 APK 与 test APK 编译通过。
- `wf-navigation-and-boundaries-build.log`：工作区独立栈、原子标定保存及 Java 外部调用夹具整合后，完整 JVM **898 项、0 失败/错误**，两 APK 编译通过。后续新增代码仍须重新验证，不把此快照当最终提交验证。
- `wf-expanded-regression.log`：Android 58 项中 **54 通过、3 失败、1 原生星图专属跳过**。相机/制冷 3、设置草稿 5、辅助标定/引导 4、工厂身份/FD 所有权 2、真实协议回环 6、关系仓库 5 均通过。失败涉及任务标题可见性、来源断言和辅助光路切换；修复后重跑，不计作通过。
- `wf-color-cfa-rerun.log`：色彩、SER/CFA、播放器和素材库 **16/16 通过，15.911 秒**。
- `wf-platform-back-retest.log`：真实系统边缘返回两项通过，非匹配媒体 Intent 一项通过；三个外部文件路径测试仍失败，正在定位跨进程无障碍断言。首轮夹具 Kotlin 独立进程依赖问题已换成纯 Java，不通过跳过掩盖结果。
- JavaScript 星图交互 **12/12**；离线原型真实 DOM **18/18**；Python 基线工具 **16/16**；预生成索引 `--check` 一致。

日志在本机 `D:/Project_SEDIRK/.android-toolchain/`。以上模拟器和合成样本结果不计入独立实拍 WCS、真实运动、S23 Ultra 实机或新手验收。

## 一手依据

- [Camera2 SENSOR_INFO_TIMESTAMP_SOURCE](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE)：按声明的时钟域配对。
- [FITS WCS 规范入口](https://science.data.nasa.gov/fits/documentation/wcs)、[FITS 标准](https://fits.gsfc.nasa.gov/standard30/fits_standard30.pdf)：CRPIX、CD 与投影边界。
- [ASTAP 官方命令行与输出说明](https://www.hnsky.org/astap.htm#astap_command_line)：INI 的 J2000、中心参考像素和 CD 输出；输出成功不提供本项目实拍精度保证。
- [AZ-EQ6 官方手册 §5.1](https://inter-static.skywatcher.com/upfiles/en_download_caty01353096919.pdf)：辅助编码器可支持手动运动记录；本项目当前 motor `:j` 读的是位置计数器，不能据型号认定当前连接已暴露辅助码盘或绝对天球真值。
