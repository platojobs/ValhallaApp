package com.valhalla.data.metrics

import android.content.Context
import android.content.SharedPreferences
import com.valhalla.core.metrics.DeviceMetricStore
import com.valhalla.data.ble.AndroidBleSupport
import com.valhalla.data.settings.StorageKeys

actual fun createDeviceMetricStore(): DeviceMetricStore =
    SharedPreferencesDeviceMetricStore(AndroidBleSupport.requireContext())

private class SharedPreferencesDeviceMetricStore(context: Context) : DeviceMetricStore {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(StorageKeys.ANDROID_METRICS_PREFS, Context.MODE_PRIVATE)

    override fun loadRaw(deviceId: String): String? =
        preferences.getString(StorageKeys.ANDROID_METRICS_KEY_PREFIX + deviceId, null)

    override fun saveRaw(deviceId: String, raw: String) {
        preferences.edit().putString(StorageKeys.ANDROID_METRICS_KEY_PREFIX + deviceId, raw).apply()
    }

    override fun clear(deviceId: String) {
        preferences.edit().remove(StorageKeys.ANDROID_METRICS_KEY_PREFIX + deviceId).apply()
    }
}
