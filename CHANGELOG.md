# CHANGELOG

本文件记录 ValhallaApp 的版本演进，按「阶段完成即提交 + 打 annotated tag」的节奏维护。

## v0.4.0 - 特性层 UI 与 App 壳工程

### 新增

- `feature/scan`：`ScanController` 将无限扫描流收敛为 `ScanUiState` 快照，按 `deviceId` 去重并按 RSSI 降序排列；
  支持开始/停止/清空与错误上报。
- `feature/detail`：`DeviceDetailController` 复用状态机，完成鉴权（AUTH 报文经 `FrameCodec` 编码写入）、
  固件/电量读取、日志聚合与断开流程。
- `feature/ota`：`OtaController` 占位实现，按分片写入 OTA 数据特征、实时进度、CRC16 记录与失败/取消状态。
- `feature/ui`：Compose Multiplatform 三段式界面（扫描页 → 设备详情页 → OTA 页），Android / iOS 共用。
- `composeApp`：Android `MainActivity`（初始化 BLE、申请蓝牙运行时权限）与 `AndroidManifest.xml`（BLE 权限声明）；
  iOS `MainViewController` 与 `ValhallaApp.framework` 输出。
- `iosApp`：SwiftUI 启动壳、Info.plist（含蓝牙用途说明）、Xcode 工程与共享 scheme；
  Xcode 构建阶段自动按架构链接 Kotlin Framework。
- 13 项 feature 单元测试：扫描去重/错误/停止，鉴权成功/失败、信息读取、断开，OTA 分片成功/失败/空包/重置。

### 变更

- Compose Multiplatform 由 1.12.2 调整为 1.10.3：1.12.x 的 Android 产物要求 compileSdk 37 / AGP 9.1.0，
  与当前 AGP 8.13.1 + compileSdk 36 冲突。
- 移除 `iosX64` 目标，仅保留 `iosArm64` 与 `iosSimulatorArm64`。

### 验证

- `./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest`：50 + 11 + 13 = 74 项全部通过。
- `./gradlew :composeApp:assembleDebug`：通过，产出 `composeApp-debug.apk`（约 11 MB）。
- `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`：通过。
- `xcodebuild ... ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build`：iOS 模拟器 App 构建通过；
  未做真机签名验证。

## v0.3.0 - BLE 抽象与 Android/iOS 平台实现

### 新增

- `data/ble` 跨平台接口：`BleClient`（适配器状态、扫描、连接、关闭）与 `BleConnection`
  （GATT 读 / 写 / 通知订阅 / 断开），以及 `BleDevice`、`ScanFilter`、`BleAdapterState`、`BleException`。
- `expect fun createBleClient()`：Android 走 `BluetoothLeScanner` + GATT 回调，iOS 走 CoreBluetooth，
  JVM 目标仅用于单测，明确抛出不支持异常。
- Android 实现：`AndroidBleSupport.init(context)` 注入 Application Context；扫描支持名称前缀与服务 UUID 过滤；
  `AndroidBleConnection` 串行化读写、10 秒单操作超时、CCCD 通知订阅、断链状态回落。
- iOS 实现：`IosBleClient` / `IosBleConnection` 基于 `CBCentralManager` 与 `CBPeripheralDelegate`，
  完成扫描、连接、服务/特征发现、读写与 notify；委托拆为独立的 Objective-C 子类，规避 KMP 超类型混用限制。
- `BleUuids`：设备信息、电量与自定义 OTA 服务/特征 UUID 常量，为后续 OTA 阶段预留。
- 3 项 `ScanFilter` 单元测试（空过滤、名称前缀、服务 UUID 大小写匹配）。

### 验证

- `./gradlew :data:jvmTest`：11 项通过。
- `./gradlew :data:compileDebugKotlinAndroid`：通过。
- `./gradlew :data:compileKotlinIosSimulatorArm64`：通过（首次自动下载 Kotlin/Native 2.3.20 工具链）。

## v0.2.0 - 设备状态机与多设备连接池

### 新增

- `core/state`：`DeviceState`（未连接 / 连接中 / 已连接 / 鉴权中 / 就绪 / 断开中 / 异常）、
  `DeviceEvent`（连接、建链、鉴权、断开、故障、重置等）与 `TransitionResult`（Accepted / Ignored / Rejected）。
- `DeviceStateMachine`：集中管理生命周期迁移，非法事件返回 `Rejected` 不抛异常，重复事件返回 `Ignored`，
  支持迁移历史（默认保留 32 条）与迁移回调。
- `data/pool`：`ConnectionSession` 会话抽象与 `MultiDeviceConnectionPool`。
- 连接池能力：信号量限制并发建链数、同设备请求串行化（重复点击幂等）、建链超时、
  失败/超时立即归还容量、`StateFlow<Map<String, DeviceState>>` 状态聚合与 `SharedFlow<PoolEvent>` 事件广播。
- `ConnectResult`（Connected / AlreadyConnected / InProgress / Failed）与 `PoolFailure`（Timeout / Transport）
  结构化结果，便于 UI 区分排队超时与链路异常。
- 20 个新增 JVM 单元测试：状态机 12 项（生命周期、非法/重复事件、历史容量、回调、故障恢复），
  连接池 8 项（幂等、并发上限、超时、失败释放槽位、断开重连、事件广播）。

### 验证

- `./gradlew :core:jvmTest`：50 项通过。
- `./gradlew :data:jvmTest`：8 项通过。

## v0.1.0 - 协议层与指令调度

### 新增

- 报文模型 `Frame`：协议版本、指令类型、序号、响应标记、payload 与字段级校验。
- `FrameCodec`：9 字节报文头的编码/解码，payload 上限 1024 字节，严格长度与版本校验。
- `Crc16`：CRC-16/CCITT-FALSE（poly 0x1021，init 0xFFFF），覆盖 version 至 payload。
- `FrameParser`：增量分帧，支持 BLE 通知分包/粘包、噪声跳过、损坏帧重同步。
- `Hex`：报文十六进制编解码，便于日志排查。
- `CommandType`：首批设备指令字典（鉴权、信息读取、配置、OTA、重启、日志、心跳）。
- `CommandDispatcher`：序号分配、超时控制、指数退避重试、响应合法性校验与结构化失败类型。
- `InMemoryDeviceLogger`：环形缓冲设备日志，UI 可读取一致性快照。
- 38 个 JVM 单元测试覆盖编解码、CRC、分帧容错、重试与日志容量策略。

## v0.0.1 - 工程骨架

### 新增

- 初始化 Git 仓库与 Kotlin Multiplatform 工程骨架（Gradle Kotlin DSL）。
- 建立 `core` / `data` / `feature` / `composeApp` 四个模块与 `iosApp` 壳工程目录规划。
- 引入 Version Catalog `gradle/libs.versions.toml`，统一 Kotlin、AGP、Compose Multiplatform 版本。
- 补充 README 架构说明、模块依赖方向、报文协议与状态机设计草案。
- 补充本机工具链检查记录。
