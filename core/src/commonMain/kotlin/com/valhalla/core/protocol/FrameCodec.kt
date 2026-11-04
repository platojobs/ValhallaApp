package com.valhalla.core.protocol

/**
 * 报文编解码器。
 *
 * 帧结构：
 * ```
 * +------+---------+------+-------+------+--------+---------+-------+
 * | SOF  | version | type | flags | seq  | length | payload | crc16 |
 * | 2B   | 1B      | 1B   | 1B    | 2BLE | 2BLE   | 0..N    | 2B    |
 * +------+---------+------+-------+------+--------+---------+-------+
 * ```
 * flags.bit0 表示响应帧；CRC 覆盖 version 到 payload 结束。
 */
object FrameCodec {

    const val PROTOCOL_VERSION: Byte = 1

    val SOF_FIRST: Byte = 0xA5.toByte()
    val SOF_SECOND: Byte = 0x5A.toByte()

    const val HEADER_SIZE = 9
    const val CRC_SIZE = 2
    const val MAX_PAYLOAD_SIZE = 1024
    const val MIN_FRAME_SIZE = HEADER_SIZE + CRC_SIZE

    private const val FLAG_RESPONSE = 0x01

    private fun writeUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    internal fun readUInt16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    fun encode(frame: Frame): ByteArray {
        val payload = frame.payload
        if (payload.size > MAX_PAYLOAD_SIZE) {
            throw FrameEncodeException("payload 超长: ${payload.size} > $MAX_PAYLOAD_SIZE")
        }

        val total = HEADER_SIZE + payload.size + CRC_SIZE
        val output = ByteArray(total)
        output[0] = SOF_FIRST
        output[1] = SOF_SECOND
        output[2] = frame.version
        output[3] = frame.type.code
        output[4] = if (frame.isResponse) FLAG_RESPONSE.toByte() else 0
        writeUInt16(output, 5, frame.sequence)
        writeUInt16(output, 7, payload.size)
        payload.copyInto(output, destinationOffset = HEADER_SIZE)

        val crc = Crc16.ccittFalse(output, startIndex = 2, endIndex = HEADER_SIZE + payload.size)
        writeUInt16(output, HEADER_SIZE + payload.size, crc)
        return output
    }

    fun decode(bytes: ByteArray): Frame {
        if (bytes.size < MIN_FRAME_SIZE) {
            throw FrameDecodeException("帧长度不足: ${bytes.size} < $MIN_FRAME_SIZE")
        }
        if (bytes[0] != SOF_FIRST || bytes[1] != SOF_SECOND) {
            throw FrameDecodeException("SOF 不匹配: ${Hex.encode(bytes.copyOfRange(0, 2))}")
        }

        val version = bytes[2]
        if (version != PROTOCOL_VERSION) {
            throw FrameDecodeException("不支持的协议版本: $version")
        }

        val type = CommandType.fromCode(bytes[3])
        val flags = bytes[4].toInt() and 0xFF
        val sequence = readUInt16(bytes, 5)
        val payloadLength = readUInt16(bytes, 7)

        if (payloadLength > MAX_PAYLOAD_SIZE) {
            throw FrameDecodeException("payload 长度非法: $payloadLength > $MAX_PAYLOAD_SIZE")
        }

        val expectedSize = HEADER_SIZE + payloadLength + CRC_SIZE
        if (bytes.size != expectedSize) {
            throw FrameDecodeException("帧长度不一致: 实际 ${bytes.size}，期望 $expectedSize")
        }

        val actualCrc = readUInt16(bytes, HEADER_SIZE + payloadLength)
        val expectedCrc = Crc16.ccittFalse(bytes, startIndex = 2, endIndex = HEADER_SIZE + payloadLength)
        if (actualCrc != expectedCrc) {
            throw FrameDecodeException(
                "CRC 校验失败: 报文 0x${actualCrc.toString(16)}，计算 0x${expectedCrc.toString(16)}"
            )
        }

        return Frame(
            type = type,
            sequence = sequence,
            payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength),
            isResponse = flags and FLAG_RESPONSE != 0,
            version = version,
        )
    }
}
