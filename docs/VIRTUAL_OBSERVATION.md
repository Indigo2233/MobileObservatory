# 虚拟观测

入口：器材 → 设备列表顶部 → 虚拟观测 → 连接虚拟设备。若器材页停留在设备详情，先返回设备列表。该入口在正式包中也可使用。它创建共享运动状态的主相机、导星相机和赤道仪，复用现有拍摄、导星、指向并居中和三点对极轴流程。启动要求主相机、导星相机和赤道仪均已断开；退出会停止任务并关闭虚拟设备。模拟状态仅保留在当前应用会话，重启后需要手动连接。

所有工作区显示模拟标记。图像来自仓库已有的 IMX585 实拍裁切，CFA 单元转为亮度后进行亚像素平移和周期平铺；曝光与增益影响亮度。主相机和导星相机按各自曝光时间生成帧。赤道仪支持方向运动、定时导星脉冲、GOTO、同步、跟踪和停止；脉冲具有独立截止时间，DEC 换向先消耗指定回差。

虚拟解析器返回曝光时刻保存的相机姿态与 WCS，使用独立临时 FITS 文件展示画面。结果和文件明确标记为模拟，保存在缓存目录，不进入科学采集来源。实际 ASTAP 匹配、星库、USB/SDK 读出、机械响应与真实天空精度仍需独立验收。

## 操作

1. 导星：工具 → 导星，自动或手动选星，开始导星；等待 RA/DEC 校准完成后观察曲线。可停止并重新选择星点。
2. 对极轴：工具 → 三点对极轴。虚拟站点默认北纬 30°、东经 120°，焦距 200 mm、像元 2.9 μm。初始方位偏差 +0.08°、高度偏差 +0.06°（约 6′）；模拟控制内可切换到方位 +0.8°、高度 +0.6° 的 1° 大偏差场景。启动自动三点后进入连续调整。
3. 展开顶部模拟控制，按向东、向西、向上、向下调整；默认每次 6′，可切为 1′。界面同时显示模型设定的当前偏差，便于与算法测得值比较。
4. 遮挡星点可检查丢星处理，恢复后可重新开始。下次解析与盲解失败会消耗两次模拟失败，用于检查重拍重试。DEC 回差可在 1800 / 3000 ms 间切换，修改后重新校准。
5. 指向并居中沿用目标操作入口，使用虚拟主相机独立曝光与模拟解析。

默认场景保留极轴误差导致的物理漂移和 0.005 px/s 的附加跟踪误差。大偏差场景会增加漂移，适合检查极轴引导；在该场景完成测试后可恢复约 6′ 默认偏差再运行导星校准。

模拟极轴参数保存在独立偏好文件，实拍参数保持原有设置。每个会话最多保留 32 个模拟采样文件。

当前虚拟相机输出 1024 × 768 的 8 位单色预览帧，曝光上限为 5 秒，实时科学叠加入口禁用。此模式可检查导航、任务启停、校准、失败重试及调整流程；彩色叠加、白平衡、满幅内存预算和真实相机长曝光读出仍需通过对应原图或设备验证。拍摄页另有“试用模拟相机”，仅用于拍摄界面演示；完整观测流程使用本页的虚拟观测入口。

### build 61 后的源码修正（待验证、未出包）

解析失败原图归档按 FITS 的 `IMAGETYP=SIMULATION` 排除模拟图像，防止虚拟故障注入进入正式相册并挤占真实失败样本配额。文件改名不影响此识别；真实图像头损坏仍允许归档。重复归档已有的失败证据时复用原图、原子更新日志，取消更新保留旧日志和原图。

虚拟观测的普通拍照和录像复用模拟相机的状态演示与帧数、时长、限额计数，不创建正式媒体文件，不将帧加入文件写入队列；暂停曝光会结束模拟录像。对极轴和居中的临时采样继续写入会话缓存。虚拟单张曝光持有相机占用状态，曝光中恢复预览会被拦截，取消后释放占用并同步曝光和增益读数。

本轮新增 8 项回归用例源码，并扩展已有失败重试用例：

