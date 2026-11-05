package com.valhalla.data.event

import com.valhalla.core.event.DeviceEventStore
import com.valhalla.core.event.InMemoryDeviceEventStore

actual fun createDeviceEventStore(): DeviceEventStore = InMemoryDeviceEventStore()
