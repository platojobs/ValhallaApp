# 静态分析基线说明（v3.3.0）

本文件由各模块 `detekt-baseline.xml` 生成，逐条列明当前保留的 detekt 问题与保留原因。
基线只用于**存量**问题：新增代码命中同一规则会直接失败，不做全局关闭、全量 `@Suppress` 或目录排除。

## 总量变化

| 模块 | v3.1.0 | v3.2.0 | v3.3.0 | 变化（v3.2.0→v3.3.0） |
|---|---|---|---|---|
| core | 30 | 19 | 17 | -2 |
| data | 5 | 5 | 5 | 0 |
| feature | 60 | 33 | 28 | -5 |
| composeApp | 0 | 0 | 0 | 0 |
| **合计** | **95** | **57** | **50** | **-7** |

`detekt.yml` 与 v3.2.0 完全一致（零 diff），基线按现状重生成，只删除已不存在的条目，没有新增条目。

按规则的变化：

| 规则 | v3.1.0 | v3.2.0 | v3.3.0 | 说明 |
|---|---|---|---|---|
| CyclomaticComplexMethod | 18 | 0 | 0 | 保持清零 |
| LongMethod | 16 | 0 | 0 | 保持清零 |
| ThrowsCount | 15 | 11 | **7** | v3.3.0 清 4：`FrameCodec.decode`、`StartOtaRequest.decode`、`OtaController.sendChunk`、`OtaController.resolveStartOffset` |
| LongParameterList | 4 | 4 | **1** | v3.3.0 清 3：`SettingsScreen`、`ScanScreen`、`ValhallaContent` 收敛为语义参数对象 |
| ReturnCount | 4 | 3 | 3 | 不变 |
| TooGenericExceptionCaught | 27 | 29 | 29 | 不变（逐条研判后保留，见下） |
| LargeClass | 4 | 4 | 4 | 不变（逐条研判后保留，见下） |
| SwallowedException | 2 | 2 | 2 | 不变 |
| LoopWithTooManyJumpStatements | 3 | 3 | 3 | 不变 |
| ExplicitGarbageCollectionCall | 1 | 1 | 1 | 不变 |

## v3.3.0 的处理原则与净减明细

逐条判断，不按规则一刀切：

| 条目 | 原状 | 处理 | 理由 |
|---|---|---|---|
| `FrameCodec.decode` | 7 处 throw | 拆为 `validateFrame()` 纯校验 + 单点抛错 | 校验规则与字段解析分离，解码函数变成直线；异常类型、触发顺序、消息文本不变 |
| `StartOtaRequest.decode` | 3 条扩展头规则 | 合并为 `validateResumeExtension()` | 三条规则同属「续传扩展头是否可用」，合成一张规则表 |
| `OtaController.sendChunk` | 2 条应答规则 + 冗余取消分支 | 合并为 `validateChunkAck()`，删掉冗余 catch | `CancellationException` 本就不在可重试集合，原 catch 只是原样重抛 |
| `OtaController.resolveStartOffset` | 3 条偏移规则 | 合并为 `validateStartOffset()` | 断点失效的三种原因同属一条「设备接受的偏移不可用」规则 |
| `SettingsScreen` | 15 参数 | 11 个回调收入 `SettingsScreenActions` | 回调按「设置页能触发的动作」成组，调用点不再一排裸 lambda |
| `ScanScreen` | 13 参数 | 7 个回调收入 `ScanScreenActions` | 同上 |
| `ValhallaContent` | 10 参数 | 复用既有 `ValhallaUiCallbacks` | 原本内部就构造了同一个对象，改成由调用方传入，去掉重复装配 |

逐函数的前后对照（行数、圈复杂度、拆成几个函数）见 [complexity-refactor.md](complexity-refactor.md)。

### 为什么不继续清（如实判断）

