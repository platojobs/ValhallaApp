# ValhallaApp

面向 BLE/IoT 智能硬件的 Kotlin Multiplatform 调试与管理客户端：协议解析、指令调度、配置管理、
OTA 升级与报文诊断在 Android / iOS 之间共享，平台能力通过 `expect/actual` 落到 Android BLE
与 iOS CoreBluetooth。

当前版本 **v2.1.0（versionCode 21）**。状态：**源码级可交付** —— 构建与测试可复现、
协议与行为有文档和自动化证据；release 产物默认未签名，权限弹窗、真实 BLE/OTA、
Keystore/Keychain、TalkBack/VoiceOver 等真机路径**未验证**。发布边界见
[docs/release-notes/v2.0.0.md](docs/release-notes/v2.0.0.md)（v2.1.0 增量见
[CHANGELOG.md](CHANGELOG.md)），交付清单见 [docs/delivery.md](docs/delivery.md)。

## 核心能力

- **设备接入**：BLE 扫描/去重/排序、连接、鉴权（预共享密钥 / HMAC-SHA256 签名挑战两种模式，界面可选）、设备信息、电量；
- **配置管理**：按固件版本与型号做能力协商，只展示可编辑项，本地校验后才下发；
- **OTA**：固件解析与签名段识别、ECDSA-P256 生产验签（PEM/DER 公钥与证书注入）、本地预检、分片传输、同偏移重传、断点续传与手动清除；
- **稳定性**：四类异常分类、统一错误边界、状态机终态收敛、连接池资源回收、报文日志并发安全；
- **诊断与隐私**：500 条内存报文日志、命令/错误/时间/关键字筛选、TEXT/JSON/CSV 流式导出、
  写入时脱敏（默认开启、不可关闭）、设备自由文本字段级脱敏、可插拔错误上报接口（默认空实现，未接远端 SDK）；
- **产品化**：中英双语、跟随系统/手动主题、设备档案与 OTA 历史、关于/帮助/隐私页、
  无障碍语义与字体缩放支持。

## 快速开始

前置条件：JDK 21、Android SDK（compileSdk 36 / build-tools 36.0.0）；iOS 需要 macOS + Xcode。

```bash
# 测试（core / data / feature 三模块 JVM 测试）
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest

# 构建 Android Debug 包
./gradlew :composeApp:assembleDebug        # → composeApp/build/outputs/apk/debug/composeApp-debug.apk

# 打包 Android Release（R8；未配置签名时产出 unsigned APK）
./gradlew :composeApp:assembleRelease :composeApp:verifyReleaseArtifact
# → composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk

# 构建 iOS 模拟器 Framework
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# 运行 Android（连接真机或启动模拟器后）
./gradlew :composeApp:installDebug
```

