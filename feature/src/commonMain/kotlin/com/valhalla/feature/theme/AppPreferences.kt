package com.valhalla.feature.theme

import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.settings.SettingsKeys
import com.valhalla.feature.i18n.AppLanguageMode

/** 本地偏好快照：语言模式与主题模式。 */
data class AppPreferences(
    val language: AppLanguageMode = AppLanguageMode.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

/**
 * 偏好读写（纯逻辑，便于测试）。
 *
 * 存储值用枚举名；遇到未知值回退到「跟随系统」，避免升级后读到脏数据崩界面。
 */
object AppPreferenceStore {
    fun load(store: AppSettingsStore): AppPreferences =
        AppPreferences(
            language = parseLanguage(store.read(SettingsKeys.LANGUAGE)),
            theme = parseTheme(store.read(SettingsKeys.THEME)),
        )

    fun saveLanguage(
        store: AppSettingsStore,
        mode: AppLanguageMode,
    ) {
        store.write(SettingsKeys.LANGUAGE, mode.name)
    }

    fun saveTheme(
        store: AppSettingsStore,
        mode: ThemeMode,
    ) {
        store.write(SettingsKeys.THEME, mode.name)
    }

    fun parseLanguage(raw: String?): AppLanguageMode = AppLanguageMode.entries.firstOrNull { it.name == raw } ?: AppLanguageMode.SYSTEM

    fun parseTheme(raw: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
}
