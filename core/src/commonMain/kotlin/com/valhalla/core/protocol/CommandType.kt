package com.valhalla.core.protocol

/**
 * 设备指令类型。code 会写入报文头，取值由设备协议字典定义。
 */
enum class CommandType(val code: Byte) {
    AUTH(0x01),
    GET_DEVICE_INFO(0x02),
    SET_CONFIG(0x03),
    START_OTA(0x10),
    OTA_DATA(0x11),
    FINISH_OTA(0x12),
    REBOOT(0x20),
    LOG_DUMP(0x30),
    HEARTBEAT(0x40),
    UNKNOWN(0x7F),
    ;

    companion object {
        fun fromCode(code: Byte): CommandType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
