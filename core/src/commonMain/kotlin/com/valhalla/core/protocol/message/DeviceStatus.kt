package com.valhalla.core.protocol.message

private const val STATUS_AUTH_FAILED: Int = 0x05
private const val STATUS_CHALLENGE_EXPIRED: Int = 0x06
private const val STATUS_BUSY: Int = 0x07
private const val STATUS_NOT_READY: Int = 0x09
private const val STATUS_OUT_OF_RANGE: Int = 0x0A
private const val STATUS_STORAGE_FULL: Int = 0x0B
private const val STATUS_OTA_VERIFY_FAILED: Int = 0x0C
private const val STATUS_CRC_MISMATCH: Int = 0x0E
private const val STATUS_OTA_RESUME_OFFSET_INVALID: Int = 0x11
private const val STATUS_OTA_RESUME_CONTEXT_INVALID: Int = 0x12
private const val STATUS_OTA_FIRMWARE_MISMATCH: Int = 0x13
private const val STATUS_UNKNOWN_ERROR: Int = 0x7F

/**
 * 设备应答状态码。每个应答帧 payload 的第 0 字节即为该状态码。
 *
 * 状态码取值由 Valhalla 设备协议字典定义；这里只覆盖当前固件已约定的范围，
 * 未知取值统一落到 [UNKNOWN_ERROR]，避免上层因新增错误码直接崩溃。
 */
enum class DeviceStatus(
    val code: Int,
    val title: String,
) {
    OK(0x00, "成功"),
    UNKNOWN_COMMAND(0x01, "未知指令"),
    INVALID_PAYLOAD(0x02, "报文负载非法"),
    INVALID_PARAMETER(0x03, "参数非法"),
    UNAUTHORIZED(0x04, "未鉴权"),
    AUTH_FAILED(STATUS_AUTH_FAILED, "鉴权失败"),
    CHALLENGE_EXPIRED(STATUS_CHALLENGE_EXPIRED, "挑战已过期"),
    BUSY(STATUS_BUSY, "设备忙"),
    TIMEOUT(0x08, "设备处理超时"),
    NOT_READY(STATUS_NOT_READY, "设备未就绪"),
    OUT_OF_RANGE(STATUS_OUT_OF_RANGE, "取值超出范围"),
    STORAGE_FULL(STATUS_STORAGE_FULL, "存储空间不足"),
    OTA_VERIFY_FAILED(STATUS_OTA_VERIFY_FAILED, "OTA 固件校验失败"),
    OTA_STATE_INVALID(0x0D, "OTA 状态不允许该操作"),
    CRC_MISMATCH(STATUS_CRC_MISMATCH, "CRC 校验失败"),
    UNSUPPORTED(0x0F, "设备不支持该能力"),
    INTERNAL_ERROR(0x10, "设备内部错误"),
    OTA_RESUME_OFFSET_INVALID(STATUS_OTA_RESUME_OFFSET_INVALID, "OTA 续传偏移非法"),
    OTA_RESUME_CONTEXT_INVALID(STATUS_OTA_RESUME_CONTEXT_INVALID, "OTA 续传上下文失效"),
    OTA_FIRMWARE_MISMATCH(STATUS_OTA_FIRMWARE_MISMATCH, "OTA 固件标识不匹配"),
    UNKNOWN_ERROR(STATUS_UNKNOWN_ERROR, "未定义错误"),
    ;

    val isSuccess: Boolean get() = this == OK

    /** 形如 `0x05 鉴权失败` 的可读描述，便于日志与 UI 展示。 */
    fun describe(): String = "0x${code.toString(16).uppercase().padStart(2, '0')} $title"

    companion object {
        fun fromCode(code: Int): DeviceStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN_ERROR
    }
}
