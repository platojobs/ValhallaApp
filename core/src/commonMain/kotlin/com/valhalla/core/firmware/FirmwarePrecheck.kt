package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion

/** 本地预检结果：只要 [errors] 非空，OTA 就不应启动。 */
data class FirmwarePrecheckResult(
    val errors: List<FirmwareException> = emptyList(),
    val payloadCrc32: Long? = null,
    /** 协议层 CRC-16/CCITT-FALSE，用于断点续传时确认「续的是同一份内容」。 */
    val payloadCrc16: Int? = null,
) {

    val passed: Boolean get() = errors.isEmpty()

    val primaryError: FirmwareException? get() = errors.firstOrNull()

    /** UI 可直接展示的多行原因。 */
    fun summary(): String =
        if (passed) "预检通过" else errors.joinToString(separator = "\n") { it.message ?: it.code }

    companion object {
        fun success(payloadCrc32: Long, payloadCrc16: Int? = null) =
            FirmwarePrecheckResult(payloadCrc32 = payloadCrc32, payloadCrc16 = payloadCrc16)
    }
}

/**
 * 本地预检器，按顺序收集所有可判定的问题（不提前短路）：
 *
 * 1. payload 大小上限（默认 32 MiB）；
 * 2. 设备型号是否命中固件头白名单（`*` 表示通配）；
 * 3. 设备当前版本是否未知 / 固件是否为版本回退（相同版本允许重刷）；
 * 4. payload CRC-32 是否与文件头声明一致（流式读取，不整包进内存）；
 *    同一次读取顺带计算协议层 CRC-16，供断点续传比对。
 *
 * 大小超限时跳过校验计算，避免对超大文件做无意义的完整读取。
 */
class FirmwarePrechecker(
    private val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES,
) {

    init {
        require(maxPayloadBytes > 0) { "maxPayloadBytes 必须大于 0: $maxPayloadBytes" }
    }

    suspend fun check(
        firmware: FirmwarePackage,
        currentVersion: FirmwareVersion?,
        currentModel: String?,
    ): FirmwarePrecheckResult {
        val errors = mutableListOf<FirmwareException>()

        val oversize = firmware.payloadLength > maxPayloadBytes
        if (oversize) {
            errors += FirmwareException.PayloadTooLarge(firmware.payloadLength, maxPayloadBytes)
        }

        if (currentModel.isNullOrBlank()) {
            errors += FirmwareException.HardwareUnknown()
        } else if (!firmware.header.supportsModel(currentModel)) {
            errors += FirmwareException.HardwareModelMismatch(currentModel, firmware.header.targetModels)
        }

        if (currentVersion == null) {
            errors += FirmwareException.VersionUnknown()
        } else {
            val comparison = compare(firmware.version, currentVersion)
            if (comparison < 0) {
                errors += FirmwareException.VersionRollback(currentVersion.toString(), firmware.version.toString())
            }
        }

        var computedCrc32: Long? = null
        var computedCrc16: Int? = null
        if (!oversize) {
            try {
                val checksums = firmware.computePayloadChecksums()
                computedCrc32 = checksums.crc32
                computedCrc16 = checksums.crc16
                if (computedCrc32 != firmware.payloadCrc32) {
                    errors += FirmwareException.PayloadChecksumMismatch(firmware.payloadCrc32, computedCrc32)
                }
            } catch (error: FirmwareException) {
                errors += error
            }
        }

        return FirmwarePrecheckResult(
            errors = errors,
            payloadCrc32 = computedCrc32,
            payloadCrc16 = computedCrc16,
        )
    }

    companion object {
        /** 默认 payload 上限 32 MiB，覆盖当前硬件 flash 规划并限制误选大文件。 */
        const val DEFAULT_MAX_PAYLOAD_BYTES: Long = 32L * 1024 * 1024

        /**
         * 版本比较仅看 `major.minor.patch.build`，忽略渠道名：
         * 渠道切换（例如 release → beta）不属于版本回退，由部署流程自行约束。
         */
        fun compare(target: FirmwareVersion, current: FirmwareVersion): Int {
            val fields = listOf(
                target.major to current.major,
                target.minor to current.minor,
                target.patch to current.patch,
                target.build to current.build,
            )
            for ((left, right) in fields) {
                if (left != right) return left.compareTo(right)
            }
            return 0
        }
    }
}
