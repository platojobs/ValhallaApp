# 固件包格式、预检与断点续传

本文说明固件文件格式、本地预检规则、文件选择与分块读取、OTA 断点持久化，
以及协议级续传偏移确认与回退策略（v1.2.0 起预检与续传状态机已收敛）。协议报文本身见
[protocol.md](protocol.md)，分层与 OTA 时序见 [architecture.md](architecture.md)。

## 1. 固件文件格式

固件文件 = **104 字节固定头** + **payload**。OTA 协议只传输 payload，文件头留在本地，
用于解析与预检。

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 2 | magic | 固定 `0x56 0x46`（ASCII "VF"） |
| 2 | 1 | formatVersion | 固定 `1`，其它值视为不支持的格式 |
| 3 | 1 | flags | bit0=`HAS_SIGNATURE`；其余位保留必须为 `0`，未知位置位会被拒绝 |
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

长度约束：

- 无签名（flags bit0=0）：文件总长度必须等于 `104 + payloadLength`，否则抛
  `FirmwareException.PayloadSizeMismatch`；
- 含签名（flags bit0=1）：总长度必须等于 `104 + payloadLength + 3 + signatureLen`，
  否则抛 `SignatureSectionInvalid`。

文件不足 104 字节抛 `Truncated`。

### 1.1 签名段（v1.1.0，v2.1.0 起生产验签为 ECDSA-P256）

签名段**追加在 payload 之后**，不参与 OTA 协议传输（`totalBytes` 仍为 payload 长度），
只在本地解析与预检时读取：

| 偏移（相对签名段起点） | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 1 | algorithm | `0x01` = HMAC-SHA256（仅测试/模拟器）；`0x02` = ECDSA-P256（生产） |
| 1 | 2 | signatureLen | u16 小端，必须大于 0 |
| 3 | N | signature | ECDSA 时为 DER/X9.62 编码的 `r, s`；HMAC 时为 32 字节原始 MAC |

- 签名对象为 `SHA-256(文件头 104 字节 || payload)`；文件头包含 flags 与头部 CRC，
  因此篡改任何头字段都会导致验签失败。
- **签名格式统一为 DER（X9.62）**：JCA `NONEwithECDSA` 与 Apple
  `SecKeyVerifySignature(...DigestX962SHA256)` 都直接消费该格式；两端输入都是 32 字节
  摘要（不是原始报文），避免 `SHA256withECDSA` 造成二次哈希。
  `EcdsaSignatureCodec` 另提供 DER ↔ 原始 `r||s` 互转，供只接受原始字节的第三方工具；
  线格式不变，转换只在联调时使用。
- 失败分类：`PUBLIC_KEY_PARSE`（PEM/DER 无法解析）、`PUBLIC_KEY_UNSUPPORTED`
  （非 P-256）、`DIGEST_LENGTH`、`ALGORITHM_MISMATCH`（固件算法编号与验签器不符）、
  `SIGNATURE_FORMAT`（非 DER）、`SIGNATURE_MISMATCH`（内容篡改/密钥不一致）、
  `PLATFORM_ERROR`（平台加密库异常）。分类对象为 `EcdsaFailureCode`。
- 生产装配：`FirmwarePrecheckFactory.production(pem)` 返回
  `EcdsaP256FirmwareVerifier` + `REQUIRE`；公钥配置存在但无法解析时使用
  全部拒绝的验签器并保持 `REQUIRE`（失败关闭），不会静默放行未签名固件。
- **构建期公钥注入**（v2.2.0，仓库不内置任何生产密钥）：
  - 优先级：环境变量 `VALHALLA_FIRMWARE_PUBKEY` > Gradle property
    `valhalla.firmwarePublicKey` > 缺省；
  - `generateFirmwareKey` 生成 `firmware/signing-key.pem`：
    Android assets、JVM classpath、iOS 资源目录（iOS 另有编译期常量兜底）；
  - 内容支持 `-----BEGIN PUBLIC KEY-----`（SPKI）或 `-----BEGIN CERTIFICATE-----`
    （X.509）；私钥 PEM 会被明确拒绝；
  - 未注入时策略为 `WARN`（开发/联调默认），关于页显示「未配置」；
  - 注入但解析失败时仍为失败关闭（拒绝全部固件）。
