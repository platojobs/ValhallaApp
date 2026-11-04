package com.valhalla.data.ble

/**
 * JVM 目标只用于运行共享层单元测试，没有真实蓝牙硬件，因此不提供实现。
 * 需要设备能力时请使用 Android / iOS 目标。
 */
actual fun createBleClient(): BleClient =
    throw UnsupportedOperationException("JVM 宿主不提供 BLE 实现，请在 Android 或 iOS 上运行")
