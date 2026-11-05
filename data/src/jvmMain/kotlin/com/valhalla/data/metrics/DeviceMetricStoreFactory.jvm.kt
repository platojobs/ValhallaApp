package com.valhalla.data.metrics

import com.valhalla.core.metrics.DeviceMetricStore
import com.valhalla.core.metrics.InMemoryDeviceMetricStore

actual fun createDeviceMetricStore(): DeviceMetricStore = InMemoryDeviceMetricStore()
