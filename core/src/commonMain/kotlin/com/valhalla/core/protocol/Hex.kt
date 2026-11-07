package com.valhalla.core.protocol

/** 十六进制编解码工具，用于日志与报文调试。 */
object Hex {
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun encode(
        bytes: ByteArray,
        upperCase: Boolean = true,
        separator: String = "",
    ): String {
        if (bytes.isEmpty()) return ""
        val digits = if (upperCase) HEX_DIGITS else HEX_DIGITS.lowercase()
        return buildString(bytes.size * 3) {
            bytes.forEachIndexed { index, byte ->
                if (index > 0 && separator.isNotEmpty()) append(separator)
                val value = byte.toInt() and 0xFF
                append(digits[value shr 4])
                append(digits[value and 0x0F])
            }
        }
    }

    fun decode(text: String): ByteArray {
        val normalized = text.filter { !it.isWhitespace() && it != ':' && it != '-' && it != '_' }
        require(normalized.length % 2 == 0) { "十六进制字符串长度必须为偶数: $text" }
        return ByteArray(normalized.length / 2) { index ->
            val high =
                normalized[index * 2].digitToIntOrNull(16)
                    ?: throw IllegalArgumentException("非法十六进制字符: ${normalized[index * 2]}")
            val low =
                normalized[index * 2 + 1].digitToIntOrNull(16)
                    ?: throw IllegalArgumentException("非法十六进制字符: ${normalized[index * 2 + 1]}")
            ((high shl 4) or low).toByte()
        }
    }
}
