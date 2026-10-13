# 外部媒体与系统返回边界验收

2026-10-14。覆盖 WF-03 / WF-11 / UX-13；行为遵循 `INTERACTION_GUIDELINES.md` 第 6 节。本文区分已实现行为与尚待运行的证据，不代替 S23 Ultra 或新手现场验收。

## 实现边界

- 外部 `ACTION_VIEW` / 单文件 `ACTION_SEND` 的 `content://` 图片或录像进入独立、标准启动模式的 `ExternalMediaActivity`。该 Activity 不创建相机/设备 ViewModel，不接管已有采集。
- 系统 Back 由真实平台任务栈回外部调用者；文件查看器工具栏 Up 进入应用内合理的影像父目的地。已有应用任务/草稿由 Shell 保留。
- 原文件只读。导入使用 64 KiB 缓冲，至少保留 256 MiB 空间，先写唯一隐藏临时文件、同步、校验内容，再在同一目录原子重命名；失败或取消只删除该次未发布临时副本。
- 完成副本放进同一媒体库的 SER / PSER / FITS / JPG 目录。支持内容校验后的 SER、PSER、FITS、JPG、PNG；不注册 MP4 或无范围的二进制 MIME，以免循环调用外部视频播放器。
- 当前明确容量边界：录像最多 8 GiB、图片 128 MiB、FITS 32 MiB；FITS 仅支持已有预览器可消费的 2D 单通道 / 3 通道主图像。PNG/JPG 显示按最长边 2048 像素采样。导入不改变原始像素数据。
- 镜面安装同时完成真实 hand-eye 外参、奇偶性与光轴关系后才传播手机姿态。源会话中断会清掉活姿态锚点；这与外部文件 Activity 的导航保持分开。

## 实际平台测试

`ExternalMediaBoundaryInstrumentedTest` 使用测试 APK 自己的 Activity 和 FileProvider 分享 PNG 给生产 Activity，检验：读授权、副本字节、同库索引、系统 Back 回调用者、Up 留在本应用、MP4/无关二进制不匹配，及真正边缘返回取消/提交。

`SystemBackGestureInstrumentedTest` 在实际 Shell 与 ViewModel 上检验：取消边缘返回保留极轴任务，提交一次只退出一层；IME 可见时边缘返回先收键盘并保留输入，下一次才离开任务。

手势注入进入 SystemUI 边缘区域，不调用应用 `onBackPressedDispatcher`。测试临时启用并恢复系统 gesture-navigation overlay 及硬件键盘下的 IME 显示；不支持该 overlay 的设备会跳过手势测试，不产生通过证据。

在同一 APK 配置下串行构建、安装 `debug` 与 `debugAndroidTest`，再运行：

```powershell
.\scripts\Test-PlatformBack.ps1 -AdbPath '<Android SDK>\platform-tools\adb.exe' -Serial emulator-5560
```

默认日志：`bin/platform-back-tests.log`。模拟器结果只能记为模拟器；S23 Ultra 真实边缘手势、输入法差异以及两名新手 UX 验收仍需现场证据。

## 运行记录

- 整合构建 / JVM 889 项通过；`emulator-5560` 第一轮平台测试 **2/6 通过、4/6 失败**，不能标记平台边界完成。
- 其中真实 IME 先退通过；取消边缘 Back 保留极轴页、提交只返回准备页的行为通过，但准备子页的工作区选中节点未显示，测试在该断言失败，导航修复待重跑。
- 三个 external 案例在调用者启动阶段失败，尚未进入生产 viewer。logcat 明确为测试 APK 独立进程缺 `kotlin.jvm.internal.Intrinsics`；调用者 Activity/provider 已改为纯 Java + framework，避免依赖被 AGP 去重到 target APK 的 runtime。替换后的真实边界流程待重建/重跑，没有跳过。
- 日志：`D:\Project_SEDIRK\.android-toolchain\wf-platform-back.log`。这些是模拟器证据；尚无 S23 Ultra 或两名新手现场结果。
- 后续整合 JVM **901 项通过**。`wf-platform-and-navigation-verification.log` 的 37 项组合中，平台 6 项已有 **4/6 通过**：两项实际 Shell / IME 手势、外部 SEND / Up、排除 MP4 / 无关 MIME。外部 VIEW 和外部边缘返回仍失败，不能计为全部通过。
- 后两项失败暴露测试生命周期竞态：切换系统导航 overlay 后 active window 短暂为空；异步 cleanup 与下一项重复使用同一文件/通用按钮。夹具已改为每项唯一受限 URI、等待生产 viewer 真正销毁、framework `ResultReceiver` 确认调用者清理，并在手势前等待对应窗口及内容稳定。修复只涉及测试夹具，保持实际系统手势与生产导航断言，待统一重建/重跑。

- **最终复测：平台六项在含 Stellarium 与原生两构建均 6/6 通过，无跳过。** 见 `wf-stellarium-accepted-ui.log`、`wf-native-accepted-ui.log`；夹具使用递归读取 Compose 无障碍节点和纯 framework parcel 的清理 ACK。前述失败是已解决的过程记录。S23 Ultra 和新手现场验收仍未进行。
