# 交付说明

本文汇总 ValhallaApp 的可交付内容、构建方式、产物路径与完成度对照。最新发布说明见
[release-notes/v2.8.0.md](release-notes/v2.8.0.md)，历史版本见
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
| CI | 版本校验、JVM 测试、Android 打包、iOS Framework、Android instrumented、iOS 运行时验签 | `.github/workflows/ci.yml` |
| 文档 | 架构、协议、固件、存储、导出、隐私、权限、产品、安全、测试、交付 | `docs/` |

### 模块说明

```
composeApp  App 壳：Android Application / iOS Framework 与平台入口、签名与 R8
feature     特性层：扫描、详情、配置、参数、诊断、OTA、日志导出、引导、i18n、主题、错误边界、Compose UI
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
| `android-instrumented` | ubuntu-latest | KVM + AVD 运行 `:core:connectedDebugAndroidTest`（ECDSA 运行时验签） |
| `ios-runtime-tests` | macos-14 | 运行 `:core:iosSimulatorArm64Test`（Kotlin/Native 运行时验签） |

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
| [release-notes/v2.5.0.md](release-notes/v2.5.0.md) | v2.5.0 固件库、诊断报告、事件时间线与本地提醒 |
| [release-notes/v2.4.0.md](release-notes/v2.4.0.md) | v2.4.0 参数面板、连接质量、引导与设置扩展 |
| [release-notes/v2.3.0.md](release-notes/v2.3.0.md) | v2.3.0 多设备管理、扫描增强、快捷操作与 OTA 历史 |
| [release-notes/v2.0.0.md](release-notes/v2.0.0.md) | v2.0.0 发布说明与真机验收清单 |
| [CHANGELOG.md](../CHANGELOG.md) | v0.0.1 → v2.5.0 版本演进 |

## 6. 可交付状态与未完成项

### 已完成可交付

| 能力 | 证据 |
| --- | --- |
| 协议帧编解码、CRC、流式分帧 | `core` JVM 测试（含粘包/拆包/CRC 向量） |
| 指令调度（seq 配对、超时重试、通知分流） | `core`/`feature` JVM 测试 + 并发压力用例 |
| OTA 预检、分片、重传、续传三分支、旧设备回退 | `protocol-testkit` 端到端 13 用例 |
| 权限/可用状态机、断连映射、自动重连退避 | `feature` JVM 测试（纯逻辑） |
| 固件库归并/排序/淘汰/损坏回退、复用校验 | `FirmwareLibraryTest`（11 项） |
| 事件时间线倒序/筛选/100 条环形/脱敏/隔离 | `DeviceEventTimelineTest`（6 项） |
| 诊断报告章节/体积预估一致/脱敏/进度/上限 | `DiagnosticReportTest`（6 项） |
| 通知开关默认关闭、低电条件与去抖 | `NotificationPolicyTest`（5 项） |
| 配置能力协商、报文日志筛选/导出/脱敏 | `core`/`feature` JVM 测试，TEXT/JSON/CSV 等价断言 |
| 设备档案、OTA 历史、保留策略 | `core` 仓储测试 + `storage.md` |
| 版本单一来源与 release 产物校验 | `verifyVersionConsistency` / `verifyReleaseArtifact` |
| 生产固件验签（ECDSA-P256，PEM/证书注入） | `EcdsaP256VerifierTest` + `EcdsaFirmwarePrecheckJvmTest`（JVM）；Android instrumented 与 iOS 模拟器运行时已真实执行同一组向量 |
| 构建期公钥注入（env > gradle property） | `generateFirmwareKey` + `FirmwareKeyInjectionTest` / `FirmwarePrecheckFactoryJvmTest`；未注入 `WARN`、非法公钥失败关闭 |
| 签名挑战鉴权界面（预共享密钥 / 签名挑战） | 设备详情页模式切换 + 结构化错误码；控制器测试 |
| 可插拔远端错误上报（默认空实现） | `RemoteErrorReporterTest`（批量、关闭、脱敏） |
| 自由文本字段级脱敏（日志/档案/上报） | `SensitiveTextRedactionTest` / `DeviceTextRedactionStorageTest` |
| 无障碍语义、字体缩放、RTL、状态色对比度 | 代码审查 + `StatusColorContrastTest`；真机朗读未验证 |
| Android Debug/Release、iOS Framework 构建 | 本机 + CI 构建记录 |
| 中英双语、主题、关于/帮助/隐私页 | 文案完整性与无硬编码中文测试；**渲染效果未验证** |
| 多设备管理（槽位/切换/断开/容量拒绝） | `MultiDeviceManagerTest`；真实多设备并发未验证 |
| 扫描增强（RSSI 分档、三种排序、去重） | `ScanEnhancementsTest`；真机弱信号阈值未校准 |
| 详情页快捷操作三态（重启/恢复出厂/心跳） | `DeviceDetailControllerTest` 新增用例；固件应答未联调 |
| OTA 历史完整列表与展开详情 | `DeviceProfileRepository` 测试 + 详情页 UI；真机升级场景未验证 |
| 参数面板（本地校验 / 单在途 / 错误码） | `ParamCatalogTest` + `ParamEditorControllerTest`；设备侧参数表仍需固件确认 |
| 连接质量看板（只用真实日志） | `ConnectionQualityStatsTest`（正常/超时/乱序/重传/无样本/错误码聚合） |
| 首次引导与偏好扩展 | `OnboardingControllerTest` + `AppSettingsTest`；真机引导渲染未验证 |
| 关于页构建信息与依赖许可 | `:core:generateAppInfo` 生成 + `VersionConsistencyTest` 许可清单断言 |

### 仍依赖真机 / 签名 / 固件联调

| 未完成项 | 依赖 | 当前状态 |
| --- | --- | --- |
| 权限弹窗、系统设置跳转、蓝牙开关交互 | Android/iOS 真机 | 未验证 |
| 生产固件公钥交付 | 固件团队 / 密钥管理 | 构建期注入通道已实现（`VALHALLA_FIRMWARE_PUBKEY` / `-Pvalhalla.firmwarePublicKey`），仓库**未内置密钥**；未注入时策略为 `WARN` |
| 真实 BLE 连接/断连时序、弱信号重连 | 真机 + 设备固件 | 未验证 |
| 真实 OTA（含断点续传、设备重启） | 真机 + 可升级固件 | 未验证（仅模拟器端到端） |
| 参数表与固件确认 | 固件团队 | 当前为协议约定值，界面已标注「需固件确认」；真实读写未联调 |
| 连接质量统计在真机长会话的表现 | 真机 | 统计逻辑已单测，长会话性能与真实分布未验证 |
| Keystore / Keychain 令牌读写与卸载行为 | 真机 | 未验证 |
| 系统保存/分享面板与三方应用读取导出文件 | 真机 | 未验证 |
| 生产签名与正式分发 | 自备 keystore / 分发渠道 | 未配置，仓库不含 keystore |
| 真实远端错误上报 SDK | 远端服务 | 接口与调度器就绪，默认空实现，未接入 |
| 固件侧协议实现 | 设备固件团队 | 不在本仓库 |
| 小时级长稳、弱信号、并发多设备 | 真机台架 | 未测量（多设备管理已在逻辑层覆盖，真实并发未验证） |
| TalkBack / VoiceOver 实际朗读、200% 字体与横屏排版 | 真机 | 语义与滚动容器已就绪，朗读/排版未验证 |
| 真实崩溃演练与遥测 SDK | 真机 / 远端服务 | 未捕获异常桥接已接入本地记录与脱敏投递，未做崩溃演练、未接 SDK |

## 7. 交付结论

- **可以作为源码级交付物**：构建可复现、测试可运行、协议与行为有文档与自动化证据；
- **暂不能作为生产安装包交付**：release 未签名、真机关键路径未验收、固件侧未联调；
- 达到生产交付还需：自备 keystore 出签名包、完成真机清单、通过
  `VALHALLA_FIRMWARE_PUBKEY` / `-Pvalhalla.firmwarePublicKey` 注入生产 ECDSA 公钥、
  接入真实上报 SDK（可选）、补齐固件侧实现。

## 8. iOS 完整 App 构建（v2.6.0）

- 新增 CI job `ios-app-build`：先 `linkDebugFrameworkIosSimulatorArm64`，再
  `xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator
  -configuration Debug -destination 'generic/platform=iOS Simulator'
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO`；失败即红。
- 本期不做启动冒烟、性能基准与脚手架脚本；如需再单独立项。
- 本地等价命令已验证通过，远程 runner 结论以待推送后的 Actions 记录为准。

## 9. 复用、可迁移与可度量能力（v2.7.0）

| 能力 | 实现位置 | 自动化验证 |
| --- | --- | --- |
| 配置预设（命名/重命名/删除/复制/最近使用排序） | `core/config/ConfigPreset.kt` + 详情页预设区 | `ConfigPresetTest` |
| 预设差异与逐键写入、部分失败 | `ConfigPresetDiffer` + `feature/config/ConfigPresetApplier.kt` | `ConfigPresetTest` / `ConfigPresetApplierTest` |
| 自动化规则（前台触发、开关/排序/删除） | `core/automation/` + `feature/automation/AutomationRuleRunner.kt` | `AutomationRuleTest` / `AutomationRuleRunnerTest` |
| 使用统计（各维度、时间范围、空数据） | `core/metrics/UsageStatistics.kt` + 统计页 | `UsageStatisticsTest` |
| 偏好备份导出/导入/恢复出厂 | `feature/settings/PreferencesBackup.kt` + 备份页 | `PreferencesBackupTest` |

- 自动化规则只在 App 前台触发，不做后台唤起；统计只用本机真实数据，不做估算；
- 备份 JSON 只含白名单字段，测试断言不出现令牌、密钥、凭证、设备档案、日志与断点键名。

## 10. 实时信号、通知与诊断包（v2.8.0）

| 能力 | 实现位置 | 自动化验证 |
| --- | --- | --- |
| 实时 RSSI 订阅（启停、周期、失败降级、断开/重连） | `BleConnection.readRssi()` + `feature/metrics/RssiSubscription.kt` + 详情页 | `RssiSubscriptionTest` |
| 旧采样格式兼容 | `core/metrics/DeviceMetricSamples.kt`（`decodeWithStatus`） | `DeviceMetricSamplesTest` |
| 通知事件、去重、权限降级、深链目标 | `feature/notification/NotificationCenter.kt` + 平台 Presenter | `NotificationCenterTest` |
| 诊断包（白名单、manifest、截断、取消） | `feature/diagnostics/DiagnosticPackage.kt` + 诊断页 | `DiagnosticPackageTest` |

- 通知只在 App 存活期间送出，不注册后台任务或远程推送；权限被拒后降级为应用内横幅；
- 诊断包只写白名单条目，构建完成前不触碰文件系统，取消或失败不留半成品；
- 明确排除令牌、密钥、凭据、断点文件、固件二进制与未脱敏原文。

## 11. 批量、固件库与 OTA 队列（v2.9.0）

| 能力 | 实现位置 | 自动化验证 |
| --- | --- | --- |
| 批量串行、失败隔离、取消剩余、锁范围、汇总 | `core/batch/BatchOperation.kt` + `feature/batch/BatchOperationController.kt` + 批量页 | `BatchOperationTest`、`BatchOperationControllerTest` |
| 固件库去重、损坏拒绝、别名、筛选、适用性与最新适用包 | `core/firmware/FirmwareLibrary.kt` + `core/firmware/FirmwareApplicability.kt` + `feature/firmware/FirmwareLibraryImporter.kt` | `FirmwareLibraryManagementTest`、`FirmwareLibraryImporterTest` |
| OTA 队列状态机、暂停安全点、取消当前、跳过失败、断点保留 | `core/ota/OtaQueue.kt` + `feature/ota/OtaQueueController.kt` + 队列页 | `OtaQueueTest`、`OtaQueueControllerTest` |

- 批量与队列都只在 App 前台串行执行，不提供后台批量或跨进程续跑；
- 队列暂停只在分片边界生效，断点复用沿用既有四重门控；
- 固件库条目只存元数据与文件引用，使用前仍完整执行 CRC 与签名预检。
