package com.valhalla.core.security

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * ECDSA-P256 验签失败分类。
 *
 * 分类粒度按「可诊断原因」划分，调用方可以据此展示不同的排障建议，
 * 而不是笼统的「验签失败」。
 */
enum class EcdsaFailureCode(
    val label: String,
) {
    /** PEM/DER 信封无法解析（Base64 损坏、PEM 头尾缺失、ASN.1 结构错误）。 */
    PUBLIC_KEY_PARSE("公钥解析失败"),

    /** 解析成功但不是 EC P-256 公钥（例如 P-384、RSA）。 */
    PUBLIC_KEY_UNSUPPORTED("公钥算法或曲线不受支持"),

    /** 摘要长度不是 SHA-256 的 32 字节。 */
    DIGEST_LENGTH("摘要长度非法"),

    /** 固件签名段声明的算法编号与验签器不一致。 */
    ALGORITHM_MISMATCH("算法编号不匹配"),

    /** 签名不是合法的 X9.62/DER 结构（缺少 SEQUENCE/INTEGER 或长度越界）。 */
    SIGNATURE_FORMAT("签名格式非法"),

    /** 签名结构合法但与摘要不匹配（内容被篡改或密钥不一致）。 */
    SIGNATURE_MISMATCH("签名与摘要不匹配"),

    /** 平台加密库调用异常（KeyFactory、SecKeyCreateWithData 等）。 */
    PLATFORM_ERROR("平台加密库调用失败"),
}

