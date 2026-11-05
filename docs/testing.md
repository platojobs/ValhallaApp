# 测试桩：设备侧协议一致性模拟器

`protocol-testkit` 是一个**设备侧协议一致性测试桩**（device simulator）。它实现与真实 BLE 链路
相同的 `FrameLink` 接口，按 [protocol.md](protocol.md) 的帧格式与命令表解析 App 请求、返回合规应答
与设备通知，并可按场景注入故障。它让「App 协议实现」与「按字典实现的设备」在 JVM 上闭环，
便于在无硬件、无 Android SDK 的 CI 环境里回归协议行为。

## 0. 边界声明（重要）

> 测试桩证明的是**协议一致性**：帧结构、序号配对、状态码、续传字段与分片语义符合本项目字典。
> 它**不代表真机行为**：BLE 分包时序、射频丢包、固件真实校验与重启、设备侧 Flash 写入等
> 硬件特性无法由模拟器覆盖。真机联调前不得把模拟器结果当作硬件验证结论。

## 1. 模块与依赖结构

```
:core               协议编解码、调度器、固件解析、断点模型（纯 Kotlin）
:protocol-testkit   DeviceSimulator + SimulatorScenario + FaultInjection（仅 JVM target）
:data               BLE 传输、平台存储
:feature            业务控制器（FirmwareSelectionController、OtaController）
:composeApp         Compose UI
```

- `protocol-testkit` 用 `api(project(":core"))` 依赖协议层，**只作为测试依赖**被 `feature` 的
  `jvmTest` 引入，不进入 `:composeApp` 主产物，也不出现在 Android/iOS 打包图中。
- 模块只声明 `jvm()` target，避免给 iOS/Android 增加额外编译面。

```kotlin
// feature/build.gradle.kts
jvmTest.dependencies {
    implementation(project(":protocol-testkit"))
}
```

## 2. 快速上手

```kotlin
val simulator = DeviceSimulator(
    scenario = SimulatorScenario(
        deviceVersion = FirmwareVersion(1, 0, 0, 0, "stable"),
        resumePolicy = ResumePolicy.ACCEPT,
        existingPayload = payload,
    ),
    scope = backgroundScope,   // runTest 的虚拟时间作用域
)
val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
val controller = OtaController(
    dispatcher = dispatcher,
    chunkSize = 64,
    scope = backgroundScope,
    connectionState = simulator.connectionState,
    checkpointStore = InMemoryOtaCheckpointStore(),
)
```

模拟器对外暴露：

| 成员 | 用途 |
| --- | --- |
| `incoming: Flow<Frame>` | 实现 `FrameLink`，向 App 上行应答/通知 |
| `send(frame)` | 接收 App 请求，解析后按命令表处理 |
| `scenario` | 可运行时替换的场景（重连后清空故障常用） |
| `connectionState` | `MutableStateFlow<Boolean>`，驱动 `OtaController` 断连快速失败 |
| `frames` / `requests` | 按顺序记录原始帧与强类型请求，供断言偏移、扩展字段 |
| `onRequest` | 在特定请求到达时观察 App 状态（如采集 `Transferring.initialOffset`） |
| `disconnect()` / `reconnect()` | 手动断开/恢复链路 |
| `resetFaults()` | 按当前 `scenario.faults` 重置故障计数 |
| `emit(payload, command)` | 主动上行一条通知 |

## 3. 场景矩阵（`SimulatorScenario`）

| 字段 | 默认值 | 语义 |
| --- | --- | --- |
| `vendor` / `model` / `serialNumber` | `Valhalla` / `VH-100` / `SIM0001` | `GET_DEVICE_INFO` 应答内容 |
| `hardwareVersion` | `HW-1.0` | 硬件版本字符串 |
| `deviceVersion` | `1.0.0.0 stable` | `GET_FIRMWARE_VERSION` 与设备信息中的固件版本 |
| `batteryPercent` / `charging` / `voltageMillivolts` | 87 / true / 4100 | 电量应答 |
| `authStatus` | `DeviceStatus.OK` | 非 `OK` 时鉴权返回该错误码（如 `AUTH_FAILED`） |
| `authToken` / `authExpiresInSeconds` | `sim-token` / 3600 | 鉴权成功应答 |
| `configs` | `led.brightness = 0x10` | `SET/GET_CONFIG` 的键值存储 |
| `chunkSize` | 64 | `START_OTA` 下发的分片大小 |
| `existingPayload` | `null` | 设备侧已持有的 payload，用于续传上下文与整包 CRC32 校验 |
| `resumePolicy` | `IGNORE` | 续传策略，见下表 |
| `resumeDownBytes` | 0 | `ADJUST` 时相对请求偏移下调的字节数 |
| `resumeRejectStatus` | `OTA_RESUME_CONTEXT_INVALID` | `REJECT` 时返回的错误码（可设 `0x11/0x12/0x13`） |
| `faults` | 空 | 故障注入，见第 5 节 |

