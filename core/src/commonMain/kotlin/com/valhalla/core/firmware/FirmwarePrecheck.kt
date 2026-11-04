package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion

/** 固件验签策略。 */
enum class FirmwareSignaturePolicy {

    /** 必须签名且验签通过，否则拒绝升级（生产构建推荐）。 */
    REQUIRE,

    /** 未签名只告警、不阻断；验签失败仍然拒绝（默认，兼容旧固件）。 */
    WARN,

    /** 完全忽略签名段，仅做 CRC 与型号/版本预检。 */
    IGNORE,
}

/** 本地预检结果：只要 [errors] 非空，OTA 就不应启动；[warnings] 不影响放行。 */
data class FirmwarePrecheckResult(
    val errors: List<FirmwareException> = emptyList(),
    val warnings: List<String> = emptyList(),
    val payloadCrc32: Long? = null,
    /** 协议层 CRC-16/CCITT-FALSE，用于断点续传时确认「续的是同一份内容」。 */
    val payloadCrc16: Int? = null,
) {

    val passed: Boolean get() = errors.isEmpty()

    val primaryError: FirmwareException? get() = errors.firstOrNull()

    /** UI 可直接展示的多行原因；[warnings] 通过 [warningSummary] 单独展示。 */
    fun summary(): String =
        if (passed) "预检通过" else errors.joinToString(separator = "\n") { it.message ?: it.code }

    /** 非阻断性风险提示，例如「固件未签名」。 */
    fun warningSummary(): String? = warnings.takeIf { it.isNotEmpty() }?.joinToString("；")

    companion object {
        fun success(payloadCrc32: Long, payloadCrc16: Int? = null) =
            FirmwarePrecheckResult(payloadCrc32 = payloadCrc32, payloadCrc16 = payloadCrc16)
    }
}

