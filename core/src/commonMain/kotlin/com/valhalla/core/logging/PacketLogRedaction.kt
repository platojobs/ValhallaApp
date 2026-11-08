package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame

private const val U8_MASK = 0xFF

/**
 * 报文日志脱敏规则（写入时执行，默认开启且没有关闭入口）。
 *
 * 设计原则：
 * - 脱敏发生在 [InMemoryPacketLog.record]，因此 TEXT / JSON / CSV 三种导出格式
 *   以及复制到剪贴板的内容拿到的是同一份已脱敏数据，不存在「某格式漏脱敏」的路径；
 * - 只替换载荷中的敏感字节，**保持长度不变**，因此帧长度、字段偏移与 CRC 可正常重算，
 *   报文仍可被解析器识别为同一命令；
 * - 掩码字节统一为 `0x2A`（ASCII `*`），替换后无法还原原文；
 * - 结构字段（命令类型、序号、状态码、各字段长度、`expiresIn`）保留，便于排障。
 *
 * 规则表（与 `docs/privacy.md` 保持同步）：
 *
 * | 命令 | 方向 | 字段 | 处理 |
 * | --- | --- | --- | --- |
 * | `AUTH` | TX | `mode` 之后整段 `credential`（令牌 / 随机数 / 签名凭据） | 全部字节替换为 `*` |
 * | `AUTH` | RX（成功） | `token` 值、`challenge` 值 | 全部字节替换为 `*` |
 * | `AUTH` | RX（失败） | 无敏感字段 | 原样保留（便于看到设备错误详情） |
 * | `GET_DEVICE_INFO` | RX（成功） | `serialNumber` | 保留前 [DEVICE_ID_PREFIX_BYTES] 字节，其余替换为 `*` |
 * | 其他命令 | 任一 | 无 | 原样保留 |
 *
 * 设备标识（MAC / deviceId）不在协议 payload 中，导出文件若需要展示，调用方应使用
 * [maskDeviceId]（可读截断）或 [hashDeviceId]（不可逆哈希）。
 */
object PacketLogRedaction {
    /** 掩码字节：ASCII `*`，属于合法 UTF-8，长度不变。 */
    const val MASK_BYTE: Byte = 0x2A

    /** 设备标识保留的明文前缀字节数。 */
    const val DEVICE_ID_PREFIX_BYTES: Int = 4

    /** 需要整体脱敏 `credential` 的命令。 */
    val credentialCommands: Set<CommandType> = setOf(CommandType.AUTH)

    /** 需要截断设备序列号的命令。 */
    val deviceIdentifierCommands: Set<CommandType> = setOf(CommandType.GET_DEVICE_INFO)

    /** 对整帧执行脱敏；无敏感字段时返回原对象，避免额外分配。 */
    fun redactFrame(frame: Frame): Frame {
        val redacted = redactPayload(frame.type, frame.isResponse, frame.payload)
        if (redacted === frame.payload) return frame
        return Frame(
            type = frame.type,
            sequence = frame.sequence,
            payload = redacted,
            isResponse = frame.isResponse,
            isNotification = frame.isNotification,
            version = frame.version,
        )
    }

    /**
     * 按命令与方向脱敏 payload。
     *
     * @return 脱敏后的新数组；没有敏感字段时返回 [payload] 本身。
     */
    fun redactPayload(
        command: CommandType,
        isResponse: Boolean,
        payload: ByteArray,
    ): ByteArray {
        if (payload.isEmpty()) return payload
        return when (command) {
            CommandType.AUTH ->
                if (isResponse) redactAuthResponse(payload) else redactAuthRequest(payload)

            CommandType.GET_DEVICE_INFO ->
                if (isResponse) redactDeviceInfo(payload) else payload

            else -> payload
        }
    }

    /** AUTH 请求：`mode:u8 | credentialLen:u16 | credential`，保留前 3 字节，掩码 credential。 */
    private fun redactAuthRequest(payload: ByteArray): ByteArray {
        if (payload.size < 3) return maskAll(payload)
        val length = readU16(payload, 1)
        val start = 3
        val end = minOf(payload.size, start + length)
        return payload.copyOf().also { mask(it, start, end) }
    }

