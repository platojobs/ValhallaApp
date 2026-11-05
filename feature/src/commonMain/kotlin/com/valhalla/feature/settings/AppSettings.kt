package com.valhalla.feature.settings

import com.valhalla.core.logging.PacketLogExportOptions
import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.settings.SettingsKeys

/**
 * 用户可调的运行参数（全部保存在平台偏好存储）。
 *
 * 边界来自产品定义，非法输入在写入时拒绝；已存储的值损坏或越界时读回默认值，
 * 保证升级旧版本或手改偏好不会让应用进入不可用状态。
 */
data class AppSettings(
    val reconnectMaxAttempts: Int = DEFAULT_RECONNECT_ATTEMPTS,
    val reconnectMaxDelaySeconds: Int = DEFAULT_RECONNECT_MAX_DELAY_SECONDS,
    val logCapacity: Int = DEFAULT_LOG_CAPACITY,
    val exportEntries: Int = DEFAULT_EXPORT_ENTRIES,
    val exportBytesKb: Int = DEFAULT_EXPORT_BYTES_KB,
    val exportHexChars: Int = DEFAULT_EXPORT_HEX_CHARS,
) {

    /** 转换为导出选项；构造前再夹取一次，避免边界外的持久化值导致构造异常。 */
    fun exportOptions(): PacketLogExportOptions = PacketLogExportOptions(
        maxEntries = exportEntries.coerceIn(
            PacketLogExportOptions.MIN_ENTRIES,
            PacketLogExportOptions.MAX_ENTRIES,
        ),
        maxBytes = (exportBytesKb * 1024).coerceIn(
            PacketLogExportOptions.MIN_BYTES,
            PacketLogExportOptions.MAX_BYTES,
        ),
        maxHexCharsPerEntry = exportHexChars.coerceIn(1, PacketLogExportOptions.MAX_HEX_CHARS),
    )

    companion object {
        const val DEFAULT_RECONNECT_ATTEMPTS = 5
        const val MIN_RECONNECT_ATTEMPTS = 1
        const val MAX_RECONNECT_ATTEMPTS = 5

        const val DEFAULT_RECONNECT_MAX_DELAY_SECONDS = 30
        const val MIN_RECONNECT_MAX_DELAY_SECONDS = 5
        const val MAX_RECONNECT_MAX_DELAY_SECONDS = 60

        const val DEFAULT_LOG_CAPACITY = 500
        const val MIN_LOG_CAPACITY = 100
        const val MAX_LOG_CAPACITY = 1000

        val DEFAULT_EXPORT_ENTRIES = PacketLogExportOptions.DEFAULT.maxEntries
        val DEFAULT_EXPORT_BYTES_KB = PacketLogExportOptions.DEFAULT.maxBytes / 1024
        val DEFAULT_EXPORT_HEX_CHARS = PacketLogExportOptions.DEFAULT.maxHexCharsPerEntry

        const val MIN_EXPORT_BYTES_KB = 1
        const val MAX_EXPORT_BYTES_KB = PacketLogExportOptions.MAX_BYTES / 1024

        /** 默认值：5 次重连、30 秒封顶、500 条日志、200 条导出 / 512 KiB。 */
        val DEFAULTS = AppSettings()

        fun isValidReconnectAttempts(value: Int): Boolean =
            value in MIN_RECONNECT_ATTEMPTS..MAX_RECONNECT_ATTEMPTS

        fun isValidReconnectMaxDelaySeconds(value: Int): Boolean =
            value in MIN_RECONNECT_MAX_DELAY_SECONDS..MAX_RECONNECT_MAX_DELAY_SECONDS

        fun isValidLogCapacity(value: Int): Boolean = value in MIN_LOG_CAPACITY..MAX_LOG_CAPACITY

        fun isValidExportEntries(value: Int): Boolean =
            value in PacketLogExportOptions.MIN_ENTRIES..PacketLogExportOptions.MAX_ENTRIES

        fun isValidExportBytesKb(value: Int): Boolean = value in MIN_EXPORT_BYTES_KB..MAX_EXPORT_BYTES_KB

        fun isValidExportHexChars(value: Int): Boolean = value in 1..PacketLogExportOptions.MAX_HEX_CHARS
    }
}

/**
 * 偏好读写。
 *
 * 写入接口返回 false 表示值越界/非数字，调用方应保留旧值；
 * 读取接口对损坏值一律回退默认值。
 */
object AppSettingsRepository {