- **跨端运行时验证（v2.2.0）**：`EcdsaTestVectors` 为唯一向量来源；
  Android instrumented 与 iOS 模拟器测试真实执行各自平台加密库，不再是「仅编译链接」的推断。
- `HmacSha256TestVerifier` 保留给测试与模拟器，生产装配不再引用。
- 未知算法编号 → `SignatureAlgorithmUnsupported`；签名段长度不符/被截断 →
  `SignatureSectionInvalid`。
- **兼容策略**：旧固件没有签名段，解析与 OTA 行为完全不变；`FirmwareSignaturePolicy`
  控制缺失签名时的行为：
  - `REQUIRE`：缺失即拒绝（生产构建推荐）；
  - `WARN`：缺失仅告警、不阻断（预检器默认值，兼容旧固件）；
  - `IGNORE`：完全忽略签名段。
  已签名但验签失败**始终**是错误，不受策略影响；已签名但当前构建未配置验签器时，
  `REQUIRE` 报错、`WARN` 告警。

### 错误类型

`FirmwareException` 为 sealed class，每类都有稳定的 `code`，UI 直接展示其中的中文
`message`：

| 类型 | code | 触发场景 |
| --- | --- | --- |
| `Truncated` | `firmware.truncated` | 文件不足文件头长度 |
| `InvalidMagic` | `firmware.magic` | 魔数不是 "VF" |
| `UnsupportedFormat` | `firmware.format` | formatVersion 不是 1 |
| `UnsupportedFlags` | `firmware.flags` | flags 含 bit0 之外的未知位 |
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
| `SignatureSectionInvalid` | `firmware.signature_section` | 签名段被截断、长度不符或为空 |
| `SignatureAlgorithmUnsupported` | `firmware.signature_algorithm` | 未知签名算法编号 |
| `SignatureMissing` | `firmware.signature_missing` | 未签名且策略为 `REQUIRE` |
| `SignatureInvalid` | `firmware.signature_invalid` | 签名与摘要不匹配（篡改/密钥错误） |
| `SignatureVerifierUnavailable` | `firmware.signature_unavailable` | 已签名但未配置验签器且策略为 `REQUIRE` |

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
| 文件大小上限 | `payloadLength > 32 MiB` → `PayloadTooLarge`（并跳过 CRC/签名计算） |
| 型号匹配 | 设备型号为空 → `HardwareUnknown`；不命中白名单且无 `*` → `HardwareModelMismatch` |
| 版本对比 | 设备版本为空 → `VersionUnknown`；目标版本 < 当前版本 → `VersionRollback` |
| 完整性 | 流式重算 payload CRC32，与头部声明不符 → `PayloadChecksumMismatch` |
| 续传一致性 | 同一次流式读取顺带计算协议层 CRC-16，供断点比对（不计入错误列表） |
| 固件签名 | 带签名段时在同一次扫描中产出 `SHA-256(头 || payload)` 并调用验签器；缺失时按策略报错/告警（见 1.1） |

**单次扫描**：CRC32、CRC16 与签名摘要都由 `FirmwarePackage.scanPayload()` 同一次流式
读取产出，payload 只读一遍；预检结果通过 `payloadCrc16` 暴露给 OTA 控制器复用，
`startPackage(knownCrc16 = ...)` 因此不会在 START 前再次完整扫描 payload。

版本比较顺序为 `major → minor → patch → build` 的字典序，忽略渠道名；**相同版本允许
重刷**，只有严格更低才判定回退。设备当前版本来自 `GET_FIRMWARE_VERSION` 应答，型号
来自 `GET_DEVICE_INFO` 应答。

