# 界面四态对照表（v3.1.0）

本表逐屏核对「空态 / 加载态 / 错误态 / 禁用或不可用态」的实际实现位置。
四态面板定义在 [ScreenState.kt](../feature/src/commonMain/kotlin/com/valhalla/feature/ui/ScreenState.kt)：

- `EmptyStatePanel`：标题 + 原因正文，`liveRegion = Polite`；
- `LoadingStatePanel`：标题 + 不确定进度条，带 `progressBarRangeInfo` 与 `contentDescription`；
- `ErrorStatePanel`：错误正文 + 可选重试，`liveRegion = Assertive`；
- `DisabledStatePanel`：不可用原因，`contentDescription` 直接读取原因文案。

所有新增文案均走 `StringKey`，中英文在
[Strings.kt](../feature/src/commonMain/kotlin/com/valhalla/feature/i18n/Strings.kt) 同表维护。

## 1. 逐屏对照

| # | 界面 | 空态 | 加载态 | 错误态 | 禁用 / 不可用态 | 不适用说明 |
|---|---|---|---|---|---|---|
| 1 | 扫描 | `ScanScreen` 1432 | 1419 | 1423、1430 | 1389 | — |
| 2 | 已连接设备 | `DevicesScreen` 1551 | N/A | N/A | 1552 | 列表来自 `MultiDeviceState` 内存快照，没有异步加载阶段；断开/切换是本地同步操作，失败原因由对应会话详情页错误区呈现 |
| 3 | 设备详情 | `DetailScreen` 2320、2699、2813 | 2587 | 2631、2656（快捷操作失败） | 2594、2918-2923 | — |
| 4 | 配置 | `ConfigSection` 3244 | 3227 | 3269 | 3230、3233 | — |
| 5 | 参数 | `ParamSection` 3479 | 3452 | 3503 | 3455、3457、3460 | — |
| 6 | 预设 | `PresetSection` 157 | 137 | 140 | 154 | — |
| 7 | OTA | `OtaScreen` 5511（固件库为空） | 5308、5311（预检进度） | 6126-6156（`OtaState.Failed`），5348（固件库提示） | 5398、6258、6261 | — |
| 8 | 日志 | `PacketLogScreen` 4974 | 4908-4938（导出进度条，带语义） | 4955（保存/分享失败） | 4736、4738 | — |
| 9 | 导出 | 导出区 4882（无匹配条目） | 4908-4938 | 4955 | 4705-4712（`canExport` 判定）、4736 | 导出是日志页内的操作区，不单设页面；四态与日志页共用同一状态源 |
| 10 | 体检 | `DetailScreen` 体检卡 2320 | 2251（`progressBarRangeInfo`） | 2432（`healthMessage`，Assertive） | 2426（运行中禁用保存；进行中隐藏启动按钮） | 体检没有整页禁用态：不可运行的根因（未连接/只读/忙碌）已在详情页与快捷操作区呈现 |
| 11 | 诊断 | `DiagnosticScreen` 4141、4240、4307 | 3987-4005、4064-4078（两段进度条） | 4030-4046、4117-4133 | 3977（生成中禁用格式切换） | 诊断页始终可用；生成期间只禁用会冲突的输入，保留取消入口，因此不整页展示禁用面板 |
| 12 | 自动化 | `AutomationScreen` 528、690 | N/A | N/A | 691（无预设时） | 规则仓库为本地同步读写，没有加载/远端错误；新增规则弹窗在无预设时同样给出空态与不可用提示 |
| 13 | 统计 | `UsageStatsScreen` 836 | N/A | N/A | N/A | 统计只读本机档案与事件时间线，同步计算，无加载与远端错误；页面没有写操作，不需要禁用态 |
| 14 | 备份 | `BackupScreen` 1086 | N/A | 996 | 1100 | 校验为本地同步解析；保存走系统文件面板，进度由系统对话框承担，不设应用内加载条 |
| 15 | 设置 | N/A | N/A | 571-583（字段校验，`liveRegion`） | 425（通知能力不支持） | 设置项为本地同步读写，无加载阶段；无空数据场景 |
| 16 | 关于 / 帮助 / 隐私 | 关于 639、隐私 702 | N/A | N/A | N/A | 关于页只读构建信息；帮助页是静态 FAQ；隐私页只读存储清单。三者均无异步加载、远端错误与写操作 |

## 2. 状态文案

| StringKey | 中文 | 英文 | 使用位置 |
|---|---|---|---|
| `STATE_EMPTY_TITLE` / `STATE_EMPTY_BODY` | 暂无内容 / 当前没有可显示的内容 | Nothing here / There is nothing to show yet | 四态空态面板 |
| `STATE_LOADING_TITLE` / `STATE_LOADING_BODY` | 加载中 / 正在读取当前状态 | Loading / Reading current state | 四态加载面板 |
| `STATE_ERROR_TITLE` / `STATE_ERROR_BODY` | 加载失败 / 具体原因：{detail} | Failed / Reason: {detail} | 四态错误面板 |
| `STATE_DISABLED_TITLE` | 暂不可用 | Unavailable | 四态禁用面板 |
| `STATE_DISABLED_NOT_CONNECTED` | 设备未连接 | Device is not connected | 设备列表、参数、快捷操作 |
| `STATE_DISABLED_READ_ONLY` | 批量任务占用中，当前为只读 | A batch task is running; this view is read-only | 详情、配置、参数、预设、OTA |
| `STATE_DISABLED_BUSY` | 当前操作进行中 | An operation is in progress | 配置、参数、OTA、日志、批量与队列 |
| `STATE_UNAVAILABLE_HINT` | 当前环境不支持该功能 | This feature is not available in the current environment | 扫描、日志、自动化、设置、备份 |

## 3. 语义与排版约束

- 四态面板统一带 `liveRegion`；错误为 `Assertive`，空态、加载与结果提示为 `Polite`。
- 所有进度条均声明 `progressBarRangeInfo`（确定进度用 `0f..1f`，不确定进度用 `Indeterminate`），
  同时提供 `contentDescription`，读屏可播报百分比或当前阶段。
- 可点击卡片、按钮与列表项均设置 `contentDescription`；长按复制等隐藏操作在文案中说明。
- 弹窗统一使用 `AlertDialog` 并提供 `onDismissRequest`，返回键可关闭。
- 关键信息文本不设 `maxLines` 截断；长文本依赖外层滚动容器，200% 字体下不裁切。
- RTL 约束由 [UiAccessibilitySourceTest.kt](../feature/src/jvmTest/kotlin/com/valhalla/feature/ui/UiAccessibilitySourceTest.kt)
  扫描源码确认：UI 代码不使用 `Alignment.Left/Right`、`padding(start/end)` 之外的绝对左右方向。
- 状态色对比度由
  [StatusColorContrastTest.kt](../feature/src/commonTest/kotlin/com/valhalla/feature/theme/StatusColorContrastTest.kt)
  覆盖 `success / warning / error / inProgress` 在亮色与暗色主题下对 `background` 均 ≥ 4.5:1。
  本轮四态组件只复用既有状态色与 `onSurfaceVariant`，未新增状态色。
