package com.valhalla.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.core.firmware.EcdsaP256FirmwareVerifier
import com.valhalla.core.firmware.FirmwareSignature
import com.valhalla.core.firmware.FirmwareSignatureAlgorithm
import com.valhalla.core.security.testdata.EcdsaTestVectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 运行时（instrumentation）验签测试。
 *
 * 直接执行 `EcdsaP256.android.kt` 的 `java.security.Signature("NONEwithECDSA")` 实现，
 * 与 JVM、iOS 共用 [EcdsaTestVectors] 中的同一批 OpenSSL 向量。
 */
@RunWith(AndroidJUnit4::class)
class EcdsaP256AndroidRuntimeTest {

    @Test
    fun sharedVectorsVerifyOnAndroidRuntime() {
        val verifier = EcdsaP256Verifier.fromPem(EcdsaTestVectors.PUBLIC_KEY_PEM)
        var verified = 0
        EcdsaTestVectors.ALL.forEach { vector ->
            assertTrue(
                "向量 ${vector.name} 在 Android 运行时验签失败",
                verifier.verify(vector.digest(), vector.signature()),
            )
            verified++
        }
        assertEquals(EcdsaTestVectors.ALL.size, verified)
        println("Android runtime ECDSA verified ${EcdsaTestVectors.ALL.size} shared vectors")
    }

    @Test
    fun tamperedDigestIsRejectedWithMismatch() {
        val verifier = EcdsaP256Verifier.fromPem(EcdsaTestVectors.PUBLIC_KEY_PEM)
        val vector = EcdsaTestVectors.VECTOR_1
        val result = verifier.verifyDetailed(
            EcdsaTestVectors.tamperedDigest(vector),
            vector.signature(),
        )
        val invalid = result as? EcdsaVerificationResult.Invalid
        if (invalid == null) {
            fail("篡改摘要不应验签通过")
            return
        }
        assertEquals(EcdsaFailureCode.SIGNATURE_MISMATCH, invalid.code)
    }

    @Test
    fun tamperedSignatureIsRejected() {
        val verifier = EcdsaP256Verifier.fromPem(EcdsaTestVectors.PUBLIC_KEY_PEM)
        val vector = EcdsaTestVectors.VECTOR_1
        val result = verifier.verifyDetailed(
            vector.digest(),
            EcdsaTestVectors.tamperedSignature(vector),
        )
        assertTrue(result is EcdsaVerificationResult.Invalid)
    }

    @Test
    fun certificateAndDerInjectionMatchPemResult() {
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
        assertArrayEquals(raw, EcdsaSignatureCodec.derToRaw(restored))
    }

    @Test
    fun nonP256KeyIsRejected() {
        try {
            EcdsaP256Verifier.fromPem(EcdsaTestVectors.P384_PUBLIC_KEY_PEM)
            fail("P-384 公钥不应被接受")
        } catch (error: EcdsaKeyException) {
            assertEquals(EcdsaFailureCode.PUBLIC_KEY_UNSUPPORTED, error.code)
        }
    }

    @Test
    fun digestLengthAndFirmwareAlgorithmAreChecked() {
        val verifier = EcdsaP256Verifier.fromPem(EcdsaTestVectors.PUBLIC_KEY_PEM)
        val lengthResult = verifier.verifyDetailed(ByteArray(31), EcdsaTestVectors.VECTOR_1.signature())
        assertEquals(
            EcdsaFailureCode.DIGEST_LENGTH,
            (lengthResult as EcdsaVerificationResult.Invalid).code,
        )

        val firmwareVerifier = EcdsaP256FirmwareVerifier(verifier)
        val vector = EcdsaTestVectors.VECTOR_1
        assertTrue(
            firmwareVerifier.verify(
                vector.digest(),
                FirmwareSignature(FirmwareSignatureAlgorithm.ECDSA_P256, vector.signature()),
            ),
        )
        val mismatch = firmwareVerifier.verifyDetailed(
            vector.digest(),
            FirmwareSignature(FirmwareSignatureAlgorithm.HMAC_SHA256, vector.signature()),
        )
        assertEquals(
            EcdsaFailureCode.ALGORITHM_MISMATCH,
            (mismatch as EcdsaVerificationResult.Invalid).code,
        )
    }
}
