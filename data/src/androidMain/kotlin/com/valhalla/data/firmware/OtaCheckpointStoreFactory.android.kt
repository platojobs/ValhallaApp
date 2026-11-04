package com.valhalla.data.firmware

import android.content.Context
import android.content.SharedPreferences
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.firmware.OtaResumePointCodec
import com.valhalla.data.ble.AndroidBleSupport
import com.valhalla.data.settings.StorageKeys

actual fun createOtaCheckpointStore(): OtaCheckpointStore =
    SharedPreferencesOtaCheckpointStore(AndroidBleSupport.requireContext())

private class SharedPreferencesOtaCheckpointStore(context: Context) : OtaCheckpointStore {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(StorageKeys.ANDROID_OTA_PREFS, Context.MODE_PRIVATE)

    override fun load(): OtaResumePoint? =
        OtaResumePointCodec.decode(preferences.getString(StorageKeys.ANDROID_OTA_KEY, null))

    override fun save(point: OtaResumePoint) {
        preferences.edit()
            .putString(StorageKeys.ANDROID_OTA_KEY, OtaResumePointCodec.encode(point))
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(StorageKeys.ANDROID_OTA_KEY).apply()
    }
}
