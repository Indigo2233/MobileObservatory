# 曝光控制（输入框 + LX 模式）

> 状态：**M1–M4 代码已落地。** 缺陷分析与实施记录见 [`archive/EXPOSURE_CONTROL_FIX_PLAN.md`](archive/EXPOSURE_CONTROL_FIX_PLAN.md)。

## 还剩什么

- 天文相机：开长曝后上限等于硬件值，不会被裁成 300 s。
- 工业相机 overlay：长曝走软件叠加，进度 `n/N`。
- 输入 `12.5s` / `30ms` 真机确认。