### 3.1 续传策略（`ResumePolicy`）

| 策略 | 设备行为 | App 期望结果 |
| --- | --- | --- |
| `IGNORE` | 收到扩展但忽略，`acceptedOffset = 0` | 从头开始，`resumeFallbackReason` 提示回退 |
| `ACCEPT` | `acceptedOffset = resumeOffset` | 从请求偏移继续 |
| `ADJUST` | `acceptedOffset = resumeOffset - resumeDownBytes`（不小于 0） | 以设备确认值继续，回退原因含「下调」 |
| `REJECT` | 返回 `resumeRejectStatus`（默认 `0x12`） | 自动回退旧格式 `START_OTA` 并从 0 开始 |
| `LEGACY_DEVICE` | 不识别扩展，返回 `INVALID_PAYLOAD` | 同 `REJECT`，验证旧设备兼容路径 |

设备侧额外校验（这些失败会以非 0 状态码返回，App 应回退或失败）：

- 收到扩展但 `existingPayload == null` → `0x12 OTA_RESUME_CONTEXT_INVALID`；
- 扩展中的 `payloadCrc32` 与设备侧 payload 不符 → `0x13 OTA_FIRMWARE_MISMATCH`；
- `acceptedOffset >= totalBytes`，或设备侧数据短于 `acceptedOffset` → 偏移非法/上下文失效。

## 4. 命令覆盖

| 命令 | 模拟器应答 | 说明 |
| --- | --- | --- |
| `AUTH` | `AuthResponse` / `ErrorResponse` | 由 `authStatus` 决定 |
| `GET_DEVICE_INFO` | `DeviceInfoResponse` | 含固件版本，供预检 |
| `GET_BATTERY` | `BatteryResponse` | |
| `GET_FIRMWARE_VERSION` | `FirmwareVersionResponse` | |
| `SET_TIME` / `GET_TIME` | 回显 epoch / 固定时间 | |
| `SET_CONFIG` / `GET_CONFIG` | 读写 `configs`；未知键 → `INVALID_PARAMETER` | |
| `SET_PARAM` / `GET_PARAM` | 固定应答 | |
| `FACTORY_RESET` / `REBOOT` / `LOG_DUMP` / `HEARTBEAT` | 合规空应答 | |
| `START_OTA` | `StartOtaResponse(sessionId, chunkSize, acceptedOffset)` | 见第 3.1 节 |
| `OTA_DATA` | `OtaDataResponse(DeviceStatus.OK, receivedBytes)` | 偏移必须连续，否则 `0x11` |
| `FINISH_OTA` | `FinishOtaResponse(OK, rebootRequired)` | 校验总长、CRC16、CRC32 |
| `ABORT_OTA` | `AbortOtaResponse(OK)` | 清空活动会话 |
| `OTA_PROGRESS_NOTIFY` | 每收到一片后主动上行 | 可被 `dropNotifications` 丢弃 |

未实现的命令返回 `UNKNOWN_COMMAND`。

## 5. 故障注入矩阵（`FaultInjection`）

所有计数均为「前 N 次生效」语义：`mapOf(CommandType.X to 1)` 表示该命令的前 1 次请求注入故障，
之后恢复正常。`resetFaults()` 按当前场景重装计数。

| 字段 | 注入效果 | 期望 App 行为 |
| --- | --- | --- |
| `timeoutCommands` | 不回应答（App 等到超时） | 调度器按 `RetryPolicy` 重试同一 `sequence` |
| `busyCommands` | 返回 `BUSY` | 传输层重传同一偏移 |
| `crcMismatchCommands` | `FINISH_OTA` 返回 `CRC_MISMATCH` | `OtaState.Failed(status=CRC_MISMATCH)` |
| `shortConfirmCommands` | `OTA_DATA` 的确认长度比实际短 1 字节且**不推进设备状态** | 控制器重传同一偏移并成功 |
| `duplicateCommands` | 同一应答投递两次 | 第二次应答被调度器丢弃，不影响配对 |
| `stickyCommands` | 把两个相同帧编码拼接后一次性喂给 `FrameParser` | 增量分帧正确拆出两帧 |
| `fragmentCommands` | 一帧编码拆成 3 段依次喂给 `FrameParser` | 跨段重组为一帧 |
| `delayedCommands` | 延迟 N 毫秒后才投递应答 | 其他请求的序号配对应不受影响 |
| `dropNotifications` | 前 N 条 `OTA_PROGRESS_NOTIFY` 丢弃 | 传输不依赖通知，仍能完成 |
| `disconnectAfterDataChunks` | 收满 N 个数据分片后置 `connectionState = false` | 控制器在分片间隙快速失败并落盘断点 |

