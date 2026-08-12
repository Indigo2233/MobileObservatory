# 手机主摄解算测试集

测试集原图可能包含位置与拍摄时间，不提交原始 DNG、FITS 或 JPEG 到公开仓库。每张图在受控存储中保存，仓库只保存本清单和经过去标识化的回归结论。

## 首批采集要求

- 设备：约 23–26 mm 等效主摄；记录 Camera2 camera ID 和逻辑/物理镜头关系。
- 画面：4:3 或 3:2；禁止夜景模式、自动 HDR、自动长曝叠加。
- 基线：0.5 s、ISO 800/1600/3200，每个设置连续 8 张；另保留单帧。
- 环境：城市、有月；城市、无月；郊区、有月；郊区、无月。每类至少 6 组。
- 方向：至少覆盖东、南、西、北和高仰角，避开镜头严重眩光区域。
- 真值：使用 ASTAP W08 或 astrometry.net 得到 RA、Dec、旋转、FOV 与残差；记录求解器和版本。

## 元数据格式

每组帧在受控存储中附一个 JSON 文件，字段如下：

```json
{
  "sample_id": "phoneA_20260812_001",
  "device": "manufacturer/model",
  "camera_id": "0",
  "equivalent_focal_length_mm": 24.0,
  "frame_size": [2048, 1536],
  "pixel_format": "MONO16",
  "exposure_seconds": 0.5,
  "iso": 1600,
  "burst_frame_count": 8,
  "utc_mid_exposure": "2026-08-12T14:00:00.000Z",
  "site": { "latitude_deg": 0.0, "longitude_deg": 0.0 },
  "imu_alt_deg": 0.0,
  "imu_az_deg": 0.0,
  "reference_solver": "ASTAP W08",
  "reference_ra_deg": 0.0,
  "reference_dec_deg": 0.0,
  "reference_rotation_deg": 0.0,
  "reference_fov_width_deg": 0.0,
  "reference_fov_height_deg": 0.0
}
```

## 回归记录

在完成 P2/P3 后，对每个样本写入：单帧与堆叠星点数、背景 sigma、局部匹配候选数、匹配星数、残差、最终中心误差和失败原因。只有误解候选被拒绝，才能记为安全失败。
