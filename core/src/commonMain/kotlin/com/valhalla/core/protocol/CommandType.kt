package com.valhalla.core.protocol

private const val CMD_GET_BATTERY: Byte = 0x05
private const val CMD_GET_FIRMWARE_VERSION: Byte = 0x06
private const val CMD_SET_TIME: Byte = 0x07
private const val CMD_SET_PARAM: Byte = 0x09
private const val CMD_GET_PARAM: Byte = 0x0A
private const val CMD_FACTORY_RESET: Byte = 0x0B
private const val CMD_OTA_DATA: Byte = 0x11
private const val CMD_FINISH_OTA: Byte = 0x12
private const val CMD_ABORT_OTA: Byte = 0x13
private const val CMD_REBOOT: Byte = 0x20
private const val CMD_LOG_DUMP: Byte = 0x30
private const val CMD_HEARTBEAT: Byte = 0x40
private const val CMD_HEARTBEAT_REPLY: Byte = 0x41
private const val CMD_DEVICE_STATUS_NOTIFY: Byte = 0x42
private const val CMD_OTA_PROGRESS_NOTIFY: Byte = 0x43
private const val CMD_UNKNOWN: Byte = 0x7F

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
    GET_BATTERY(CMD_GET_BATTERY),
    GET_FIRMWARE_VERSION(CMD_GET_FIRMWARE_VERSION),
    SET_TIME(CMD_SET_TIME),
    GET_TIME(0x08),
    SET_PARAM(CMD_SET_PARAM),
    GET_PARAM(CMD_GET_PARAM),
    FACTORY_RESET(CMD_FACTORY_RESET),

    START_OTA(0x10),
    OTA_DATA(CMD_OTA_DATA),
    FINISH_OTA(CMD_FINISH_OTA),
    ABORT_OTA(CMD_ABORT_OTA),

    REBOOT(CMD_REBOOT),
    LOG_DUMP(CMD_LOG_DUMP),
    HEARTBEAT(CMD_HEARTBEAT),

    /** 设备主动上报的心跳应答（通知帧）。 */
    HEARTBEAT_REPLY(CMD_HEARTBEAT_REPLY),

    /** 设备状态变化上报（通知帧）。 */
    DEVICE_STATUS_NOTIFY(CMD_DEVICE_STATUS_NOTIFY),

    /** OTA 进度上报（通知帧）。 */
    OTA_PROGRESS_NOTIFY(CMD_OTA_PROGRESS_NOTIFY),

    UNKNOWN(CMD_UNKNOWN),
    ;

    /** 该指令是否只以通知帧形式出现（设备主动推送，不是请求-应答配对的一部分）。 */
    val isNotificationOnly: Boolean
        get() = this == HEARTBEAT_REPLY || this == DEVICE_STATUS_NOTIFY || this == OTA_PROGRESS_NOTIFY

    companion object {
        fun fromCode(code: Byte): CommandType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
