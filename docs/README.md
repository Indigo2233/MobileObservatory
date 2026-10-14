# 方案与文档索引

状态约定与 `TODO.md` 一致：`[ ]` 待办、`[~]` 进行中、`[x]` 代码已落地（真机可能未完）、`[-]` 暂不实施。

已完成设计、只剩真机回归的长文放在 [`archive/`](archive/)。这里只留仍指导开发的文档和短状态页。

2026-10-13 的文档修订将交互质量列为下一阶段 **P0 产品主线**，2026-10-14 已接入第零批基线与第一批主要代码路径；见 [WF 批次验收](WF_PHASE_0_1_ACCEPTANCE.md)。所有 UI 工作先读 [交互规范](INTERACTION_GUIDELINES.md)：任务分类、四工作区、层级/返回、就地操作、状态和渐进呈现。设备安全、崩溃、数据丢失及科学正确性修复仍可先行。

阅读顺序：交互规范 → [观测流程实例](OBSERVING_WORKFLOW_DESIGN.md) → [TODO 的 WF-00–WF-12](../TODO.md)；算法/坐标再查 [指向方案](PHONE_PLATE_SOLVE_PLAN.md)。`DESIGN_NOTES` 保留具体决策，归档中的旧入口/完成记录不覆盖新约定。设计文本、原型、代码和真机状态分别记录。

## 进行中

| 文档 | 状态 | 还缺什么 |
|---|---|---|
| [`WF_PHASE_0_1_ACCEPTANCE.md`](WF_PHASE_0_1_ACCEPTANCE.md) | `[~]` 首批代码与分层证据 | 真实新手、物理设备和系统手势验收 |
| [`WF_REMAINING_IMPLEMENTATION.md`](WF_REMAINING_IMPLEMENTATION.md) | `[~]` 后续整合与验证总账，含相机/制冷闪退 | 最新代码回归、独立实拍与两名新手验收；本轮状态以此为准 |
| [`INTERACTION_GUIDELINES.md`](INTERACTION_GUIDELINES.md) | `[~]` P0 规范/基线/原型已有，首批代码已接入 | 真人找入口与现场路径预算验证；复杂编辑器继续迁移 |
| [`OBSERVING_WORKFLOW_DESIGN.md`](OBSERVING_WORKFLOW_DESIGN.md) | `[~]` P0 首批主要路径已接入 | 分层自动化/真机、新手验收；UX-06–08 等后续能力 |
| [`DEVICE_UI_AND_SETTINGS_REDESIGN_PLAN.md`](DEVICE_UI_AND_SETTINGS_REDESIGN_PLAN.md) | `[~]` 状态就地控制与清单→详情已接入 | 多类型设备连接、按住释放及拔插真机回归 |
| [`DSLR_MIRRORLESS_CAMERA_PLAN.md`](DSLR_MIRRORLESS_CAMERA_PLAN.md) | `[~]` M1–M3 代码已接入 | Nikon D5100 PTP：真机 go/no-go 仍缺；Canon/Sony、RAW/B 门未做 |
| [`PHONE_PLATE_SOLVE_PLAN.md`](PHONE_PLATE_SOLVE_PLAN.md) | `[~]` 真实标定、完整姿态、多源与停手验证已有实现，产品仍实验性 | 整合回归、夜间独立精度与新手验收 |
| [`PHONE_WIDE_FIELD_SOLVER_IMPLEMENTATION.md`](PHONE_WIDE_FIELD_SOLVER_IMPLEMENTATION.md) | `[~]` 持久化索引、姿态/镜面和有界采集已有实现 | 实拍 P6、低内存/功耗和主相机并发证据 |
| [`wanderer-support-plan.md`](wanderer-support-plan.md) | `[~]` Phase 1 代码已有 | 旋转器真机；Cover / 雪花轮 / Box 未做 |
| [`OASIS_ACCESSORY_INTEGRATION_PLAN.md`](OASIS_ACCESSORY_INTEGRATION_PLAN.md) | `[~]` USB HID 已接入 | 一代电调焦、两款滤镜轮真机；BLE 不做 |
| [`STAR_MAP_DUAL_FOV_PLAN.md`](STAR_MAP_DUAL_FOV_PLAN.md) | `[~]` 双光路/投影/位置角已有 | 可信指向来源、当前/构图区分、辅助光路关系和目镜预设库 |
| [`DSO_SEQUENCE_PLAN.md`](DSO_SEQUENCE_PLAN.md) | `[~]` M1–M6 代码已落地 | M7 夜间验收未做。分支 `feature/dso-sequence` |
| [`NINA_ADVANCED_SEQUENCER_PLAN.md`](NINA_ADVANCED_SEQUENCER_PLAN.md) | `[~]` N0–N5 代码已落地 | 子帧不实施；N6 真机 |

