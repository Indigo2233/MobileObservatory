# 相册单张图像编辑

2026-10-25。功能归属为「影像 → 打开图片 → 后期」，复用现有素材库和查看页。代码、自动化证据和设备验收分别记录。

## 当前能力

- JPG、PNG、未压缩 FIT/FITS 单张图像可进入后期。FITS 录像目录继续由现有查看流程管理。
- LP700 转色、一次自动白平衡、RGB 通道增益、亮度 EV、黑场、白场和伽马曲线；提供曲线预览和重置。
- 另存 JPG、PNG、FIT；保存后可直接查看，返回素材库后能找到结果。
- 预览有尺寸上限；保存重新读取原文件，保持原始分辨率。内存不足时显示原因和重试提示。
- 调整参数跨屏幕旋转保留；保存任务由 ViewModel 持有。退出时处理未保存调整，取消保存时清理本次临时文件。

## 像素和 FITS 契约

FITS 输入支持 BITPIX 8、16、32、-32，应用 BSCALE/BZERO。单通道、RGB 三平面和带明确 BAYERPAT 的相机图像均可读取；RGGB、GRBG、GBRG、BGGR 及 XBAYROFF/YBAYROFF 保持传感器相位，彩色 Bayer 使用双线性去马赛克。素材类型取自文件头。

编辑使用浮点样本。普通 FITS 以预览抽样得到公共黑白窗口，窗口和 LP700 增益固定后供全分辨率保存复用；JPG/PNG 及本功能导出的显示 FITS 使用 0–1 范围。亮度、白平衡、转色和曲线都在最终 JPG/PNG 量化之前计算。

FIT 输出为大端 Float32。彩色输出采用 `NAXIS=3`、`NAXIS3=3`，按 R、G、B 平面存储；单色输出为二维。`BUNIT='normalized display'`、`EDITED=T` 和 `COLORSPC` 标识处理域，处理后的非线性显示值写入新文件。原始输入文件保留。

保存文件包含来源、归一化窗口、处理参数、LP700 署名和源 FITS 头信息的 JSON 元数据；FIT 同时嵌入 `EDITMETA` 扩展。主头保留适用的曝光、器材和几何信息。原始科学数据继续由输入文件保管。再次打开显示 FITS 时按其显示域读取。

JPG/PNG 保存于既有 captures/JPG，FIT 保存于 captures/FITS，素材库沿用同一索引。保存使用唯一命名和隐藏临时文件，像素文件和参数文件完成后一起归集。

## 验证记录

- 数值回归：RGB 平面顺序、亚 8 位精度、整数/浮点缩放、四种 CFA 与偏移、曲线/白平衡、非法输入与取消。
- Android 回归源码：三种格式全分辨率导出、预览与 PNG 像素一致性、原片 SHA-256、取消清理、来源变更拒绝、查看页后期与保存后打开。
- 2026-10-25：主代码与 Android 测试源码编译通过；17 项相关 JVM 回归通过，零失败/错误。其中包含 5 项新增数值/文件检查和 12 项既有 FITS、素材库回归。
- `lintDebug` 通过：0 错误、248 警告、13 条信息；未添加 suppression 或 baseline。
- 本轮命令：`gradlew.bat :app:testDebugUnitTest --tests com.indigo.mobileobservatory.media.MediaImageEditingTest --tests com.indigo.mobileobservatory.stacking.StackFitsWriterTest --tests com.indigo.mobileobservatory.stacking.StackFitsPreviewTest --tests com.indigo.mobileobservatory.recording.MediaLibraryTest :app:compileDebugAndroidTestKotlin :app:lintDebug -PstellariumNonCommercial=true -PsequenceEnabled=false -PemulatorTest=false`。
- 当前没有连接 Android 设备；界面操作、旋转保存、内存峰值和外部软件的 RGB FITS 显示仍需设备验收。

本功能的分发版本为 `v1.0.9` / build 50 预发布，状态和安装附件以 GitHub Release 为准。稳定通道继续使用 `v1.0.8` / build 49。
