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

视场框按天空坐标投影：当前框跟赤道仪指向，目标黄虚线钉在屏幕中央构图。未接赤道仪时当前框也在屏幕中央。跟随曾被当成「框=望远镜」的默认，但会让找星无法完成。

**代码：** `StarMapScreen.kt`（`followMount`、`onFollowMountChanged`）、`app/src/stellarium/assets/stellarium/app.js`（`followMount`、`userPointerActive`、`pauseFollowAfterUserPan`）

---

## 星图：左下角双面板

控件默认会自动隐藏。点一下星图再出现。空闲约 4 秒且没有打开面板/对话框时收起，包括返回、搜索和左下角按钮。

左下角两个圆钮（Stellarium Plus 同款）：

| 按钮 | 内容 |
|---|---|
| 观测工具 | 赤道仪方向盘、跟随指向、居中到赤道仪、视场叠加与视场设置 |
| 星图图层 | 赤经赤纬网格（历元网格 `equatorial_jnow`）、地平网格、子午圈、黄道、星座连线/名称/边界、恒星标签、大气、DSS、锁定浮层 |

同时只打开其中一个面板。网格默认关闭，开关写入 `star_map_*` 偏好。底栏与点选目标卡左右分栏，各不超过半屏和 300dp，避免叠在一起。

夜间红光不在图层里：右上角搜索按钮下方单独一颗灯钮。打开后 Material 主题变红，并且 `setNightVision` 给星图画布加上红光滤镜。

**代码：** `StarMapHud.kt`、`StarMapScreen.kt`、`app.js` `setSkyAppearance` / `setNightVision`、`styles.css` `html.night-vision`

**回归：** `StarMapAssetsRegressionTest.skyAppearanceControlsEquatorialAndHorizonGrids`、`StarMapAssetsRegressionTest.nightVisionTintsTheStarMapCanvas`、`ObservingUiWiringTest.starMapChromeUsesTwoHideableCornerPanels`

---

## 星图：视场框样式

两条光路（主镜 / 导星），同一时刻只画**激活那一条**的视场。两框同一形状：绿实线 = 当前，黄虚线 = 目标。

| 点 | 约定 |
|---|---|
| 光路 | 各有焦距 + 终端（目镜圆或相机矩），互不覆盖。默认显示**导星**。 |
| 当前框 | `#fov-current`，绿实线。已连接赤道仪时钉在指向的 RA/Dec；未连接则钉屏幕中央。 |
| 目标框 | `#fov-target`，黄虚线。只要开着视场叠加就一直显示，钉在屏幕中央（构图/GOTO 这块天）。不需要先点选天体。 |
| 标签 | 用用户给主镜 / 相机起的显示名，例如 `C8 当前 0.42°` / `ASI533 目标 1.20°×0.80°`。未命名时才回退到「主镜」「导星」。 |
| 设备库 | 视场设置可添加、删除、命名主镜和相机；芯片和叠加层都显示该显示名。已连接相机不能改名或删除。至少保留一项。 |
| 十字丝 | 不要屏幕中心十字丝（`mount-reticle` 已去掉）。指向改用跟随或「居中到赤道仪」。 |
| 板解焦距 | 仅当**导星且为相机**时，在视场设置里改焦距才写 `plate_focal_length_mm`。主镜目视不改板解焦距。`persistFovPrefs()` 不得顺手覆盖该键。 |
| 旧安装 | 现有 `star_map_*` 键迁到主镜。 |

**代码：** `StarMapOpticsTrain.kt`、`UserOpticsCatalog.kt`、`StarMapFovOverlay.kt`、`StarMapFovSheet.kt`、`StarMapHud.kt`、`stellarium/styles.css`、`app.js` `projectRaDecToScreen`

**回归：** `StarMapOpticsTrainTest`、`UserOpticsCatalogTest`、`StarMapFovOverlayTest`、`FovOverlayLayoutTest.skyOffsetUsesTheSameLinearDegreeMappingAsTheBox`、`StarMapAssetsRegressionTest.overlayApiUsesCurrentAndTargetRoles`

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
| 星图 | `persistFovPrefs()` 不得覆盖 `plate_focal_length_mm`。只有在**导星且为相机**的视场设置里明确改焦距时才写入。 |

**代码：** `PlateSolveOptics.kt`、`SolveOpticsFields.kt`、`OpticsEquipment.defaultSensors`、`FitsSolveHints.kt`、极轴页同一套光学栏

---

## 指向并居中：历元与停止误差

赤道仪协议与星图点击用 **JNOW**；星图目录和 ASTAP WCS 用 **J2000/ICRF**。2026 年岁差约 20′，混用会让 6′ 停止条件永远达不到，星图居中也会偏一块。

| 点 | 约定 |
|---|---|
| 进赤道仪前 | `EquatorialEpoch.toJnowHours`：J2000/ICRF 岁差到当日，JNOW 原样。GOTO / 同步 / 指向并居中共用。 |
| ASTAP 结果 | CRVAL 是 J2000，先转到 JNOW 再和目标比、再 sync。 |
| 星图居中 | 解算坐标已经是 JNOW，`centerOnRaDec(..., frame = "JNOW")`。 |
| 停止误差 | 默认 **6′**。星图「观测工具」可改，写入 `star_map_precision_tolerance_arcmin`，范围 1′–30′。 |
| 成功当轮 | 若赤道仪支持 sync，仍要把解算位置 sync 上去，避免第一轮已够近却模型不更新。 |

