# 架构说明：特性层与协议调度器的接入

本文说明 `core` 协议栈如何经 `data` 的 BLE 适配层进入 `feature` 控制器与 Compose UI，
重点覆盖 `FrameLink`、`ConcurrentCommandDispatcher` 与 OTA 时序。协议字段定义见
[protocol.md](protocol.md)；固件包格式、本地预检与断点持久化见 [firmware.md](firmware.md)；
设备档案、配置与本地保留策略见 [storage.md](storage.md)；
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
PacketRecordingFrameLink (core/command)  ← 仅在装配时装饰，记录 TX/RX 报文
        │
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
- 配置读写使用 `SetConfigRequest` / `GetConfigRequest`；v1.3.0 起由
  `ConfigEditorController` 按命令表 `ConfigCatalog` 统一做可编辑性判断与类型/长度校验，
  成功后同步 `configuration` 缓存。
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

> 预检阶段（`FirmwareSelectionController`）不在 OTA 状态机内：`prechecking` /
> `precheckProgress` 属于选择状态，预检未通过或预检中时不会进入 `Preparing`。

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
- 断点续传（v0.9.0 起，v1.2.0 收敛生命周期）：`startPackage(firmware, resumeFrom, knownCrc16)`
  在本地通过「版本（忽略渠道）/ 总长 / CRC-32 / CRC-16」四项标识校验后，携带 14 字节
  续传扩展发出 `START_OTA`；`knownCrc16` 直接复用预检结果，START 前不再重扫 payload。
  以设备应答的 `acceptedOffset` 为唯一权威起点（0 表示从头开始、向下调整按设备值、
  拒绝则回退为不带扩展的旧格式）。新会话建立后立即按设备确认值落盘；成功/用户中止/
  `reset()` 清除；外部协程取消与失败则落盘精确偏移，START 未建立时保留旧断点。
  字段与状态码见 [protocol.md 4.3.1](protocol.md#431-续传扩展v090)，
  完整状态转移表与断点生命周期见 [firmware.md](firmware.md)。

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

## 6. 设备档案、配置编辑与报文日志（v1.3.0）

### 6.1 设备档案

- `DeviceProfileRepository`（`core/profile`）负责档案 CRUD、按最后连接时间排序、OTA 历史与保留策略；
  编解码格式与表结构见 [storage.md](storage.md)。
- 平台差异只在 `createDeviceProfileStore()`：Android `SharedPreferences`、iOS `NSUserDefaults`、
  JVM 内存；仓储本身不依赖平台，`jvmTest` 可直接验证。
- 写入联动：
  - `DeviceDetailController` 初始化时 `recordConnection`，`refreshInfo()` 成功后 `updateSnapshot`；
  - `OtaController` 在成功/失败/中止终态调用 `recordOta`，记录版本、结果、耗时、错误码与偏移；
  - 档案写入失败不影响主流程。

### 6.2 配置编辑

```
ConfigEditorController (feature/config)
        │ 命令表：ConfigCatalog（可编辑键/类型/长度/区间）
        ▼
ConcurrentCommandDispatcher → GET_CONFIG / SET_CONFIG
```

- 可编辑键在命令表中定义；未知键与设备只读键在 UI 标注原因，不能下发；
- 校验在本地完成，失败不发请求；同一时刻只允许一个保存请求在途；
- 每个键的读取失败独立展示，不影响其它键。

### 6.3 报文日志

- `PacketRecordingFrameLink` 装饰 `BleFrameLink`：下行 `send` 前、上行 `incoming` 收集后
  各记录一条 `PacketLogEntry`，不参与请求/应答配对；
- `InMemoryPacketLog` 默认保留 500 条，环形淘汰最旧记录，避免 OTA 长时间传输导致内存膨胀；
- 每条日志包含方向（TX/RX）、命令、seq、完整 HEX、解析摘要与错误标记；
- UI 在详情页提供「报文日志」入口：按时间倒序、按命令/错误过滤、关键字搜索、单条复制与清空。
- 运行时开销只在帧边界做一次解析摘要与字符串拼接，不阻塞传输；极端并发下允许丢日志。

### 6.4 配置能力协商（v1.4.0）

```
DeviceDetailController.refreshInfo()
        │ firmware / deviceInfo(model, vendor)
        ▼
ConfigEditorController.updateTarget(DeviceConfigTarget)
        │ ConfigCatalog.capability(spec, target)
        ▼
ConfigEditorState.visibleFields / hiddenFields + capabilityNotice
```

- `ConfigScope` 声明版本区间（`major.minor.patch.build`，忽略渠道）与型号白名单；
  未声明约束的键是「通用键」，在固件/型号未知时仍可读写。
- 判定结果 `SUPPORTED` / `VERSION_TOO_OLD` / `VERSION_TOO_NEW` / `MODEL_UNSUPPORTED` /
  `TARGET_UNKNOWN`。除 `SUPPORTED` 外一律 `visible = false`，UI 不请求、不下发该键。
- 降级策略：固件版本或型号缺失时只展示通用键，其余键隐藏并在配置区提示
  「先刷新设备信息」；刷新后由 `updateTarget` 重新判定，已输入文本与校验错误保留。
- 保存前用当前 `DeviceConfigTarget` 再次调用 `capability()`；若能力已失效（例如读取版本后
  设备降级），直接拒绝并提示原因，不发 `SET_CONFIG`。

### 6.5 报文日志导出与索引（v1.4.0）

- `PacketLogExporter`（`core/logging`）把日志渲染为纯文本：绝对时间（含时区偏移）+ 相对时间、
  方向、`RESPONSE`/`NOTIFY` 标记、seq、命令、摘要、错误与 HEX；
- 导出按 `timestampMillis` 倒序、同时间戳按 id 倒序，截断后仍保持「最新在前」；
- 上限保护：`PacketLogExportOptions` 默认 200 条 / 512 KiB / 单条 HEX 4096 字符，
  超限丢弃更旧条目并在头部标注「已按上限截断」；
- 平台分享：`data/share/TextShare.kt` 定义 `TextSharer` 与 `createTextSharer()`，
  Android 写 cache 后经 `FileProvider` 走 `ACTION_SEND`，iOS 写临时目录后走
  `UIActivityViewController`，JVM 返回 `UNAVAILABLE`；
- 时区：`currentTimeZoneOffsetMinutes()` 由平台实现，`core` 只接收偏移参数，保证可测试；
- 检索性能：`PacketLogIndex` 在日志快照变化时构建一次小写索引，搜索只做一次 `contains`；
  UI 对输入做 150ms 去抖，不随每次按键全量重算。

### 6.6 报文日志导出增强（v1.5.0）

导出流程与格式细节见 [export.md](export.md)。

```
UI 筛选状态（命令集合 / 仅错误 / 时间 / 关键字）
        │ PacketLogFilter
        ├── PacketLogIndex.query()      ← 列表展示
        └── PacketLogExporter.estimate()/build() ← 预估与导出
                 │ TEXT / JSON / CSV
                 ▼
        rememberFileSaver()（Android SAF / iOS UIDocumentPicker）
        createTextSharer()（Android ACTION_SEND / iOS UIActivityViewController）
```

- **筛选单一来源**：`PacketLogFilter` 新增 `commands: Set<CommandType>`、`fromMillis`/`toMillis`
  （闭区间）与 `matches(entry)`；线性过滤 `filterPacketEntries`、索引 `PacketLogIndex.query`
  与导出共用同一判断，关键字字段也由 `searchText()` 统一。
  时间倒序（起 > 止）在语义上不匹配任何条目，线性与索引结果都为空。
- **上限与预估**：`PacketLogExportOptions` 限定 1..5000 条、512 B..16 MiB、单条 HEX ≤65536；
  UI 的条数 / KB 输入先经 `validateEntryLimit` / `validateByteLimitKb` 校验，
  非法输入禁用导出并显示原因。`estimate()` 和 `build()` 走同一套筛选与截断引擎，
  因此预估条数/字节与实际导出一致；超限时只丢弃最旧条目
  （`PacketLogTruncationTarget.OLDEST`），并提示「截断最旧的一端」。
- **空结果**：筛选无命中或上限过小时 `build()` 返回 `empty = true`、`text = ""`，
  UI 不调用保存/分享，避免产生空文件或只有头部的文件。
- **格式**：`PacketLogExportFormat` 提供 `TEXT`（既有可读风格）、`JSON`（含元数据与
  完整结构化字段）与 `CSV`（表头 + 行，逗号/引号/换行转义）；HEX 逐条受
  `maxHexCharsPerEntry` 限制，JSON/CSV 额外记录 `hexOmittedChars`。
- **平台差异**：`feature/export` 的 `rememberFileSaver()` 是 expect/actual：
  Android 用 `ActivityResultContracts.StartActivityForResult` + `ACTION_CREATE_DOCUMENT`
  写 `content://` URI；iOS 先写临时目录，再展示
  `UIDocumentPickerViewController(forExportingURLs:asCopy:)`；JVM 返回 `UNAVAILABLE`。
  分享继续复用 `data/share/TextSharer`，两个入口互不替代。
- **大日志与取消**：`build()` 为 `suspend`，在筛选/渲染/拼装阶段按批次 `yield()` 并
  `ensureActive()`；UI 用 `Dispatchers.Default` 执行，进度经 `PacketLogExportProgress`
  驱动进度条，可随时取消。内容完全构建后才交给平台写文件，取消不会留下半成品；
  iOS 的临时文件是内容快照，未选中保存位置时不会写入用户目录。
- **重名冲突**：文件名 `valhalla-packet-log-<YYYYMMDD-HHMMSS>.<ext>`；
  `resolveFileName()` 在会话内维护已用文件名，冲突时追加 `-1`、`-2`…（忽略大小写）；
  系统 SAF / 文档选择器仍可能在覆盖确认上二次提示。
