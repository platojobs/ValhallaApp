package com.valhalla.core.firmware

/** CRC-32/IEEE（多项式 0xEDB88320，初值与结果异或 0xFFFFFFFF），输出按无符号 32 位存放在 Long 中。 */
object Crc32 {
    private const val POLYNOMIAL = 0xEDB88320L

    fun compute(
        data: ByteArray,
        startIndex: Int = 0,
        endIndex: Int = data.size,
    ): Long {
        require(startIndex in 0..data.size) { "startIndex 越界: $startIndex" }
        require(endIndex in startIndex..data.size) { "endIndex 越界: $endIndex" }
        var crc = 0xFFFFFFFFL
        for (index in startIndex until endIndex) {
            crc = crc xor (data[index].toLong() and 0xFF)
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc shr 1) xor POLYNOMIAL else crc shr 1
            }
        }
        return crc xor 0xFFFFFFFFL
    }

    /** 增量计算：传入上一次 [compute] 或 [update] 的结果。 */
    fun update(
        current: Long,
        data: ByteArray,
        startIndex: Int = 0,
        endIndex: Int = data.size,
    ): Long {
        require(startIndex in 0..data.size) { "startIndex 越界: $startIndex" }
        require(endIndex in startIndex..data.size) { "endIndex 越界: $endIndex" }
        var crc = current xor 0xFFFFFFFFL
        for (index in startIndex until endIndex) {
            crc = crc xor (data[index].toLong() and 0xFF)
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc shr 1) xor POLYNOMIAL else crc shr 1
            }
        }
        return crc xor 0xFFFFFFFFL
    }
}
