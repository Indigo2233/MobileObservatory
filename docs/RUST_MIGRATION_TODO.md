# Rust 迁移 TODO

## 目标

将不依赖 Android 生命周期和 UI 的自研计算代码逐步迁移到 Rust。Android 平台接口、厂商 SDK 和 Compose UI 继续保留 Kotlin/C++ 适配层。

## 工具链

- Rust stable：`rustc 1.89.0` 或更高版本。
- Android Rust targets：
  - `aarch64-linux-android`
  - `x86_64-linux-android`
  - `i686-linux-android`（模拟器 x86）
- Android NDK：项目现有 `ndkVersion = "25.1.8937393"`。
- Rust JNI：`jni` crate。
- 构建入口：Gradle 调用 `cargo-ndk`，使用 NDK 的 `clang` 作为链接器。

安装命令：

```powershell
rustup target add aarch64-linux-android x86_64-linux-android i686-linux-android
cargo install cargo-ndk --locked
```

## 迁移原则

1. 先迁移纯计算、输入输出可序列化、已有回归测试的模块。
2. 保持 Kotlin 公共 API 不变，Rust 通过 JNI 实现现有 native 接口。
3. 不跨 JNI 逐像素调用；每帧最多传输一次连续缓冲区。
4. 每个迁移项必须有数值一致性测试、取消测试和内存边界测试。
5. 每项完成后执行 `codexreview-loop`，处理审查结果，再提交并推送。
6. 未经 profile 证明的模块不因语言偏好迁移。

## 迁移清单

### RUST-01：深空叠加累加器

- 状态：已完成（2026-11-16）。
- 范围：`NativeStackAccumulator` 的 `create`、`accumulate`、`readRows`、`releaseM2` 和 `release`。
- 迁移前实现：`app/src/main/cpp/stack_accumulator_jni.cpp`。
- 目标实现：`app/src/main/rust/stack_accumulator`。
- 验收：
  - 双线性重采样结果与 C++ 实现一致。
  - 时序 sigma 拒绝和 M2 更新一致。
  - `UInt16` 覆盖计数、`-2` 覆盖上限和 `-3` 取消返回一致。
  - Kotlin `DeepSkyStacker` 回归测试通过。
  - Android APK 能加载 `libstack_accumulator_jni.so`。
- 验证记录：
  - `cargo fmt --check`、`cargo clippy --all-targets -- -D warnings`：通过。
  - `cargo test`：3 项通过。
  - `DeepSkyAlgorithmsTest`：通过。
  - `:app:assembleDebug`：通过。
  - APK 包含 `lib/arm64-v8a/libstack_accumulator_jni.so`，5 个 JNI 导出符号已核对。
  - CodexReview：无 findings。

### RUST-02：行星 warp 与累加

- 状态：已完成（2026-11-16）。
- 范围：`NativePlanetaryAccumulator` 和 `NativePlanetaryWarpBuilder`。Rust 生成位移 mesh，并执行每帧 mesh 插值、双线性采样、Double 累加、UInt16 覆盖计数和输出归一化。
- Kotlin 保留帧解码、配准决策、边缘校验、进度/取消协调和原生不可用时的回退路径。
- 验收：
  - Rust identity、越界、取消、RGB、非平凡 local warp 和 NaN 像素测试通过。
  - native 输出与 Kotlin oracle 逐 Float 一致。
  - 行星搜索、完整叠加、缓冲复用和 SER/PSER 端到端测试通过。
  - APK 包含 `libplanetary_stack_jni.so`，5 个 JNI 导出符号已核对。

### RUST-03：双线性与 VNG 去马赛克

- 状态：已完成（2026-11-16）。
- 范围：`NativeDemosaic` 的双线性与 VNG 像素循环及 `CopyBack` 输出。
- Kotlin 保留 CFA 相位解析、非 Bayer 直通、预算计算和原生不可用时的回退实现。
- 验收：
  - Rust 常量色、全部 CFA、边缘、非有限输入和取消测试通过。
  - 官方 OpenCV 4.10 VNG fixture、仿射、旋转、16 位和极弱信号测试通过。
  - DDFAPD 与解码器回归测试通过。
  - APK 包含 `libdemosaic_jni.so`，bilinear/VNG JNI 符号已核对。

### RUST-04：DDFAPD 去马赛克

- 状态：待开始。
- 范围：`DdfapdDemosaicer` 的方向决策、镜像边界和三个阶段 refinement。
- 约束：保留 `Workspace` 的并发占用、借出输出和跨调用复用契约。
- 验收：Menon 独立参考 fixture、工作区复用、取消和内存上限测试通过。

### RUST-05：校准与星点检测

- 状态：待开始。
- 范围：BIAS/DARK/FLAT 应用、星点质心、HFR、刚体配准。
- 验收：现有校准和 `DeepSkyAlgorithmsTest` 通过。

### RUST-06：FITS、SER、PSER 编解码

- 状态：待开始。
- 范围：头解析、像素解码、行带编码和校验。
- 验收：真实文件回放、字节级兼容、损坏输入和取消测试通过。

### RUST-07：领域状态机

- 状态：待开始。
- 范围：序列运行时、赤道仪协议解析、配件协议解析、指向计算。
- 验收：协议回放、并发、取消和错误恢复测试通过。

### RUST-08：PHD2 数值核心评估

- 状态：待开始。
- 范围：仅评估是否从上游 C++ 迁移。
- 约束：默认保留上游 C++，除非 profile 和数值回归证明迁移收益明确。

## 暂不迁移

- Compose UI、`Activity`、`ViewModel`、生命周期和权限。
- USB、蓝牙、Camera2、MediaCodec、ContentResolver、MediaStore、WebView。
- 厂商 SDK 的 JAR/AAR 和必须保留的 C/C++ 二进制。

## 审查与推送

每个迁移项完成后：

```powershell
pwsh -NoProfile -File C:\Users\yao.su\.codex\skills\codexreview-loop\scripts\review-loop.ps1 start -Repo D:\Unity\MobileObservatory -Revision HEAD
```

根据审查结果修复、反驳或记录延期项。审查收敛后提交：

```powershell
git add <迁移文件>
git commit -m "perf(rust): migrate <module>"
git push origin main
```

推送后在本文档记录提交哈希、测试命令和审查结论。
