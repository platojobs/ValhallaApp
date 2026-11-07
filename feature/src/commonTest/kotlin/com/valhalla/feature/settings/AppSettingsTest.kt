package com.valhalla.feature.settings

import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.settings.SettingsKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeSettingsStore : AppSettingsStore {
    val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class AppSettingsTest {
    @Test
    fun `默认值与产品定义一致`() {
        val settings = AppSettings.DEFAULTS
        assertEquals(5, settings.reconnectMaxAttempts)
        assertEquals(30, settings.reconnectMaxDelaySeconds)
        assertEquals(500, settings.logCapacity)
        assertEquals(200, settings.exportEntries)
        assertEquals(512, settings.exportBytesKb)
        assertEquals(4096, settings.exportHexChars)
    }

    @Test
    fun `写入合法值后读回一致`() {
        val store = FakeSettingsStore()
        assertTrue(AppSettingsRepository.saveReconnectMaxAttempts(store, 3))
        assertTrue(AppSettingsRepository.saveReconnectMaxDelaySeconds(store, 45))
        assertTrue(AppSettingsRepository.saveLogCapacity(store, 800))
        assertTrue(AppSettingsRepository.saveExportEntries(store, 100))
        assertTrue(AppSettingsRepository.saveExportBytesKb(store, 64))
        assertTrue(AppSettingsRepository.saveExportHexChars(store, 128))

        val loaded = AppSettingsRepository.load(store)
        assertEquals(3, loaded.reconnectMaxAttempts)
        assertEquals(45, loaded.reconnectMaxDelaySeconds)
        assertEquals(800, loaded.logCapacity)
        assertEquals(100, loaded.exportEntries)
        assertEquals(64, loaded.exportBytesKb)
        assertEquals(128, loaded.exportHexChars)
    }

    @Test
    fun `越界输入被拒绝且不覆盖旧值`() {
        val store = FakeSettingsStore()
        AppSettingsRepository.saveReconnectMaxAttempts(store, 3)

        assertFalse(AppSettingsRepository.saveReconnectMaxAttempts(store, 0))
        assertFalse(AppSettingsRepository.saveReconnectMaxAttempts(store, 6))
        assertFalse(AppSettingsRepository.saveReconnectMaxDelaySeconds(store, 4))
        assertFalse(AppSettingsRepository.saveReconnectMaxDelaySeconds(store, 61))
        assertFalse(AppSettingsRepository.saveLogCapacity(store, 99))
        assertFalse(AppSettingsRepository.saveLogCapacity(store, 1001))
        assertFalse(AppSettingsRepository.saveExportBytesKb(store, 0))
        assertFalse(AppSettingsRepository.saveExportHexChars(store, 0))

        assertEquals(3, AppSettingsRepository.load(store).reconnectMaxAttempts)
    }

    @Test
    fun `损坏或越界的持久化值回退默认`() {
        val store = FakeSettingsStore()
        store.write(SettingsKeys.RECONNECT_MAX_ATTEMPTS, "abc")
        store.write(SettingsKeys.RECONNECT_MAX_DELAY_SECONDS, "999")
        store.write(SettingsKeys.LOG_CAPACITY, "-1")
        store.write(SettingsKeys.EXPORT_DEFAULT_ENTRIES, "")
        store.write(SettingsKeys.EXPORT_DEFAULT_BYTES_KB, "99999999")
        store.write(SettingsKeys.EXPORT_DEFAULT_HEX_CHARS, "0")

        val loaded = AppSettingsRepository.load(store)
        assertEquals(AppSettings.DEFAULTS, loaded)
    }

    @Test
    fun `导出选项按设置生成且夹取在协议边界内`() {
        val store = FakeSettingsStore()
        AppSettingsRepository.saveExportEntries(store, 900)
        AppSettingsRepository.saveExportBytesKb(store, 64)
        AppSettingsRepository.saveExportHexChars(store, 512)

        val options = AppSettingsRepository.load(store).exportOptions()
        assertEquals(900, options.maxEntries)
        assertEquals(64 * 1024, options.maxBytes)
        assertEquals(512, options.maxHexCharsPerEntry)
    }

    @Test
    fun `恢复默认只清除运行参数不碰语言主题与引导`() {
        val store = FakeSettingsStore()
        store.write(SettingsKeys.LANGUAGE, "en")
        store.write(SettingsKeys.THEME, "dark")
        AppSettingsRepository.setOnboardingCompleted(store, true)
        AppSettingsRepository.saveLogCapacity(store, 800)

        AppSettingsRepository.resetToDefaults(store)

        assertEquals("en", store.read(SettingsKeys.LANGUAGE))
        assertEquals("dark", store.read(SettingsKeys.THEME))
        assertTrue(AppSettingsRepository.isOnboardingCompleted(store))
        assertEquals(AppSettings.DEFAULTS, AppSettingsRepository.load(store))
    }

    @Test
    fun `引导完成标记的读写与损坏值处理`() {
        val store = FakeSettingsStore()
        assertFalse(AppSettingsRepository.isOnboardingCompleted(store))

        AppSettingsRepository.setOnboardingCompleted(store, true)
        assertTrue(AppSettingsRepository.isOnboardingCompleted(store))

        store.write(SettingsKeys.ONBOARDING_COMPLETED, "not-a-bool")
        assertFalse(AppSettingsRepository.isOnboardingCompleted(store))
    }
}
