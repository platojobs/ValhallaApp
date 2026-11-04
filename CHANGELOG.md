# CHANGELOG

本文件记录 ValhallaApp 的版本演进，按「阶段完成即提交 + 打 annotated tag」的节奏维护。

## v1.4.0 - 配置能力协商、日志导出与交互稳健性

### 新增

- **配置能力协商**
  - `core/config` 新增 `ConfigScope`（最低/最高固件版本 + 型号白名单）、
    `DeviceConfigTarget`（固件版本/型号/厂商快照）与 `ConfigCapabilityResult`；
    版本比较沿用 `major.minor.patch.build`，忽略渠道；
  - `ConfigCatalog` 为 `led_enabled`（≥1.0.0）、`sample_interval_ms`（≥1.2.0）、
    `tx_power_dbm`（≥1.3.0 且型号 VH-1000/VH-2000）声明能力；`device_name` 与只读键为通用键；
  - `ConfigEditorController` 新增 `updateTarget()`：按快照重算可见性，只读取可见键，
    隐藏项汇总展示原因；
  - 降级策略：固件版本或型号未知时仅保留通用键，配置区提示「先刷新设备信息」；
  - 保存前二次能力校验：能力失效（如读取版本后设备降级）直接拒绝，不发 `SET_CONFIG`。
- **报文日志导出**
  - `PacketLogExporter` 输出本地化文本：绝对时间（含时区偏移）+ 相对时间、方向、
    `RESPONSE/NOTIFY`、seq、命令、摘要、错误与 HEX；
  - `PacketLogExportOptions` 默认 200 条 / 512 KiB / 单条 HEX 4096 字符，
    超限截断更旧条目并在头部标注；
  - 文件名带本地时间戳，避免重复导出覆盖；
  - `data/share` 新增 `TextSharer` / `createTextSharer()`：Android 写 cache 后经
    `FileProvider` 发 `ACTION_SEND`（新增 provider 与 `xml/valhalla_file_paths.xml`），
    iOS 写临时目录后展示 `UIActivityViewController`，JVM 返回 `UNAVAILABLE`；
  - `currentTimeZoneOffsetMinutes()` 由平台实现，`core` 只接收偏移参数。
- **检索与交互**
  - `PacketLogIndex` 在日志快照变化时构建一次索引，关键字搜索只做一次小写 `contains`；
  - 日志页输入 150ms 去抖，过滤结果不再随每次按键全量重算；
  - 日志卡片展示「本地绝对时间 · 相对时间」，「复制单条」使用同一本地化格式；
  - 配置保存失败保留用户输入，不清空表单。

### 测试

- 新增 24 项测试：
  - `core`：能力矩阵（未知快照、版本下限/上限、型号白名单与大小写、渠道忽略、可见键顺序），
    导出字段与文件名、条目/字节上限截断、HEX 超长截断、绝对时间负值与跨日时区、
    相对时间边界；索引与线性过滤结果一致、数量上限与空快照；
  - `feature`：未知快照仅读通用键、低版本/型号不匹配隐藏、保存前二次校验拒绝且保留输入、
    能力未知时通用键仍可保存、保存失败保留输入。
- 全量 `:core:jvmTest :data:jvmTest :feature:jvmTest`：335 项通过
  （core 230 + data 11 + feature 94；v1.3.0 为 311 项，既有断言未削弱）。
- `:composeApp:assembleDebug` 与 `:composeApp:linkDebugFrameworkIosSimulatorArm64` 构建通过。

### 未验证

- Android FileProvider 分享、iOS `UIActivityViewController` 在真机/模拟器上的实际弹窗与
  第三方应用接收行为未实机验证（JVM 仅验证导出内容与上限）；
- 能力声明的版本锚点当前来自命令表约定，未与真实设备固件版本矩阵联调；
- 设备固件实际拒绝 `SET_CONFIG` 的错误码口径未变，仍以 `DeviceStatus` 文案展示。

