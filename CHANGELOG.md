# CHANGELOG

本文件记录 ValhallaApp 的版本演进，按「阶段完成即提交 + 打 annotated tag」的节奏维护。

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
