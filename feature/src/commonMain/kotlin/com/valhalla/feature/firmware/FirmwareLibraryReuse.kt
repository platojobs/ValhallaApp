package com.valhalla.feature.firmware

import com.valhalla.core.firmware.FirmwareLibraryRecord
import com.valhalla.core.firmware.FirmwareParser
import com.valhalla.core.firmware.FirmwareReuseCheck
import com.valhalla.core.firmware.FirmwareReuseReason

/**
 * 固件库复用校验：重新打开原始文件并重算 payload CRC-32。
 *
 * 不复用预检结果，也不相信内存里的旧包：文件可能已被系统清理、移动或被替换。
 * 只有「能打开 + CRC 与记录一致」才允许直接进入升级流程；任何异常都返回不可用。
 */
class FirmwareLibraryReuse(private val picker: FirmwarePicker) {

    suspend fun check(record: FirmwareLibraryRecord): FirmwareReuseCheck {
        val picked = try {
            picker.openFirmware(record.sourcePath)
        } catch (_: Throwable) {
            null
        } ?: return FirmwareReuseCheck.Missing(record, FirmwareReuseReason.ACCESS_DENIED)

        return try {
            val parsed = FirmwareParser.parse(picked.name, picked.source)
            try {
                val actual = parsed.computePayloadCrc32()
                if (actual == record.crc32) {
                    FirmwareReuseCheck.Available(record)
                } else {
                    FirmwareReuseCheck.CrcMismatch(record, record.crc32, actual)
                }
            } finally {
                parsed.close()
            }
        } catch (error: Throwable) {
            runCatching { picked.source.close() }
            FirmwareReuseCheck.Missing(record, FirmwareReuseReason.READ_FAILED)
        }
    }
}
