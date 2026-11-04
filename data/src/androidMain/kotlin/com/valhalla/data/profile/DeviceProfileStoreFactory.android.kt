package com.valhalla.data.profile

import android.content.Context
import android.content.SharedPreferences
import com.valhalla.core.profile.DeviceProfileStore
import com.valhalla.data.ble.AndroidBleSupport

actual fun createDeviceProfileStore(): DeviceProfileStore =
    SharedPreferencesDeviceProfileStore(AndroidBleSupport.requireContext())

private class SharedPreferencesDeviceProfileStore(context: Context) : DeviceProfileStore {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun loadRaw(): String? = preferences.getString(KEY_PROFILES, null)

    override fun saveRaw(raw: String) {
        preferences.edit().putString(KEY_PROFILES, raw).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_PROFILES).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "valhalla_profiles"
        const val KEY_PROFILES = "profiles"
    }
}
