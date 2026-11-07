package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.security.Sha256Digest
import kotlinx.coroutines.test.runTest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 生产 ECDSA 验签的整包链路测试（仅 JVM）。
 *
 * 用 OpenSSL 生成的**测试私钥**在测试运行时对固件摘要签名，构造与真实固件
 * 相同布局的「头 + payload + 签名段」，再交给 [FirmwarePrechecker]。
 * 私钥只存在于测试源码，未进入任何生产包。
 */
class EcdsaFirmwarePrecheckJvmTest {
    private val publicKeyPem =
        """
        -----BEGIN PUBLIC KEY-----
        MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEWXWes/sli2RbqBvrh8XJjxoqEG9U
        hImkFXqNjdLEQwzeHuLAidPNy1Qtt895nkOTUppNHfii/mVVw4GY/C/CnQ==
        -----END PUBLIC KEY-----
        """.trimIndent()

    private val privateKeyPem =
        """
        -----BEGIN PRIVATE KEY-----
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgBmjv6/k/KKZBIaO2
        RPq2xxLHrtH9I+kvhUN6owM73oahRANCAARZdZ6z+yWLZFuoG+uHxcmPGioQb1SE
        iaQVeo2N0sRDDN4e4sCJ083LVC23z3meQ5NSmk0d+KL+ZVXDgZj8L8Kd
        -----END PRIVATE KEY-----
        """.trimIndent()

    private val version = FirmwareVersion(1, 2, 3, 4, "release")
    private val currentVersion = FirmwareVersion(1, 2, 3, 3, "release")
    private val payload = ByteArray(512) { (it * 11 % 251).toByte() }

    @Test
    fun `ECDSA 签名的固件在 REQUIRE 策略下通过预检`() =
        runTest {
            val firmware = FirmwareParser.parse("ecdsa.bin", FirmwareSource.memory(signedPackage()))
            val result =
                prechecker(FirmwareSignaturePolicy.REQUIRE)
                    .check(firmware, currentVersion, "VH-100")

            assertTrue(result.passed, result.summary())
            assertTrue(result.errors.isEmpty())
        }

    @Test
    fun `签名被篡改时 ECDSA 验签失败`() =
        runTest {
            val bytes =
                signedPackage().also {
                    it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
                }
            val firmware = FirmwareParser.parse("tampered.bin", FirmwareSource.memory(bytes))
            val result =
                prechecker(FirmwareSignaturePolicy.WARN)
                    .check(firmware, currentVersion, "VH-100")

            assertFalse(result.passed)
            assertTrue(result.errors.any { it is FirmwareException.SignatureInvalid })
        }

    @Test
    fun `算法编号被改成 HMAC 时 ECDSA 验签器拒绝`() =
        runTest {
            val bytes =
                signedPackage().also {
                    it[FirmwareHeader.SIZE + payload.size] = 0x01 // ECDSA(0x02) → HMAC(0x01)
                }
            val firmware = FirmwareParser.parse("wrong-alg.bin", FirmwareSource.memory(bytes))
            val result =
                prechecker(FirmwareSignaturePolicy.REQUIRE)
                    .check(firmware, currentVersion, "VH-100")

            assertFalse(result.passed)
            assertTrue(result.errors.any { it is FirmwareException.SignatureInvalid })
        }

    @Test
    fun `payload 被篡改且重算头 CRC 后签名仍失败`() =
        runTest {
            val tamperedPayload = payload.copyOf().also { it[100] = (it[100].toInt() xor 0xFF).toByte() }
            val originalHeader = header(payload)
            val signature = sign(originalHeader, payload)
            val tamperedHeader = header(tamperedPayload)
            val bytes = tamperedHeader.encode() + tamperedPayload + signature.encode()

            val firmware = FirmwareParser.parse("tampered.bin", FirmwareSource.memory(bytes))
            val result =
                prechecker(FirmwareSignaturePolicy.REQUIRE)
                    .check(firmware, currentVersion, "VH-100")

            assertFalse(result.passed)
            assertTrue(result.errors.none { it is FirmwareException.PayloadChecksumMismatch })
            assertTrue(result.errors.any { it is FirmwareException.SignatureInvalid })
        }

    @Test
    fun `证书形式注入的公钥与 PEM 公钥结果一致`() =
        runTest {
            val firmware = FirmwareParser.parse("ecdsa.bin", FirmwareSource.memory(signedPackage()))
            val fromCertificate =
                FirmwarePrechecker(
                    signatureVerifier = EcdsaP256FirmwareVerifier.fromPem(certificatePem),
                    signaturePolicy = FirmwareSignaturePolicy.REQUIRE,
                ).check(firmware, currentVersion, "VH-100")

            assertTrue(fromCertificate.passed)
        }

    private fun prechecker(policy: FirmwareSignaturePolicy) =
        FirmwarePrechecker(
            signatureVerifier = EcdsaP256FirmwareVerifier.fromPem(publicKeyPem),
            signaturePolicy = policy,
        )

    private fun header(payload: ByteArray) =
        FirmwareHeader(
            firmwareVersion = version,
            targetModels = listOf("VH-100"),
            payloadLength = payload.size.toLong(),
            payloadCrc32 = Crc32.compute(payload),
            hasSignature = true,
        )

    private fun sign(
        header: FirmwareHeader,
        payload: ByteArray,
    ): FirmwareSignature {
        val digest = Sha256Digest().update(header.encode()).update(payload).digest()
        val signature =
            Signature
                .getInstance("NONEwithECDSA")
                .apply {
                    initSign(privateKey())
                    update(digest)
                }.sign()
        return FirmwareSignature(FirmwareSignatureAlgorithm.ECDSA_P256, signature)
    }

    private fun signedPackage(): ByteArray {
        val header = header(payload)
        return header.encode() + payload + sign(header, payload).encode()
    }

    private fun privateKey() =
        KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(pemBody(privateKeyPem)),
        )

    private fun pemBody(pem: String): ByteArray =
        Base64.getDecoder().decode(
            pem.lines().filterNot { it.startsWith("-----") || it.isBlank() }.joinToString(""),
        )

    private val certificatePem: String by lazy {
        """
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
    }
}
