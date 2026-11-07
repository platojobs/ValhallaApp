package com.valhalla.core.protocol

/**
 * 设备指令类型。code 写入报文头第 4 字节（从 0 开始计数），取值由设备协议字典定义。
 *
 * 约定：
 * - 0x01..0x3F 为设备主动处理的上行指令；
 * - 0x40..0x5F 为心跳与设备主动上报的通知；
 * - 0x7F 为解析失败时的兜底值。
 */
enum class CommandType(
    val code: Byte,
) {
    AUTH(0x01),
    GET_DEVICE_INFO(0x02),
    SET_CONFIG(0x03),
    GET_CONFIG(0x04),
    GET_BATTERY(0x05),
    GET_FIRMWARE_VERSION(0x06),
    SET_TIME(0x07),
    GET_TIME(0x08),
    SET_PARAM(0x09),
    GET_PARAM(0x0A),
    FACTORY_RESET(0x0B),

    START_OTA(0x10),
    OTA_DATA(0x11),
    FINISH_OTA(0x12),
    ABORT_OTA(0x13),

    REBOOT(0x20),
    LOG_DUMP(0x30),
    HEARTBEAT(0x40),

    /** 设备主动上报的心跳应答（通知帧）。 */
    HEARTBEAT_REPLY(0x41),

    /** 设备状态变化上报（通知帧）。 */
    DEVICE_STATUS_NOTIFY(0x42),

    /** OTA 进度上报（通知帧）。 */
    OTA_PROGRESS_NOTIFY(0x43),

    UNKNOWN(0x7F),
    ;

    /** 该指令是否只以通知帧形式出现（设备主动推送，不是请求-应答配对的一部分）。 */
    val isNotificationOnly: Boolean
        get() = this == HEARTBEAT_REPLY || this == DEVICE_STATUS_NOTIFY || this == OTA_PROGRESS_NOTIFY

    companion object {
        fun fromCode(code: Byte): CommandType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
