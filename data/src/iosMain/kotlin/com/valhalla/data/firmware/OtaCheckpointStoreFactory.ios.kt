package com.valhalla.data.firmware

import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.firmware.OtaResumePointCodec
import platform.Foundation.NSUserDefaults

actual fun createOtaCheckpointStore(): OtaCheckpointStore = NSUserDefaultsOtaCheckpointStore()

private class NSUserDefaultsOtaCheckpointStore : OtaCheckpointStore {

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    override fun load(): OtaResumePoint? =
        OtaResumePointCodec.decode(defaults.stringForKey(KEY_RESUME_POINT))

    override fun save(point: OtaResumePoint) {
        defaults.setObject(OtaResumePointCodec.encode(point), forKey = KEY_RESUME_POINT)
    }

    override fun clear() {
        defaults.removeObjectForKey(KEY_RESUME_POINT)
    }

    private companion object {
        const val KEY_RESUME_POINT = "valhalla.ota.resume_point"
    }
}
