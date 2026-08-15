# 方案与文档索引

状态约定与 `TODO.md` 一致：`[ ]` 待办、`[~]` 进行中、`[x]` 代码已落地（真机可能未完）、`[-]` 暂不实施。

已完成设计、只剩真机回归的长文放在 [`archive/`](archive/)。这里只留仍指导开发的文档和短状态页。

## 进行中

| 文档 | 状态 | 还缺什么 |
|---|---|---|
| [`DSLR_MIRRORLESS_CAMERA_PLAN.md`](DSLR_MIRRORLESS_CAMERA_PLAN.md) | `[~]` M1–M3 代码已接入 | Nikon D5100 PTP：真机 go/no-go 仍缺；Canon/Sony、RAW/B 门未做 |
| [`PHONE_PLATE_SOLVE_PLAN.md`](PHONE_PLATE_SOLVE_PLAN.md) | `[~]` | 宽场解算器、夜间真机 go/no-go、M5–M8 |
| [`PHONE_WIDE_FIELD_SOLVER_IMPLEMENTATION.md`](PHONE_WIDE_FIELD_SOLVER_IMPLEMENTATION.md) | `[~]` | 持久化索引、陀螺提示、相对跟踪、实拍 P6 |
| [`wanderer-support-plan.md`](wanderer-support-plan.md) | `[~]` Phase 1 代码已有 | 旋转器真机；Cover / 雪花轮 / Box 未做 |
| [`OASIS_ACCESSORY_INTEGRATION_PLAN.md`](OASIS_ACCESSORY_INTEGRATION_PLAN.md) | `[~]` USB HID 已接入 | 一代电调焦、两款滤镜轮真机；BLE 不做 |

## 代码已落地、待真机

| 文档 | 代码 | 还缺什么 |
|---|---|---|
| [`PLAYERONE_CAMERA_PLAN.md`](PLAYERONE_CAMERA_PLAN.md) | M0–M4 | 单机真机基本通过；双路无第二台暂缓。升级 SDK 前看 [archive SONAME](archive/PLAYERONE_CAMERA_PLAN.md) |
| [`CAMERA_NATIVE_GAIN_AND_ISO_PLAN.md`](CAMERA_NATIVE_GAIN_AND_ISO_PLAN.md) | M1–M4 | 天文相机增益真机。单反 ISO 改走 DSLR 方案 |
| [`EXPOSURE_CONTROL_FIX_PLAN.md`](EXPOSURE_CONTROL_FIX_PLAN.md) | M1–M4 | LX 量程与输入框真机 |
| [`DEVICE_UI_AND_SETTINGS_REDESIGN_PLAN.md`](DEVICE_UI_AND_SETTINGS_REDESIGN_PLAN.md) | 阶段 1–4 | 阶段 5 真机回归 |

## 过程记录（archive）

| 文档 | 说明 |
|---|---|
| [`archive/PLAYERONE_SDK_VENDOR_REQUEST.md`](archive/PLAYERONE_SDK_VENDOR_REQUEST.md) | 已解决的厂商阻塞项原信 |
| 上表各计划的全文 | 设计过程、已勾选里程碑、接口对照表 |

## 发布与质量

| 文档 | 内容 |
|---|---|
| [`QUALITY_STATUS.md`](QUALITY_STATUS.md) | 自动化 / 真机 / 板解成熟度 |
| [`RELEASE_PROCESS.md`](RELEASE_PROCESS.md) | 签名与发布门 |
| [`testing/HARDWARE_SMOKE_TESTS.md`](testing/HARDWARE_SMOKE_TESTS.md) | 真机冒烟矩阵 |
