package com.valhalla.data.profile

import com.valhalla.core.profile.DeviceProfileStore

/**
 * 创建设备档案存储：
 * - Android：SharedPreferences（Application Context 由 `AndroidBleSupport.init` 提供）；
 * - iOS：NSUserDefaults；
 * - JVM：内存实现（仅用于测试宿主，进程退出即丢失）。
 */
expect fun createDeviceProfileStore(): DeviceProfileStore