### 5.1 故障优先级

1. `timeoutCommands` / `busyCommands` 在**推进设备状态之前**消费，保证重试时偏移连续；
2. `shortConfirmCommands` 同样不推进状态，只伪造一次短确认；
3. `crcMismatchCommands` 只在 `FINISH_OTA` 分支消费；
4. `duplicate` / `sticky` / `fragment` / `delayed` 属于投递层，按上表顺序只命中一种；
5. `disconnectAfterDataChunks` 在正常应答与通知投递之后触发。

> 注意：BUSY 与其它投递类故障叠加时，BUSY 分支会直接投递错误应答，不再叠加
> duplicate/sticky/fragment/delay。当前端到端用例不做这种叠加。

## 6. 端到端覆盖的命令链

测试文件：`feature/src/jvmTest/kotlin/com/valhalla/feature/e2e/DeviceSimulatorE2ETest.kt`
（13 个用例）。它们把「测试桩 + `ConcurrentCommandDispatcher` + `FirmwareSelectionController`
+ `OtaController`」串成完整链路。

统一链路：

```
AUTH → GET_DEVICE_INFO → GET_FIRMWARE_VERSION → 选框/预检 → START_OTA
     → OTA_DATA(绝对偏移，分片) → FINISH_OTA → （成功或失败/中止）
```

| 用例 | 覆盖点 |
| --- | --- |
| 鉴权到升级完成的完整链路 | 鉴权、信息、版本、预检、5 个分片偏移 `0/64/128/192/256`、成功后断点清除 |
| 设备接受续传偏移时从断点继续 | `acceptedOffset == 请求值`，起点 128，进度 `128/300` |
| 设备下调续传偏移时按确认值继续 | `ADJUST`，起点 96，偏移序列 `96/160/224/288`，回退原因含「下调」 |
| 设备拒绝续传时回退旧格式从头开始 | 两次 `START_OTA`，第二次无扩展，偏移从 0 开始 |
| 旧设备不识别扩展时回退并完成升级 | `LEGACY_DEVICE → INVALID_PAYLOAD` 回退路径 |
| 固件标识不一致时禁止续传并提示原因 | 预检拦截，`resumeBlockedReason`，不发扩展 |
| 中途断连后落盘断点并可再次续传 | 断连失败、`lastResumePoint.sentBytes = 128`、重连后从 128 续传成功、断点清除 |
| 超时与忙时同偏移重传且最终成功 | `START_OTA` 超时 + `OTA_DATA` BUSY，`retransmissions >= 1`，重传偏移相同 |
| 确认长度不足时重传同一分片 | 短确认后重传同一偏移 |
| 重复应答 粘包 拆包与通知丢失不影响升级 | 四类投递故障叠加仍成功 |
| 设备整包校验失败时升级失败 | `Failed(status = CRC_MISMATCH)` |
| 鉴权失败映射为结构化错误 | `CommandFailure.DeviceRejected(AUTH_FAILED)` |
| 延迟应答时并发请求仍按序号配对 | 并行请求 + 延迟应答，序号隔离 |

### 6.1 断点落盘与回退断言

- 传输中每 4 KiB 落盘一次；失败/取消写入精确偏移；成功、中止、`reset` 清除。
- 断连用例断言 `store.load() == controller.lastResumePoint`，续传成功后 `store.load() == null`。
- 续传用例断言 `OtaState.Transferring.initialOffset`、`progress`、`resumeFallbackReason`。

## 7. 如何新增场景

1. **只改返回值**：优先在已有 `SimulatorScenario` 字段上扩展（如电量、型号、`chunkSize`），
   在 `DeviceSimulator.handle()` 的对应分支读取即可。
2. **新增续传行为**：在 `ResumePolicy` 增加枚举值，并在 `DeviceSimulator.handleStart` 的
   `when` 中补齐分支；同时更新第 3.1 节矩阵。
3. **新增故障**：在 `FaultInjection` 增加字段（命令 → 前 N 次），在 `resetFaults()` 登记到
   `remainingFaults`，增加 `FaultKind`，在 `respond()` 或具体 handler 中 `consume()`。
   - 若故障会影响设备状态边界，务必在**推进状态之前**消费，否则重试偏移会不连续。
