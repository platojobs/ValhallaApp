package com.valhalla.core.security

/**
 * 纯 Kotlin HMAC-SHA256（RFC 2104），与 SHA-256 一起用于 AUTH 挑战应答签名和
 * 测试密钥固件验签。
 *
 * 只提供一次性计算与常量时间比较；密钥长度任意（超过块大小时先做 SHA-256）。
 */
object HmacSha256 {

    private const val BLOCK_SIZE = 64
    private const val IPAD = 0x36
    private const val OPAD = 0x5C

    fun compute(key: ByteArray, message: ByteArray): ByteArray {
        val normalizedKey = if (key.size > BLOCK_SIZE) Sha256.compute(key) else key
        val innerPad = ByteArray(BLOCK_SIZE) { index ->
            val value = if (index < normalizedKey.size) normalizedKey[index].toInt() xor IPAD else IPAD
            value.toByte()
        }
        val outerPad = ByteArray(BLOCK_SIZE) { index ->
            val value = if (index < normalizedKey.size) normalizedKey[index].toInt() xor OPAD else OPAD
            value.toByte()
        }

        val innerDigest = Sha256Digest()
            .update(innerPad)
            .update(message)
            .digest()
        return Sha256Digest()
            .update(outerPad)
            .update(innerDigest)
            .digest()
    }

    /** 常量时间比较，避免按字节提前返回带来的时序侧信道。 */
    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var difference = 0
        for (index in left.indices) {
            difference = difference or (left[index].toInt() xor right[index].toInt())
        }
        return difference == 0
    }

    fun verify(key: ByteArray, message: ByteArray, expected: ByteArray): Boolean =
        constantTimeEquals(compute(key, message), expected)
}
