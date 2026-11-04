package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 流式导出（v1.6.0）：逐条序列化 + 分块缓冲。
 *
 * 重点验证三件事：
 * 1. 分块拼装与整体拼装的输出字节完全一致（含分隔符、截断元数据与空结果）；
 * 2. 单块缓冲存在硬上限，且峰值不随条目数线性增长；
 * 3. 预估（不拼装文本）与导出结果的条数/字节数一致。
 */
class PacketLogExportStreamingTest {

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

    private val now = 2_000L

    @Test
    fun `TEXT 单条目完整输出与既有格式一致`() = runTest {
        val result = PacketLogExporter.build(
            entries = listOf(entry(id = 1, timestampMillis = 1_000)),
            nowMillis = now,
            zoneOffsetMinutes = 0,
        )
        val expected = buildString {
            append("ValhallaApp 报文日志\n")
            append("导出时间：1970-01-01 00:00:02.000 +00:00\n")
            append("筛选：无\n")
            append("来源：1 条\n")
            append("条目：1 / 1\n")
            append("上限：200 条 / 524288 字节 / 单条 HEX 4096 字符\n")
            append("字段：时间（绝对 | 相对） 方向 [RESPONSE/NOTIFY] seq 命令 / 摘要 / 错误 / HEX\n")
            append("[1970-01-01 00:00:01.000 +00:00 | 1s前] TX seq=1 GET_CONFIG\n")
            append("  摘要：GET_CONFIG key=led_enabled\n")
            append("  HEX：A55A0001000203\n")
        }
        assertEquals(expected, result.text)
        assertEquals(expected.encodeToByteArray().size, result.byteSize)
        assertFalse(result.truncated)
        assertEquals(1, result.entryCount)
    }

