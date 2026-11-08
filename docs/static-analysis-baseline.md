# 静态分析基线说明（v3.1.0）

本文件由各模块 `detekt-baseline.xml` 生成，逐条列明当前保留的 detekt 问题与保留原因。
基线只用于**存量**问题：新增代码命中同一规则会直接失败，不做全局关闭、全量 `@Suppress` 或目录排除。

## 总量变化

| 模块 | v3.0.0 | v3.1.0 | 变化 |
|---|---|---|---|
| core | 266 | 30 | -236 |
| data | 10 | 5 | -5 |
| feature | 115 | 60 | -55 |
| composeApp | 0 | 0 | 0 |
| **合计** | **391** | **95** | **-296** |

本轮全部为真实代码修改，未新增任何规则例外或排除项。按规则统计的修复量：

| 规则 | 修复条数 | 处理方式 |
|---|---|---|
| MagicNumber | 260 | 全部提取为具名常量（协议码、位掩码、偏移、时间单位、校验边界）；同值不同义不合并 |
| MaxLineLength | 18 | `Strings.kt` 英文字符串按原渲染文本换行拼接 |
| ComplexCondition | 6 | 抽出具名布尔中间量（窗口判定、忙碌状态、备份字段完整性等） |
| SwallowedException | 1 | `ConcurrentCommandDispatcher` 将捕获异常挂到 `CommandFailure.InvalidResponse.cause` |
| UnusedParameter | 3 | 删除未使用的 `LocaleFormat`、iOS `CBService`/`CBCharacteristic` 参数并更新调用点 |
| MatchingDeclarationName | 2 | `BatchDeviceOption` 独立成文件；`FailureInfo` 更名为 `CommandFailureInfo` |
| InstanceOfCheckForException | 1 | `ErrorReporter` 改为独立 `catch (UserCancelledException)` 分支 |
| LongMethod | 1 | `DeviceDashboardCard` 拆出 `DeviceDashboardTrend` |
| CyclomaticComplexMethod | 2 | 抽出具名判定与 `ConfigCapabilityNotice`，降低两处复杂度 |
| LongParameterList | 2 | `DeviceDashboardCard`、自动化规则行改为参数 data class |
| **合计修复** | **296** | |

> 四态补齐过程中 `BackupScreen` 等 3 处曾因新增分支触发复杂度阈值，已同轮拆出
> `BackupPreview`、`QuickActionResult` 等消除；该部分属于「新增即修复」，不计入上表净减。
> 上表逐项之和与基线 391 → 95 的净减完全一致。

---

## 剩余 95 条与逐条理由

### core（30 条）

#### CyclomaticComplexMethod（7）

| 条目 | 理由 |
|---|---|
| `ConnectionQualityStats.kt$ConnectionQualityAnalyzer.analyze` | 每条分支对应一种质量指标（CRC、超时、失败率、样本量），拆分会引入中间结果类型但不减少判定数量。 |
| `DeviceStateMachine.kt$DeviceStateMachine.resolve` | 分支与设备状态迁移表一一对应，保持与协议状态图同构更易核对。 |
| `FirmwareHeader.kt$FirmwareHeader.decode` | 固件头按字段顺序校验，分支数量等于必需字段数量；拆成子函数会重复传递偏移量。 |
| `MessageCodec.kt$MessageCodec.decodeRequest` | 命令码到请求体的分派是协议文档的直译，分支即命令类型。 |
| `MessageCodec.kt$MessageCodec.decodeResponse` | 同 `decodeRequest`，响应分派逐命令展开。 |
| `PacketLogExport.kt$parseLocalDateTimeMillis` | 年/月/日/时/分/秒逐段解析，任一段非法即提前返回，扁平结构比嵌套 `when` 更直观。 |
| `PacketLogExport.kt$PacketLogExporter.compute` | 过滤、分组、进度、输出组装四个阶段串行；拆分需新增阶段结果对象，当前已在 120 行内。 |

#### ThrowsCount（11）

解析器对每个字段抛出带字段名的具体异常，合并为单一异常会丢失字段定位与用户可读的失败原因：

`EcdsaP256.fromPem`、`EcdsaP256.parseDer`、`EcdsaP256.readInteger`、`EcdsaP256.readLength`、`FirmwareHeader.decode`、`FirmwareParser.parse`、`FirmwareSignature.decode`、`FrameCodec.decode`、`MessageCodec.decodeNotification`、`MessageCodec.decodeRequest`、`StartOtaRequest.decode`。

