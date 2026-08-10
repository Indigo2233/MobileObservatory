# Oasis 电调焦与滤镜轮接入

状态：已实施 USB HID 首期接入；Rose 二代电调焦已完成连接与控制真机验证，待验收一代电调焦、7 孔 36mm 滤镜轮和 7 孔 2 英寸滤镜轮。

## 范围

- Oasis Focuser 一代和 Oasis Focuser Rose 二代。
- Oasis Filter Wheel 7 孔 36mm 与 7 孔 2 英寸。应用按设备回报的槽位数量工作，不依赖机械规格。
- Android USB Host 直连，设备在附件列表中独立显示和授权。

本期不包含滤镜轮 BLE。Oasis Mate 1.3.0 仅发现电调焦 BLE 实现；滤镜轮官方手册说明 USB HID 通信。

## 协议依据

| 设备 | USB VID:PID | 报文 | 核心命令 |
| --- | --- | --- | --- |
| Oasis Focuser | `338F:A0F0` | 65 字节 HID Report | 相对移动 `0x35`、绝对移动 `0x36`、停止 `0x37`、同步 `0x38`、置零 `0x34` |
| Focuser 一代 | `338F:A0F0` | 配置 18 字节，状态 14 字节 | 配置 `0x30/0x31`，状态 `0x32` |
| Focuser Rose 二代 | `338F:A0F0` | 配置 40 字节，状态 40 字节 | 配置 `0x3A/0x3B`，状态 `0x3C` |
| Oasis Filter Wheel | `338F:0FE0` | 65 字节 HID Report | 槽位数 `0x50`、名称 `0x51/0x52`、位置 `0x57`、校准 `0x58` |

实现以公开 INDI 驱动与厂商 Linux SDK 的可观察协议行为为依据，Android 端不加载 Linux SDK。

## 实现结构

```
AccessoryDeviceManager
  ├─ OasisHidTransport
  │   ├─ OasisHidProtocol
  │   ├─ OasisFocuserProtocol
  │   └─ OasisFilterWheelProtocol
  ├─ FocuserControllerRouter
  │   └─ OasisHidFocuserController
  └─ FilterWheelControllerRouter
      └─ OasisHidFilterWheelController
```

- `OasisHidTransport` 负责 HID 接口声明、端点发现、65 字节 Report、超时和串行化。
- 电调焦连接时优先查询 Rose 二代状态；未得到符合长度的响应时回退到一代协议。
- 滤镜轮使用设备回报的槽位数和设备保存的槽位名称；应用界面采用 0-based 槽位，协议采用 1-based 槽位，转换仅位于协议适配器。
- 滤镜轮面板的刷新操作映射为校准，恢复出厂设置不暴露给日常操作。

## 验收清单

1. 每类设备可被扫描、授权、连接、断开和重新连接。
2. 一代与 Rose 二代均可读取位置和温度，完成绝对/相对移动、停止、同步、置零。
3. 两款滤镜轮均可读取七个槽位、切换槽位、校准、读取和保存槽位名称。
4. 电调焦和滤镜轮可同时在线；相机、赤道仪和现有 ToupTek/Gemini/EFucoser 设备保持可用。
5. USB 拔出、权限拒绝和三次连续状态读取失败后，界面回到断开状态。

## 真机验证记录

- 2026-08-10：Oasis Focuser Rose 二代通过 Android USB Host 成功连接，位置读取、相对移动和停止控制通过。
- 待补充：固件版本、Android 设备型号、USB OTG 供电方式，以及一代电调焦和两款滤镜轮的回归结果。
