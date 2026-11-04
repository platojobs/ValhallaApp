package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketLogExportTest {

    private fun entry(
        id: Long,
        timestampMillis: Long = 0,
        command: CommandType = CommandType.GET_CONFIG,
        sequence: Int = 0,
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

    @Test
    fun `导出内容包含本地时间相对时间与完整字段`() = runTest {
        val now = 1_793_809_800_000L
        val result = PacketLogExporter.build(
            entries = listOf(
                entry(id = 1, timestampMillis = now - 3_200, sequence = 7),
                entry(
                    id = 2,
                    timestampMillis = now - 65_000,
                    direction = FrameDirection.RX,
                    isResponse = true,
                    command = CommandType.SET_CONFIG,
                    sequence = 8,
                    summary = "SET_CONFIG ok",
                    error = "设备拒绝：0x03",
                ),
            ),
            nowMillis = now,
            zoneOffsetMinutes = 480,
        )

        assertTrue(result.text.contains("导出时间：2026-11-05 00:30:00.000 +08:00"))
        assertTrue(result.text.contains("TX seq=7 GET_CONFIG"))
        assertTrue(result.text.contains("RX RESPONSE seq=8 SET_CONFIG"))
        assertTrue(result.text.contains("摘要：GET_CONFIG key=led_enabled"))
        assertTrue(result.text.contains("错误：设备拒绝：0x03"))
        assertTrue(result.text.contains("HEX：A55A0001000203"))
        assertTrue(result.text.contains("3s前"))
        assertTrue(result.text.contains("1m05s前"))
        assertEquals(2, result.entryCount)
        assertEquals(2, result.sourceCount)
        assertEquals(2, result.matchedCount)
        assertFalse(result.truncated)
        assertEquals(PacketLogExportFormat.TEXT, result.format)
        assertEquals(result.text.encodeToByteArray().size, result.byteSize)
    }

    @Test
    fun `条目上限截断并保留最新条目`() = runTest {
        val entries = (1L..5L).map { id -> entry(id = id, timestampMillis = id * 1_000, sequence = id.toInt()) }
        val result = PacketLogExporter.build(
            entries = entries,
            nowMillis = 5_000,
            zoneOffsetMinutes = 0,
            options = PacketLogExportOptions(maxEntries = 2),
        )

        assertTrue(result.truncated)
        assertEquals(PacketLogTruncationTarget.OLDEST, result.truncationTarget)
        assertEquals(2, result.entryCount)
        assertEquals(5, result.sourceCount)
        assertEquals(5, result.matchedCount)
        assertTrue(result.text.contains("seq=5"))
        assertTrue(result.text.contains("seq=4"))
        assertFalse(result.text.contains("seq=3"), "更旧的条目应被截断")
        assertTrue(result.text.contains("已按上限截断"))
        assertTrue(result.text.contains("截断端=最旧"))
    }

    @Test
    fun `字节上限保护最终文本大小`() = runTest {
        val entries = (1L..6L).map { id ->
            entry(
                id = id,
                timestampMillis = id * 1_000,
                sequence = id.toInt(),
                hex = "AB".repeat(120),
            )
        }
        val result = PacketLogExporter.build(
            entries = entries,
            nowMillis = 6_000,
            zoneOffsetMinutes = 0,
            options = PacketLogExportOptions(maxEntries = 200, maxBytes = 900),
        )

        assertTrue(result.byteSize <= 900, "实际 ${result.byteSize} 字节")
        assertTrue(result.truncated)
        assertTrue(result.entryCount in 1..5)
    }

    @Test
    fun `超长 HEX 在条目内截断并标注省略量`() = runTest {
        val result = PacketLogExporter.build(
            entries = listOf(entry(id = 1, timestampMillis = 1_000, hex = "A".repeat(100))),
            nowMillis = 2_000,
            zoneOffsetMinutes = 0,
            options = PacketLogExportOptions(maxHexCharsPerEntry = 20),
        )

        assertTrue(result.text.contains("A".repeat(20) + "…（省略 80 字符）"))
    }

    @Test
    fun `空日志不输出内容与文件字节`() = runTest {
        val result = PacketLogExporter.build(emptyList(), nowMillis = 1_793_809_800_000L, zoneOffsetMinutes = 480)

        assertTrue(result.empty)
        assertEquals("", result.text)
        assertEquals(0, result.byteSize)
        assertEquals(0, result.entryCount)
        assertFalse(result.truncated)
        assertEquals("valhalla-packet-log-20261105-003000.txt", result.fileName)
    }

    @Test
    fun `绝对时间格式化覆盖负时间与跨日时区`() {
        assertEquals("1970-01-01 00:00:00.000 +00:00", formatAbsoluteTime(0, 0))
        assertEquals("1969-12-31 23:59:59.999 +00:00", formatAbsoluteTime(-1, 0))
        assertEquals("2026-11-04 00:00:00.000 +08:00", formatAbsoluteTime(1_793_721_600_000L, 480))
        assertEquals("2026-11-03 19:00:00.000 -05:00", formatAbsoluteTime(1_793_750_400_000L, -300))
        assertEquals("2026-11-05 00:30:00.000 +08:00", formatAbsoluteTime(1_793_809_800_000L, 480))
    }

    @Test
    fun `相对时间格式化边界`() {
        val now = 1_000_000L
        assertEquals("刚刚", formatRelativeTime(now, now))
        assertEquals("刚刚", formatRelativeTime(now - 999, now))
        assertEquals("1s前", formatRelativeTime(now - 1_000, now))
        assertEquals("59s前", formatRelativeTime(now - 59_999, now))
        assertEquals("1m00s前", formatRelativeTime(now - 60_000, now))
        assertEquals("3m05s前", formatRelativeTime(now - 185_000, now))
        assertEquals("1h00m前", formatRelativeTime(now - 3_600_000, now))
        assertEquals("未来 2s", formatRelativeTime(now + 2_000, now))
    }
}
