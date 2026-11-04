package com.valhalla.core.security

/** AUTH 挑战应答签名器。 */
interface AuthSigner {

    /** 算法标识，用于日志与协议协商（当前仅有 HMAC-SHA256）。 */
    val algorithm: String

    fun sign(message: ByteArray): ByteArray
}

/** 基于预共享密钥的 HMAC-SHA256 签名器。 */
class HmacAuthSigner(private val secret: ByteArray) : AuthSigner {

    init {
        require(secret.isNotEmpty()) { "AUTH 密钥不能为空" }
    }

    override val algorithm: String = ALGORITHM

    override fun sign(message: ByteArray): ByteArray = HmacSha256.compute(secret, message)

    companion object {
        const val ALGORITHM: String = "HMAC-SHA256"
    }
}

/**
 * 挑战应答签名的规范化消息。
 *
 * 参与签名的三个要素（缺一不可）：
 * 1. `challenge`：设备下发的随机挑战；
 * 2. `credential`：本次会话的客户端随机数（clientNonce）；
 * 3. `deviceDigest`：设备标识摘要（型号/序列号等派生），防止签名被跨设备重放。
 *
 * 编码格式（小端序，带长度前缀以避免拼接歧义）：
 * `version:u8(=1) | challengeLen:u16 | challenge | credentialLen:u16 | credential | digestLen:u16 | digest(UTF-8)`
 */
object AuthSignatureMessage {

    const val VERSION: Byte = 0x01

    fun canonical(challenge: ByteArray, credential: ByteArray, deviceDigest: String): ByteArray {
        val digestBytes = deviceDigest.encodeToByteArray()
        val size = 1 + 2 + challenge.size + 2 + credential.size + 2 + digestBytes.size
        val out = ByteArray(size)
        var index = 0
        out[index++] = VERSION
        index = writeU16(out, index, challenge.size)
        challenge.copyInto(out, index)
        index += challenge.size
        index = writeU16(out, index, credential.size)
        credential.copyInto(out, index)
        index += credential.size
        index = writeU16(out, index, digestBytes.size)
        digestBytes.copyInto(out, index)
        return out
    }

    private fun writeU16(target: ByteArray, offset: Int, value: Int): Int {
        require(value in 0..0xFFFF) { "长度字段超出 u16 范围: $value" }
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        return offset + 2
    }
}
