package com.valhalla.feature.firmware

import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareHeader
import com.valhalla.core.firmware.FirmwareSignaturePolicy
import com.valhalla.core.firmware.FirmwareSource
import com.valhalla.core.firmware.FirmwareTestSigner
import com.valhalla.core.firmware.FirmwarePrechecker
import com.valhalla.core.firmware.HmacSha256TestVerifier
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.FirmwareVersion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 预检 × 断点组合矩阵。
 *
 * 维度：固件版本（升级/同版本/回退）、型号（精确/通配/大小写/空白/不匹配）、
 * CRC32（正确/错误）、签名（无/有效/被篡改/无验签器）、签名策略（WARN/REQUIRE），
 * 断点（无/有效/版本失效/长度失效/CRC32 失效/CRC16 失效/偏移为 0/偏移等于总量）。
 */
class FirmwarePrecheckResumeMatrixTest {

    private val currentVersion = FirmwareVersion(1, 9, 0, 0, "release")
    private val verifierSecret = "matrix-test-secret".encodeToByteArray()
    private val payload = ByteArray(300) { (it % 251).toByte() }

    private val upgradeVersion = FirmwareVersion(2, 0, 0, 1, "release")

    private enum class SignatureKind { NONE, VALID, TAMPERED }

    private enum class PointKind {
        NONE,
        VALID,
        STALE_VERSION,
        STALE_LENGTH,
        STALE_CRC32,
        STALE_CRC16,
        OFFSET_ZERO,
        OFFSET_TOTAL,
    }

    private data class Case(
        val name: String,
        val firmwareVersion: FirmwareVersion = FirmwareVersion(2, 0, 0, 1, "release"),
        val targetModels: List<String> = listOf("VH-100"),
        val model: String? = "VH-100",
        val crcDelta: Long = 0,
        val signature: SignatureKind = SignatureKind.NONE,
        val policy: FirmwareSignaturePolicy = FirmwareSignaturePolicy.WARN,
        val verifierAvailable: Boolean = true,
        val point: PointKind = PointKind.NONE,
        val pointChannel: String = "release",
        val expectPrecheckPass: Boolean,
        val expectResume: Boolean,
        val expectReasonContains: String? = null,
    )

    @Test
    fun `有效断点在版本与型号边界下均可续传`() = runTest {
        val cases = listOf(
            Case(
                name = "升级版本 + 精确型号",
                point = PointKind.VALID,
                expectPrecheckPass = true,
                expectResume = true,
            ),
            Case(
                name = "同版本 build 相同、渠道不同",
                point = PointKind.VALID,
                pointChannel = "beta",
                expectPrecheckPass = true,
                expectResume = true,
            ),
            Case(
                name = "通配型号 *",
                targetModels = listOf("*"),
                model = "UNKNOWN-MODEL",
                point = PointKind.VALID,
                expectPrecheckPass = true,
                expectResume = true,
            ),
            Case(
                name = "型号大小写不敏感",
                model = "vh-100",
                point = PointKind.VALID,
                expectPrecheckPass = true,
                expectResume = true,
            ),
            Case(
                name = "型号前后空白被裁剪",
                model = "  VH-100  ",
                point = PointKind.VALID,
                expectPrecheckPass = true,
                expectResume = true,
            ),
        )

        cases.forEach { case -> runCase(case) }
    }