## v1.3.0 - 新增功能：设备档案、配置编辑与报文日志

### 新增

- **设备档案持久化**
  - `core/profile` 新增 `DeviceProfile`、`OtaHistoryRecord`、`DeviceProfileCodec`、
    `DeviceProfileStore` / `InMemoryDeviceProfileStore` 与 `DeviceProfileRepository`；
  - 档案字段：设备 id / 显示名 / 厂商 / 型号 / 最后连接时间 / 最近固件版本 / 电量 /
    充电状态 / OTA 历史（版本、结果、耗时、错误码、原因、发送字节）；
  - 提供按最后连接时间倒序列表、单条查询、删除、清空；默认最多 50 个设备、
    每个设备最多 10 条 OTA 历史，超出淘汰最旧；
  - `data/profile` 新增 `createDeviceProfileStore()`：Android SharedPreferences、
    iOS NSUserDefaults、JVM 内存；仓储与编解码在 `commonMain`，可纯逻辑测试；
  - 选择「平台键值 + 版本化行式编码」而非 SQLDelight，原因与表结构见 `docs/storage.md`。
- **档案写入联动**
  - `DeviceDetailController` 连接建立时 `recordConnection`，信息刷新成功后
    `updateSnapshot`（固件、电量、厂商/型号）；
  - `OtaController` 在成功、失败、中止/取消终态写入 `OtaHistoryRecord`，
    失败记录设备错误码与可操作原因；档案写入失败不影响主流程。
- **配置读写 UI**
  - `core/config/ConfigCatalog` 定义可编辑键（`device_name`、`led_enabled`、
    `sample_interval_ms`、`tx_power_dbm`）与只读键，并提供字符串长度、布尔、整数区间校验；
  - `feature/config/ConfigEditorController` 读取配置、校验后下发 `SET_CONFIG`；
    未知键/只读键标注原因，校验失败不发请求，同一时刻仅允许一个保存请求在途；
  - 详情页新增配置区：读取中/保存中/已保存/失败状态、校验提示与设备错误码可见。
- **报文日志可视化**
  - `PacketRecordingFrameLink` 装饰 `BleFrameLink`，记录 TX/RX 报文；
  - `InMemoryPacketLog` 环形缓冲默认 500 条，超出淘汰最旧，避免 OTA 长传输内存膨胀；
  - 每条记录包含方向、命令、seq、完整 HEX、解析摘要与错误标记；
  - 详情页新增「报文日志」页：按时间倒序、按命令/错误过滤、关键字搜索、
    单条复制与清空；日志写入不参与请求/应答配对，不阻塞主流程。
- **文档**
  - 新增 `docs/storage.md`：持久化选型取舍、平台 driver、表结构、序列化格式、保留策略与未验证项；
  - `docs/architecture.md` 增加 v1.3.0 档案/配置/报文日志数据流与装配说明。

### 测试

- 新增 35 项测试：
  - `core`：档案 CRUD/排序/保留淘汰/OTA 历史/编码往返/损坏回退，
    配置表校验与格式化，报文日志环形淘汰/命令与错误过滤/关键字搜索/解析失败；
  - `feature`：配置读取/校验/保存/只读/重复提交/设备拒绝/单键失败隔离，
    OTA 成功/失败/中止历史写入，连接建档与信息快照更新。
- 全量 `:core:jvmTest :data:jvmTest :feature:jvmTest`：311 项通过
  （core 211 + data 11 + feature 89；v1.2.0 为 276 项，既有断言未削弱）。
- `:composeApp:assembleDebug` 与 `:composeApp:linkDebugFrameworkIosSimulatorArm64` 构建通过。

## v1.2.0 - 功能完善：预检单一入口与断点续传状态机收敛

### 修复

