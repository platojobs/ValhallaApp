package com.valhalla.core.security

import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * JVM/Android 侧 ECDSA-P256 验签实现。
 *
 * - 公钥：`KeyFactory("EC")` 读取 SPKI；证书：`CertificateFactory("X.509")`。
 * - 验签：`Signature("NONEwithECDSA")`。固件摘要已经由
 *   `FirmwarePackage.computeSignatureDigest` 算成 SHA-256，这里不能再套一次 SHA-256，
 *   否则会与 Apple 的 `...DigestX962` 语义不一致（双重哈希导致跨端结果不同）。
 * - 签名入参与出参均为 DER（X9.62），与 JCA 默认格式一致。
 */
private class JcaEcdsaPublicKey(
    private val key: PublicKey,
) : PlatformEcdsaKey {
    override fun verify(
        digest: ByteArray,
        signatureDer: ByteArray,
    ): Boolean {
        val verifier = Signature.getInstance("NONEwithECDSA")
        verifier.initVerify(key)
        verifier.update(digest)
        return verifier.verify(signatureDer)
    }
}

internal actual fun platformLoadEcdsaKeyFromSpki(der: ByteArray): PlatformEcdsaKey {
    val key =
        try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        } catch (error: GeneralSecurityException) {
            throw EcdsaKeyException(
                EcdsaFailureCode.PUBLIC_KEY_PARSE,
                "SPKI 公钥解析失败：${error.message ?: error::class.simpleName}",
                error,
            )
        }
    requireP256(key)
    return JcaEcdsaPublicKey(key)
}

internal actual fun platformLoadEcdsaKeyFromCertificate(der: ByteArray): PlatformEcdsaKey {
    val certificate =
        try {
            CertificateFactory
                .getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        } catch (error: GeneralSecurityException) {
            throw EcdsaKeyException(
                EcdsaFailureCode.PUBLIC_KEY_PARSE,
                "X.509 证书解析失败：${error.message ?: error::class.simpleName}",
                error,
            )
        }
    val key = certificate.publicKey
    requireP256(key)
    return JcaEcdsaPublicKey(key)
}

private fun requireP256(key: PublicKey) {
    val params =
        (key as? ECPublicKey)?.params
            ?: throw EcdsaKeyException(
                EcdsaFailureCode.PUBLIC_KEY_UNSUPPORTED,
                "公钥不是 EC 类型：${key.algorithm}",
            )
    val fieldBits = params.curve.field.fieldSize
    val orderBits = params.order.bitLength()
    if (fieldBits != 256 || orderBits != 256) {
        throw EcdsaKeyException(
            EcdsaFailureCode.PUBLIC_KEY_UNSUPPORTED,
            "仅支持 P-256，实际 field=$fieldBits order=$orderBits",
        )
    }
}
