# 相册单张图像编辑

2026-10-25。功能归属为「影像 → 打开图片 → 后期」，复用现有素材库和查看页。代码、自动化证据和设备验收分别记录。

## 当前能力

- JPG、PNG、未压缩 FIT/FITS 单张图像可进入后期。FITS 录像目录继续由现有查看流程管理。
- LP700 转色、一次自动白平衡、RGB 通道增益、亮度 EV、黑场、白场和伽马曲线；提供曲线预览和重置。
- 另存 JPG、PNG、FIT；保存后可直接查看，返回素材库后能找到结果。
- 预览有尺寸上限；保存重新读取原文件，保持原始分辨率。内存不足时显示原因和重试提示。
- 调整参数跨屏幕旋转保留；保存任务由 ViewModel 持有。退出时处理未保存调整，取消保存时清理本次临时文件。

## 像素和 FITS 契约

FITS 输入支持 BITPIX 8、16、32、-32，应用 BSCALE/BZERO。整数 8/16 位输入按其物理范围编码为 UInt16；32 位整数和浮点输入先以固定大小的缓冲扫描数值范围，再量化到 16 位工作精度。单通道、RGB 三平面和带明确 BAYERPAT 的相机图像均可读取；RGGB、GRBG、GBRG、BGGR 及 XBAYROFF/YBAYROFF 保持传感器相位，彩色 Bayer 使用双线性去马赛克。素材类型取自文件头。

当前工作区的相册后期使用 ShortArray 保存无符号 16 位样本，mono 为每像素 2 字节、RGB 为每像素 6 字节。归一化采用整数查找表，LP700 和白平衡使用 Int/Long 定点运算，曲线使用 65536 级 UInt16 查找表；参数与小规模统计保留浮点标量。公共黑白窗口和 LP700 增益固定后供预览、区域查看及全尺寸导出复用。原始范围与显示窗口分别记录，避免在 LP700 前提前裁掉窗口外的通道值。

当前 FIT 输出为大端 UInt16，采用标准 `BITPIX=16`、`BSCALE=1`、`BZERO=32768` 约定。彩色输出采用 `NAXIS=3`、`NAXIS3=3`，按 R、G、B 平面存储；单色输出为二维。`BUNIT='display intensity'`、`EDITED=T`、`COLORSPC` 以及 `DATAMIN=0` / `DATAMAX=65535` 标识显示数据。PNG 输出为每通道 16 位；JPG 使用每通道 8 位。旧 Float32 显示 FITS 的读取与配方继续兼容。原始输入文件保留，后期输出属于经过非线性处理的显示数据。

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

## 相册检查、编辑恢复与关联管理（后续开发）

本次变更扩展当前相册流程，版本号和发布附件保持原有状态。已发布 v1.0.9 / build 50 使用先前的 Float32 实现，下面的后续改进仍为工作区代码。

### 图像检查

- 查看页支持双指缩放、拖动、双击和显式适应画面 / 1:1 按钮。1:1 加载成功后，一个源像素对应一个屏幕物理像素，放大后系统返回和工具栏返回均先恢复适应画面。
- 进入编辑后提供原片与调整结果的分界对比，分界位置通过滑块调整；两侧共用缩放和平移。
- RGB 直方图采用对数计数，并显示预览中任一通道达到 255 的抽样比例。高亮溢出遮罩覆盖当前显示结果。这些指标描述显示预览，传感器饱和度须根据原始科学数据判断。
- 细节查看按当前可见区域及少量边缘读取，并根据缩放选取采样级别。1:1 读取源像素；区域块缓存限制为 4 项 / 24 MiB，当前显示与在途分配另计。失败保留预览并可重试。
- JPG/PNG 的预览与导出共用 EXIF 方向处理。缩放、分界和检查开关在旋转后恢复。

### 可恢复编辑

- 撤销 / 重做由图像任务的 ViewModel 持有，保留最多 80 步。一次连续滑块手势记为一步，创建新分支时清除旧的重做分支。
- 保存配方将来源指纹、归一化窗口、LP700 增益及全部编辑参数原子写入应用私有 `files/image-recipes/`。保存图像时同时更新原片配方。退出有未保存参数时，可保存配方并离开。
- 重新打开原片恢复最近保存的配方；重新打开导出版本使用该版本记录的配方。恢复从已索引的原片读取样本，校验路径、长度和修改时间。原片不可用时提示，并将当前导出图作为新的编辑来源。
- 导出参数文件和 FITS `EDITMETA` 均包含配方。既有版本的导出缺少 `lpGains` 时，从原片重新估计该值。
- 已保存配方可跨应用进程重启恢复；未保存的历史由当前 ViewModel 保持，进程被系统回收时可能丢失。卸载应用会删除私有配方。

