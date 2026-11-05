# 设备档案与本地存储

本文说明设备档案（Device Profile）的持久化选型、数据表结构、保留策略与平台实现边界。
代码位置：

- 模型、编解码与仓储：`core/src/commonMain/kotlin/com/valhalla/core/profile/`
- 平台 driver：`data/src/{android,ios,jvm}Main/kotlin/com/valhalla/data/profile/DeviceProfileStoreFactory.*.kt`
- 写入联动：`feature/detail/DeviceDetailController.kt`、`feature/ota/OtaController.kt`

## 1. 选型与取舍

**结论：使用「平台键值存储 + 版本化行式编码」，不引入 SQLDelight。**

理由：

- 档案是数量很小的一维对象集合（默认上限 50 条），查询需求只有
  「按最后连接时间排序列表、按 id 取单条、删除、清空」，没有跨表关联、分页或事务需求；
- SQLDelight 需要新增 Gradle 插件、按平台引入 SQLite driver、维护 `.sq` schema 与版本迁移；
  Android / iOS / JVM 三端 driver 的版本与打包成本明显高于当前数据模型带来的收益；
- 现有仓库已有成熟的 `expect/actual` 平台存储模式（`OtaCheckpointStore`、`TokenStore`），
  档案复用同一套约束，平台侧只实现字符串读写，编解码与排序全部留在 `commonMain`，
  可在 `jvmTest` 完整覆盖；
- 不新增第三方序列化依赖，避免 JSON 库版本与 KMP 目标兼容问题。

代价与边界：

- 每次写入是整表覆盖，档案很大（千级）或需要并发多写时会放大 I/O；当前规模不构成问题；
- 没有索引与条件查询，新增复杂查询时应迁移到 SQLDelight（迁移时保持 `DeviceProfileRepository`
  公共接口不变，替换 store 与仓储内部实现即可）；
- 行式编码是内部格式，不作为对外交换格式；升级格式时通过首行版本号 `v1` 做兼容判断。

## 2. 平台实现

| 平台 | driver | 行为 |
| --- | --- | --- |
| Android | `SharedPreferencesDeviceProfileStore` | 文件 `valhalla_profiles`，键 `profiles`；`apply()` 异步落盘 |
| iOS | `NSUserDefaultsDeviceProfileStore` | `NSUserDefaults.standardUserDefaults`，键 `valhalla.profiles` |
| JVM | `InMemoryDeviceProfileStore` | 进程内保存，用于测试宿主；进程退出即丢失 |

- Android 使用 `AndroidBleSupport.requireContext()` 提供的 Application Context，不持有 Activity；
- 平台实现只做 `loadRaw / saveRaw / clear`，不解析内容；
- 读取损坏数据返回 `null`，仓储解码失败时回退为空列表，不向 UI 抛异常。

## 3. 逻辑表结构

### 3.1 设备档案（DeviceProfile）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `deviceId` | String | 主键；BLE 设备标识，不能为空 |
| `displayName` | String? | 扫描到的设备名；后续连接可更新 |
| `vendor` | String? | 厂商（来自 `GET_DEVICE_INFO`） |
| `model` | String? | 型号 |
| `lastConnectedAtEpochMillis` | Long | 最后一次成功连接的 Unix 毫秒；列表排序键 |
| `lastKnownFirmwareVersion` | String? | 最近读取到的固件版本（含 build 与渠道） |
| `lastKnownBatteryPercent` | Int? | 最近电量（0..100） |
| `lastKnownCharging` | Boolean? | 最近充电状态 |
| `otaHistory` | List\<OtaHistoryRecord\> | 最近 OTA 记录，新的在前，默认最多 10 条 |

### 3.2 OTA 历史（OtaHistoryRecord）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `firmwareVersion` | String | 本次执行固件的版本字符串（含 build/渠道） |
| `finishedAtEpochMillis` | Long | 终态时间 |
| `durationMillis` | Long | 从传输准备到终态的耗时 |
| `outcome` | Enum | `SUCCEEDED` / `FAILED` / `ABORTED` |
| `errorCode` | String? | 设备状态码描述（如 `0x0E CRC 校验失败`），成功/中止为 null |
| `detail` | String? | 面向用户的失败/中止原因 |
| `sentBytes` / `totalBytes` | Int | 已发送与总字节，用于展示进度 |

### 3.3 序列化格式（v1）

整表按行存储，字段用 UTF-8 百分号转义（安全字符为 `A-Z a-z 0-9 . _ ~ -`），
空字段表示 `null`：

```
v1
P|deviceId|displayName|vendor|model|lastConnectedAt|firmware|battery|charging
O|firmwareVersion|finishedAt|duration|outcome|errorCode|detail|sent|total
O|...
P|...
```

- 每个 `P` 行开始一条档案，其后的 `O` 行归属该档案；
- `outcome` 使用枚举名；`charging` 使用 `1/0/空`；
- 首行不是 `v1`、字段数量不符或数值无法解析的行会被跳过，读取不抛异常。

## 4. 保留策略

- **档案数量**：默认最多 50 条，超出时淘汰 `lastConnectedAtEpochMillis` 最早的档案；
  可在 `DeviceProfileRepository(store, maxProfiles = ...)` 调整。
- **OTA 历史**：每个档案默认最多 10 条，超出淘汰最旧记录；新的始终在前。
- **删除/清空**：`delete(deviceId)` 删除单条；`clear()` 清空整表并清除底层存储。
- **排序稳定性**：按最后连接时间倒序；时间相同按 `deviceId` 升序，保证列表可复现。

## 5. 写入时机

