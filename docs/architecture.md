# 架构说明：特性层与协议调度器的接入

本文说明 `core` 协议栈如何经 `data` 的 BLE 适配层进入 `feature` 控制器与 Compose UI，
重点覆盖 `FrameLink`、`ConcurrentCommandDispatcher` 与 OTA 时序。协议字段定义见
[protocol.md](protocol.md)；固件包格式、本地预检与断点持久化见 [firmware.md](firmware.md)；
设备侧测试桩、故障注入矩阵与 CI 见 [testing.md](testing.md)；
安全模型、签名与令牌存储的防护边界见 [security.md](security.md)。

## 1. 分层与数据流

```
Compose UI (feature/ui)
        │ 读取 StateFlow / 调用 suspend 方法
        ▼
DeviceDetailController / OtaController (feature)
        │ 强类型 DeviceRequest / DeviceResponse
        ▼
ConcurrentCommandDispatcher (core/command)
        │ Frame（seq 配对、超时重试、通知广播）
        ▼
FrameLink (core/command)  ◄── BleFrameLink (data/ble)
        │ 下行 write / 上行 notifications
        ▼
BleConnection (data/ble)
        │ Android GATT / iOS CoreBluetooth
        ▼
设备
```

- `core` 不依赖平台，只处理帧、强类型消息、序号与错误语义。
- `data` 负责把 BLE 通道包装成传输层；`feature` 只面向调度器与消息模型，不再直接读写特征值。
- UI 只观察 `StateFlow` 与调用控制器的 suspend 方法，不接触 `Frame`。
- `protocol-testkit`（仅测试依赖）提供设备侧 `FrameLink` 实现 `DeviceSimulator`，
  在 JVM 上替代 `BleFrameLink` 驱动同一套 `ConcurrentCommandDispatcher` 与 `feature` 控制器；
  它不进入 Android/iOS 产物。

## 2. BleFrameLink：BLE 侧的 FrameLink 适配

实现位于 `data/src/commonMain/kotlin/com/valhalla/data/ble/BleFrameLink.kt`。

| 方向 | 协议通道（`BleUuids`） | 行为 |
| --- | --- | --- |
| 下行 | `PROTOCOL_WRITE` (`0000f1b1-…`) | `FrameCodec.encode` 后写入，`withResponse=true` |
| 上行 | `PROTOCOL_NOTIFY` (`0000f1b2-…`) | 通知/指示数据块交给 `FrameParser` 增量分帧 |

- **上行统一入口**：应答帧与设备主动通知都从 `FrameLink.incoming` 发出，由调度器按
  `isNotification` / `isResponse` 分流；通知不会误配给业务请求。
- **下行序列化**：`send` 由 `Mutex` 串行化，同一链路任何时刻只有一个在途写操作，
  避免并发请求、鉴权与 OTA 分片在 GATT 层互相覆盖。
- **冷流语义**：`incoming` 每次收集创建独立的 `FrameParser` 与通知订阅，因此一条链路
  应只由一个调度器收集。
- **平台落地**：
  - Android：`AndroidBleConnection` 使用 `BluetoothGatt.writeCharacteristic` 写入，
    `onCharacteristicChanged` 同时接收 notify 与 indicate；订阅时按特征属性选择
    `ENABLE_NOTIFICATION_VALUE` 或 `ENABLE_INDICATION_VALUE`。
  - iOS：`IosBleConnection` 使用 `CBPeripheral.writeValue` 写入，
    `didUpdateValueForCharacteristic` 接收 notify / indicate，`setNotifyValue` 统一订阅。

## 3. 特性层的接入方式

`DeviceDetailController` 与 `OtaController` 都只依赖 `ConcurrentCommandDispatcher`：

```kotlin
val dispatcher = ConcurrentCommandDispatcher(BleFrameLink(connection))
val detail = DeviceDetailController(
    dispatcher = dispatcher,
    deviceId = connection.deviceId,
    disconnectLink = { connection.disconnect() },
)
val ota = OtaController(dispatcher)
```

- 同一连接的详情页与 OTA 页共享一个调度器，序号与在途请求在同一条链路上保持唯一。
- 控制器调用 `dispatcher.execute(GetBatteryRequest())` 获取 `BatteryResponse`；
  错误码非 0 时调度器抛 `CommandFailure.DeviceRejected`，控制器通过 `DeviceStatus.describe()`
  转成 UI 文案，并把状态码写入 `DeviceDetailState.errorCode`。
- `refreshInfo()` 通过 `supervisorScope + async` 并行发出 `GET_DEVICE_INFO`、
  `GET_FIRMWARE_VERSION`、`GET_BATTERY` 三个请求；应答按 sequence 精确回到各自等待者，
  设备乱序回复不会串包，单项失败不影响其它项。
- 配置读写使用 `SetConfigRequest` / `GetConfigRequest`，成功后同步 `configuration` 缓存。
- 上传/接收协议帧不再出现在 `feature` 层：`Grep "connection.write"` 在 `feature` 中已无命中。

## 4. OTA 时序

```
UI                OtaController            Dispatcher            Device
 │ start(firmware)      │                      │                    │
 ├─────────────────────►│ Preparing(total,crc) │                    │
 │                      ├─ START_OTA(total,crc,chunk,version) ─────►│
 │                      │◄─ StartOtaResponse(sessionId,chunkSize) ──┤
 │                      ├─ OTA_DATA(offset=0,data) ───────────────►│
 │                      │◄─ OtaDataResponse(receivedBytes) ─────────┤
 │   Transferring ◄─────┤ … 循环直到 offset+len == total …          │
 │                      ├─ FINISH_OTA(total,crc) ──────────────────►│
 │                      │◄─ FinishOtaResponse(rebootRequired) ──────┤
 │   Succeeded ◄────────┤ Succeeded(total,crc,chunks)               │
```

