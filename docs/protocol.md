# Valhalla 设备协议字典（v1）

本文档描述 ValhallaApp 与智能硬件之间使用的串口 / BLE 通道协议，是 `core` 协议层的唯一实现依据。

> 说明：当前没有厂商提供的真实固件协议文档，本字典是一套自洽的「假设协议」，
> 所有取值集中定义在 `core/protocol` 与 `core/protocol/message` 中。
> 后续拿到真机协议后，只需调整命令码、字段布局与错误码表，上层调度与 UI 不受影响。

## 1. 传输层约定

- 物理通道：BLE GATT 或串口透传，二者共享同一套帧格式。
- 所有多字节整数均为**小端序（little-endian）**。
- 字符串统一为 UTF-8，长度前缀分两种：
  - `str1`：1 字节长度（0..255）；
  - `str2`：2 字节长度（0..65535）。
- 单帧 payload 最大 1024 字节（`FrameCodec.MAX_PAYLOAD_SIZE`）。
- BLE 通知可能分包/粘包，接收侧必须使用增量分帧器 `FrameParser`，不要假设「一次通知 = 一帧」。
- v1 帧在 BLE 上使用独立协议通道（`BleUuids`）：
  | 特征 | UUID | 用途 |
  | --- | --- | --- |
  | `PROTOCOL_SERVICE` | `0000f1b0-0000-1000-8000-00805f9b34fb` | 协议服务 |
  | `PROTOCOL_WRITE` | `0000f1b1-0000-1000-8000-00805f9b34fb` | App → 设备，写完整帧 |
  | `PROTOCOL_NOTIFY` | `0000f1b2-0000-1000-8000-00805f9b34fb` | 设备 → App，notify / indicate |
- 下行写入必须串行化（`BleFrameLink` 使用 `Mutex`），避免并发请求与 OTA 分片在 GATT 层互相覆盖；
  上行通知与应答统一进入 `FrameLink.incoming`，由调度器分流。

## 2. 帧格式

```
+------+---------+------+-------+------+--------+---------+-------+
| SOF  | version | type | flags | seq  | length | payload | crc16 |
| 2B   | 1B      | 1B   | 1B    | 2B   | 2B     | 0..N    | 2B    |
+------+---------+------+-------+------+--------+---------+-------+
```

| 字段 | 偏移 | 长度 | 说明 |
| --- | --- | --- | --- |
| SOF | 0 | 2 | 固定 `0xA5 0x5A` |
| version | 2 | 1 | 协议版本，当前为 `0x01` |
| type | 3 | 1 | 指令码，见第 4 节命令表 |
| flags | 4 | 1 | bit0=应答，bit1=通知，二者互斥；请求为 `0x00` |
| seq | 5 | 2 | 请求序号，`0..65535` 循环；应答必须回填相同序号 |
| length | 7 | 2 | payload 字节数，最大 1024 |
| payload | 9 | N | 业务负载，结构由 type 决定 |
| crc16 | 9+N | 2 | CRC-16/CCITT-FALSE，覆盖 `version..payload` |

CRC 参数：poly `0x1021`，init `0xFFFF`，不反转输入输出，无最终异或。

### 帧种类

| 种类 | flags | 语义 |
| --- | --- | --- |
| 请求 | `0x00` | App 下发，等待同 seq 的应答 |
| 应答 | `0x01` | 设备回复请求，seq 与请求一致 |
| 通知 | `0x02` | 设备主动上报，不参与请求-应答配对，可携带任意 seq |

## 3. 应答状态码

所有**应答** payload 的第 0 字节固定为状态码：

```
payload = status:u8 | body...
```

- `status == 0x00`：成功，`body` 按命令定义解析；
- `status != 0x00`：失败，`body` 为可选的 UTF-8 错误详情（协议层不强制长度）。