/** 单个公钥/证书无法加载时抛出，[code] 给出稳定分类。 */
class EcdsaKeyException(
    val code: EcdsaFailureCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** 验签结果：成功或带分类的失败。 */
sealed interface EcdsaVerificationResult {
    data object Valid : EcdsaVerificationResult

    data class Invalid(
        val code: EcdsaFailureCode,
        val detail: String,
    ) : EcdsaVerificationResult
}

/** 签名在 DER（X9.62）与原始 `r || s` 之间的转换。 */
class EcdsaSignatureFormatException(
    message: String,
) : IllegalArgumentException(message)

/**
 * P-256 签名编解码。
 *
 * 线上格式统一为 **DER（X9.62）编码**：JCA `SHA256withECDSA` / `NONEwithECDSA` 与
 * Apple `SecKeyCreateSignature(...X962...)` 都直接产出该格式，固件签名段无需再转换。
 * 本对象额外提供 `raw r||s` 互转，用于与只接受原始字节的第三方工具/设备联调。
 *
 * P-256 的 r、s 各最多 32 字节；DER 中允许 33 字节（首位是 0x00 正号补位）。
 */
object EcdsaSignatureCodec {
    const val COORDINATE_BYTES: Int = 32

    /** 结构是否合法；不抛异常，供失败分类使用。 */
    fun isValidDer(signature: ByteArray): Boolean = runCatching { parseDer(signature) }.isSuccess

    /** DER → 64 字节 `r || s`；非法结构抛 [EcdsaSignatureFormatException]。 */
    fun derToRaw(signature: ByteArray): ByteArray {
        val (r, s) = parseDer(signature)
        return ByteArray(COORDINATE_BYTES * 2).also { out ->
            r.copyInto(out, COORDINATE_BYTES - r.size)
            s.copyInto(out, COORDINATE_BYTES * 2 - s.size)
        }
    }

    /** 64 字节 `r || s` → DER；长度不符抛 [EcdsaSignatureFormatException]。 */
    fun rawToDer(raw: ByteArray): ByteArray {
        if (raw.size != COORDINATE_BYTES * 2) {
            throw EcdsaSignatureFormatException("原始签名需为 64 字节，实际 ${raw.size}")
        }
        val r = raw.copyOfRange(0, COORDINATE_BYTES)
        val s = raw.copyOfRange(COORDINATE_BYTES, COORDINATE_BYTES * 2)
        val rDer = encodeInteger(r)
        val sDer = encodeInteger(s)
        val content = rDer + sDer
        return encodeLength(content.size).let { lengthBytes ->
            byteArrayOf(SEQUENCE_TAG.toByte()) + lengthBytes + content
        }
    }

    private const val SEQUENCE_TAG = 0x30
    private const val INTEGER_TAG = 0x02

    private fun parseDer(signature: ByteArray): Pair<ByteArray, ByteArray> {
        if (signature.size < 2 || signature[0].toInt() != SEQUENCE_TAG) {
            throw EcdsaSignatureFormatException("缺少 DER SEQUENCE 头")
        }
        val lengthInfo = readLength(signature, 1)
        val contentStart = lengthInfo.second
        val contentEnd = contentStart + lengthInfo.first
        if (contentEnd != signature.size) {
            throw EcdsaSignatureFormatException(
                "DER 长度不符：声明 ${lengthInfo.first} 字节，实际 ${signature.size - contentStart}",
            )
        }
        val r = readInteger(signature, contentStart, contentEnd)
        val s = readInteger(signature, r.second, contentEnd)
        if (s.second != contentEnd) {
            throw EcdsaSignatureFormatException("DER 尾部存在多余内容")
        }
        return r.first to s.first
    }

    /** 读取长度字段，返回 (内容长度, 内容起点)。 */
    private fun readLength(
        bytes: ByteArray,
        offset: Int,
    ): Pair<Int, Int> {
        if (offset >= bytes.size) throw EcdsaSignatureFormatException("长度字段缺失")
        val first = bytes[offset].toInt() and 0xFF
        if (first < 0x80) return first to (offset + 1)
        val count = first and 0x7F
        if (count == 0 || count > 4) throw EcdsaSignatureFormatException("不支持的长度编码")
        if (offset + 1 + count > bytes.size) throw EcdsaSignatureFormatException("长度字段被截断")
        var value = 0
        for (index in 0 until count) {
            value = (value shl 8) or (bytes[offset + 1 + index].toInt() and 0xFF)
        }
        return value to (offset + 1 + count)
    }

    /** 读取 INTEGER，返回 (去前导零后的坐标字节, 下一个字段起点)。 */
    private fun readInteger(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
    ): Pair<ByteArray, Int> {
        if (offset + 2 > limit) throw EcdsaSignatureFormatException("INTEGER 头不完整")
        if (bytes[offset].toInt() != INTEGER_TAG) {
            throw EcdsaSignatureFormatException("预期 INTEGER，实际 0x${bytes[offset].toString(16)}")
        }
        val length = bytes[offset + 1].toInt() and 0xFF
        if (length == 0) throw EcdsaSignatureFormatException("INTEGER 长度为 0")
        val start = offset + 2
        val end = start + length
        if (end > limit) throw EcdsaSignatureFormatException("INTEGER 被截断")
        val content = bytes.copyOfRange(start, end)
        if (content.size > COORDINATE_BYTES + 1) {
            throw EcdsaSignatureFormatException("坐标长度 ${content.size} 超过 P-256 上限")
        }
        val coordinate =
            when {
                content[0].toInt() == 0 -> {
                    if (content.size == 1) {
                        throw EcdsaSignatureFormatException("INTEGER 为零")
                    }
                    if (content[1].toInt() and 0x80 == 0) {
                        throw EcdsaSignatureFormatException("INTEGER 存在非最小前导零")
                    }
                    content.copyOfRange(1, content.size)
                }

                content[0].toInt() and 0x80 != 0 -> throw EcdsaSignatureFormatException("INTEGER 为负")
                else -> content
            }
        if (coordinate.size > COORDINATE_BYTES) {
            throw EcdsaSignatureFormatException("坐标长度 ${coordinate.size} 超过 32 字节")
        }
        return coordinate to end
    }

    private fun encodeInteger(coordinate: ByteArray): ByteArray {
        var start = 0
        while (start < coordinate.size - 1 && coordinate[start].toInt() == 0) start++
        val trimmed = coordinate.copyOfRange(start, coordinate.size)
        val needsPad = trimmed[0].toInt() and 0x80 != 0
        val content = if (needsPad) byteArrayOf(0) + trimmed else trimmed
        return byteArrayOf(INTEGER_TAG.toByte()) + encodeLength(content.size) + content
    }

    private fun encodeLength(length: Int): ByteArray =
        when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte())
        }
}

/**
 * SPKI（X.509 SubjectPublicKeyInfo）辅助解析。
 *
 * Apple `SecKeyCreateWithData` 的 EC 公钥入参是 X9.63 原始点（`04 || X || Y`），
 * 而 PEM/DER 注入通常给的是 SPKI。这里从 SPKI 中取出 BIT STRING 内容，
 * 供 iOS actual 使用；JVM/Android 直接交给 `KeyFactory`，不需要该结果。
 */
object EcdsaPublicKeyEncoding {
    /** P-256 公钥原始点长度：0x04 + 32 字节 X + 32 字节 Y。 */
    const val P256_POINT_BYTES: Int = 65

    /** 提取 P-256 原始点；结构不符或长度不是 65 字节时返回 null。 */
    fun extractP256PointFromSpki(spki: ByteArray): ByteArray? {
        val outer = readSequence(spki, 0, spki.size) ?: return null
        val algorithm = readSequence(spki, outer.second, outer.third) ?: return null
        val bitStringStart = algorithm.third
        if (bitStringStart >= outer.third) return null
        if (spki[bitStringStart].toInt() != 0x03) return null
        val lengthInfo = readLength(spki, bitStringStart + 1, outer.third) ?: return null
        val contentStart = lengthInfo.second
        val contentEnd = contentStart + lengthInfo.first
        if (contentEnd != outer.third) return null
        if (contentStart >= contentEnd) return null
        if (spki[contentStart].toInt() != 0x00) return null // unused bits 必须为 0
        val point = spki.copyOfRange(contentStart + 1, contentEnd)
        return point.takeIf { it.size == P256_POINT_BYTES }
    }