WF 本轮实现细节与证据边界：

- [设置草稿与跨工作区任务](WF12_SETTINGS_IMPLEMENTATION.md)、[外部媒体与系统返回](WF_EXTERNAL_MEDIA_ACCEPTANCE.md)。
- [光路配置与可信视场](WF09_OPTICS_CONFIGURATION.md)、[辅助相机标定/推镜](WF_AUXILIARY_POINTING.md)、[镜面 hand-eye](MIRROR_HAND_EYE_CALIBRATION.md)。
- [架台能力与只读访问](WF08_MOUNT_CAPABILITIES.md)、[可选自动采样](WF_AUTOMATIC_CALIBRATION_ACCEPTANCE.md)。
- [手机采集资源与多帧时间边界](WF_PHONE_RESOURCE_BOUNDARIES.md)、[持久化索引及独立实拍证据](WF10_INDEX_AND_EVIDENCE.md)。

## 代码已落地、待真机

[相机色彩与 SER CFA 修复](CAMERA_COLOR_AND_SER_CFA.md)：普通可见光默认、保留 Bayer 相位的显示/软件 bin、明确的原始排列与校准兼容性；AE676C 色卡待真机复拍。

| 文档 | 代码 | 还缺什么 |
|---|---|---|
| [`PLAYERONE_CAMERA_PLAN.md`](PLAYERONE_CAMERA_PLAN.md) | M0–M4 | 单机真机基本通过；双路无第二台暂缓。升级 SDK 前看 [archive SONAME](archive/PLAYERONE_CAMERA_PLAN.md) |
| [`CAMERA_NATIVE_GAIN_AND_ISO_PLAN.md`](CAMERA_NATIVE_GAIN_AND_ISO_PLAN.md) | M1–M4 | 天文相机增益真机。单反 ISO 改走 DSLR 方案 |
| [`EXPOSURE_CONTROL_FIX_PLAN.md`](EXPOSURE_CONTROL_FIX_PLAN.md) | M1–M4 | LX 量程与输入框真机 |

## 过程记录（archive）

| 文档 | 说明 |
|---|---|
| [`archive/PLAYERONE_SDK_VENDOR_REQUEST.md`](archive/PLAYERONE_SDK_VENDOR_REQUEST.md) | 已解决的厂商阻塞项原信 |
| 上表各计划的全文 | 设计过程、已勾选里程碑、接口对照表 |

## 设计备忘

| 文档 | 内容 |
|---|---|
| [`DESIGN_NOTES.md`](DESIGN_NOTES.md) | 交互优先约定与具体产品/协议规则；待实施项标注 WF，遵循全应用交互规范，并保留 STOP、零位、EQ/地平和板解光学约定 |

## 发布与质量

| 文档 | 内容 |
|---|---|
| [`QUALITY_STATUS.md`](QUALITY_STATUS.md) | 自动化 / 真机 / 板解成熟度 |
| [`RELEASE_PROCESS.md`](RELEASE_PROCESS.md) | 签名与发布门 |
| [`testing/HARDWARE_SMOKE_TESTS.md`](testing/HARDWARE_SMOKE_TESTS.md) | 真机冒烟矩阵 |