| 状态码 | 枚举 | 含义 |
| --- | --- | --- |
| `0x00` | `OK` | 成功 |
| `0x01` | `UNKNOWN_COMMAND` | 未知指令 |
| `0x02` | `INVALID_PAYLOAD` | 报文负载非法 |
| `0x03` | `INVALID_PARAMETER` | 参数非法 |
| `0x04` | `UNAUTHORIZED` | 未鉴权 |
| `0x05` | `AUTH_FAILED` | 鉴权失败 |
| `0x06` | `CHALLENGE_EXPIRED` | 挑战已过期 |
| `0x07` | `BUSY` | 设备忙 |
| `0x08` | `TIMEOUT` | 设备处理超时 |
| `0x09` | `NOT_READY` | 设备未就绪 |
| `0x0A` | `OUT_OF_RANGE` | 取值超出范围 |
| `0x0B` | `STORAGE_FULL` | 存储空间不足 |
| `0x0C` | `OTA_VERIFY_FAILED` | OTA 固件校验失败 |
| `0x0D` | `OTA_STATE_INVALID` | OTA 状态不允许该操作 |
| `0x0E` | `CRC_MISMATCH` | CRC 校验失败 |
| `0x0F` | `UNSUPPORTED` | 设备不支持该能力 |
| `0x10` | `INTERNAL_ERROR` | 设备内部错误 |
| `0x11` | `OTA_RESUME_OFFSET_INVALID` | OTA 续传偏移非法 |
| `0x12` | `OTA_RESUME_CONTEXT_INVALID` | OTA 续传上下文失效 |
| `0x13` | `OTA_FIRMWARE_MISMATCH` | OTA 固件标识不匹配 |
| `0x7F` | `UNKNOWN_ERROR` | 未定义错误（保留兜底） |

App 侧映射规则：

- `DeviceStatus.fromCode(int)` 负责状态码 → 枚举，未知值落到 `UNKNOWN_ERROR`；
- 调度层在状态码非 0 时抛出 `CommandFailure.DeviceRejected`，携带 `command`、`status`、
  可选 `detail`，UI 可直接展示 `status.describe()`（如 `0x05 鉴权失败`）。

## 4. 命令表

### 4.1 鉴权与设备信息

| 指令码 | 命令 | 方向 | 请求 payload | 成功应答 body |
| --- | --- | --- | --- | --- |
| `0x01` | `AUTH` | App → 设备 | `mode:u8, credentialLen:u16, credential` | `tokenLen:u16, token, expiresIn:u32, challengeLen:u16, challenge` |
| `0x02` | `GET_DEVICE_INFO` | App → 设备 | 空 | `vendor:str1, model:str1, serial:str1, hardwareVersion:str1, firmwareVersion` |
| `0x05` | `GET_BATTERY` | App → 设备 | 空 | `percent:u8, charging:u8(0/1), voltageMv:u16` |
| `0x06` | `GET_FIRMWARE_VERSION` | App → 设备 | 空 | `firmwareVersion` |

`firmwareVersion` 结构：`major:u8, minor:u8, patch:u8, build:u16, channel:str1`。

`AuthMode`：

| 值 | 枚举 | credential 含义 |
| --- | --- | --- |
| `0x01` | `TOKEN` | 长期令牌字节 |
| `0x02` | `CHALLENGE` | 客户端随机数（建议 16 字节） |

### 4.2 配置、参数与时间

| 指令码 | 命令 | 方向 | 请求 payload | 成功应答 body |
| --- | --- | --- | --- | --- |
| `0x03` | `SET_CONFIG` | App → 设备 | `key:str1, valueLen:u16, value` | 空 |
| `0x04` | `GET_CONFIG` | App → 设备 | `key:str1` | `key:str1, valueLen:u16, value` |
| `0x07` | `SET_TIME` | App → 设备 | `epochSeconds:u32, timezoneOffsetMinutes:i16` | `appliedEpochSeconds:u32` |
| `0x08` | `GET_TIME` | App → 设备 | 空 | `epochSeconds:u32, timezoneOffsetMinutes:i16` |
| `0x09` | `SET_PARAM` | App → 设备 | `paramId:u16, valueLen:u16, value` | 空 |
| `0x0A` | `GET_PARAM` | App → 设备 | `paramId:u16` | `paramId:u16, valueLen:u16, value` |
| `0x0B` | `FACTORY_RESET` | App → 设备 | `confirmToken:str1`（可为空串） | 空 |