    /** 返回 (内容长度, 内容起点, 内容终点)。 */
    private fun readSequence(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
    ): Triple<Int, Int, Int>? {
        if (offset >= limit || bytes[offset].toInt() != 0x30) return null
        val lengthInfo = readLength(bytes, offset + 1, limit) ?: return null
        val start = lengthInfo.second
        val end = start + lengthInfo.first
        if (end > limit) return null
        return Triple(lengthInfo.first, start, end)
    }

    private fun readLength(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
    ): Pair<Int, Int>? {
        if (offset >= limit) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first < 0x80) return first to (offset + 1)
        val count = first and 0x7F
        if (count == 0 || count > 4 || offset + 1 + count > limit) return null
        var value = 0
        for (index in 0 until count) {
            value = (value shl 8) or (bytes[offset + 1 + index].toInt() and 0xFF)
        }
        return value to (offset + 1 + count)
    }
}

/**
 * 平台相关的 ECDSA 公钥句柄。
 *
 * 只暴露「用已加载的公钥验签」，密钥解析与曲线校验在各平台 actual 内完成。
 */
internal interface PlatformEcdsaKey {
    fun verify(
        digest: ByteArray,
        signatureDer: ByteArray,
    ): Boolean
}

/** 从 SPKI（X.509 SubjectPublicKeyInfo）DER 加载公钥。 */
internal expect fun platformLoadEcdsaKeyFromSpki(der: ByteArray): PlatformEcdsaKey

/** 从 X.509 证书 DER 加载公钥。 */
internal expect fun platformLoadEcdsaKeyFromCertificate(der: ByteArray): PlatformEcdsaKey

/**
 * ECDSA-P256 验签器（公钥/证书注入）。
 *
 * 构造时一次性解析公钥并校验曲线；之后每次验签只做摘要校验与平台验签。
 * 线上签名格式固定为 DER，详细说明见 [EcdsaSignatureCodec]。
 */
