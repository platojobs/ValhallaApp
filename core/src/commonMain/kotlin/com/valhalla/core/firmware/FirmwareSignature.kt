package com.valhalla.core.firmware

import com.valhalla.core.security.HmacSha256

/** 固件签名算法。低字节为协议中的算法编号，预留 ECDSA-P256 以便后续接入平台实现。 */
enum class FirmwareSignatureAlgorithm(val code: Int, val title: String) {
    /** 预共享密钥 HMAC-SHA256；当前用于测试密钥与本地验签流程。 */
    HMAC_SHA256(0x01, "HMAC-SHA256"),

    /** ECDSA P-256 + SHA-256，曲线与哈希固定；验签接口已预留，平台实现待接入。 */
    ECDSA_P256(0x02, "ECDSA-P256"),
    ;

    companion object {
        fun fromCode(code: Int): FirmwareSignatureAlgorithm? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 固件签名段（追加在 payload 之后，不参与 OTA 协议传输）。
 *
 * 编码格式：`algorithm:u8 | signatureLen:u16 | signature`
 *
 * 签名对象为 `SHA-256(文件头 104 字节 || payload)`；文件头本身包含 `hasSignature` 标志，
 * 因此篡改标志位会同时破坏签名。
 */
class FirmwareSignature(
    val algorithm: FirmwareSignatureAlgorithm,
    val bytes: ByteArray,
) {

    init {
        require(bytes.isNotEmpty()) { "签名内容不能为空" }
        require(bytes.size <= 0xFFFF) { "签名内容超过 u16 长度上限: ${bytes.size}" }
    }

    /** 签名段的完整编码长度（算法 1 字节 + 长度 2 字节 + 签名内容）。 */
    val encodedSize: Int get() = HEADER_SIZE + bytes.size

    fun encode(): ByteArray {
        val out = ByteArray(encodedSize)
        out[0] = algorithm.code.toByte()
        out[1] = (bytes.size and 0xFF).toByte()
        out[2] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes.copyInto(out, destinationOffset = HEADER_SIZE)
        return out
    }

    override fun toString(): String =
        "FirmwareSignature(${algorithm.title}, ${bytes.size} 字节)"

    companion object {
        const val HEADER_SIZE: Int = 3

        /** 解析签名段；长度与实际字节数不符时抛出 [FirmwareException.SignatureSectionInvalid]。 */
        fun decode(bytes: ByteArray, offset: Int): FirmwareSignature {
            if (offset < 0 || offset + HEADER_SIZE > bytes.size) {
                throw FirmwareException.SignatureSectionInvalid("签名段头部不完整：offset=$offset")
            }
            val algorithm = FirmwareSignatureAlgorithm.fromCode(bytes[offset].toInt() and 0xFF)
                ?: throw FirmwareException.SignatureAlgorithmUnsupported(bytes[offset].toInt() and 0xFF)
            val length = (bytes[offset + 1].toInt() and 0xFF) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8)
            if (length <= 0) {
                throw FirmwareException.SignatureSectionInvalid("签名长度为 0")
            }
            val end = offset + HEADER_SIZE + length
            if (end > bytes.size) {
                throw FirmwareException.SignatureSectionInvalid(
                    "签名段被截断：需要 ${HEADER_SIZE + length} 字节，实际 ${bytes.size - offset} 字节",
                )
            }
            val signatureBytes = bytes.copyOfRange(offset + HEADER_SIZE, end)
            return FirmwareSignature(algorithm, signatureBytes)
        }
    }
}

/**
 * 固件验签接口。
 *
 * 实现方只负责算法与密钥管理，摘要计算由 [FirmwarePackage.computeSignatureDigest] 提供。
 */
interface FirmwareSignatureVerifier {

    fun verify(digest: ByteArray, signature: FirmwareSignature): Boolean
}

/**
 * 测试密钥验签器：使用预共享密钥的 HMAC-SHA256。
 *
 * 仅用于测试与本地联调；生产环境应替换为基于平台加密库的 ECDSA 验签实现。
 */
class HmacSha256TestVerifier(private val secret: ByteArray) : FirmwareSignatureVerifier {

    init {
        require(secret.isNotEmpty()) { "验签密钥不能为空" }
    }

    override fun verify(digest: ByteArray, signature: FirmwareSignature): Boolean {
        if (signature.algorithm != FirmwareSignatureAlgorithm.HMAC_SHA256) return false
        val expected = HmacSha256.compute(secret, digest)
        return HmacSha256.constantTimeEquals(expected, signature.bytes)
    }
}

/** 签名计算便捷入口：用测试密钥对摘要签名，供测试桩构造已签名固件。 */
object FirmwareTestSigner {

    fun sha256Digest(header: FirmwareHeader, payload: ByteArray): ByteArray =
        Sha256DigestBuilder.build(header, payload)

    fun signWithHmacKey(header: FirmwareHeader, payload: ByteArray, secret: ByteArray): FirmwareSignature {
        val digest = Sha256DigestBuilder.build(header, payload)
        return FirmwareSignature(
            algorithm = FirmwareSignatureAlgorithm.HMAC_SHA256,
            bytes = HmacSha256.compute(secret, digest),
        )
    }
}

private object Sha256DigestBuilder {

    fun build(header: FirmwareHeader, payload: ByteArray): ByteArray {
        val digest = com.valhalla.core.security.Sha256Digest()
        digest.update(header.encode())
        digest.update(payload)
        return digest.digest()
    }
}
