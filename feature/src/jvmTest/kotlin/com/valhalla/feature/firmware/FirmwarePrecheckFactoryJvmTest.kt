package com.valhalla.feature.firmware

import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwareHeader
import com.valhalla.core.firmware.FirmwareParser
import com.valhalla.core.firmware.FirmwareSignature
import com.valhalla.core.firmware.FirmwareSignatureAlgorithm
import com.valhalla.core.firmware.FirmwareSource
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.security.Sha256Digest
import com.valhalla.core.security.testdata.EcdsaTestVectors
import kotlinx.coroutines.test.runTest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 构建期公钥注入的运行时行为测试（JVM）。
 *
 * 覆盖：注入有效公钥可验签、未注入回退 WARN、非法公钥失败关闭。
 * 签名私钥仅存在于测试向量中，仓库不含生产私钥。
 */
class FirmwarePrecheckFactoryJvmTest {
    private val version = FirmwareVersion(1, 2, 3, 4, "release")
    private val currentVersion = FirmwareVersion(1, 2, 3, 3, "release")
    private val payload = ByteArray(512) { (it * 13 % 251).toByte() }

    @Test
    fun injectedValidPublicKeyVerifiesSignedFirmware() =
        runTest {
            val packageBytes = signedPackage()
            val firmware = FirmwareParser.parse("injected.bin", FirmwareSource.memory(packageBytes))
            val result =
                FirmwarePrecheckFactory
                    .production(EcdsaTestVectors.PUBLIC_KEY_PEM)
                    .check(firmware, currentVersion, "VH-100")

            assertTrue(result.passed, result.summary())
            assertTrue(result.errors.isEmpty())
        }

    @Test
    fun noInjectedKeyFallsBackToWarnForUnsignedFirmware() =
        runTest {
            val firmware = FirmwareParser.parse("unsigned.bin", FirmwareSource.memory(unsignedPackage()))
            val result =
                FirmwarePrecheckFactory
                    .production(null)
                    .check(firmware, currentVersion, "VH-100")

            assertTrue(result.passed, result.summary())
            assertTrue(result.warnings.any { it.contains("未签名") || it.contains("警告") })
        }

    @Test
    fun invalidInjectedKeyIsFailClosed() =
        runTest {
            val firmware = FirmwareParser.parse("signed.bin", FirmwareSource.memory(signedPackage()))
            val result =
                FirmwarePrecheckFactory
                    .production("-----BEGIN PUBLIC KEY-----\nnot-base64!!\n-----END PUBLIC KEY-----")
                    .check(firmware, currentVersion, "VH-100")

            assertFalse(result.passed)
            assertTrue(result.errors.any { it is FirmwareException.SignatureInvalid })
        }

    @Test
    fun injectedCertificatePemIsEquivalentToPublicKeyPem() =
        runTest {
            val firmware = FirmwareParser.parse("cert.bin", FirmwareSource.memory(signedPackage()))
            val result =
                FirmwarePrecheckFactory
                    .production(EcdsaTestVectors.CERTIFICATE_PEM)
                    .check(firmware, currentVersion, "VH-100")

            assertTrue(result.passed, result.summary())
        }

    private fun unsignedPackage(): ByteArray {
        val header = header(hasSignature = false)
        return header.encode() + payload
    }

    private fun signedPackage(): ByteArray {
        val header = header(hasSignature = true)
        return header.encode() + payload + sign(header).encode()
    }

    private fun header(hasSignature: Boolean) =
        FirmwareHeader(
            firmwareVersion = version,
            targetModels = listOf("VH-100"),
            payloadLength = payload.size.toLong(),
            payloadCrc32 = Crc32.compute(payload),
            hasSignature = hasSignature,
        )

    private fun sign(header: FirmwareHeader): FirmwareSignature {
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

    private fun privateKey() =
        KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(EcdsaTestVectors.pemBodyToDer(EcdsaTestVectors.PRIVATE_KEY_PEM)),
        )
}
