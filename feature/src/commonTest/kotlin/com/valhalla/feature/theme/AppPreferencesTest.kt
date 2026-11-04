package com.valhalla.feature.theme

import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.settings.SettingsKeys
import com.valhalla.feature.i18n.AppLanguageMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppPreferencesTest {

    private class FakeSettingsStore : AppSettingsStore {
        val values = mutableMapOf<String, String?>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String?) {
            values[key] = value
        }
    }

    @Test
    fun `主题跟随系统与手动覆盖`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemDark = false))
    }

    @Test
    fun `首次启动使用跟随系统`() {
        val preferences = AppPreferenceStore.load(FakeSettingsStore())

        assertEquals(AppLanguageMode.SYSTEM, preferences.language)
        assertEquals(ThemeMode.SYSTEM, preferences.theme)
    }

    @Test
    fun `读写语言与主题偏好`() {
        val store = FakeSettingsStore()

        AppPreferenceStore.saveLanguage(store, AppLanguageMode.ENGLISH)
        AppPreferenceStore.saveTheme(store, ThemeMode.DARK)

        assertEquals("ENGLISH", store.values[SettingsKeys.LANGUAGE])
        assertEquals("DARK", store.values[SettingsKeys.THEME])

        val loaded = AppPreferenceStore.load(store)
        assertEquals(AppLanguageMode.ENGLISH, loaded.language)
        assertEquals(ThemeMode.DARK, loaded.theme)
    }

    @Test
    fun `未知偏好值回退跟随系统`() {
        val store = FakeSettingsStore()
        store.values[SettingsKeys.LANGUAGE] = "klingon"
        store.values[SettingsKeys.THEME] = "sepia"

        val loaded = AppPreferenceStore.load(store)

        assertEquals(AppLanguageMode.SYSTEM, loaded.language)
        assertEquals(ThemeMode.SYSTEM, loaded.theme)
    }

    @Test
    fun `清空偏好后回退默认值`() {
        val store = FakeSettingsStore()
        AppPreferenceStore.saveLanguage(store, AppLanguageMode.CHINESE)
        AppPreferenceStore.saveTheme(store, ThemeMode.LIGHT)
        store.values.clear()

        val loaded = AppPreferenceStore.load(store)
        assertEquals(AppLanguageMode.SYSTEM, loaded.language)
        assertEquals(ThemeMode.SYSTEM, loaded.theme)
        assertNull(store.values[SettingsKeys.LANGUAGE])
    }
}