### 素材关联与批量操作

- 素材库默认按关联分组，可切回平铺。原始录像、线性 FITS、叠加显示 PNG 和编辑版本在同一组显示，并标注素材角色。分类筛选命中组内任一成员时，显示该组全部关联素材。
- 来源关系只解析到当前可见媒体索引；活动录制、隐藏文件和临时文件继续排除。同名且来源不明确的文件保持独立。
- 多选支持选择整组、导出 ZIP 和 Android 系统分享。ZIP 保留所选原文件、图像参数 JSON 和 `manifest.json` 组关系清单；FITS 录像目录包含其 FIT/FITS 帧。导出保持所选文件格式。
- 归档采用流式读写和隐藏临时文件；来源变化、失败或取消时清理临时输出。系统保存选择器指定目标，任务由 ViewModel 持有并可取消；目标文档写入中断时请求文档提供方删除未完成文件。
- 普通文件通过只读 FileProvider URI 分享；包含目录时先生成 ZIP 再打开系统选择器。归档缓存限定为 `cache/media-share/`，48 小时后在后续归档时清理。
- 回收站同时移动参数文件，记录原始位置；恢复时检查应用媒体根目录和同名冲突，编辑 FITS 可恢复到原 captures/FITS 目录。

本批自动化结果与设备验收记录在下方追加；触控、横竖屏、系统选择器、接收应用和大图内存仍需 Android 设备验证。

### 本批验证记录

- 29 项相关 JVM 回归通过，0 失败、0 错误、0 跳过：包含 9 项新增相册流程测试，以及既有媒体导入、数值编辑、媒体索引和 FITS 检查。
- 新增覆盖：滑块手势撤销与分支历史、任务检查点恢复、配方数值域及非法参数、配方原子替换和来源变更、原片 / 叠加 / 编辑关联、同名歧义、ZIP 内容与关系清单、取消与来源变化、回收站参数和原位置恢复、显示直方图。
- 主代码和 Android 测试源码编译通过；新增 Android 原始像素查看 / 配方恢复与 PNG 全分辨率像素一致性检查。Android 测试尚未在设备上执行。
- 最终 `lintDebug` 通过：0 错误、250 警告、13 条信息，未添加 suppression 或 baseline。
- 最终回归命令：`gradlew.bat :app:testDebugUnitTest --tests com.indigo.mobileobservatory.media.* --tests com.indigo.mobileobservatory.recording.MediaLibraryTest --tests com.indigo.mobileobservatory.util.FitsIntegerPreviewTest --tests com.indigo.mobileobservatory.stacking.StackFitsWriterTest --tests com.indigo.mobileobservatory.stacking.StackFitsPreviewTest :app:compileDebugAndroidTestKotlin :app:lintDebug -PstellariumNonCommercial=true -PsequenceEnabled=false -PemulatorTest=false`。
- `adb devices -l` 当前为空。真机验收应覆盖双指 / 双击 / 拖动和两种返回、编辑对比与夜间直方图、旋转期间的历史与保存、系统保存取消与分享接收、多种尺寸 FITS/JPG/PNG 内存边界。

## 16 位与内存改进

- 相册后期移除整图 FloatArray。16 位工作缓冲中的 RGB 每像素 6 字节，较 RGB Float32 的 12 字节减半；统计、增益和 UI 参数使用少量浮点标量。
- FITS 读取通过有界行缓存读取区域，Bayer 在源图坐标处理并读取区域外的插值邻域。24 MP 源图的 512×512 RGB 区域缓冲为 1.5 MiB。
- 1:1 / 缩放只解码可见区域和边缘。JPG / 8 位 PNG 使用 BitmapRegionDecoder；16 位 PNG 支持灰度、RGB、Alpha、五种行滤波和 Adam7。区域通过 EXIF 映射，处理器使用与整图相同的窗口和系数。
- PNG / FIT 输出分 32 行处理，保持原始尺寸和 16 位通道精度。FIT 通过定位写入 R/G/B 三平面，PNG 通过分块 IDAT 流式压缩。JPG 仅保留编码所需的全尺寸原生 ARGB 位图和小工作带。
- 内存检查分别计算 ART 托管缓冲和原生 Bitmap，原生预算参考系统可用内存及保留阈值。`Runtime.maxMemory()` 仅用于托管堆预算。
- 小尺寸历史叠加 FITS 的只读显示优先打开同组已保存的显示 PNG；缺少对应 PNG 时继续复用叠加模块保存的高级配方。叠加累积及该历史显示回退路径沿用原有科学处理精度；进入相册编辑后使用本节的 16 位链路。
- 新增 JDK ImageIO 独立编码的 PNG16 灰度 / RGB 普通及 Adam7 样本，生成命令见 `app/src/test/resources/media-u16/README.md`。