#### TooGenericExceptionCaught（5）

| 条目 | 理由 |
|---|---|
| `CommandDispatcher.kt$CommandDispatcher.select` | 命令队列边界需要把任何写入/序列化失败转成 `CommandFailure`，不得让异常穿透协程作用域。 |
| `ConcurrentCommandDispatcher.kt$ConcurrentCommandDispatcher` | 并发调度边界：捕获后统一转为带 cause 的失败结果，已不再静默。 |
| `EcdsaP256.kt$EcdsaP256Verifier.Companion` | 验签失败统一返回 `false`；平台签名实现可能抛任意 `Error`，收窄会漏掉平台异常。 |
| `EcdsaP256.kt$EcdsaP256Verifier` | 同上，验签入口必须把平台异常转换为验签失败。 |
| `PacketLog.kt$PacketLogFormatter` | 报文格式化属于诊断路径，任何格式化异常都不能中断日志采集，捕获后降级为占位文本。 |

#### ReturnCount（3）

| 条目 | 理由 |
|---|---|
| `EcdsaP256.extractP256PointFromSpki` | DER 结构逐层校验，失败即返回 `null`，线性提前返回比嵌套分支更清晰。 |
| `FirmwareLibrary.parse` | 旧版/新版格式字段逐项校验并提前返回，保持两种格式的差异可读。 |
| `PacketLogExport.parseLocalDateTimeMillis` | 逐段解析，任一段失败即返回 `null`。 |

#### LoopWithTooManyJumpStatements（2）

| 条目 | 理由 |
|---|---|
| `FrameParser.kt$FrameParser` | 帧同步循环需要 `continue` 跳过噪声、`break` 处理长度不足；状态机会让同步字处理更难验证。 |
| `PacketLogIndex.kt$PacketLogIndex` | 索引扫描需要按帧边界提前退出，属于解析算法的自然结构。 |

#### LongMethod（1）与 ExplicitGarbageCollectionCall（1）

| 条目 | 理由 |
|---|---|
| `ConnectionQualityStats.kt$ConnectionQualityAnalyzer.analyze` | 分析流程按「样本→CRC→超时→失败率→结论」顺序展开，抽中间对象会与 `ConnectionQualityStats` 重复。 |
| `PacketLogExportBenchmark.kt$gc()` | 基准测量需要显式 GC 固定多次测量起点，非生产代码路径。 |

### data（5 条）

全部为 `TooGenericExceptionCaught`：Android/iOS BLE 回调、连接池与平台分享回调必须捕获 `Throwable` 并转换为连接/分享失败结果，否则平台异常会直接终止 UI 协程。

条目：`AndroidBleConnection`、`IosBleClient`（2 处）、`MultiDeviceConnectionPool`、`AndroidTextSharer`。

### feature（60 条）

#### TooGenericExceptionCaught（17）

控制器与平台桥接层统一把异常转换为 `UiMessage`/失败状态并写入日志，禁止异常穿透 UI：

`AutomationRuleRunner`、`AutoReconnectController`、`BatchOperationController`、`ConfigEditorController`、`ConfigPresetApplier`、`DeviceDetailController`、`DeviceHealthCheckController`、`ErrorReporter`、`FileSaver.android`、`FirmwareLibraryImporter`、`FirmwareLibraryReuse`、`FirmwareSelectionController`、`OtaController`、`OtaQueueController`、`ParamEditorController`、`ScanController`、`ValhallaContent`。

#### LongMethod（15）