### 4.3 OTA

| 指令码 | 命令 | 方向 | 请求 payload | 成功应答 body |
| --- | --- | --- | --- | --- |
| `0x10` | `START_OTA` | App → 设备 | `totalBytes:u32, crc16:u16, chunkSize:u16, firmwareVersion`<br>（可选尾部续传扩展，见 4.3.1） | `sessionId:u32, chunkSize:u16`<br>（可选 `acceptedOffset:u32`） |
| `0x11` | `OTA_DATA` | App → 设备 | `offset:u32, dataLen:u16, data` | `receivedBytes:u32` |
| `0x12` | `FINISH_OTA` | App → 设备 | `totalBytes:u32, crc16:u16` | `rebootRequired:u8(0/1)` |
| `0x13` | `ABORT_OTA` | App → 设备 | `reason:u8` | 空 |

`OtaAbortReason`：`0x00` 用户取消、`0x01` 超时、`0x02` 校验失败、`0x03` 传输异常、`0x04` 设备拒绝。

`OtaStage`（用于进度通知）：`0x00` 准备、`0x01` 传输、`0x02` 校验、`0x03` 应用、`0x04` 完成。

#### 4.3.1 续传扩展（v0.9.0）

**方案取舍**：没有新增独立的 `RESUME_OTA(0x14)`，而是在 `START_OTA` 基础字段之后追加
**可选 14 字节尾部扩展**。理由：

- 不续传时**不追加任何字节**，旧报文与新 App 发出的请求字节完全一致，旧设备无需改动；
- 续传仍复用 `START_OTA` 的会话建立与分片参数协商流程，不引入新状态机分支；
- 独立指令需要设备同时维护「START_OTA 状态」与「RESUME_OTA 状态」，兼容与测试成本更高；
- 代价是旧设备收到带扩展的请求会因尾部多余字节返回 `INVALID_PAYLOAD`（或 `UNSUPPORTED`），
  App 侧据此自动回退为不带扩展的旧格式并从 0 开始。

扩展字段（仅当 `resumeOffset > 0` 时发送）：

| 偏移 | 字段 | 类型 | 说明 |
| --- | --- | --- | --- |
| 0 | `extVersion` | u8 | 固定 `0x01` |
| 1 | `flags` | u8 | bit0 = `RESUME`，其余位必须为 0 |
| 2 | `resumeSessionId` | u32 | 上次设备分配的 `sessionId` |
| 6 | `resumeOffset` | u32 | 断点绝对偏移（payload 内，不含固件头），必须 `< totalBytes` |
| 10 | `payloadCrc32` | u32 | 固件头声明的 payload CRC-32，用于设备侧校验固件身份 |

设备解析规则：

1. 基础字段解析后 `remaining == 0`：旧格式，视为从头开始；
2. `remaining == 14`：解析扩展并校验 `extVersion`、`flags`；
3. 其它长度：返回 `INVALID_PAYLOAD`。

设备应答：

- 旧设备/未接受续传：只返回 `sessionId:u32, chunkSize:u16`，App 解码为 `acceptedOffset = 0`；
- 设备接受非 0 偏移：在基础 body 之后追加 `acceptedOffset:u32`（实际生效的起始偏移）。

设备可拒绝续传并返回以下错误码：

| 错误码 | 语义 | App 行为 |
| --- | --- | --- |
| `0x11` `OTA_RESUME_OFFSET_INVALID` | 偏移越界/不对齐 | 回退旧格式从头开始 |
| `0x12` `OTA_RESUME_CONTEXT_INVALID` | 会话/续传上下文已失效 | 回退旧格式从头开始 |
| `0x13` `OTA_FIRMWARE_MISMATCH` | 固件标识（版本/总长/CRC32/CRC16）不匹配 | 回退旧格式从头开始 |

**App 侧起点策略**（可测试、无歧义）：

