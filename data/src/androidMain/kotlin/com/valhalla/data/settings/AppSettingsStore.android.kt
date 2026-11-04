package com.valhalla.data.settings

import android.content.Context
import com.valhalla.data.ble.AndroidBleSupport

actual fun createAppSettingsStore(): AppSettingsStore =
    SharedPreferencesAppSettingsStore(AndroidBleSupport.requireContext())

actual fun currentStoragePlatform(): StoragePlatform = StoragePlatform.ANDROID

private class SharedPreferencesAppSettingsStore(context: Context) : AppSettingsStore {

    private val preferences = context.getSharedPreferences(
        StorageKeys.ANDROID_SETTINGS_PREFS,
        Context.MODE_PRIVATE,
    )

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String?) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
