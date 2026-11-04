package com.valhalla.data.profile

import com.valhalla.core.profile.DeviceProfileStore
import com.valhalla.core.profile.InMemoryDeviceProfileStore

actual fun createDeviceProfileStore(): DeviceProfileStore = InMemoryDeviceProfileStore()
