package com.valhalla.data.ble

/** 蓝牙适配器可用状态。 */
enum class BleAdapterState {
    UNKNOWN,
    UNSUPPORTED,
    POWERED_OFF,
    POWERED_ON,
    UNAUTHORIZED,

    /** 系统正在重启蓝牙服务（iOS `CBManagerStateResetting`）。 */
    RESETTING,
}

/** 扫描到的设备。 */
data class BleDevice(
    val deviceId: String,
    val name: String?,
    val rssi: Int,
    val isConnectable: Boolean = true,
    val serviceUuids: List<String> = emptyList(),
)

/** 扫描过滤条件。为空表示不过滤。 */
data class ScanFilter(
    val namePrefix: String? = null,
    val serviceUuid: String? = null,
) {
    fun matchesName(name: String?): Boolean {
        if (namePrefix == null) return true
        return name != null && name.startsWith(namePrefix, ignoreCase = true)
    }

    fun matchesService(serviceUuids: List<String>): Boolean {
        if (serviceUuid == null) return true
        return serviceUuids.any { it.equals(serviceUuid, ignoreCase = true) }
    }
}

/** BLE 层统一异常，避免上层直接依赖 Android/iOS 平台异常类型。 */
class BleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 常用 GATT UUID（16 位标准服务扩写为 128 位）。 */
object BleUuids {
    const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    const val DEVICE_INFO_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb"
    const val FIRMWARE_REVISION = "00002a26-0000-1000-8000-00805f9b34fb"
    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"

    /** Valhalla 自定义 OTA 服务与特征，固件协议接入前先固定 UUID。 */
    const val OTA_SERVICE = "0000f1a0-0000-1000-8000-00805f9b34fb"
    const val OTA_CONTROL = "0000f1a1-0000-1000-8000-00805f9b34fb"
    const val OTA_DATA = "0000f1a2-0000-1000-8000-00805f9b34fb"

    /**
     * v1 报文协议通道：下行帧写入 [PROTOCOL_WRITE]，设备应答与主动通知从 [PROTOCOL_NOTIFY] 上报。
     *
     * 通知特征同时支持 notify 与 indicate，平台实现需按特征属性选择使能方式。
     */
    const val PROTOCOL_SERVICE = "0000f1b0-0000-1000-8000-00805f9b34fb"
    const val PROTOCOL_WRITE = "0000f1b1-0000-1000-8000-00805f9b34fb"
    const val PROTOCOL_NOTIFY = "0000f1b2-0000-1000-8000-00805f9b34fb"
}
