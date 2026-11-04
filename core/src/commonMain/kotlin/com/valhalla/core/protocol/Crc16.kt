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
            crc = crc xor ((data[index].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor POLYNOMIAL
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