| 场景 | 处理 |
| --- | --- |
| `acceptedOffset == 0` 且请求了续传 | 设备要求从头开始，从 0 传输并记录回退原因 |
| `0 < acceptedOffset <= resumeOffset` | 以设备确认值为起点（允许设备向下取整） |
| `acceptedOffset > resumeOffset` | 判定设备应答非法，升级失败并提示 |
| `acceptedOffset >= totalBytes` | 判定设备应答非法，升级失败并提示 |
| 未请求续传却返回非 0 `acceptedOffset` | 判定设备应答非法，升级失败并提示 |
| 续传请求被设备以任意非 0 状态码拒绝 | 自动重发不带扩展的 `START_OTA`，从 0 开始 |

### 4.4 系统与心跳

| 指令码 | 命令 | 方向 | 请求 payload | 成功应答 body |
| --- | --- | --- | --- | --- |
| `0x20` | `REBOOT` | App → 设备 | `delayMillis:u16, reason:u8` | 空 |
| `0x30` | `LOG_DUMP` | App → 设备 | `sinceSequence:u32, maxEntries:u16` | `count:u16, entries...` |
| `0x40` | `HEARTBEAT` | App → 设备 | `uptimeSeconds:u32` | `uptimeSeconds:u32, batteryPercent:u8` |

`LOG_DUMP` 每个条目：`level:u8, timestampSeconds:u32, message:str2`，
`level` 与 `LogLevel.priority` 一致（0=VERBOSE、1=DEBUG、2=INFO、3=WARN、4=ERROR）。

### 4.5 设备通知（flags bit1 = 1）

| 指令码 | 命令 | 通知 payload |
| --- | --- | --- |
| `0x41` | `HEARTBEAT_REPLY` | `batteryPercent:u8, uptimeSeconds:u32` |
| `0x42` | `DEVICE_STATUS_NOTIFY` | `state:u8, batteryPercent:u8, faultCode:u16` |
| `0x43` | `OTA_PROGRESS_NOTIFY` | `sessionId:u32, received:u32, total:u32, stage:u8` |

`state` 编码与核心状态机一致：

| 值 | 状态 |
| --- | --- |
| `0x00` | `DISCONNECTED` |
| `0x01` | `CONNECTING` |
| `0x02` | `CONNECTED` |
| `0x03` | `AUTHENTICATING` |
| `0x04` | `READY` |
| `0x05` | `DISCONNECTING` |
| `0x06` | `FAULT` |

## 5. 典型交互序列

### 5.1 令牌鉴权

```
App                              Device
 |-- AUTH(mode=TOKEN, cred) ------>|
 |<-- AUTH_OK(token, expires) -----|   status=0x00
 |-- GET_DEVICE_INFO ------------->|
 |<-- 设备信息 --------------------|
```

挑战模式：

```
App                              Device
 |-- AUTH(mode=CHALLENGE, nonce) ->|
 |<-- AUTH_OK(token, challenge) ---|   status=0x00
 |   （保存 challenge，用于下一轮挑战应答或会话恢复）
```

鉴权失败时设备返回 `status=0x05`，可带 UTF-8 详情：
App 侧 `ConcurrentCommandDispatcher.execute()` 抛出 `CommandFailure.DeviceRejected`。

### 5.2 读固件版本

```
App -- GET_FIRMWARE_VERSION(空 payload) --> Device
App <-- status=0x00, 1.2.3 (build 456, stable) -- Device
```

### 5.3 OTA 分片

```
App -- START_OTA(total=2048, crc16, chunk=64, version) --> Device
App <-- status=0x00, sessionId=0x10203040, chunkSize=64 -- Device

（循环，直到 offset + len == total）
App -- OTA_DATA(offset=0, data=64B) --> Device
App <-- status=0x00, receivedBytes=64 --> Device

Device -- OTA_PROGRESS_NOTIFY(sessionId, received, total, stage) --> App（可多次）

App -- FINISH_OTA(total=2048, crc16) --> Device
App <-- status=0x00, rebootRequired=1 --> Device
```

设备可在任意时刻用 `OTA_PROGRESS_NOTIFY` 上报进度；该通知不会被误配给 OTA 请求。

