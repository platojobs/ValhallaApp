package com.valhalla.data.firmware

import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.firmware.OtaResumePointCodec
import com.valhalla.data.settings.StorageKeys
import platform.Foundation.NSUserDefaults

actual fun createOtaCheckpointStore(): OtaCheckpointStore = NSUserDefaultsOtaCheckpointStore()

private class NSUserDefaultsOtaCheckpointStore : OtaCheckpointStore {

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    override fun load(): OtaResumePoint? =
        OtaResumePointCodec.decode(defaults.stringForKey(StorageKeys.IOS_OTA_KEY))

    override fun save(point: OtaResumePoint) {
        defaults.setObject(OtaResumePointCodec.encode(point), forKey = StorageKeys.IOS_OTA_KEY)
    }

    override fun clear() {
        defaults.removeObjectForKey(StorageKeys.IOS_OTA_KEY)
    }
}