- **TooGenericExceptionCaught（29 条）**：这些 catch 全部位于控制器/平台回调边界，且都在同一个 `try` 里先单独捕获并重抛 `CancellationException`。收窄成具体异常类型会漏掉未知失败；收窄成 `Exception` 仍会被规则命中（detekt 默认把 `Exception`/`RuntimeException`/`Throwable` 都算「过泛」）。唯一能让规则闭嘴的写法是把异常变量改成 `_` 或 `ignored...`，那是纯凑指标，没有做。
- **LargeClass（4 条）**：`DeviceSimulatorE2ETest` 与 `OtaControllerPackageTest` 是各自组件唯一的测试套件，按场景劈开只是换个文件存放；`AppStrings` 是 `StringKey → 中英文` 的单一映射表，拆分会破坏「一处新增即中英同步」的完整性检查；`OtaController` 在 v3.2.0 已拆出 10 个子步骤，剩余体积是围绕同一份可变传输状态的状态机，再拆需要重新设计状态归属，风险大于收益。
- **ThrowsCount 剩余 7 条**：`EcdsaP256` 的 DER 读取（`parseDer`/`readLength`/`readInteger`）、`fromPem`、`FirmwareSignature.decode`、`FirmwarePackage.parse`、`MessageCodec.decodeNotification` 的每个抛点都对应一种可用诊断消息的格式错误。阈值是 2，要把它们压到 2 只能把 `throw` 挪进私有函数（明确禁止的凑数）或改成返回可空值 + 消息变量，那会让 ASN.1/协议解析更难读。
- **LongParameterList 剩余 1 条（`DetailScreen`）**：20 个参数中状态与回调各 10 个，收敛需要改动函数体内约 35 处引用，并引入两条 10 元解构；改动风险大于可读性收益。
- **ReturnCount 3 条**：`extractP256PointFromSpki`、`FirmwareLibraryCodec.parse`、`classifyScanEmpty` 都是「失败即返回」的卫语句链，正是规则本身推荐的写法；改成单出口需要嵌套判断，反而更难跟。
- **SwallowedException 2 条**：一条是用户主动取消预检的控制流异常（无诊断价值），一条已经把失败转换成用户可见的 `READ_FAILED` 并关闭句柄。
- **LoopWithTooManyJumpStatements 3 条 / ExplicitGarbageCollectionCall 1 条**：帧同步/索引/批量循环的 `break`/`continue` 是算法结构本身；显式 GC 只出现在基准测量里。

---

## 剩余 50 条与逐条理由

### core（17 条）


#### ThrowsCount（7）

解析器对每个字段抛出带字段名的具体异常，合并为单一异常会丢失字段定位与用户可读的失败原因：

`EcdsaP256.fromPem`、`EcdsaP256.parseDer`、`EcdsaP256.readInteger`、`EcdsaP256.readLength`、`FirmwareParser.parse`、`FirmwareSignature.decode`、`MessageCodec.decodeNotification`。

v3.3.0 已清 `FrameCodec.decode`（改为 `validateFrame()` 纯校验）与 `StartOtaRequest.decode`（改为 `validateResumeExtension()`）。

#### TooGenericExceptionCaught（5）

| 条目 | 理由 |
|---|---|
| `CommandDispatcher.kt$CommandDispatcher.select` | 命令队列边界需要把任何写入/序列化失败转成 `CommandFailure`，不得让异常穿透协程作用域。 |
| `ConcurrentCommandDispatcher.kt$ConcurrentCommandDispatcher` | 并发调度边界：捕获后统一转为带 cause 的失败结果，已不再静默。 |
| `EcdsaP256.kt$EcdsaP256Verifier.Companion` | 验签失败统一返回 `false`；平台签名实现可能抛任意 `Error`，收窄会漏掉平台异常。 |
| `EcdsaP256.kt$EcdsaP256Verifier` | 同上，验签入口必须把平台异常转换为验签失败。 |
| `PacketLog.kt$PacketLogFormatter` | 报文格式化属于诊断路径，任何格式化异常都不能中断日志采集，捕获后降级为占位文本。 |

#### ReturnCount（2）

| 条目 | 理由 |
|---|---|
| `EcdsaP256.extractP256PointFromSpki` | DER 结构逐层校验，失败即返回 `null`，线性提前返回比嵌套分支更清晰。 |
| `FirmwareLibrary.parse` | 旧版/新版格式字段逐项校验并提前返回，保持两种格式的差异可读。 |

#### LoopWithTooManyJumpStatements（2）

