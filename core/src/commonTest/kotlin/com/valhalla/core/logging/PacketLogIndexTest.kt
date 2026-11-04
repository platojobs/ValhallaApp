package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import kotlin.test.Test
import kotlin.test.assertEquals

class PacketLogIndexTest {

    private fun entry(
        id: Long,
        command: CommandType,
        sequence: Int,
        summary: String,
        hex: String = "A55A0001",
        error: String? = null,
        isResponse: Boolean = false,
    ) = PacketLogEntry(
        id = id,
        timestampMillis = id * 1_000,
        direction = if (isResponse) FrameDirection.RX else FrameDirection.TX,
        command = command,
        sequence = sequence,
        isResponse = isResponse,
        isNotification = false,
        hex = hex,
        summary = summary,
        error = error,
    )

    private val entries = listOf(
        entry(1, CommandType.GET_CONFIG, 11, "GET_CONFIG key=Alpha"),
        entry(2, CommandType.SET_CONFIG, 22, "SET_CONFIG key=beta", isResponse = false),
        entry(3, CommandType.SET_CONFIG, 777, "SET_CONFIG ok", isResponse = true),
        entry(4, CommandType.OTA_DATA, 33, "OTA_DATA received=128", error = "CRC_MISMATCH"),
    )

    @Test
    fun `索引查询与线性过滤结果完全一致`() {
        val index = PacketLogIndex(entries)
        val filters = listOf(
            PacketLogFilter(),
            PacketLogFilter(keyword = "BETA"),
            PacketLogFilter(keyword = "777"),
            PacketLogFilter(command = CommandType.SET_CONFIG),
            PacketLogFilter(errorsOnly = true),
            PacketLogFilter(command = CommandType.SET_CONFIG, keyword = "ok"),
            PacketLogFilter(keyword = "不存在的关键字"),
        )

        filters.forEach { filter ->
            val expected = filterPacketEntries(entries, filter, limit = InMemoryPacketLog.DEFAULT_CAPACITY)
            assertEquals(expected.map { it.id }, index.query(filter).map { it.id }, "过滤条件：$filter")
        }
    }

    @Test
    fun `索引按时间倒序并支持数量上限`() {
        val index = PacketLogIndex(entries)

        assertEquals(listOf(4L, 3L, 2L, 1L), index.query(PacketLogFilter()).map { it.id })
        assertEquals(listOf(4L, 3L), index.query(PacketLogFilter(), limit = 2).map { it.id })
        assertEquals(emptyList(), index.query(PacketLogFilter(), limit = 0))
    }

    @Test
    fun `关键字忽略大小写并覆盖摘要与错误字段`() {
        val index = PacketLogIndex(entries)

        assertEquals(listOf(2L), index.query(PacketLogFilter(keyword = " beta ")).map { it.id })
        assertEquals(listOf(4L), index.query(PacketLogFilter(keyword = "crc_mismatch")).map { it.id })
        assertEquals(listOf(1L), index.query(PacketLogFilter(keyword = "alpha")).map { it.id })
    }

    @Test
    fun `空快照查询返回空列表`() {
        val index = PacketLogIndex(emptyList())
        assertEquals(0, index.size)
        assertEquals(emptyList(), index.query(PacketLogFilter(keyword = "x")))
    }
}
