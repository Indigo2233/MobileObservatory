# WF-09：光路配置与实际相机几何

## 偏心 ROI 的当前框和到位边界

`ConnectedOpticsGeometry` 现在保留实际光轴参考像素 `axisPixelX / axisPixelY`（0-based，与 ROI 宽高同坐标域）及 `axisKnown`。生产快照通过传感器全尺寸、实际 ROI 和适配器的 `roiSensorScale` 映射，不把 ROI 中心冒充传感器／主镜轴。未知、范围外或恰在裁切边界的轴不能用于到位验证。

相机验证视场由实际像元、焦距及光轴到左／右／上／下四边的最近距离计算保守内接矩形。4000px 传感器取 `x=1900, width=1000` 时，轴只距左边 100px，可验证范围不能仍按 ROI 中心向左扩展 250px。预设相机目录和目标构图只作规划，不能扩大实际裁切范围。居中全幅及居中裁切保留正常验证能力，ROI／bin 改动不会自行作废机械安装关系。

偏心、未知或与规划尺寸不符的当前实体矩形隐藏，并显示简短裁切／未确认提示；目标虚线构图保留。实际光学轴可继续提供跟随来源，不能因为有方向解就宣称当前矩形位于该轴中心。相关数值回归在 `CroppedOpticalFieldTest`，包括偏心假绿、居中、未知／边界、硬件 bin 同角范围及目录预设隔离；真机 SDK ROI/bin 和裁切场景仍需现场验收。

ToupTek 数字 bin 的 ROI 使用原始传感器坐标，`roiSensorScale=1`；ZWO 硬件 bin 的 ROI 使用 bin 后网格，`roiSensorScale=hardwareBin`，像元随该尺度相乘。连接几何按这个 ROI 域计算物理覆盖角；帧交付尺寸另外由采集 SDK 的最终输出确认，不能从相机原始 resolution 直接代替。

ToupTek 采集现在用 `Toupcam_get_Size` 的原分辨率保留安全 pull 容量，使用 V3 FrameInfo 的实际宽高并核对 `Toupcam_get_FinalSize`，显式采用紧密 row pitch。未匹配的旧尺寸／截断容量拒绝交付。全幅直接沿用原池的所有权；ROI／数字 bin 用会话私有大 scratch 与紧凑输出池，scratch 不交给预览／录像，消费者不携带无效尾部。`ToupcamFrameLayoutTest` 覆盖 bin／ROI、原图、非 DIB 行宽、旧尺寸不匹配、截断／溢出以及 scratch 重用不污染输出；冻结库的相关导出已静态确认，JNI 实际链接由统一 ARM64 构建验收，现场相机仍待实测。

本次把星图的主镜、导星配置与各自相机接线分开，并将配置读取、视场和推镜验证边界收敛到 `astro/PointingOpticsConfiguration.kt`。它补的是实现和可复现回归，不代表硬件下标定精度已验收。

## 共享接口

```kotlin
PointingOpticsConfiguration.load(
    prefs: OpticsPrefReader,
    primaryGeometry: ConnectedOpticsGeometry? = null,
    secondaryGeometry: ConnectedOpticsGeometry? = null
)
configuration.sensorsFor(train)
configuration.computation(train)
configuration.primaryReferenceFocalLengthMm(): Float?
configuration.verificationField(
    selection: PointingOpticsSelection?,
    altitudeUpRollDeg: Double? = null
): VerificationField?
configuration.changeTo(next, train): PointingOpticsChange?
```

`AndroidOpticsPrefs` 是同一 `mobile_observatory` SharedPreferences 的适配器。VM、标定向导、星图和推镜应读同一配置，不再另存默认 500 mm 或 1° 验证视场。`PointingOpticsSelection` 包含用户明确选择的 `PRIMARY / SECONDARY` 和 `EYEPIECE / SENSOR`，不由激活的规划框自动推断。

`pointingOpticsRevision` 将外部任务的参数/确认修改同步到仍保留在栈下的星图和原生 sheet。编辑器按各实际焦距/像元/尺寸更新字段，即使两光路使用相同的「已连接」ID，切换后也显示对应相机的参数。

