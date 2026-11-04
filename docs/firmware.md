# 固件包格式、预检与断点续传

本文说明 v0.8.0 引入的固件文件格式、本地预检规则、文件选择与分块读取、OTA 断点持久化
及失效条件。协议报文本身见 [protocol.md](protocol.md)，分层与 OTA 时序见
[architecture.md](architecture.md)。

## 1. 固件文件格式

固件文件 = **104 字节固定头** + **payload**。OTA 协议只传输 payload，文件头留在本地，
用于解析与预检。

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 2 | magic | 固定 `0x56 0x46`（ASCII "VF"） |
| 2 | 1 | formatVersion | 固定 `1`，其它值视为不支持的格式 |
| 3 | 1 | flags | 保留，必须为 `0` |
| 4 | 2 | major | u16 小端，实际限制 `0..255` |
| 6 | 2 | minor | u16 小端，实际限制 `0..255` |
| 8 | 2 | patch | u16 小端，实际限制 `0..255` |
| 10 | 2 | build | u16 小端，`0..65535` |
| 12 | 1 | channelLength | 渠道名长度，`0..16` |
| 13 | 16 | channel | ASCII，不足补零；允许 `A-Z a-z 0-9 . _ -` |
| 29 | 1 | modelCount | 目标型号数量，`1..4` |
| 30 | 64 | models | 4 个 16 字节字段，ASCII，不足补零 |
| 94 | 4 | payloadLength | u32 小端，必须大于 0 |
| 98 | 4 | payloadCrc32 | u32 小端，payload 的 CRC-32/IEEE |
| 102 | 2 | headerCrc16 | 前 102 字节的 CRC-16/CCITT-FALSE |

型号字段规则：

- 每个型号非空、最长 16 字节、不可重复（大小写不敏感）；
- 允许的字符为 `A-Z a-z 0-9 . _ -`；
- `*` 只能独占整个字段，表示通配所有型号；`A*` 之类视为非法。

校验算法：

- **头部**：复用协议层的 `com.valhalla.core.protocol.Crc16`（CCITT-FALSE，初值
  `0xFFFF`，多项式 `0x1021`），覆盖偏移 `0..101`，结果写入 `102..103`。
- **payload**：统一使用 **CRC-32/IEEE**（反射多项式 `0xEDB88320`，初值/终值异或
  `0xFFFFFFFF`），实现位于 `core/firmware/Crc32.kt`。选择 CRC32 而不是协议 CRC16，
  是因为整包完整性需要更强区分度；协议层 `START_OTA` / `FINISH_OTA` 仍使用
  `crc16`，两者针对同一段 payload 独立计算，互不替代。

长度约束：文件总长度必须等于 `104 + payloadLength`，否则抛
`FirmwareException.PayloadSizeMismatch`。文件不足 104 字节抛 `Truncated`。

### 错误类型

`FirmwareException` 为 sealed class，每类都有稳定的 `code`，UI 直接展示其中的中文
`message`：

| 类型 | code | 触发场景 |
| --- | --- | --- |
| `Truncated` | `firmware.truncated` | 文件不足文件头长度 |
| `InvalidMagic` | `firmware.magic` | 魔数不是 "VF" |
| `UnsupportedFormat` | `firmware.format` | formatVersion 不是 1 |
| `UnsupportedFlags` | `firmware.flags` | flags 非 0 |
| `InvalidHeaderField` | `firmware.header` | 字段越界、字符非法、重复型号等 |
| `HeaderChecksumMismatch` | `firmware.header_crc` | 头部 CRC16 不符 |
| `PayloadSizeMismatch` | `firmware.size` | 声明长度与实际文件长度不符 |
| `PayloadTooLarge` | `firmware.too_large` | payload 超过预检上限 |
| `PayloadChecksumMismatch` | `firmware.payload_crc` | payload CRC32 不符 |
| `HardwareUnknown` | `firmware.hardware_unknown` | 设备型号未知 |
| `HardwareModelMismatch` | `firmware.model` | 型号不在白名单且非 `*` |
| `VersionUnknown` | `firmware.version_unknown` | 设备当前版本未知 |
| `VersionRollback` | `firmware.version_rollback` | 目标版本低于当前版本 |
| `ReadFailure` | `firmware.read` | 分块读取越界或 IO 失败 |

## 2. 文件选择与分块读取

统一接口（feature 层）：

```kotlin
interface FirmwarePicker {
    suspend fun pickFirmware(): PickedFirmware?   // 用户取消返回 null
}

data class PickedFirmware(val name: String, val source: FirmwareSource)
```

`FirmwareSource` 提供 `size`、`suspend fun read(offset, length)` 与 `close()`；
`FirmwarePackage` 在其上封装 payload 偏移，OTA 分片直接调用
`readPayload(offset, length)`，不会把整包读入内存。

平台实现：

- **Android**：`rememberFirmwarePicker()` 使用
  `ActivityResultContracts.OpenDocument()`（SAF），选中后通过 `ContentResolver`
  `openFileDescriptor(uri, "r")` 打开只读句柄，`FileChannel.position(offset)` 随机
  读取，读取调度到 `Dispatchers.IO`，`Mutex` 串行化同一句柄。
- **iOS**：`UIDocumentPickerViewController(forOpeningContentTypes = [UTTypeData],
  asCopy = true)` 导入文件到 App 沙盒，再用 POSIX `open` / `pread` / `close`
  按偏移读取，`Mutex` 串行化。`asCopy` 避免 security-scoped URL 的访问生命周期问题。
