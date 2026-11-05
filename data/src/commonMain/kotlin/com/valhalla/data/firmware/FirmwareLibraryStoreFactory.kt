package com.valhalla.data.firmware

import com.valhalla.core.firmware.FirmwareLibraryStore

/**
 * 固件库元信息存储（**不缓存固件二进制**）：
 * - Android：SharedPreferences（`valhalla_firmware_library`）；
 * - iOS：NSUserDefaults；
 * - JVM：内存实现（仅测试宿主）。
 */
expect fun createFirmwareLibraryStore(): FirmwareLibraryStore
