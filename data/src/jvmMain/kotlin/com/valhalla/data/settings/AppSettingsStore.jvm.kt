package com.valhalla.data.settings

actual fun createAppSettingsStore(): AppSettingsStore = InMemoryAppSettingsStore()

actual fun currentStoragePlatform(): StoragePlatform = StoragePlatform.JVM

/** JVM 无持久化偏好存储，仅用于测试宿主。 */
class InMemoryAppSettingsStore : AppSettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
