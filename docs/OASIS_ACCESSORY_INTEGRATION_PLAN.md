# Oasis 电调焦与滤镜轮

> 状态：**USB HID 首期代码已落地，未全部真机验收。** 不是完成项。

## 代码

`AccessoryDeviceManager` → `OasisHidTransport` / 协议 → `OasisHidFocuserController`、`OasisHidFilterWheelController`。Android 不加载 Linux SDK。滤镜轮 BLE **不做**。

电调焦连接先探 Rose 二代状态帧；长度不对再回退一代。滤镜轮槽位数以设备回报为准；UI 0-based、协议 1-based，只在适配器转换。

## 协议（实施与排障仍用）

| 设备 | USB VID:PID | 报文 | 核心命令 |
| --- | --- | --- | --- |
| Oasis Focuser | `338F:A0F0` | 65 字节 HID Report | 相对 `0x35`、绝对 `0x36`、停止 `0x37`、同步 `0x38`、置零 `0x34` |
| Focuser 一代 | 同上 | 配置 18 / 状态 14 | 配置 `0x30/0x31`，状态 `0x32` |
| Focuser Rose 二代 | 同上 | 配置 40 / 状态 40 | 配置 `0x3A/0x3B`，状态 `0x3C` |
| Filter Wheel | `338F:0FE0` | 65 字节 HID Report | 槽位数 `0x50`、名称 `0x51/0x52`、位置 `0x57`、校准 `0x58` |

## 真机

| 设备 | 结果 |
|---|---|
| Rose 二代电调焦 | 2026-08-10：连接、读位置、相对移动、停止通过。固件/手机型号/供电未记 |
| 一代电调焦 | 待测 |
| 7 孔 36mm 滤镜轮 | 待测 |
| 7 孔 2 英寸滤镜轮 | 待测 |

还需要：与相机/赤道仪/ToupTek 附件同时在线；拔线、拒权、连续状态失败后回到断开。