- **JVM**：测试宿主无系统选择器，`rememberFirmwarePicker()` 返回空实现。

`FirmwareParser.parse(name, source)` 读取头部并核对长度；失败时调用方负责关闭 source，
成功时 `FirmwarePackage.close()` 接管 source 的生命周期。

## 3. 本地预检

`FirmwarePrechecker` 在 OTA 启动前收集所有可判定问题，不提前短路：

| 规则 | 判定 |
| --- | --- |
| 文件大小上限 | `payloadLength > 32 MiB` → `PayloadTooLarge`（并跳过 CRC 计算） |
| 型号匹配 | 设备型号为空 → `HardwareUnknown`；不命中白名单且无 `*` → `HardwareModelMismatch` |
| 版本对比 | 设备版本为空 → `VersionUnknown`；目标版本 < 当前版本 → `VersionRollback` |
| 完整性 | 流式重算 payload CRC32，与头部声明不符 → `PayloadChecksumMismatch` |

版本比较顺序为 `major → minor → patch → build` 的字典序，忽略渠道名；**相同版本允许
重刷**，只有严格更低才判定回退。设备当前版本来自 `GET_FIRMWARE_VERSION` 应答，型号
来自 `GET_DEVICE_INFO` 应答。

预检结果 `FirmwarePrecheckResult.passed == false` 时，UI 禁用「开始升级」与「继续上次
升级」按钮，并逐条展示错误原因；OTA 控制器本身也不接受未经选择流程的固件包。

## 4. 断点持久化

### 数据结构

`OtaResumePoint`（已迁移到 `core/firmware`）：

| 字段 | 含义 |
| --- | --- |
| `sessionId` | 设备在 `START_OTA` 应答中分配的会话号 |
| `firmwareVersion` | 固件版本（含渠道） |
| `sentBytes` | 已确认写入设备的 payload 字节数（绝对偏移） |
| `totalBytes` | payload 总长度 |
| `crc16` | 协议层 payload CRC-16/CCITT-FALSE |
| `crc32` | 固件头声明的 payload CRC-32，用于本地识别固件身份 |

### 存储编码

`OtaResumePointCodec` 使用固定分隔符字符串（字段只允许安全字符，无需转义）：

```
v1|sessionId|major.minor.patch.build|channel|totalBytes|sentBytes|crc16|crc32
```

平台存储（`data/firmware/` 的 `createOtaCheckpointStore()`）：

- Android：`SharedPreferences("valhalla_ota")`，键 `resume_point`；
- iOS：`NSUserDefaults.standardUserDefaults`，键 `valhalla.ota.resume_point`；
- JVM：内存实现。

解码时任何字段解析失败或 `isValid()` 不通过都返回 `null`，按「无断点」处理，不抛出
平台异常。

### 写入与清除时机

- 传输过程中每累计 **4 KiB** 成功写入落盘一次，避免频繁写 SharedPreferences；
- 传输失败或协程被取消时，记录精确的 `sentBytes` 并落盘；
- 升级成功、用户主动中止（`ABORT_OTA`）、`reset()`、用户点击「清除断点」时清除；
- `start` / `startPackage` 重新开始时会重置内存中的 `lastResumePoint`，但不会
  预先清除存储，只有新会话真正开始后续写覆盖。

### 恢复与失效条件

恢复入口为 `OtaController.startPackage(firmware, resumeFrom)`，按下述规则决定是否使用
断点：

1. 断点 `isValid()`：`sentBytes` 在 `1 until totalBytes`，`totalBytes/crc16/crc32`
   在合法范围；
2. `firmwareVersion`、`totalBytes`、`crc32` 与当前固件完全一致；
3. 新 `START_OTA` 应答的 `sessionId` 与断点相同；
4. 设备未处于中止/复位状态。

任一条件不满足即**从偏移 0 重新开始**，不会产生错误的续传。UI 仅在预检通过且断点与
所选固件匹配时显示「继续上次升级」。

**协议限制（未验证）**：当前协议没有独立的恢复指令，`START_OTA` 也不携带起始偏移。
只有设备在新会话中复用原 `sessionId` 才能续传；若设备每次 `START_OTA` 都分配新会话，
则断点实际只能作为记录与 UI 提示，无法续传。该行为依赖设备固件实现，需真机联调确认。

## 5. 测试覆盖

纯逻辑层测试（JVM 可执行，不依赖平台 API）：

- `Crc32Test`：标准向量、增量计算与空数据；
- `FirmwareHeaderTest`：编码解码往返、魔数/格式/标志/头部 CRC、型号字段、长度边界；
- `FirmwareParserTest`：截断、长度不符、分块读取越界、不同分块大小的 CRC32 一致性；
- `FirmwarePrecheckTest`：大小、型号、版本、CRC 分支与多错误收集；
- `OtaResumePointTest`：编解码往返、非法字符串、有效性、固件身份匹配、内存存储；
- `FirmwareSelectionControllerTest`：选择成功/取消/损坏文件、预检门控、断点加载与清除；
- `OtaControllerPackageTest`：只传输 payload、失败落盘精确断点、同/异会话恢复、
  固件身份不一致时回退到 0。

平台选择器、SAF/UIDocumentPicker、SharedPreferences/NSUserDefaults 的实际行为无法在
JVM 测试覆盖，已通过 Android `assembleDebug` 与 iOS
`linkDebugFrameworkIosSimulatorArm64` 编译验证，但**未在真机验证**。