    @Test
    fun `预检失败或断点失效时禁用续传并区分原因`() = runTest {
        val cases = listOf(
            Case(
                name = "版本回退 + 有效断点",
                firmwareVersion = FirmwareVersion(1, 0, 0, 0, "release"),
                point = PointKind.VALID,
                expectPrecheckPass = false,
                expectResume = false,
                expectReasonContains = "预检未通过",
            ),
            Case(
                name = "型号不匹配 + 有效断点",
                model = "VH-200",
                point = PointKind.VALID,
                expectPrecheckPass = false,
                expectResume = false,
                expectReasonContains = "预检未通过",
            ),
            Case(
                name = "CRC32 不一致 + 有效断点",
                crcDelta = 1,
                point = PointKind.VALID,
                expectPrecheckPass = false,
                expectResume = false,
                expectReasonContains = "预检未通过",
            ),
            Case(
                name = "设备型号为空",
                model = null,
                point = PointKind.NONE,
                expectPrecheckPass = false,
                expectResume = false,
                expectReasonContains = null,
            ),
            Case(
                name = "断点版本失效",
                point = PointKind.STALE_VERSION,
                expectPrecheckPass = true,
                expectResume = false,
                expectReasonContains = "版本/总长/CRC32",
            ),
            Case(
                name = "断点长度失效",
                point = PointKind.STALE_LENGTH,
                expectPrecheckPass = true,
                expectResume = false,
                expectReasonContains = "版本/总长/CRC32",
            ),
            Case(
                name = "断点 CRC32 失效",
                point = PointKind.STALE_CRC32,
                expectPrecheckPass = true,
                expectResume = false,
                expectReasonContains = "版本/总长/CRC32",
            ),
            Case(
                name = "断点 CRC16 失效",
                point = PointKind.STALE_CRC16,
                expectPrecheckPass = true,
                expectResume = false,
                expectReasonContains = "CRC16",
            ),
            Case(
                name = "断点偏移为 0",
                point = PointKind.OFFSET_ZERO,
                expectPrecheckPass = true,
                expectResume = false,
                expectReasonContains = "失效",
            ),
            Case(
                name = "断点偏移等于总量",
                point = PointKind.OFFSET_TOTAL,
                expectPrecheckPass = true,
                expectResume = false,
                expectReasonContains = "失效",
            ),
            Case(
                name = "没有断点",
                point = PointKind.NONE,
                expectPrecheckPass = true,
                expectResume = false,
                expectReasonContains = null,
            ),
        )

        cases.forEach { case -> runCase(case) }
    }

    @Test
    fun `签名策略与断点组合`() = runTest {
        val cases = listOf(
            Case(
                name = "无签名 + REQUIRE + 有效断点",
                policy = FirmwareSignaturePolicy.REQUIRE,
                point = PointKind.VALID,
                expectPrecheckPass = false,
                expectResume = false,
                expectReasonContains = "预检未通过",
            ),
            Case(
                name = "无签名 + WARN + 有效断点",
                signature = SignatureKind.NONE,
                policy = FirmwareSignaturePolicy.WARN,
                point = PointKind.VALID,
                expectPrecheckPass = true,
                expectResume = true,
            ),
            Case(
                name = "有效签名 + REQUIRE + 有效断点",
                signature = SignatureKind.VALID,
                policy = FirmwareSignaturePolicy.REQUIRE,
                point = PointKind.VALID,
                expectPrecheckPass = true,
                expectResume = true,
            ),
            Case(
                name = "签名被篡改 + REQUIRE + 有效断点",
                signature = SignatureKind.TAMPERED,
                policy = FirmwareSignaturePolicy.REQUIRE,
                point = PointKind.VALID,
                expectPrecheckPass = false,
                expectResume = false,
                expectReasonContains = "预检未通过",
            ),
            Case(
                name = "有效签名 + 无验签器 + WARN",
                signature = SignatureKind.VALID,
                policy = FirmwareSignaturePolicy.WARN,
                verifierAvailable = false,
                point = PointKind.VALID,
                expectPrecheckPass = true,
                expectResume = true,
            ),
            Case(
                name = "有效签名 + 无验签器 + REQUIRE",
                signature = SignatureKind.VALID,
                policy = FirmwareSignaturePolicy.REQUIRE,
                verifierAvailable = false,
                point = PointKind.VALID,
                expectPrecheckPass = false,
                expectResume = false,
                expectReasonContains = "预检未通过",
            ),
        )

        cases.forEach { case -> runCase(case) }
    }

    private suspend fun runCase(case: Case) {
        val declaredCrc32 = (Crc32.compute(payload) + case.crcDelta) and 0xFFFFFFFFL
        val bytes = packageBytes(case, declaredCrc32)
        val store = RawCheckpointStore(pointFor(case, declaredCrc32))
        val picker = FakePicker(PickedFirmware("matrix.bin", TrackingSource(bytes)))
        val controller = FirmwareSelectionController(
            picker = picker,
            checkpointStore = store,
            prechecker = FirmwarePrechecker(
                signatureVerifier = if (case.verifierAvailable) HmacSha256TestVerifier(verifierSecret) else null,
                signaturePolicy = case.policy,
            ),
        )

        try {
            controller.pickAndPrecheck(currentVersion, case.model)
            val state = controller.state.value
            assertEquals(
                case.expectPrecheckPass,
                state.precheck?.passed == true,
                "case=${case.name} 预检结果不符",
            )
            assertEquals(
                case.expectResume,
                state.resumePoint != null,
                "case=${case.name} 续传可用性不符",
            )
            assertEquals(
                case.expectResume,
                state.canResume,
                "case=${case.name} canResume 不符",
            )
            val reason = state.resumeBlockedReason
            if (case.expectReasonContains != null) {
                assertTrue(
                    reason?.contains(case.expectReasonContains) == true,
                    "case=${case.name} 期望原因包含 ${case.expectReasonContains}，实际 $reason",
                )
            } else {
                assertNull(reason, "case=${case.name} 不应有断点阻塞原因")
            }
            if (!case.expectResume) {
                assertNull(state.resumePoint, "case=${case.name} 不应加载断点")
            }
        } finally {
            controller.close()
        }
    }

