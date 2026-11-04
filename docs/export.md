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

## 脱敏（v1.9.0）

脱敏在写入 `InMemoryPacketLog` 时完成，导出层拿到的是已脱敏条目，因此 TEXT / JSON / CSV
与剪贴板内容一致，且没有任何导出选项可以关闭：

- `AUTH` 请求的 credential、成功应答的 token/challenge 全部替换为 `*`，保留长度与结构字段；
- `GET_DEVICE_INFO` 的 serialNumber 保留前 4 字节，其余掩码；
- 其他命令原样保留。

规则表、设备标识截断/哈希方案与验证用例见 [privacy.md](privacy.md)。

## 流式拼装（v1.6.0）

导出内容不再一次性拼装成超大字符串：

1. **预算阶段**：按「最新优先」逐条渲染，只累计每条条目的 UTF-8 字节数（`IntArray`），
   同时处理条数上限与字节上限；不保留条目文本。
2. **拼装阶段**：确定最终条数与截断标记后，重新逐条渲染并写入 `ChunkedTextBuilder`，
   按 8192 字符（`PacketLogExporter.STREAM_CHUNK_CHARS`）切块缓冲，最后顺序拼接。
3. **等价性**：头部、分隔符、条目与尾部片段仍由同一 renderer 产出，分块只改变拼接方式，
   因此 TEXT/JSON/CSV 输出与 v1.5.0 字节层面等价（含截断元数据与空结果语义）。
4. **可观测峰值**：`PacketLogExportResult.peakBufferChars` 返回单块缓冲的峰值字符数，
   恒 ≤ 8192；测试断言该值不随条目数增长（200 条与 2000 条均达到块上限）。
5. **预估**：`estimate()` 只走预算阶段（`assembleOutput = false`），字节数由片段求和得到，
   与 `build()` 的 `byteSize` 完全一致。

## 基准（v1.6.0）

基准不进入常规单测 / CI，手动执行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:benchmarkExport
# 可传参数（示例）：./gradlew :core:benchmarkExport --args="--entries=5000 --iterations=3"
```

样本：5000 条报文，每条 HEX 约 3.6 KiB 字符；JSON 上限 16 MiB（超出部分从最旧端截断）。
本机（macOS aarch64 / JVM 21.0.6，Apple Silicon）实测：

| 指标 | 结果 |
| --- | --- |
| 样本 | 5000 条 → 实际导出 4251 条 / 16.00 MiB |
| 生成耗时（平均） | 2 ms |
| 导出耗时（平均 / 最小） | 117 ms / 113 ms |
| 导出吞吐 | 约 137 MiB/s |
| 峰值单块缓冲 | 8192 字符（= 块上限） |
| 导出后堆占用（未 GC，含待回收临时对象） | 约 273 MiB |
| 导出后保留堆（GC 后） | 约 53 MiB |
| 输出体积/条 | 约 3946 B |

说明：

- 堆占用取自 `Runtime.totalMemory - freeMemory`，受 GC 时机影响，仅作量级参考；
- 真正的内存上限证据是 `peakBufferChars`：无论 200 条还是 5000 条，单块缓冲都不超过 8192 字符；
- 预算阶段与拼装阶段各渲染一次（时间换内存），因此耗时约为单次渲染的两倍。

