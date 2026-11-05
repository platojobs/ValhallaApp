@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.valhalla.core.security

import com.valhalla.core.firmware.EcdsaP256FirmwareVerifier
import com.valhalla.core.protocol.Hex
import com.valhalla.core.security.testdata.EcdsaTestVectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ECDSA-P256 验签与格式对齐测试（JVM 分支）。
 *
 * 测试向量统一来自 [EcdsaTestVectors]：同一份常量同时供 Android instrumented 与
 * iOS 模拟器运行时测试使用，保证三端不是各写一套数据。
 */
class EcdsaP256VerifierTest {

    private val publicKeyPem = EcdsaTestVectors.PUBLIC_KEY_PEM
    private val certificatePem = EcdsaTestVectors.CERTIFICATE_PEM
    private val p384PublicKeyPem = EcdsaTestVectors.P384_PUBLIC_KEY_PEM

    private val message1 = EcdsaTestVectors.VECTOR_1.message
    private val digest1 = EcdsaTestVectors.VECTOR_1.digestHex
    private val signature1 = EcdsaTestVectors.VECTOR_1.signatureHex

    private val message2 = EcdsaTestVectors.VECTOR_2.message
    private val digest2 = EcdsaTestVectors.VECTOR_2.digestHex
    private val signature2 = EcdsaTestVectors.VECTOR_2.signatureHex

    @Test
    fun `测试向量摘要确实来自 SHA-256`() {
        assertContentEquals(
            Hex.decode(digest1),
            Sha256.compute(message1.encodeToByteArray()),
        )
        assertContentEquals(
            Hex.decode(digest2),
            Sha256.compute(message2.encodeToByteArray()),
        )
    }

    @Test
    fun `PEM 公钥注入后两组标准向量均验签通过`() {
        val verifier = EcdsaP256Verifier.fromPem(publicKeyPem)
        assertEquals(EcdsaP256Verifier.Source.PEM_PUBLIC_KEY, verifier.source)

        val results = listOf(
            digest1 to signature1,
            digest2 to signature2,
        ).map { (digest, signature) ->
            verifier.verifyDetailed(Hex.decode(digest), Hex.decode(signature))
        }

        assertTrue(results.all { it is EcdsaVerificationResult.Valid })
        assertTrue(verifier.verify(Hex.decode(digest1), Hex.decode(signature1)))
    }

    @Test
    fun `证书 PEM 注入得到同一公钥并可验签`() {
        val verifier = EcdsaP256Verifier.fromPem(certificatePem)
        assertEquals(EcdsaP256Verifier.Source.PEM_CERTIFICATE, verifier.source)
        assertTrue(verifier.verify(Hex.decode(digest1), Hex.decode(signature1)))
    }

    @Test
    fun `DER 证书自动识别`() {
        val der = certificateDer()
        val verifier = EcdsaP256Verifier.fromDer(der)
        assertEquals(EcdsaP256Verifier.Source.DER_CERTIFICATE, verifier.source)
        assertTrue(verifier.verify(Hex.decode(digest1), Hex.decode(signature1)))
    }

    @Test
    fun `篡改摘要后验签失败并分类为不匹配`() {
        val verifier = EcdsaP256Verifier.fromPem(publicKeyPem)
        val tampered = Hex.decode(digest1).also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val result = verifier.verifyDetailed(tampered, Hex.decode(signature1))

        val invalid = assertIs<EcdsaVerificationResult.Invalid>(result)
        assertEquals(EcdsaFailureCode.SIGNATURE_MISMATCH, invalid.code)
    }

    @Test
    fun `篡改签名后验签失败`() {
        val verifier = EcdsaP256Verifier.fromPem(publicKeyPem)
        val tampered = Hex.decode(signature1).also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        assertIs<EcdsaVerificationResult.Invalid>(
            verifier.verifyDetailed(Hex.decode(digest1), tampered),
        )
    }

    @Test
    fun `摘要长度非法直接分类为 DIGEST_LENGTH`() {
        val verifier = EcdsaP256Verifier.fromPem(publicKeyPem)
        val result = verifier.verifyDetailed(ByteArray(31), Hex.decode(signature1))
        assertEquals(
            EcdsaFailureCode.DIGEST_LENGTH,
            assertIs<EcdsaVerificationResult.Invalid>(result).code,
        )
    }

