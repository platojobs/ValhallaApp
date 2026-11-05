package com.valhalla.data.metrics

import com.valhalla.core.metrics.DeviceMetricStore
import com.valhalla.data.settings.StorageKeys
import platform.Foundation.NSUserDefaults

actual fun createDeviceMetricStore(): DeviceMetricStore = NSUserDefaultsDeviceMetricStore()

private class NSUserDefaultsDeviceMetricStore : DeviceMetricStore {

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    override fun loadRaw(deviceId: String): String? =
        defaults.stringForKey(StorageKeys.IOS_METRICS_KEY_PREFIX + deviceId)

    override fun saveRaw(deviceId: String, raw: String) {
        defaults.setObject(raw, forKey = StorageKeys.IOS_METRICS_KEY_PREFIX + deviceId)
    }

    override fun clear(deviceId: String) {
        defaults.removeObjectForKey(StorageKeys.IOS_METRICS_KEY_PREFIX + deviceId)
    }
}