    fun load(store: AppSettingsStore): AppSettings {
        val defaults = AppSettings.DEFAULTS
        return AppSettings(
            reconnectMaxAttempts = storedInt(
                store = store,
                key = SettingsKeys.RECONNECT_MAX_ATTEMPTS,
                default = defaults.reconnectMaxAttempts,
                valid = { AppSettings.isValidReconnectAttempts(it) },
            ),
            reconnectMaxDelaySeconds = storedInt(
                store = store,
                key = SettingsKeys.RECONNECT_MAX_DELAY_SECONDS,
                default = defaults.reconnectMaxDelaySeconds,
                valid = { AppSettings.isValidReconnectMaxDelaySeconds(it) },
            ),
            logCapacity = storedInt(
                store = store,
                key = SettingsKeys.LOG_CAPACITY,
                default = defaults.logCapacity,
                valid = { AppSettings.isValidLogCapacity(it) },
            ),
            exportEntries = storedInt(
                store = store,
                key = SettingsKeys.EXPORT_DEFAULT_ENTRIES,
                default = defaults.exportEntries,
                valid = { AppSettings.isValidExportEntries(it) },
            ),
            exportBytesKb = storedInt(
                store = store,
                key = SettingsKeys.EXPORT_DEFAULT_BYTES_KB,
                default = defaults.exportBytesKb,
                valid = { AppSettings.isValidExportBytesKb(it) },
            ),
            exportHexChars = storedInt(
                store = store,
                key = SettingsKeys.EXPORT_DEFAULT_HEX_CHARS,
                default = defaults.exportHexChars,
                valid = { AppSettings.isValidExportHexChars(it) },
            ),
        )
    }

    fun saveReconnectMaxAttempts(store: AppSettingsStore, value: Int): Boolean =
        saveInt(store, SettingsKeys.RECONNECT_MAX_ATTEMPTS, value) {
            AppSettings.isValidReconnectAttempts(it)
        }

    fun saveReconnectMaxDelaySeconds(store: AppSettingsStore, value: Int): Boolean =
        saveInt(store, SettingsKeys.RECONNECT_MAX_DELAY_SECONDS, value) {
            AppSettings.isValidReconnectMaxDelaySeconds(it)
        }

    fun saveLogCapacity(store: AppSettingsStore, value: Int): Boolean =
        saveInt(store, SettingsKeys.LOG_CAPACITY, value) { AppSettings.isValidLogCapacity(it) }

    fun saveExportEntries(store: AppSettingsStore, value: Int): Boolean =
        saveInt(store, SettingsKeys.EXPORT_DEFAULT_ENTRIES, value) {
            AppSettings.isValidExportEntries(it)
        }

    fun saveExportBytesKb(store: AppSettingsStore, value: Int): Boolean =
        saveInt(store, SettingsKeys.EXPORT_DEFAULT_BYTES_KB, value) {
            AppSettings.isValidExportBytesKb(it)
        }

    fun saveExportHexChars(store: AppSettingsStore, value: Int): Boolean =
        saveInt(store, SettingsKeys.EXPORT_DEFAULT_HEX_CHARS, value) {
            AppSettings.isValidExportHexChars(it)
        }

    /** 恢复全部默认值（语言与主题不受影响）。 */
    fun resetToDefaults(store: AppSettingsStore) {
        listOf(
            SettingsKeys.RECONNECT_MAX_ATTEMPTS,
            SettingsKeys.RECONNECT_MAX_DELAY_SECONDS,
            SettingsKeys.LOG_CAPACITY,
            SettingsKeys.EXPORT_DEFAULT_ENTRIES,
            SettingsKeys.EXPORT_DEFAULT_BYTES_KB,
            SettingsKeys.EXPORT_DEFAULT_HEX_CHARS,
        ).forEach { store.write(it, null) }
    }

    fun isOnboardingCompleted(store: AppSettingsStore): Boolean =
        store.read(SettingsKeys.ONBOARDING_COMPLETED)?.toBooleanStrictOrNull() ?: false

    fun setOnboardingCompleted(store: AppSettingsStore, completed: Boolean) {
        store.write(SettingsKeys.ONBOARDING_COMPLETED, completed.toString())
    }

    private fun storedInt(
        store: AppSettingsStore,
        key: String,
        default: Int,
        valid: (Int) -> Boolean,
    ): Int {
        val raw = store.read(key)?.trim()?.toIntOrNull() ?: return default
        return if (valid(raw)) raw else default
    }

    private fun saveInt(
        store: AppSettingsStore,
        key: String,
        value: Int,
        valid: (Int) -> Boolean,
    ): Boolean {
        if (!valid(value)) return false
        store.write(key, value.toString())
        return true
    }
}
