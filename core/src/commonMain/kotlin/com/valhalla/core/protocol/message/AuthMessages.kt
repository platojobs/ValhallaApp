package com.valhalla.core.protocol.message

import com.valhalla.core.protocol.CommandType

/** 鉴权模式。 */
enum class AuthMode(val code: Int) {
    /** 直接使用长期令牌鉴权。 */
    TOKEN(0x01),

    /** 挑战-应答：请求携带客户端随机数，应答返回服务端挑战与令牌。 */
    CHALLENGE(0x02),

    /**
     * 签名挑战应答：credential 为 [SignedAuthCredential] 编码后的字节，
     * 设备用预共享密钥验签后返回令牌。
     */
    SIGNED(0x03),
    ;

    companion object {
        fun fromCode(code: Int): AuthMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 鉴权请求。
 *
 * payload：`mode:u8 | credentialLen:u16 | credential`
 * - [AuthMode.TOKEN]：credential 为令牌字节；
 * - [AuthMode.CHALLENGE]：credential 为客户端随机数（建议 16 字节）；
 * - [AuthMode.SIGNED]：credential 为 [SignedAuthCredential] 编码后的字节。
 */
class AuthRequest(
    val mode: AuthMode,
    val credential: ByteArray,
) : DeviceRequest() {

    override val command: CommandType = CommandType.AUTH

    override fun encodeBody(writer: PayloadWriter) {
        writer.byte(mode.code)
        writer.u16(credential.size)
        writer.bytes(credential)
    }
}

/**
 * 鉴权成功应答。
 *
 * payload（状态码之后）：`tokenLen:u16 | token | expiresIn:u32 | challengeLen:u16 | challenge`
 * - token 为后续指令使用的会话令牌；
 * - expiresIn 为令牌有效期（秒）；
 * - challenge 仅在挑战模式下返回，可为空，调用方应保存并用于下一轮挑战应答。
 */
class AuthResponse(
    status: DeviceStatus,
    val token: String,
    val expiresInSeconds: Long,
    val challenge: ByteArray,
) : DeviceResponse() {

    override val command: CommandType = CommandType.AUTH

    override val status: DeviceStatus = status

    val hasChallenge: Boolean get() = challenge.isNotEmpty()

    override fun encodeBody(writer: PayloadWriter) {
        writer.string2(token)
        writer.u32(expiresInSeconds)
        writer.u16(challenge.size)
        writer.bytes(challenge)
    }
}

/**
 * 签名挑战应答凭据（`AuthMode.SIGNED` 的 credential 内容）。
 *
 * 编码格式：`challengeLen:u16 | challenge | clientNonceLen:u16 | clientNonce |
 * deviceDigestLen:u16 | deviceDigest(UTF-8) | signatureLen:u16 | signature`
 *
 * 签名消息由 `AuthSignatureMessage.canonical(challenge, clientNonce, deviceDigest)` 生成，
 * 算法为 HMAC-SHA256（预共享密钥），见 `docs/protocol.md`。
 */
class SignedAuthCredential(
    val challenge: ByteArray,
    val clientNonce: ByteArray,
    val deviceDigest: String,
    val signature: ByteArray,
) {

    init {
        require(clientNonce.isNotEmpty()) { "clientNonce 不能为空" }
        require(signature.isNotEmpty()) { "签名不能为空" }
    }

    fun encode(): ByteArray = PayloadWriter()
        .u16(challenge.size)
        .bytes(challenge)
        .u16(clientNonce.size)
        .bytes(clientNonce)
        .string2(deviceDigest)
        .u16(signature.size)
        .bytes(signature)
        .toByteArray()

    companion object {

        fun decode(bytes: ByteArray): SignedAuthCredential {
            val reader = PayloadReader(bytes)
            val credential = SignedAuthCredential(
                challenge = reader.bytes(reader.u16()),
                clientNonce = reader.bytes(reader.u16()),
                deviceDigest = reader.string2(),
                signature = reader.bytes(reader.u16()),
            )
            reader.ensureExhausted()
            return credential
        }
    }
}
