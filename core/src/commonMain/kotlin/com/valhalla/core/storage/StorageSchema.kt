package com.valhalla.core.storage

/**
 * 本地存储的类型与当前 schema 版本。
 *
 * 所有版本号只在这里维护，编解码器通过 [StorageSchemaMigrator] 统一升级，
 * 避免每个存储各写一套「版本判断 + 迁移」逻辑。
 */
enum class StorageKind(
    val currentVersion: Int,
) {
    /** 偏好键值存储（逐 key 保存，版本号存在独立 key 中）。 */
    PREFERENCES(currentVersion = 2),

    /** 设备档案（行式文本，v1 行结构沿用）。 */
    DEVICE_PROFILES(currentVersion = 1),

    /** OTA 断点（单行竖线分隔文本）。 */
    OTA_CHECKPOINT(currentVersion = 1),

    /** 指标采样（行式文本，v1 → v2 升级头标记）。 */
    METRIC_SAMPLES(currentVersion = 2),

    /** 偏好备份 JSON 文件。 */
    PREFERENCES_BACKUP(currentVersion = 2),
}

/** 一次存储读取/迁移的结果状态；任何情况下都不抛异常。 */
sealed interface StorageMigrationStatus {
    /** 数据已经是当前版本，或存储为空。 */
    data object Current : StorageMigrationStatus

    /** 旧版本数据已成功升级。 */
    data class Migrated(
        val fromVersion: Int,
        val toVersion: Int,
    ) : StorageMigrationStatus

    /** 数据版本高于当前代码支持的版本；调用方应回退默认值并提示用户。 */
    data class FutureVersion(
        val foundVersion: Int,
        val supportedVersion: Int,
    ) : StorageMigrationStatus

    /** 数据格式损坏或迁移步骤失败；调用方应回退安全默认值并提示用户。 */
    data class Corrupted(
        val reason: String,
    ) : StorageMigrationStatus
}

/** 迁移结果：[raw] 为可继续解析的字符串（回退默认时为 null），[status] 说明发生了什么。 */
data class StorageMigrationResult(
    val raw: String?,
    val status: StorageMigrationStatus,
) {
    val isUsable: Boolean get() = raw != null
}

/**
 * 集中迁移入口：按 [StorageKind] 读取版本、判断未来版本并逐级执行迁移步骤。
 *
 * 约定：
 * - 空数据视为「无数据」，状态为 [StorageMigrationStatus.Current]；
 * - 版本无法解析、缺少迁移步骤、迁移步骤返回 null，都视为 [StorageMigrationStatus.Corrupted]；
 * - 版本高于当前版本时不改动数据，返回 [StorageMigrationStatus.FutureVersion] 由调用方回退默认。
 */
object StorageSchemaMigrator {
    fun migrate(
        kind: StorageKind,
        raw: String?,
        readVersion: (String) -> Int?,
        steps: Map<Int, (String) -> String?> = emptyMap(),
    ): StorageMigrationResult {
        val source = raw?.takeIf { it.isNotBlank() } ?: return StorageMigrationResult(null, StorageMigrationStatus.Current)
        val found = readVersion(source) ?: return StorageMigrationResult(null, StorageMigrationStatus.Corrupted("无法解析 schemaVersion"))
        val supported = kind.currentVersion
        if (found > supported) {
            return StorageMigrationResult(
                raw = null,
                status = StorageMigrationStatus.FutureVersion(foundVersion = found, supportedVersion = supported),
            )
        }
        var current = found
        var currentRaw = source
        while (current < supported) {
            val step = steps[current] ?: return StorageMigrationResult(null, StorageMigrationStatus.Corrupted("缺少 v$current → v${current + 1} 迁移步骤"))
            val next = step(currentRaw) ?: return StorageMigrationResult(null, StorageMigrationStatus.Corrupted("v$current → v${current + 1} 迁移失败"))
            currentRaw = next
            current++
        }
        val status = if (found == supported) StorageMigrationStatus.Current else StorageMigrationStatus.Migrated(found, supported)
        return StorageMigrationResult(currentRaw, status)
    }

    /** 只判断版本，不执行迁移；备份 JSON 等结构化格式使用。 */
    fun versionStatus(
        kind: StorageKind,
        foundVersion: Int,
    ): StorageMigrationStatus =
        when {
            foundVersion == kind.currentVersion -> StorageMigrationStatus.Current
            foundVersion > kind.currentVersion ->
                StorageMigrationStatus.FutureVersion(foundVersion = foundVersion, supportedVersion = kind.currentVersion)
            else -> StorageMigrationStatus.Migrated(foundVersion, kind.currentVersion)
        }
}