| 条目 | 理由 |
|---|---|
| `BatchQueueScreens.BatchScreen` | Compose 声明式布局按设备选择、执行、结果三段展开；继续拆分会新增跨作用域的槽位参数。 |
| `BatchQueueScreens.OtaQueueScreen` | 同上，队列控制与结果区块共享状态提升。 |
| `FeatureScreens.BackupScreen` | 导入/导出/预览共享同一组局部状态，预览已拆出 `BackupPreview`，剩余为文件与控制区。 |
| `FeatureScreens.UsageStatsScreen` | 统计卡片按周期、设备、导出顺序排列，拆分会重复格式化调用。 |
| `FeatureScreens.PresetSection` | 预设列表、应用结果与禁用提示共享同一状态快照。 |
| `InfoScreens.SettingsValuesSection` | 设置项按字段顺序渲染，字段文案/回调成对出现。 |
| `OtaController.runTransfer` | 传输状态机按分块、进度、断点顺序展开，拆分需新增中间状态对象并与 `OtaState` 重复。 |
| `ValhallaApp.ScanScreen` | 扫描列表、排序、空态/加载态在同一状态快照上渲染。 |
| `ValhallaApp.DetailScreen` | 详情页聚合设备信息、指标、快捷操作与健康检查，共享 `DeviceSession`。 |
| `ValhallaApp.QuickActionSection` | 快捷操作按钮、结果与禁用提示共享 `QuickActionState`；结果已拆出 `QuickActionResult`。 |
| `ValhallaApp.DiagnosticScreen` | 诊断报告/打包两个进度区共享本地快照。 |
| `ValhallaApp.OtaScreen` | 选包、预检、传输、结果四段共享 OTA 状态。 |
| `ValhallaApp.PacketLogScreen` | 过滤、列表、导出三区共享日志快照。 |
| `ValhallaApp.ValhallaContent` | 顶层路由与导航状态集中在此，拆分需要跨文件暴露私有路由类型。 |
| `ValhallaApp.startExport` | 导出流程按校验、组装、保存顺序展开，错误分支需保持原位。 |

#### CyclomaticComplexMethod（11）

`BatchQueueScreens.BatchScreen`、`BatchQueueScreens.OtaQueueScreen`、`DeviceHealthCheckController.run`、`FeatureScreens.PresetSection`、`ValhallaApp.DisconnectBanner`、`ValhallaApp.ParamFieldRow`、`ValhallaApp.DetailScreen`、`ValhallaApp.DiagnosticScreen`、`ValhallaApp.OtaScreen`、`ValhallaApp.PacketLogScreen`、`ValhallaApp.ValhallaContent`。

理由：分支分别对应设备状态、连接状态、字段校验状态与 OTA 阶段，数量与状态枚举同构；继续抽分会增加参数传递但不减少判定。

#### LargeClass（4）

| 条目 | 理由 |
|---|---|
| `DeviceSimulatorE2ETest` | 端到端共享场景步骤与模拟器装配，拆文件会复制大量夹具。 |
| `OtaController` | OTA 状态机、断点、分块与通知在同一生命周期内协作，拆分会跨类暴露内部状态。 |
| `OtaControllerPackageTest` | 与 `OtaController` 用例一一对应的测试夹具集合。 |
| `Strings.kt$AppStrings` | `StringKey → 中英文` 的单一映射表，拆分会破坏「一处新增即中英同步」的完整性检查。 |

#### ThrowsCount（4）

| 条目 | 理由 |
|---|---|
| `DeviceHealthCheckController.run` | 每项体检失败抛出带项目名的异常，UI 据此展示具体失败项。 |
| `OtaController.resolveStartOffset` | 断点不可用/越界/总量不符分别抛出不同原因，用户需要看到具体失效原因。 |
| `OtaController.runTransfer` | 传输阶段按 CRC、确认、超时分别抛出不同失败原因。 |
| `OtaController.sendChunk` | 单块写入对长度、应答、CRC 分别报错，便于定位坏块。 |

#### LongParameterList（4）

`InfoScreens.SettingsScreen`、`ValhallaApp.ValhallaContent`、`ValhallaApp.DetailScreen`、`ValhallaApp.ScanScreen`。

理由：Compose 渲染函数的参数是「数据快照 + 导航回调」的天然组合；改成参数对象会把可组合边界藏进 data class，且调用点仍需逐项传递。非 Composable 的 `DeviceDashboardCard` 与自动化规则行已改为 data class。

#### SwallowedException（2）

| 条目 | 理由 |
|---|---|
| `FirmwareLibraryReuse` | 读取失败被转换为用户可见的 `FirmwareReuseCheck.Missing(READ_FAILED)`，并关闭文件句柄，不是静默吞掉。 |
| `FirmwareSelectionController` | 捕获 `FirmwarePrecheckCancelledException` 是用户主动取消预检的正常路径，状态已复位并返回 `false`。 |

#### ReturnCount（1）、RethrowCaughtException（1）、LoopWithTooManyJumpStatements（1）

| 条目 | 理由 |
|---|---|
| `ScanEmptyReason.classifyScanEmpty` | 空扫描原因按「扫描中→无设备→权限/蓝牙不可用→错误→超时」优先级提前返回，顺序即产品语义。 |
| `ValhallaApp.throw cancellation` | `catch (CancellationException)` 后原样抛出，保证协程取消语义不被吞掉。 |
| `BatchOperationController` | 批量执行循环需要按取消/失败/跳过提前退出，属于任务调度结构。 |
