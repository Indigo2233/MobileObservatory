# 相机 bin 选择与确认

用户选择的总倍率保持不变。优先尝试设备声明支持的最大整除倍率，再选择较小的原生倍率，
最后由应用软件合并补足。例如请求 4×4、设备只支持原生 2×2 时，采用原生 2×2 和软件 2×2。
Bin 1 保持原始采样；连接相机时沿用原有默认倍率。

`CameraBinningCapable.currentHardwareBin` 为兼容已有几何及采集代码，继续表示 SDK 输出的
原生倍率。`binningStateVerified` 表示该状态是否可信；`sensorHardwareBinningActive`
单独表示传感器模式是否得到确认。原生倍率始终为正数，状态未知时停止采集并禁止使用缓存倍率。

## 厂商路径

- **ToupTek**：枚举 `Toupcam_get_BinningNumber/Value/Method`，只接受具有 1×1 恢复模式的
  对称倍率。优先 Average，其次 Add；Skip 保留为独立的抽样方式，不作为像素合并使用。
  通过 `Toupcam_put_Binning` 设置并用 `Toupcam_get_Binning` 核对实际值和方法。
  SDK digital bin (`OPTION_BINNING`) 保持 1，剩余倍率由应用既有软件 bin 处理。
  能力枚举只读；连接时不逐个试写 2/3/4，避免此前部分相机的连接阻塞。
- **ZWO**：读取 SDK 声明的倍率；设备提供可写 `ASI_HARDWARE_BIN` 时优先启用并回读。
  同时检查 ROI、输出倍率及图像类型。没有控制项的旧型号保留 SDK 原生 ROI bin 路径。
- **Player One**：结合 `supportsHardwareBin()` 与可写 `HARDWARE_BIN` 控制项启用传感器模式，
  回读开关、`imageBin`、输出尺寸及像素格式。位深保持用户选择的格式。
- **QHY**：继续使用能力查询及 `SetQHYCCDBinMode`，同时检查 `SetQHYCCDResolution` 的结果。
  该接口没有接入独立的传感器模式回读，界面按 SDK 路径显示。

ZWO、Player One 在重新开始采集时再次优先启用可用硬件模式，并核对格式/几何保持一致。
SDK 拒绝传感器模式而原生输出仍有效时，保留 SDK 路径。硬件模式的 SDK 确认并不说明所有 CMOS
型号都采用 ADC 前电荷合并；实际读噪和动态范围依据具体传感器及厂商实现。

## 几何、CFA 与失败处理

设置新倍率时先停采，保存原模式和 ROI。只有设置成功且回读一致时才发布新状态。
拒绝或不一致时恢复原倍率、数字合并设置和 ROI，再尝试较小倍率/软件补足。
恢复失败会将状态标为未知，保持停采，避免用旧几何解释新帧。

ToupTek 以硬件合并后的 `get_FinalSize` 建立 ROI 坐标，`roiSensorScale` 记录传感器比例。
按当前 SDK RawFormat FourCC 解释 Bayer/单色；已确认的硬件合并 Bayer 输出沿用该输出 CFA，
保留原始有效位深。切换倍率时清理旧帧缓冲和采集代次。

主相机界面显示硬件、SDK、软件及混合路径。导星直接使用独立相机适配器返回的帧，
保留现有导星入口及默认倍率。

## 验证

自动回归覆盖能力枚举无写入、对称倍率、Average/Add 选择、接受写入但回读不符、
数字 bin 关闭、尺寸异常、完整恢复及恢复失败，以及应用最大整除倍率选择和已确认 CFA 的逐像素解码。
Android 构建验证 JNI 与已捆绑官方 SDK 的实际链接；本次未更换任何厂商预编译库。

现场验收应逐型号检查 1/2/4 倍率、ROI、RAW8/高位深切换、实际帧尺寸与硬件开关回读，
并确认色彩、饱和及 USB 吞吐。更换传感器合并方式后需采集匹配读出设置的暗场/平场。
