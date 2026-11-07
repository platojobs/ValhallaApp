package com.valhalla.data.profile

import android.content.Context
import android.content.SharedPreferences
import com.valhalla.core.profile.DeviceProfileStore
import com.valhalla.data.ble.AndroidBleSupport
import com.valhalla.data.settings.StorageKeys

actual fun createDeviceProfileStore(): DeviceProfileStore = SharedPreferencesDeviceProfileStore(AndroidBleSupport.requireContext())

private class SharedPreferencesDeviceProfileStore(
    context: Context,
) : DeviceProfileStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(StorageKeys.ANDROID_PROFILES_PREFS, Context.MODE_PRIVATE)

    override fun loadRaw(): String? = preferences.getString(StorageKeys.ANDROID_PROFILES_KEY, null)

    override fun saveRaw(raw: String) {
        preferences.edit().putString(StorageKeys.ANDROID_PROFILES_KEY, raw).apply()
    }

    override fun clear() {
        preferences.edit().remove(StorageKeys.ANDROID_PROFILES_KEY).apply()
    }
}