- 签名：通过 `VALHALLA_KEYSTORE_PATH` 等环境变量或 `keystore.properties` 配置，完整步骤见
  [发布说明第 4 节](docs/release-notes/v2.0.0.md#4-安装前提)；
- iOS App 壳在 `iosApp/iosApp.xcodeproj`，用 Xcode 选择自己的开发团队签名后运行；
- 版本号唯一来源是 [gradle.properties](gradle.properties)，改动后运行
  `./gradlew :composeApp:syncIosVersion`。

## 文档索引

完整索引见 [docs/README.md](docs/README.md)，常用入口：

| 文档 | 内容 |
| --- | --- |
| [docs/delivery.md](docs/delivery.md) | 交付物、构建命令、产物路径、CI 入口、完成度对照 |
| [docs/release-notes/v2.0.0.md](docs/release-notes/v2.0.0.md) | 版本要点、能力边界、已知限制、真机验收清单 |
| [docs/architecture.md](docs/architecture.md) | 分层、BLE 适配、调度器接入、OTA 时序、错误边界 |
| [docs/protocol.md](docs/protocol.md) | 帧格式、状态码、命令表、续传扩展与签名挑战 |
| [docs/firmware.md](docs/firmware.md) | 固件格式、签名段、预检与断点持久化 |
| [docs/storage.md](docs/storage.md) / [docs/export.md](docs/export.md) | 本地存储与报文导出 |
| [docs/privacy.md](docs/privacy.md) / [docs/permissions.md](docs/permissions.md) | 脱敏规则与权限/重连 |
| [docs/product.md](docs/product.md) / [docs/security.md](docs/security.md) | 产品功能与安全边界 |
| [CHANGELOG.md](CHANGELOG.md) | v0.0.1 → v2.1.0 版本演进 |

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.3.20 |
| 构建 | Gradle 8.14.3 + Kotlin DSL + Version Catalog (`gradle/libs.versions.toml`) |
| 跨端 | Kotlin Multiplatform（Android / iOS / JVM 测试宿主） |
| UI | Compose Multiplatform 1.10.3 + Material 3 |
| 异步 | kotlinx-coroutines 1.10.2（`StateFlow` / `SharedFlow`） |
| Android | AGP 8.13.1，minSdk 26，compileSdk 36 |
| iOS | Xcode 工程 + CoreBluetooth（Kotlin/Native 2.3.20） |

> iOS 目标保留 `iosArm64`（真机）与 `iosSimulatorArm64`（Apple Silicon 模拟器），不再声明 `iosX64`。
> Compose Multiplatform 由 1.12.2 调整为 1.10.3：1.12.x 的 Android 产物要求 compileSdk 37 / AGP 9.1，
> 与当前 AGP 8.13.1 + compileSdk 36 的工具链冲突。

## 模块划分

```
ValhallaApp
├── core        协议报文、CRC、帧解析、设备状态机、日志、指令调度（纯 Kotlin，无平台依赖）
├── data        BLE 抽象（扫描 / 连接 / GATT / 通知）、expect/actual 平台实现、多设备连接池
├── feature     特性层：扫描、设备详情、OTA 流程控制 + Compose UI
├── composeApp  App 壳：Android Application / iOS Framework 与平台入口
└── iosApp      Xcode 工程壳（SwiftUI 启动 + 蓝牙权限声明）
```

依赖方向：`composeApp → feature → data → core`，上层不得反向依赖。

### core

- `protocol`：报文结构、CRC16-CCITT、编解码、流式分帧（BLE 通知分包场景）。
- `state`：设备生命周期状态机（未连接 / 连接中 / 已连接 / 鉴权 / 就绪 / 异常）。
- `command`：带超时与重试的指令调度器，面向任意 `CommandTransport`。
- `logging`：设备日志抽象与内存环形缓冲实现。

### data

- `ble`：设备模型、GATT 服务/特征抽象、扫描器与中心设备接口。
- `platform`：`expect fun createBleClient()` 与 Android / iOS 的 `actual` 实现。
- `pool`：`MultiDeviceConnectionPool`，限制并发连接数、排队、状态聚合。

### feature

- `scan`：扫描控制器（`StateFlow` 驱动 UI）。
- `detail`：设备详情控制器，负责鉴权、读取信息、下发指令。
- `ota`：OTA 分片传输状态机（准备 → 传输 → 校验 → 成功/失败）。
- `i18n`：`StringKey` / 中英文案表 / `UiMessage` / `LocaleFormat` 与 `LocalStrings`。
- `theme`：设计令牌、亮暗主题解析与语言/主题偏好存取。
- `ui`：Compose Multiplatform 页面（扫描、详情、OTA、日志、导出、设置、关于、帮助、隐私）。

### composeApp

- Android：`MainActivity` + 蓝牙权限声明。
- iOS：`MainViewController`（Compose 入口）+ `ValhallaApp.framework` 输出配置。

## 功能一览

| 模块 | 能力 |
| --- | --- |
| 扫描 | BLE 扫描、去重、排序、12 秒无结果提示、权限与蓝牙状态引导 |
| 连接 | 建立链路、并发命令调度、失败原因（超时/传输/设备拒绝）、自动重连退避 |
| 详情 | 设备信息、固件版本、电量、充电状态、设备档案与 OTA 历史 |
| 配置 | 按设备能力展示可编辑项，隐藏不支持项并说明原因 |
| OTA | 固件预检、分片传输、进度/速率、失败原因、断点续传与手动清除断点 |
| 日志 | 内存报文日志（默认 500 条）、筛选、复制、JSON/CSV 导出与分享 |
| 设置 | 语言（中文 / English / 跟随系统）、主题（跟随系统 / 浅色 / 深色） |
| 关于/帮助/隐私 | 版本与构建信息、常见问题、本机存储清单与无上传声明 |

产品视角的功能与文案约定见 [docs/product.md](docs/product.md)。

## 语言、主题与隐私

- **国际化**：commonMain 维护 `StringKey` + 中英文案表，逻辑层只产出 `UiMessage`
  （key + 参数 + 可嵌套原因），UI 通过 `LocalStrings` 渲染。语言可跟随系统或手动锁定
  中文 / English，切换后当前页面立即刷新；日期时间复用 `core` 的时间格式化，
  数字仅英文加千位分隔。
- **主题与设计令牌**：`DesignTokens` 统一间距、圆角与尺寸；`ThemeMode` 支持跟随系统 /
  浅色 / 深色；成功、警告、错误、进行中四类状态色在扫描、详情、配置、OTA、日志、导出页
  保持一致，亮暗各一套。
- **隐私**：隐私页按当前平台列出设备档案、OTA 断点、鉴权令牌、报文日志、应用偏好与
  蓝牙权限标记的存储位置与 key（来源 `StorageInventory`）；报文日志仅存内存；
  当前版本未声明 `INTERNET` 权限，**没有任何网络上传**。详见 [docs/storage.md](docs/storage.md)。
- **关于/帮助**：关于页版本来自构建期生成的 `AppBuildInfo`，与 Android/iOS 同源；
  帮助页只描述已实现行为（权限、连接失败、OTA 失败、日志位置）。

## 报文协议（v1）

```
+--------+---------+------+-------+--------+--------+---------+--------+
| SOF    | version | type | flags | seq    | length | payload | crc16  |
| 2B     | 1B      | 1B   | 1B    | 2B LE  | 2B LE  | 0..N B  | 2B     |
+--------+---------+------+-------+--------+--------+---------+--------+
SOF = 0xA5 0x5A；flags.bit0 = 是否为响应帧；crc16 覆盖 version..payload
```

- 单帧 payload 上限 1024 字节（`FrameCodec.MAX_PAYLOAD_SIZE`）。
- CRC 采用 CCITT-FALSE（poly `0x1021`，init `0xFFFF`）。
- `FrameParser` 支持增量输入，用于 BLE 通知分包与会话粘包。

## 设备状态机

```
DISCONNECTED --ConnectRequested--> CONNECTING --LinkEstablished--> CONNECTED
CONNECTED --AuthenticationStarted--> AUTHENTICATING --AuthSucceeded--> READY
AUTHENTICATING --AuthFailed--> FAULT --ResetRequested--> DISCONNECTED
任意状态 --FaultRaised--> FAULT；任意已连接状态 --DisconnectRequested--> DISCONNECTING
DISCONNECTING --Disconnected--> DISCONNECTED；连接丢失（LinkLost）回落 DISCONNECTED
```

非法迁移不会抛异常，返回 `TransitionResult.Rejected` 并保留原因，便于上层做埋点与容错。

## 构建与运行

前置条件：JDK 17+（本机验证使用 JDK 21）、Android SDK（compileSdk 36 / build-tools 36）、macOS 上的 Xcode。

```bash
# 共享层单元测试（JVM 宿主，最快反馈）
./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest

# Android Debug 包
./gradlew :composeApp:assembleDebug

# Android Release 包（R8/资源压缩；未配置签名时产出 unsigned APK）
./gradlew :composeApp:assembleRelease
./gradlew :composeApp:verifyReleaseArtifact   # 校验 release APK 存在

# 导出基准（5000 条 / 16 MiB，独立任务，不进常规测试）
./gradlew :core:benchmarkExport

# iOS Framework（需 Kotlin/Native 工具链）
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# iOS App 壳工程（Xcode，Apple Silicon 模拟器）
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build
```

Android 端在 `AndroidBleSupport.init(context)` 之后才可使用真实 BLE，示例入口已在
`composeApp` 中完成初始化。运行时权限由扫描页的 `BlePermissionController` 按状态机发起
（Android 12+ 使用 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`，11 及以下回落 `ACCESS_FINE_LOCATION`），
永久拒绝后不再弹窗，引导用户前往系统设置。iOS 端需要 `NSBluetoothAlwaysUsageDescription`
并声明 `bluetooth-central` 后台模式，均已写入 `iosApp` 的 Info.plist；
Xcode 构建阶段会自动调用 Gradle 链接对应架构的 Framework。权限细节见
[docs/permissions.md](docs/permissions.md)。

## 版本号

唯一来源是 [gradle.properties](gradle.properties)

```properties
valhalla.versionName=2.1.0
valhalla.versionCode=21
```

- Android：`composeApp/build.gradle.kts` 通过 `providers.gradleProperty` 读取，写入
  `defaultConfig.versionName/versionCode`；
- 关于页：`core` 的 `generateAppInfo` 在构建期生成 `AppBuildInfo`（应用名/版本/构建号/
  渠道），与上述值同源，`VersionConsistencyTest` 断言一致；
- iOS：`Version.xcconfig` 由 `:composeApp:syncIosVersion` 从同一组值生成，
  Xcode 工程通过 `baseConfigurationReference` 读取，`Info.plist` 使用
  `$(MARKETING_VERSION)` / `$(CURRENT_PROJECT_VERSION)`；
- 改动版本后执行 `./gradlew :composeApp:syncIosVersion`，并在提交前运行
  `./gradlew :composeApp:verifyVersionConsistency`（CI 也会执行）。

## Release 构建与签名

Release 已启用 R8（`isMinifyEnabled` + `isShrinkResources`），规则见
[composeApp/proguard-rules.pro](composeApp/proguard-rules.pro)：保留 Compose 运行时与
组合函数、Kotlin 协程、Kotlinx 序列化（预置）与枚举名，并用
`-assumenosideeffects` 移除 `Log.v/d/i` 调试输出。

签名从环境变量或本地 `keystore.properties`（已 gitignore）读取，不硬编码密钥：

```properties
# keystore.properties（不要提交）
storeFile=/absolute/path/valhalla-release.keystore
storePassword=***
keyAlias=valhalla
keyPassword=***
```

对应环境变量为 `VALHALLA_KEYSTORE_PATH`、`VALHALLA_KEYSTORE_PASSWORD`、
`VALHALLA_KEY_ALIAS`、`VALHALLA_KEY_PASSWORD`。

- 四项全部提供：`assembleRelease` 产出签名 APK；
- 只提供一部分：Gradle 直接报错并列出缺失项；
- 全部缺失：允许构建，但产物为 `composeApp-release-unsigned.apk`，不可直接分发。

产物位于 `composeApp/build/outputs/apk/release/`；`verifyReleaseArtifact` 会检查 APK
是否存在，签名已配置时还会拒绝 unsigned 产物。

## CI

[.github/workflows/ci.yml](.github/workflows/ci.yml) 分四档，全部使用 JDK 21 与
`gradle/actions/setup-gradle` 缓存：

| Job | Runner | 内容 |
| --- | --- | --- |
| `version-check` | macos-14 | `verifyVersionConsistency` |
| `jvm-tests` | ubuntu-latest | core / data / feature 三模块 `jvmTest` |
| `android` | macos-14 | 安装 SDK 平台 → `assembleDebug` → `assembleRelease`（unsigned）→ 上传 APK |
| `ios` | macos-14 | `linkDebugFrameworkIosSimulatorArm64` |

失败时会上传控制台日志与测试报告（`actions/upload-artifact`），便于定位；Android
不再使用会因 `sdkmanager tools` 缺失而失败的第三方 setup 步骤，改用 runner 预装 SDK。
支持 `workflow_dispatch` 手动触发，也可用 `gh workflow run CI --ref main`。


## 环境验证记录

本机工具链与实际执行结果记录在 [docs/environment-report.md](docs/environment-report.md)。

## 下一步

- 接入真实设备协议字典（按产品型号生成 `CommandType` 与 payload 编解码）。
- 固件签名校验与更细的 OTA 回滚策略。
- 扩展更多语言（当前中英双语），补充 RTL 与大字体的无障碍检查。
- 配置正式 release keystore 与分发渠道（当前 CI 仅构建 unsigned release）。