| 条目 | 理由 |
|---|---|
| `FrameParser.kt$FrameParser` | 帧同步循环需要 `continue` 跳过噪声、`break` 处理长度不足；状态机会让同步字处理更难验证。 |
| `PacketLogIndex.kt$PacketLogIndex` | 索引扫描需要按帧边界提前退出，属于解析算法的自然结构。 |

#### ExplicitGarbageCollectionCall（1）

| 条目 | 理由 |
|---|---|
| `PacketLogExportBenchmark.kt$gc()` | 基准测量需要显式 GC 固定多次测量起点，非生产代码路径。 |

### data（5 条）

全部为 `TooGenericExceptionCaught`：Android/iOS BLE 回调、连接池与平台分享回调必须捕获 `Throwable` 并转换为连接/分享失败结果，否则平台异常会直接终止 UI 协程。

条目：`AndroidBleConnection`、`IosBleClient`（2 处）、`MultiDeviceConnectionPool`、`AndroidTextSharer`。

### feature（28 条）

#### TooGenericExceptionCaught（19）

控制器与平台桥接层统一把异常转换为 `UiMessage`/失败状态并写入日志，禁止异常穿透 UI：

`AutomationRuleRunner`、`AutoReconnectController`、`BatchOperationController`、`ConfigEditorController`、`ConfigPresetApplier`、`DeviceDetailController`、`DeviceHealthCheckController`、`ErrorReporter`、`FileSaver.android`、`FirmwareLibraryImporter`、`FirmwareLibraryReuse`、`FirmwareSelectionController`、`OtaController`、`OtaQueueController`、`ParamEditorController`、`ScanController`、`ScreenLogic`、`ValhallaDetailScreens`、`ValhallaDiagnostics`。

其中 `ScreenLogic`、`ValhallaDetailScreens`、`ValhallaDiagnostics` 三条对应 v3.1.0 里 `ValhallaContent` 的同三个 catch 点，拆分文件后条目数从 1 变 3，代码语义未变。

#### LargeClass（4）

| 条目 | 理由 |
|---|---|
| `DeviceSimulatorE2ETest` | 端到端共享场景步骤与模拟器装配，拆文件会复制大量夹具。 |
| `OtaController` | OTA 状态机、断点、分块与通知在同一生命周期内协作，`runTransfer` 已拆分，但类整体仍承担状态机职责。 |
| `OtaControllerPackageTest` | 与 `OtaController` 用例一一对应的测试夹具集合。 |
| `Strings.kt$AppStrings` | `StringKey → 中英文` 的单一映射表，拆分会破坏「一处新增即中英同步」的完整性检查。 |

#### LongParameterList（1）

`ValhallaDetailScreens.DetailScreen`（20 个参数）。

理由：状态与回调各 10 个，收敛需要改动函数体内约 35 处引用并引入两条 10 元解构；v3.3.0 已把 `SettingsScreen`（15→5）、`ScanScreen`（13→7）、`ValhallaContent`（10→6）收敛为语义对象，这一条判断为改动风险大于收益。

#### ThrowsCount（0）

v3.3.0 清零：`OtaController.sendChunk` 与 `resolveStartOffset` 的三类失败各自同属一条规则，分别合并为 `validateChunkAck()` 与 `validateStartOffset()`。

#### SwallowedException（2）

| 条目 | 理由 |
|---|---|
| `FirmwareLibraryReuse` | 读取失败被转换为用户可见的 `FirmwareReuseCheck.Missing(READ_FAILED)`，并关闭文件句柄，不是静默吞掉。 |
| `FirmwareSelectionController` | 捕获 `FirmwarePrecheckCancelledException` 是用户主动取消预检的正常路径，状态已复位并返回 `false`。 |

#### ReturnCount（1）与 LoopWithTooManyJumpStatements（1）

| 条目 | 理由 |
|---|---|
| `ScanEmptyReason.classifyScanEmpty` | 空扫描原因按「扫描中→无设备→权限/蓝牙不可用→错误→超时」优先级提前返回，顺序即产品语义。 |
| `BatchOperationController` | 批量执行循环需要按取消/失败/跳过提前退出，属于任务调度结构。 |

---

## 验证方式

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew detekt ktlintCheck
```

新增违规会让 `detekt` 直接失败；v3.3.0 基线净减 7 条（57 → 50），没有新增任何规则类型或条目。
