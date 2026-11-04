# CHANGELOG

本文件记录 ValhallaApp 的版本演进，按「阶段完成即提交 + 打 annotated tag」的节奏维护。

## v0.7.0 - OTA 完整化

### 新增

- `OtaController` 完整升级会话：
  - `START_OTA` 携带固件总长度、CRC16、期望分片大小与目标版本；
  - 按 `StartOtaResponse.chunkSize` 重新切片，`OTA_DATA` 使用固件内绝对偏移；
  - 全部分片确认后发送 `FINISH_OTA`，以设备校验结果收尾。
- 分片重传：设备以 `BUSY` / `CRC_MISMATCH` / `INTERNAL_ERROR` 拒绝分片，
  或应答 `receivedBytes` 小于 `offset + chunk.size` 时，按同一 offset 重传，
  默认最多 `maxChunkRetries = 2` 次，重传次数累计到 `Transferring.retransmissions`。
- `ABORT_OTA`：`abort(reason)` 置位中止标记并尽力发送中止指令（单次尝试、1s 超时），
  传输循环在下一个分片边界退出，状态落到 `Aborted`。
- `OTA_PROGRESS_NOTIFY` 驱动 UI：升级期间订阅调度器通知流，按会话号过滤后更新
  `Transferring.stage` 与进度，不阻塞数据发送。
- 失败处理：
  - 超时 / 传输异常 → `Failed`，文案来自 `CommandFailure`；
  - 设备拒绝 / `CRC_MISMATCH` → `Failed` 携带 `DeviceStatus` 错误码；
  - 中途断连 → 通过 `connectionState` 在分片边界快速失败，`reset()` 复位到 `Idle`。
- 断点续传预留：新增 `OtaResumePoint(sessionId, sentBytes, totalBytes, crc16)`、
  `lastResumePoint` 与 `start(firmware, resumeFrom)`。协议暂未定义恢复指令，
  仅当新会话复用相同 `sessionId` 时从断点继续，否则从头开始，恢复语义待真机确认。
- UI 升级页展示当前阶段、百分比进度、重传次数、中止原因、错误码与断点信息，
  新增「中止升级」按钮，运行中返回会先中止升级。
- 新增 4 项 OTA 测试（feature 18 → 22）：分片重传、CRC 校验失败、主动中止、
  断连快速失败与复位、进度通知驱动阶段（共覆盖 10 个 OTA 场景）。
- `docs/architecture.md` 补充 OTA 状态机、重传/进度、中止与断连、断点续传说明。

### 变更

- `OtaState` 扩展：`Transferring` 增加 `sessionId/stage/retransmissions`，
  `Verifying` 增加 `sessionId/retransmissions`，`Succeeded` 增加 `rebootRequired`，
  新增 `Aborted`，`Failed` 增加 `totalBytes/status/resumePoint`。
- `OtaController` 构造参数新增可选的 `scope`、`connectionState` 与 `maxChunkRetries`，
  旧调用方式保持可用。
- 取消升级不再落为 `Failed("OTA 已取消")`，统一为 `Aborted(USER_CANCELLED)`。

### 验证

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest`：
  core 86 + data 11 + feature 22 = 119 项全部通过。
- `:composeApp:assembleDebug` 与 `:composeApp:linkDebugFrameworkIosSimulatorArm64` 均通过。
- OTA 分片大小、重传上限、进度通知节奏与断点恢复均未与真实固件联调，需真机确认。

## v0.6.0 - 特性层接入新协议栈

### 新增

- `data/ble/BleFrameLink`：把 BLE GATT 链路适配为 `FrameLink`。
  - 上行：订阅 `PROTOCOL_NOTIFY` 通知/指示，数据块经 `FrameParser` 增量分帧后，
    应答与设备通知统一进入 `FrameLink.incoming`；
  - 下行：`FrameCodec` 编码后用 `Mutex` 串行写入 `PROTOCOL_WRITE`，避免并发请求、
    鉴权与 OTA 分片在 GATT 层互相覆盖。
- `BleUuids` 新增协议通道 `PROTOCOL_SERVICE` / `PROTOCOL_WRITE` / `PROTOCOL_NOTIFY`。
- Android `AndroidBleConnection` 订阅通知时按特征属性选择 notify 或 indicate 使能值，
  两种上报都经 `onCharacteristicChanged` 进入统一通知流；iOS `IosBleConnection` 由
  `CBPeripheral.writeValue` + `didUpdateValueForCharacteristic` 承担，两条平台路径共用同一适配器。
- `DeviceDetailController` 改为持有 `ConcurrentCommandDispatcher`：
  - 鉴权使用 `AuthRequest` / `AuthResponse`，保存会话令牌、有效期与服务端挑战；
  - 设备信息、固件版本、电量使用 `GET_DEVICE_INFO` / `GET_FIRMWARE_VERSION` / `GET_BATTERY`
    强类型请求，并经 `supervisorScope + async` 并行发起，乱序应答按 sequence 精确配对；
  - 配置读写使用 `SET_CONFIG` / `GET_CONFIG` 强类型消息并同步本地缓存；
  - 设备错误码经 `DeviceStatus.describe()` 翻译为 UI 文案，`errorCode` 暴露原始状态码。
- `OtaController` 改为协议会话：`START_OTA`（总长 + CRC16 + 分片大小 + 固件版本）→
  按绝对偏移逐片 `OTA_DATA` → `FINISH_OTA`，并接受设备返回的实际分片大小。
- `feature/CommandFailureInfo`：统一把 `CommandFailure.*` 与 `DeviceStatus` 翻译为中文可读错误。
- Compose UI 以 `DeviceSession` 共享同一调度器，详情页与 OTA 页复用一条链路；
  详情页展示型号、充电状态与错误码文案。
- `FakeFrameLink` 测试桩：按 `MessageCodec.decodeRequest` 解析请求、生成强类型应答回灌上行。
- 新增 5 项特性层测试（feature 13 → 18）：完整会话（鉴权 → 读信息/版本/电量）、
  并行请求乱序应答、设备拒绝错误码传播、配置读写、OTA 分片大小协商与设备拒绝。
- `docs/architecture.md`：说明特性层与调度器接入方式、`BleFrameLink` 平台落地与 OTA 时序。

### 变更

- `DeviceDetailController` 构造参数由 `BleConnection` 改为
  `(dispatcher, deviceId, logger, disconnectLink)`；
  `OtaController` 构造参数由 `BleConnection` 改为 `(dispatcher, chunkSize, firmwareVersion)`。
  既有测试已同步改写，覆盖点未删减、断言未削弱。
- `feature` 层不再直接调用 `connection.write` / `connection.read`，裸字节读写全部收敛到 `data` 与 `core`。

### 验证

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest`：
  core 86 + data 11 + feature 18 = 115 项全部通过。