    /**
     * AUTH 成功应答：`status:u8 | tokenLen:u16 | token | expiresIn:u32 | challengeLen:u16 | challenge`。
     *
     * 状态码非 0 时为错误应答，没有 token/challenge，原样保留。
     */
    private fun redactAuthResponse(payload: ByteArray): ByteArray {
        if (payload[0].toInt() != 0) return payload
        val bytes = payload.copyOf()
        var cursor = 1
        cursor = maskLengthPrefixed(bytes, cursor) ?: return maskAll(payload)
        // expiresIn 为 u32，非敏感，保留。
        cursor += 4
        if (cursor > bytes.size) return maskAll(payload)
        cursor = maskLengthPrefixed(bytes, cursor) ?: return maskAll(payload)
        return bytes
    }

    /**
     * 设备信息应答：`status:u8 | vendor:str1 | model:str1 | serial:str1 | hardware:str1 | firmware`。
     *
     * 只截断序列号，其余字段保留。
     */
    private fun redactDeviceInfo(payload: ByteArray): ByteArray {
        if (payload[0].toInt() != 0) return payload
        var cursor = 1
        cursor = skipString1(payload, cursor) ?: return payload
        cursor = skipString1(payload, cursor) ?: return payload
        val serialStart = cursor + 1
        val serialLength = payload.getOrNull(cursor)?.toInt()?.and(U8_MASK) ?: return payload
        val serialEnd = minOf(payload.size, serialStart + serialLength)
        if (serialLength <= DEVICE_ID_PREFIX_BYTES) return payload
        return payload.copyOf().also {
            mask(it, serialStart + DEVICE_ID_PREFIX_BYTES, serialEnd)
        }
    }

    /** `str1`：1 字节长度 + 内容；返回下一个字段起点，越界返回 null。 */
    private fun skipString1(
        payload: ByteArray,
        cursor: Int,
    ): Int? {
        val length = payload.getOrNull(cursor)?.toInt()?.and(U8_MASK) ?: return null
        val next = cursor + 1 + length
        return if (next <= payload.size) next else null
    }

    /** 掩码 `u16 长度 + 内容` 字段；返回下一个字段起点，越界返回 null。 */
    private fun maskLengthPrefixed(
        payload: ByteArray,
        cursor: Int,
    ): Int? {
        if (cursor + 2 > payload.size) return null
        val length = readU16(payload, cursor)
        val start = cursor + 2
        val end = start + length
        if (end > payload.size) return null
        mask(payload, start, end)
        return end
    }

    /** 协议的长度前缀为小端 u16。 */
    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset + 1].toInt() and U8_MASK) shl 8) or (bytes[offset].toInt() and U8_MASK)

    private fun mask(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ) {
        for (index in from until minOf(to, bytes.size)) {
            bytes[index] = MASK_BYTE
        }
    }

    /** 解析失败时的兜底：除首字节（命令相关状态/模式）外全部掩码。 */
    private fun maskAll(payload: ByteArray): ByteArray = payload.copyOf().also { mask(it, 1, it.size) }

    /**
     * 设备标识可读截断：保留前 [keepPrefix] 个字符，其余替换为 `*`。
     *
     * 例：`AA:BB:CC:DD:EE:FF` → `AA:B**********`（长度不变）。
     */
    fun maskDeviceId(
        deviceId: String,
        keepPrefix: Int = DEVICE_ID_PREFIX_BYTES,
    ): String {
        if (deviceId.length <= keepPrefix) return deviceId.map { '*' }.joinToString("")
        return deviceId.take(keepPrefix) + "*".repeat(deviceId.length - keepPrefix)
    }

    /**
     * 设备标识不可逆哈希（FNV-1a 32 位），用于必须区分设备但不需要展示原值的导出字段。
     *
     * 输出形如 `dev#1a2b3c4d`，同一输入稳定，无法从哈希反推标识。
     */
    fun hashDeviceId(deviceId: String): String {
        var hash = 0x811C9DC5u
        deviceId.encodeToByteArray().forEach { byte ->
            hash = hash xor (byte.toInt().and(U8_MASK).toUInt())
            hash *= 0x01000193u
        }
        return "dev#" + hash.toString(16).padStart(8, '0')
    }
}
