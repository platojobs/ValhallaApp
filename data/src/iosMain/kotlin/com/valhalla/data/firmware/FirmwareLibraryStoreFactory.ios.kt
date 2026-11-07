package com.valhalla.data.firmware

import com.valhalla.core.firmware.FirmwareLibraryStore
import com.valhalla.data.settings.StorageKeys
import platform.Foundation.NSUserDefaults

actual fun createFirmwareLibraryStore(): FirmwareLibraryStore = NSUserDefaultsFirmwareLibraryStore()

private class NSUserDefaultsFirmwareLibraryStore : FirmwareLibraryStore {
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    override fun loadRaw(): String? = defaults.stringForKey(StorageKeys.IOS_FIRMWARE_KEY)

    override fun saveRaw(raw: String) {
        defaults.setObject(raw, forKey = StorageKeys.IOS_FIRMWARE_KEY)
    }

    override fun clear() {
        defaults.removeObjectForKey(StorageKeys.IOS_FIRMWARE_KEY)
    }
}
