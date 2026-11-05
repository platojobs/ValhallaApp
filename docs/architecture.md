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
- 权限状态机、断连映射、自动重连与扫描空态统一收敛到 `feature/ble`（纯逻辑，可 JVM 测试），
  平台权限入口为 expect/actual，详见 [permissions.md](permissions.md)。
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

### 6.7 导出流式拼装与基准（v1.6.0）

- **逐条序列化**：`PacketLogExporter.compute` 分两阶段。预算阶段逐条渲染并只记录
  `IntArray` 字节数（不保留条目字符串）；拼装阶段按最终条数/截断标记重新逐条渲染，
  直接写入 `ChunkedTextBuilder`。因此拼装时不会同时持有「全部条目字符串 + 完整结果」。
- **分块缓冲上限**：`ChunkedTextBuilder` 以 8192 字符（`STREAM_CHUNK_CHARS`）为块，
  追加内容按剩余空间切片并 flush；`peakBufferedChars` 记录单块达到过的最大字符数，
  恒不超过块大小。`PacketLogExportResult.peakBufferChars` 将该值暴露给调用方/测试。
- **字节等价**：头部/分隔符/条目/尾部仍由同一套 renderer 生成，分块只是顺序拼接，
  输出与整体拼装字节层面等价；`byteSize` 由各片段字节数求和得出（`estimate()` 不再拼装
  文本），因此预估与导出条数/字节严格一致。