初始配置仍可画规划视场。用户在视场设置中按「使用这套配置」后，才写 `star_map_primary_pointing_optics_confirmed` / `star_map_secondary_pointing_optics_confirmed`，允许提供工作视场。编辑组合清除对应确认标记；无有效像元/尺寸/焦距、未确认、终端与当前组合不符时，验证视场返回 `null`。相机矩形缺少地平切平面 roll 时不能验证到位；不把目标构图角或未知默认角当成当前像面角。

主镜参考解算的焦距从已确认 PRIMARY 望远镜配置获取，未知返回 `null`，由 ASTAP 自动视场（0）处理。旧 `plate_focal_length_mm` 保持导星解算提示的原约定：只在 SECONDARY + SENSOR 且用户明确修改望远镜/焦距时写入。切换终端、换传感器、显隐规划框、主镜编辑和普通快照保存均不能顺手写它。旧 `star_map_*` 光路数据保留为主镜，默认激活光路仍是导星。

两光路即使选用同一设备库模型，用户实际焦距也各自保存为 `OpticsTrainConfig.telescopeFocalLengthOverrideMm`，有效焦距统一通过 `train.focalLengthMm(telescopes)` 获取。新增 `star_map_primary_telescope_focal_override_mm` / `star_map_secondary_telescope_focal_override_mm` 保存这些选择；首次未明确配置仍保持未确认。修改共享库模型时，另一光路保留原有效焦距；修改主镜不会改导星解算提示键。

不含 Stellarium 的原生星图也有「光路配置」，复用同一 `StarMapFovSheet` 和器材参数编辑，隐藏该页面不适用的绘图开关和马赛克控件。不存在只有收费/非商业星图版本才能确认工作光路的限制。

标定向导在首步就地确认 PRIMARY 的同一终端配置，再开始真实采样；推镜也从当前关系模型选择同一 PRIMARY 配置。两处复用该 sheet，锁定主镜/当前终端，不再让新手重新选择另一条光路。手填角度保留在高级视场覆盖层，可一键恢复共享配置。无效参数草稿禁确认，关闭配置层只回当前任务。

向导冻结 phone/primary 安装 revision；配置变化清本轮样本、验证和手眼拟合，保存由仓库锁内原子检查。只改变观测方向不会当作换安装而丢掉跨方向训练；直接解算发布仍使用同时包含方向代次的强检查。

## 实际几何与局部失效

| 变化 | 处理 |
|---|---|
| 主相机连接/ROI | PRIMARY 已连接传感器使用主相机实际信息；SECONDARY 不借用它 |
| 导星相机连接/ROI | SECONDARY 使用独立 guide 会话实际信息；无导星相机不自动套用主相机 |
| 手填或设备库传感器 | 保留原配置，可离线计算规划和已确认视场 |
| ROI/bin | 更新受影响视场；不删除光轴关系 |
| 换望远镜、改变该光路有效焦距 | 发 TELESCOPE，局部失效受影响光路关系 |
| 切换目镜/像面终端、换相机安装身份 | 发 IMAGE_INSTALLATION，局部失效受影响光路关系 |
| 换目镜焦距/视角、设备改名、修订口径信息 | 仅更新视场或标签，不删除光轴关系 |
| 屏幕旋转 | 不参与光学身份，也不产生关系失效 |

`ConnectedOpticsGeometry` 用实际 `currentRoi.width/height`，`binning` 参数表示 **ROI 坐标至原始像元的比例**，由 `Camera.roiSensorScale` 提供。ZWO/Player One/QHY 的 ROI 位于硬件 bin 网格，比例为硬件 bin；Toup ROI 始终是原始分辨率，数字 bin 时比例仍为 1。软件预览降采样不扩大视场，不能取缩小的 Bitmap 尺寸，也不能一概乘应用 bin。

星图回调为 `onOpticalPathInvalidated: (OpticsTrainId, PointingOpticsChange) -> Unit`，壳将 train 对应到实际 opticalPathId 后接 `PointingRepository.invalidateOpticalPath`。只删除依赖该光路的关系，不清空其他设备的标定。

## 架台动作

星图目标卡、观测面板和原生目标库按 `PointingMountCapabilities` 分别显示读取、GOTO、SYNC、手动和归零。SYNC 不依赖 GOTO。只读连接的目标主动作是推镜；不提供坐标写入动作和手动方向键。STOP 保留，展开层的架台模型诊断按真实读取能力使用。待确认期间丧失对应能力时关闭确认框，执行回调前再次检查连接、能力和 busy。

