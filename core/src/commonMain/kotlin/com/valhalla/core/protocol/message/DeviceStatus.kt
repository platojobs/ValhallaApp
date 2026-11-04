package com.valhalla.core.protocol.message

/**
 * 设备应答状态码。每个应答帧 payload 的第 0 字节即为该状态码。
 *
 * 状态码取值由 Valhalla 设备协议字典定义；这里只覆盖当前固件已约定的范围，
 * 未知取值统一落到 [UNKNOWN_ERROR]，避免上层因新增错误码直接崩溃。
 */
enum class DeviceStatus(val code: Int, val title: String) {
    OK(0x00, "成功"),
    UNKNOWN_COMMAND(0x01, "未知指令"),
    INVALID_PAYLOAD(0x02, "报文负载非法"),
    INVALID_PARAMETER(0x03, "参数非法"),
    UNAUTHORIZED(0x04, "未鉴权"),
    AUTH_FAILED(0x05, "鉴权失败"),
    CHALLENGE_EXPIRED(0x06, "挑战已过期"),
    BUSY(0x07, "设备忙"),
    TIMEOUT(0x08, "设备处理超时"),
    NOT_READY(0x09, "设备未就绪"),
    OUT_OF_RANGE(0x0A, "取值超出范围"),
    STORAGE_FULL(0x0B, "存储空间不足"),
    OTA_VERIFY_FAILED(0x0C, "OTA 固件校验失败"),
    OTA_STATE_INVALID(0x0D, "OTA 状态不允许该操作"),
    CRC_MISMATCH(0x0E, "CRC 校验失败"),
    UNSUPPORTED(0x0F, "设备不支持该能力"),
    INTERNAL_ERROR(0x10, "设备内部错误"),
    UNKNOWN_ERROR(0x7F, "未定义错误"),
    ;

    val isSuccess: Boolean get() = this == OK

    /** 形如 `0x05 鉴权失败` 的可读描述，便于日志与 UI 展示。 */
    fun describe(): String = "0x${code.toString(16).uppercase().padStart(2, '0')} $title"

    companion object {
        fun fromCode(code: Int): DeviceStatus =
            entries.firstOrNull { it.code == code } ?: UNKNOWN_ERROR
    }
}
