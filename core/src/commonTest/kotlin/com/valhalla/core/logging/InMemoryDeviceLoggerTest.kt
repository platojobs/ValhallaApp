package com.valhalla.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryDeviceLoggerTest {
    @Test
    fun `日志按写入顺序排列并分配自增 id`() {
        val logger = InMemoryDeviceLogger(capacity = 10)
        logger.log(LogLevel.INFO, "scan", "开始扫描")
        logger.log(LogLevel.WARN, "ble", "信号较弱", timestampMillis = 100L)

        val entries = logger.entries()
        assertEquals(2, entries.size)
        assertEquals(0L, entries[0].id)
        assertEquals(1L, entries[1].id)
        assertEquals(100L, entries[1].timestampMillis)
        assertEquals("[W] ble - 信号较弱", entries[1].format())
    }

    @Test
    fun `容量到达上限后仅保留最新日志`() {
        val logger = InMemoryDeviceLogger(capacity = 3)
        repeat(5) { index -> logger.log(LogLevel.DEBUG, "ota", "分片 $index") }

        val entries = logger.entries()
        assertEquals(3, entries.size)
        assertEquals(listOf(2L, 3L, 4L), entries.map { it.id })
        assertTrue(entries.last().message.endsWith("4"))
    }

    @Test
    fun `clear 清空快照`() {
        val logger = InMemoryDeviceLogger()
        logger.log(LogLevel.ERROR, "gatt", "断开连接")
        logger.clear()
        assertTrue(logger.entries().isEmpty())
    }

    @Test
    fun `容量必须为正数`() {
        assertFailsWithIllegalArgument { InMemoryDeviceLogger(capacity = 0) }
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("应抛出 IllegalArgumentException")
    }
}