| 触发 | 写入内容 | 代码 |
| --- | --- | --- |
| 连接成功（`DeviceDetailController` 初始化） | 新建/刷新档案，更新 `displayName` 与最后连接时间 | `recordConnection` |
| 刷新设备信息成功 | 厂商/型号/固件版本/电量/充电状态 | `updateSnapshot` |
| OTA 成功 | `SUCCEEDED` + 总字节 + 耗时 | `OtaController.recordOtaHistory` |
| OTA 失败 | `FAILED` + 状态码 + 可操作原因 + 精确偏移 | 同上 |
| OTA 中止 / 协程取消 | `ABORTED` + 原因 | 同上 |

- 档案写入失败不影响连接与 OTA 主流程（内部 `runCatching` 隔离）；
- 未传入 `profileRepository` / `deviceId` 时不写档案，保持旧调用与测试行为不变。

## 6. 未验证项

- Android `SharedPreferences` 在真机上的实际落盘、进程重启后读取；
- iOS `NSUserDefaults` 在真机/模拟器上的持久化与 App 删除后的清理；
- 多进程并发写入（当前约定单进程使用）。

## 7. 隐私清单单一来源（v1.8.0）

隐私页展示的存储位置、键名与「是否持久化」不再手写，而是来自
`data/src/commonMain/kotlin/com/valhalla/data/settings/StorageInventory.kt`：

- `StorageItem` 枚举覆盖设备档案、OTA 断点、鉴权令牌、报文日志、应用偏好、蓝牙权限标记；
- `StorageKeys` 是 Android/iOS 平台 actual 代码与隐私页共用的常量，避免文案与真实 key 漂移；
- `StorageInventory.forPlatform(platform)` 返回该平台全部条目，`StorageLocation.persistent`
  与实现一致（JVM 宿主全部为内存实现，报文日志在所有平台均不落盘）；
- 应用偏好通过 `AppSettingsStore` 持久化（`SettingsKeys.LANGUAGE` / `SettingsKeys.THEME`），
  Android 为 `SharedPreferences(valhalla_settings)`，iOS 为 `NSUserDefaults` 加
  `valhalla.settings.` 前缀，JVM 为内存实现。

`StorageInventoryTest` 断言每个平台覆盖全部类别、键非空且不重复、令牌条目引用真实的
Keystore/Keychain 常量；`PrivacyDisclosureTest` 断言隐私页条目与清单一致、声明「没有网络上传」。


## 8. 固件库与事件时间线（v2.5.0）

| 数据 | Android | iOS | JVM | 持久化 |
| --- | --- | --- | --- | --- |
| 固件库 | `SharedPreferences(valhalla_firmware_library)`，键 `firmware.library` | `NSUserDefaults`，键 `valhalla.firmware.library` | 内存实现 | Android/iOS 是 |
| 设备事件时间线 | `SharedPreferences(valhalla_device_events)`，键 `device.<deviceId>` | `NSUserDefaults`，键 `valhalla.events.<deviceId>` | 内存实现 | Android/iOS 是 |

- 两者均为行式编码（百分号转义），损坏数据回退为空；
- 固件库只保存元信息与平台文件引用，不缓存二进制；事件时间线每设备 100 条环形；
- 通知开关与低电量阈值存入既有应用偏好（`SettingsKeys.NOTIFY_*`），JVM 恒为内存实现。

## 9. 设备指标采样（v2.6.0）

| 数据 | Android | iOS | JVM | 持久化 |
| --- | --- | --- | --- | --- |
| 设备指标采样 | `SharedPreferences(valhalla_device_metrics)`，键 `metrics.<deviceId>` | `NSUserDefaults`，键 `valhalla.metrics.<deviceId>` | 内存实现 | Android/iOS 是 |

- 内容为 `timestamp|batteryPercent|rssi` 行式编码（`v1` 头），每设备最多 50 点，超出淘汰最旧；
- 空值不写入，编码损坏或版本不符回退为空；读取走本机键值存储，跨进程可读；
- 仪表盘每次刷新采样一次，不足 3 点只显示当前值，不画折线。

## 10. 配置预设与自动化规则（v2.7.0）

| 数据 | 存储位置 | 键 | 持久化 |
| --- | --- | --- | --- |
| 配置预设 | 应用偏好存储（Android `valhalla_settings` / iOS `NSUserDefaults` 前缀） | `SettingsKeys.CONFIG_PRESETS` | Android/iOS 是，JVM 内存 |
| 自动化规则 | 同上 | `SettingsKeys.AUTOMATION_RULES` | Android/iOS 是，JVM 内存 |

- 两者为行式编码（`v1` 头、百分号转义），损坏数据回退为空列表；预设默认上限 50 条，
  规则默认上限 20 条；
- 使用统计不新增存储：连接/写入/体检来自事件时间线，OTA 来自设备档案；
- 偏好备份只导出白名单字段，不含令牌、密钥、凭证、设备档案、日志与断点。

## 11. 实时信号与通知偏好（v2.8.0）

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `rssi_subscription_enabled` | Boolean | `true` | 连接期间实时 RSSI 订阅开关 |
| `rssi_sample_period_seconds` | Int | `3` | 采样周期，1–10 秒 |
| `notify_master_enabled` | Boolean | `true` | 本地通知总开关（各事件渠道仍默认关闭） |
| `notify_reconnect_enabled` | Boolean | `false` | 自动重连耗尽提醒 |
| `notify_health_enabled` | Boolean | `false` | 体检异常提醒 |

- 采样仍写入既有 `DeviceMetricStore`（`v1` 头，50 点/设备）；旧格式读取时按空处理并返回
  `legacyIgnored=true`，新样本继续追加。
- 诊断包不新增持久化：生成在内存中完成，保存走既有文件通道。
