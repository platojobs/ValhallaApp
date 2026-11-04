package com.valhalla.core.profile

/** OTA 一次执行的结果。 */
enum class OtaOutcome {
    SUCCEEDED,
    FAILED,
    ABORTED,
}

/**
 * 设备档案中的一条 OTA 历史记录。
 *
 * - [firmwareVersion] 为执行的固件版本（含 build 与渠道，便于排查）；
 * - [durationMillis] 从 START_OTA 准备阶段到终态的实际耗时；
 * - [errorCode] 设备返回的状态码描述（如 `0x05 鉴权失败`），成功/中止时为 null；
 * - [detail] 面向用户的失败/中止原因，便于在档案页直接展示。
 */
data class OtaHistoryRecord(
    val firmwareVersion: String,
    val finishedAtEpochMillis: Long,
    val durationMillis: Long,
    val outcome: OtaOutcome,
    val errorCode: String? = null,
    val detail: String? = null,
    val sentBytes: Int = 0,
    val totalBytes: Int = 0,
) {

    init {
        require(firmwareVersion.isNotEmpty()) { "固件版本不能为空" }
        require(finishedAtEpochMillis >= 0) { "完成时间不能为负" }
        require(durationMillis >= 0) { "耗时不能为负" }
        require(sentBytes >= 0) { "已发送字节不能为负" }
        require(totalBytes >= 0) { "总字节不能为负" }
    }

    val progress: Float
        get() = if (totalBytes == 0) 0f else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * 设备档案：跨会话保留的设备快照。
 *
 * 档案只保存「最近一次已知状态」，不追求与设备实时同步；连接成功或刷新信息后由
 * `DeviceDetailController` 更新。OTA 历史按时间倒序保存在 [otaHistory] 中，
 * 由 [DeviceProfileRepository] 控制保留条数。
 */
data class DeviceProfile(
    val deviceId: String,
    val displayName: String? = null,
    val vendor: String? = null,
    val model: String? = null,
    val lastConnectedAtEpochMillis: Long,
    val lastKnownFirmwareVersion: String? = null,
    val lastKnownBatteryPercent: Int? = null,
    val lastKnownCharging: Boolean? = null,
    /** 最近一次 OTA 记录在前；受仓储层 [DeviceProfileRepository.maxOtaHistory] 限制。 */
    val otaHistory: List<OtaHistoryRecord> = emptyList(),
) {

    init {
        require(deviceId.isNotBlank()) { "设备 id 不能为空" }
        require(lastConnectedAtEpochMillis >= 0) { "最后连接时间不能为负" }
        require(lastKnownBatteryPercent == null || lastKnownBatteryPercent in 0..100) {
            "电量百分比超出范围: $lastKnownBatteryPercent"
        }
    }

    val lastOtaRecord: OtaHistoryRecord? get() = otaHistory.firstOrNull()
}

/** 设备档案的原始字符串存储。平台实现由 data 模块提供，JVM 测试使用内存实现。 */
interface DeviceProfileStore {

    fun loadRaw(): String?

    fun saveRaw(raw: String)

    fun clear()
}

/** 内存实现：用于 JVM / 单元测试，进程退出即丢失。 */
class InMemoryDeviceProfileStore(private var raw: String? = null) : DeviceProfileStore {

    override fun loadRaw(): String? = raw

    override fun saveRaw(raw: String) {
        this.raw = raw
    }

    override fun clear() {
        raw = null
    }
}

/**
 * 设备档案的版本化行式编码。
 *
 * 选择行式编码而不是 JSON / SQLDelight 的原因见 `docs/storage.md`：
 * 档案是一组小对象，只需要整表读写的键值存储即可；行式编码无需引入序列化库，
 * 在 `commonMain` 中可直接单测，平台侧只提供 KV driver。
 *
 * 格式：
 * ```
 * v1
 * P|deviceId|displayName|vendor|model|lastConnectedAt|firmware|battery|charging
 * O|firmwareVersion|finishedAt|duration|outcome|errorCode|detail|sent|total
 * O|...
 * P|...
 * ```
 *
 * 字段使用 UTF-8 百分号转义（仅保留 `A-Z a-z 0-9 . _ ~ -`），空字符串表示 null。
 * 任何结构损坏或版本不匹配的记录都会被跳过，读取不会抛出异常。
 */
object DeviceProfileCodec {

    private const val VERSION = "v1"
    private const val PROFILE = "P"
    private const val OTA = "O"
    private const val SEPARATOR = '|'
    private const val PROFILE_PARTS = 9
    private const val OTA_PARTS = 9
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun encode(profiles: List<DeviceProfile>): String = buildString {
        append(VERSION)
        append('\n')
        profiles.forEach { profile ->
            append(PROFILE)
            append(SEPARATOR)
            append(escape(profile.deviceId))
            append(SEPARATOR)
            append(escape(profile.displayName))
            append(SEPARATOR)
            append(escape(profile.vendor))
            append(SEPARATOR)
            append(escape(profile.model))
            append(SEPARATOR)
            append(profile.lastConnectedAtEpochMillis)
            append(SEPARATOR)
            append(escape(profile.lastKnownFirmwareVersion))
            append(SEPARATOR)
            append(profile.lastKnownBatteryPercent?.toString().orEmpty())
            append(SEPARATOR)
            append(profile.lastKnownCharging?.let { if (it) "1" else "0" }.orEmpty())
            append('\n')
            profile.otaHistory.forEach { record ->
                append(OTA)
                append(SEPARATOR)
                append(escape(record.firmwareVersion))
                append(SEPARATOR)
                append(record.finishedAtEpochMillis)
                append(SEPARATOR)
                append(record.durationMillis)
                append(SEPARATOR)
                append(record.outcome.name)
                append(SEPARATOR)
                append(escape(record.errorCode))
                append(SEPARATOR)
                append(escape(record.detail))
                append(SEPARATOR)
                append(record.sentBytes)
                append(SEPARATOR)
                append(record.totalBytes)
                append('\n')
            }
        }
    }

    fun decode(raw: String?): List<DeviceProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != VERSION) return emptyList()

        val result = mutableListOf<DeviceProfile>()
        var current: DeviceProfile? = null

        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split(SEPARATOR)
            when (parts.firstOrNull()) {
                PROFILE -> {
                    current?.let(result::add)
                    current = parseProfile(parts)
                }

                OTA -> {
                    val profile = current ?: return@forEach
                    val record = parseOta(parts) ?: return@forEach
                    current = profile.copy(otaHistory = profile.otaHistory + record)
                }
            }
        }
        current?.let(result::add)
        return result
    }

    private fun parseProfile(parts: List<String>): DeviceProfile? {
        if (parts.size != PROFILE_PARTS) return null
        val deviceId = unescape(parts[1]) ?: return null
        val lastConnectedAt = parts[5].toLongOrNull() ?: return null
        val battery = if (parts[7].isEmpty()) null else parts[7].toIntOrNull() ?: return null
        val charging = when (parts[8]) {
            "" -> null
            "0" -> false
            "1" -> true
            else -> return null
        }
        return runCatching {
            DeviceProfile(
                deviceId = deviceId,
                displayName = unescape(parts[2]),
                vendor = unescape(parts[3]),
                model = unescape(parts[4]),
                lastConnectedAtEpochMillis = lastConnectedAt,
                lastKnownFirmwareVersion = unescape(parts[6]),
                lastKnownBatteryPercent = battery,
                lastKnownCharging = charging,
            )
        }.getOrNull()
    }

    private fun parseOta(parts: List<String>): OtaHistoryRecord? {
        if (parts.size != OTA_PARTS) return null
        val version = unescape(parts[1]) ?: return null
        val finishedAt = parts[2].toLongOrNull() ?: return null
        val duration = parts[3].toLongOrNull() ?: return null
        val outcome = OtaOutcome.entries.firstOrNull { it.name == parts[4] } ?: return null
        val sent = parts[7].toIntOrNull() ?: return null
        val total = parts[8].toIntOrNull() ?: return null
        return runCatching {
            OtaHistoryRecord(
                firmwareVersion = version,
                finishedAtEpochMillis = finishedAt,
                durationMillis = duration,
                outcome = outcome,
                errorCode = unescape(parts[5]),
                detail = unescape(parts[6]),
                sentBytes = sent,
                totalBytes = total,
            )
        }.getOrNull()
    }

    private fun escape(value: String?): String {
        if (value == null) return ""
        return buildString {
            value.encodeToByteArray().forEach { byte ->
                val code = byte.toInt() and 0xFF
                if (isAsciiSafe(code)) {
                    append(code.toChar())
                } else {
                    append('%')
                    append(HEX_DIGITS[code shr 4])
                    append(HEX_DIGITS[code and 0x0F])
                }
            }
        }
    }

    private fun isAsciiSafe(code: Int): Boolean =
        code in 0x30..0x39 ||
            code in 0x41..0x5A ||
            code in 0x61..0x7A ||
            code == '.'.code ||
            code == '_'.code ||
            code == '~'.code ||
            code == '-'.code

    private fun unescape(value: String): String? {
        if (value.isEmpty()) return null
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                if (index + 2 >= value.length) return null
                val high = value[index + 1].digitToIntOrNull(16) ?: return null
                val low = value[index + 2].digitToIntOrNull(16) ?: return null
                bytes += ((high shl 4) or low).toByte()
                index += 3
            } else {
                if (char.code > 0x7E) return null
                bytes += char.code.toByte()
                index++
            }
        }
        return runCatching { bytes.toByteArray().decodeToString() }.getOrNull()
    }
}
