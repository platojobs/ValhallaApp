package com.valhalla.data.settings

/**
 * 轻量本地偏好存储（语言、主题、重连策略、日志容量、导出默认值与引导状态）。
 *
 * - Android：私有 SharedPreferences（`valhalla_settings`）；
 * - iOS：NSUserDefaults；
 * - JVM：内存实现（仅测试宿主，进程退出即丢失）。
 *
 * 这里只存非敏感配置；令牌仍走 [com.valhalla.data.security.createTokenStore]。
 */
interface AppSettingsStore {

    fun read(key: String): String?

    fun write(key: String, value: String?)
}

expect fun createAppSettingsStore(): AppSettingsStore

/** 偏好键名，隐私说明与实现共用同一常量，避免文案与真实存储漂移。 */
object SettingsKeys {
    const val LANGUAGE = "language"
    const val THEME = "theme"

    /** 自动重连最大次数，取值 1..5。 */
    const val RECONNECT_MAX_ATTEMPTS = "reconnect_max_attempts"

    /** 自动重连退避上限（秒），取值 5..60。 */
    const val RECONNECT_MAX_DELAY_SECONDS = "reconnect_max_delay_seconds"

    /** 报文日志环形缓冲容量，取值 100..1000。 */
    const val LOG_CAPACITY = "log_capacity"

    /** 导出默认条数（与导出页的取值边界一致）。 */
    const val EXPORT_DEFAULT_ENTRIES = "export_default_entries"

    /** 导出默认文本上限（KiB）。 */
    const val EXPORT_DEFAULT_BYTES_KB = "export_default_bytes_kb"

    /** 导出默认单条 HEX 字符上限。 */
    const val EXPORT_DEFAULT_HEX_CHARS = "export_default_hex_chars"

    /** 首次使用引导是否已完成（true/false）。 */
    const val ONBOARDING_COMPLETED = "onboarding_completed"

    /** 全部偏好键，供隐私页与存储清单展示。 */
    val ALL: List<String> = listOf(
        LANGUAGE,
        THEME,
        RECONNECT_MAX_ATTEMPTS,
        RECONNECT_MAX_DELAY_SECONDS,
        LOG_CAPACITY,
        EXPORT_DEFAULT_ENTRIES,
        EXPORT_DEFAULT_BYTES_KB,
        EXPORT_DEFAULT_HEX_CHARS,
        ONBOARDING_COMPLETED,
    )
}