    private fun packageBytes(case: Case, declaredCrc32: Long): ByteArray {
        val hasSignature = case.signature != SignatureKind.NONE
        val header = FirmwareHeader(
            firmwareVersion = case.firmwareVersion,
            targetModels = case.targetModels,
            payloadLength = payload.size.toLong(),
            payloadCrc32 = declaredCrc32,
            hasSignature = hasSignature,
        )
        if (!hasSignature) return header.encode() + payload

        val signingSecret = if (case.signature == SignatureKind.TAMPERED) {
            "wrong-secret".encodeToByteArray()
        } else {
            verifierSecret
        }
        val signature = FirmwareTestSigner.signWithHmacKey(header, payload, signingSecret)
        return header.encode() + payload + signature.encode()
    }

    private fun pointFor(case: Case, declaredCrc32: Long): OtaResumePoint? {
        if (case.point == PointKind.NONE) return null
        val total = payload.size
        val crc16 = Crc16.ccittFalse(payload)
        return when (case.point) {
            PointKind.NONE -> null
            PointKind.VALID -> OtaResumePoint(
                sessionId = 42,
                sentBytes = 128,
                totalBytes = total,
                crc16 = crc16,
                firmwareVersion = case.firmwareVersion.copy(channel = case.pointChannel),
                crc32 = declaredCrc32,
            )
            PointKind.STALE_VERSION -> OtaResumePoint(
                sessionId = 42,
                sentBytes = 128,
                totalBytes = total,
                crc16 = crc16,
                firmwareVersion = case.firmwareVersion.copy(patch = case.firmwareVersion.patch + 1),
                crc32 = declaredCrc32,
            )
            PointKind.STALE_LENGTH -> OtaResumePoint(
                sessionId = 42,
                sentBytes = 128,
                totalBytes = total - 1,
                crc16 = crc16,
                firmwareVersion = case.firmwareVersion,
                crc32 = declaredCrc32,
            )
            PointKind.STALE_CRC32 -> OtaResumePoint(
                sessionId = 42,
                sentBytes = 128,
                totalBytes = total,
                crc16 = crc16,
                firmwareVersion = case.firmwareVersion,
                crc32 = declaredCrc32 xor 1L,
            )
            PointKind.STALE_CRC16 -> OtaResumePoint(
                sessionId = 42,
                sentBytes = 128,
                totalBytes = total,
                crc16 = crc16 xor 0xFFFF,
                firmwareVersion = case.firmwareVersion,
                crc32 = declaredCrc32,
            )
            PointKind.OFFSET_ZERO -> OtaResumePoint(
                sessionId = 42,
                sentBytes = 0,
                totalBytes = total,
                crc16 = crc16,
                firmwareVersion = case.firmwareVersion,
                crc32 = declaredCrc32,
            )
            PointKind.OFFSET_TOTAL -> OtaResumePoint(
                sessionId = 42,
                sentBytes = total,
                totalBytes = total,
                crc16 = crc16,
                firmwareVersion = case.firmwareVersion,
                crc32 = declaredCrc32,
            )
        }
    }

    private class RawCheckpointStore(private var point: OtaResumePoint?) : com.valhalla.core.firmware.OtaCheckpointStore {
        override fun load(): OtaResumePoint? = point
        override fun save(point: OtaResumePoint) {
            this.point = point
        }

        override fun clear() {
            point = null
        }
    }

    private class FakePicker(var result: PickedFirmware?) : FirmwarePicker {
        override suspend fun pickFirmware(): PickedFirmware? = result
    }

    private class TrackingSource(private val bytes: ByteArray) : FirmwareSource {
        override val size: Long get() = bytes.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray {
            val start = offset.toInt()
            val end = (start + length).coerceAtMost(bytes.size)
            return bytes.copyOfRange(start, end)
        }

        override fun close() = Unit
    }
}
