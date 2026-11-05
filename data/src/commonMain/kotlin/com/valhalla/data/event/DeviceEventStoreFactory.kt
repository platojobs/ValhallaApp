package com.valhalla.data.event

import com.valhalla.core.event.DeviceEventStore

/**
 * 设备事件时间线存储（每设备一份，跨进程可读）：
 * - Android：SharedPreferences（`valhalla_device_events`，键为 `device.<deviceId>`）；
 * - iOS：NSUserDefaults（键为 `valhalla.events.<deviceId>`）；
 * - JVM：内存实现（仅测试宿主）。
 */
expect fun createDeviceEventStore(): DeviceEventStore
