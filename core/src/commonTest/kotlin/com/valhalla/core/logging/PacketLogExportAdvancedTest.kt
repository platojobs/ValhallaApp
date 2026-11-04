package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketLogExportAdvancedTest {

    private fun entry(
        id: Long,
        timestampMillis: Long = id * 1_000,
        command: CommandType = CommandType.GET_CONFIG,
        sequence: Int = id.toInt(),
        direction: FrameDirection = FrameDirection.TX,
        isResponse: Boolean = false,
        hex: String = "A55A0001000203",
        summary: String = "GET_CONFIG key=led_enabled",
        error: String? = null,
    ) = PacketLogEntry(
        id = id,
        timestampMillis = timestampMillis,
        direction = direction,
        command = command,
        sequence = sequence,
        isResponse = isResponse,
        isNotification = false,
        hex = hex,
        summary = summary,
        error = error,
    )

    private val entries = listOf(
        entry(1, command = CommandType.GET_CONFIG, summary = "GET_CONFIG alpha"),
        entry(2, command = CommandType.SET_CONFIG, summary = "SET_CONFIG beta"),
        entry(
            id = 3,
            command = CommandType.SET_CONFIG,
            summary = "SET_CONFIG failed",
            error = "设备拒绝：0x03",
            direction = FrameDirection.RX,
            isResponse = true,
        ),
        entry(4, command = CommandType.START_OTA, summary = "START_OTA total=1024"),
    )

    @Test
    fun `筛选组合同时覆盖命令集合时间闭区间与仅错误`() = runTest {
        val filter = PacketLogFilter(
            commands = setOf(CommandType.SET_CONFIG, CommandType.START_OTA),
            errorsOnly = false,
            fromMillis = 2_000,
            toMillis = 4_000,
        )

        val expected = filterPacketEntries(entries, filter)
        assertEquals(listOf(4L, 3L, 2L), expected.map { it.id }, "时间边界应包含起止端点")
        assertEquals(expected.map { it.id }, PacketLogIndex(entries).query(filter).map { it.id })

        val result = PacketLogExporter.build(
            entries = entries,
            nowMillis = 5_000,
            zoneOffsetMinutes = 0,
            filter = filter,
        )
        assertEquals(3, result.matchedCount)
        assertEquals(3, result.entryCount)
        assertFalse(result.text.contains("GET_CONFIG alpha"))
    }

    @Test
    fun `时间范围倒序时线性与索引都返回空且导出为空文件`() = runTest {
        val filter = PacketLogFilter(fromMillis = 4_000, toMillis = 1_000)
        assertTrue(filter.timeRangeReversed)
        assertTrue(filterPacketEntries(entries, filter).isEmpty())
        assertTrue(PacketLogIndex(entries).query(filter).isEmpty())

        val result = PacketLogExporter.build(
            entries = entries,
            nowMillis = 5_000,
            zoneOffsetMinutes = 0,
            filter = filter,
        )
        assertTrue(result.empty)
        assertEquals("", result.text)
        assertEquals(0, result.matchedCount)
    }

    @Test
    fun `仅错误与命令集合与关键字组合`() = runTest {
        val filter = PacketLogFilter(
            commands = setOf(CommandType.SET_CONFIG),
            errorsOnly = true,
            keyword = "0x03",
        )
        assertEquals(listOf(3L), filterPacketEntries(entries, filter).map { it.id })
        assertEquals(listOf(3L), PacketLogIndex(entries).query(filter).map { it.id })

        val result = PacketLogExporter.build(
            entries = entries,
            nowMillis = 5_000,
            zoneOffsetMinutes = 0,
            filter = filter,
        )
        assertEquals(1, result.entryCount)
        assertTrue(result.text.contains("错误：设备拒绝：0x03"))
    }

    @Test
    fun `预估与实际导出的条数与截断方向一致`() = runTest {
        val options = PacketLogExportOptions(maxEntries = 2, maxBytes = 64 * 1024)
        val estimate = PacketLogExporter.estimate(
            entries = entries,
            nowMillis = 5_000,
            zoneOffsetMinutes = 0,
            options = options,
        )
        val result = PacketLogExporter.build(
            entries = entries,
            nowMillis = 5_000,
            zoneOffsetMinutes = 0,
            options = options,
        )

        assertEquals(4, estimate.matchedCount)
        assertEquals(result.entryCount, estimate.estimatedEntries)
        assertEquals(result.byteSize, estimate.estimatedBytes)
        assertTrue(estimate.truncated)
        assertEquals(PacketLogTruncationTarget.OLDEST, estimate.truncationTarget)
        assertEquals(PacketLogTruncationTarget.OLDEST, result.truncationTarget)
        assertTrue(result.text.contains("seq=4"))
        assertFalse(result.text.contains("seq=2"))
    }

    @Test
    fun `条数上限输入校验覆盖空非整数与越界`() {
        assertFalse(validateEntryLimit("").valid)
        assertFalse(validateEntryLimit("abc").valid)
        assertFalse(validateEntryLimit("0").valid)
        assertFalse(validateEntryLimit("5001").valid)
        assertEquals(1, validateEntryLimit(" 1 ").value)
        assertEquals(5000, validateEntryLimit("5000").value)
        assertTrue(validateEntryLimit("5001").error!!.contains("1..5000"))
    }

    @Test
    fun `大小上限输入校验以 KB 为单位换算为字节`() {
        assertFalse(validateByteLimitKb("").valid)
        assertFalse(validateByteLimitKb("1.5").valid)
        assertFalse(validateByteLimitKb("0").valid)
        assertFalse(validateByteLimitKb("16385").valid)
        assertEquals(1024, validateByteLimitKb("1").value)
        assertEquals(16 * 1024 * 1024, validateByteLimitKb("16384").value)
        assertTrue(validateByteLimitKb("16385").error!!.contains("16384"))
    }

    @Test
    fun `构造选项时拒绝超出保护范围的值`() {
        assertFailsWith<IllegalArgumentException> { PacketLogExportOptions(maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { PacketLogExportOptions(maxEntries = 5001) }
        assertFailsWith<IllegalArgumentException> { PacketLogExportOptions(maxBytes = 511) }
        assertFailsWith<IllegalArgumentException> {
            PacketLogExportOptions(maxBytes = PacketLogExportOptions.MAX_BYTES + 1024)
        }
    }

    @Test
    fun `JSON 结构包含完整字段并转义特殊字符`() = runTest {
        val tricky = entry(
            id = 7,
            timestampMillis = 4_000,
            command = CommandType.SET_CONFIG,
            sequence = 42,
            direction = FrameDirection.RX,
            isResponse = true,
            hex = "A5\"5A\\01" + "\n" + "02",
            summary = "SET_CONFIG \"quoted\", path=C:\\tmp\n第二行",
            error = "设备拒绝：0x03",
        )
        val result = PacketLogExporter.build(
            entries = listOf(tricky),
            nowMillis = 5_000,
            zoneOffsetMinutes = 480,
            format = PacketLogExportFormat.JSON,
        )

        assertEquals("valhalla-packet-log-19700101-080005.json", result.fileName)
        assertTrue(result.text.startsWith("{\n"), "JSON 应以对象开头")
        assertTrue(result.text.contains("\"schemaVersion\": 1"))
        assertTrue(result.text.contains("\"exportedCount\": 1"))
        assertTrue(result.text.contains("\"entries\": ["))
        assertTrue(result.text.contains("\"id\":7"))
        assertTrue(result.text.contains("\"timestampMillis\":4000"))
        assertTrue(result.text.contains("\"timestamp\":\"1970-01-01 08:00:04.000 +08:00\""))
        assertTrue(result.text.contains("\"relativeTime\":\"1s前\""))
        assertTrue(result.text.contains("\"timezoneOffsetMinutes\":480"))
        assertTrue(result.text.contains("\"direction\":\"RX\""))
        assertTrue(result.text.contains("\"command\":\"SET_CONFIG\""))
        assertTrue(result.text.contains("\"sequence\":42"))
        assertTrue(result.text.contains("\"response\":true"))
        assertTrue(result.text.contains("\"notification\":false"))
        assertTrue(result.text.contains("\"hexOmittedChars\":0"))
        assertTrue(result.text.contains("path=C:\\\\tmp\\n第二行"), "反斜杠与换行应被转义")
        assertTrue(result.text.contains("\"error\":\"设备拒绝：0x03\""))
        assertTrue(result.text.endsWith("  ]\n}\n"))
    }

    @Test
    fun `JSON 截断时给出截断端与上限`() = runTest {
        val entries = (1L..3L).map { id ->
            entry(id = id, summary = "GET_CONFIG entry$id", hex = "AB".repeat(30))
        }
        val result = PacketLogExporter.build(
            entries = entries,
            nowMillis = 3_000,
            zoneOffsetMinutes = 0,
            options = PacketLogExportOptions(maxEntries = 1),
            format = PacketLogExportFormat.JSON,
        )

        assertTrue(result.truncated)
        assertTrue(result.text.contains("\"truncated\": true"))
        assertTrue(result.text.contains("\"truncatedEnd\": \"OLDEST\""))
        assertTrue(result.text.contains("\"maxEntries\": 1"))
        assertTrue(result.text.contains("\"exportedCount\": 1"))
        assertFalse(result.text.contains("entry1"))
        assertTrue(result.text.contains("entry3"))
    }

    @Test
    fun `CSV 对逗号引号换行与 HEX 做转义`() = runTest {
        val tricky = entry(
            id = 1,
            timestampMillis = 1_000,
            summary = "SET_CONFIG name=\"a,b\"\nnext",
            error = "含,逗号",
            hex = "A55A,0001",
        )
        val result = PacketLogExporter.build(
            entries = listOf(tricky),
            nowMillis = 2_000,
            zoneOffsetMinutes = 0,
            format = PacketLogExportFormat.CSV,
        )

        assertEquals("valhalla-packet-log-19700101-000002.csv", result.fileName)
        val lines = result.text.split('\n')
        assertTrue(
            result.text.startsWith(
                "id,timestampMillis,timestamp,timezoneOffsetMinutes,direction,command," +
                    "sequence,response,notification,hex,hexOmittedChars,summary,error",
            ),
        )
        assertTrue(
            result.text.contains("\"SET_CONFIG name=\"\"a,b\"\"\nnext\""),
            "CSV 字段内的引号应加倍并整体加引号",
        )
        assertTrue(result.text.contains("\"A55A,0001\""), "HEX 含逗号时应保留在字段中")
        assertTrue(result.text.contains("\"含,逗号\""))
        assertTrue(lines.size >= 3)
    }

    @Test
    fun `超长 HEX 在 JSON 与 CSV 中记录省略量`() = runTest {
        val longHex = "A".repeat(100)
        val json = PacketLogExporter.build(
            entries = listOf(entry(1, hex = longHex)),
            nowMillis = 2_000,
            zoneOffsetMinutes = 0,
            options = PacketLogExportOptions(maxHexCharsPerEntry = 20),
            format = PacketLogExportFormat.JSON,
        )
        val csv = PacketLogExporter.build(
            entries = listOf(entry(1, hex = longHex)),
            nowMillis = 2_000,
            zoneOffsetMinutes = 0,
            options = PacketLogExportOptions(maxHexCharsPerEntry = 20),
            format = PacketLogExportFormat.CSV,
        )

        assertTrue(json.text.contains("\"hex\":\"${"A".repeat(20)}\""))
        assertTrue(json.text.contains("\"hexOmittedChars\":80"))
        assertTrue(csv.text.contains("${"A".repeat(20)},80,"))
    }

    @Test
    fun `文件名按格式生成并处理重名冲突`() {
        assertEquals(
            "valhalla-packet-log-20261105-003000.json",
            PacketLogExporter.fileName(1_793_809_800_000L, 480, PacketLogExportFormat.JSON),
        )
        assertEquals(
            "valhalla-packet-log-20261105-003000.csv",
            PacketLogExporter.fileName(1_793_809_800_000L, 480, PacketLogExportFormat.CSV),
        )
        val base = "valhalla-packet-log-20261105-003000.txt"
        assertEquals(base, PacketLogExporter.resolveFileName(base, emptyList()))
        assertEquals(
            "valhalla-packet-log-20261105-003000-1.txt",
            PacketLogExporter.resolveFileName(base, listOf(base)),
        )
        assertEquals(
            "valhalla-packet-log-20261105-003000-2.txt",
            PacketLogExporter.resolveFileName(
                base,
                listOf(base, "valhalla-packet-log-20261105-003000-1.txt"),
            ),
        )
        assertEquals(
            "valhalla-packet-log-20261105-003000-1.txt",
            PacketLogExporter.resolveFileName(base, listOf(base.uppercase())),
        )
    }

    @Test
    fun `取消导出会抛出 CancellationException 且不产生结果`() = runTest {
        var result: PacketLogExportResult? = null
        var renderStarted = false
        var job: Job? = null
        job = launch(start = CoroutineStart.LAZY) {
            result = PacketLogExporter.build(
                entries = (1L..300L).map { id -> entry(id, summary = "GET_CONFIG entry$id") },
                nowMillis = 300_000,
                zoneOffsetMinutes = 0,
                onProgress = { progress ->
                    if (progress.phase == PacketLogExportPhase.RENDERING && !renderStarted) {
                        renderStarted = true
                        job?.cancel()
                    }
                },
            )
        }
        job.start()
        job.join()

        assertTrue(job.isCancelled, "导出任务应处于取消状态")
        assertTrue(renderStarted, "取消应发生在渲染阶段")
        assertNull(result, "取消后不应返回任何导出内容，调用方因此不会写文件")
    }

    @Test
    fun `导出进度覆盖筛选渲染与拼装阶段`() = runTest {
        val events = mutableListOf<PacketLogExportProgress>()
        val result = PacketLogExporter.build(
            entries = (1L..20L).map { id -> entry(id) },
            nowMillis = 20_000,
            zoneOffsetMinutes = 0,
            onProgress = { events += it },
        )

        assertEquals(20, result.entryCount)
        assertTrue(events.any { it.phase == PacketLogExportPhase.FILTERING && it.total == 20 })
        assertTrue(events.any { it.phase == PacketLogExportPhase.RENDERING })
        val assembling = events.last()
        assertEquals(PacketLogExportPhase.ASSEMBLING, assembling.phase)
        assertEquals(1f, assembling.fraction)
    }

    @Test
    fun `本地时间输入解析与绝对时间格式同口径`() {
        assertEquals(
            1_793_809_800_000L,
            parseLocalDateTimeMillis("2026-11-05 00:30:00", 480),
        )
        assertEquals(
            1_793_809_800_000L,
            parseLocalDateTimeMillis("2026-11-05T00:30:00.000", 480),
        )
        assertEquals(0L, parseLocalDateTimeMillis("1970-01-01 00:00:00", 0))
        assertNull(parseLocalDateTimeMillis("2026-11-05", 480))
        assertNull(parseLocalDateTimeMillis("2026-02-30 00:00:00", 480))
        assertNull(parseLocalDateTimeMillis("2026-11-05 24:00:00", 480))
        assertNull(parseLocalDateTimeMillis("abc", 480))
        assertEquals(
            "2026-11-05 00:30:00.000 +08:00",
            formatAbsoluteTime(parseLocalDateTimeMillis("2026-11-05 00:30:00", 480)!!, 480),
        )
    }

    @Test
    fun `上限过小时不导出任何条目并给出提示`() = runTest {
        val result = PacketLogExporter.build(
            entries = listOf(entry(1, hex = "A".repeat(4096))),
            nowMillis = 2_000,
            zoneOffsetMinutes = 0,
            options = PacketLogExportOptions(maxEntries = 200, maxBytes = PacketLogExportOptions.MIN_BYTES),
        )

        assertTrue(result.empty)
        assertEquals("", result.text)
        assertEquals(0, result.entryCount)
        assertTrue(result.truncated)
        assertTrue(result.notice!!.contains("大小上限过小"))
    }
}
