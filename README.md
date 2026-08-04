# 靛空台（Indigo Observatory）

靛空台是一款在 Android 手机 / 平板上运行的天文设备控制应用。相机、赤道仪、导星与配件各自独立连接，可单独使用，也可组成完整的观测与拍摄台。

仓库目录与 Android 包名仍为 `MobileObservatory` / `com.indigo.mobileobservatory`。

## 能做什么

| 模块 | 能力 |
|---|---|
| **相机** | USB 天文相机实时预览、曝光/增益/ROI、FITS/JPG 拍摄、SER/PSER/MP4 录像、制冷与读出模式 |
| **赤道仪** | 连接、方向键、多档速率、GOTO、回零、同步、站点读写；运动期间全局 STOP |
| **星图** | Stellarium Web 星图、选目标 GOTO、闭环精定（解析 → 同步 → 再 GOTO）、方向键面板 |
| **板解** | 内置 ASTAP，对拍摄帧或载入图像做天体解析，供 GOTO 精定与极轴使用 |
| **极轴校准** | 三点极轴（对齐 NINA TPPA）：自动/手动采点、真极/折射极、连续误差估算、容差自动完成 |
| **导星** | 独立导星相机、多星锁定、校准、滞后/低通等算法、RA/DEC 曲线与 RMS |
| **器材** | 电调焦、电动镜头盖/平场板、电动 CAA（旋转器）、滤镜轮；串口设备可自动探测连接 |
| **回放** | 本地 SER / 图像序列播放 |

相机、赤道仪、导星相机、配件互不绑定：例如可以只连赤道仪做 GOTO，或只连主相机拍摄。

## 支持的设备（摘要）

**相机（USB）**

- ToupTek、ZWO ASI、QHY 等天文相机（公开构建可用）
- 其它机型以实际发布的 APK 能力为准，不保证开源自编译即可使用

**赤道仪**

| 协议 | 连接方式 |
|---|---|
| LX200 / OnStep | 蓝牙 SPP、USB 串口、TCP |
| iOptron V3 | 蓝牙 / 串口 / TCP |
| Sky-Watcher SynScan | SynScan Wi-Fi 等 |

**配件**

- ToupTek EAF 调焦、滤镜轮
- 自定义串口：电调焦、DLC 盖板/平场、ECAA 旋转器等（可自动识别）

具体型号以实际 USB/协议兼容为准；新设备通常通过现有适配层扩展。

## 典型使用流程

**目视 / GOTO**

1. 连接赤道仪，设置或同步站点与时间  
2. 打开星图选目标 → GOTO  
3. （可选）主相机拍摄 → 板解 → 同步 → 再 GOTO，做闭环精定  

**深空拍摄台**

1. 连接主相机与赤道仪；必要时做三点极轴校准  
2. 连接导星相机 → 校准 → 开启自动导星  
3. 主相机拍摄 / 录像；滤镜轮、调焦、CAA 在器材页操作  

**安全提示**

- GOTO 与定距移动前确认周围无碰撞风险，地理位置与时间正确  
- 运动过程中任意页面都有全局 **STOP**；异常或断开时会尽力发全轴停止  

## 界面语言

内置简体中文与英文；系统语言为中文时默认中文界面。

## 构建与安装

需要 **JDK 17**、Android SDK、NDK `25.1.8937393`。

```powershell
.\Build.ps1

# 含 Stellarium Web 星图（非商业分发，需遵守 AGPL）
.\Build.ps1 -NonCommercial
```

产物：`bin/Installer/IndigoObservatory_android.apk`

首次克隆请拉取 submodule（含 libusb）。原生构建相关说明见 `AGENTS.md`。

## 相关文档

| 文档 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | 工程约定、包名、构建与 libusb patch |
| [`TODO.md`](TODO.md) | 已完成项与后续优化（导星增强、targetSdk 等） |
| [`AGPL_SOURCE_DELIVERY.md`](AGPL_SOURCE_DELIVERY.md) | Stellarium Web 非商业交付与源码义务 |
| [`docs/testing/HARDWARE_SMOKE_TESTS.md`](docs/testing/HARDWARE_SMOKE_TESTS.md) | 真机冒烟测试矩阵 |
| [`docs/PHONE_PLATE_SOLVE_PLAN.md`](docs/PHONE_PLATE_SOLVE_PLAN.md) | 手机板解 / Push-to 方案（开放开发中） |

## 技术要点（开发者）

- UI：Kotlin + Jetpack Compose  
- 原生：CMake / JNI（各相机 SDK、自定义 libusb、ASTAP 等）  
- 架构：相机 / 赤道仪 / 配件分模块，经统一 ViewModel 聚合到界面  
- CI：GitHub Actions 跑单元测试与 debug APK 编译（见 `.github/workflows`）  

更细的实现约束以 `AGENTS.md` 为准。
