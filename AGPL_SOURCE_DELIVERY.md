# Stellarium Web Engine 非商业版交付

本应用在 Android `WebView` 中运行 Stellarium Web Engine。当前固定上游版本：

`1fa2d3bbb19f66ebb0de6deed3090391497c8047`

上游许可证为 GNU Affero General Public License v3（AGPL-3.0）。

## 构建星图引擎

准备 Docker、Git 和 PowerShell，然后在仓库根目录执行：

```powershell
.\tools\Prepare-StellariumWebEngine.ps1
.\Build.ps1 -AndroidOnly -NonCommercial
```

准备脚本使用上游声明的 Emscripten `1.39.17` Docker 环境构建
`stellarium-web-engine.js` 和 `stellarium-web-engine.wasm`，并将引擎、完整
AGPL 许可证和上游测试星表复制到 APK assets。

仓库同时保留 `tools/stellarium-web-engine-windows-scons.patch`。该补丁记录
本次 Windows 本机验证构建所需的 SCons 启动兼容调整；Docker 发布流程直接
使用固定版本上游源码。

## 生成接收者源码包

每次对外提供 APK 时执行：

```powershell
.\tools\Prepare-StellariumWebEngine.ps1 -SkipBuild -PackageSource
```

将以下文件与 APK 一同提供：

- `bin\Source\StellariumWebEngine_1fa2d3bbb19f.zip`
- `bin\Source\MobileObservatory_StellariumIntegrationSource_1fa2d3bbb19f.zip`
- 本次发布所对应的完整应用源码、构建脚本和依赖获取说明

发布源码必须与 APK 使用同一提交状态。应用源码包中需要包含星图桥接层、
LX200 GOTO 实现以及 APK 的完整可重复构建材料。

## 发布检查

- APK 内含 `THIRD_PARTY_STELLARIUM_WEB_ENGINE.txt`。
- APK 内含 `stellarium/LICENSE-AGPL-3.0.txt`。
- 构建命令包含 `-NonCommercial`，普通 OEM 构建不包含引擎资源。
- 引擎源码归档固定到上述提交。
- APK 下载页面或交付介质同时提供对应源码。
- 保留上游版权与许可证声明。
- 对 APK 中其他第三方相机 SDK 的再分发条款逐项复核。

非商业分发仍受 AGPL 条款约束。当前 WebView/JavaScript Bridge 形成独立模块
边界；整个 APK 的许可范围、厂商 SDK 再分发条件和完整 Corresponding Source
范围应在正式发布前由熟悉自由软件许可证的法律专业人员复核。