预检结果为 `FirmwarePrecheckResult`，同时携带 `payloadCrc32`、`payloadCrc16`，
以及非阻断性的 `warnings`（例如「固件未签名」），通过 `warningSummary()` 单独展示。
`passed == false` 时，UI 禁用「开始升级」与「继续上次升级」按钮，并逐条展示错误原因；
OTA 控制器本身也不接受未经选择流程的固件包。

### 3.1 进度与取消

`FirmwareSelectionController` 在解析与扫描期间：

- 将解析/预检调度到 `Dispatchers.Default`，大文件不会阻塞 UI 线程；
- 每个 64 KiB 分块回调 `FirmwarePrecheckProgress(readBytes, totalBytes)`，UI 状态
  `prechecking` / `precheckProgress` 显示百分比进度；
- 调用 `cancelPrecheck()` 后，扫描在下一个分块边界抛出
  `FirmwarePrecheckCancelledException`；**取消不会破坏上一次可用选择**：新文件的数据源
  被关闭，旧固件包及其预检结果原样保留，用户可重新选择或直接继续使用旧选择；
- 协程取消（页面销毁）同样会关闭新数据源，但不吞掉 `CancellationException`；
- 选择/重检由 `Mutex` 串行化，避免 `recheck` 与 `pickAndPrecheck` 并发覆盖状态。

`FirmwarePrechecker` 的构造参数：

```kotlin
FirmwarePrechecker(
    maxPayloadBytes = 32 MiB,
    signatureVerifier = EcdsaP256FirmwareVerifier.fromPem(publicKeyPem),
    signaturePolicy = FirmwareSignaturePolicy.REQUIRE,
)
```

应用装配使用 `FirmwarePrecheckFactory.production(rememberFirmwareSigningKeyPem())`：
注入公钥后为 ECDSA + `REQUIRE`；未注入时为 `WARN`，关于页显示「未配置验签公钥」。
`rememberFirmwareSigningKeyPem()` 的平台读取顺序为：构建期生成的资源/classpath →
`BuildInjectedFirmwareKey` 常量；两者都缺失返回 null。
测试/模拟器仍可显式使用 `HmacSha256TestVerifier`。

## 4. 断点持久化

### 数据结构

`OtaResumePoint`（已迁移到 `core/firmware`）：

| 字段 | 含义 |
| --- | --- |
| `sessionId` | 设备在 `START_OTA` 应答中分配的会话号 |
| `firmwareVersion` | 固件版本（渠道仅用于展示，身份判定时忽略） |
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

断点生命周期以**设备会话**为锚点，规则统一如下（`OtaController` 内实现）：

| 事件 | 断点动作 |
| --- | --- |
| `prepareRun()`（每次开始/重试） | 清空内存 `lastResumePoint`，**不**预清除存储 |
| `START_OTA` 未建立会话（超时/链路失败） | 保留调用方传入的旧断点，便于原样重试 |
| `START_OTA` 成功，设备确认 `acceptedOffset == 0` | 旧会话上下文失效，**立即清除**存储 |
| `START_OTA` 成功，设备确认 `acceptedOffset > 0` | **立即落盘** `(sessionId, acceptedOffset)`，不等下一个 4 KiB |
| 传输中每累计 4 KiB 成功写入 | 以当前 `sessionId` 与精确 `sentBytes` 覆盖 |
| 升级成功（`FINISH_OTA` OK） | 清除 |
| 用户主动中止（`abort()` / `ABORT_OTA`） | 清除（用户放弃，不应自动续传） |
| 外部协程取消（页面销毁等） | 按当前精确 `sentBytes` 落盘（可能恢复） |
| 链路断开 / 读取失败 / 设备错误 | 会话已建立则按当前 `sentBytes` 落盘；未建立则保留旧断点 |

`checkpointStore.clear()/save()` 都通过 `runCatching` 包裹：平台存储异常不会把一次成功
升级打成失败，也不会让失败路径再次抛出。

