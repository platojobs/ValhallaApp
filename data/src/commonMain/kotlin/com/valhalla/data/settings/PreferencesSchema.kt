package com.valhalla.data.settings

import com.valhalla.core.storage.StorageKind
import com.valhalla.core.storage.StorageMigrationStatus
import com.valhalla.core.storage.StorageSchemaMigrator

/**
 * 偏好存储的集中迁移入口。
 *
 * 偏好是逐 key 存储，没有单一字符串可迁移，因此这里读取版本 key 后：
 * - 缺失版本视为 v1，升级到 v2：把历史小写语言/主题值规范化为枚举名，并写入版本号；
 * - 版本等于当前版本则直接返回；
 * - 版本高于当前版本时**不改写任何数据**，返回 [StorageMigrationStatus.FutureVersion]，
 *   由调用方回退默认值并提示用户；
 * - 版本字段损坏时返回 [StorageMigrationStatus.Corrupted]，同样不覆盖原数据。
 */
object PreferencesSchema {
    const val CURRENT_VERSION: Int = 2

    private val LEGACY_LANGUAGE_MAP: Map<String, String> =
        mapOf(
            "system" to "SYSTEM",
            "zh" to "CHINESE",
            "zh-hans" to "CHINESE",
            "zh-hans-cn" to "CHINESE",
            "zh-cn" to "CHINESE",
            "zh_hans" to "CHINESE",
            "chinese" to "CHINESE",
            "en" to "ENGLISH",
            "en-us" to "ENGLISH",
            "english" to "ENGLISH",
        )

    private val LEGACY_THEME_MAP: Map<String, String> =
        mapOf(
            "system" to "SYSTEM",
            "light" to "LIGHT",
            "dark" to "DARK",
        )

    fun migrate(store: AppSettingsStore): StorageMigrationStatus {
        val rawVersion = store.read(SettingsKeys.SCHEMA_VERSION)
        val found = rawVersion?.toIntOrNull()
        if (rawVersion != null && found == null) {
            return StorageMigrationStatus.Corrupted("storage_schema_version 不是整数: $rawVersion")
        }
        return when {
            found == null -> upgradeFromV1(store)
            found > CURRENT_VERSION ->
                StorageSchemaMigrator.versionStatus(StorageKind.PREFERENCES, found)
            found == CURRENT_VERSION -> StorageMigrationStatus.Current
            else -> upgradeFromV1(store)
        }
    }

    private fun upgradeFromV1(store: AppSettingsStore): StorageMigrationStatus {
        normalizeLanguage(store)
        normalizeTheme(store)
        store.write(SettingsKeys.SCHEMA_VERSION, CURRENT_VERSION.toString())
        return StorageMigrationStatus.Migrated(fromVersion = 1, toVersion = CURRENT_VERSION)
    }

    private val VALID_LANGUAGES: Set<String> = setOf("SYSTEM", "CHINESE", "ENGLISH")

    private fun normalizeLanguage(store: AppSettingsStore) {
        val raw = store.read(SettingsKeys.LANGUAGE) ?: return
        if (raw in VALID_LANGUAGES) return
        val normalized = LEGACY_LANGUAGE_MAP[raw.trim().lowercase()] ?: return
        store.write(SettingsKeys.LANGUAGE, normalized)
    }

    private fun normalizeTheme(store: AppSettingsStore) {
        val raw = store.read(SettingsKeys.THEME) ?: return
        if (raw == "SYSTEM" || raw == "LIGHT" || raw == "DARK") return
        val normalized = LEGACY_THEME_MAP[raw.trim().lowercase()] ?: return
        store.write(SettingsKeys.THEME, normalized)
    }
}
