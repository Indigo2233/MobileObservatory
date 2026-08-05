# targetSdk 升级迁移计划（TODO 事项 4）

现状：`compileSdk = 34`，`targetSdk = 34`，`minSdk = 26`。
目标：分阶段升到 **targetSdk 34**（视发布渠道要求再评估 35），移除 lint 豁免，Android 12–15 冒烟通过。

## 0. 已就绪项（调研结论，无需改动）

代码已提前满足多数新版行为，升级主要是「验证」而非「重写」：

| 新版要求 | 生效 API | 现状 |
|---|---|---|
| Scoped Storage：MediaStore + `RELATIVE_PATH` + `IS_PENDING` 保存图像/视频 | 29 | `CameraViewModel` 已实现；`getExternalStoragePublicDirectory` 仅作为 API < 29 回退（minSdk 26 仍需要，保留） |
| 存储权限声明加 `maxSdkVersion` 上限 | 29/33 | Manifest 已限 `WRITE≤28`、`READ≤32` |
| PendingIntent 必须显式 mutability | 31 | 所有 USB 权限 PendingIntent 均已 `FLAG_MUTABLE`（USB 广播需要） |
| 新蓝牙权限 `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`（`neverForLocation`） | 31 | Manifest 已声明；`BluetoothPermissionPolicy` 已做运行时请求 |
| 请求精确定位必须同时请求粗略定位并接受降级 | 31 | `PolarAlignmentScreen` 同时请求 FINE+COARSE，任一授予即可用 |
| 带 `<intent-filter>` 的组件必须显式 `exported` | 31 | `MainActivity` 已 `exported="true"` |
| 动态注册接收器需声明 exported 状态 | 33 | `DahengCameraManager` / `AccessoryDeviceManager` 已按 SDK 分支传 `RECEIVER_EXPORTED` |
| `POST_NOTIFICATIONS` 通知运行时权限 | 33 | 全程无通知，不涉及 |
| 前台服务类型必须声明 | 34 | 无前台服务，不涉及 |
| 强制 edge-to-edge | 35 | `MainActivity` 已 `enableEdgeToEdge()` 并自管 insets |

## 1. 阶段一：targetSdk 28 → 33

改动：

- [x] `app/build.gradle.kts`：`targetSdk = 33`（随后并入阶段二至 34）
- [x] `extractNativeLibs` 从 Manifest 移到 Gradle `packaging { jniLibs { useLegacyPackaging = true } }`（消除 AGP 警告，保持厂商 SDK 从文件系统 dlopen 的现状）
- [x] 移除 Manifest 里 `tools:targetApi="28"` 应用级标注

验证重点（真机 Android 12/13）：

- [x] USB 相机全流程：插拔广播、权限对话框、fd 传递（ToupTek / ZWO / QHY + libusb Android FD patch）
- [x] 蓝牙赤道仪：扫描/连接权限弹窗、拒绝后的恢复指引
- [ ] 拍摄保存：JPG/FITS 到 MediaStore；录像 SER/PSER/MP4 及图库复制
- [x] Stellarium WebView 星图加载与 GOTO
- [x] 极轴校准取手机位置（仅授粗略定位时也可用）
- [ ] 手机板解调试页：相机权限流、RAW 拍摄、缓存清理

## 2. 阶段二：targetSdk 33 → 34

改动：

- [x] `targetSdk = 34`
- [x] 检查隐式 Intent 均指向导出组件（系统设置页 / `ACTION_SEND` chooser；USB 权限广播改为显式 `setPackage`）

验证重点（真机/模拟器 Android 14）：

- [ ] 重跑阶段一整套冒烟
- [ ] 前后台切换 + Activity 重建后设备重连（相机/赤道仪/配件）
- [ ] `USB_DEVICE_ATTACHED` 启动路径

## 3. 阶段三：收尾与合规

- [x] 删除 `lint { disable += "ExpiredTargetSdkVersion" }`
- [x] `./gradlew lintDebug` 零新增 error；清理随升级失效的 `@Suppress` / `tools:` 标注（`registerReceiver` 统一改 `ContextCompat`）
- [x] 权限被永久拒绝时的设置页指引复查（相机 / 蓝牙 / 定位统一 `open_app_settings` + `AppSettingsNavigator`）
- [x] README/AGENTS 构建说明如有 SDK 版本表述则同步（无硬编码 targetSdk，无需改）

## 4. 阶段四（观察项）：targetSdk 35

暂不执行，列入观察：

- Google Play 2025+ 要求 target 35 时再升
- **16 KB page size**：Android 15 新设备要求 native 库 16 KB 对齐；厂商预编译 `.so`（toupcam / qhyccd / ASI / astap）需逐个核查，未对齐的等厂商更新或评估重打包
- edge-to-edge 已就绪，预期 UI 无改动

## 5. 测试矩阵

| 平台 | 方式 | 覆盖 |
|---|---|---|
| Android 12 (31) | 真机 | USB 相机、蓝牙赤道仪、保存、权限 |
| Android 13 (33) | 真机或模拟器 | 同上 + 接收器 exported 行为 |
| Android 14 (34) | 真机 | 全套冒烟 + 重建重连 |
| Android 15 (35) | 模拟器 | UI/edge-to-edge、存储回归 |
| Android 8.1 (27) | 模拟器 | minSdk 回归：旧存储回退路径 |

USB / 蓝牙硬件项参照 `docs/testing/HARDWARE_SMOKE_TESTS.md` 执行。

## 6. 风险

- 厂商 native SDK 在新 target 下的 USB 行为差异只能真机验证（重点 QHY libusb 路径）
- WebView 随 target 变化的混合内容/文件访问策略：Stellarium 资源走 `WebViewAssetLoader`，风险低但需回归
- 每阶段独立提交，出问题可单独回退版本号
