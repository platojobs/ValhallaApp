package com.valhalla.data.event

import com.valhalla.core.event.DeviceEventStore
import com.valhalla.data.settings.StorageKeys
import platform.Foundation.NSUserDefaults

actual fun createDeviceEventStore(): DeviceEventStore = NSUserDefaultsDeviceEventStore()

private class NSUserDefaultsDeviceEventStore : DeviceEventStore {

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    override fun loadRaw(deviceId: String): String? =
        defaults.stringForKey(StorageKeys.IOS_EVENTS_KEY_PREFIX + deviceId)

    override fun saveRaw(deviceId: String, raw: String) {
        defaults.setObject(raw, forKey = StorageKeys.IOS_EVENTS_KEY_PREFIX + deviceId)
    }

    override fun clear(deviceId: String) {
        defaults.removeObjectForKey(StorageKeys.IOS_EVENTS_KEY_PREFIX + deviceId)
    }
}
