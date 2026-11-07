package com.valhalla.core.protocol

/**
 * 一帧完整报文。
 *
 * 帧的种类由 flags 字节区分：
 * - 普通请求：flags = 0x00
 * - 应答：[isResponse] = true，flags bit0 = 1
 * - 通知：[isNotification] = true，flags bit1 = 1（设备主动上报，不参与请求-应答配对）
 *
 * @param type 指令类型
 * @param sequence 会话内序号，0..65535 循环
 * @param payload 业务负载
 * @param isResponse 是否为设备回复帧
 * @param isNotification 是否为设备主动通知帧
 * @param version 协议版本
 */
class Frame(
    val type: CommandType,
    val sequence: Int,
    val payload: ByteArray = EMPTY_PAYLOAD,
    val isResponse: Boolean = false,
    val isNotification: Boolean = false,
    val version: Byte = FrameCodec.PROTOCOL_VERSION,
) {
    init {
        require(sequence in 0..MAX_SEQUENCE) { "sequence 超出范围: $sequence" }
        require(payload.size <= FrameCodec.MAX_PAYLOAD_SIZE) {
            "payload 超长: ${payload.size} > ${FrameCodec.MAX_PAYLOAD_SIZE}"
        }
        require(version == FrameCodec.PROTOCOL_VERSION) { "不支持的协议版本: $version" }
        require(!(isResponse && isNotification)) { "应答帧与通知帧标记互斥" }
    }

    fun payloadCopy(): ByteArray = payload.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type &&
            sequence == other.sequence &&
            isResponse == other.isResponse &&
            isNotification == other.isNotification &&
            version == other.version &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + sequence
        result = 31 * result + isResponse.hashCode()
        result = 31 * result + isNotification.hashCode()
        result = 31 * result + version.toInt()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Frame(type=$type, seq=$sequence, response=$isResponse, notification=$isNotification, " +
            "payload=${Hex.encode(payload, separator = " ")})"

    companion object {
        const val MAX_SEQUENCE = 0xFFFF
        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
