package com.valhalla.data.settings

import platform.Foundation.NSUserDefaults

actual fun createAppSettingsStore(): AppSettingsStore = NSUserDefaultsAppSettingsStore()

actual fun currentStoragePlatform(): StoragePlatform = StoragePlatform.IOS

private class NSUserDefaultsAppSettingsStore : AppSettingsStore {

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    private fun storageKey(key: String): String = StorageKeys.IOS_SETTINGS_PREFIX + key

    override fun read(key: String): String? = defaults.stringForKey(storageKey(key))

    override fun write(key: String, value: String?) {
        val storageKey = storageKey(key)
        if (value == null) {
            defaults.removeObjectForKey(storageKey)
        } else {
            defaults.setObject(value, forKey = storageKey)
        }
    }
}