**设备下调偏移**时（`acceptedOffset < resumeOffset`），控制器在 START 成功后立刻把
断点改为设备确认值；因此即使首个分片前崩溃，也不会续到旧的高偏移。

### 恢复与失效条件

恢复入口为 `OtaController.startPackage(firmware, resumeFrom)`（UI 显式传入当前选择校验
后的断点），以及 `resume(firmware)`（内存 payload）。`resume()` 优先使用内存中的
`lastResumePoint`，进程重启后为空则回退读取持久化存储，避免静默从头开始。

是否进入续传由两层门控决定：

**第一层：本地门控（`FirmwareSelectionController.resolveResume`）**

1. 断点 `isValid()`：`sentBytes` 在 `1 until totalBytes`，`totalBytes/crc16/crc32`
   在合法范围；
2. `firmwareVersion`（忽略渠道名）、`totalBytes`、`crc32` 与当前固件一致；
3. 本地预检通过；
4. 预检计算出的 `payloadCrc16` 与断点记录一致。

任一不满足都不展示「继续上次升级」，并在 UI 显示具体原因（断点失效 / 固件标识不匹配 /
预检未通过 / CRC16 不一致）。断点数据本身保留，用户仍可手动「清除断点」。

**第二层：设备确认（`OtaController`）**

本地门控通过后，`START_OTA` 携带续传扩展（`resumeSessionId`、`resumeOffset`、
`payloadCrc32`），最终起点以设备应答的 `acceptedOffset` 为准：

| 设备应答 | 控制器行为 |
| --- | --- |
| `acceptedOffset == 0` | 设备要求从头开始，从 0 传输，并记录回退原因 |
| `0 < acceptedOffset <= resumeOffset` | 以设备确认值为起点（允许向下取整） |
| `acceptedOffset > resumeOffset` | 应答非法，进入 `Failed` |
| `acceptedOffset >= totalBytes` | 应答非法，进入 `Failed`（**不进入 FINISH**） |
| 未请求续传却返回非 0 偏移 | 应答非法，进入 `Failed` |
| 续传请求被设备以非 0 状态码拒绝 | 自动重发不带扩展的旧格式 `START_OTA`，从 0 开始 |

### 3.2 `acceptedOffset == totalBytes` 的结论（v2.1.0 补充）

模拟器新增 `forcedAcceptedOffset` 与 `allowCompleteResume` 两个场景开关：

- 默认 `allowCompleteResume = false`：模拟器自身把该应答视为非法，返回
  `OTA_RESUME_OFFSET_INVALID`，客户端不会看到 `acceptedOffset == totalBytes`；
- 置为 `true` 并配合 `forcedAcceptedOffset = totalBytes` 时，模拟器会真的返回
  「已接收完」，用于压测客户端防御分支。

结论（沿用 v1.2.0 的保守策略）：**客户端直接失败，不发送 `FINISH_OTA`，也不保留
由设备声称的断点**。理由是无法确认设备侧已完整接收 payload 且校验通过，若直接 FINISH
会把不可信状态写回本地；宁可让用户重新发起一轮完整 OTA。端到端用例
`设备返回 acceptedOffset 等于 totalBytes 时保守失败且不发送 FINISH` 断言了
`Failed`、零字节发送、无 `FINISH`/`OTA_DATA` 请求与断点被清除。

回退/下调信息记录在 `OtaState.Transferring.resumeFallbackReason`，UI 直接展示。
续传时 `sentBytes` 初始值等于设备确认起点，进度始终以完整 `totalBytes` 为分母。

**本地门控拒绝**（断点失效、固件不一致、CRC16 不一致）时，控制器仍会正常从 0 开始，
并在 `resumeFallbackReason` 中写明原因，不再静默回退。

**失败提示**按可操作原因区分：链路断开提示重新连接后续传；固件读取失败提示重新选择文件
（附 offset/length）；设备超时提示确认距离与电量；传输失败提示重连。无法识别的异常保留
原始协议错误文案。

