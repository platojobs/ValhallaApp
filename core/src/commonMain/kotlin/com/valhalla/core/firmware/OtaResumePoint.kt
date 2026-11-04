package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion

/**
 * OTA 断点锚点。
 *
 * 字段含义：
 * - [sessionId]：设备在 START_OTA 应答中分配的会话号，只有同一会话才能续传；
 * - [firmwareVersion]：固件版本，用于恢复时确认「续的是同一份固件」；
 * - [sentBytes]：已成功写入设备的 payload 字节数（绝对偏移，不含文件头）；
 * - [totalBytes]：payload 总长度；
 * - [crc16]：协议层对 payload 计算的 CRC-16/CCITT-FALSE；
 * - [crc32]：固件头声明的 payload CRC-32，用于本地识别固件身份。
 *
 * 失效条件（任一命中都不能续传）：
 * 1. [sentBytes] 不在 `1 until totalBytes`，或 [totalBytes] 非法；
 * 2. 当前所选固件的版本 / payload 总长 / CRC-32 与断点不一致；
 * 3. 设备重新执行 START_OTA 后返回的 `sessionId` 与断点不同；
 * 4. 升级成功、用户主动中止、或执行 reset / 清除断点之后。
 */
data class OtaResumePoint(
    val sessionId: Long,
    val sentBytes: Int,
    val totalBytes: Int,
    val crc16: Int,
    val firmwareVersion: FirmwareVersion,
    val crc32: Long,
) {

    fun isValid(): Boolean =
        sessionId in 0..0xFFFFFFFFL &&
            totalBytes in 1..Int.MAX_VALUE &&
            sentBytes in 1 until totalBytes &&
            crc16 in 0..0xFFFF &&
            crc32 in 0..0xFFFFFFFFL

    /** 固件身份是否一致（版本 + 总长 + payload CRC-32）。 */
    fun matchesFirmware(firmware: FirmwarePackage): Boolean =
        firmwareVersion == firmware.version &&
            totalBytes.toLong() == firmware.payloadLength &&
            crc32 == firmware.payloadCrc32

    /** 同一固件内容的完整校验（含协议层 CRC-16）。 */
    fun matchesFirmwarePayload(firmware: FirmwarePackage, crc16: Int): Boolean =
        matchesFirmware(firmware) && this.crc16 == crc16

    /** 与内存中的 payload（无文件头）比对：版本 + 总长 + CRC-32 + CRC-16。 */
    fun matchesPayload(
        version: FirmwareVersion,
        totalBytes: Long,
        crc32: Long,
        crc16: Int,
    ): Boolean =
        firmwareVersion == version &&
            this.totalBytes.toLong() == totalBytes &&
            this.crc32 == crc32 &&
            this.crc16 == crc16

    fun progress(): Float = if (totalBytes == 0) 0f else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * 断点字符串编码，供 SharedPreferences / NSUserDefaults 等键值存储使用：
 *
 * `v1|sessionId|major.minor.patch.build|channel|totalBytes|sentBytes|crc16|crc32`
 *
 * 渠道名与版本字段只允许 `A-Z a-z 0-9 . _ -`，因此无需转义；解码失败一律返回 null。
 */
object OtaResumePointCodec {

    private const val VERSION = "v1"
    private const val SEPARATOR = "|"
    private const val PARTS = 8

    fun encode(point: OtaResumePoint): String {
        require(point.isValid()) { "断点数据非法，拒绝编码: $point" }
        val version = point.firmwareVersion
        return listOf(
            VERSION,
            point.sessionId.toString(),
            "${version.major}.${version.minor}.${version.patch}.${version.build}",
            version.channel,
            point.totalBytes.toString(),
            point.sentBytes.toString(),
            point.crc16.toString(),
            point.crc32.toString(),
        ).joinToString(SEPARATOR)
    }

    fun decode(raw: String?): OtaResumePoint? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(SEPARATOR)
        if (parts.size != PARTS || parts[0] != VERSION) return null

        val sessionId = parts[1].toLongOrNull() ?: return null
        val version = decodeVersion(parts[2], parts[3]) ?: return null
        val totalBytes = parts[4].toIntOrNull() ?: return null
        val sentBytes = parts[5].toIntOrNull() ?: return null
        val crc16 = parts[6].toIntOrNull() ?: return null
        val crc32 = parts[7].toLongOrNull() ?: return null

        val point = OtaResumePoint(
            sessionId = sessionId,
            sentBytes = sentBytes,
            totalBytes = totalBytes,
            crc16 = crc16,
            firmwareVersion = version,
            crc32 = crc32,
        )
        return point.takeIf { it.isValid() }
    }

    private fun decodeVersion(versionPart: String, channel: String): FirmwareVersion? {
        val fields = versionPart.split('.')
        if (fields.size != 4) return null
        val major = fields[0].toIntOrNull() ?: return null
        val minor = fields[1].toIntOrNull() ?: return null
        val patch = fields[2].toIntOrNull() ?: return null
        val build = fields[3].toIntOrNull() ?: return null
        return runCatching {
            FirmwareVersion(major = major, minor = minor, patch = patch, build = build, channel = channel)
        }.getOrNull()
    }
}
