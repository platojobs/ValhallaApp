package com.valhalla.core.firmware

import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.FirmwareVersion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareSignatureTest {

    private val secret = "firmware-test-secret".encodeToByteArray()
    private val version = FirmwareVersion(1, 2, 3, 4, "release")
    private val currentVersion = FirmwareVersion(1, 2, 3, 3, "release")
    private val payload = ByteArray(512) { (it * 7 % 251).toByte() }

    private fun header(payload: ByteArray = this.payload, signed: Boolean = false) = FirmwareHeader(
        firmwareVersion = version,
        targetModels = listOf("VH-100"),
        payloadLength = payload.size.toLong(),
        payloadCrc32 = Crc32.compute(payload),
        hasSignature = signed,
    )

    private fun signedPackageBytes(
        payload: ByteArray = this.payload,
        signingSecret: ByteArray = secret,
        signatureOverride: FirmwareSignature? = null,
    ): ByteArray {
        val header = header(payload, signed = true)
        val signature = signatureOverride ?: FirmwareTestSigner.signWithHmacKey(header, payload, signingSecret)
        return header.encode() + payload + signature.encode()
    }

    private fun prechecker(
        policy: FirmwareSignaturePolicy,
        verifier: FirmwareSignatureVerifier? = HmacSha256TestVerifier(secret),
    ) = FirmwarePrechecker(signatureVerifier = verifier, signaturePolicy = policy)

    @Test
    fun `已签名固件解析出签名段`() = runTest {
        val source = FirmwareSource.memory(signedPackageBytes())
        val firmware = FirmwareParser.parse("signed.bin", source)

        assertTrue(firmware.header.hasSignature)
        val signature = assertNotNull(firmware.signature)
        assertEquals(FirmwareSignatureAlgorithm.HMAC_SHA256, signature.algorithm)
        assertEquals(firmware.payloadOffset + payload.size.toLong(), firmware.signatureOffset)
        assertTrue(firmware.signatureOffset + signature.encodedSize.toLong() == source.size)
    }

    @Test
    fun `旧格式无签名固件仍可解析`() = runTest {
        val firmware = FirmwareParser.parse(
            "legacy.bin",
            FirmwareSource.memory(header().encode() + payload),
        )

        assertFalse(firmware.header.hasSignature)
        assertNull(firmware.signature)
    }

    @Test
    fun `签名摘要与测试签名器一致`() = runTest {
        val firmware = FirmwareParser.parse("signed.bin", FirmwareSource.memory(signedPackageBytes()))
        val expected = FirmwareTestSigner.sha256Digest(header(signed = true), payload)
        assertTrue(expected.contentEquals(firmware.computeSignatureDigest()))
    }

    @Test
    fun `有效签名在 REQUIRE 策略下通过`() = runTest {
        val firmware = FirmwareParser.parse("signed.bin", FirmwareSource.memory(signedPackageBytes()))
        val result = prechecker(FirmwareSignaturePolicy.REQUIRE).check(firmware, currentVersion, "VH-100")

        assertTrue(result.passed)
        assertTrue(result.errors.isEmpty())
        assertNull(result.warningSummary())
    }

    @Test
    fun `签名被篡改时验签失败`() = runTest {
        val bytes = signedPackageBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val firmware = FirmwareParser.parse("tampered.bin", FirmwareSource.memory(bytes))
        val result = prechecker(FirmwareSignaturePolicy.WARN).check(firmware, currentVersion, "VH-100")

        assertFalse(result.passed)
        assertTrue(result.errors.any { it is FirmwareException.SignatureInvalid })
    }

    @Test
    fun `payload 被篡改且重算头部 CRC 后仍因签名失败`() = runTest {
        val tamperedPayload = payload.copyOf().also { it[100] = (it[100].toInt() xor 0xFF).toByte() }
        // 攻击者可以重算文件头 CRC，但没有密钥无法重签。
        val originalSignature = FirmwareTestSigner.signWithHmacKey(header(payload, signed = true), payload, secret)
        val bytes = header(tamperedPayload, signed = true).encode() + tamperedPayload + originalSignature.encode()
        val firmware = FirmwareParser.parse("tampered.bin", FirmwareSource.memory(bytes))
        val result = prechecker(FirmwareSignaturePolicy.REQUIRE).check(firmware, currentVersion, "VH-100")

        assertFalse(result.passed)
        assertTrue(result.errors.none { it is FirmwareException.PayloadChecksumMismatch })
        assertTrue(result.errors.any { it is FirmwareException.SignatureInvalid })
    }

    @Test
    fun `错误密钥导致验签失败`() = runTest {
        val firmware = FirmwareParser.parse(
            "signed.bin",
            FirmwareSource.memory(signedPackageBytes(signingSecret = "wrong-key".encodeToByteArray())),
        )
        val result = prechecker(FirmwareSignaturePolicy.REQUIRE).check(firmware, currentVersion, "VH-100")

        assertFalse(result.passed)
        assertTrue(result.errors.any { it is FirmwareException.SignatureInvalid })
    }

    @Test
    fun `旧格式无签名在 REQUIRE 策略下被拒绝`() = runTest {
        val firmware = FirmwareParser.parse("legacy.bin", FirmwareSource.memory(header().encode() + payload))
        val result = prechecker(FirmwareSignaturePolicy.REQUIRE).check(firmware, currentVersion, "VH-100")

        assertFalse(result.passed)
        assertTrue(result.errors.any { it is FirmwareException.SignatureMissing })
    }

    @Test
    fun `旧格式无签名在 WARN 策略下仅告警`() = runTest {
        val firmware = FirmwareParser.parse("legacy.bin", FirmwareSource.memory(header().encode() + payload))
        val result = prechecker(FirmwareSignaturePolicy.WARN).check(firmware, currentVersion, "VH-100")

        assertTrue(result.passed)
        assertNotNull(result.warningSummary())
        assertTrue(result.warningSummary()!!.contains("未签名"))
    }

    @Test
    fun `旧格式无签名在 IGNORE 策略下静默通过`() = runTest {
        val firmware = FirmwareParser.parse("legacy.bin", FirmwareSource.memory(header().encode() + payload))
        val result = prechecker(FirmwareSignaturePolicy.IGNORE).check(firmware, currentVersion, "VH-100")

        assertTrue(result.passed)
        assertNull(result.warningSummary())
    }

    @Test
    fun `已签名但未配置验签器时 REQUIRE 拒绝`() = runTest {
        val firmware = FirmwareParser.parse("signed.bin", FirmwareSource.memory(signedPackageBytes()))
        val result = prechecker(
            policy = FirmwareSignaturePolicy.REQUIRE,
            verifier = null,
        ).check(firmware, currentVersion, "VH-100")

        assertFalse(result.passed)
        assertTrue(result.errors.any { it is FirmwareException.SignatureVerifierUnavailable })
    }

    @Test
    fun `已签名但未配置验签器时 WARN 仅告警`() = runTest {
        val firmware = FirmwareParser.parse("signed.bin", FirmwareSource.memory(signedPackageBytes()))
        val result = prechecker(
            policy = FirmwareSignaturePolicy.WARN,
            verifier = null,
        ).check(firmware, currentVersion, "VH-100")

        assertTrue(result.passed)
        assertNotNull(result.warningSummary())
    }

    @Test
    fun `标志位声明签名但缺少签名段时解析失败`() = runTest {
        val bytes = header(payload, signed = true).encode() + payload
        val error = runCatching {
            FirmwareParser.parse("broken.bin", FirmwareSource.memory(bytes))
        }.exceptionOrNull()

        assertTrue(error is FirmwareException.SignatureSectionInvalid)
    }

    @Test
    fun `签名段长度与实际文件不符时解析失败`() = runTest {
        val bytes = signedPackageBytes()
        val header = bytes.copyOfRange(0, FirmwareHeader.SIZE)
        val payloadPart = bytes.copyOfRange(FirmwareHeader.SIZE, FirmwareHeader.SIZE + payload.size)
        val section = bytes.copyOfRange(FirmwareHeader.SIZE + payload.size, bytes.size)
        val longerSection = section.copyOf(section.size + 5)
        // 追加尾部多余字节，但签名段声明的长度不变。
        val error = runCatching {
            FirmwareParser.parse(
                "broken.bin",
                FirmwareSource.memory(header + payloadPart + longerSection),
            )
        }.exceptionOrNull()

        assertTrue(error is FirmwareException.SignatureSectionInvalid)
    }

    @Test
    fun `未知签名算法编号被拒绝`() = runTest {
        val header = header(signed = true)
        val invalidSection = ByteArray(FirmwareSignature.HEADER_SIZE + 4)
        invalidSection[0] = 0x03
        invalidSection[1] = 4
        val bytes = header.encode() + payload + invalidSection
        val error = runCatching {
            FirmwareParser.parse("broken.bin", FirmwareSource.memory(bytes))
        }.exceptionOrNull()

        assertTrue(error is FirmwareException.SignatureAlgorithmUnsupported)
    }

    @Test
    fun `签名段反向编码与解析一致`() {
        val signature = FirmwareSignature(FirmwareSignatureAlgorithm.HMAC_SHA256, ByteArray(32) { it.toByte() })
        val decoded = FirmwareSignature.decode(signature.encode(), 0)
        assertEquals(signature.algorithm, decoded.algorithm)
        assertTrue(signature.bytes.contentEquals(decoded.bytes))
        assertEquals(signature.encodedSize, signature.encode().size)
    }

    @Test
    fun `Hmac 验签器拒绝非 HMAC 算法`() {
        val verifier = HmacSha256TestVerifier(secret)
        val signature = FirmwareSignature(FirmwareSignatureAlgorithm.ECDSA_P256, ByteArray(8))
        assertFalse(verifier.verify(ByteArray(32), signature))
    }

    @Test
    fun `头部 CRC 计算使用与解析一致的算法`() {
        val header = header(signed = true).encode()
        assertEquals(Crc16.ccittFalse(header, 0, 102), header.readU16At(102))
    }

    private fun ByteArray.readU16At(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}
