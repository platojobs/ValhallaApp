package com.valhalla.data.profile

import com.valhalla.core.profile.DeviceProfileStore
import platform.Foundation.NSUserDefaults

actual fun createDeviceProfileStore(): DeviceProfileStore = NSUserDefaultsDeviceProfileStore()

private class NSUserDefaultsDeviceProfileStore : DeviceProfileStore {

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    override fun loadRaw(): String? = defaults.stringForKey(KEY_PROFILES)

    override fun saveRaw(raw: String) {
        defaults.setObject(raw, forKey = KEY_PROFILES)
    }

    override fun clear() {
        defaults.removeObjectForKey(KEY_PROFILES)
    }

    private companion object {
        const val KEY_PROFILES = "valhalla.profiles"
    }
}
