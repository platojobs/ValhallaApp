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
 * Android 侧 ECDSA-P256 验签实现（`java.security` + `NONEwithECDSA`）。
 *
 * 与 JVM 实现保持同一语义：摘要来自固件流式扫描，签名格式为 DER；
 * 不使用 `SHA256withECDSA`，避免对已是 SHA-256 的摘要再次哈希。
 * Android 的 `KeyFactory("EC")` 与 `CertificateFactory("X.509")` 自 API 1 起可用。
 */
private class AndroidEcdsaPublicKey(private val key: PublicKey) : PlatformEcdsaKey {

    override fun verify(digest: ByteArray, signatureDer: ByteArray): Boolean {
        val verifier = Signature.getInstance("NONEwithECDSA")
        verifier.initVerify(key)
        verifier.update(digest)
        return verifier.verify(signatureDer)
    }
}

internal actual fun platformLoadEcdsaKeyFromSpki(der: ByteArray): PlatformEcdsaKey {
    val key = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    } catch (error: GeneralSecurityException) {
        throw EcdsaKeyException(
            EcdsaFailureCode.PUBLIC_KEY_PARSE,
            "SPKI 公钥解析失败：${error.message ?: error::class.simpleName}",
            error,
        )
    }
    requireP256(key)
    return AndroidEcdsaPublicKey(key)
}

internal actual fun platformLoadEcdsaKeyFromCertificate(der: ByteArray): PlatformEcdsaKey {
    val certificate = try {
        CertificateFactory.getInstance("X.509")
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
    return AndroidEcdsaPublicKey(key)
}

private fun requireP256(key: PublicKey) {
    val params = (key as? ECPublicKey)?.params
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
