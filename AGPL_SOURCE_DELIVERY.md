# Stellarium Web Engine 非商业版交付

本应用在 Android `WebView` 中运行 Stellarium Web Engine。当前固定上游版本：

`1fa2d3bbb19f66ebb0de6deed3090391497c8047`

上游许可证为 GNU Affero General Public License v3（AGPL-3.0）。

## 星图引擎来源

APK 使用的 `stellarium-web-engine.js`、`stellarium-web-engine.wasm`、许可证和
星表资源保存在 `app/src/stellarium/assets/stellarium/`，对应上述固定提交。
需要重新构建引擎时，从交付的 Stellarium Web Engine 源码包检出该提交，并按
上游 README 使用其声明的 Emscripten 工具链构建。发布时不得使用未记录提交的
引擎二进制文件。

包含星图资源的正式构建命令为：

```powershell
.\Build.ps1 -Release -NonCommercial
```

该命令要求独立 release 密钥，并自动生成 APK 校验和、构建信息和源码包。

## 生成接收者源码包

每次对外提供 APK 时执行：

```powershell
.\tools\Package-ReleaseSource.ps1
```

将以下文件与 APK 一同提供：

- `bin\Source\StellariumWebEngine_1fa2d3bbb19f.zip`
- `bin\Source\MobileObservatory_Source_<应用提交前 12 位>.zip`
- `bin\Installer\*.sha256`
- `bin\Installer\*.build-info.txt`

打包脚本要求主仓库处于干净提交状态，归档当前应用提交、固定 libusb 源码、
libusb Android FD 补丁以及固定 Stellarium 上游源码。发布源码必须与 APK 的
构建信息记录同一提交。

## 发布检查

- APK 内含 `THIRD_PARTY_STELLARIUM_WEB_ENGINE.txt`。
- APK 内含 `stellarium/LICENSE-AGPL-3.0.txt`。
- 构建命令包含 `-NonCommercial`，普通 OEM 构建不包含引擎资源。
- 引擎源码归档固定到上述提交。
- APK 的 SHA-256 与构建信息随发布提供。
- APK 下载页面或交付介质同时提供对应源码。
- 保留上游版权与许可证声明。
- 对 APK 中其他第三方相机 SDK 的再分发条款逐项复核。

非商业分发仍受 AGPL 条款约束。当前 WebView/JavaScript Bridge 形成独立模块
边界；整个 APK 的许可范围、厂商 SDK 再分发条件和完整 Corresponding Source
范围应在正式发布前由熟悉自由软件许可证的法律专业人员复核。
