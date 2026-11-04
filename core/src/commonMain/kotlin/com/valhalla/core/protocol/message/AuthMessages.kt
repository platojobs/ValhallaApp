package com.valhalla.core.protocol.message

import com.valhalla.core.protocol.CommandType

/** 鉴权模式。 */
enum class AuthMode(val code: Int) {
    /** 直接使用长期令牌鉴权。 */
    TOKEN(0x01),

    /** 挑战-应答：请求携带客户端随机数，应答返回服务端挑战与令牌。 */
    CHALLENGE(0x02),
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
 * - [AuthMode.CHALLENGE]：credential 为客户端随机数（建议 16 字节）。
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
