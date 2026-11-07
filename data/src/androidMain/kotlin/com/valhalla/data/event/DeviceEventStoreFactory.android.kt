package com.valhalla.data.event

import android.content.Context
import android.content.SharedPreferences
import com.valhalla.core.event.DeviceEventStore
import com.valhalla.data.ble.AndroidBleSupport
import com.valhalla.data.settings.StorageKeys

actual fun createDeviceEventStore(): DeviceEventStore = SharedPreferencesDeviceEventStore(AndroidBleSupport.requireContext())

private class SharedPreferencesDeviceEventStore(
    context: Context,
) : DeviceEventStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(StorageKeys.ANDROID_EVENTS_PREFS, Context.MODE_PRIVATE)

    override fun loadRaw(deviceId: String): String? = preferences.getString(StorageKeys.ANDROID_EVENTS_KEY_PREFIX + deviceId, null)

    override fun saveRaw(
        deviceId: String,
        raw: String,
    ) {
        preferences.edit().putString(StorageKeys.ANDROID_EVENTS_KEY_PREFIX + deviceId, raw).apply()
    }

    override fun clear(deviceId: String) {
        preferences.edit().remove(StorageKeys.ANDROID_EVENTS_KEY_PREFIX + deviceId).apply()
    }
}
