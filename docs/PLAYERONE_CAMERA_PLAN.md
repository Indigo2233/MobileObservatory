# Player One 相机

> 状态：**M0–M4 代码已落地。** 2026-08-15 单机真机：出图、长跑、热拔插、供电 Hub、Android 版本基本通过。完整设计与 SONAME 升级检查见 [`archive/PLAYERONE_CAMERA_PLAN.md`](archive/PLAYERONE_CAMERA_PLAN.md)。
>
> 厂商阻塞项原信（已解决）见 [`archive/PLAYERONE_SDK_VENDOR_REQUEST.md`](archive/PLAYERONE_SDK_VENDOR_REQUEST.md)。

## 真机

- [x] M0 出图：授权、连接、预览。
- [x] M5 长跑 / 热拔插 / 供电 Hub / Android 版本：基本稳定。
- [-] M4 双路：主 + 导星各一台 Player One。手头没有第二台，暂缓。

「两小时泄漏」不是单独功能，而是长跑时看进程内存（Java 堆、native、Bitmap/direct buffer）是否只涨不回。没有留 RSS/profiler 曲线，不作为正式证据；现场长跑未观察到越用越卡或崩掉。

升级官方 AAR 前必须重跑 archive 文档 §3.1 的 SONAME 检查，禁止与 ZWO 的 `libusb-1.0.so` 撞名。
