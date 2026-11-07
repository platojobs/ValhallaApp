package com.valhalla.data.firmware

import android.content.Context
import android.content.SharedPreferences
import com.valhalla.core.firmware.FirmwareLibraryStore
import com.valhalla.data.ble.AndroidBleSupport
import com.valhalla.data.settings.StorageKeys

actual fun createFirmwareLibraryStore(): FirmwareLibraryStore = SharedPreferencesFirmwareLibraryStore(AndroidBleSupport.requireContext())

private class SharedPreferencesFirmwareLibraryStore(
    context: Context,
) : FirmwareLibraryStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(StorageKeys.ANDROID_FIRMWARE_PREFS, Context.MODE_PRIVATE)

    override fun loadRaw(): String? = preferences.getString(StorageKeys.ANDROID_FIRMWARE_KEY, null)

    override fun saveRaw(raw: String) {
        preferences.edit().putString(StorageKeys.ANDROID_FIRMWARE_KEY, raw).apply()
    }

    override fun clear() {
        preferences.edit().remove(StorageKeys.ANDROID_FIRMWARE_KEY).apply()
    }
}