数值量化与实际内存峰值分别验收：自动化检查位深、码值、来源保持和区域一致性，设备验收继续覆盖峰值内存、触控拖动、旋转、取消与外部 FITS/PNG 软件打开。

本轮数值检查包含 16 位全码值 FITS 往返、独立解析 PNG 输出与外部 ImageIO 样本、透明度 / tRNS / Adam7、定点颜色与窄窗口亮星比例、Bayer 区域边界、EXIF 仿射映射、24 MP 源的有界读取和托管 / 原生预算隔离。预览副本、直方图和高亮遮罩同样检查分配预算；内存不足时保留画面并显示可恢复错误。

本轮最终验证：41 项相关 JVM 回归通过，0 失败 / 错误 / 跳过；主代码及 Android 测试源码编译通过；`lintDebug` 通过，0 错误、250 警告、13 条信息。保留原片、三格式全尺寸导出、区域与 PNG 像素一致性及旋转 / 编辑流程的 Android 测试源码同步更新。当前未连接设备，Android 用例执行和实际峰值内存仍待真机验收。本轮未调整版本号或重新分发 APK。

## 叠加成片与线性来源（2026-10-25）

- 叠加显示 PNG 改为每通道 16 位流式导出。只分配一个浮点处理行和一个 UInt16 行带，移除导出时的全尺寸 ARGB 位图；科学叠加累积和线性 FITS 沿用现有科学处理精度。
- FITS、PNG、参数 JSON 全部先写入隐藏临时文件，完整写入后依次发布。正常取消和失败清理本次输出，同名冲突保留已有文件。跨目录多文件发布仍有短暂的可见时间差，进程或设备突发崩溃后的完整组恢复另行验收。
- 保存结果卡可直接打开线性 FITS 继续编辑。相册打开叠加 PNG 后，编辑面板可进入同组线性叠加原片；来源只从媒体索引解析，核对双向文件名及来源身份，歧义时隐藏入口。
- 线性来源沿用相册的 16 位编辑、配方和三格式导出流程。叠加高级配方仍保存在原结果元数据中；恢复整套高级叠加处理任务继续列为后续工作。
- 新增逐码值 PNG16 检查、导出失败/取消回滚及来源解析检查。手机内存峰值、两处入口的触控/返回和外部软件显示仍需设备验证。

## 应用内选图与单张分享（2026-10-25）

- 解析页顶部提供应用内影像和设备文件两个来源。应用内列表复用媒体索引，包含 JPG/PNG、单张 FITS、叠加结果及 FITS 录像中的已完成单帧；序列帧显示所属目录，隐藏文件、未完成文件及正在写入的文件或目录排除。取消选择保留当前文件；成功选择后清除旧解析结果。系统文件读取失败保留原选择并显示错误。
- 相册列表中单文件旁可直接打开系统分享；查看页提供分享原始文件和分享显示预览。预览使用当前显示处理效果，编码为预览分辨率、每通道 8 位 PNG，保留原图的科学数据和分辨率。全尺寸及高位深传递使用原文件分享；全尺寸处理结果继续通过原有另存流程生成。
- 单文件和批量分享共用 `FileProvider` 内容 URI、`ClipData` 与临时只读授权。原始 FITS 的分享请求、`ClipData` 和文件 URI 类型统一使用 `application/octet-stream`，包含 FITS 的混合多选同样按文件附件分享，保持原始文件名、扩展名和字节内容。PNG 预览位于缓存的 `media-share/` 下，采用独立文件名，失败或取消清理本次未完成输出。预览文件遵循 Android 缓存回收策略。
- 已补充媒体索引、单张/多张分享 MIME 与可读流、PNG 像素及原片保持、取消清理、选图取消及恢复、单张分享入口、解析返回原图等回归。设备未连接，Android 测试执行、系统选择器取消、第三方软件接收和真机旋转验收继续单列。

本轮验证：1153 项 JVM 测试通过，0 失败 / 错误 / 跳过；主代码及 Android 测试源码编译通过；`lintDebug` 为 0 错误、297 警告。本轮新增的 7 项 Android 用例尚未在设备上执行。标准构建保持 `SEQUENCE_ENABLED=false` 和 `EMULATOR_TEST=false`。
