package com.valhalla.core.protocol

/**
 * 一帧完整报文。
 *
 * @param type 指令类型
 * @param sequence 会话内序号，0..65535 循环
 * @param payload 业务负载
 * @param isResponse 是否为设备回复帧
 * @param version 协议版本
 */
class Frame(
    val type: CommandType,
    val sequence: Int,
    val payload: ByteArray = EMPTY_PAYLOAD,
    val isResponse: Boolean = false,
    val version: Byte = FrameCodec.PROTOCOL_VERSION,
) {

    init {
        require(sequence in 0..MAX_SEQUENCE) { "sequence 超出范围: $sequence" }
        require(payload.size <= FrameCodec.MAX_PAYLOAD_SIZE) {
            "payload 超长: ${payload.size} > ${FrameCodec.MAX_PAYLOAD_SIZE}"
        }
        require(version == FrameCodec.PROTOCOL_VERSION) { "不支持的协议版本: $version" }
    }

    fun payloadCopy(): ByteArray = payload.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type &&
            sequence == other.sequence &&
            isResponse == other.isResponse &&
            version == other.version &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + sequence
        result = 31 * result + isResponse.hashCode()
        result = 31 * result + version.toInt()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Frame(type=$type, seq=$sequence, response=$isResponse, payload=${Hex.encode(payload, separator = " ")})"

    companion object {
        const val MAX_SEQUENCE = 0xFFFF
        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
