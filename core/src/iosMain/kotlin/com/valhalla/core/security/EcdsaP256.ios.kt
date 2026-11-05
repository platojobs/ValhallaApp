@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package com.valhalla.core.security

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Security.SecCertificateCopyKey
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyGetBlockSize
import platform.Security.SecKeyRef
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureDigestX962SHA256

/**
 * iOS 侧 ECDSA-P256 验签实现（`Security.framework`）。
 *
 * 两个关键点：
 * 1. **签名格式**：使用 `kSecKeyAlgorithmECDSASignatureDigestX962SHA256`，
 *    入参是 32 字节 SHA-256 摘要、签名是 X9.62/DER，与 JCA `NONEwithECDSA` 完全一致；
 * 2. **公钥格式**：`SecKeyCreateWithData` 的 EC 公钥要求 X9.63 原始点，
 *    因此先从 SPKI 中提取 `04 || X || Y`；证书则用 `SecCertificateCopyKey` 直接取公钥。
 */
private class AppleEcdsaPublicKey(private val key: SecKeyRef) : PlatformEcdsaKey {

    override fun verify(digest: ByteArray, signatureDer: ByteArray): Boolean {
        val digestData = digest.toNSData()
        val signatureData = signatureDer.toNSData()
        val retainedDigest = CFBridgingRetain(digestData)
        val retainedSignature = CFBridgingRetain(signatureData)
        return try {
            SecKeyVerifySignature(
                key = key,
                algorithm = kSecKeyAlgorithmECDSASignatureDigestX962SHA256,
                signedData = retainedDigest as CFDataRef,
                signature = retainedSignature as CFDataRef,
                error = null,
            )
        } finally {
            CFRelease(retainedDigest)
            CFRelease(retainedSignature)
        }
    }
}

internal actual fun platformLoadEcdsaKeyFromSpki(der: ByteArray): PlatformEcdsaKey {
    val point = EcdsaPublicKeyEncoding.extractP256PointFromSpki(der)
        ?: throw EcdsaKeyException(
            EcdsaFailureCode.PUBLIC_KEY_PARSE,
            "SPKI 中未找到 P-256 公钥点（需 65 字节 04||X||Y）",
        )
    return loadKey(point, "SPKI 公钥")
}

internal actual fun platformLoadEcdsaKeyFromCertificate(der: ByteArray): PlatformEcdsaKey {
    val certificateData = der.toNSData()
    val retained = CFBridgingRetain(certificateData)
    val key = try {
        val certificate = SecCertificateCreateWithData(null, retained as CFDataRef)
            ?: throw EcdsaKeyException(EcdsaFailureCode.PUBLIC_KEY_PARSE, "X.509 证书解析失败")
        try {
            SecCertificateCopyKey(certificate)
                ?: throw EcdsaKeyException(EcdsaFailureCode.PUBLIC_KEY_PARSE, "证书未包含可用公钥")
        } finally {
            CFRelease(certificate)
        }
    } finally {
        CFRelease(retained)
    }
    return checkAndWrap(key, "证书公钥")
}

private fun loadKey(rawPoint: ByteArray, label: String): PlatformEcdsaKey {
    val data = rawPoint.toNSData()
    val retainedData = CFBridgingRetain(data)
    val dictionary = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
        ?: throw EcdsaKeyException(EcdsaFailureCode.PLATFORM_ERROR, "无法创建公钥属性字典")
    val retainedSize = CFBridgingRetain(NSNumber(int = P256_SIZE_BITS))
    val key = try {
        CFDictionaryAddValue(dictionary, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        CFDictionaryAddValue(dictionary, kSecAttrKeyClass, kSecAttrKeyClassPublic)
        CFDictionaryAddValue(dictionary, kSecAttrKeySizeInBits, retainedSize)
        SecKeyCreateWithData(retainedData as CFDataRef, dictionary, null)
            ?: throw EcdsaKeyException(EcdsaFailureCode.PUBLIC_KEY_PARSE, "$label 无法导入 Security.framework")
    } finally {
        CFRelease(retainedSize)
        CFRelease(dictionary)
        CFRelease(retainedData)
    }
    return checkAndWrap(key, label)
}

private fun checkAndWrap(key: SecKeyRef, label: String): PlatformEcdsaKey {
    val blockSize = SecKeyGetBlockSize(key)
    if (blockSize != P256_BLOCK_BYTES.toULong()) {
        CFRelease(key)
        throw EcdsaKeyException(
            EcdsaFailureCode.PUBLIC_KEY_UNSUPPORTED,
            "$label 不是 P-256（blockSize=$blockSize）",
        )
    }
    return AppleEcdsaPublicKey(key)
}

private const val P256_SIZE_BITS = 256
private const val P256_BLOCK_BYTES = 32

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}
