package com.valhalla.core.firmware

/** 固件文件与预检的结构化错误，`code` 用于 UI/日志稳定标识。 */
sealed class FirmwareException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    abstract val code: String

    class Truncated(val expectedBytes: Long, val actualBytes: Long) : FirmwareException(
        "固件文件被截断：至少需要 $expectedBytes 字节，实际 $actualBytes 字节",
    ) {
        override val code: String = "firmware.truncated"
    }

    class InvalidMagic(val actual: String) : FirmwareException("固件魔数不匹配：$actual") {
        override val code: String = "firmware.magic"
    }

    class UnsupportedFormat(val version: Int) : FirmwareException("不支持的固件格式版本：$version") {
        override val code: String = "firmware.format"
    }

    class UnsupportedFlags(val flags: Int) : FirmwareException("固件头包含未知标志位：0x${flags.toString(16)}") {
        override val code: String = "firmware.flags"
    }

    class InvalidHeaderField(val field: String, val detail: String) :
        FirmwareException("固件头字段 $field 非法：$detail") {
        override val code: String = "firmware.header"
    }

    class HeaderChecksumMismatch(val expected: Int, val actual: Int) : FirmwareException(
        "固件头 CRC 校验失败：期望 0x${expected.toString(16)}，实际 0x${actual.toString(16)}",
    ) {
        override val code: String = "firmware.header_crc"
    }

    class PayloadSizeMismatch(val declaredBytes: Long, val actualBytes: Long) : FirmwareException(
        "固件 payload 长度不符：头声明 $declaredBytes 字节，实际 $actualBytes 字节",
    ) {
        override val code: String = "firmware.size"
    }

    class PayloadTooLarge(val actualBytes: Long, val maxBytes: Long) : FirmwareException(
        "固件 payload 过大：$actualBytes 字节，上限 $maxBytes 字节",
    ) {
        override val code: String = "firmware.too_large"
    }

    class PayloadChecksumMismatch(val expected: Long, val actual: Long) : FirmwareException(
        "固件 payload CRC32 校验失败：期望 0x${expected.toString(16)}，实际 0x${actual.toString(16)}",
    ) {
        override val code: String = "firmware.payload_crc"
    }

    class HardwareUnknown : FirmwareException("无法获取设备硬件型号，不能做固件型号预检") {
        override val code: String = "firmware.hardware_unknown"
    }

    class HardwareModelMismatch(val deviceModel: String?, val supported: List<String>) : FirmwareException(
        "固件不适用于当前设备型号：设备 ${deviceModel ?: "未知"}，固件支持 ${supported.joinToString("/")}",
    ) {
        override val code: String = "firmware.model"
    }

    class VersionUnknown : FirmwareException("无法获取设备当前固件版本，不能做版本预检") {
        override val code: String = "firmware.version_unknown"
    }

    class VersionRollback(val current: String, val target: String) : FirmwareException(
        "固件版本回退：设备当前 $current，待升级 $target",
    ) {
        override val code: String = "firmware.version_rollback"
    }

    class ReadFailure(val offset: Long, val length: Int, cause: Throwable? = null) : FirmwareException(
        "固件读取失败：offset=$offset, length=$length",
        cause,
    ) {
        override val code: String = "firmware.read"
    }
}
