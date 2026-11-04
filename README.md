# ValhallaApp

面向智能硬件（IoT/BLE 设备）的 Kotlin Multiplatform 跨平台应用骨架，业务逻辑、协议层与 UI 在
Android / iOS 之间共享，设备能力通过 `expect/actual` 分别落到 Android BLE 与 iOS CoreBluetooth。

> 当前仓库处于持续迭代的早期版本，版本节奏见 [CHANGELOG.md](CHANGELOG.md)。

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
- `ui`：Compose Multiplatform 页面（扫描页、设备详情页、OTA 页）。

### composeApp

- Android：`MainActivity` + 蓝牙权限声明。
- iOS：`MainViewController`（Compose 入口）+ `ValhallaApp.framework` 输出配置。

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
`composeApp` 中完成初始化并申请运行时权限。iOS 端需要 `NSBluetoothAlwaysUsageDescription`，
已在 `iosApp` 的 Info.plist 中声明；Xcode 构建阶段会自动调用 Gradle 链接对应架构的 Framework。

## 版本号

唯一来源是 [gradle.properties](gradle.properties)

```properties
valhalla.versionName=1.6.0
valhalla.versionCode=16
```

- Android：`composeApp/build.gradle.kts` 通过 `providers.gradleProperty` 读取，写入
  `defaultConfig.versionName/versionCode`；
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
- OTA 增加断点续传与固件签名校验。
- 引入 Ktor/SQLDelight 做设备档案与升级记录持久化。
- 配置正式 release keystore 与分发渠道（当前 CI 仅构建 unsigned release）。
