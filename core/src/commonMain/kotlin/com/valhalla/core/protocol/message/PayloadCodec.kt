package com.valhalla.core.protocol.message

import com.valhalla.core.protocol.FrameException

/** payload 结构非法，例如字段截断、长度越界或尾部存在未解析字节。 */
class MessageDecodeException(
    message: String,
) : FrameException(message)

/**
 * 小端序 payload 写入器。
 *
 * 字符串统一按 UTF-8 编码，长度前缀由调用方选择 1 字节或 2 字节。
 */
class PayloadWriter {
    private val values = mutableListOf<Byte>()

    fun byte(value: Int): PayloadWriter {
        require(value in 0..0xFF) { "byte 超出范围: $value" }
        values += value.toByte()
        return this
    }

    fun u16(value: Int): PayloadWriter {
        require(value in 0..0xFFFF) { "u16 超出范围: $value" }
        byte(value and 0xFF)
        byte((value shr 8) and 0xFF)
        return this
    }

    fun u32(value: Long): PayloadWriter {
        require(value in 0..0xFFFFFFFFL) { "u32 超出范围: $value" }
        u16((value and 0xFFFF).toInt())
        u16(((value shr 16) and 0xFFFF).toInt())
        return this
    }

    fun i16(value: Int): PayloadWriter {
        require(value in -0x8000..0x7FFF) { "i16 超出范围: $value" }
        u16(value and 0xFFFF)
        return this
    }

    fun bytes(value: ByteArray): PayloadWriter {
        values += value.toList()
        return this
    }

    fun string1(value: String): PayloadWriter {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= 0xFF) { "字符串超过 1 字节长度上限: ${encoded.size}" }
        byte(encoded.size)
        bytes(encoded)
        return this
    }

    fun string2(value: String): PayloadWriter {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= 0xFFFF) { "字符串超过 2 字节长度上限: ${encoded.size}" }
        u16(encoded.size)
        bytes(encoded)
        return this
    }

    fun toByteArray(): ByteArray = values.toByteArray()
}

/**
 * 小端序 payload 读取器。所有越界访问都会抛出 [MessageDecodeException]，
 * 便于调度层把「设备回了畸形 payload」与「超时」区分开。
 */
class PayloadReader(
    private val data: ByteArray,
) {
    private var index: Int = 0

    val remaining: Int get() = data.size - index

    fun byte(): Int {
        ensureAvailable(1, "1 字节")
        return data[index++].toInt() and 0xFF
    }

    fun u16(): Int {
        ensureAvailable(2, "u16")
        val low = data[index++].toInt() and 0xFF
        val high = data[index++].toInt() and 0xFF
        return low or (high shl 8)
    }

    fun u32(): Long {
        ensureAvailable(4, "u32")
        val low = u16().toLong()
        val high = u16().toLong()
        return low or (high shl 16)
    }

    fun i16(): Int {
        val raw = u16()
        return if (raw and 0x8000 != 0) raw - 0x1_0000 else raw
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0) throw MessageDecodeException("读取长度非法: $count")
        ensureAvailable(count, "$count 字节数据")
        val result = data.copyOfRange(index, index + count)
        index += count
        return result
    }

    fun string1(): String {
        val length = byte()
        ensureAvailable(length, "长度前缀 1 字节的字符串（长度 $length）")
        return bytes(length).decodeToString()
    }

    fun string2(): String {
        val length = u16()
        ensureAvailable(length, "长度前缀 2 字节的字符串（长度 $length）")
        return bytes(length).decodeToString()
    }

    /** 校验 payload 已被完整消费，防止字段与协议字典不一致时静默丢数据。 */
    fun ensureExhausted() {
        if (remaining != 0) {
            throw MessageDecodeException("payload 尾部仍有 $remaining 字节未解析")
        }
    }

    private fun ensureAvailable(
        count: Int,
        label: String,
    ) {
        if (count > remaining) {
            throw MessageDecodeException("payload 截断：需要 $label，剩余 $remaining 字节")
        }
    }
}
