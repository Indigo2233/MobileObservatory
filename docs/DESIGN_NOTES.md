# 设计备忘（查找用）

已拍板的产品与交互决策。改行为先改这里，再改代码。待办与真机缺口仍写 `TODO.md`。

相关代码路径写在各节末尾，便于从文档跳到实现。

---

## 星图：跟随指向 vs 找星

连接赤道仪**不得**把星图强制锁在当前指向。默认用途是拖动画布找星，再 GOTO。

| 点 | 约定 |
|---|---|
| 默认 | 不跟随。偏好键 `star_map_follow_mount`，缺省 `false`。旧安装若已存 `true`，拖一下即停。 |
| 跟随开着时 | 赤道仪坐标刷新会把画面居中到指向，让屏幕中央的视场框对准望远镜。 |
| 拖动 | 单指移动超过约 16 px **暂停跟随**，并通知 Android 关掉开关。双指缩放不因此关掉跟随。 |
| 按下期间 | 坐标轮询不得抢居中，否则一拖就弹回。 |
| 需要看指向时 | 打开「跟随赤道仪指向」，或点「居中到赤道仪」。 |

视场框是 CSS `position:fixed` 钉在屏幕正中，不是钉在天空坐标上。跟随曾被当成「框=望远镜」的默认，但会让找星无法完成。不跟随模式下框仍停在屏幕中央，这是已知限制（见 `TODO.md` 6c）。

**代码：** `StarMapScreen.kt`（`followMount`、`onFollowMountChanged`）、`app/src/stellarium/assets/stellarium/app.js`（`followMount`、`userPointerActive`、`pauseFollowAfterUserPan`）

---

## 星图：左下角双面板

控件默认会自动隐藏。点一下星图再出现。空闲约 4 秒且没有打开面板/对话框时收起，包括返回、搜索和左下角按钮。

左下角两个圆钮（Stellarium Plus 同款）：

| 按钮 | 内容 |
|---|---|
| 观测工具 | 赤道仪方向盘、跟随指向、居中到赤道仪、视场叠加与视场设置 |
| 星图图层 | 赤经赤纬网格（历元网格 `equatorial_jnow`）、地平网格、子午圈、黄道、星座连线/名称/边界、恒星标签、大气、夜间红光、DSS、锁定浮层 |

同时只打开其中一个面板。网格默认关闭，开关写入 `star_map_*` 偏好。

**代码：** `StarMapHud.kt`、`StarMapScreen.kt`、`app.js` `setSkyAppearance`

**回归：** `StarMapAssetsRegressionTest.skyAppearanceControlsEquatorialAndHorizonGrids`、`ObservingUiWiringTest.starMapChromeUsesTwoHideableCornerPanels`

---

- 预览 / 相机矩形：`#fov-frame`，黄色虚线。
- 望远镜 / 目镜圆形：`#eyepiece-fov`，绿色实线。
- 不要屏幕中心十字丝（`mount-reticle` 已去掉）。指向改用跟随或「居中到赤道仪」。

**代码：** `stellarium/styles.css`、`app.js` 叠加层标签

---

## 赤道仪：全局 STOP 与手动方向键

同一时刻只允许一种运动（`MountMotionRunner`）。GOTO、回零、定距 RA 期间，各页面左下角弹出全局 STOP。

**手动方向键（按住移动、松手停止）不弹全局 STOP。** 方向盘里已经有停止键。弹窗与左下角方向盘重叠，新窗口一出现会取消当前按住，表现为「按钮被顶上去、断触」。

| `MountMotionType` | 全局 STOP |
|---|---|
| `GOTO` / `HOME` / `RA_MOVE` | 显示 |
| `MANUAL` | 不显示（`showsGlobalStop == false`） |
| `IDLE` | 不显示 |

星图方向盘在南键下提供「回零位」（先确认）。赤道仪页与相机侧栏同样有回零 / 重设零位。

**代码：** `MountMotionState.showsGlobalStop`、`CameraScreen.MountMotionStopPopup`、`StarMapScreen` 方向盘

---

## 赤道仪：零位用语

对用户不要写「原点」「返回原点」「回到原点」「设为原点」。