## 当前光路跟随

星图的「跟随当前光路 / 居中到当前光路」使用该光路的可信轴坐标，独立于架台模型回读。无架台时已标定手机/辅助光路同样可用；轴方向有效但矩形 roll 未知时可以跟随轴，不画假矩形。保留光路、源类别、绝对参考年龄与估计标签。

`setPointingCoordinates` 给 JavaScript 传独立 RA/Dec、ICRF/JNOW、绝对和估计时间；架台坐标只给模型诊断。Android 与 JavaScript 均拒过龄（绝对 30 秒/估计 3 秒），失效后停止跟随、显示「更新指向」；新参考到来后由「继续跟随」明确恢复。不能回退架台、屏幕中心，也不能静默恢复。默认 off、16 px 单指拖动暂停、整段 pinch 不暂停、手指未全离开时更新不抢居中。

指向源必须仍在实际发布合格观测/传播；离开手机引导后不能把旧坐标作为持续实时流，过龄后按上述方式停止。

## 共享观测地点

`PhoneSiteProvider` 保存实际取得或用户明确填写的地点，兼容已有极轴地点；没有记录时返回未知，不生成 (0°, 0°) 默认地点。手填地点可在无定位权限时用于观测。星图两种构建提供同一就地地点入口：使用手机位置时才请求授权，手填入口逐层展开，缺权限/定位失败可恢复；打开星图不主动弹权限。

星图使用共享保存地点，暂无共享记录时可用明确的架台地点，并标出来源。未知地点时隐藏真实本地地平/子午坐标与可信当前框、停止光路跟随，不能用引擎默认地点冒充当前所在地。按 2026-10-14 用户补充，独立的「模拟地景」开关允许无地点时显示明确标注的视觉预览；罗盘/陀螺仪跟随只用于手机姿态浏览，不发布镜筒观测或已验证状态。向导和手机引导读相同保存地点，并在修改后更新自己的站点上下文。坐标选择不写入架台控制器。

## 回归与实测边界

- `PointingOpticsConfigurationTest`：9 个 JVM 场景，独立主辅传感器、硬件 bin/ROI、未知配置/终端/roll、目镜圆形视场、局部安装失效、主镜焦距隔离、旧提示键明确写入、无效几何、共享库编辑与各光路焦距持久化。
- `StarMapMountCapabilityInstrumentedTest`：3 个真实 Compose 场景，只读目标卡、只读面板保留 READ/STOP、SYNC 独立于 GOTO 和动态撤权。
- `PointingOpticsSheetInstrumentedTest`：真实复用 sheet 切换主辅的不同实际传感器，明确确认持久化、编辑导星焦距仅失效导星。
- `ObservatoryWorkflowInstrumentedTest.nativeMapCanConfigureTheSameOpticalSetupWithoutStellarium`：只在不含 Stellarium 的构建执行，验证真实壳中打开配置和系统返回仍留在星图。
- `StarMapPointingFollowInstrumentedTest`：3 个真实屏幕/独立时钟场景，无架台可信手机轴（含未知矩形 roll）、过龄停止与恢复入口、切光路仅取对应辅助源/缺失不得回退，固定目标保持。
- `scripts/tests/star-map-interaction.test.cjs`：13 项实际集成脚本测试已通过，含独立 epoch/光路跟随、过龄清空不回退、拖动暂停、pinch 剩余一指移动不误停、无地点不冒充本地地平。JS 集成源交付声明同步在 `AGPL_SOURCE_DELIVERY.md`。
- `PointingRepositoryInstrumentedTest` 新增两项保存门禁：安装变化拒保存/不写证据、只换方向可保存真实训练记录。
- `ObserverSiteInstrumentedTest` 三项和 `ObserverSiteDialogInstrumentedTest` 一项：无默认地点、手填无需 GPS/合法性/旧地点兼容、旧自动 GPS 不覆盖明确选择、真实 dialog 禁提交空值和越界值。
- 测试执行结果由最终统一 Gradle/Android 报告记录；本文件不把尚未执行的新增测试写成通过。
- 硬件下主辅图像视场、安装换位后的标定失效和首次使用可发现性仍需真实相机/架台及新手实测，不由静态测试替代。