- **预检重复计算**：`FirmwarePackage.scanPayload()` 单次流式读取同时产出 CRC-32、
  协议 CRC-16 与签名摘要；已签名固件不再扫描 payload 两遍；CRC32/CRC16/签名摘要
  三套口径统一由该入口产出。
- **OTA 启动重复扫描**：`startPackage(..., knownCrc16)` 复用预检 CRC-16，
  START_OTA 前不再把 payload 完整再读一遍（大文件下曾表现为长时间无反馈）。
- **断点落盘时机**：`START_OTA` 成功后立即按设备 `acceptedOffset` 落盘；
  确认偏移为 0 时立即清除旧断点；设备下调偏移后不会再残留旧的高偏移。
- **断点清除/落盘矛盾**：成功、用户中止清除；外部取消、失败落盘；START 未建立保留旧断点；
  `checkpointStore` 读写在失败路径中通过 `runCatching` 隔离，不再把成功升级打成失败。
- **`resume()` stale 状态**：进程重启后内存断点为空时回退读取持久化存储，不再静默从 0 开始。
- **本地续传门控静默回退**：断点失效/固件不一致/CRC16 不一致时记录
  `resumeFallbackReason`，UI 可展示具体原因。
- **版本口径不一致**：预检与断点身份统一为「`major.minor.patch.build` 比较、忽略渠道、
  同版本允许重刷」，避免渠道切换被误判为固件不匹配。
- **选择失败/取消后的 stale 状态**：新文件解析与预检成功前不再关闭上一次固件；
  失败或取消时回滚到上一次可用选择，数据源正确关闭。
- **可操作错误提示**：OTA 失败按链路断开、固件读取失败（附 offset/length）、
  设备超时、传输失败分别给出建议；预检错误保留原有分类与错误码。

### 新增

- `FirmwareSelectionController` 预检进度（`prechecking` / `precheckProgress`）
  与 `cancelPrecheck()`；解析/扫描调度到 `Dispatchers.Default`，大文件不阻塞 UI；
  选择与重检通过 `Mutex` 串行化。
- `FirmwarePrecheckProgress` / `FirmwarePrecheckCancelledException` /
  `FirmwareScan` / `FirmwareChecksums` 公共类型。
- 协议层 CRC-16 由同一次扫描产出并透传到 OTA 启动，避免跨链路重复读取。
- 新增测试：`FirmwarePrecheckProgressTest`、`FirmwarePrecheckResumeMatrixTest`，
  `FirmwareSelectionControllerTest` 取消回滚用例，`OtaControllerPackageTest`
  下调立即落盘 / START 未建立保留断点 / 本地门控原因 / CRC16 复用 / resume 回退持久化断点。
- 文档：`docs/firmware.md` 增加预检规则、进度取消、断点生命周期与状态转移表；
  `docs/architecture.md` 更新 OTA 状态机与续传说明。

### 测试

- 全量 `:core:jvmTest :data:jvmTest :feature:jvmTest`：276 项通过（v1.1.0 为 263 项，
  既有断言未削弱）。
- `:composeApp:assembleDebug` 与 `:composeApp:linkDebugFrameworkIosSimulatorArm64` 构建通过。

## v1.1.0 - 安全加固：签名鉴权、固件验签与令牌安全存储

### 新增

- **AUTH 签名挑战应答（`AuthMode.SIGNED=0x03`）**
  - `core/security` 新增纯 Kotlin `Sha256Digest` / `HmacSha256`（无平台加密依赖，
    可在 `jvmTest` 验证），常量时间比较；
  - `AuthSignatureMessage` 规范化签名消息
    `version || challenge || clientNonce || deviceDigest`（长度前缀，小端序）；
  - `HmacAuthSigner` 签名器与 `SignedAuthCredential` 协议编解码；
  - `DeviceDetailController.authenticateSigned(...)`：无挑战时自动先发 `CHALLENGE`，
    再以 `SIGNED` 提交凭据；失败进入 `FAULT` 并保留 `AUTH_FAILED`；
  - 测试桩 `DeviceSimulator` 支持 `CHALLENGE` 下发挑战、`SIGNED` 验签，新增
    `SimulatorScenario.authChallenge` / `authSecret` / `deviceDigest`。