    @Test
    fun `JSON 单条目完整输出与既有格式一致`() = runTest {
        val result = PacketLogExporter.build(
            entries = listOf(entry(id = 1, timestampMillis = 1_000)),
            nowMillis = now,
            zoneOffsetMinutes = 0,
            format = PacketLogExportFormat.JSON,
        )
        val expected = buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"exportedAt\": \"1970-01-01 00:00:02.000 +00:00\",\n")
            append("  \"exportedAtMillis\": 2000,\n")
            append("  \"timezoneOffsetMinutes\": 0,\n")
            append("  \"sourceCount\": 1,\n")
            append("  \"matchedCount\": 1,\n")
            append("  \"exportedCount\": 1,\n")
            append("  \"truncated\": false,\n")
            append("  \"truncatedEnd\": null,\n")
            append("  \"limits\": {\"maxEntries\": 200, \"maxBytes\": 524288, \"maxHexCharsPerEntry\": 4096},\n")
            append("  \"filter\": {\"commands\": [], \"errorsOnly\": false, \"keyword\": \"\", \"fromMillis\": null, \"toMillis\": null},\n")
            append("  \"entries\": [\n")
            append("    {\"id\":1,\"timestampMillis\":1000,")
            append("\"timestamp\":\"1970-01-01 00:00:01.000 +00:00\",\"relativeTime\":\"1s前\",")
            append("\"timezoneOffsetMinutes\":0,\"direction\":\"TX\",\"command\":\"GET_CONFIG\",\"sequence\":1,")
            append("\"response\":false,\"notification\":false,\"hex\":\"A55A0001000203\",\"hexOmittedChars\":0,")
            append("\"summary\":\"GET_CONFIG key=led_enabled\",\"error\":null}")
            append("\n  ]\n}\n")
        }
        assertEquals(expected, result.text)
        assertEquals(expected.encodeToByteArray().size, result.byteSize)
    }

    @Test
    fun `CSV 单条目完整输出与既有格式一致`() = runTest {
        val result = PacketLogExporter.build(
            entries = listOf(entry(id = 1, timestampMillis = 1_000)),
            nowMillis = now,
            zoneOffsetMinutes = 0,
            format = PacketLogExportFormat.CSV,
        )
        val expected = buildString {
            append("id,timestampMillis,timestamp,timezoneOffsetMinutes,direction,command,sequence,")
            append("response,notification,hex,hexOmittedChars,summary,error\n")
            append("1,1000,1970-01-01 00:00:01.000 +00:00,0,TX,GET_CONFIG,1,false,false,")
            append("A55A0001000203,0,GET_CONFIG key=led_enabled,\n")
        }
        assertEquals(expected, result.text)
        assertEquals(expected.encodeToByteArray().size, result.byteSize)
    }

    @Test
    fun `JSON 多条条目以逗号换行分隔且截断元数据保持`() = runTest {
        val result = PacketLogExporter.build(
            entries = (1L..3L).map { id -> entry(id = id, summary = "GET_CONFIG entry$id") },
            nowMillis = 3_000,
            zoneOffsetMinutes = 0,
            options = PacketLogExportOptions(maxEntries = 2),
            format = PacketLogExportFormat.JSON,
        )

        assertTrue(result.truncated)
        assertEquals(2, result.entryCount)
        assertTrue(result.text.contains("\"exportedCount\": 2"))
        assertTrue(result.text.contains("\"truncated\": true"))
        assertTrue(result.text.contains("\"truncatedEnd\": \"OLDEST\""))
        // 两条条目之间只有一个 ",\n"，末尾紧跟 footer，不会出现多余逗号。
        assertFalse(result.text.contains("},\n\n"))
        assertTrue(result.text.contains("\"error\":null},\n    {\"id\":2,"))
        assertTrue(result.text.endsWith("\n  ]\n}\n"))
    }

    @Test
    fun `字节上限下三种格式都不超限且结果自洽`() = runTest {
        val entries = (1L..40L).map { id -> entry(id = id, hex = "AB".repeat(80)) }
        for (format in PacketLogExportFormat.entries) {
            for (cap in listOf(900, 2_000, 8_000)) {
                val result = PacketLogExporter.build(
                    entries = entries,
                    nowMillis = 40_000,
                    zoneOffsetMinutes = 0,
                    options = PacketLogExportOptions(maxEntries = 40, maxBytes = cap),
                    format = format,
                )
                assertEquals(result.text.encodeToByteArray().size, result.byteSize, "$format/$cap")
                assertTrue(result.byteSize <= cap, "$format/$cap 实际 ${result.byteSize}")
                assertTrue(result.entryCount <= 40)
                if (result.entryCount == 0) {
                    assertTrue(result.truncated)
                    assertTrue(result.notice!!.contains("大小上限过小"))
                } else {
                    assertTrue(
                        result.peakBufferChars in 1..PacketLogExporter.STREAM_CHUNK_CHARS,
                        "$format/$cap 峰值 ${result.peakBufferChars}",
                    )
                }
            }
        }
    }

    @Test
    fun `分块缓冲峰值不超过块大小且不随条目数增长`() = runTest {
        suspend fun peak(count: Int): Pair<Int, Int> {
            val result = PacketLogExporter.build(
                entries = (1L..count.toLong()).map { id ->
                    entry(id = id, hex = "A55A".repeat(100), summary = "GET_CONFIG payload")
                },
                nowMillis = count * 1_000L,
                zoneOffsetMinutes = 0,
                options = PacketLogExportOptions(maxEntries = 5000, maxBytes = PacketLogExportOptions.MAX_BYTES),
                format = PacketLogExportFormat.JSON,
            )
            assertEquals(result.text.encodeToByteArray().size, result.byteSize)
            return result.peakBufferChars to result.text.length
        }

        val small = peak(200)
        val large = peak(2_000)

        assertTrue(small.second > PacketLogExporter.STREAM_CHUNK_CHARS, "样本应大于一个分块")
        assertTrue(large.second > small.second * 4, "大样本文本应显著更长")
        assertTrue(small.first <= PacketLogExporter.STREAM_CHUNK_CHARS, "小样本峰值 ${small.first}")
        assertTrue(large.first <= PacketLogExporter.STREAM_CHUNK_CHARS, "大样本峰值 ${large.first}")
        assertEquals(
            PacketLogExporter.STREAM_CHUNK_CHARS,
            large.first,
            "大样本应恰好填满分块，峰值由块大小而非条目数决定",
        )
    }

    @Test
    fun `预估不拼装文本但条数与字节和导出一致`() = runTest {
        val entries = (1L..50L).map { id -> entry(id = id, hex = "AB".repeat(60)) }
        val options = PacketLogExportOptions(maxEntries = 50, maxBytes = 4_000)
        val estimate = PacketLogExporter.estimate(
            entries = entries,
            nowMillis = 50_000,
            zoneOffsetMinutes = 0,
            options = options,
            format = PacketLogExportFormat.CSV,
        )
        val result = PacketLogExporter.build(
            entries = entries,
            nowMillis = 50_000,
            zoneOffsetMinutes = 0,
            options = options,
            format = PacketLogExportFormat.CSV,
        )

        assertEquals(result.entryCount, estimate.estimatedEntries)
        assertEquals(result.byteSize, estimate.estimatedBytes)
        assertEquals(result.truncated, estimate.truncated)
        assertTrue(estimate.estimatedBytes <= options.maxBytes)
    }

    @Test
    fun `ChunkedTextBuilder 按块切分并保持拼接顺序`() {
        val builder = ChunkedTextBuilder(chunkChars = 3)
        builder.append("abcdefghij")
        builder.append("")
        builder.append("123456789")

        assertEquals("abcdefghij123456789", builder.assemble())
        assertTrue(builder.peakBufferedChars <= 3, "峰值 ${builder.peakBufferedChars}")
        assertEquals(3, builder.peakBufferedChars)
    }

    @Test
    fun `ChunkedTextBuilder 拒绝非法块大小`() {
        assertFailsWith<IllegalArgumentException> { ChunkedTextBuilder(chunkChars = 0) }
        assertFailsWith<IllegalArgumentException> { ChunkedTextBuilder(chunkChars = -1) }
    }

    @Test
    fun `空结果不产生文本也不记录峰值`() = runTest {
        val result = PacketLogExporter.build(
            entries = emptyList(),
            nowMillis = 2_000,
            zoneOffsetMinutes = 0,
            format = PacketLogExportFormat.JSON,
        )
        assertEquals("", result.text)
        assertEquals(0, result.byteSize)
        assertEquals(0, result.peakBufferChars)
    }
}