| 操作 | 文案 |
|---|---|
| 转到已配置的家位置 | **回零位**（`home` / `go_home`） |
| 把当前位置记为家位置 | **重设零位**（`set_home`） |

确认框同样用「零位」。旋转器等配件的 `home` 字符串共用「回零位」。

**代码：** `values-zh-rCN/strings.xml` 的 `home`、`go_home`、`set_home`、`mount_home_confirmation`、`set_home_confirmation`

---

## 赤道仪：赤道仪 vs 经纬仪

连接时**不会**根据通讯自动在两种机械结构之间切换控制数学。型号字符串可以显示，但不改模式。

| 连接 | 模式从哪来 |
|---|---|
| SynScan Wi‑Fi（电机协议） | 连接前用户点选「赤道仪 / 经纬仪」。该选择参与编码器 ↔ 天球换算、跟踪与方向键。手控器回报的型号（EQ6、AZ-GTi 等）只用于显示。 |
| USB 高端 SynScan | 要求手控器已对齐，之后只读赤经赤纬；EQ/地平由手控器处理。 |
| iOptron | `:MountInfo#` 得到型号名（如 SkyHunter EQ / AA），协议仍走赤经赤纬。 |
| OnStep / LX200 | 无此模式开关。 |

选错 SynScan Wi‑Fi 模式时，坐标和方向键会对不上，需要断开后改芯片再连。

**代码：** `SkyWatcherMountMode`、`MountControlScreen` SynScan 芯片、`SkyWatcherMotorAdapter`、`Lx200MountController.connectSynScanWifi`

---

## 板解：焦距、像元与视场

用户不要手填视场高度。界面是 **焦距 + 像元（或选传感器）**，再算出 ASTAP 所需 FOV。

| 点 | 约定 |
|---|---|
| JPG / PNG | 没有 FITS `FOVH`。用 `206.265 × 像元μm / 焦距mm` 得角秒/像素，再乘图像高度。 |
| ASTAP `-fov` | 非 FITS 时是**图像高度（度）**，不是对角线、也不是宽度。 |
| 用户填的焦距 | 解算输入以用户当前值为准，不得用上一次解出的 525 mm 之类结果粘住下一次。 |
| 解算后 | 可显示**实测焦距**（由 ″/px 反推），那是结果，不是下次输入。 |
| 传感器目录 | 用画幅区分相近像元（如 IMX455 vs IMX571）。 |
| 星图 | `persistFovPrefs()` 不得覆盖 `plate_focal_length_mm`；只有用户在星图里明确选了望远镜焦距才写入。 |

**代码：** `PlateSolveOptics.kt`、`SolveOpticsFields.kt`、`OpticsEquipment.defaultSensors`、`FitsSolveHints.kt`、极轴页同一套光学栏

---

## 图谱（ToupTek）USB 连接

图谱 SDK 用 Android 已经打开的 fd（`Toupcam_Open("fd-%d-%04x-%04x")`），自己不走 libusb。

- 打开后必须把 `UsbDeviceConnection` 保存在 Java 里，直到主动断开。丢掉引用后 GC 会关 fd，表现为连不上或连上立刻掉线。ZWO / QHY / 图谱滤镜轮都是这样持有的。
- SDK 型号表没有的 PID 仍当作相机列出，不要因为 `getModelName` 为空就忽略（VID 仍是 0x0547）。
- 预览降采样只发生在 `FrameProcessor.frameToBitmap`，且仅当帧超过约 4MP；拍摄/FITS 走原图。常见图谱行星相机尺寸应保持 `sampleStep = 1`。
- 工业 overlay 覆盖 `DahengCameraManager.kt` 时必须带上 USB 句柄持有和未知 PID 枚举，否则会把公有树修复盖掉。

**代码：** `DahengCameraManager.openToupcamDevice`、`ToupTekDevices`

**回归：** `ObservingUiWiringTest.toupTekCameraKeepsTheUsbConnectionAlive`、`previewDownsampleIsDisplayOnly`、`PreviewScaleTest.typicalToupTekPreviewSizesStayFullResolution`、`ToupTekDevicesTest`

---

## 连接独立性

相机、赤道仪、导星相机、配件各自连接、互不绑定。协议细节留在 adapter 里，UI 只看统一 ViewModel 状态。包名/JNI 符号约定见 `AGENTS.md`。
