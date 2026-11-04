package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaResumePointTest {

    private val version = FirmwareVersion(1, 0, 0, 2, "release")

    private fun point(
        sessionId: Long = 42,
        sentBytes: Int = 128,
        totalBytes: Int = 300,
        crc16: Int = 0x1234,
        crc32: Long = 0x89ABCDEF,
    ) = OtaResumePoint(sessionId, sentBytes, totalBytes, crc16, version, crc32)

    @Test
    fun `编码解码往返一致`() {
        val original = point()
        assertEquals(original, OtaResumePointCodec.decode(OtaResumePointCodec.encode(original)))
    }

    @Test
    fun `空渠道名可以往返`() {
        val original = point(crc32 = 1).copy(firmwareVersion = version.copy(channel = ""))
        assertEquals(original, OtaResumePointCodec.decode(OtaResumePointCodec.encode(original)))
    }

    @Test
    fun `损坏或非法字符串解码返回 null`() {
        assertNull(OtaResumePointCodec.decode(null))
        assertNull(OtaResumePointCodec.decode(""))
        assertNull(OtaResumePointCodec.decode("v2|1|1.0.0.0|release|300|128|4660|1"))
        assertNull(OtaResumePointCodec.decode("v1|1|1.0.0|release|300|128|4660|1"))
        assertNull(OtaResumePointCodec.decode("v1|1|1.0.0.0|release|300|128|4660"))
        assertNull(OtaResumePointCodec.decode("v1|abc|1.0.0.0|release|300|128|4660|1"))
    }

    @Test
    fun `偏移越界或 CRC 非法的断点无效`() {
        assertFalse(point(sentBytes = 0).isValid())
        assertFalse(point(sentBytes = 300).isValid())
        assertFalse(point(sentBytes = 301).isValid())
        assertFalse(point(totalBytes = 0, sentBytes = 0).isValid())
        assertFalse(point(crc16 = 0x1_0000).isValid())
        assertFalse(point(crc32 = -1).isValid())
    }

    @Test
    fun `解码时应用有效性校验`() {
        assertNull(OtaResumePointCodec.decode("v1|42|1.0.0.2|release|300|0|4660|2309737967"))
        assertNull(OtaResumePointCodec.decode("v1|42|1.0.0.2|release|300|300|4660|2309737967"))
    }

    @Test
    fun `固件身份匹配规则`() = runTest {
        val payload = ByteArray(300) { it.toByte() }
        val firmware = packageOf(payload)
        val crc16 = com.valhalla.core.protocol.Crc16.ccittFalse(payload)

        val matching = OtaResumePoint(
            sessionId = 42,
            sentBytes = 128,
            totalBytes = 300,
            crc16 = crc16,
            firmwareVersion = firmware.version,
            crc32 = firmware.payloadCrc32,
        )

        assertTrue(matching.matchesFirmware(firmware))
        assertTrue(matching.matchesFirmwarePayload(firmware, crc16))
        assertTrue(matching.matchesPayload(firmware.version, 300, firmware.payloadCrc32, crc16))

        assertFalse(matching.matchesPayload(firmware.version, 300, firmware.payloadCrc32, crc16 + 1))
        assertFalse(matching.matchesPayload(firmware.version, 301, firmware.payloadCrc32, crc16))
        assertFalse(
            matching.matchesPayload(
                firmware.version.copy(patch = firmware.version.patch + 1),
                300,
                firmware.payloadCrc32,
                crc16,
            ),
        )
    }

    @Test
    fun `内存断点存储可保存读取与清除`() {
        val store = InMemoryOtaCheckpointStore()
        assertNull(store.load())

        val saved = point()
        store.save(saved)
        assertEquals(saved, store.load())

        store.clear()
        assertNull(store.load())
    }

    private suspend fun packageOf(payload: ByteArray): FirmwarePackage {
        val header = FirmwareHeader(
            firmwareVersion = version,
            targetModels = listOf("VH-100"),
            payloadLength = payload.size.toLong(),
            payloadCrc32 = Crc32.compute(payload),
        ).encode()
        return FirmwareParser.parse("test.bin", FirmwareSource.memory(header + payload))
    }
}