4. **新增命令**：在 `core` 的 `DeviceRequest`/`MessageCodec` 增加编解码，在 `DeviceSimulator.handle`
   增加分支，并补第 4 节命令表。
5. **补端到端用例**：在 `DeviceSimulatorE2ETest` 中以「场景 + `runTest` + `backgroundScope`」
   组织；断言优先使用 `simulator.requests`（强类型）而不是裸帧。

## 8. CI 流水线

工作流文件：`.github/workflows/ci.yml`。

### 8.1 触发条件

- `push` 到 `main`，以及推送任意 `v*` 标签；
- 针对 `main` 的 `pull_request`；
- 手动 `workflow_dispatch`。

同一 `ref` 的新流水线会取消旧流水线（`concurrency.cancel-in-progress`）。

### 8.2 分档 Job

| Job | Runner | 命令 | 失败定位 |
| --- | --- | --- | --- |
| `jvm-tests`（矩阵 `core` / `data` / `feature`） | `macos-14` | `:core:jvmTest` / `:data:jvmTest` / `:feature:jvmTest` | 直接看出哪一层失败 |
| `android-build` | `macos-14` | `:composeApp:assembleDebug` | Android SDK / 资源 / 打包问题 |
| `ios-framework` | `macos-14` | `:composeApp:linkDebugFrameworkIosSimulatorArm64` | Kotlin/Native 与 Xcode 问题 |

公共步骤：`actions/checkout@v4` → `actions/setup-java@v4`（Temurin **JDK 21**，自动设置
`JAVA_HOME`）→ `gradle/actions/setup-gradle@v4`（Gradle 依赖与构建缓存）。

- 测试 job 失败时上传 `build/reports/tests/jvmTest` 与 `build/test-results/jvmTest`。
- Android job 额外执行 `android-actions/setup-android@v3`，保证 SDK licenses 已接受；
  runner 缺少 `compileSdk 36` 时由 AGP 自动下载。
- APK 作为 `valhalla-debug-apk` artifact 上传。

### 8.3 本地等价命令

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:jvmTest :data:jvmTest :feature:jvmTest
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :composeApp:assembleDebug
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

CI 与本地使用同一套 Gradle 命令，唯一差异是 `JAVA_HOME` 由 `setup-java` 注入。

## 9. 未验证项

1. 真机 BLE 链路上的分包时序、MTU 协商与射频丢包；模拟器是内存直投递，无真实传输时延。
2. 真实固件的 Flash 写入、校验失败回滚与重启行为；模拟器只校验 CRC 并返回 `rebootRequired`。
3. iOS/Android 平台特有的 `FrameLink` 实现（`BleFrameLink`）不在测试桩覆盖范围。
4. GitHub Actions 工作流尚未在远程 runner 实跑验证（本地只验证了 YAML 语法与命令等价性）；
   首次推送后需检查 Actions 运行结果。

## 10. v2.6.0 新增逻辑测试

- `DeviceHealthCheckTest`：七项全正常、低电量+心跳差异、能力不支持跳过、单项超时继续、
  链路断开中止、设备拒绝错误码、失败归类、报告脱敏。
- `OtaWizardTest`：步骤推进条件、传输/结果不可前进、回退规则、取消语义、断点百分比、
  预检规则映射与全通过。
- `DeviceMetricSamplesTest`：采样顺序、50 点淘汰、3 点阈值、空值不写、编码往返、
  损坏回退、设备隔离。

## 11. v2.7.0 新增逻辑测试

- `ConfigPresetTest`：编码往返、损坏回退空、重命名/删除/复制、最近使用排序、
  差异三态（将修改/已是目标/设备不支持）、非法值与规范化比较。
- `ConfigPresetApplierTest`：逐键写入、部分失败继续后续键、设备拒绝错误码 `0x03`、
  能力不满足跳过、已是目标不重复下发、取消异常透传。
- `AutomationRuleTest`：编码往返、损坏回退、开关/排序/删除、整表替换重新编号。
- `AutomationRuleRunnerTest`：前台关闭跳过、只执行匹配且启用的规则、单条失败写时间线
  并继续后续规则。
- `UsageStatisticsTest`：连接次数与完整会话平均时长、OTA 按结束时间筛选、配置/参数写入、
  体检、按设备分布、近 7/30 天/全部、空数据不估算、未结束会话不计入平均时长。
- `PreferencesBackupTest`：导出字段白名单并断言不含敏感项、缺失/非法字段回退默认并记录
  问题、非法预设/规则条目跳过、schema 版本校验、导入覆盖预览与写入、恢复出厂只清偏好。
