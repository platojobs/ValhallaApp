package com.valhalla.feature.ble

/** 扫描列表为空时的具体原因，UI 据此展示不同文案与操作。 */
enum class ScanEmptyReason {
    /** 有设备或仍在扫描且未超时，无需空态提示。 */
    NONE,
    /** 正在扫描，尚未发现设备。 */
    SCANNING,
    /** 扫描超时仍未发现设备。 */
    SCAN_TIMEOUT,
    /** 扫描结束且确实没有设备。 */
    NO_DEVICES,
    /** 缺少蓝牙权限。 */
    NO_PERMISSION,
    /** 权限被永久拒绝，需要去系统设置。 */
    PERMISSION_PERMANENTLY_DENIED,
    /** 蓝牙关闭。 */
    BLUETOOTH_OFF,
    /** 蓝牙正在重置。 */
    BLUETOOTH_RESETTING,
    /** 设备不支持 BLE。 */
    UNSUPPORTED,
    /** 蓝牙状态未知。 */
    UNKNOWN_AVAILABILITY,
    /** 扫描过程报错。 */
    ERROR,
}

/**
 * 扫描空态判定（纯逻辑）。
 *
 * 判定顺序：已有设备 > 错误 > 权限/蓝牙不可用 > 扫描中/超时 > 真的无设备。
 * 权限与蓝牙状态优先，避免把「没有权限」误报成「没有设备」。
 */
fun classifyScanEmpty(
    scanning: Boolean,
    deviceCount: Int,
    availability: BleAvailability,
    error: String? = null,
    timedOut: Boolean = false,
): ScanEmptyReason {
    if (deviceCount > 0) return ScanEmptyReason.NONE
    if (error != null) return ScanEmptyReason.ERROR
    when (availability) {
        BleAvailability.PERMISSION_NOT_REQUESTED,
        BleAvailability.PERMISSION_DENIED,
        -> return ScanEmptyReason.NO_PERMISSION

        BleAvailability.PERMISSION_PERMANENTLY_DENIED ->
            return ScanEmptyReason.PERMISSION_PERMANENTLY_DENIED

        BleAvailability.BLUETOOTH_OFF -> return ScanEmptyReason.BLUETOOTH_OFF
        BleAvailability.BLUETOOTH_RESETTING -> return ScanEmptyReason.BLUETOOTH_RESETTING
        BleAvailability.UNSUPPORTED -> return ScanEmptyReason.UNSUPPORTED
        BleAvailability.UNKNOWN -> return ScanEmptyReason.UNKNOWN_AVAILABILITY
        BleAvailability.READY -> Unit
    }
    return when {
        timedOut -> ScanEmptyReason.SCAN_TIMEOUT
        scanning -> ScanEmptyReason.SCANNING
        else -> ScanEmptyReason.NO_DEVICES
    }
}