/** 本地预检进度：用于 UI 展示大文件校验进度。 */
data class FirmwarePrecheckProgress(
    val readBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 1f else (readBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** 用户主动取消预检（不是文件错误，也不是协程取消）。 */
class FirmwarePrecheckCancelledException : Exception("固件预检已取消")

/**
 * 本地预检器，按顺序收集所有可判定的问题（不提前短路）：
 *
 * 1. payload 大小上限（默认 32 MiB）；
 * 2. 设备型号是否命中固件头白名单（`*` 表示通配）；
 * 3. 设备当前版本是否未知 / 固件是否为版本回退（相同版本允许重刷）；
 * 4. payload CRC-32 是否与文件头声明一致（流式读取，不整包进内存）；
 * 5. 固件签名（可选）：按 [signaturePolicy] 对签名段做验签，缺失时按策略拒绝或告警。
 *
 * 第 4、5 步以及续传所需的协议层 CRC-16 由**同一次**流式扫描产出，payload 只读一遍。
 * 扫描期间通过 [check] 的 `onProgress` 回调推送进度，并响应协程取消与 `shouldCancel`。
 * 大小超限时跳过校验计算，避免对超大文件做无意义的完整读取。
 */
class FirmwarePrechecker(
    private val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES,
    /** 验签器；为 null 时无法验证签名，REQUIRE 策略会把已签名固件视为不可用。 */
    private val signatureVerifier: FirmwareSignatureVerifier? = null,
    /** 签名策略：默认 WARN，兼容旧固件；生产构建应显式使用 REQUIRE。 */
    private val signaturePolicy: FirmwareSignaturePolicy = FirmwareSignaturePolicy.WARN,
) {

    init {
        require(maxPayloadBytes > 0) { "maxPayloadBytes 必须大于 0: $maxPayloadBytes" }
    }

    suspend fun check(
        firmware: FirmwarePackage,
        currentVersion: FirmwareVersion?,
        currentModel: String?,
        onProgress: ((FirmwarePrecheckProgress) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null,
    ): FirmwarePrecheckResult {
        val errors = mutableListOf<FirmwareException>()
        val warnings = mutableListOf<String>()

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

        var scan: FirmwareScan? = null
        if (!oversize) {
            try {
                scan = firmware.scanPayload(
                    includeSignatureDigest = shouldComputeSignatureDigest(firmware),
                    onProgress = { readBytes, totalBytes ->
                        if (shouldCancel?.invoke() == true) {
                            throw FirmwarePrecheckCancelledException()
                        }
                        onProgress?.invoke(FirmwarePrecheckProgress(readBytes, totalBytes))
                    },
                )
            } catch (cancelled: FirmwarePrecheckCancelledException) {
                throw cancelled
            } catch (error: FirmwareException) {
                errors += error
            }
        }

        val computedCrc32 = scan?.crc32
        val computedCrc16 = scan?.crc16
        val scanned = scan
        if (scanned != null && scanned.crc32 != firmware.payloadCrc32) {
            errors += FirmwareException.PayloadChecksumMismatch(firmware.payloadCrc32, scanned.crc32)
        }

        if (!oversize) {
            checkSignature(firmware, scan?.signatureDigest, errors, warnings)
        }

        return FirmwarePrecheckResult(
            errors = errors,
            warnings = warnings,
            payloadCrc32 = computedCrc32,
            payloadCrc16 = computedCrc16,
        )
    }

    private fun shouldComputeSignatureDigest(firmware: FirmwarePackage): Boolean =
        firmware.signature != null &&
            signaturePolicy != FirmwareSignaturePolicy.IGNORE &&
            signatureVerifier != null

    /**
     * 签名分支：
     * - 带签名段：配置了验签器则按摘要验签，失败恒为错误；未配置验签器时按策略报错或告警；
     * - 无签名段：REQUIRE 报错，WARN 告警，IGNORE 静默。
     *
     * [digest] 由同一次扫描产出；读取失败时为 null（此时已有读取错误，不再重复报告）。
     */
    private fun checkSignature(
        firmware: FirmwarePackage,
        digest: ByteArray?,
        errors: MutableList<FirmwareException>,
        warnings: MutableList<String>,
    ) {
        val signature = firmware.signature
        if (signature == null) {
            when (signaturePolicy) {
                FirmwareSignaturePolicy.REQUIRE -> errors += FirmwareException.SignatureMissing()
                FirmwareSignaturePolicy.WARN -> warnings += "固件未签名，已按警告策略放行"
                FirmwareSignaturePolicy.IGNORE -> Unit
            }
            return
        }

        val verifier = signatureVerifier
        if (verifier == null) {
            when (signaturePolicy) {
                FirmwareSignaturePolicy.REQUIRE -> errors += FirmwareException.SignatureVerifierUnavailable()
                FirmwareSignaturePolicy.WARN -> warnings += "固件已签名，但当前构建未配置验签器"
                FirmwareSignaturePolicy.IGNORE -> Unit
            }
            return
        }

        if (signaturePolicy == FirmwareSignaturePolicy.IGNORE) return
        // 摘要随扫描一起产出；扫描失败（文件读取错误）已经记录，签名分支无法继续。
        if (digest == null) return

        if (!verifier.verify(digest, signature)) {
            errors += FirmwareException.SignatureInvalid(
                algorithm = signature.algorithm.title,
                detail = "摘要与签名不匹配（内容被篡改或密钥不一致）",
            )
        }
    }

    companion object {
        /** 默认 payload 上限 32 MiB，覆盖当前硬件 flash 规划并限制误选大文件。 */
        const val DEFAULT_MAX_PAYLOAD_BYTES: Long = 32L * 1024 * 1024

        /**
         * 版本比较仅看 `major.minor.patch.build`，忽略渠道名：
         * 渠道切换（例如 release → beta）不属于版本回退，由部署流程自行约束。
         */
        fun compare(target: FirmwareVersion, current: FirmwareVersion): Int {
            if (target.sameReleaseAs(current)) return 0
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
