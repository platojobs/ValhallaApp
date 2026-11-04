package com.valhalla.data.settings

/**
 * 轻量本地偏好存储（语言、主题）。
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
}
