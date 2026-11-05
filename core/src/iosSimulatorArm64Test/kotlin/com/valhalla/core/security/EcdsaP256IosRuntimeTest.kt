package com.valhalla.core.security

import com.valhalla.core.security.testdata.EcdsaTestVectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * iOS 模拟器运行时（Kotlin/Native）验签测试。
 *
 * 执行 `EcdsaP256.ios.kt` 的 `SecKeyVerifySignature(...DigestX962SHA256)` 实现，
 * 与 JVM、Android 共用 [EcdsaTestVectors] 中的同一批 OpenSSL 向量。
 */
class EcdsaP256IosRuntimeTest {

    @Test
    fun sharedVectorsVerifyOnIosRuntime() {
        val verifier = EcdsaP256Verifier.fromPem(EcdsaTestVectors.PUBLIC_KEY_PEM)
        var verified = 0
        EcdsaTestVectors.ALL.forEach { vector ->
            assertTrue(
                verifier.verify(vector.digest(), vector.signature()),
                "向量 ${vector.name} 在 iOS 运行时验签失败",
            )
            verified++
        }
        assertEquals(EcdsaTestVectors.ALL.size, verified)
        println("iOS runtime ECDSA verified ${EcdsaTestVectors.ALL.size} shared vectors")
    }

    @Test
    fun tamperedDigestIsRejected() {
        val verifier = EcdsaP256Verifier.fromPem(EcdsaTestVectors.PUBLIC_KEY_PEM)
        val vector = EcdsaTestVectors.VECTOR_1
        val result = verifier.verifyDetailed(
            EcdsaTestVectors.tamperedDigest(vector),
            vector.signature(),
        )
        assertEquals(
            EcdsaFailureCode.SIGNATURE_MISMATCH,
            assertIs<EcdsaVerificationResult.Invalid>(result).code,
        )
    }

    @Test
    fun tamperedSignatureIsRejected() {
        val verifier = EcdsaP256Verifier.fromPem(EcdsaTestVectors.PUBLIC_KEY_PEM)
        val vector = EcdsaTestVectors.VECTOR_1
        assertIs<EcdsaVerificationResult.Invalid>(
            verifier.verifyDetailed(vector.digest(), EcdsaTestVectors.tamperedSignature(vector)),
        )
    }

    @Test
    fun certificateCertificateDerAndPemMatch() {
        val pemVerifier = EcdsaP256Verifier.fromPem(EcdsaTestVectors.CERTIFICATE_PEM)
        val derVerifier = EcdsaP256Verifier.fromDer(EcdsaTestVectors.certificateDer())
        assertEquals(EcdsaP256Verifier.Source.PEM_CERTIFICATE, pemVerifier.source)
        assertEquals(EcdsaP256Verifier.Source.DER_CERTIFICATE, derVerifier.source)
        EcdsaTestVectors.ALL.forEach { vector ->
            assertTrue(pemVerifier.verify(vector.digest(), vector.signature()))
            assertTrue(derVerifier.verify(vector.digest(), vector.signature()))
        }
    }

    @Test
    fun derRawRoundTripStillVerifies() {
        val verifier = EcdsaP256Verifier.fromPem(EcdsaTestVectors.PUBLIC_KEY_PEM)
        val vector = EcdsaTestVectors.VECTOR_1
        val raw = EcdsaSignatureCodec.derToRaw(vector.signature())
        assertEquals(64, raw.size)
        val restored = EcdsaSignatureCodec.rawToDer(raw)
        assertTrue(verifier.verify(vector.digest(), restored))
        assertContentEquals(raw, EcdsaSignatureCodec.derToRaw(restored))
    }

    @Test
    fun nonP256KeyIsRejected() {
        val error = runCatching {
            EcdsaP256Verifier.fromPem(EcdsaTestVectors.P384_PUBLIC_KEY_PEM)
        }.exceptionOrNull()
        assertEquals(
            EcdsaFailureCode.PUBLIC_KEY_UNSUPPORTED,
            assertIs<EcdsaKeyException>(error).code,
        )
    }
}
