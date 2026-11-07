package com.valhalla.core.firmware

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {
    @Test
    fun `标准测试向量与分段计算结果一致`() {
        val data = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926L, Crc32.compute(data))

        var incremental = 0L
        incremental = Crc32.update(incremental, data, 0, 3)
        incremental = Crc32.update(incremental, data, 3, 6)
        incremental = Crc32.update(incremental, data, 6, 9)
        assertEquals(0xCBF43926L, incremental)
    }

    @Test
    fun `空数据 CRC 为零`() {
        assertEquals(0L, Crc32.compute(ByteArray(0)))
        assertEquals(0L, Crc32.update(0L, ByteArray(0)))
    }

    @Test
    fun `空区间不改变当前值`() {
        val data = "abc".encodeToByteArray()
        val crc = Crc32.compute(data)
        assertEquals(crc, Crc32.update(crc, data, data.size, data.size))
    }
}
