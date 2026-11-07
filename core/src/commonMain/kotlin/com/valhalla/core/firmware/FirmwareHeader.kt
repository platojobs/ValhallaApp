package com.valhalla.core.firmware

import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.FirmwareVersion

/**
 * 固件文件头（固定 104 字节，小端序）。
 *
 * | 偏移 | 长度 | 字段 |
 * | ---- | ---- | ---- |
 * | 0 | 2 | 魔数 `0x56 0x46`（"VF"） |
 * | 2 | 1 | 格式版本，当前固定为 1 |
 * | 3 | 1 | 标志位：bit0 = 含签名段，其余位保留必须为 0 |
 * | 4 | 2 | 版本 major（u16，实际 0..255） |
 * | 6 | 2 | 版本 minor（u16，实际 0..255） |
 * | 8 | 2 | 版本 patch（u16，实际 0..255） |
 * | 10 | 2 | 版本 build（u16） |
 * | 12 | 1 | 渠道名长度（0..16） |
 * | 13 | 16 | 渠道名（ASCII，不足补 0） |
 * | 29 | 1 | 目标型号数量（1..4） |
 * | 30 | 64 | 4 个目标型号，每个 16 字节（ASCII，不足补 0） |
 * | 94 | 4 | payload 长度（u32） |
 * | 98 | 4 | payload CRC-32（IEEE） |
 * | 102 | 2 | 头部 CRC-16/CCITT-FALSE（覆盖 0..101） |
 *
 * OTA 协议层只传输 payload，文件头本身不上传；因此协议里的 `totalBytes`/`crc16`
 * 针对 payload 计算，与固件头里的 `payloadLength`/`payloadCrc32` 保持一致。
 *
 * 型号字段允许 `*` 作为唯一通配符（必须独占整个字段）；渠道名与型号允许的字符为
 * `A-Z a-z 0-9 . _ -`，避免不同平台对文件名/模型名的转义差异。
 */
