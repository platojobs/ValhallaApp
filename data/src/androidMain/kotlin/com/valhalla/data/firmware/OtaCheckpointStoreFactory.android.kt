package com.valhalla.data.firmware

import android.content.Context
import android.content.SharedPreferences
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.firmware.OtaResumePointCodec
import com.valhalla.data.ble.AndroidBleSupport

actual fun createOtaCheckpointStore(): OtaCheckpointStore =
    SharedPreferencesOtaCheckpointStore(AndroidBleSupport.requireContext())

private class SharedPreferencesOtaCheckpointStore(context: Context) : OtaCheckpointStore {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): OtaResumePoint? =
        OtaResumePointCodec.decode(preferences.getString(KEY_RESUME_POINT, null))

    override fun save(point: OtaResumePoint) {
        preferences.edit().putString(KEY_RESUME_POINT, OtaResumePointCodec.encode(point)).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_RESUME_POINT).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "valhalla_ota"
        const val KEY_RESUME_POINT = "resume_point"
    }
}
