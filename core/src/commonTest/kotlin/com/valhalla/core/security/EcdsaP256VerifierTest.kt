@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.valhalla.core.security

import com.valhalla.core.firmware.EcdsaP256FirmwareVerifier
import com.valhalla.core.protocol.Hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ECDSA-P256 验签与格式对齐测试。
 *
 * 测试向量由 **OpenSSL 3** 独立生成（`openssl pkeyutl -sign -in digest.bin`），
 * 摘要为原始 32 字节 SHA-256，签名格式为 DER/X9.62：
 *
 * - 公钥/cert 仅用于测试，不得进入生产包；
 * - 同一组向量在 JVM（`NONEwithECDSA`）、Android（`java.security`）、
 *   iOS（`SecKeyVerifySignature(...DigestX962SHA256)`）上应得到一致结果；
 *   本环境实际执行的是 JVM 分支，其余两支只完成编译与链接。
 */
class EcdsaP256VerifierTest {

    private val publicKeyPem = """
        -----BEGIN PUBLIC KEY-----
        MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEWXWes/sli2RbqBvrh8XJjxoqEG9U
        hImkFXqNjdLEQwzeHuLAidPNy1Qtt895nkOTUppNHfii/mVVw4GY/C/CnQ==
        -----END PUBLIC KEY-----
    """.trimIndent()

    private val certificatePem = """
        -----BEGIN CERTIFICATE-----
        MIIBhTCCASygAwIBAgIUf18XfofjsddqGFeocSu3BvMsS68wCgYIKoZIzj0EAwIw
        KTEnMCUGA1UEAwweVmFsaGFsbGEgVGVzdCBGaXJtd2FyZSBTaWduaW5nMB4XDTI2
        MTEwNTAwMDgxOVoXDTM2MTEwMjAwMDgxOVowKTEnMCUGA1UEAwweVmFsaGFsbGEg
        VGVzdCBGaXJtd2FyZSBTaWduaW5nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE
        WXWes/sli2RbqBvrh8XJjxoqEG9UhImkFXqNjdLEQwzeHuLAidPNy1Qtt895nkOT
        UppNHfii/mVVw4GY/C/CnaMyMDAwHQYDVR0OBBYEFPKs26S8VUoO7VNmI5BWQrin
        cxnFMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIgUpjFyE+zTmjT
        wCBoSPDYnb4lODfKsz30eawxuO7jUy0CIBaoeqJf/qngqH+gtV1T8OmDEKBh7Cv5
        v67LEY7NgKAb
        -----END CERTIFICATE-----
    """.trimIndent()

    /** P-384 公钥，用于验证曲线白名单。 */
    private val p384PublicKeyPem = """
        -----BEGIN PUBLIC KEY-----
        MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE0TxUcY1BxR/7nzjbv26JVxjb5nPPRs4t
        NXokZKPA/PbXWWcOQbPk+6674QnrOKP4qVYc2e7L+CVGfdJdsshZrbIsOAdZLLrf
        NGSlxxCLSZoNHM+3OzGrnaWfRjulGPqM
        -----END PUBLIC KEY-----
    """.trimIndent()

    private val message1 = "valhalla-ecdsa-test-vector-1"
    private val digest1 = "aa0b3dcd03296f9e6b2073455f1721eb39534746a8a627abce2f2a99a5ef2e5d"
    private val signature1 =
        "30450221008a6af213d443e3b16549b1b497eaee97dd7226e1fc52ee65311e392bfb234b87" +
            "022027178caeef2d510c5e786e6315ce35b5608b28313f9516b70c29ab38e377299e"

    private val message2 = "valhalla-ecdsa-test-vector-2"
    private val digest2 = "5338e9a61e427a555b8bb212b4afb49c027918a286282d8787fb590144f223a6"
    private val signature2 =
        "3045022100876d2333e7c3831bec73aa54ce83251bb410632e93573d2950ace168a1707494" +
            "02203d48b85f4e98b0e49c07f133b8fe924cd6322d3e90b0e23735d2954527d7d3b8"

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
            EcdsaP256Verifier.fromPem(
                """
                -----BEGIN PRIVATE KEY-----
                MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgBmjv6/k/KKZBIaO2
                RPq2xxLHrtH9I+kvhUN6owM73oahRANCAARZdZ6z+yWLZFuoG+uHxcmPGioQb1SE
                iaQVeo2N0sRDDN4e4sCJ083LVC23z3meQ5NSmk0d+KL+ZVXDgZj8L8Kd
                -----END PRIVATE KEY-----
                """.trimIndent(),
            )
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

    private fun certificateDer(): ByteArray = pemBodyToDer(certificatePem)

    private fun pemBodyToDer(pem: String): ByteArray {
        val body = pem.lines()
            .filterNot { it.startsWith("-----") || it.isBlank() }
            .joinToString(separator = "")
        return kotlin.io.encoding.Base64.decode(body)
    }
}