class FirmwareHeader(
    val firmwareVersion: FirmwareVersion,
    val targetModels: List<String>,
    val payloadLength: Long,
    val payloadCrc32: Long,
    /** 标志位 bit0 是否置位：payload 之后是否追加签名段。 */
    val hasSignature: Boolean = false,
) {
    init {
        require(firmwareVersion.channel.length <= CHANNEL_FIELD_SIZE) {
            "渠道名超过 $CHANNEL_FIELD_SIZE 字节: ${firmwareVersion.channel}"
        }
        firmwareVersion.channel.forEach { require(isSafeChar(it, allowWildcard = false)) { "渠道名包含非法字符: '$it'" } }
        require(targetModels.isNotEmpty()) { "目标型号白名单不能为空" }
        require(targetModels.size <= MAX_MODELS) { "目标型号最多 $MAX_MODELS 个，实际 ${targetModels.size}" }
        targetModels.forEach { model ->
            require(model.isNotEmpty()) { "目标型号不能为空" }
            require(model.length <= MODEL_FIELD_SIZE) { "目标型号超过 $MODEL_FIELD_SIZE 字节: $model" }
            require(isModelValid(model)) { "目标型号格式非法: $model" }
        }
        require(targetModels.distinctBy { it.lowercase() }.size == targetModels.size) { "目标型号存在重复项: $targetModels" }
        require(payloadLength in 1..0xFFFFFFFFL) { "payloadLength 超出 u32 范围: $payloadLength" }
        require(payloadCrc32 in 0..0xFFFFFFFFL) { "payloadCrc32 超出 u32 范围: $payloadCrc32" }
    }

    /** 是否使用 `*` 通配所有型号。 */
    val matchesAnyModel: Boolean get() = targetModels.contains(WILDCARD_MODEL)

    fun supportsModel(model: String): Boolean {
        val normalized = model.trim()
        if (normalized.isEmpty()) return false
        return matchesAnyModel || targetModels.any { it.equals(normalized, ignoreCase = true) }
    }

    /** 编码为 104 字节固定头，自动填充头部 CRC。 */
    fun encode(): ByteArray {
        val bytes = ByteArray(SIZE)
        bytes[0] = MAGIC_0
        bytes[1] = MAGIC_1
        bytes[2] = FORMAT_VERSION.toByte()
        bytes[3] = if (hasSignature) SIGNATURE_FLAG.toByte() else 0
        writeU16(bytes, 4, firmwareVersion.major)
        writeU16(bytes, 6, firmwareVersion.minor)
        writeU16(bytes, 8, firmwareVersion.patch)
        writeU16(bytes, 10, firmwareVersion.build)

        val channel = firmwareVersion.channel.encodeToByteArray()
        bytes[12] = channel.size.toByte()
        channel.copyInto(bytes, destinationOffset = 13)

        bytes[29] = targetModels.size.toByte()
        targetModels.forEachIndexed { index, model ->
            val encoded = model.encodeToByteArray()
            encoded.copyInto(bytes, destinationOffset = 30 + index * MODEL_FIELD_SIZE)
        }

        writeU32(bytes, 94, payloadLength)
        writeU32(bytes, 98, payloadCrc32)
        writeU16(bytes, 102, Crc16.ccittFalse(bytes, 0, 102))
        return bytes
    }

    override fun toString(): String =
        "FirmwareHeader($firmwareVersion, models=$targetModels, payloadLength=$payloadLength, " +
            "payloadCrc32=0x${payloadCrc32.toString(16)})"

    companion object {
        const val SIZE: Int = 104
        const val FORMAT_VERSION: Int = 1
        const val MAX_MODELS: Int = 4
        const val MODEL_FIELD_SIZE: Int = 16
        const val CHANNEL_FIELD_SIZE: Int = 16
        const val WILDCARD_MODEL: String = "*"

        /** 标志位 bit0：payload 之后包含签名段。 */
        const val SIGNATURE_FLAG: Int = 0x01

        private const val MAGIC_0: Byte = 0x56
        private const val MAGIC_1: Byte = 0x46

        /** 解析 104 字节文件头；字段非法、魔数不符、CRC 失败等都会抛出 [FirmwareException]。 */
        fun decode(bytes: ByteArray): FirmwareHeader {
            if (bytes.size < SIZE) throw FirmwareException.Truncated(SIZE.toLong(), bytes.size.toLong())

            if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) {
                throw FirmwareException.InvalidMagic(
                    "0x${hex(bytes[0])} 0x${hex(bytes[1])}",
                )
            }

            val formatVersion = bytes[2].toInt() and 0xFF
            if (formatVersion != FORMAT_VERSION) throw FirmwareException.UnsupportedFormat(formatVersion)

            val flags = bytes[3].toInt() and 0xFF
            if ((flags and SIGNATURE_FLAG.inv() and 0xFF) != 0) throw FirmwareException.UnsupportedFlags(flags)
            val hasSignature = (flags and SIGNATURE_FLAG) != 0

            val expectedHeaderCrc = readU16(bytes, 102)
            val actualHeaderCrc = Crc16.ccittFalse(bytes, 0, 102)
            if (expectedHeaderCrc != actualHeaderCrc) {
                throw FirmwareException.HeaderChecksumMismatch(expectedHeaderCrc, actualHeaderCrc)
            }

            val major = readU16(bytes, 4)
            val minor = readU16(bytes, 6)
            val patch = readU16(bytes, 8)
            val build = readU16(bytes, 10)
            if (major > 0xFF) throw FirmwareException.InvalidHeaderField("major", "超出 0..255: $major")
            if (minor > 0xFF) throw FirmwareException.InvalidHeaderField("minor", "超出 0..255: $minor")
            if (patch > 0xFF) throw FirmwareException.InvalidHeaderField("patch", "超出 0..255: $patch")

            val channelLength = bytes[12].toInt() and 0xFF
            if (channelLength > CHANNEL_FIELD_SIZE) {
                throw FirmwareException.InvalidHeaderField("channelLength", "超出 0..$CHANNEL_FIELD_SIZE: $channelLength")
            }
            val channel =
                readFixedString(bytes, 13, CHANNEL_FIELD_SIZE, channelLength) { char ->
                    isSafeChar(char, allowWildcard = false)
                }
            if (hasNonZeroTail(bytes, 13 + channelLength, 13 + CHANNEL_FIELD_SIZE)) {
                throw FirmwareException.InvalidHeaderField("channel", "长度之外的填充字节非零")
            }

            val modelCount = bytes[29].toInt() and 0xFF
            if (modelCount !in 1..MAX_MODELS) {
                throw FirmwareException.InvalidHeaderField("modelCount", "超出 1..$MAX_MODELS: $modelCount")
            }
            val models = mutableListOf<String>()
            repeat(MAX_MODELS) { index ->
                val fieldStart = 30 + index * MODEL_FIELD_SIZE
                if (index < modelCount) {
                    val length =
                        firstZeroIndex(bytes, fieldStart, fieldStart + MODEL_FIELD_SIZE)
                            .let { zero -> zero - fieldStart }
                    if (length == 0) {
                        throw FirmwareException.InvalidHeaderField("model[$index]", "型号为空")
                    }
                    val model = readFixedString(bytes, fieldStart, MODEL_FIELD_SIZE, length) { isModelValidChar(it) }
                    if (!isModelValid(model)) {
                        throw FirmwareException.InvalidHeaderField("model[$index]", "型号格式非法: $model")
                    }
                    if (models.any { it.equals(model, ignoreCase = true) }) {
                        throw FirmwareException.InvalidHeaderField("model[$index]", "型号重复: $model")
                    }
                    models += model
                } else if (hasNonZeroTail(bytes, fieldStart, fieldStart + MODEL_FIELD_SIZE)) {
                    throw FirmwareException.InvalidHeaderField("model[$index]", "未使用的型号字段非零")
                }
            }

            val payloadLength = readU32(bytes, 94)
            if (payloadLength == 0L) {
                throw FirmwareException.InvalidHeaderField("payloadLength", "必须大于 0")
            }
            val payloadCrc32 = readU32(bytes, 98)

            return FirmwareHeader(
                firmwareVersion = FirmwareVersion(major, minor, patch, build, channel),
                targetModels = models,
                payloadLength = payloadLength,
                payloadCrc32 = payloadCrc32,
                hasSignature = hasSignature,
            )
        }

        private fun readFixedString(
            bytes: ByteArray,
            start: Int,
            fieldSize: Int,
            length: Int,
            isValid: (Char) -> Boolean,
        ): String {
            val end = start + length
            val builder = StringBuilder(length)
            for (index in start until end) {
                val value = bytes[index].toInt() and 0xFF
                if (value == 0) throw FirmwareException.InvalidHeaderField("string", "字段内出现提前的 0 字节")
                val char = value.toChar()
                if (!isValid(char)) throw FirmwareException.InvalidHeaderField("string", "包含非法字符: '$char'")
                builder.append(char)
            }
            // fieldSize 只用于定位；长度之外的填充由调用方校验。
            require(length <= fieldSize) { "字符串长度超过字段大小: $length > $fieldSize" }
            return builder.toString()
        }

        private fun firstZeroIndex(
            bytes: ByteArray,
            start: Int,
            end: Int,
        ): Int {
            for (index in start until end) {
                if (bytes[index].toInt() == 0) return index
            }
            return end
        }

        private fun hasNonZeroTail(
            bytes: ByteArray,
            start: Int,
            end: Int,
        ): Boolean {
            for (index in start until end) {
                if (bytes[index].toInt() != 0) return true
            }
            return false
        }

        private fun isSafeChar(
            char: Char,
            allowWildcard: Boolean,
        ): Boolean =
            char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '.' ||
                char == '_' ||
                char == '-' ||
                (allowWildcard && char == '*')

        private fun isModelValidChar(char: Char): Boolean = isSafeChar(char, allowWildcard = true)

        private fun isModelValid(model: String): Boolean {
            if (model == WILDCARD_MODEL) return true
            if (model.contains(WILDCARD_MODEL)) return false
            return model.all { isSafeChar(it, allowWildcard = false) }
        }

        private fun writeU16(
            bytes: ByteArray,
            offset: Int,
            value: Int,
        ) {
            require(value in 0..0xFFFF) { "u16 超出范围: $value" }
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        private fun writeU32(
            bytes: ByteArray,
            offset: Int,
            value: Long,
        ) {
            require(value in 0..0xFFFFFFFFL) { "u32 超出范围: $value" }
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        private fun readU16(
            bytes: ByteArray,
            offset: Int,
        ): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readU32(
            bytes: ByteArray,
            offset: Int,
        ): Long {
            val low = readU16(bytes, offset).toLong()
            val high = readU16(bytes, offset + 2).toLong()
            return low or (high shl 16)
        }

        private fun hex(value: Byte): String = (value.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}
