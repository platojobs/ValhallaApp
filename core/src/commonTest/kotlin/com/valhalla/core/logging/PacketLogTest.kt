package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.SetConfigRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PacketLogTest {
    private fun txFrame(
        command: CommandType,
        payload: ByteArray,
        sequence: Int = 0,
    ): Frame = Frame(type = command, sequence = sequence, payload = payload)

    private fun responseFrame(
        command: CommandType,
        payload: ByteArray,
        sequence: Int = 0,
    ): Frame = Frame(type = command, sequence = sequence, payload = payload, isResponse = true)

    @Test
    fun `环形缓冲只保留最新容量并分配自增 id`() {
        val log = InMemoryPacketLog(capacity = 3, clockMillis = { 100L })
        repeat(5) { index ->
            log.record(
                FrameDirection.TX,
                txFrame(CommandType.GET_CONFIG, GetConfigRequest("key$index").encode(), index),
            )
        }

        assertEquals(3, log.entries.value.size)
        assertEquals(listOf(2L, 3L, 4L), log.entries.value.map { it.id })
        assertEquals(
            "key4",
            log.entries.value
                .last()
                .summary
                .substringAfter("key="),
        )
    }

    @Test
    fun `摘要包含方向命令名与 HEX`() {
        val log = InMemoryPacketLog(clockMillis = { 0L })
        val entry =
            log.record(
                FrameDirection.TX,
                txFrame(CommandType.SET_CONFIG, SetConfigRequest("device_name", byteArrayOf(1, 2)).encode()),
            )

        assertTrue(entry.summary.contains("SET_CONFIG"))
        assertTrue(entry.summary.contains("device_name"))
        assertTrue(entry.hex.startsWith("A55A"))
        assertTrue(entry.formatForCopy().contains("TX"))
        assertTrue(entry.formatForCopy().contains("SET_CONFIG"))
        assertEquals(null, entry.error)
    }

    @Test
    fun `设备错误应答标记为错误并可按错误过滤`() {
        val log = InMemoryPacketLog(clockMillis = { 0L })
        log.record(
            FrameDirection.TX,
            txFrame(CommandType.GET_CONFIG, GetConfigRequest("led_enabled").encode(), 1),
        )
        log.record(
            FrameDirection.RX,
            responseFrame(
                CommandType.GET_CONFIG,
                ErrorResponse(CommandType.GET_CONFIG, DeviceStatus.INVALID_PARAMETER, "参数非法").encode(),
                1,
            ),
        )
        log.record(
            FrameDirection.RX,
            responseFrame(CommandType.GET_CONFIG, ConfigResponse(DeviceStatus.OK, "led_enabled", byteArrayOf(1)).encode(), 2),
        )

        val errors = log.filter(PacketLogFilter(errorsOnly = true))
        assertEquals(1, errors.size)
        assertNotNull(errors.single().error)
        assertTrue(errors.single().summary.contains("0x03"))
        assertEquals(FrameDirection.RX, errors.single().direction)
    }

    @Test
    fun `按命令与关键字过滤且结果按时间倒序`() {
        val log = InMemoryPacketLog(clockMillis = { 0L })
        log.record(FrameDirection.TX, txFrame(CommandType.GET_CONFIG, GetConfigRequest("alpha").encode(), 11))
        log.record(FrameDirection.TX, txFrame(CommandType.SET_CONFIG, SetConfigRequest("beta", byteArrayOf(1)).encode(), 22))
        log.record(FrameDirection.RX, responseFrame(CommandType.SET_CONFIG, setConfigResponseOk().encode(), 777))

        val filtered = log.filter(PacketLogFilter(command = CommandType.SET_CONFIG))
        assertEquals(2, filtered.size)
        assertEquals(listOf(777, 22), filtered.map { it.sequence })

        val keyword = log.filter(PacketLogFilter(keyword = "beta"))
        assertEquals(1, keyword.size)
        assertTrue(keyword.single().summary.contains("beta"))

        val sequence = log.filter(PacketLogFilter(keyword = "777"))
        assertEquals(1, sequence.size, "关键字应能匹配 seq")
        assertEquals(777, sequence.single().sequence)
    }

    @Test
    fun `解析失败的请求保留原始报文并记录错误`() {
        val log = InMemoryPacketLog(clockMillis = { 0L })
        val entry =
            log.record(
                FrameDirection.TX,
                txFrame(CommandType.UNKNOWN, byteArrayOf(1, 2, 3)),
            )

        assertNotNull(entry.error)
        assertTrue(entry.hex.startsWith("A55A"))
        assertTrue(entry.summary.contains("UNKNOWN"))
    }

    @Test
    fun `OTA 分片与进度通知可被解析`() {
        val log = InMemoryPacketLog(clockMillis = { 0L })
        val data =
            log.record(
                FrameDirection.RX,
                responseFrame(
                    CommandType.OTA_DATA,
                    OtaDataResponse(DeviceStatus.OK, receivedBytes = 128).encode(),
                    3,
                ),
            )
        assertTrue(data.summary.contains("received=128"))

        val progress =
            log.record(
                FrameDirection.RX,
                Frame(
                    type = CommandType.OTA_PROGRESS_NOTIFY,
                    sequence = 0,
                    payload = byteArrayOf(0, 0, 0, 42, 64, 0, 0, 0, 100, 0, 0, 0, 1),
                    isNotification = true,
                ),
            )
        assertTrue(progress.summary.contains("OTA_PROGRESS"))
        assertEquals(null, progress.error)
    }

    @Test
    fun `清空日志后快照为空`() {
        val log = InMemoryPacketLog(clockMillis = { 0L })
        log.record(FrameDirection.TX, txFrame(CommandType.GET_DEVICE_INFO, ByteArray(0)))
        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }

    private fun setConfigResponseOk() =
        com.valhalla.core.protocol.message
            .SetConfigResponse(DeviceStatus.OK)
}