- `SolveFailureArchiveTest`：重复归档保持原图路径和字节、取消日志替换保留旧证据、相似名称的外部文件保持不变。
- `VirtualObservationInstrumentedTest`：各拍照/录像格式均不保存模拟数据、不进入写入队列、录像限额及暂停生效；放弃极轴解析后用同一张图重试；曝光占用与取消恢复；后台极轴页解析时退出并重连。原有失败注入用例增加相册无新增文件断言。
- `PrecisionGotoStopCrashInstrumentedTest`：居中解析期间断开虚拟设备，等待旧任务结束后重连，并完成新一轮居中。

以上用例待统一执行。本轮仅完成源码检查，未运行测试、构建或重新发布；现有 build 61 不包含本节新增修正。

## 本地构建与验收

虚拟观测属于正常 App 的内置模式，使用原包名 `com.indigo.mobileobservatory` 和显示名 `靛空台`，通过器材页启用。沿用标准构建命令：

```powershell
.\Build.ps1 -NonCommercial
```

保持现有 versionCode。输出 `bin/Installer/IndigoObservatory_android.apk`。序列入口默认关闭。覆盖已安装的正式版需要相同签名；签名不可用时保留原安装与数据。

`VirtualObservatoryTest` 检查三点轴几何、调整后的连续残差、DEC 空程、脉冲截止/停止、故障恢复及实拍像素驱动的正式校准，校准测量包含 2 秒曝光与处理等待。`VirtualObservationInstrumentedTest` 运行真实 MainActivity、ViewModel、相机预览与流程，检查导星校准/运行/停止、极轴失败重试/连续调整、指向并居中，以及预览期间的导航和模拟控件。

已有 Android 模拟器启动后，可在本机复跑（将序列号替换为对应模拟器）：

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest -PstellariumNonCommercial=true -PsequenceEnabled=false -PemulatorTest=true
adb -s emulator-5560 install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5560 shell am instrument -w -r -e class com.indigo.mobileobservatory.ui.VirtualObservationInstrumentedTest com.indigo.mobileobservatory.emulatortest.test/androidx.test.runner.AndroidJUnitRunner
```

上述 x86 开发变体仅用于本机模拟器，手机交付沿用正常 ARM64 包。验收以 instrumentation 最终 `OK` 及断言结果为准；安装成功或编译成功各自只证明对应步骤。

### 2026-10-29 验收记录

- 相关 JVM 回归：115 项通过，包含默认场景的 2 秒曝光间隔导星校准。
- Android API 34 模拟器：4 项端到端测试全部通过（97.57 秒），使用标准 Gradle 构建产物。
- 覆盖导星校准/正式导星/停止、1° 偏差极轴重拍重试/连续调整、独立曝光居中、连续预览期间的导航/模拟控制/退出。
- 在自动测试结束后，另以 Android 实际触控事件检查器材页连接、切换拍摄页、展开模拟控制、遮挡/恢复星点及退出，均正常响应。预览与控制截图位于本地 `build/reports/virtual-observation/virtual-controls.png`。
- 修复 AAPT 展开 `.gz` 后资源路径变化造成的连接失败。故障细节写入诊断日志，界面提供重新连接提示。
- 手机端测试已按用户要求停止；手机整体触控失去响应的反馈尚未在相同设备上复测。
- 正常 ARM64 Release 已生成：`bin/Installer/IndigoObservatory_android_v1.0.9-build59.apk`，原包名、原显示名、build 59；SHA-256 `deee892500197f4b3ac4a98d47c082841bf1becf2ad65cc57c552c18e55a892d`。正式证书及历史签名链均通过构建校验，冻结的 ZWO/libusb 二进制与源文件逐字节一致。未继续安装到手机。

本机正式签名保存在 `%USERPROFILE%/.android/indigo-observatory-release.jks`，配套 `indigo-observatory-release.credential.xml` 由 Windows 凭据保护；仅检查当前环境变量不足以判断签名是否存在。证书与 `docs/RELEASE_PROCESS.md` 指纹一致，历史签名链使用同目录下已核对指纹的 `debug.keystore`。