    @Test
    fun `非法 DER 结构分类为 SIGNATURE_FORMAT`() {
        val verifier = EcdsaP256Verifier.fromPem(publicKeyPem)
        val truncated = Hex.decode(signature1).copyOf(10)
        val result = verifier.verifyDetailed(Hex.decode(digest1), truncated)
        assertEquals(
            EcdsaFailureCode.SIGNATURE_FORMAT,
            assertIs<EcdsaVerificationResult.Invalid>(result).code,
        )
    }

    @Test
    fun `DER 与原始 r s 互转后仍可通过验签`() {
        val verifier = EcdsaP256Verifier.fromPem(publicKeyPem)
        val raw = EcdsaSignatureCodec.derToRaw(Hex.decode(signature1))
        assertEquals(64, raw.size)

        val restored = EcdsaSignatureCodec.rawToDer(raw)
        assertTrue(verifier.verify(Hex.decode(digest1), restored))
        // DER → raw → DER 应回到等价结构（首字节与长度一致）。
        assertEquals(Hex.decode(signature1)[0], restored[0])
        assertContentEquals(raw, EcdsaSignatureCodec.derToRaw(restored))
    }

    @Test
    fun `非 DER 字节序列不会误判为合法签名`() {
        assertTrue(EcdsaSignatureCodec.isValidDer(Hex.decode(signature1)))
        assertTrue(!EcdsaSignatureCodec.isValidDer(ByteArray(64)))
        assertTrue(!EcdsaSignatureCodec.isValidDer(byteArrayOf(0x30, 0x00)))
        assertFailsWith<EcdsaSignatureFormatException> {
            EcdsaSignatureCodec.derToRaw(Hex.decode(signature1) + byteArrayOf(0x00))
        }
    }

    @Test
    fun `非 P-256 公钥被明确拒绝`() {
        val error = assertFailsWith<EcdsaKeyException> {
            EcdsaP256Verifier.fromPem(p384PublicKeyPem)
        }
        assertEquals(EcdsaFailureCode.PUBLIC_KEY_UNSUPPORTED, error.code)
    }

    @Test
    fun `私钥 PEM 被拒绝加载`() {
        val error = assertFailsWith<EcdsaKeyException> {
            EcdsaP256Verifier.fromPem(EcdsaTestVectors.PRIVATE_KEY_PEM)
        }
        assertEquals(EcdsaFailureCode.PUBLIC_KEY_PARSE, error.code)
    }

    @Test
    fun `损坏的 PEM 分类为公钥解析失败`() {
        val error = assertFailsWith<EcdsaKeyException> {
            EcdsaP256Verifier.fromPem("-----BEGIN PUBLIC KEY-----\nnot-base64!\n-----END PUBLIC KEY-----")
        }
        assertEquals(EcdsaFailureCode.PUBLIC_KEY_PARSE, error.code)
    }

    @Test
    fun `SPKI 提取出 65 字节 P-256 原始点`() {
        val der = pemBodyToDer(publicKeyPem)
        val point = EcdsaPublicKeyEncoding.extractP256PointFromSpki(der)
        assertEquals(EcdsaPublicKeyEncoding.P256_POINT_BYTES, point?.size)
        assertEquals(0x04, point!![0].toInt())
    }

    @Test
    fun `固件验签器只接受 ECDSA 算法编号`() {
        val verifier = EcdsaP256FirmwareVerifier(EcdsaP256Verifier.fromPem(publicKeyPem))
        val digest = Hex.decode(digest1)
        val ecdsa = com.valhalla.core.firmware.FirmwareSignature(
            com.valhalla.core.firmware.FirmwareSignatureAlgorithm.ECDSA_P256,
            Hex.decode(signature1),
        )
        assertTrue(verifier.verify(digest, ecdsa))

        val hmac = com.valhalla.core.firmware.FirmwareSignature(
            com.valhalla.core.firmware.FirmwareSignatureAlgorithm.HMAC_SHA256,
            Hex.decode(signature1),
        )
        val result = verifier.verifyDetailed(digest, hmac)
        assertEquals(
            EcdsaFailureCode.ALGORITHM_MISMATCH,
            assertIs<EcdsaVerificationResult.Invalid>(result).code,
        )
    }

    private fun certificateDer(): ByteArray = EcdsaTestVectors.certificateDer()

    private fun pemBodyToDer(pem: String): ByteArray = EcdsaTestVectors.pemBodyToDer(pem)
}
