package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion

/** 固件库条目对某台设备的适用性。 */
enum class FirmwareApplicabilityStatus {
    APPLICABLE,
    INAPPLICABLE,
}

/**
 * 不适用原因。
 *
 * 与 [FirmwarePrechecker] 的预检口径保持一致：型号未知/不匹配、版本未知、版本回退。
 */
enum class FirmwareApplicabilityReason {
    NONE,
    MODEL_UNKNOWN,
    MODEL_MISMATCH,
    VERSION_UNKNOWN,
    VERSION_UNPARSEABLE,
    VERSION_ROLLBACK,
}

data class FirmwareApplicabilityResult(
    val status: FirmwareApplicabilityStatus,
    val reason: FirmwareApplicabilityReason,
    val firmwareVersionText: String,
) {
    val applicable: Boolean get() = status == FirmwareApplicabilityStatus.APPLICABLE
}

/**
 * 用固件库元数据做「适用/不适用」快速标记。
 *
 * 这里只复用既有预检规则（型号白名单与 [FirmwarePrechecker.compare]），不新造判定标准；
 * 真正升级前仍必须打开文件执行完整预检（CRC、签名、版本、型号）。
 */
object FirmwareApplicabilityEvaluator {

    private val VERSION_PATTERN =
        Regex("""^(\d+)\.(\d+)\.(\d+)(?:\s*\(build\s+(\d+),\s*([^)]+)\))?$""")

    fun parseVersion(text: String): FirmwareVersion? {
        val match = VERSION_PATTERN.matchEntire(text.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        val build = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val channel = match.groupValues[5].trim()
        return runCatching {
            FirmwareVersion(major = major, minor = minor, patch = patch, build = build, channel = channel)
        }.getOrNull()
    }

    fun evaluate(
        record: FirmwareLibraryRecord,
        currentModel: String?,
        currentVersion: FirmwareVersion?,
    ): FirmwareApplicabilityResult {
        val targetVersion = parseVersion(record.version)
            ?: return FirmwareApplicabilityResult(
                status = FirmwareApplicabilityStatus.INAPPLICABLE,
                reason = FirmwareApplicabilityReason.VERSION_UNPARSEABLE,
                firmwareVersionText = record.version,
            )
        if (currentModel.isNullOrBlank()) {
            return FirmwareApplicabilityResult(
                status = FirmwareApplicabilityStatus.INAPPLICABLE,
                reason = FirmwareApplicabilityReason.MODEL_UNKNOWN,
                firmwareVersionText = record.version,
            )
        }
        if (!record.supportsModel(currentModel)) {
            return FirmwareApplicabilityResult(
                status = FirmwareApplicabilityStatus.INAPPLICABLE,
                reason = FirmwareApplicabilityReason.MODEL_MISMATCH,
                firmwareVersionText = record.version,
            )
        }
        if (currentVersion == null) {
            return FirmwareApplicabilityResult(
                status = FirmwareApplicabilityStatus.INAPPLICABLE,
                reason = FirmwareApplicabilityReason.VERSION_UNKNOWN,
                firmwareVersionText = record.version,
            )
        }
        if (FirmwarePrechecker.compare(targetVersion, currentVersion) < 0) {
            return FirmwareApplicabilityResult(
                status = FirmwareApplicabilityStatus.INAPPLICABLE,
                reason = FirmwareApplicabilityReason.VERSION_ROLLBACK,
                firmwareVersionText = record.version,
            )
        }
        return FirmwareApplicabilityResult(
            status = FirmwareApplicabilityStatus.APPLICABLE,
            reason = FirmwareApplicabilityReason.NONE,
            firmwareVersionText = record.version,
        )
    }

    /** 选出版本最高的适用条目；同版本时返回最近引入的一条。 */
    fun latestApplicable(
        records: List<FirmwareLibraryRecord>,
        currentModel: String?,
        currentVersion: FirmwareVersion?,
    ): FirmwareLibraryRecord? {
        var best: FirmwareLibraryRecord? = null
        var bestVersion: FirmwareVersion? = null
        records.forEach { record ->
            val candidate = parseVersion(record.version) ?: return@forEach
            if (!evaluate(record, currentModel, currentVersion).applicable) return@forEach
            val previous = best
            if (previous == null || bestVersion == null) {
                best = record
                bestVersion = candidate
                return@forEach
            }
            val comparison = FirmwarePrechecker.compare(candidate, bestVersion!!)
            if (comparison > 0 || (comparison == 0 && record.addedAtEpochMillis > previous.addedAtEpochMillis)) {
                best = record
                bestVersion = candidate
            }
        }
        return best
    }
}

/**
 * 型号白名单匹配与 [FirmwareHeader.supportsModel] 完全一致：
 * `*` 通配所有型号，其余忽略大小写精确匹配。
 */
internal fun FirmwareLibraryRecord.supportsModel(model: String): Boolean {
    val normalized = model.trim()
    if (normalized.isEmpty()) return false
    return modelWhitelist.contains("*") ||
        modelWhitelist.any { it.equals(normalized, ignoreCase = true) }
}