- 分片由 `OtaController` 按 `StartOtaResponse.chunkSize` 重新确定（设备可覆盖本地默认值），
  offset 为固件内的绝对字节偏移，便于设备校验乱序与重传。
- 每个 `OTA_DATA` 都通过调度器等待应答，因此天然具备超时重试与序号配对能力。
- `OTA_PROGRESS_NOTIFY` 进入调度器 `notifications` 流，由 `OtaController` 在升级期间订阅，
  在不阻塞传输的情况下更新 `Transferring.stage` 与进度；会话号不匹配的通知会被丢弃。
- 失败语义：超时/传输异常 → `CommandFailure.Timeout|TransportFailure`，
  设备拒绝 → `CommandFailure.DeviceRejected`（如 `OTA_STATE_INVALID`、`CRC_MISMATCH`）。

### 4.1 状态机

| 状态 | 进入条件 | UI 展示 |
| --- | --- | --- |
| `Idle` | 初始 / `reset()` | 等待开始 |
| `Preparing` | 计算 CRC、发出 `START_OTA` | 总长度、CRC、会话号 |
| `Transferring` | 收到 `StartOtaResponse` 后逐片发送 | 进度条、阶段、重传次数 |
| `Verifying` | 全部分片确认后发出 `FINISH_OTA` | 设备校验中 |
| `Succeeded` | `FinishOtaResponse` 成功 | 分片数、CRC、是否重启 |
| `Aborted` | 用户 `abort()` 或协程取消 | 中止原因、已发送字节 |
| `Failed` | 超时、传输异常、设备拒绝、CRC 不匹配、断连 | 错误码、已发送字节、断点 |

### 4.2 分片重传与进度

- 设备以 `BUSY` / `CRC_MISMATCH` / `INTERNAL_ERROR` 拒绝分片，或应答的
  `receivedBytes` 小于 `offset + chunk.size` 时，`OtaController` 以**同一 offset**
  重传该分片，默认最多 `maxChunkRetries = 2` 次；重传次数累计到
  `Transferring.retransmissions` 并在 UI 显示。
- 超时与传输异常已由调度器内建重试处理，不再在分片层重复放大重试次数。
- `FakeFrameLink.emitNotification` 可在测试中模拟设备进度通知，验证阶段与进度更新。

### 4.3 中止、断连与断点续传

- `abort(reason)`：置位中止标记并尽力发送 `ABORT_OTA`（单次尝试、1s 超时）；
  传输循环在下一个分片边界抛出内部中止异常，状态落到 `Aborted`。
- 断连：`OtaController` 可接收 `connectionState`（`BleConnection.connected`），
  在分片边界与 `FINISH_OTA` 前快速失败；在途请求由调度器超时/传输异常兜底。
  `reset()` 清除断点并回到 `Idle`，便于用户重新发起。
- 断点续传（v0.9.0 起）：`startPackage(firmware, resumeFrom)` 在本地通过
  「版本 / 总长 / CRC-32 / CRC-16」四项标识校验后，携带 14 字节续传扩展发出 `START_OTA`；
  以设备应答的 `acceptedOffset` 为唯一权威起点（0 表示从头开始、向下调整按设备值、
  拒绝则回退为不带扩展的旧格式）。失败时写入 `lastResumePoint` 并持久化到
  `OtaCheckpointStore`，成功/中止/`reset()` 清除。字段与状态码见
  [protocol.md 4.3.1](protocol.md#431-续传扩展v090)，恢复策略见 [firmware.md](firmware.md)。

## 5. 测试策略

分两层：

**单元层（`core` / `data` / `feature` 的 `jvmTest`）**

- 调度器与控制器测试使用 `FakeFrameLink`：下行帧记录后按协议 `MessageCodec.decodeRequest`
  解析为强类型请求，`responder` 生成强类型应答回灌上行。
- 上行使用无界 `Channel`，避免应答在收集器启动前丢失，使并发/乱序用例在
  `StandardTestDispatcher` 下确定可复现。
- 覆盖点：完整会话（鉴权 → 读信息/版本/电量）、并行请求交错与乱序应答、
  设备错误码向 UI 状态传播、配置读写、OTA 正常路径/分片大小协商/重传/CRC 失败/中止/断连复位/空包/重置。

**端到端层（`feature` 的 `jvmTest` + `protocol-testkit`）**

- `DeviceSimulator` 实现真实 `FrameLink` 语义，按命令表生成应答与通知，
  并支持场景（鉴权、续传策略、旧设备）与故障注入（超时、忙、CRC、短确认、重复/乱序/粘包/拆包、
  通知丢失、中途断连）。
- 把 `DeviceSimulator` → `ConcurrentCommandDispatcher` → `FirmwareSelectionController`
  → `OtaController` 串成完整链路：鉴权 → 读信息 → 读版本 → 预检 → OTA → 续传 → 完成/中止。
- 断言状态迁移、进度、断点落盘与清除、`resumeFallbackReason`；覆盖续传「接受/下调/拒绝」
  三分支与旧设备回退。
- 用例清单、矩阵与新增场景步骤见 [testing.md](testing.md)；CI 工作流为
  `.github/workflows/ci.yml`（macOS + JDK 21，按 core/data/feature 测试、Android 打包、
  iOS Framework 分档）。
- 边界：端到端测试只证明协议一致性，不代表真机 BLE/固件行为。
