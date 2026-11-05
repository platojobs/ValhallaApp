package com.valhalla.data.metrics

import com.valhalla.core.metrics.DeviceMetricStore

/**
 * 设备指标采样存储（每设备一份，跨进程可读）：
 * - Android：SharedPreferences（`valhalla_device_metrics`，键为 `metrics.<deviceId>`）；
 * - iOS：NSUserDefaults（键为 `valhalla.metrics.<deviceId>`）；
 * - JVM：内存实现（仅测试宿主）。
 */
expect fun createDeviceMetricStore(): DeviceMetricStore
