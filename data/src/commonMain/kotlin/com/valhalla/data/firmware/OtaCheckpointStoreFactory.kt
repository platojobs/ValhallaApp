package com.valhalla.data.firmware

import com.valhalla.core.firmware.OtaCheckpointStore

/**
 * 创建平台断点存储：
 * - Android：SharedPreferences（Application Context 由 `AndroidBleSupport.init` 提供）；
 * - iOS：NSUserDefaults；
 * - JVM：内存实现（仅用于测试宿主）。
 */
expect fun createOtaCheckpointStore(): OtaCheckpointStore
