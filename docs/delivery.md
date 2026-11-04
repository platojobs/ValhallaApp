# 交付说明

本文汇总 ValhallaApp 的可交付内容、构建方式、产物路径与完成度对照。发布说明见
[release-notes/v2.0.0.md](release-notes/v2.0.0.md)，全部文档入口见 [README.md](README.md)。

## 1. 交付物清单

| 类别 | 内容 | 位置 |
| --- | --- | --- |
| 源码 | Kotlin Multiplatform 全量源码（core / data / feature / composeApp / iosApp / protocol-testkit） | 仓库根目录 |
| 构建脚本 | Gradle 8.14.3 + Version Catalog | `settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml` |
| 版本单一来源 | `versionName` / `versionCode` | `gradle.properties` |
| Android 产物 | Debug APK、Release APK（默认 unsigned） | `composeApp/build/outputs/apk/` |
| iOS 产物 | 模拟器 Debug Framework | `composeApp/build/bin/iosSimulatorArm64/debugFramework/ValhallaApp.framework` |
| Xcode 工程 | SwiftUI 壳、蓝牙权限声明、Framework 集成 | `iosApp/iosApp.xcodeproj` |
| 测试与测试桩 | JVM 单元/端到端测试、设备模拟器 | `*/src/*Test`、`protocol-testkit/` |
| CI | 版本校验、JVM 测试、Android 打包、iOS Framework 四档 | `.github/workflows/ci.yml` |
| 文档 | 架构、协议、固件、存储、导出、隐私、权限、产品、安全、测试、交付 | `docs/` |

### 模块说明

```
composeApp  App 壳：Android Application / iOS Framework 与平台入口、签名与 R8
feature     特性层：扫描、详情、配置、OTA、日志导出、i18n、主题、错误边界、Compose UI
data        BLE 抽象与平台实现（Android GATT / iOS CoreBluetooth）、连接池
core        协议帧、CRC、消息编解码、指令调度、状态机、日志与脱敏（纯 Kotlin）
protocol-testkit  设备侧 FrameLink 模拟器（仅测试依赖，不进入产物）
iosApp      Xcode 工程壳（SwiftUI 启动 + Info.plist 权限）
```

依赖方向：`composeApp → feature → data → core`，上层不得反向依赖。

## 2. 构建命令

前置条件：JDK 21（本机验证版本）、Android SDK（compileSdk 36 / build-tools 36.0.0）、macOS 上的 Xcode（iOS 部分）。

```bash
# 全量 JVM 测试（core / data / feature）
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest

# Android Debug
./gradlew :composeApp:assembleDebug

# Android Release（R8；未配置签名时为 unsigned）
./gradlew :composeApp:assembleRelease
./gradlew :composeApp:verifyReleaseArtifact

# iOS 模拟器 Framework
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# 版本一致性 / iOS 版本同步
./gradlew :composeApp:verifyVersionConsistency
./gradlew :composeApp:syncIosVersion

# iOS App 壳（Xcode，Apple Silicon 模拟器）
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build
```

说明：

