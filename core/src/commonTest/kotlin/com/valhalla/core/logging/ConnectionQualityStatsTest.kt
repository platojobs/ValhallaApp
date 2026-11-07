package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionQualityStatsTest {
    private var nextId = 1L

    private fun entry(
        sequence: Int,
        direction: FrameDirection,
        command: CommandType = CommandType.GET_CONFIG,
        timestamp: Long,
        isResponse: Boolean = false,
        isNotification: Boolean = false,
        error: String? = null,
    ) = PacketLogEntry(
        id = nextId++,
        timestampMillis = timestamp,
        direction = direction,
        command = command,
        sequence = sequence,
        isResponse = isResponse,
        isNotification = isNotification,
        hex = "00",
        summary = "${direction.name} ${command.name}",
        error = error,
    )

    @Test
    fun `正常序列统计成功率与时延`() {
        val entries =
            listOf(
                entry(1, FrameDirection.TX, timestamp = 100),
                entry(1, FrameDirection.RX, isResponse = true, timestamp = 150),
                entry(2, FrameDirection.TX, timestamp = 200),
                entry(2, FrameDirection.RX, isResponse = true, timestamp = 300),
            )
        val stats = ConnectionQualityAnalyzer.analyze(entries)

        assertEquals(2, stats.requests)
        assertEquals(2, stats.responses)
        assertEquals(2, stats.successes)
        assertEquals(0, stats.deviceErrors)
        assertEquals(0, stats.noResponses)
        assertEquals(0, stats.retransmissions)
        assertEquals(0, stats.outOfOrderResponses)
        assertEquals(1.0, stats.successRate)
        assertEquals(75.0, stats.averageLatencyMillis)
        assertEquals(100L, stats.maxLatencyMillis)
        assertTrue(stats.insufficientSample)
        assertEquals(2, stats.sampleCount)
    }

    @Test
    fun `超时请求计入无应答且不出现在成功率分子中`() {
        val entries =
            listOf(
                entry(1, FrameDirection.TX, timestamp = 100),
                entry(1, FrameDirection.RX, isResponse = true, timestamp = 200),
                entry(2, FrameDirection.TX, timestamp = 300),
            )
        val stats = ConnectionQualityAnalyzer.analyze(entries)

        assertEquals(2, stats.requests)
        assertEquals(1, stats.successes)
        assertEquals(1, stats.noResponses)
        assertEquals(0.5, stats.successRate)
        assertEquals(1, stats.recentFailures.size)
        assertTrue(stats.recentFailures.first().noResponse)
        assertEquals("无应答", stats.recentFailures.first().detail)
    }

    @Test
    fun `乱序应答被计数但不影响成功判定`() {
        val entries =
            listOf(
                entry(1, FrameDirection.TX, timestamp = 100),
                entry(2, FrameDirection.TX, timestamp = 120),
                entry(2, FrameDirection.RX, isResponse = true, timestamp = 180),
                entry(1, FrameDirection.RX, isResponse = true, timestamp = 200),
            )
        val stats = ConnectionQualityAnalyzer.analyze(entries)

        assertEquals(2, stats.successes)
        assertEquals(1, stats.outOfOrderResponses)
        assertEquals(0, stats.unmatchedResponses)
    }

    @Test
    fun `同一 sequence 重复发送计为重传且时延取最后一次发送`() {
        val entries =
            listOf(
                entry(1, FrameDirection.TX, timestamp = 100),
                entry(1, FrameDirection.TX, timestamp = 200),
                entry(1, FrameDirection.RX, isResponse = true, timestamp = 260),
            )
        val stats = ConnectionQualityAnalyzer.analyze(entries)

        assertEquals(1, stats.requests)
        assertEquals(1, stats.retransmissions)
        assertEquals(1, stats.successes)
        // 260 - 200 = 60，而不是 260 - 100。
        assertEquals(60.0, stats.averageLatencyMillis)
    }

    @Test
    fun `无样本时不给出成功率与时延`() {
        val stats = ConnectionQualityAnalyzer.analyze(emptyList())

        assertEquals(0, stats.requests)
        assertNull(stats.successRate)
        assertNull(stats.averageLatencyMillis)
        assertNull(stats.maxLatencyMillis)
        assertTrue(stats.insufficientSample)
        assertEquals(0, stats.sampleCount)
        assertTrue(stats.commands.isEmpty())
        assertTrue(stats.recentFailures.isEmpty())
    }

    @Test
    fun `错误码按次数聚合并带标题`() {
        val entries =
            listOf(
                entry(1, FrameDirection.TX, timestamp = 100),
                entry(1, FrameDirection.RX, isResponse = true, timestamp = 150, error = "0x05 鉴权失败"),
                entry(2, FrameDirection.TX, timestamp = 200),
                entry(2, FrameDirection.RX, isResponse = true, timestamp = 250, error = "0x05 鉴权失败"),
                entry(3, FrameDirection.TX, timestamp = 300),
                entry(3, FrameDirection.RX, isResponse = true, timestamp = 350, error = "0x03 参数越界"),
            )
        val stats = ConnectionQualityAnalyzer.analyze(entries)

        assertEquals(3, stats.deviceErrors)
        assertEquals(0, stats.successes)
        assertEquals(2, stats.errorCodes.size)
        assertEquals("0x05", stats.errorCodes.first().code)
        assertEquals(2, stats.errorCodes.first().count)
        assertTrue(
            stats.errorCodes
                .first()
                .title
                .isNotBlank(),
        )
        assertEquals(3, stats.recentFailures.size)
        assertFalse(stats.recentFailures.first().noResponse)
    }

    @Test
    fun `通知帧与无配对应答不进入请求统计`() {
        val entries =
            listOf(
                entry(1, FrameDirection.TX, timestamp = 100),
                entry(1, FrameDirection.RX, isResponse = true, timestamp = 150),
                entry(
                    0,
                    FrameDirection.RX,
                    command = CommandType.HEARTBEAT_REPLY,
                    isResponse = false,
                    isNotification = true,
                    timestamp = 160,
                ),
                entry(99, FrameDirection.RX, isResponse = true, timestamp = 170),
            )
        val stats = ConnectionQualityAnalyzer.analyze(entries)

        assertEquals(1, stats.requests)
        assertEquals(2, stats.responses)
        assertEquals(1, stats.unmatchedResponses)
        assertEquals(1, stats.successes)
    }

    @Test
    fun `按命令维度拆分请求与错误`() {
        val entries =
            listOf(
                entry(1, FrameDirection.TX, CommandType.GET_CONFIG, timestamp = 100),
                entry(1, FrameDirection.RX, CommandType.GET_CONFIG, isResponse = true, timestamp = 160),
                entry(2, FrameDirection.TX, CommandType.GET_CONFIG, timestamp = 200),
                entry(2, FrameDirection.RX, CommandType.GET_CONFIG, isResponse = true, timestamp = 260),
                entry(3, FrameDirection.TX, CommandType.SET_PARAM, timestamp = 300),
                entry(
                    3,
                    FrameDirection.RX,
                    CommandType.SET_PARAM,
                    isResponse = true,
                    timestamp = 380,
                    error = "0x03 参数越界",
                ),
            )
        val stats = ConnectionQualityAnalyzer.analyze(entries)

        assertEquals(2, stats.commands.size)
        val config = stats.commands.first { it.command == CommandType.GET_CONFIG }
        assertEquals(2, config.requests)
        assertEquals(2, config.successes)
        assertEquals(0, config.deviceErrors)
        assertEquals(60.0, config.averageLatencyMillis)
        val setParam = stats.commands.first { it.command == CommandType.SET_PARAM }
        assertEquals(1, setParam.requests)
        assertEquals(0, setParam.successes)
        assertEquals(1, setParam.deviceErrors)
    }

    @Test
    fun `样本达到阈值后不再标记样本不足`() {
        val entries =
            (1..20).flatMap { index ->
                listOf(
                    entry(index, FrameDirection.TX, timestamp = index * 100L),
                    entry(index, FrameDirection.RX, isResponse = true, timestamp = index * 100L + 50),
                )
            }
        val stats = ConnectionQualityAnalyzer.analyze(entries)

        assertEquals(20, stats.requests)
        assertFalse(stats.insufficientSample)
        assertEquals(1.0, stats.successRate)
    }
}
