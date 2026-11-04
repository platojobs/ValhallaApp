# 权限与连接体验（v1.7.0）

本文说明 Android / iOS 的蓝牙权限声明、统一权限状态机、连接生命周期与自动重连策略。
纯逻辑部分位于 `feature/src/commonMain/kotlin/com/valhalla/feature/ble/`，平台入口为
`rememberBlePermissionController()` 的 expect/actual 实现。分层与数据流见
[architecture.md](architecture.md#7-权限连接生命周期与重连v170)。

## 1. Android 权限声明

| 系统版本 | 运行时权限 | manifest 声明 |
| --- | --- | --- |
| Android 12+（API 31+） | `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT` | 无 `maxSdkVersion`；`BLUETOOTH_SCAN` 带 `neverForLocation` |
| Android 11 及以下 | `ACCESS_FINE_LOCATION` | `maxSdkVersion="30"` |
| Android 11 及以下（安装时） | `BLUETOOTH`、`BLUETOOTH_ADMIN` | `maxSdkVersion="30"` |

- 新旧权限不会同时生效：`BLUETOOTH_SCAN/CONNECT` 在 API 31 以下由系统忽略，
  `ACCESS_FINE_LOCATION` / `BLUETOOTH` / `BLUETOOTH_ADMIN` 在 API 31+ 被 `maxSdkVersion` 截断；
- 扫描仅用于发现设备，不用于定位，因此 `BLUETOOTH_SCAN` 声明 `neverForLocation`；
- `BLUETOOTH_CONNECT` 用于连接、读取设备名与 GATT 服务；
- `<uses-feature android:name="android.hardware.bluetooth_le" required="false" />`
  允许无 BLE 的设备安装，运行时由 `UNSUPPORTED` 状态提示。

运行时请求**不再在 `MainActivity.onCreate` 自动发起**，统一由扫描页按状态机触发，
避免启动即弹窗，以及永久拒绝后每次启动反复打扰。

## 2. 统一权限状态机

`BlePermissionStateMachine`（纯逻辑）：

| 状态 | 含义 | 允许再次请求 |
| --- | --- | --- |
| `NOT_REQUESTED` | 从未请求，或系统尚未给出结论 | 是 |
| `GRANTED` | 已授权 | 否 |
| `DENIED` | 被拒绝，但系统仍允许弹窗 | 是 |
| `PERMANENTLY_DENIED` | 被拒绝且系统不再弹窗 | 否 |

- `beginRequest()`：仅在 `NOT_REQUESTED` / `DENIED` 且无在途请求时返回 true；
  `PERMANENTLY_DENIED` 恒为 false —— 这是「永久拒绝不再反复弹窗」的唯一入口；
- `onRequestResult(granted, canAskAgain)`：授权 → `GRANTED`；拒绝且可再问 → `DENIED`；
  拒绝且不可再问 → `PERMANENTLY_DENIED`；
- `syncFromSystem(granted, canAskAgain, hasRequestedBefore)`：回到前台、从设置返回时校正；
  从未请求过保持 `NOT_REQUESTED`，已授权后在设置中被回收（不可再问）降级为永久拒绝；
- `cancelRequest()`：页面销毁时清除在途标记。

Android 事实来源：`checkSelfPermission` + `shouldShowRequestPermissionRationale`，
「是否请求过」持久化在 `SharedPreferences("valhalla_ble_permission")`；
iOS 事实来源：`CBManagerState`（见第 4 节）。

## 3. 统一可用状态与引导

`resolveBleAvailability(adapterState, permissionState)` 将权限与适配器状态合并：

| 可用状态 | 触发条件 | 引导动作 |
| --- | --- | --- |
| `READY` | 已授权 + `POWERED_ON` | 无 |
| `PERMISSION_NOT_REQUESTED` | 未请求 | 请求权限 |
| `PERMISSION_DENIED` | 被拒绝但可再问 | 请求权限 |
| `PERMISSION_PERMANENTLY_DENIED` | 永久拒绝 / 适配器 `UNAUTHORIZED` | 去系统设置 |
| `BLUETOOTH_OFF` | 已授权 + `POWERED_OFF` | 开启蓝牙 |
| `BLUETOOTH_RESETTING` | iOS `CBManagerStateResetting` | 重试 |
| `UNSUPPORTED` | 无 BLE 适配器 | 仅说明 |
| `UNKNOWN` | 状态暂不可得 | 重试 |

扫描与连接按钮仅在 `READY` 时可用；不可用时展示引导卡片并说明原因，不会静默失败。
「开启蓝牙」：Android 调用系统 `ACTION_REQUEST_ENABLE` 确认框；iOS 无法由应用开启，
跳转系统设置。

## 4. iOS 声明与状态映射

`iosApp/iosApp/Info.plist`：

- `NSBluetoothAlwaysUsageDescription`：说明扫描/连接设备、读取状态、报文、配置与固件升级用途，
  并声明不用于定位或广告；
- `NSBluetoothPeripheralUsageDescription`：iOS 13 以下兼容声明；
- `UIBackgroundModes` 包含 `bluetooth-central`，允许已连接设备的后台回调。

`CBCentralManager` 状态映射：

| CBManagerState | BleAdapterState | 权限状态 | 引导 |
| --- | --- | --- | --- |
| `PoweredOn` | `POWERED_ON` | `GRANTED` | 可用 |
| `PoweredOff` | `POWERED_OFF` | `GRANTED` | 开启蓝牙 |
| `Unauthorized` | `UNAUTHORIZED` | `PERMANENTLY_DENIED` | 去系统设置 |
| `Unsupported` | `UNSUPPORTED` | `GRANTED` | 不支持说明 |
| `Resetting` | `RESETTING` | `GRANTED` | 稍候重试 |
| `Unknown` | `UNKNOWN` | `NOT_REQUESTED` | 重试 |

回到前台：`UIApplicationDidBecomeActiveNotification` 触发
`bleClient.refreshAdapterState()` 并重算权限状态，避免 UI 停留在过期状态。
iOS 没有编程式权限请求 API：首次使用 `CBCentralManager` 时系统自动弹窗，之后只能去设置修改。

## 5. 连接生命周期

断连原因由 `classifyDisconnect(DisconnectSignals)` 统一映射，优先级从高到低：

| 优先级 | 原因 | 触发 | UI 动作 | 允许自动重连 |
| --- | --- | --- | --- | --- |
| 1 | `USER_REQUESTED` | 用户返回/主动断开 | 返回扫描 | 否 |
| 2 | `PERMISSION_LOST` | 权限被拒 / 适配器未授权 | 去系统设置 | 否 |
| 3 | `BLUETOOTH_OFF` | 蓝牙关闭或重置 | 开启蓝牙 / 重连 | 是 |
| 4 | `TIMEOUT` | 连接/服务发现超时 | 重新连接 | 是 |
| 5 | `DEVICE_SIDE` | 对端终止（关机、超距、重启） | 重新连接 | 是 |
| 6 | `CONNECT_FAILED` | 传输层错误 | 重新连接 | 是 |
| 7 | `UNKNOWN` | 无可识别原因 | 重新连接 | 是 |

详情页在 `connection.connected == false` 时展示横幅（标题、说明、动作按钮）；
`DeviceSession.userDisconnected` 区分主动断开与意外断开，避免把用户操作误判为设备侧断开。

## 6. 自动重连策略

`ReconnectPolicy`：初始 1s、倍率 2、上限 30s、最多 5 次 → `1/2/4/8/16` 秒。

`ReconnectScheduler` 状态机：

```
IDLE ──断开──▶ WAITING ──beginAttempt──▶ CONNECTING ──成功──▶ SUCCEEDED
                  │                          │
                  │ 蓝牙关闭                  │ 失败且未达上限
                  ▼                          ▼
                PAUSED ◀──蓝牙关闭──── WAITING（attempt+1，delay 翻倍）
                  │ 蓝牙开启
                  └────────▶ WAITING（从已完成次数继续）

任一阶段 cancel() ──▶ CANCELLED；失败达上限 ──▶ EXHAUSTED
```

- 重连期间（`WAITING` / `CONNECTING`）`start()` 不会创建第二条协程，`beginAttempt()` 也拒绝，
  因此不会重复发起连接；
- 蓝牙关闭时取消等待/在途任务进入 `PAUSED`，不消耗尝试次数；重新开启后退避从当前次数继续；
- 达到上限进入 `EXHAUSTED`，UI 提示手动重试；
- 用户取消后进入 `CANCELLED`，可手动重新开始；
- 重连成功后复用同一份报文日志，关闭旧调度器并建立新会话。

## 7. 扫描超时与空态

`ScanController` 启动扫描时启动 `DEFAULT_SCAN_TIMEOUT_MILLIS = 12_000` 计时器；
超过阈值仍无设备置 `timedOut`。`classifyScanEmpty` 判定顺序：

已有设备 → 扫描错误 → 权限/蓝牙不可用 → 扫描中（未超时）→ 扫描超时 → 扫描结束无设备。

「无权限」「蓝牙关闭」「扫描超时」「真的没有设备」四类文案完全独立，不会互相混淆。

## 8. 测试边界

平台权限 API（`checkSelfPermission`、`shouldShowRequestPermissionRationale`、
`CBCentralManager.state`）无法在 JVM 上运行，因此：

- 状态机、可用状态合并、引导文案、断连映射、退避序列、暂停/取消、扫描空态
  全部抽为纯逻辑，由 `:feature:jvmTest` 覆盖；
- Android/iOS 平台实现只做「读取事实 + 调用动作」，通过 `assembleDebug` / iOS framework
  链接验证编译，实际弹窗、蓝牙开关、真机断连与重连需真机验收，详见测试报告中的「未验证项」。

## 9. 真机验收清单（未在 CI 验证）

1. Android 12+ 首次进入扫描页弹出「附近的设备」权限；允许后可扫描连接；
2. Android 11 及以下弹出定位权限；拒绝后再次点击「授予权限」可再次弹窗；
3. 选择「不再询问」后按钮变为「去系统设置」，再次点击不再弹窗；
4. 系统设置中关闭蓝牙后扫描页显示「蓝牙已关闭」，开启后恢复扫描；
5. iOS 首次使用弹出蓝牙授权；拒绝后显示「去系统设置」；
6. 连接中关闭蓝牙 → 横幅显示「蓝牙已关闭」，自动重连暂停；重新开启后继续；
7. 设备断电/超距 → 横幅「设备已断开连接」，按 1/2/4/8/16 秒自动重连；
8. 重连过程中点击「取消重连」不再尝试，可手动「重新连接」；
9. 连续失败 5 次后提示达到上限；
10. 扫描 12 秒无设备显示超时文案，与无权限/蓝牙关闭文案不同。
