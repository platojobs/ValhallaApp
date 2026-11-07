package com.valhalla.feature.settings

import com.valhalla.core.automation.AutomationAction
import com.valhalla.core.automation.AutomationRule
import com.valhalla.core.automation.AutomationRuleRepository
import com.valhalla.core.automation.AutomationTrigger
import com.valhalla.core.automation.InMemoryAutomationRuleStore
import com.valhalla.core.config.ConfigPreset
import com.valhalla.core.config.ConfigPresetRepository
import com.valhalla.core.config.InMemoryConfigPresetStore
import com.valhalla.core.storage.StorageMigrationStatus
import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.settings.SettingsKeys
import com.valhalla.feature.i18n.AppLanguageMode
import com.valhalla.feature.theme.AppPreferenceStore
import com.valhalla.feature.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreferencesBackupTest {
    private class MemorySettingsStore : AppSettingsStore {
        val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(
            key: String,
            value: String?,
        ) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun repos(): Pair<ConfigPresetRepository, AutomationRuleRepository> {
        val presets = ConfigPresetRepository(InMemoryConfigPresetStore(), clockMillis = { 1L })
        val rules = AutomationRuleRepository(InMemoryAutomationRuleStore(), clockMillis = { 1L })
        return presets to rules
    }

    private fun backup() =
        PreferencesBackup(
            language = AppLanguageMode.ENGLISH,
            theme = ThemeMode.DARK,
            reconnectMaxAttempts = 3,
            reconnectMaxDelaySeconds = 20,
            logCapacity = 300,
            exportEntries = 100,
            exportBytesKb = 256,
            exportHexChars = 32,
            presets =
                listOf(
                    ConfigPreset(
                        id = "p1",
                        name = "Daily",
                        values = mapOf("device_name" to "Valhalla", "led_enabled" to "true"),
                        createdAtMillis = 1,
                        lastUsedAtMillis = 2,
                    ),
                ),
            automationRules =
                listOf(
                    AutomationRule(
                        id = "r1",
                        trigger = AutomationTrigger.CONNECTED,
                        action = AutomationAction.READ_BATTERY,
                        order = 0,
                    ),
                ),
            onboardingCompleted = true,
        )

    @Test
    fun `导出只包含白名单字段且不含敏感项`() {
        val json = backup().toJson()
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
        ).forEach { field ->
            assertTrue(json.contains("\"$field\""), "缺少字段 $field")
        }
        listOf(
            "token",
            "secret",
            "credential",
            "password",
            "deviceProfile",
            "deviceProfileId",
            "packetLog",
            "logs",
            "resume",
            "checkpoint",
            "authToken",
            "authKey",
        ).forEach { sensitive ->
            assertFalse(json.contains(sensitive, ignoreCase = true), "导出不应包含 $sensitive")
        }
        // 明确不导出提醒开关
        assertFalse(json.contains("notifyOtaEnabled"))
        assertFalse(json.contains("notifyLowBattery"))
    }

    @Test
    fun `缺失字段回退默认并逐条提示`() {
        val result =
            PreferencesBackupParser.parse(
                """{"schema":"valhalla.preferences","schemaVersion":1}""",
            )
        assertIs<BackupParseResult.Success>(result)
        assertEquals(StorageMigrationStatus.Migrated(1, 2), result.migrationStatus)
        val parsed = result.backup
        assertEquals(AppLanguageMode.SYSTEM, parsed.language)
        assertEquals(ThemeMode.SYSTEM, parsed.theme)
        assertEquals(AppSettings.DEFAULT_RECONNECT_ATTEMPTS, parsed.reconnectMaxAttempts)
        assertEquals(AppSettings.DEFAULT_LOG_CAPACITY, parsed.logCapacity)
        assertEquals(AppSettings.DEFAULT_EXPORT_ENTRIES, parsed.exportEntries)
        assertTrue(parsed.presets.isEmpty())
        assertTrue(parsed.automationRules.isEmpty())
        assertFalse(parsed.onboardingCompleted)
        val fields = result.issues.map { it.field }
        assertTrue(fields.contains("language"))
        assertTrue(fields.contains("theme"))
        assertTrue(fields.contains("presets"))
        assertTrue(fields.contains("automationRules"))
    }

    @Test
    fun `非法字段与非法预设规则条目被跳过`() {
        val result =
            PreferencesBackupParser.parse(
                """
                {
                  "schema":"valhalla.preferences",
                  "schemaVersion":1,
                  "language":"KLINGON",
                  "theme":"DARK",
                  "reconnectMaxAttempts":99,
                  "logCapacity":12,
                  "exportEntries":100,
                  "exportBytesKb":256,
                  "exportHexChars":32,
                  "onboardingCompleted":true,
                  "presets":[{"id":"p1","name":"Daily","createdAtMillis":1,"lastUsedAtMillis":2,"values":{"device_name":"A"}},
                             {"id":"","name":"bad"}],
                  "automationRules":[{"id":"r1","trigger":"CONNECTED","action":"READ_BATTERY","enabled":true,"order":0},
                                     {"id":"r2","trigger":"UNKNOWN","action":"READ_BATTERY","enabled":true,"order":1}]
                }
                """.trimIndent(),
            )
        assertIs<BackupParseResult.Success>(result)
        assertEquals(AppLanguageMode.SYSTEM, result.backup.language)
        assertEquals(ThemeMode.DARK, result.backup.theme)
        assertEquals(AppSettings.DEFAULT_RECONNECT_ATTEMPTS, result.backup.reconnectMaxAttempts)
        assertEquals(AppSettings.DEFAULT_LOG_CAPACITY, result.backup.logCapacity)
        assertEquals(1, result.backup.presets.size)
        assertEquals(1, result.backup.automationRules.size)
        assertTrue(result.issues.any { it.field == "language" && !it.missing })
        assertTrue(result.issues.any { it.field == "presets[]" })
        assertTrue(result.issues.any { it.field == "automationRules[]" })
    }

    @Test
    fun `版本不支持或结构非法直接失败`() {
        assertIs<BackupParseResult.Failure>(
            PreferencesBackupParser.parse("[]"),
        )
        assertIs<BackupParseResult.Failure>(
            PreferencesBackupParser.parse(
                """{"schema":"other","schemaVersion":1}""",
            ),
        )
        assertIs<BackupParseResult.Failure>(
            PreferencesBackupParser.parse(
                """{"schema":"valhalla.preferences"}""",
            ),
        )
    }

    @Test
    fun `未来版本备份安全回退默认并禁止导入`() {
        val result =
            PreferencesBackupParser.parse(
                """{"schema":"valhalla.preferences","schemaVersion":3}""",
            )
        assertIs<BackupParseResult.Success>(result)
        assertFalse(result.canApply)
        assertTrue(result.warning.orEmpty().contains("高于当前支持版本"))
        assertEquals(StorageMigrationStatus.FutureVersion(3, 2), result.migrationStatus)
        assertEquals(AppLanguageMode.SYSTEM, result.backup.language)
        assertEquals(0, result.backup.presets.size)
    }

    @Test
    fun `导入预览列出覆盖项并写入白名单字段`() {
        val store = MemorySettingsStore()
        val (presets, rules) = repos()
        store.write(SettingsKeys.NOTIFY_LOW_BATTERY_ENABLED, "true")
        val incoming = backup()
        val plan = PreferencesBackupService.plan(incoming, store, presets, rules)
        assertEquals(BackupSection.entries.size, plan.size)
        assertTrue(plan.any { it.willChange })

        PreferencesBackupService.apply(incoming, store, presets, rules)
        assertEquals(AppLanguageMode.ENGLISH, AppPreferenceStore.load(store).language)
        assertEquals(ThemeMode.DARK, AppPreferenceStore.load(store).theme)
        assertEquals("3", store.read(SettingsKeys.RECONNECT_MAX_ATTEMPTS))
        assertEquals("true", store.read(SettingsKeys.ONBOARDING_COMPLETED))
        assertEquals(1, presets.list().size)
        assertEquals(1, rules.list().size)
        // 提醒开关不在白名单内，导入不应覆盖
        assertEquals("true", store.read(SettingsKeys.NOTIFY_LOW_BATTERY_ENABLED))
    }

    @Test
    fun `恢复出厂设置清空偏好与预设`() {
        val store = MemorySettingsStore()
        val (presets, rules) = repos()
        AppPreferenceStore.saveLanguage(store, AppLanguageMode.CHINESE)
        AppPreferenceStore.saveTheme(store, ThemeMode.LIGHT)
        store.write(SettingsKeys.NOTIFY_OTA_ENABLED, "true")
        presets.saveFromValues("Daily", mapOf("device_name" to "A"))
        rules.add(AutomationTrigger.CONNECTED, AutomationAction.READ_BATTERY)

        PreferencesReset.clearAll(store, presets, rules)

        assertNull(store.read(SettingsKeys.LANGUAGE))
        assertNull(store.read(SettingsKeys.THEME))
        assertNull(store.read(SettingsKeys.NOTIFY_OTA_ENABLED))
        assertTrue(presets.list().isEmpty())
        assertTrue(rules.list().isEmpty())
        assertEquals(AppLanguageMode.SYSTEM, AppPreferenceStore.load(store).language)
        assertEquals(ThemeMode.SYSTEM, AppPreferenceStore.load(store).theme)
        assertNotNull(store.values)
    }
}