class EcdsaP256Verifier private constructor(
    private val key: PlatformEcdsaKey,
    /** 注入来源，仅用于日志/诊断，不包含密钥内容。 */
    val source: Source,
) {
    enum class Source {
        PEM_PUBLIC_KEY,
        PEM_CERTIFICATE,
        DER_PUBLIC_KEY,
        DER_CERTIFICATE,
    }

    /** 算法标识；与协议中的 `ECDSA_P256`（0x02）对应。 */
    val algorithm: String = ALGORITHM

    fun verifyDetailed(
        digest: ByteArray,
        signatureDer: ByteArray,
    ): EcdsaVerificationResult {
        if (digest.size != 32) {
            return EcdsaVerificationResult.Invalid(
                EcdsaFailureCode.DIGEST_LENGTH,
                "P-256 需要 32 字节 SHA-256 摘要，实际 ${digest.size}",
            )
        }
        if (!EcdsaSignatureCodec.isValidDer(signatureDer)) {
            return EcdsaVerificationResult.Invalid(
                EcdsaFailureCode.SIGNATURE_FORMAT,
                "签名不是合法的 DER/X9.62 结构",
            )
        }
        return try {
            if (key.verify(digest, signatureDer)) {
                EcdsaVerificationResult.Valid
            } else {
                EcdsaVerificationResult.Invalid(
                    EcdsaFailureCode.SIGNATURE_MISMATCH,
                    "签名与摘要不匹配（内容被篡改或密钥不一致）",
                )
            }
        } catch (error: Throwable) {
            EcdsaVerificationResult.Invalid(
                EcdsaFailureCode.PLATFORM_ERROR,
                error.message ?: error::class.simpleName ?: "平台验签异常",
            )
        }
    }

    /** 便捷布尔入口；需要失败原因时用 [verifyDetailed]。 */
    fun verify(
        digest: ByteArray,
        signatureDer: ByteArray,
    ): Boolean = verifyDetailed(digest, signatureDer) is EcdsaVerificationResult.Valid

    companion object {
        const val ALGORITHM: String = "ECDSA-P256"

        /** PEM 或 DER 自动识别（PEM 按标签区分公钥/证书；DER 先按 SPKI，再按证书）。 */
        fun fromPemOrDer(input: String): EcdsaP256Verifier {
            val trimmed = input.trim()
            return if (trimmed.contains("-----BEGIN")) fromPem(trimmed) else fromDer(trimmed.encodeToByteArray())
        }

        /**
         * 解析 PEM。支持 `PUBLIC KEY`（SPKI）与 `CERTIFICATE`（X.509）。
         *
         * 私钥 PEM（`PRIVATE KEY`）会被明确拒绝，避免误把私钥打进客户端。
         */
        fun fromPem(pem: String): EcdsaP256Verifier {
            val block =
                PemBlock.parse(pem)
                    ?: throw EcdsaKeyException(EcdsaFailureCode.PUBLIC_KEY_PARSE, "未找到 PEM 头尾")
            val der =
                try {
                    Base64.decode(block.body)
                } catch (error: IllegalArgumentException) {
                    throw EcdsaKeyException(
                        EcdsaFailureCode.PUBLIC_KEY_PARSE,
                        "PEM Base64 解码失败：${error.message}",
                        error,
                    )
                }
            return when (block.label) {
                "PUBLIC KEY" -> fromDer(der, Source.PEM_PUBLIC_KEY)
                "CERTIFICATE" -> fromCertificateDer(der, Source.PEM_CERTIFICATE)
                "PRIVATE KEY", "EC PRIVATE KEY" -> throw EcdsaKeyException(
                    EcdsaFailureCode.PUBLIC_KEY_PARSE,
                    "拒绝加载私钥 PEM，客户端只允许注入公钥或证书",
                )

                else -> throw EcdsaKeyException(
                    EcdsaFailureCode.PUBLIC_KEY_PARSE,
                    "不支持的 PEM 标签：${block.label}",
                )
            }
        }

        /** DER 自动识别：优先 SPKI，失败后按 X.509 证书重试。 */
        fun fromDer(der: ByteArray): EcdsaP256Verifier {
            val spkiError =
                try {
                    return fromDer(der, Source.DER_PUBLIC_KEY)
                } catch (error: EcdsaKeyException) {
                    error
                }
            return try {
                fromCertificateDer(der, Source.DER_CERTIFICATE)
            } catch (certificate: EcdsaKeyException) {
                throw EcdsaKeyException(
                    EcdsaFailureCode.PUBLIC_KEY_PARSE,
                    "DER 既不是合法公钥也不是合法证书：${spkiError.message}；${certificate.message}",
                    certificate,
                )
            }
        }

        fun fromDer(
            der: ByteArray,
            source: Source,
        ): EcdsaP256Verifier {
            val key =
                try {
                    platformLoadEcdsaKeyFromSpki(der)
                } catch (error: EcdsaKeyException) {
                    throw error
                } catch (error: Throwable) {
                    throw EcdsaKeyException(
                        EcdsaFailureCode.PUBLIC_KEY_PARSE,
                        "公钥加载失败：${error.message ?: error::class.simpleName}",
                        error,
                    )
                }
            return EcdsaP256Verifier(key, source)
        }

        fun fromCertificateDer(
            der: ByteArray,
            source: Source,
        ): EcdsaP256Verifier {
            val key =
                try {
                    platformLoadEcdsaKeyFromCertificate(der)
                } catch (error: EcdsaKeyException) {
                    throw error
                } catch (error: Throwable) {
                    throw EcdsaKeyException(
                        EcdsaFailureCode.PUBLIC_KEY_PARSE,
                        "证书加载失败：${error.message ?: error::class.simpleName}",
                        error,
                    )
                }
            return EcdsaP256Verifier(key, source)
        }
    }
}

/** PEM 单块解析：只接受一个头尾完整、标签一致的块。 */
internal data class PemBlock(
    val label: String,
    val body: String,
) {
    companion object {
        @OptIn(ExperimentalEncodingApi::class)
        fun parse(pem: String): PemBlock? {
            val normalized = pem.replace("\r\n", "\n").replace('\r', '\n')
            val lines = normalized.lines()
            val beginIndex = lines.indexOfFirst { it.trim().startsWith(BEGIN_PREFIX) }
            if (beginIndex < 0) return null
            val beginLine = lines[beginIndex].trim()
            if (!beginLine.endsWith(DASHES)) return null
            val label = beginLine.removePrefix(BEGIN_PREFIX).removeSuffix(DASHES).trim()
            val endIndex = lines.indexOfFirst { it.trim() == "$END_PREFIX$label$DASHES" }
            if (endIndex <= beginIndex) return null
            val body =
                lines
                    .subList(beginIndex + 1, endIndex)
                    .joinToString(separator = "")
                    .filterNot { it.isWhitespace() }
            if (body.isEmpty()) return null
            // 只做「是否可解码」校验，真正的 DER 解析交给平台。
            return runCatching {
                Base64.decode(body)
                PemBlock(label, body)
            }.getOrNull()
        }

        private const val BEGIN_PREFIX = "-----BEGIN "
        private const val END_PREFIX = "-----END "
        private const val DASHES = "-----"
    }
}
