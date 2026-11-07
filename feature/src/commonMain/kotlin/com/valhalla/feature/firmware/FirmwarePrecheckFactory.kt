package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import com.valhalla.core.firmware.EcdsaP256FirmwareVerifier
import com.valhalla.core.firmware.FirmwarePrechecker
import com.valhalla.core.firmware.FirmwareSignature
import com.valhalla.core.firmware.FirmwareSignaturePolicy
import com.valhalla.core.firmware.FirmwareSignatureVerifier
import com.valhalla.core.security.EcdsaKeyException

/**
 * 固件签名公钥注入入口。
 *
 * 读取平台资源中的 PEM（公钥或 X.509 证书）：
 * - Android：`assets/firmware/signing-key.pem`
 * - iOS：bundle 内 `firmware/signing-key.pem`
 * - JVM：classpath `firmware/signing-key.pem`
 *
 * 仓库**不内置任何生产密钥**；未注入时返回 null。注入方式与失败策略见 `docs/firmware.md`。
 */
@Composable
expect fun rememberFirmwareSigningKeyPem(): String?

/** 生产固件预检装配：ECDSA-P256 验签，HMAC 实现仅保留在测试/模拟器路径。 */
object FirmwarePrecheckFactory {
    /**
     * 装配规则：
     * - 已注入且可解析 → ECDSA 验签 + [FirmwareSignaturePolicy.REQUIRE]；
     * - 已注入但不可解析 → 全部拒绝的验签器 + REQUIRE（**失败关闭**，避免密钥配置错误后
     *   静默放行未签名固件）；
     * - 未注入 → 无验签器 + [FirmwareSignaturePolicy.WARN]（开发/联调默认，文档明确标注）。
     */
    fun production(publicKeyPem: String?): FirmwarePrechecker {
        if (publicKeyPem.isNullOrBlank()) {
            return FirmwarePrechecker(signaturePolicy = FirmwareSignaturePolicy.WARN)
        }
        return try {
            FirmwarePrechecker(
                signatureVerifier = EcdsaP256FirmwareVerifier.fromPem(publicKeyPem),
                signaturePolicy = FirmwareSignaturePolicy.REQUIRE,
            )
        } catch (error: EcdsaKeyException) {
            FirmwarePrechecker(
                signatureVerifier = RejectingFirmwareVerifier(error.code.label),
                signaturePolicy = FirmwareSignaturePolicy.REQUIRE,
            )
        }
    }

    /** 公钥配置错误时的失败关闭实现：任何签名都不通过。 */
    private class RejectingFirmwareVerifier(
        private val reason: String,
    ) : FirmwareSignatureVerifier {
        override fun verify(
            digest: ByteArray,
            signature: FirmwareSignature,
        ): Boolean = false

        override fun toString(): String = "RejectingFirmwareVerifier($reason)"
    }
}