## 5. 测试覆盖

纯逻辑层测试（JVM 可执行，不依赖平台 API）：

- `Crc32Test`：标准向量、增量计算与空数据；
- `FirmwareHeaderTest`：编码解码往返、魔数/格式/标志/头部 CRC、型号字段、长度边界；
- `FirmwareParserTest`：截断、长度不符、分块读取越界、不同分块大小的 CRC32 一致性；
- `FirmwarePrecheckTest`：大小、型号、版本、CRC 分支与多错误收集，CRC16 同步计算；
- `FirmwarePrecheckProgressTest`：进度从 0 单调到 100%、用户取消抛出专用异常、
  已签名固件只扫描 payload 一次；
- `FirmwarePrecheckResumeMatrixTest`：「版本/型号/CRC/签名 × 断点有效/失效/越界」
  组合矩阵，覆盖渠道差异、`*` 通配、大小写/空白型号、偏移为 0/等于总量；
- `OtaResumePointTest`：编解码往返、非法字符串、有效性、固件身份匹配、内存存储；
- `FirmwareSelectionControllerTest`：选择成功/取消/损坏文件、预检门控、断点加载与清除、
  断点失效/固件标识不匹配/CRC16 不一致/预检失败时的续传禁用与原因提示、预检取消回滚；
- `OtaResumeProtocolTest`：旧格式请求/应答字节兼容、续传扩展往返、非法扩展长度/版本/标志、
  非法 `resumeOffset`、新错误码映射；
- `OtaControllerPackageTest`：只传输 payload、失败落盘精确断点、设备接受/下调/拒绝续传偏移、
  回退旧格式、非法 `acceptedOffset`、续传进度与总长计算、固件身份/CRC16 不一致时不发扩展、
  下调后立即落盘、START 未建立保留旧断点、本地门控回退原因、复用预检 CRC16 避免二次扫描。

平台选择器、SAF/UIDocumentPicker、SharedPreferences/NSUserDefaults 的实际行为无法在
JVM 测试覆盖，已通过 Android `assembleDebug` 与 iOS
`linkDebugFrameworkIosSimulatorArm64` 编译验证，但**未在真机验证**。

续传扩展与 `acceptedOffset` 的设备行为同样是协议级假设：设备是否接受断点、是否复用
原 `sessionId`、是否按旧格式拒绝扩展，都需要真机固件联调确认。App 侧已通过回退旧格式
保证兼容，但回退后的实际传输表现仍需硬件验证。

## 6. 固件库与复用（v2.5.0）

- OTA 页的本地固件库只保存文件名、版本、渠道、型号白名单、总大小、CRC-32、引入时间、
  最近作用设备与结果，以及平台文件引用（Android SAF URI / iOS 文件路径），**不复制二进制**。
- 记录身份为 `CRC-32 + payload 总长 + 版本`，同一固件从不同路径重新选择会归并为一条。
- 复用时分四种结论：可用、文件不可访问、读取失败、CRC 不一致、未找到；后三种要求重新选择，
  不会把失效引用当作可用固件。
- 默认保留 20 条，淘汰顺序为「非常用优先、最久未用优先」；编码损坏时回退为空列表。

## 7. 升级向导（v2.6.0）

- 五步：选择文件与版本 → 预检结果 → 升级前确认 → 传输中 → 结果；步骤展示为「第 X/5 步」。
- 预检结果按六条规则逐条展示（文件结构 / 大小上限 / 设备型号 / 版本方向 / 内容完整性 / 固件签名），
  阻断项标红并给出原始错误，非阻断提示单独列出；未通过不能进入确认。
- 传输中禁止回退，取消等同中止（沿用既有 `Aborted` 收敛）；结果页展示失败原因与是否可续传。
- 断点在确认页明确展示「从 X% 继续」或「断点已失效：原因」，失效时仍可从头上传。
- 步骤推进与取消判断在 `feature/ota/OtaWizard.kt`，不改动既有 OTA 状态机。
