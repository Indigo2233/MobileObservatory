# 手机 RAW、连拍与资源边界

2026-10-14。此实现与数值证据属于 WF-10 的工程边界，不替代 S23 Ultra、低内存机、夜间解算或功耗实测。

## 已实现

- 如果相机所有 RAW 输出均超过请求尺寸，选择最小可用 RAW 流。原始 DNG 继续保存该流的原始像素，解算预览单独生成。
- RAW 解算帧按完整偶数 Bayer 块逐行平均；普通尺寸仍使用原来的 2×2 整数平均。大流可用 8×8、16×16 或其他完整偶数块，输出长边不超过请求上限，使用 Long 累加，保持 MONO16。
- 不复制完整 RAW 平面，只保留一行和一行输出累加器；奇数行跨度、像素间距、末行无填充、输入 buffer 位置均显式处理。逐 RAW 行检查取消，不返回“部分成功”帧。
- 原始 Camera2 crop 保留。派生 solve footprint 另存 Double 精度的实际覆盖范围与首个平均像素中心；FOV 和内参校正使用该范围，指纹/诊断包含它。未完整覆盖的右/下 Bayer 边缘不会假装仍在图像中。
- 在创建 Camera2 reader 前评估 Java burst/输出/提取 scratch、保守 native reader/HAL 缓冲及存储余量；每帧重查剩余输出与存储需求。预算计算检查乘法/加法溢出，不静默减少用户请求张数。
- warp 与 trimmed mean 逐输出像素采样，不保留每张对齐图像的完整副本；输出分配前检查堆预算。最多 16 帧，手机路径只接受紧密排列的 mono 数据。
- 堆、相机数据流和存储不足各有短恢复提示。真实分配 OOM 也转为 `RESOURCE_LIMIT`；该分支不增加 burst、ISO 或曝光，取消仍优先抛出。

## 多曝光时刻的限制

当前 warp 对齐的是物理 IMU 姿态，还没有逐曝光天球时刻重投影。多帧结果保留图片、星点与图像解析/诊断；当实际贡献帧数大于一时，不发布 timed OpticalObservation、hand-eye 样本或新 IMU 锚点。

推镜自动复核主动使用单帧，重试只增加适用的 ISO/曝光；手动诊断 burst 提供“用单张曝光验证”恢复动作。校准向导本来就使用单张曝光，所以正常两组和镜面四组采样保持原契约。现有绝对锚点的年龄不会因诊断 burst 刷新。

## 验证状态

父代理 `wf-resource-and-platform-build.log` 的整合构建通过，全量 JVM **889 项，0 failure / error / skip**。当次相关 XML 为：converter 6、budget 3、FOV 3、undistorter 6、stacker 7；覆盖普通 2×2 等价、奇数 stride、8×8/16×16、最大 mono 尺寸、Bayer 权重/65535、截断拒收、预算/溢出、精确 footprint，以及 MONO8/MONO16 的 streamed 与 materialized stack 字节等价。

之后补充的逐行取消、RESOURCE 不增长、真实 OOM 分类，以及多帧 fail-closed / 单帧复核回归均已包含在最终 **917 项 JVM、0 失败/错误/跳过** 的整合执行中，见 `wf-stop-verified-build.log`。旧 889 项记录仅描述当时的检查范围。

原始巨大 RAW 的 native 占用仍然存在，缩小解算帧不能取消传感器/HAL 开销。预算是保守准入估计；OEM 内部 buffer 数量、GC/并发分配、帧延迟、热状态与实际夜间成功率仍需真机测量。未获得 ≥30 组真实独立参考、低内存压力和功耗数据。
