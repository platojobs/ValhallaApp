# v3.2.0 复杂度重构记录

基线：`4ab01ca`（v3.1.0）。范围仅限 detekt 基线中的 `CyclomaticComplexMethod`（18 条）与 `LongMethod`（16 条），按函数去重后 **25 个唯一函数**。

本版不新增功能、不新增页面，只做行为等价的可读性拆分。

## 结果总览

| 指标 | v3.1.0 | v3.2.0 |
| --- | --- | --- |
| detekt 基线总数 | 95 | 57 |
| `CyclomaticComplexMethod` | 18 | **0** |
| `LongMethod` | 16 | **0** |

两类目标规则全部清零，没有使用 `@Suppress`、没有新增 detekt 例外配置、没有 `// detekt:ignore`，也没有把逻辑挪进 Composable 规避统计。基线文件按现状重生成，剩余 57 条均为 v3.1.0 就存在的历史类型。

## 逐函数前后对照

行数为函数体行数；圈复杂度取自 detekt 实际度量（阈值临时降为 1 采集，采集后已恢复原配置）。
「拆分后子函数」指该函数现在直接调用或经一层小包装转调的、有语义命名的抽取函数，不含 lambda 与 Composable 局部函数。

| # | 函数 | 原位置 | 原行数 | 原圈复杂度 | 拆分后子函数 | 现行数 | 现圈复杂度 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `ConnectionQualityAnalyzer.analyze` | core/logging/ConnectionQualityStats.kt | 174 | 32 | 10 | 30 | 1 |
| 2 | `DeviceStateMachine.resolve` | core/state/DeviceStateMachine.kt | 53 | 38 | 7 | 9 | 8 |
| 3 | `FirmwareHeader.decode` | core/firmware/FirmwareHeader.kt | 74 | 20 | 8 | 18 | 2 |
| 4 | `MessageCodec.decodeRequest` | core/protocol/message/MessageCodec.kt | 65 | 22 | 8 | 6 | 1 |
| 5 | `MessageCodec.decodeResponse` | core/protocol/message/MessageCodec.kt | 97 | 22 | 12 | 10 | 2 |
| 6 | `PacketLogExporter.compute` | core/logging/PacketLogExport.kt | 106 | 23 | 3 | 40 | 4 |
| 7 | `parseLocalDateTimeMillis` | core/logging/PacketLogExport.kt | 36 | 24 | 2 | 12 | 8 |
| 8 | `OtaController.runTransfer` | feature/ota/OtaController.kt | 207 | 18 | 9 | 31 | 5 |
| 9 | `DeviceHealthCheckController.run` | feature/health/DeviceHealthCheck.kt | 74 | 20 | 4 | 35 | 11 |
| 10 | `BatchScreen` | feature/ui/BatchQueueScreens.kt | 273 | 37 | 8 | 100 | 6 |
| 11 | `OtaQueueScreen` | feature/ui/BatchQueueScreens.kt | 224 | 30 | 8 | 110 | 6 |
| 12 | `PresetSection` | feature/ui/FeatureScreens.kt | 263 | 30 | 8 | 103 | 10 |
| 13 | `BackupScreen` | feature/ui/FeatureScreens.kt | 145 | 12 | 6 | 85 | 10 |
| 14 | `UsageStatsScreen` | feature/ui/FeatureScreens.kt | 149 | 17 | 3 | 34 | 2 |
| 15 | `SettingsValuesSection` | feature/ui/InfoScreens.kt | 203 | 3 | 6 | 97 | 3 |
| 16 | `DisconnectBanner` | feature/ui/ValhallaApp.kt | 83 | 20 | 2 | 30 | 2 |
| 17 | `ParamFieldRow` | feature/ui/ValhallaApp.kt | 109 | 24 | 3 | 67 | 11 |
| 18 | `ScanScreen` | feature/ui/ValhallaApp.kt | 175 | 17 | 6 | 67 | 6 |
| 19 | `ValhallaContent` | feature/ui/ValhallaApp.kt | 628 | 81 | 10 | 83 | 3 |
| 20 | `DetailScreen` | feature/ui/ValhallaApp.kt → ValhallaDetailScreens.kt | 802 | 93 | 24 | 92 | 5 |
| 21 | `QuickActionSection` | feature/ui/ValhallaApp.kt → ValhallaDetailScreens.kt | 133 | 10 | 3 | 52 | 1 |
| 22 | `DiagnosticScreen` | feature/ui/ValhallaApp.kt → ValhallaDiagnostics.kt | 451 | 70 | 4 | 28 | 1 |
| 23 | `OtaScreen` | feature/ui/ValhallaApp.kt → ValhallaOtaScreens.kt | 1267 | 178 | 10 | 86 | 10 |
| 24 | `PacketLogScreen` | feature/ui/ValhallaApp.kt → ValhallaPacketLogScreens.kt | 387 | 77 | 15 | 112 | 4 |
| 25 | `startExport` → `startPacketLogExport` | feature/ui/ValhallaApp.kt → ScreenLogic.kt | 127 | ≤1 | 2 | 75 | 14 |

说明：第 25 项原函数只触发 `LongMethod`，按阈值 1 扫描时未上报圈复杂度，实际不超过 1；拆分后引入了显式分支（`try/catch` 与动作分派），圈复杂度反而上升到 14，但仍在阈值内，且逻辑比原「匿名 when 内联」更直白。

## 拆分方式

只使用以下可读性手段：

- 长 `if/else` 链与枚举分派改为 `when` + 提前返回；
- 抽取「做什么」命名的子步骤（如 `validateMagic`、`tallyOutcomes`、`handleCancellation`，不使用 `handlePart1`）；
- 深层嵌套用卫语句拍平；
- 纯计算下沉为可单测的普通函数（如 `parseDateParts`、`pairRequests`）；
- 重复分支合并（如 `MessageCodec` 的响应解码按消息类型分发）。

## 行为等价验证

- 拆分前先确认测试覆盖：被拆函数所在包均有对应测试；纯逻辑新增单测后才动手。
- 全量测试：**772**（core 385 + data 24 + feature 363），v3.1.0 基线 722，新增 **50** 个测试，未修改任何既有断言。
- 覆盖率（`koverVerifyJvm`）：

| 模块 | v3.1.0 | v3.2.0 | 变化 | 门禁 |
| --- | --- | --- | --- | --- |
| core | 94.31% | 94.17% | -0.14pp | ≥90% |
| data | 87.55% | 87.72% | +0.17pp | ≥80% |
| feature | 84.81% | 86.66% | +1.85pp | ≥80% |

core 的 -0.14pp 来自函数拆分后新增的接线行（每个子步骤的调用点），属可解释的小幅波动；feature 的提升来自新增的会话编排与 UI 纯逻辑测试。

## 为什么不继续压

- `PacketLogScreen`（112 行）与 `OtaQueueScreen`（110 行）已贴近 120 行阈值，但再拆会把「同一屏的参数选择 / 导出区」切成过碎片段，可读性下降，故停在阈值内。
- `SettingsValuesSection` 原圈复杂度只有 3，问题纯粹是行数；拆成 6 个语义分区后行数 97，继续拆没有收益。
- 两个规则已无剩余条目，无需保留任何硬理由条目。

## 复现命令

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  ./gradlew detekt ktlintCheck \
  :core:jvmTest :data:jvmTest :feature:jvmTest \
  :core:koverVerifyJvm :data:koverVerifyJvm :feature:koverVerifyJvm
```