- **固件签名校验**
  - 文件头 flags bit0 = `HAS_SIGNATURE`，其余位仍拒绝；签名段追加在 payload 之后：
    `algorithm:u8 | signatureLen:u16 | signature`；
  - `FirmwarePackage` 暴露 `signature` 与 `computeSignatureDigest()`
    （`SHA-256(头 104 字节 || payload)`，流式读取）；
  - `FirmwareSignatureVerifier` 验签接口 + `HmacSha256TestVerifier` 测试密钥完整验签；
    `FirmwareSignatureAlgorithm` 预留 ECDSA-P256（`0x02`）；
  - 预检新增 `FirmwareSignaturePolicy`（`REQUIRE` / `WARN`（默认）/ `IGNORE`）与
    签名分支；新增 `SignatureMissing` / `SignatureInvalid` /
    `SignatureVerifierUnavailable` / `SignatureSectionInvalid` /
    `SignatureAlgorithmUnsupported` 错误码；
  - `FirmwarePrecheckResult` 新增非阻断 `warnings` 与 `warningSummary()`；
  - `SimulatorFirmware.packageBytes(..., signingSecret = ...)` 可生成已签名固件。
- **会话令牌安全存储**
  - `TokenStore` 接口（`save` / `load` / `clear` / `loadValid(now)`）、`AuthToken`
    与过期判断、`InMemoryTokenStore`；
  - `data/security/TokenStoreFactory.kt` 统一 `expect fun createTokenStore()`：
    Android 使用 Android Keystore AES-256/GCM 加密后写 SharedPreferences；
    iOS 使用 Keychain 通用密码；JVM 使用内存实现；
  - `DeviceDetailController` 鉴权成功后自动写入安全存储，提供
    `loadPersistedToken()` / `clearPersistedToken()`；UI 装配已接入平台存储。

### 变更

- `FirmwareHeader` 构造新增 `hasSignature`（默认 `false`），旧调用与旧固件行为不变；
- `FirmwareParser` 在 flags 置位时校验签名段总长度，旧格式仍按
  `104 + payloadLength` 校验；
- `AuthRequest` 的 `SIGNED` 模式复用 `credential` 字段承载 `SignedAuthCredential`，
  `TOKEN` / `CHALLENGE` 语义不变。

### 文档

- `docs/protocol.md`：新增 4.1.1 签名挑战应答（字段、规范化编码、密钥约定）与
  `SIGNED` 时序；更新已知限制。
- `docs/firmware.md`：新增 1.1 签名段格式、验签规则、兼容策略；更新 flags、
  长度约束、错误码与预检参数。
- 新增 `docs/security.md`：威胁模型、三项防护的设计与边界、向后兼容与未验证项。
- `docs/architecture.md` 增加安全文档交叉引用。

