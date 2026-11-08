package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.storage.StorageKind
import com.valhalla.core.storage.StorageMigrationStatus
import com.valhalla.core.storage.StorageSchemaMigrator

private const val U32_MAX = 0xFFFFFFFFL
private const val U16_MAX = 0xFFFF
private const val INDEX_SESSION_ID = 1
private const val INDEX_TOTAL_BYTES = 4
private const val INDEX_SENT_BYTES = 5
private const val INDEX_CRC16 = 6
private const val INDEX_CRC32 = 7

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
        sessionId in 0..U32_MAX &&
            totalBytes in 1..Int.MAX_VALUE &&
            sentBytes in 1 until totalBytes &&
            crc16 in 0..U16_MAX &&
            crc32 in 0..U32_MAX

    /** 固件身份是否一致（版本号 + 总长 + payload CRC-32）。版本号忽略渠道名。 */
    fun matchesFirmware(firmware: FirmwarePackage): Boolean =
        firmwareVersion.sameReleaseAs(firmware.version) &&
            totalBytes.toLong() == firmware.payloadLength &&
            crc32 == firmware.payloadCrc32

    /** 同一固件内容的完整校验（含协议层 CRC-16）。 */
    fun matchesFirmwarePayload(
        firmware: FirmwarePackage,
        crc16: Int,
    ): Boolean = matchesFirmware(firmware) && this.crc16 == crc16

    /** 与内存中的 payload（无文件头）比对：版本 + 总长 + CRC-32 + CRC-16。版本号忽略渠道名。 */
    fun matchesPayload(
        version: FirmwareVersion,
        totalBytes: Long,
        crc32: Long,
        crc16: Int,
    ): Boolean =
        firmwareVersion.sameReleaseAs(version) &&
            this.totalBytes.toLong() == totalBytes &&
            this.crc32 == crc32 &&
            this.crc16 == crc16

    fun progress(): Float = if (totalBytes == 0) 0f else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** 断点读取结果：有效断点（或 null）+ schema 迁移状态。 */
data class OtaCheckpointDecodeResult(
    val point: OtaResumePoint?,
    val status: StorageMigrationStatus,
)

/**
 * 断点字符串编码，供 SharedPreferences / NSUserDefaults 等键值存储使用：
 *
 * `v1|sessionId|major.minor.patch.build|channel|totalBytes|sentBytes|crc16|crc32`
 *
 * 渠道名与版本字段只允许 `A-Z a-z 0-9 . _ -`，因此无需转义；解码失败一律返回 null。
 * 版本判断与未来版本回退统一经过 [StorageSchemaMigrator]。
 */
object OtaResumePointCodec {
    private const val VERSION = "v1"
    private const val SEPARATOR = "|"
    private const val PARTS = 8

    private data class ParsedOtaCheckpoint(
        val point: OtaResumePoint?,
        val reason: String?,
    )

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

    fun decode(raw: String?): OtaResumePoint? = decodeWithStatus(raw).point

    fun decodeWithStatus(raw: String?): OtaCheckpointDecodeResult {
        val migration = StorageSchemaMigrator.migrate(StorageKind.OTA_CHECKPOINT, raw, ::readVersion)
        val usable = migration.raw ?: return OtaCheckpointDecodeResult(null, migration.status)
        val parsed = parse(usable)
        return if (parsed.point != null) {
            OtaCheckpointDecodeResult(parsed.point, migration.status)
        } else {
            OtaCheckpointDecodeResult(null, StorageMigrationStatus.Corrupted(parsed.reason.orEmpty()))
        }
    }

    private fun parse(usable: String): ParsedOtaCheckpoint {
        val parts = usable.split(SEPARATOR)
        if (parts.size != PARTS) {
            return ParsedOtaCheckpoint(null, "断点字段数量异常")
        }
        val sessionId =
            parts[INDEX_SESSION_ID].toLongOrNull()
                ?: return ParsedOtaCheckpoint(null, "sessionId 无法解析")
        val version =
            decodeVersion(parts[2], parts[3])
                ?: return ParsedOtaCheckpoint(null, "固件版本字段无法解析")
        val totalBytes =
            parts[INDEX_TOTAL_BYTES].toIntOrNull()
                ?: return ParsedOtaCheckpoint(null, "totalBytes 无法解析")
        val sentBytes =
            parts[INDEX_SENT_BYTES].toIntOrNull()
                ?: return ParsedOtaCheckpoint(null, "sentBytes 无法解析")
        val crc16 =
            parts[INDEX_CRC16].toIntOrNull()
                ?: return ParsedOtaCheckpoint(null, "crc16 无法解析")
        val crc32 =
            parts[INDEX_CRC32].toLongOrNull()
                ?: return ParsedOtaCheckpoint(null, "crc32 无法解析")

        val point =
            OtaResumePoint(
                sessionId = sessionId,
                sentBytes = sentBytes,
                totalBytes = totalBytes,
                crc16 = crc16,
                firmwareVersion = version,
                crc32 = crc32,
            )
        return if (point.isValid()) {
            ParsedOtaCheckpoint(point, null)
        } else {
            ParsedOtaCheckpoint(null, "断点数据校验失败")
        }
    }

    private fun readVersion(raw: String): Int? = raw.substringBefore(SEPARATOR).removePrefix("v").toIntOrNull()

    private fun decodeVersion(
        versionPart: String,
        channel: String,
    ): FirmwareVersion? {
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
