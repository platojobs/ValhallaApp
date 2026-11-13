package com.valhalla.core.protocol

private const val UINT8_MAX = 0xFF
private const val SEQUENCE_OFFSET = 5
private const val LENGTH_OFFSET = 7

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
 * flags.bit0 表示响应帧，flags.bit1 表示通知帧（互斥）；CRC 覆盖 version 到 payload 结束。
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
    private const val FLAG_NOTIFICATION = 0x02

    private fun writeUInt16(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = (value and UINT8_MAX).toByte()
        target[offset + 1] = ((value shr 8) and UINT8_MAX).toByte()
    }

    internal fun readUInt16(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and UINT8_MAX) or ((source[offset + 1].toInt() and UINT8_MAX) shl 8)

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
        var flags = 0
        if (frame.isResponse) flags = flags or FLAG_RESPONSE
        if (frame.isNotification) flags = flags or FLAG_NOTIFICATION
        output[4] = flags.toByte()
        writeUInt16(output, SEQUENCE_OFFSET, frame.sequence)
        writeUInt16(output, LENGTH_OFFSET, payload.size)
        payload.copyInto(output, destinationOffset = HEADER_SIZE)

        val crc = Crc16.ccittFalse(output, startIndex = 2, endIndex = HEADER_SIZE + payload.size)
        writeUInt16(output, HEADER_SIZE + payload.size, crc)
        return output
    }

    fun decode(bytes: ByteArray): Frame {
        validateFrame(bytes)?.let { reason -> throw FrameDecodeException(reason) }

        val flags = bytes[4].toInt() and UINT8_MAX
        val payloadLength = readUInt16(bytes, LENGTH_OFFSET)
        return Frame(
            type = CommandType.fromCode(bytes[3]),
            sequence = readUInt16(bytes, SEQUENCE_OFFSET),
            payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength),
            isResponse = flags and FLAG_RESPONSE != 0,
            isNotification = flags and FLAG_NOTIFICATION != 0,
            version = bytes[2],
        )
    }

    /**
     * 帧结构校验：返回第一条不满足的规则说明，`null` 表示可以安全解析。
     *
     * 校验与解析分开后，[decode] 只保留一个抛错点，读起来是一条直线；
     * 校验规则本身是纯函数，不依赖解析结果。
     */
    private fun validateFrame(bytes: ByteArray): String? {
        if (bytes.size < MIN_FRAME_SIZE) {
            return "帧长度不足: ${bytes.size} < $MIN_FRAME_SIZE"
        }
        if (bytes[0] != SOF_FIRST || bytes[1] != SOF_SECOND) {
            return "SOF 不匹配: ${Hex.encode(bytes.copyOfRange(0, 2))}"
        }
        if (bytes[2] != PROTOCOL_VERSION) {
            return "不支持的协议版本: ${bytes[2]}"
        }
        val flags = bytes[4].toInt() and UINT8_MAX
        if (flags and FLAG_RESPONSE != 0 && flags and FLAG_NOTIFICATION != 0) {
            return "flags 同时标记应答与通知: 0x${flags.toString(16)}"
        }
        val payloadLength = readUInt16(bytes, LENGTH_OFFSET)
        if (payloadLength > MAX_PAYLOAD_SIZE) {
            return "payload 长度非法: $payloadLength > $MAX_PAYLOAD_SIZE"
        }
        val expectedSize = HEADER_SIZE + payloadLength + CRC_SIZE
        if (bytes.size != expectedSize) {
            return "帧长度不一致: 实际 ${bytes.size}，期望 $expectedSize"
        }
        val actualCrc = readUInt16(bytes, HEADER_SIZE + payloadLength)
        val expectedCrc = Crc16.ccittFalse(bytes, startIndex = 2, endIndex = HEADER_SIZE + payloadLength)
        if (actualCrc != expectedCrc) {
            return "CRC 校验失败: 报文 0x${actualCrc.toString(16)}，计算 0x${expectedCrc.toString(16)}"
        }
        return null
    }
}