- `:composeApp:assembleDebug` 与 `:composeApp:linkDebugFrameworkIosSimulatorArm64` 均通过。
- 协议通道 UUID、设备分片大小协商与错误码映射仍为自定义假设，未与真实固件联调。

## v0.5.0 - 协议层完整落地

### 新增

- 命令表扩展：在 `CommandType` 中补齐鉴权、设备信息、电量/固件版本、配置与参数读写、时间同步、
  OTA（开始/分片/结束/中止）、重启、日志导出、心跳等 21 个指令，并标记 `HEARTBEAT_REPLY`、
  `DEVICE_STATUS_NOTIFY`、`OTA_PROGRESS_NOTIFY` 为设备主动通知。
- 帧种类完整化：`Frame` 新增 `isNotification`，`FrameCodec` 使用 flags bit1 表示通知帧，
  与 bit0 应答位互斥；解码时显式拒绝同时置位的非法帧。
- `protocol/message` 强类型模型：
  - `DeviceRequest` / `DeviceResponse` / `DeviceNotification` 基类与各命令的请求、应答、通知模型；
  - `PayloadWriter` / `PayloadReader` 提供小端序、带长度前缀字符串与严格越界校验；
  - `DeviceStatus` 错误码表（18 个状态码，含未知兜底）与 `describe()` 可读描述；
  - `MessageCodec` 负责请求、应答、通知的双向编解码，状态码非 0 时返回 `ErrorResponse`；
  - `AUTH` 应答完整解析令牌、有效期与服务端挑战（`token` / `expiresIn` / `challenge`），无 TODO。
- `FrameLink` + `ConcurrentCommandDispatcher`：把上行帧流与下行写拆开，支持并发请求；
  按 sequence 精确配对，乱序应答各自路由，重复/迟到/无主应答丢弃并记录，
  超时与退避重试沿用 `RetryPolicy`；通知帧单独进入 `notifications` 流，不误配业务请求。
- 结构化失败：新增 `CommandFailure.DeviceRejected`（携带命令与 `DeviceStatus`）与
  `CommandFailure.InvalidResponse`（畸形 payload），与既有 `Timeout` / `TransportFailure` 保持一致。
- `docs/protocol.md`：中文协议字典，含帧格式、完整命令表、字段结构、错误码、
  鉴权 / 读固件 / OTA / 心跳典型序列与容错语义。
- 36 项协议层单元测试：全部命令编解码往返、AUTH 成功/失败、错误码映射、通知解析、
  非法 payload 边界、并发隔离、乱序/重复应答、超时重试、设备拒绝与畸形应答。

### 变更

- `Frame` 的 `equals` / `hashCode` / `toString` 纳入 `isNotification`；
  既有 4 参数构造调用保持兼容（新参数默认 `false`）。
- `CommandType.fromCode` 仍保留 `UNKNOWN` 兜底，新增 `isNotificationOnly` 便于上层分流。

### 验证

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest`：
  core 86 + data 11 + feature 13 = 110 项全部通过（既有 74 项无回归）。
- 协议层尚未与真实固件联调；命令码与字段布局为自洽假设，详见 `docs/protocol.md` 第 7 节。

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