- **基准**：`./gradlew :core:benchmarkExport` 独立运行 5000 条 / 16 MiB 量级的
  JSON 导出（`core/src/jvmTest/.../PacketLogExportBenchmark.kt`），输出耗时、吞吐、
  峰值单块缓冲与堆占用概览；任务不挂到 `check`/`jvmTest`，不阻塞 CI。实测结果见
  [export.md](export.md#基准v160)。

## 7. 权限、连接生命周期与重连（v1.7.0）

权限声明、状态机与平台差异的完整说明见 [permissions.md](permissions.md)。本节只描述
与数据流和 UI 的衔接方式。

### 7.1 权限状态机

```
平台事实（系统授权 / 是否可再次弹窗 / 适配器状态）
        │ syncFromSystem / onRequestResult
        ▼
BlePermissionStateMachine (feature/ble)
        │ NOT_REQUESTED / GRANTED / DENIED / PERMANENTLY_DENIED
        ▼
resolveBleAvailability(adapterState, permission)
        │ READY / 权限类 / BLUETOOTH_OFF / RESETTING / UNSUPPORTED / UNKNOWN
        ▼
BleGuidance（标题 + 说明 + 动作）→ 扫描页引导卡片 + 禁用扫描/连接按钮
```

- 状态机是纯逻辑类，不依赖 Android/iOS API，`jvmTest` 直接覆盖全部状态转移；
- `PERMANENTLY_DENIED` 之后 `beginRequest()` 恒为 false，平台层不会再次调起弹窗；
  `requestInFlight` 防止连点造成并发弹窗；
- 平台控制器 `BlePermissionController` 是 expect/actual：
  Android 从 `checkSelfPermission` / `shouldShowRequestPermissionRationale` 读取事实，
  用 `SharedPreferences` 记录「是否请求过」；iOS 由 `CBManagerState` 映射；
- 「回到前台」：Android 在 `ON_RESUME` 重新读取权限并 `refreshAdapterState()`；
  iOS 监听 `UIApplicationDidBecomeActiveNotification`，同样重读 central 状态，
  避免 UI 停留在过期的 `poweredOff` / `unauthorized`。

### 7.2 连接生命周期与断连原因

```
BleConnection.connected == false
        │ DisconnectSignals(userRequested / timeout / peerTerminated / transportError
        │                   + adapterState + permissionState)
        ▼
classifyDisconnect() → DisconnectInfo(reason, title, message, action, autoReconnectAllowed)
        ▼
详情页断连横幅：文案 + 可操作项（重新连接 / 去系统设置 / 开启蓝牙 / 返回扫描）
```

- 优先级：主动断开 > 权限丢失 > 蓝牙关闭（含重置）> 超时 > 设备侧断开 > 连接失败 > 未知；
- 主动断开与权限丢失不允许自动重连；蓝牙关闭、设备侧断开、超时允许；
- 所有文案集中在 `DisconnectInfo`，UI 不再拼接平台错误字符串。

### 7.3 自动重连

```
ReconnectScheduler (纯逻辑)
  IDLE → WAITING(delay=1s) → CONNECTING → 失败 → WAITING(2s) → … → EXHAUSTED(5 次)
                    ↑ 蓝牙关闭 → PAUSED ──开启──┘        cancel() → CANCELLED

AutoReconnectController (执行器)
  delayMillis 注入 + attemptConnect 注入 + onConnected 回调
```

- 退避策略 `ReconnectPolicy` 默认 1s 起、2 倍递增、30s 封顶、最多 5 次，序列为
  1/2/4/8/16 秒；达到上限进入 `EXHAUSTED`，不再自动尝试；
- `WAITING` / `CONNECTING` 阶段 `beginAttempt()` / `start()` 均被拒绝，天然禁止重复发起；
- 蓝牙关闭：取消等待或进行中的任务并进入 `PAUSED`，不消耗次数；重新开启后从当前
  已完成次数继续退避（不会回到第 1 次之前）；
- 用户可在详情页断连横幅中「取消重连」，之后可手动「重新连接」；
- 重连成功后关闭旧调度器，复用同一份 `InMemoryPacketLog` 建立新的 `DeviceSession`。

### 7.4 扫描空态

`ScanController` 在启动扫描时启动 12 秒计时器（`DEFAULT_SCAN_TIMEOUT_MILLIS`），
空列表超过阈值置 `timedOut = true`；发现设备立即清除。扫描页用
`classifyScanEmpty(scanning, deviceCount, availability, error, timedOut)` 判定：

| 判定顺序 | 结果 | UI |
| --- | --- | --- |
| 已有设备 | `NONE` | 正常列表 |
| 扫描报错 | `ERROR` | 错误文案 |
| 权限未请求 / 被拒绝 | `NO_PERMISSION` | 权限引导卡片 |
| 权限永久拒绝 | `PERMISSION_PERMANENTLY_DENIED` | 去系统设置 |
| 蓝牙关闭 / 重置 | `BLUETOOTH_OFF` / `BLUETOOTH_RESETTING` | 开启蓝牙 / 稍候 |
| 不支持 / 未知 | `UNSUPPORTED` / `UNKNOWN_AVAILABILITY` | 说明原因 |
| 扫描中 / 超时 / 结束无设备 | `SCANNING` / `SCAN_TIMEOUT` / `NO_DEVICES` | 分别展示 |

权限与蓝牙不可用优先于「无设备」，不会把「没有权限」误报成「暂未发现设备」。

## 8. 国际化、主题与产品信息页（v1.8.0）

### 8.1 文案资源与切换机制

```
StringKey（枚举，唯一 key 集合）
        │
AppStrings（Map<StringKey, StringsEntry(zh, en)>）
        │ StringResolver(language).t / render(UiMessage)
        ▼
CompositionLocalProvider(LocalStrings) → 全部 Composable 页面
```

- 选择 commonMain 纯 Kotlin 文案表而不是 Compose Resources：语言切换后无需重建
  Activity/页面，`LocalStrings` 变化会让所有读取文案的 Composable 立即重组；
  key 完整性与占位符一致性可以在 `commonTest` 直接断言。
- 语言偏好 `AppLanguageMode(SYSTEM / CHINESE / ENGLISH)` 存于 `AppSettingsStore`
  （Android SharedPreferences、iOS NSUserDefaults、JVM 内存）。`resolveLanguage`
  是纯函数：`zh*` 映射中文，其余回退英文；`SYSTEM` 读取 `Locale.current.language`。
- 逻辑层不拼文案：`UiMessage(key, args, nested)` 由控制器产出，UI 用
  `StringResolver.render()` 渲染；`nested` 用于「失败原因本身也需要本地化」的嵌套文案。
- 日期时间与数字统一走 `LocaleFormat`：时间复用 `core` 的 `formatAbsoluteTime` /
  `formatRelativeTime`（后者新增可本地化的「刚刚 / 前 / 未来」标签）；数字仅英文加千位
  分隔，中文保留原始数字。

### 8.2 设计令牌与主题

- `DesignTokens`：`Spacing(xxs..xxl)`、`Radius(sm/md/lg)`、`Sizes` 与
  `StatusColors(success/warning/error/inProgress)`，页面不再散落 `.dp` 魔法数字。
- `ThemeMode(SYSTEM / LIGHT / DARK)` + `resolveDarkTheme(mode, systemDark)` 纯函数；
  `ValhallaTheme` 根据解析结果套用 `lightScheme` / `darkScheme`，亮暗各一套状态色。
- 状态色只从 `statusColors()` 获取，扫描/详情/配置/OTA/日志/导出页统一：成功 → success、
  警告（扫描超时）→ warning、失败 → error、进行中 → inProgress。

### 8.3 关于、帮助与隐私页

- 关于页版本来自 `core` 构建期生成的 `AppBuildInfo`（`generateAppInfo` 读取
  `gradle.properties`），与 Android/iOS 版本号同源。
- 帮助页只描述已实现行为：权限申请路径、自动重连退避与次数、OTA 续传/断点、日志仅在
  内存并支持保存/分享。
- 隐私页数据全部来自 `data/settings/StorageInventory`：按当前平台列出存储项、位置、
  key 与实际持久化属性；`StorageKeys` 同时被各平台 actual 引用，文案不会与实现漂移。
  当前版本无网络上传能力（Android manifest 未声明 `INTERNET`），页面如实声明。

### 8.4 测试边界

`StringsCompletenessTest`（key 集合、空值、占位符、英文无中文、错误文案含建议）、
`NoHardcodedChineseTest`（扫描 `ui`/`theme`/`composeApp`，忽略注释与 i18n 目录）、
`AppPreferencesTest`（主题解析与偏好读写/回退）、`PrivacyDisclosureTest`（存储项覆盖与
无上传声明）、`StorageInventoryTest`（平台覆盖、键唯一、令牌加密键一致）、
`VersionConsistencyTest`（`AppBuildInfo` 与 `gradle.properties` 一致）均在 JVM 宿主运行。
系统语言切换、系统深浅色、真机字体与排版只能人工验收，自动化测试不覆盖渲染效果。

## 9. 稳定性、异常边界与压力验证（v1.9.0）

### 9.1 异常分类与语义

所有异常在进入边界时先归类，四类语义互不吞并（`ErrorKind` / `classifyError`）：

| 分类 | 判定 | 典型来源 | 处理 |
| --- | --- | --- | --- |
| `USER_CANCELLED` | `UserCancelledException` | 用户点击取消、OTA 中止 | 记录并结束操作，保留终态 |
| `COROUTINE_CANCELLED` | 其他 `CancellationException` | 页面销毁、作用域取消 | 原样重抛，保持结构化并发 |
| `TIMEOUT` | `TimeoutCancellationException` / `BleTimeoutException` / `CommandFailure.Timeout` | 建链超时、应答超时 | 转成超时 UiMessage 与失败终态 |
| `FAILURE` | 其余 `Throwable` | 协议异常、IO/平台异常 | 记录原始类型并转成失败终态 |

判定顺序固定：先用户取消与超时，再普通协程取消，最后真实失败；否则超时会被当成
「正常取消」吞掉。

### 9.2 三层错误边界

1. **协程层（core）**：`ConcurrentCommandDispatcher` 的上行收集协程带
   `CoroutineExceptionHandler`。收集流异常只记录日志并结束该协程，下一次 `execute`
   检测到 job 不在活跃状态后重建；收集器死亡瞬间的在途请求按各自超时收敛，不会永久挂起。
2. **控制器层（feature）**：扫描、配置、OTA、重连的入口都保证 `finally` 或 `catch`
   把状态推进到终态（`scanning=false` / `loading=false` / `Failed` / `CANCELLED`），
   异常同时转成 `UiMessage`。`OtaController` 的进度收集协程单独捕获异常，
   进度增强失败不影响传输主流程。
3. **界面层（feature/ui）**：根 `ValhallaApp` 创建唯一的 `ErrorReporter` 并放入
   `LocalErrorReporter`；所有可点击协程改为 `scope.launchSafely(reporter, origin)`，
   非取消异常被记录（保留原始异常类型、消息、截断栈），不会沿默认未捕获处理器崩溃进程。
   `ErrorReporter` 为 50 条环形内存记录，id 与写入均为线程安全。
4. **遥测层（feature/error，v2.1.0）**：`ErrorReporter` 记录后可选地把错误交给
   `RemoteErrorDispatcher`：内部无界 `Channel` + 单协程消费，攒够 `batchSize` 或等待
   `flushIntervalMillis` 后批量调用 `RemoteErrorReporter.report`。载荷在入队时即完成
   字段级脱敏（`SensitiveTextRedaction`），关闭时投递剩余批次并调用 `close`。
   默认装配 `NoopRemoteErrorReporter`，**未接任何真实远端 SDK**；接入只需实现
   `RemoteErrorReporter` 并在入口构造调度器，无需改动业务代码。
5. **未捕获异常桥接（v2.2.0）**：`installUncaughtExceptionBridge(ErrorReporter)` 在根界面
   安装一次；Android/JVM 使用 `Thread.setDefaultUncaughtExceptionHandler`，iOS 使用
   `NSSetUncaughtExceptionHandler`。桥接只记录并脱敏投递，随后调用原处理器，保持系统
   崩溃流程；iOS 仅覆盖 Objective-C 异常，Kotlin/Native 未捕获异常不走该回调。
6. **跨端验签测试（v2.2.0）**：共享向量 `EcdsaTestVectors` 位于 `core` commonMain，
   JVM 测试读取同一对象；Android instrumented（`core/src/androidInstrumentedTest`）
   在真实 Android 运行时执行 `java.security.Signature`；iOS 模拟器
   （`:core:iosSimulatorArm64Test`）执行 `SecKeyVerifySignature`。CI 两个 job 失败即红，
   不使用 `continue-on-error`，避免「推断了但没跑」的静默通过。

7. **多设备管理（v2.3.0）**：`MultiDeviceManager` 在 `MultiDeviceConnectionPool` 之上维护
   槽位、容量前置拒绝与当前操作设备；容量满时直接返回 `LimitReached`，不进入排队。
   每个设备持有独立 `DeviceSession`（调度器/日志/档案），切换只改变 UI 目标；
   自动重连通过 `replaceConnection` 替换底层链路，避免旧连接残留。

8. **快捷操作（v2.3.0）**：`DeviceDetailController` 新增 `QuickActionState` 三态与
   `reboot`/`factoryReset`/`heartbeat`；同一时刻只允许一个在途操作，
   失败保留设备错误码。`confirmToken` 语义标注为需固件确认。

9. **OTA 历史与交互（v2.3.0）**：详情页展示 `DeviceProfile.otaHistory` 全部记录并可展开；
   扫描页增加 RSSI 分档与三种排序；错误区提供重试动作，deviceId/错误详情支持长按复制。

### 9.3 状态机收敛清单

| 状态机 | 已修复的不收敛路径 | 终态 |
| --- | --- | --- |
| 扫描 | 扫描流异常、去重逻辑异常 | `scanning=false` + `error` |
| 详情 | 三项并发刷新单项失败、类型不符 | 各分组错误态，`loading` 归零 |
| 配置 | 框架级异常越过 `loadOne` | `loading=false` + `error` |
| OTA | 写入/校验/续传异常、进度收集异常、协程取消 | `Failed` / `Aborted` |
| 导出 | 渲染/保存/分享意外异常 | `exporting=false` + 失败提示 |
| 重连 | 连接回调或退避注入异常 | `CANCELLED`（不再停在 `CONNECTING`） |

### 9.4 连接池与并发约束

- `MultiDeviceConnectionPool`：容量信号量覆盖「建链阶段」，成功或失败都会归还；
  `activeSessions()` 读取独立的不可变快照，不再暴露可变 map。
- 建链失败、超时、取消都会关闭已创建但未注册的 `ConnectionSession`；超时边界完成注册
  的极端竞态也会主动摘除并关闭，调用方取消时状态回落到 `DISCONNECTED`。
- 同一条 BLE 链路的写操作由 `BleFrameLink.writeMutex` 串行化，OTA 分片与普通指令共享
  同一把锁，压力测试断言在途写峰值恒为 1。
- `InMemoryPacketLog`：id 通过原子计数分配，列表通过 CAS 整体替换；TX 发送与 RX 收集
  可并发写入，容量上限（默认 500 条）与唯一 id 在 8000 条并发写入下仍成立。

### 9.5 压力与稳定性测试

新增测试均在 JVM 宿主运行，使用固定种子或纯不变量断言，避免 CI 抖动：

| 测试 | 规模 | 结论 |
| --- | --- | --- |
| `ConcurrentCommandDispatcherStabilityTest` | 200 条并发指令、乱序应答 | 序号配对全部正确；收集协程异常被拦截并可重建 |
| `InMemoryPacketLogConcurrencyTest` | 8 协程 × 1000 条 | 容量恒为 500，id 无重复，保留最新窗口 |
| `MultiDeviceConnectionPoolStressTest` | 16 设备 × 20 轮 | 并发建链 ≤ 容量，会话连接/断开计数配平，槽位全部归还 |
| `BleFrameLinkSerializationTest` | 40 次并发写 | 在途写峰值 = 1 |
| `PacketLogRedactionTest` | AUTH / 设备信息 / 三种导出格式 | 敏感字段不可还原，非敏感命令不受影响 |

真机 BLE 并发、系统断链回调时序、长时间运行（小时级）与崩溃埋点不在本轮自动化范围，
标注为未验证。

## 10. v2.x 产品化扩展

### 10.1 设备参数面板（v2.4.0）

- `core/config/ParamCatalog`：集中定义参数表（paramId、名称、类型、可写、整数区间、
  枚举表、UTF-8 字节上限、单位）。参数表是**协议约定值**，`confirmedByFirmware` 固定为
  false，界面标题区与文档都标注「需固件确认」，不冒充设备上报能力。
- 编码约定：整数大端 u32；布尔 1/0；枚举单字节；字符串 UTF-8。
- `feature/param/ParamEditorController`：读取按顺序串行、写入单在途，读写状态机与
  配置编辑器一致（加载中/保存中/成功/失败/错误码）；本地校验失败不产生请求。
- 界面在详情页配置区之后展示，只读参数禁用输入并说明原因。

### 10.2 连接质量看板（v2.4.0）

- `core/logging/ConnectionQualityAnalyzer` 是纯函数：输入 `PacketLogEntry` 快照，
  输出请求/应答/成功/设备错误/无应答、重传、乱序、时延（平均/最大）、错误码聚合、
  分命令明细与最近失败。
- 口径：请求只算 TX 非通知帧；同一 sequence 的重复 TX 计为重传，时延以最后一次发送为基准；
  应答按 sequence 配对；乱序按应答时间顺序与请求发起顺序比较。
- 样本少于 20 条时 `insufficientSample = true`，界面只显示「样本不足（X 条）」，
  不给出成功率/时延结论。
- 诊断页从详情页进入，数据只来自本机真实报文日志。

### 10.3 首次引导与偏好（v2.4.0）

- `feature/onboarding/OnboardingController`：三步（权限说明 / 扫描 / 连接与鉴权），
  可任一步跳过；完成状态写入 `AppSettingsStore.onboarding_completed`。
- 引导只做说明，不自动触发系统权限弹窗；「检查并授予权限」按钮调用既有
  `BlePermissionState`，请求次数限制与拒绝后静默降级仍由权限状态机负责。
- `feature/settings/AppSettings` + `AppSettingsRepository`：重连次数 1..5、退避上限
  5..60 秒、日志容量 100..1000、导出条数/大小/单条 HEX 上限；写入时非法值拒绝，
  读取时损坏值回退默认。
- 日志容量只影响新建连接（`InMemoryPacketLog(capacity)`），已有连接保持当前容量。
- 重连策略在新建 `AutoReconnectController` 时生效。

### 10.4 构建信息与依赖许可（v2.4.0）

- `:core:generateAppInfo` 从 `gradle.properties`、环境变量
  （`VALHALLA_BUILD_CHANNEL` / `VALHALLA_BUILD_COMMIT` / `GITHUB_SHA`）与依赖版本表生成
  `AppBuildInfo` 与 `AppDependencyInfo`；取不到提交号时为空字符串，关于页显示「未知」。
- 关于页逐条展示依赖名称、版本、许可证与作用域，不再用一句写死的许可证文案代替。




### 10.5 固件库、事件时间线与诊断报告（v2.5.0）

- `core/firmware/FirmwareLibrary.kt`：`FirmwareLibraryRecord` 只保存元信息与平台文件引用，
  身份为 `CRC-32 + payload 总长 + 版本`；`FirmwareLibraryStore` 行式编码，损坏回退空，
  默认 20 条，淘汰顺序为「非常用优先、最久未用优先」。
- `feature/firmware/FirmwareLibraryReuse`：复用前重新打开原始引用并重算 payload CRC，
  返回 `Available / Missing(ACCESS_DENIED|READ_FAILED) / CrcMismatch / NotFound`，UI 逐项本地化。
- `core/event/DeviceEventTimeline.kt`：每设备一份环形存储（100 条），倒序读取、类型筛选；
  落盘前脱敏，写入失败返回 false，不阻塞业务。
- `core/diagnostics/DiagnosticReport.kt`：`DiagnosticReportComposer.build` 为 suspend，
  逐小节 `ensureActive`；`estimate` 与 `build` 共用渲染逻辑，保证预览体积与结果一致。
  报告只读真实快照，设备 ID 输出短哈希，所有自由文本走 `SensitiveTextRedaction`。
- `feature/notification/NotificationPolicy.kt`：通知策略与低电去抖为纯逻辑；平台 presenters
  负责权限与展示（Android `POST_NOTIFICATIONS`、iOS `UNUserNotificationCenter`），JVM 空实现。

## 11. 设备体检、升级向导与仪表盘（v2.6.0）

- `feature/health/DeviceHealthCheck.kt`：固定七项顺序执行，单项失败不阻断后续；
  链路断开为全局失败并中止。结论四态 `OK/ABNORMAL/SKIPPED/FAILED`，异常附设备错误码，
  超时/传输失败附建议；`HealthCheckReportFormatter` 生成脱敏文本。
- `feature/ota/OtaWizard.kt`：五步向导推进与取消语义为纯逻辑（`OtaWizardFlow`），
  传输中禁止回退、取消等于中止；预检规则映射（`OtaPrecheckRuleEvaluator`）把错误码归到六条规则。
- `core/metrics/DeviceMetricSamples.kt`：采样模型、行式编码与 50 点淘汰；仓库按设备隔离，
  不足 3 点不画线。平台存储走 `DeviceMetricStore` expect/actual。
- UI 仍是 `ValhallaApp.kt` 内的组合式接入，向导只重组展示层，不改 OTA 状态机。