- `verifyReleaseArtifact` 依赖 `assembleRelease`，会校验 APK 是否存在；配置签名后会拒绝 unsigned 产物；
- 未配置签名时 Gradle 打印警告并产出 `composeApp-release-unsigned.apk`，不可直接分发；
- 签名通道与完整步骤见 [release-notes/v2.0.0.md 第 4 节](release-notes/v2.0.0.md#4-安装前提)。

## 3. 产物路径

| 产物 | 路径 |
| --- | --- |
| Debug APK | `composeApp/build/outputs/apk/debug/composeApp-debug.apk` |
| Release APK | `composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk`（未配置签名时） |
| iOS Simulator Framework | `composeApp/build/bin/iosSimulatorArm64/debugFramework/ValhallaApp.framework` |
| 测试报告 | `core/build/reports/tests/jvmTest/index.html` 等三份 |
| 测试结果 XML | `*/build/test-results/jvmTest/*.xml` |
| 版本校验日志 | CI 产物 `logs-version-check` |
| Android 构建日志 | CI 产物 `logs-android`（失败时） |

## 4. CI 入口

- 工作流：[.github/workflows/ci.yml](../.github/workflows/ci.yml)
- 触发：推送到 `main`、推送 `v*` tag、PR、`workflow_dispatch`
- 手动触发：`gh workflow run CI --ref main`
- Job：

| Job | Runner | 内容 |
| --- | --- | --- |
| `version-check` | macos-14 | `verifyVersionConsistency` |
| `jvm-tests` | ubuntu-latest | core / data / feature 三模块 `jvmTest`（矩阵并行） |
| `android` | macos-14 | 安装 SDK → `assembleDebug` → `assembleRelease` + `verifyReleaseArtifact` → 上传 APK |
| `ios` | macos-14 | `linkDebugFrameworkIosSimulatorArm64` |

## 5. 文档索引

| 文档 | 内容 |
| --- | --- |
| [README.md](README.md) | 文档总索引 |
| [architecture.md](architecture.md) | 分层、BLE 适配、调度器接入、OTA 时序、错误边界与压力验证 |
| [protocol.md](protocol.md) | 帧格式、状态码、命令表、续传扩展、签名挑战 |
| [firmware.md](firmware.md) | 固件包格式、签名段、预检、断点持久化 |
| [storage.md](storage.md) | 设备档案、OTA 历史、保留策略、隐私清单来源 |
| [export.md](export.md) | 报文日志导出格式、筛选、上限、脱敏与流式拼装 |
| [privacy.md](privacy.md) | 日志脱敏规则表、设备标识处理、上传边界 |
| [permissions.md](permissions.md) | Android/iOS 权限、权限状态机、重连策略、真机清单 |
| [security.md](security.md) | 威胁模型、签名鉴权、固件验签、令牌存储边界 |
| [product.md](product.md) | 面向使用者的功能、文案规范、验收边界 |
| [testing.md](testing.md) | 设备模拟器、场景矩阵、故障注入、端到端用例 |
| [environment-report.md](environment-report.md) | 本机工具链与实际构建记录 |
| [delivery.md](delivery.md) | 本文：交付物、构建命令、产物路径、完成度对照 |
| [release-notes/v2.0.0.md](release-notes/v2.0.0.md) | v2.0.0 发布说明与真机验收清单 |
| [CHANGELOG.md](../CHANGELOG.md) | v0.0.1 → v2.0.0 版本演进 |

## 6. 可交付状态与未完成项

### 已完成可交付

| 能力 | 证据 |
| --- | --- |
| 协议帧编解码、CRC、流式分帧 | `core` JVM 测试（含粘包/拆包/CRC 向量） |
| 指令调度（seq 配对、超时重试、通知分流） | `core`/`feature` JVM 测试 + 并发压力用例 |
| OTA 预检、分片、重传、续传三分支、旧设备回退 | `protocol-testkit` 端到端 13 用例 |
| 权限/可用状态机、断连映射、自动重连退避 | `feature` JVM 测试（纯逻辑） |
| 配置能力协商、报文日志筛选/导出/脱敏 | `core`/`feature` JVM 测试，TEXT/JSON/CSV 等价断言 |
| 设备档案、OTA 历史、保留策略 | `core` 仓储测试 + `storage.md` |
| 版本单一来源与 release 产物校验 | `verifyVersionConsistency` / `verifyReleaseArtifact` |
| Android Debug/Release、iOS Framework 构建 | 本机 + CI 构建记录 |
| 中英双语、主题、关于/帮助/隐私页 | 文案完整性与无硬编码中文测试；**渲染效果未验证** |

### 仍依赖真机 / 签名 / 固件联调

| 未完成项 | 依赖 | 当前状态 |
| --- | --- | --- |
| 权限弹窗、系统设置跳转、蓝牙开关交互 | Android/iOS 真机 | 未验证 |
| 签名鉴权界面接入与生产密钥注入 | 产品决策 / 密钥 | 控制器层已实现，界面默认走令牌，未接入 |
| 真实 BLE 连接/断连时序、弱信号重连 | 真机 + 设备固件 | 未验证 |
| 真实 OTA（含断点续传、设备重启） | 真机 + 可升级固件 | 未验证（仅模拟器端到端） |
| Keystore / Keychain 令牌读写与卸载行为 | 真机 | 未验证 |
| 系统保存/分享面板与三方应用读取导出文件 | 真机 | 未验证 |
| 生产签名与正式分发 | 自备 keystore / 分发渠道 | 未配置，仓库不含 keystore |
| ECDSA-P256 验签与密钥分发 | 平台安全模块 + 固件 | 未实现（接口预留） |
| 固件侧协议实现 | 设备固件团队 | 不在本仓库 |
| 小时级长稳、弱信号、并发多设备 | 真机台架 | 未测量 |
| 视觉/无障碍/横屏排版 | 设计 + 真机 | 未验证 |
| 崩溃埋点与遥测 | 远端服务 | 未接入 |

## 7. 交付结论

- **可以作为源码级交付物**：构建可复现、测试可运行、协议与行为有文档与自动化证据；
- **暂不能作为生产安装包交付**：release 未签名、真机关键路径未验收、固件侧未联调；
- 达到生产交付还需：自备 keystore 出签名包、完成第 5 节真机清单、替换测试验签器、补齐固件侧实现。
