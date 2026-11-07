package com.valhalla.feature.settings

import com.valhalla.core.AppBuildInfo
import com.valhalla.core.automation.AutomationAction
import com.valhalla.core.automation.AutomationRule
import com.valhalla.core.automation.AutomationRuleRepository
import com.valhalla.core.automation.AutomationTrigger
import com.valhalla.core.config.ConfigPreset
import com.valhalla.core.config.ConfigPresetRepository
import com.valhalla.core.storage.StorageKind
import com.valhalla.core.storage.StorageMigrationStatus
import com.valhalla.core.storage.StorageSchemaMigrator
import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.settings.SettingsKeys
import com.valhalla.feature.i18n.AppLanguageMode
import com.valhalla.feature.theme.AppPreferenceStore
import com.valhalla.feature.theme.ThemeMode

/** 备份包含的偏好分区；界面据此逐条展示覆盖项。 */
enum class BackupSection {
    LANGUAGE,
    THEME,
    RECONNECT,
    LOG_CAPACITY,
    EXPORT_DEFAULTS,
    PRESETS,
    AUTOMATION_RULES,
    ONBOARDING,
}

/** 导入前预览的一项。 */
data class BackupPlanItem(
    val section: BackupSection,
    val currentValue: String,
    val incomingValue: String,
    val willChange: Boolean,
)

/** 导入告警；缺失/非法字段已回退默认，界面逐条提示。 */
data class BackupIssue(
    val field: String,
    val missing: Boolean,
)

/**
 * 偏好与预设备份文档。
 *
 * 字段白名单固定：语言、主题、重连策略、日志容量、导出默认值、预设、自动化规则、引导标记。
 * 不包含令牌、密钥、凭证、设备档案、日志、断点或提醒开关。
 */
