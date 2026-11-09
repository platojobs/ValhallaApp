# 静态分析基线说明（v3.2.0）

本文件由各模块 `detekt-baseline.xml` 生成，逐条列明当前保留的 detekt 问题与保留原因。
基线只用于**存量**问题：新增代码命中同一规则会直接失败，不做全局关闭、全量 `@Suppress` 或目录排除。

## 总量变化

| 模块 | v3.1.0 | v3.2.0 | 变化 |
|---|---|---|---|
| core | 30 | 19 | -11 |
| data | 5 | 5 | 0 |
| feature | 60 | 33 | -27 |
| composeApp | 0 | 0 | 0 |
| **合计** | **95** | **57** | **-38** |

本轮只做复杂函数拆分，未新增任何规则例外或排除项；基线按现状重生成，删除已不存在的条目。
按规则的变化：

| 规则 | v3.1.0 | v3.2.0 | 说明 |
|---|---|---|---|
| CyclomaticComplexMethod | 18 | **0** | 25 个目标函数拆分后全部降至阈值内 |
| LongMethod | 16 | **0** | 同上 |
| ThrowsCount | 11 + 4 | 9 + 2 | `FirmwareHeader.decode`、`MessageCodec.decodeRequest`、`DeviceHealthCheck.run`、`OtaController.runTransfer` 拆段后单函数抛点减少 |
| ReturnCount | 3 + 1 | 2 + 1 | `parseLocalDateTimeMillis` 改为卫语句后不再超限 |
| RethrowCaughtException | 0 + 1 | 0 + 0 | `ValhallaContent` 拆出路由宿主后该 catch 段落不再触发 |
| TooGenericExceptionCaught | 5 + 17 + 5 | 5 + 19 + 5 | feature +2 是同一逻辑的 catch 点在文件拆分后落到 `ScreenLogic.kt`、`ValhallaDetailScreens.kt`、`ValhallaDiagnostics.kt` 三个文件，条目计数变化，代码本身未增加 |
| 其余规则 | — | — | 条数不变 |

逐函数的前后对照（行数、圈复杂度、拆成几个函数）见 [complexity-refactor.md](complexity-refactor.md)。

---

## 剩余 57 条与逐条理由

### core（19 条）

#### ThrowsCount（9）

解析器对每个字段抛出带字段名的具体异常，合并为单一异常会丢失字段定位与用户可读的失败原因：

`EcdsaP256.fromPem`、`EcdsaP256.parseDer`、`EcdsaP256.readInteger`、`EcdsaP256.readLength`、`FirmwareParser.parse`、`FirmwareSignature.decode`、`FrameCodec.decode`、`MessageCodec.decodeNotification`、`StartOtaRequest.decode`。

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

### feature（33 条）

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

#### LongParameterList（4）

`InfoScreens.SettingsScreen`、`ValhallaApp.ValhallaContent`、`ValhallaDetailScreens.DetailScreen`、`ValhallaApp.ScanScreen`。

理由：Compose 渲染函数的参数是「数据快照 + 导航回调」的天然组合；改成参数对象会把可组合边界藏进 data class，且调用点仍需逐项传递。

#### ThrowsCount（2）

| 条目 | 理由 |
|---|---|
| `OtaController.resolveStartOffset` | 断点不可用/越界/总量不符分别抛出不同原因，用户需要看到具体失效原因。 |
| `OtaController.sendChunk` | 单块写入对长度、应答、CRC 分别报错，便于定位坏块。 |

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

新增违规会让 `detekt` 直接失败；本轮基线净减 38 条，没有新增任何规则类型。
