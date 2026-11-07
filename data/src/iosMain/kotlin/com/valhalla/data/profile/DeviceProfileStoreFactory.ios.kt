package com.valhalla.data.profile

import com.valhalla.core.profile.DeviceProfileStore
import com.valhalla.data.settings.StorageKeys
import platform.Foundation.NSUserDefaults

actual fun createDeviceProfileStore(): DeviceProfileStore = NSUserDefaultsDeviceProfileStore()

private class NSUserDefaultsDeviceProfileStore : DeviceProfileStore {
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    override fun loadRaw(): String? = defaults.stringForKey(StorageKeys.IOS_PROFILES_KEY)

    override fun saveRaw(raw: String) {
        defaults.setObject(raw, forKey = StorageKeys.IOS_PROFILES_KEY)
    }

    override fun clear() {
        defaults.removeObjectForKey(StorageKeys.IOS_PROFILES_KEY)
    }
}
