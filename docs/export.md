# 报文日志导出说明

策略与平台差异的总体说明见 [architecture.md](architecture.md) 6.6 节；本文给出文件名、
筛选/上限规则与三种格式的示例。

## 文件名与保存

- 规则：`valhalla-packet-log-<YYYYMMDD-HHMMSS>.<ext>`，时间为本地时间（含时区偏移）；
- 后缀：纯文本 `.txt`、结构化 JSON `.json`、表格 CSV `.csv`；
- 重名：会话内已用文件名冲突时依次追加 `-1`、`-2`…（`PacketLogExporter.resolveFileName`，
  忽略大小写）。系统 SAF / 文档选择器仍可能就覆盖做二次确认；
- 保存入口：Android `ACTION_CREATE_DOCUMENT`（SAF），iOS
  `UIDocumentPickerViewController(forExportingURLs:asCopy:)`；分享入口继续使用
  `TextSharer`（Android `ACTION_SEND` / iOS `UIActivityViewController`）。

## 筛选规则

| 条件 | 语义 |
| --- | --- |
| 命令集合 | 多选；为空表示全部命令（单选 `command` 仍兼容，集合非空时优先） |
| 仅错误 | 只保留 `error != null` 的条目（解析失败或设备返回非 0 状态） |
| 关键字 | 匹配命令名 / 摘要 / HEX / seq / 错误文本，忽略大小写 |
| 时间范围 | 闭区间 `fromMillis <= timestampMillis <= toMillis`，任一侧为空表示不限；起 > 止时不匹配任何条目 |

列表展示、索引查询与导出共用 `PacketLogFilter.matches`，不会出现两套筛选口径。
时间输入格式为 `YYYY-MM-DD HH:mm:ss`，可带 `.SSS` 毫秒与 `T` 分隔符。

## 上限与截断

- 条数上限：`1..5000`，默认 200；
- 大小上限：`512 B..16 MiB`，UI 以 KB 输入（`1..16384`），默认 512 KiB；
- 单条 HEX：最多 `65536` 字符，默认 4096；超出时在条目内标注省略量；
- 截断方向：只丢弃**最旧**的一端（`PacketLogTruncationTarget.OLDEST`），保留「最新优先」；
- 非法输入（空、非整数、越界）由 `validateEntryLimit` / `validateByteLimitKb` 拒绝，
  UI 禁用导出并显示原因；
- 导出前 `estimate()` 给出条数与字节数预估；筛选无命中或上限过小时返回空结果，
  不生成文件。

## TEXT 示例

```
ValhallaApp 报文日志
导出时间：2026-11-05 00:30:00.000 +08:00
筛选：命令=SET_CONFIG；仅错误
来源：3 条
条目：2 / 2
上限：200 条 / 524288 字节 / 单条 HEX 4096 字符
字段：时间（绝对 | 相对） 方向 [RESPONSE/NOTIFY] seq 命令 / 摘要 / 错误 / HEX
[2026-11-05 00:29:58.000 +08:00 | 2s前] RX RESPONSE seq=8 SET_CONFIG
  摘要：SET_CONFIG ok
  错误：设备拒绝：0x03
  HEX：A55A0C00080003
[2026-11-05 00:29:00.000 +08:00 | 1m00s前] TX seq=7 SET_CONFIG
  摘要：SET_CONFIG key=led_enabled value=1B
  HEX：A55A03000700
```

## JSON 示例

字段完整、稳定，适合程序处理；`error` 无值为 `null`，`hexOmittedChars` 记录被截断的 HEX 字符数。

```json
{
  "schemaVersion": 1,
  "exportedAt": "2026-11-05 00:30:00.000 +08:00",
  "exportedAtMillis": 1793809800000,
  "timezoneOffsetMinutes": 480,
  "sourceCount": 3,
  "matchedCount": 2,
  "exportedCount": 2,
  "truncated": false,
  "truncatedEnd": null,
  "limits": {"maxEntries": 200, "maxBytes": 524288, "maxHexCharsPerEntry": 4096},
  "filter": {"commands": ["SET_CONFIG"], "errorsOnly": true, "keyword": "", "fromMillis": null, "toMillis": null},
  "entries": [
    {"id": 8, "timestampMillis": 1793809798000, "timestamp": "2026-11-05 00:29:58.000 +08:00", "relativeTime": "2s前", "timezoneOffsetMinutes": 480, "direction": "RX", "command": "SET_CONFIG", "sequence": 8, "response": true, "notification": false, "hex": "A55A0C00080003", "hexOmittedChars": 0, "summary": "SET_CONFIG ok", "error": "设备拒绝：0x03"},
    {"id": 7, "timestampMillis": 1793809740000, "timestamp": "2026-11-05 00:29:00.000 +08:00", "relativeTime": "1m00s前", "timezoneOffsetMinutes": 480, "direction": "TX", "command": "SET_CONFIG", "sequence": 7, "response": false, "notification": false, "hex": "A55A03000700", "hexOmittedChars": 0, "summary": "SET_CONFIG key=led_enabled value=1B", "error": null}
  ]
}
```

截断时 `truncated = true`、`truncatedEnd = "OLDEST"`，`exportedCount < matchedCount`。

## CSV 示例

首行为表头；包含逗号、双引号、换行或回车的字段整体加双引号，内部双引号加倍。

```csv
id,timestampMillis,timestamp,timezoneOffsetMinutes,direction,command,sequence,response,notification,hex,hexOmittedChars,summary,error
8,1793809798000,2026-11-05 00:29:58.000 +08:00,480,RX,SET_CONFIG,8,true,false,A55A0C00080003,0,SET_CONFIG ok,设备拒绝：0x03
7,1793809740000,2026-11-05 00:29:00.000 +08:00,480,TX,SET_CONFIG,7,false,false,A55A03000700,0,"SET_CONFIG key=led_enabled value=1B",
```

HEX 若包含逗号会被整体加引号；`error` 为空时保留空列。

## 进度、取消与清理

- `PacketLogExporter.build` 是 `suspend` 函数，筛选/渲染/拼装阶段分批回调
  `PacketLogExportProgress`（`FILTERING` / `RENDERING` / `ASSEMBLING`）并检查协程取消；
- UI 在 `Dispatchers.Default` 执行，期间可点「取消」；
- 只有内容完全构建成功后才会调用平台保存/分享，因此取消不会在用户目录留下半文件；
  iOS 的临时文件仅是内容快照，未选择保存位置时不会被导出。