### 5.4 OTA 断点续传

App 在发起续传前先做本地门控：版本、payload 总长、CRC-32、CRC-16 必须与断点记录一致，
且本地预检通过；任一不满足都不发送续传扩展。

```
App -- START_OTA(total, crc16, chunk, version,
                 extVersion=1, flags=RESUME,
                 resumeSessionId, resumeOffset=1024, payloadCrc32) --> Device

alt 设备接受
  App <-- status=0x00, sessionId=新会话, chunkSize, acceptedOffset=1024 -- Device
  App -- OTA_DATA(offset=1024, ...) --> Device ... （从断点继续）
else 设备下调偏移
  App <-- status=0x00, acceptedOffset=768 -- Device
  App -- OTA_DATA(offset=768, ...) --> Device ... （以设备确认值为起点）
else 设备拒绝续传
  App <-- status=0x11 / 0x12 / 0x13 -- Device
  App -- START_OTA(旧格式，无扩展字段) --> Device
  App <-- status=0x00, sessionId, chunkSize -- Device
  App -- OTA_DATA(offset=0, ...) --> Device ... （回退为从头开始）
end
```

续传成功后仍按 `FINISH_OTA(totalBytes, crc16)` 校验整包；设备侧应以扩展中的
`payloadCrc32` 与本地 `totalBytes` 判断是否复用原断点上下文。

### 5.5 心跳

```
App -- HEARTBEAT(uptime=1000) --> Device
App <-- status=0x00, uptime=1000, battery=68 -- Device

Device -- HEARTBEAT_REPLY(battery=70, uptime=2000) --> App（主动通知）
```

## 6. 调度与容错语义

实现位于 `core/command/ConcurrentCommandDispatcher.kt`：

- **序号分配**：发出前在互斥保护下分配 `sequence`，并在 `pending` 表登记等待者；
  重试沿用同一 `sequence`，设备不会把重试当作新请求。
- **请求-应答配对**：只接受 `isResponse=true`、`sequence` 相同、`type` 相同的帧。
- **乱序应答**：不同 sequence 的应答各自路由到对应等待者，互不影响。
- **重复应答**：同一请求的第二次应答被 `CompletableDeferred.complete()` 拒绝并记录日志。
- **迟到 / 无主应答**：`pending` 中不存在对应 sequence 时直接丢弃并记录日志。
- **超时与退避**：`RetryPolicy(maxAttempts, timeoutMillis, initialRetryDelayMillis, backoffMultiplier)`，
  第 n 次重试等待 `initial * multiplier^(n-1)`。
- **传输异常**：计入重试；全部尝试均失败时抛 `CommandFailure.TransportFailure`。
- **设备拒绝**：状态码非 0 时抛 `CommandFailure.DeviceRejected`。
- **畸形 payload**：解析失败抛 `CommandFailure.InvalidResponse`。
- **通知隔离**：`isNotification=true` 的帧进入 `notifications` 流，不参与配对。

## 7. 已知限制与假设

1. 命令码、字段布局、错误码均为本项目自定义，未与真实固件联调；
   拿到厂商字典后需要同步修改 `CommandType`、`message/*Messages.kt` 与本文件。
2. `AUTH` 的挑战算法（如 HMAC-SHA256）尚未实现：协议层只负责解析并透传
   `challenge`，具体签名算法需按固件约定补充。
3. `SET_CONFIG` / `GET_CONFIG` / `SET_PARAM` / `GET_PARAM` 的 value 语义未定义，
   协议层按字节数组透传。
4. `LOG_DUMP` 的分页游标（`sinceSequence`）语义为假设值，真机可能使用时间戳或日志 ID。
5. 单帧 payload 上限 1024 字节；BLE 场景下 OTA 分片建议不超过 240 字节，
   避免依赖系统的长包拆分。
6. `FrameParser` 只做帧级 CRC 校验，不校验业务层 CRC（OTA 的 `crc16` 由设备在
   `START_OTA` / `FINISH_OTA` 中独立校验）。