data class PreferencesBackup(
    val language: AppLanguageMode,
    val theme: ThemeMode,
    val reconnectMaxAttempts: Int,
    val reconnectMaxDelaySeconds: Int,
    val logCapacity: Int,
    val exportEntries: Int,
    val exportBytesKb: Int,
    val exportHexChars: Int,
    val presets: List<ConfigPreset>,
    val automationRules: List<AutomationRule>,
    val onboardingCompleted: Boolean,
) {
    /** 导出 JSON；只写白名单字段，并且不写任何运行时/敏感数据。 */
    fun toJson(): String =
        MiniJson.encode(
            JsonValue.Obj(
                linkedMapOf(
                    "schema" to JsonValue.Str(SCHEMA),
                    "schemaVersion" to JsonValue.Num(SCHEMA_VERSION.toDouble(), SCHEMA_VERSION.toString()),
                    "appVersion" to JsonValue.Str(AppBuildInfo.VERSION_NAME),
                    "language" to JsonValue.Str(language.name),
                    "theme" to JsonValue.Str(theme.name),
                    "reconnectMaxAttempts" to number(reconnectMaxAttempts),
                    "reconnectMaxDelaySeconds" to number(reconnectMaxDelaySeconds),
                    "logCapacity" to number(logCapacity),
                    "exportEntries" to number(exportEntries),
                    "exportBytesKb" to number(exportBytesKb),
                    "exportHexChars" to number(exportHexChars),
                    "onboardingCompleted" to JsonValue.Bool(onboardingCompleted),
                    "presets" to
                        JsonValue.Arr(
                            presets.map { preset ->
                                JsonValue.Obj(
                                    linkedMapOf(
                                        "id" to JsonValue.Str(preset.id),
                                        "name" to JsonValue.Str(preset.name),
                                        "createdAtMillis" to number(preset.createdAtMillis),
                                        "lastUsedAtMillis" to number(preset.lastUsedAtMillis),
                                        "values" to
                                            JsonValue.Obj(
                                                preset.values.entries
                                                    .sortedBy { it.key }
                                                    .associate { it.key to JsonValue.Str(it.value) },
                                            ),
                                    ),
                                )
                            },
                        ),
                    "automationRules" to
                        JsonValue.Arr(
                            automationRules.map { rule ->
                                JsonValue.Obj(
                                    linkedMapOf(
                                        "id" to JsonValue.Str(rule.id),
                                        "trigger" to JsonValue.Str(rule.trigger.name),
                                        "action" to JsonValue.Str(rule.action.name),
                                        "enabled" to JsonValue.Bool(rule.enabled),
                                        "order" to number(rule.order),
                                        "presetId" to (rule.presetId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                                    ),
                                )
                            },
                        ),
                ),
            ),
        )

    companion object {
        const val SCHEMA = "valhalla.preferences"
        const val SCHEMA_VERSION = 2

        private fun number(value: Int): JsonValue.Num = JsonValue.Num(value.toDouble(), value.toString())

        private fun number(value: Long): JsonValue.Num = JsonValue.Num(value.toDouble(), value.toString())
    }
}

sealed interface BackupParseResult {
    data class Success(
        val backup: PreferencesBackup,
        val issues: List<BackupIssue>,
        val migrationStatus: StorageMigrationStatus = StorageMigrationStatus.Current,
        val warning: String? = null,
        val canApply: Boolean = true,
    ) : BackupParseResult

    data class Failure(
        val reason: String,
    ) : BackupParseResult
}

/**
 * 备份结构校验与版本检查。
 *
 * - 顶层必须是对象，`schema` 与 `schemaVersion` 必须匹配；
 * - 缺失或非法字段回退默认，并生成逐条 [BackupIssue]；
 * - 非法预设/规则条目跳过，不影响其它字段。
 */
object PreferencesBackupParser {
    fun parse(text: String): BackupParseResult {
        val root =
            MiniJson.parse(text)?.asObject()
                ?: return BackupParseResult.Failure("JSON 顶层不是对象")
        if (root.values["schema"]?.asString() != PreferencesBackup.SCHEMA) {
            return BackupParseResult.Failure("备份标识缺失或不受支持")
        }
        val version =
            root.values["schemaVersion"]?.asLong()
                ?: return BackupParseResult.Failure("缺少 schemaVersion")
        if (version < 1L) {
            return BackupParseResult.Failure("不支持的备份版本：$version")
        }
        val migration =
            StorageSchemaMigrator.versionStatus(
                StorageKind.PREFERENCES_BACKUP,
                version.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        if (migration is StorageMigrationStatus.FutureVersion) {
            return futureVersionFallback(version, migration)
        }

        val issues = mutableListOf<BackupIssue>()
        val defaults = AppSettings.DEFAULTS

        val language =
            enumField(
                root = root,
                field = "language",
                values = AppLanguageMode.entries,
                default = AppLanguageMode.SYSTEM,
                issues = issues,
            )
        val theme =
            enumField(
                root = root,
                field = "theme",
                values = ThemeMode.entries,
                default = ThemeMode.SYSTEM,
                issues = issues,
            )
        val reconnectAttempts =
            intField(
                root,
                "reconnectMaxAttempts",
                defaults.reconnectMaxAttempts,
                { AppSettings.isValidReconnectAttempts(it) },
                issues,
            )
        val reconnectDelay =
            intField(
                root,
                "reconnectMaxDelaySeconds",
                defaults.reconnectMaxDelaySeconds,
                { AppSettings.isValidReconnectMaxDelaySeconds(it) },
                issues,
            )
        val logCapacity =
            intField(
                root,
                "logCapacity",
                defaults.logCapacity,
                { AppSettings.isValidLogCapacity(it) },
                issues,
            )
        val exportEntries =
            intField(
                root,
                "exportEntries",
                defaults.exportEntries,
                { AppSettings.isValidExportEntries(it) },
                issues,
            )
        val exportBytes =
            intField(
                root,
                "exportBytesKb",
                defaults.exportBytesKb,
                { AppSettings.isValidExportBytesKb(it) },
                issues,
            )
        val exportHex =
            intField(
                root,
                "exportHexChars",
                defaults.exportHexChars,
                { AppSettings.isValidExportHexChars(it) },
                issues,
            )

        val onboarding = root.values["onboardingCompleted"]?.asBoolean()
        if (onboarding == null) {
            issues += BackupIssue("onboardingCompleted", missing = root.values["onboardingCompleted"] == null)
        }

        val presets = presetField(root, issues)
        val rules = ruleField(root, issues)

        return BackupParseResult.Success(
            backup =
                PreferencesBackup(
                    language = language,
                    theme = theme,
                    reconnectMaxAttempts = reconnectAttempts,
                    reconnectMaxDelaySeconds = reconnectDelay,
                    logCapacity = logCapacity,
                    exportEntries = exportEntries,
                    exportBytesKb = exportBytes,
                    exportHexChars = exportHex,
                    presets = presets,
                    automationRules = rules,
                    onboardingCompleted = onboarding ?: false,
                ),
            issues = issues,
            migrationStatus = migration,
        )
    }

    /**
     * 未来版本安全回退：不解读未知字段，返回全默认值并明确提示。
     * [BackupParseResult.Success.canApply] 为 false，界面应禁用「导入」按钮，避免用默认值覆盖现有偏好。
     */
    private fun futureVersionFallback(
        version: Long,
        status: StorageMigrationStatus.FutureVersion,
    ): BackupParseResult.Success {
        val defaults = AppSettings.DEFAULTS
        return BackupParseResult.Success(
            backup =
                PreferencesBackup(
                    language = AppLanguageMode.SYSTEM,
                    theme = ThemeMode.SYSTEM,
                    reconnectMaxAttempts = defaults.reconnectMaxAttempts,
                    reconnectMaxDelaySeconds = defaults.reconnectMaxDelaySeconds,
                    logCapacity = defaults.logCapacity,
                    exportEntries = defaults.exportEntries,
                    exportBytesKb = defaults.exportBytesKb,
                    exportHexChars = defaults.exportHexChars,
                    presets = emptyList(),
                    automationRules = emptyList(),
                    onboardingCompleted = false,
                ),
            issues =
                listOf(
                    "language",
                    "theme",
                    "reconnectMaxAttempts",
                    "reconnectMaxDelaySeconds",
                    "logCapacity",
                    "exportEntries",
                    "exportBytesKb",
                    "exportHexChars",
                    "presets",
                    "automationRules",
                    "onboardingCompleted",
                ).map { BackupIssue(it, missing = true) },
            migrationStatus = status,
            warning = "备份版本 $version 高于当前支持版本 ${PreferencesBackup.SCHEMA_VERSION}，已按默认值回退且禁止导入。",
            canApply = false,
        )
    }

    private fun <T> enumField(
        root: JsonValue.Obj,
        field: String,
        values: List<T>,
        default: T,
        issues: MutableList<BackupIssue>,
    ): T where T : Enum<T> {
        val raw = root.values[field]
        if (raw == null) {
            issues += BackupIssue(field, missing = true)
            return default
        }
        val parsed = values.firstOrNull { it.name == raw.asString() }
        if (parsed == null) {
            issues += BackupIssue(field, missing = false)
            return default
        }
        return parsed
    }

    private fun intField(
        root: JsonValue.Obj,
        field: String,
        default: Int,
        valid: (Int) -> Boolean,
        issues: MutableList<BackupIssue>,
    ): Int {
        val raw = root.values[field]
        if (raw == null) {
            issues += BackupIssue(field, missing = true)
            return default
        }
        val value = raw.asLong()?.toInt()
        if (value == null || !valid(value)) {
            issues += BackupIssue(field, missing = false)
            return default
        }
        return value
    }

    private fun presetField(
        root: JsonValue.Obj,
        issues: MutableList<BackupIssue>,
    ): List<ConfigPreset> {
        val raw = root.values["presets"]
        if (raw == null) {
            issues += BackupIssue("presets", missing = true)
            return emptyList()
        }
        val array = raw.asArray()
        if (array == null) {
            issues += BackupIssue("presets", missing = false)
            return emptyList()
        }
        return array.values.mapNotNull { item ->
            val objectValue = item.asObject()
            if (objectValue == null) {
                issues += BackupIssue("presets[]", missing = false)
                return@mapNotNull null
            }
            val id = objectValue.values["id"]?.asString()
            val name = objectValue.values["name"]?.asString()
            val createdAt = objectValue.values["createdAtMillis"]?.asLong()
            val lastUsed = objectValue.values["lastUsedAtMillis"]?.asLong()
            val valuesObject = objectValue.values["values"]?.asObject()
            if (id.isNullOrBlank() ||
                name.isNullOrBlank() ||
                createdAt == null ||
                lastUsed == null ||
                valuesObject == null
            ) {
                issues += BackupIssue("presets[]", missing = false)
                return@mapNotNull null
            }
            val values =
                valuesObject.values
                    .mapNotNull { (key, value) ->
                        val text = value.asString()
                        if (key.isBlank() || text == null) {
                            issues += BackupIssue("presets[].values", missing = false)
                            null
                        } else {
                            key to text
                        }
                    }.toMap()
            runCatching {
                ConfigPreset(
                    id = id,
                    name = name,
                    values = values,
                    createdAtMillis = createdAt,
                    lastUsedAtMillis = lastUsed,
                )
            }.onFailure { issues += BackupIssue("presets[]", missing = false) }.getOrNull()
        }
    }

    private fun ruleField(
        root: JsonValue.Obj,
        issues: MutableList<BackupIssue>,
    ): List<AutomationRule> {
        val raw = root.values["automationRules"]
        if (raw == null) {
            issues += BackupIssue("automationRules", missing = true)
            return emptyList()
        }
        val array = raw.asArray()
        if (array == null) {
            issues += BackupIssue("automationRules", missing = false)
            return emptyList()
        }
        return array.values.mapNotNull { item ->
            val objectValue = item.asObject()
            if (objectValue == null) {
                issues += BackupIssue("automationRules[]", missing = false)
                return@mapNotNull null
            }
            val id = objectValue.values["id"]?.asString()
            val trigger =
                objectValue.values["trigger"]
                    ?.asString()
                    ?.let { name -> AutomationTrigger.entries.firstOrNull { it.name == name } }
            val action =
                objectValue.values["action"]
                    ?.asString()
                    ?.let { name -> AutomationAction.entries.firstOrNull { it.name == name } }
            val enabled = objectValue.values["enabled"]?.asBoolean()
            val order = objectValue.values["order"]?.asLong()?.toInt()
            val presetId = objectValue.values["presetId"]?.asString()
            if (id.isNullOrBlank() ||
                trigger == null ||
                action == null ||
                enabled == null ||
                order == null ||
                order < 0
            ) {
                issues += BackupIssue("automationRules[]", missing = false)
                return@mapNotNull null
            }
            runCatching {
                AutomationRule(
                    id = id,
                    trigger = trigger,
                    action = action,
                    enabled = enabled,
                    presetId = presetId,
                    order = order,
                )
            }.onFailure { issues += BackupIssue("automationRules[]", missing = false) }.getOrNull()
        }
    }
}

/**
 * 备份导入预览与落盘。
 *
 * 预览展示每个分区「当前值 → 导入值」，让用户在确认前知道会覆盖什么；
 * 写入只触及白名单字段，不触碰设备档案、令牌、日志与断点。
 */
object PreferencesBackupService {
    fun snapshot(
        store: AppSettingsStore,
        presets: ConfigPresetRepository,
        rules: AutomationRuleRepository,
    ): PreferencesBackup {
        val settings = AppSettingsRepository.load(store)
        val prefs = AppPreferenceStore.load(store)
        return PreferencesBackup(
            language = prefs.language,
            theme = prefs.theme,
            reconnectMaxAttempts = settings.reconnectMaxAttempts,
            reconnectMaxDelaySeconds = settings.reconnectMaxDelaySeconds,
            logCapacity = settings.logCapacity,
            exportEntries = settings.exportEntries,
            exportBytesKb = settings.exportBytesKb,
            exportHexChars = settings.exportHexChars,
            presets = presets.list(),
            automationRules = rules.list(),
            onboardingCompleted = AppSettingsRepository.isOnboardingCompleted(store),
        )
    }

    fun plan(
        backup: PreferencesBackup,
        store: AppSettingsStore,
        presets: ConfigPresetRepository,
        rules: AutomationRuleRepository,
    ): List<BackupPlanItem> {
        val current = snapshot(store, presets, rules)
        return listOf(
            planItem(BackupSection.LANGUAGE, current.language.name, backup.language.name),
            planItem(BackupSection.THEME, current.theme.name, backup.theme.name),
            planItem(
                BackupSection.RECONNECT,
                "${current.reconnectMaxAttempts} / ${current.reconnectMaxDelaySeconds}s",
                "${backup.reconnectMaxAttempts} / ${backup.reconnectMaxDelaySeconds}s",
            ),
            planItem(BackupSection.LOG_CAPACITY, current.logCapacity.toString(), backup.logCapacity.toString()),
            planItem(
                BackupSection.EXPORT_DEFAULTS,
                "${current.exportEntries} / ${current.exportBytesKb}K / ${current.exportHexChars}",
                "${backup.exportEntries} / ${backup.exportBytesKb}K / ${backup.exportHexChars}",
            ),
            planItem(
                BackupSection.PRESETS,
                current.presets.size.toString(),
                backup.presets.size.toString(),
            ),
            planItem(
                BackupSection.AUTOMATION_RULES,
                current.automationRules.size.toString(),
                backup.automationRules.size.toString(),
            ),
            planItem(
                BackupSection.ONBOARDING,
                current.onboardingCompleted.toString(),
                backup.onboardingCompleted.toString(),
            ),
        )
    }

    fun apply(
        backup: PreferencesBackup,
        store: AppSettingsStore,
        presets: ConfigPresetRepository,
        rules: AutomationRuleRepository,
    ) {
        AppPreferenceStore.saveLanguage(store, backup.language)
        AppPreferenceStore.saveTheme(store, backup.theme)
        AppSettingsRepository.saveReconnectMaxAttempts(store, backup.reconnectMaxAttempts)
        AppSettingsRepository.saveReconnectMaxDelaySeconds(store, backup.reconnectMaxDelaySeconds)
        AppSettingsRepository.saveLogCapacity(store, backup.logCapacity)
        AppSettingsRepository.saveExportEntries(store, backup.exportEntries)
        AppSettingsRepository.saveExportBytesKb(store, backup.exportBytesKb)
        AppSettingsRepository.saveExportHexChars(store, backup.exportHexChars)
        AppSettingsRepository.setOnboardingCompleted(store, backup.onboardingCompleted)
        presets.replaceAll(backup.presets)
        rules.replaceAll(backup.automationRules)
    }

    private fun planItem(
        section: BackupSection,
        current: String,
        incoming: String,
    ) = BackupPlanItem(
        section = section,
        currentValue = current,
        incomingValue = incoming,
        willChange = current != incoming,
    )
}

/**
 * 恢复出厂设置：清空偏好键、预设与自动化规则。
 *
 * 只操作偏好存储；设备档案、令牌与日志各自在独立存储中，不会被删除。
 */
object PreferencesReset {
    fun clearAll(
        store: AppSettingsStore,
        presets: ConfigPresetRepository,
        rules: AutomationRuleRepository,
    ) {
        SettingsKeys.ALL.forEach { key -> store.write(key, null) }
        presets.clear()
        rules.clear()
    }
}