### 测试

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest`：
  core 185 + data 11 + feature 67 = **263 项全部通过**（新增 56 项，v1.0.0 的 207 项无回归）。
  - SHA-256（FIPS 180-4，含百万字节向量）与 HMAC-SHA256（RFC 4231 用例 1/2/3/4/6/7）；
  - 错误密钥、长度前缀消歧、签名器空密钥；
  - 签名固件有效/签名篡改/payload 篡改且重算头部 CRC/错误密钥/旧格式
    `REQUIRE`/`WARN`/`IGNORE`/未配置验签器/签名段截断/未知算法；
  - 令牌过期、损坏编码、过期读取自动清除；
  - 控制器签名鉴权成功/错误密钥/设备摘要不匹配、令牌落盘/过期/清除；
  - E2E：模拟器签名鉴权成功与被拒、已签名固件 `REQUIRE` 通过、旧固件
    `REQUIRE` 拒绝、旧固件 `WARN` 告警。
- `:composeApp:assembleDebug` 与 `:composeApp:linkDebugFrameworkIosSimulatorArm64` 均通过。

### 未验证

- Android 真机 Keystore 与 iOS 真机 Keychain 的读写/锁屏/卸载重装行为未验证；
- ECDSA-P256 仅预留算法编号与接口，未接入平台实现；
- 设备固件侧签名鉴权与验签实现未联调，测试桩只证明协议一致性；
- 预共享密钥的生产注入/轮换流程未实现。

## v1.0.0 - 协议一致性测试桩与 CI 流水线

### 新增

- 独立测试桩模块 `:protocol-testkit`（仅 JVM target，作为 `feature` 的 `jvmTest` 依赖，
  不进入 Android/iOS 主产物）：
  - `DeviceSimulator` 实现设备侧 `FrameLink`，按协议字典解析 App 请求并生成
    鉴权、设备信息、电量、版本、配置/参数、时间、心跳、OTA 应答与 `OTA_PROGRESS_NOTIFY`；
  - `SimulatorScenario` 场景配置：鉴权成功/失败、设备标识与电量、配置读写、
    OTA 正常升级、`chunkSize` 协商、旧设备不识别扩展；
  - `ResumePolicy` 续传策略：`ACCEPT`（`acceptedOffset == 请求值`）、`ADJUST`
    （下调到更小偏移）、`REJECT`（返回 `0x11/0x12/0x13`）、`LEGACY_DEVICE`
    （返回 `INVALID_PAYLOAD`）、`IGNORE`；
  - `FaultInjection` 故障注入：应答超时、`BUSY`、`CRC_MISMATCH`、确认长度不足、
    重复应答、乱序（延迟）应答、通知丢失、中途断连、粘包（两帧拼接）与拆包（一帧分三段）；
  - `SimulatorFirmware` 生成带 104 字节头的固件包，便于端到端用例构造 payload。
- 端到端测试 `DeviceSimulatorE2ETest`（13 个用例）：测试桩 +
  `ConcurrentCommandDispatcher` + `FirmwareSelectionController` + `OtaController`
  串起「鉴权 → 读信息 → 读版本 → 预检 → OTA → 续传 → 完成/中止」，
  断言状态迁移、进度、断点落盘/清除与 `resumeFallbackReason`；
  覆盖续传「接受/下调/拒绝」三分支、旧设备回退、标识不一致、断连恢复、
  超时/BUSY/短确认重传、重复/粘包/拆包/通知丢失、整包 CRC 失败、鉴权失败与乱序应答。
- CI：`.github/workflows/ci.yml`
  - 触发：`push`（`main` 与 `v*` 标签）、`pull_request`（`main`）、`workflow_dispatch`；
  - `macos-14` runner + `actions/setup-java@v4`（Temurin JDK 21）+ `gradle/actions/setup-gradle@v4` 缓存；
  - 分档 job：`jvm-tests`（core/data/feature 矩阵）、`android-build`（`assembleDebug`）、
    `ios-framework`（`linkDebugFrameworkIosSimulatorArm64`）；
  - 测试失败上传报告，Android 构建上传 APK artifact。
- 文档：新增 [docs/testing.md](docs/testing.md)（架构、场景/续传/故障注入矩阵、
  命令链与用例清单、新增场景步骤、CI 说明、边界声明）；
  `docs/protocol.md` 与 `docs/architecture.md` 增加测试桩与 CI 的交叉引用。

### 变更

- `settings.gradle.kts` 注册 `:protocol-testkit`；`feature/build.gradle.kts` 为 `jvmTest`
  增加测试桩依赖（`implementation`，不参与主产物）。

### 验证

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest`：
  core 139 + data 11 + feature 57 = **207 项全部通过**（新增 13 项 E2E）。