**代码：** `EquatorialEpoch.kt`、`PrecisionGotoMath`、`CameraViewModel.startPrecisionGoto`、`StarMapHud` 观测工具面板

**回归：** `EquatorialEpochTest`、`PrecisionGotoMathTest`、`ObservingUiWiringTest.precisionGotoUnifiesEpochsAndExposesSixArcminTolerance`

---

## 星图目视同步

不用相机时，把天体对准目镜中心，在星图点选该天体，再按 **同步**。赤道仪不转，只把当前指向写成该天体的 JNOW 坐标。同步与 GOTO 同级显示，不必先展开目标详情。「指向并居中」仍要相机 + ASTAP，和目视同步不是同一条路。

| 点 | 约定 |
|---|---|
| 历元 | 与 GOTO 相同：进赤道仪前 `EquatorialEpoch.toJnowHours`。 |
| USB SynScan 手控器 | 不支持协议 sync；请在手控器对齐，或改用 Wi‑Fi / 电机协议。 |
| OnStep / LX200 / iOptron / SynScan 电机 | `:CM#` 或电机协议 sync。 |

**代码：** `StarMapScreen` 目标卡、`CameraViewModel.syncMountToTarget`、`MountModule.syncMountToTarget`、`Lx200MountController.syncTo`

**回归：** `ObservingUiWiringTest.starMapExposesVisualMountSyncWithoutExpandingTarget`

---

## 图谱（ToupTek）USB 连接

图谱 SDK 用 Android 已经打开的 fd（`Toupcam_Open("fd-%d-%04x-%04x")`），自己不走 libusb。

- 打开后必须把 `UsbDeviceConnection` 保存在 Java 里，直到主动断开。丢掉引用后 GC 会关 fd，表现为连不上或连上立刻掉线。ZWO / QHY / 图谱滤镜轮都是这样持有的。句柄要在 `Toupcam_Open` 返回前就赋给字段。
- 枚举结束不要把连接状态设成 `Enumerating`。那个状态会用转圈盖住连接按钮，而且没有超时和报错。USB 权限弹窗必须在主线程申请；打开相机不要在 `open()` 里写 binning。
- 扫描只按 Android USB 列表列出相机，不要在扫描时启动 Player One SDK。点中某一台后才连接，Player One 的 SDK 只在这时启动。
- 导星相机不打开主相机已经占用的那台 USB 设备。主相机没连接时，导星相机可以单独连接，例如主镜目视、导星镜负责指向。拔掉其中一台时，另一个会话不要跟着断开。
- SDK 型号表没有的 PID 仍当作相机列出，不要因为 `getModelName` 为空就忽略（VID 仍是 0x0547）。
- 预览降采样只发生在 `FrameProcessor.frameToBitmap`，且仅当帧超过约 4MP；拍摄/FITS 走原图。常见图谱行星相机尺寸应保持 `sampleStep = 1`。
- 工业 overlay 覆盖 `DahengCameraManager.kt` 时必须带上 USB 句柄持有和未知 PID 枚举，否则会把公有树修复盖掉。

**代码：** `DahengCameraManager.openToupcamDevice`、`ToupTekDevices`

**回归：** `ObservingUiWiringTest.toupTekCameraKeepsTheUsbConnectionAlive`、`previewDownsampleIsDisplayOnly`、`PreviewScaleTest.typicalToupTekPreviewSizesStayFullResolution`、`ToupTekDevicesTest`

---

## 相机：预览浮层不要竖排堆按钮

横屏预览高度不够把「适应、夜间、面板、叠加、对焦辅助、中心十字」六个 SmallFAB 竖排在右上角。最下面两颗会压住右下角对焦辅助窗。

| 点 | 约定 |
|---|---|
| 常驻 | 面板开关、适应窗口（连着相机时）+ 一个「预览工具」菜单。左上只留暂停和 REC。仍横排，不要竖排堆 FAB。 |
| 菜单里 | 夜间红光、信息叠加、对焦辅助、图像中心、图像解析、文件浏览。 |
| 对焦辅助打开时 | 自动收起菜单，避免挡住放大窗。 |
| 对焦窗 | 约 220×160 dp，默认展开；需要时再收成分数条。 |

**代码：** `CameraPreviewTools.kt`、`FocusAssistOverlay.kt`、`CameraScreen.kt`

**回归：** `ObservingUiWiringTest.cameraPreviewChromeUsesOverflowMenuInsteadOfVerticalFabStack`

---

## 天体库与星图目录

目标库直接复用星图搜索的 `AssetDeepSkyCatalog`（OpenNGC + 命名亮星 + Caldwell/中文别名），空搜索走今夜可见排序，不再另做一份演示 10 条。

**代码：** `TargetLibraryScreen.kt`、`AssetDeepSkyCatalog.kt`、`StarMapScreen.kt`

**回归：** `ObservingUiWiringTest.targetLibraryUsesTheStarMapCatalog`

---

## 推镜用法（对标 StarSense Explorer）

选目标后：静止自动拍星野对齐 → 按箭头推镜（过程中不解算，IMU 跟随）→ 停手再自动拍一张校正。到位后不再连拍。仍可点「立即对齐」手动重解。

**代码：** `MotionGate.kt`、`PhoneSkyAttitudeSource.kt`、`PushToExperienceScreen.kt`

**回归：** `MotionGateTest`、`PushToAutoSolveTest`

---

相机、赤道仪、导星相机、配件各自连接、互不绑定。协议细节留在 adapter 里，UI 只看统一 ViewModel 状态。包名/JNI 符号约定见 `AGENTS.md`。
