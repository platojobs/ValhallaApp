package com.valhalla.core.protocol

/**
 * CRC-16/CCITT-FALSE：poly = 0x1021，init = 0xFFFF，不反转输入输出，无最终异或。
 */
object Crc16 {

    private const val POLYNOMIAL = 0x1021

    fun ccittFalse(data: ByteArray, startIndex: Int = 0, endIndex: Int = data.size): Int {
        require(startIndex in 0..data.size) { "startIndex 越界: $startIndex" }
        require(endIndex in startIndex..data.size) { "endIndex 越界: $endIndex" }
        var crc = 0xFFFF
        for (index in startIndex until endIndex) {
            crc = step(crc, data[index].toInt() and 0xFF)
        }
        return crc
    }

    /**
     * 增量计算：用于流式读取固件 payload，避免整包驻留内存。
     * 首次调用传入 [INITIAL]（或直接使用 [ccittFalse] 的起点 0xFFFF）。
     */
    fun update(current: Int, data: ByteArray, startIndex: Int = 0, endIndex: Int = data.size): Int {
        require(current in 0..0xFFFF) { "current 超出范围: $current" }
        require(startIndex in 0..data.size) { "startIndex 越界: $startIndex" }
        require(endIndex in startIndex..data.size) { "endIndex 越界: $endIndex" }
        var crc = current
        for (index in startIndex until endIndex) {
            crc = step(crc, data[index].toInt() and 0xFF)
        }
        return crc
    }

    /** 增量计算的初始值（等价于 [ccittFalse] 的内部初值）。 */
    const val INITIAL: Int = 0xFFFF

    private fun step(current: Int, value: Int): Int {
        var crc = current xor (value shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) {
                (crc shl 1) xor POLYNOMIAL
            } else {
                crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }
}