- `:composeApp:assembleDebug` 与 `:composeApp:linkDebugFrameworkIosSimulatorArm64` 均通过。
- CI 工作流已通过本地 YAML 语法校验，命令与本地等价；首次推送后需在远程 Actions 实跑确认。
- **测试桩只证明协议一致性，不代表真机 BLE/固件行为**；真机联调仍待完成。

## v0.9.0 - 协议续传字段与完整续传链路

### 新增

- 协议续传（保持向后兼容）：
  - `START_OTA` 请求在基础字段之后追加**可选 14 字节续传扩展**
    （`extVersion=1 | flags=RESUME | resumeSessionId:u32 | resumeOffset:u32 | payloadCrc32:u32`）；
    不续传时不追加任何字节，旧报文与新 App 请求字节完全一致；
  - `START_OTA` 应答在基础 body 之后追加**可选 `acceptedOffset:u32`**，返回设备实际生效的起点；
    旧设备 6 字节应答解码为 `acceptedOffset = 0`；
  - 未采用独立 `RESUME_OTA(0x14)`，取舍与兼容策略记录在
    [docs/protocol.md](docs/protocol.md#431-续传扩展v090)。
- 新增 `DeviceStatus` 错误码：
  - `0x11 OTA_RESUME_OFFSET_INVALID`：偏移非法；
  - `0x12 OTA_RESUME_CONTEXT_INVALID`：续传上下文失效；
  - `0x13 OTA_FIRMWARE_MISMATCH`：固件标识不匹配。
- 预检联动：
  - `FirmwarePrecheckResult` 新增 `payloadCrc16`，`computePayloadChecksums()` 一次流式读取
    同时计算 CRC-32 与 CRC-16；
  - `FirmwareSelectionState` 新增 `computedCrc16` 与 `resumeBlockedReason`；
    断点必须通过「合法 + 版本/总长/CRC32 + 预检通过 + CRC16」四层校验才可续传，
    不匹配时禁用续传并展示具体原因。
- UI：固件卡展示 CRC16 与「可续传，从 X 继续」；传输状态展示设备确认起点、
    设备下调/拒绝续传的回退说明。
- 测试：新增 `OtaResumeProtocolTest` 与 `OtaControllerPackageTest` 续传用例
  （接受偏移、向下调整、拒绝回退、非法偏移、CRC16 不一致、续传进度与总长）。

### 变更

- `OtaController` 以设备 `acceptedOffset` 为唯一权威起点：
  - `0` → 从 0 开始并提示回退；
  - `0 < accepted <= requested` → 从设备确认值继续；
  - `accepted > requested` 或 `>= totalBytes` → `Failed`；
  - 续传请求被设备拒绝（任意非 0 状态码）→ 自动重发不带扩展的旧格式并从 0 开始。
- 移除旧的「新会话 `sessionId` 必须等于断点 `sessionId`」门控，改由设备通过
  `resumeSessionId` 上下文与 `acceptedOffset` 决定。
- 断点写入同步：新会话已建立且已传输时按实际偏移落盘；已建立但未传输时清除旧断点；
  会话尚未建立时保留旧断点。
- `docs/protocol.md` 增加续传扩展字段、错误码、起点策略与续传时序；
  `docs/firmware.md` 更新两层门控、回退策略与未验证项。

### 验证

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest`：
  core 139 + data 11 + feature 44 = 194 项全部通过（既有 172 项无回归）。
- `:composeApp:assembleDebug` 与 `:composeApp:linkDebugFrameworkIosSimulatorArm64` 通过。
- 未在真机验证设备侧续传行为（见 docs/firmware.md 末尾）。

## v0.8.0 - 固件文件选择、本地预检与断点持久化

### 新增

- 固件包格式（`core/firmware/`）：
  - `FirmwareHeader`：104 字节固定头，包含魔数 `VF`、格式版本、固件版本
    （major/minor/patch/build/渠道）、最多 4 个目标型号（支持 `*` 通配）、
    payload 长度与 CRC-32；头部自身使用协议层 `Crc16`（CCITT-FALSE）校验。
  - `FirmwareParser` / `FirmwarePackage`：解析头部、核对文件总长与声明 payload 长度，
    保留 `FirmwareSource` 句柄按偏移分块读取，OTA 分片直接复用。
  - `Crc32`：CRC-32/IEEE 计算与增量更新；文件头 CRC32 与协议层 CRC16 同时保留，
    两者针对 payload 独立计算。
  - `FirmwareException`：截断、魔数、格式、标志、字段、头 CRC、长度、payload CRC、
    型号未知/不匹配、版本未知/回退、读取失败均有独立错误类型与稳定 `code`。
- 文件选择（`feature/firmware/`）：
  - 统一 `FirmwarePicker` 与 `PickedFirmware`，通过 `@Composable expect`
    `rememberFirmwarePicker()` 暴露平台实现；
  - Android 使用 SAF（`OpenDocument` + `ContentResolver` + `FileChannel` 随机读取），
    iOS 使用 `UIDocumentPickerViewController` 导入副本 + POSIX `pread`，均在
    `Dispatchers.IO`/`Mutex` 下分块读取，不整包进内存；
  - `FirmwareSelectionController` 管理选择、解析、预检、断点加载与数据源生命周期。
- 本地预检 `FirmwarePrechecker`：payload 上限（默认 32 MiB）、型号白名单、设备版本
  未知、版本回退、payload CRC32 完整性；多问题一次性收集，超限时跳过 CRC 读取。
- 断点持久化：
  - `OtaResumePoint` 迁移至 `core/firmware` 并扩展 `firmwareVersion` 与 `crc32`；
  - `OtaResumePointCodec` 固定字符串编码
    `v1|sessionId|major.minor.patch.build|channel|totalBytes|sentBytes|crc16|crc32`；
  - `OtaCheckpointStore` + `InMemoryOtaCheckpointStore`，平台实现
    `createOtaCheckpointStore()`：Android SharedPreferences、iOS NSUserDefaults、JVM 内存；
  - `OtaController` 新增 `checkpointStore` 与 `startPackage(firmware, resumeFrom)`：
    流式计算协议 CRC16，每 4 KiB 落盘一次断点，失败/取消记录精确偏移，
    成功、中止、reset 清除；恢复时校验版本、总长、CRC32、CRC16 与设备 `sessionId`。
- UI（OTA 页）：
  - 「选择固件 / 重新选择固件」按钮；
  - 固件信息卡：文件名、版本、payload 大小、声明/实际 CRC32、预检结果；
  - 预检失败逐条展示原因，未通过时禁用「开始升级」；
  - 「继续上次升级」「清除断点」入口，展示断点偏移；
  - 「刷新设备信息」「重新预检」便于设备版本/型号缺失时恢复。
- 文档：新增 [docs/firmware.md](docs/firmware.md)，说明固件格式、错误码、预检规则、
  断点存储结构与失效条件、平台实现与未验证项。

### 变更

- `OtaResumePoint` 从 `feature/ota` 移到 `core/firmware`，并增加固件版本与 CRC32 字段。
- OTA 页不再使用占位固件，改为必须选择真实固件并通过本地预检后才能启动。
- `feature` Android 源集新增 `androidx.activity:activity-compose` 依赖。

### 验证

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest`：
  core 127 + data 11 + feature 34 = 172 项全部通过。
- `:composeApp:assembleDebug` 与 `:composeApp:linkDebugFrameworkIosSimulatorArm64` 均通过。
- 平台文件选择器、SAF/UIDocumentPicker、SharedPreferences/NSUserDefaults 未在真机验证；
  断点续传依赖设备复用 `sessionId`，当前协议无恢复指令，需真机联调。

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
