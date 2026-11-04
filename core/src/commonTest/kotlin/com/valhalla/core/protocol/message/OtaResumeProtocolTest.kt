package com.valhalla.core.protocol.message

import com.valhalla.core.protocol.CommandType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v0.9.0 `START_OTA` 续传扩展的编解码与向后兼容测试。
 *
 * 兼容基线：不续传时请求 payload 与 v0.7.0 完全一致；旧设备只需解析基础字段。
 */
class OtaResumeProtocolTest {

    private val version = FirmwareVersion(1, 2, 3, 456, "release")

    private fun legacyRequestPayload(
        totalBytes: Long = 2_048L,
        crc16: Int = 0x1234,
        chunkSize: Int = 64,
    ): ByteArray {
        val writer = PayloadWriter()
        writer.u32(totalBytes)
        writer.u16(crc16)
        writer.u16(chunkSize)
        version.encodeTo(writer)
        return writer.toByteArray()
    }

    @Test
    fun `不续传时请求字节与旧协议完全一致`() {
        val request = StartOtaRequest(
            totalBytes = 2_048L,
            crc16 = 0x1234,
            chunkSize = 64,
            firmwareVersion = version,
        )

        assertFalse(request.hasResumeExtension)
        assertContentEquals(legacyRequestPayload(), request.encode())
    }

    @Test
    fun `旧格式请求解码后续传字段为零`() {
        val decoded = MessageCodec.decodeRequest(
            CommandType.START_OTA,
            legacyRequestPayload(),
        ) as StartOtaRequest

        assertEquals(2_048L, decoded.totalBytes)
        assertEquals(0x1234, decoded.crc16)
        assertEquals(64, decoded.chunkSize)
        assertEquals(version, decoded.firmwareVersion)
        assertEquals(0L, decoded.resumeOffset)
        assertEquals(0L, decoded.resumeSessionId)
        assertFalse(decoded.hasResumeExtension)
    }

    @Test
    fun `携带领续传扩展的请求往返一致`() {
        val request = StartOtaRequest(
            totalBytes = 2_048L,
            crc16 = 0x1234,
            chunkSize = 64,
            firmwareVersion = version,
            resumeSessionId = 0x10203040L,
            resumeOffset = 1_024L,
            payloadCrc32 = 0xDEADBEEFL,
        )

        val encoded = request.encode()
        assertEquals(legacyRequestPayload().size + StartOtaRequest.EXTENSION_SIZE, encoded.size)

        val decoded = MessageCodec.decodeRequest(CommandType.START_OTA, encoded) as StartOtaRequest
        assertTrue(decoded.hasResumeExtension)
        assertEquals(0x10203040L, decoded.resumeSessionId)
        assertEquals(1_024L, decoded.resumeOffset)
        assertEquals(0xDEADBEEFL, decoded.payloadCrc32)
        assertContentEquals(encoded, decoded.encode())
    }

    @Test
    fun `扩展长度非法时拒绝解析`() {
        val payload = legacyRequestPayload() + ByteArray(10)
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeRequest(CommandType.START_OTA, payload)
        }
    }

    @Test
    fun `未知扩展版本时拒绝解析`() {
        val extension = byteArrayOf(2, StartOtaRequest.FLAG_RESUME.toByte()) + ByteArray(12)
        val payload = legacyRequestPayload() + extension
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeRequest(CommandType.START_OTA, payload)
        }
    }

    @Test
    fun `扩展缺少续传标志时拒绝解析`() {
        val extension = byteArrayOf(StartOtaRequest.EXTENSION_VERSION.toByte(), 0) + ByteArray(12)
        val payload = legacyRequestPayload() + extension
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeRequest(CommandType.START_OTA, payload)
        }
    }

    @Test
    fun `扩展包含未知标志位时拒绝解析`() {
        val flags = (StartOtaRequest.FLAG_RESUME or 0x80).toByte()
        val extension = byteArrayOf(StartOtaRequest.EXTENSION_VERSION.toByte(), flags) + ByteArray(12)
        val payload = legacyRequestPayload() + extension
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeRequest(CommandType.START_OTA, payload)
        }
    }

    @Test
    fun `续传偏移不得大于等于总长`() {
        assertFailsWith<IllegalArgumentException> {
            StartOtaRequest(
                totalBytes = 1_024L,
                crc16 = 0x1234,
                chunkSize = 64,
                firmwareVersion = version,
                resumeOffset = 1_024L,
            )
        }
    }

    @Test
    fun `旧格式应答解码为非零偏移为零`() {
        val legacy = StartOtaResponse(DeviceStatus.OK, sessionId = 0x10203040L, chunkSize = 64)
        val encoded = legacy.encode()

        val decoded = MessageCodec.decodeResponse(CommandType.START_OTA, encoded) as StartOtaResponse
        assertEquals(0L, decoded.acceptedOffset)
        assertContentEquals(encoded, decoded.encode())
    }

    @Test
    fun `设备确认偏移随应答往返一致`() {
        val response = StartOtaResponse(
            status = DeviceStatus.OK,
            sessionId = 0x10203040L,
            chunkSize = 64,
            acceptedOffset = 1_024L,
        )
        val encoded = response.encode()

        val decoded = MessageCodec.decodeResponse(CommandType.START_OTA, encoded) as StartOtaResponse
        assertEquals(1_024L, decoded.acceptedOffset)
        assertContentEquals(encoded, decoded.encode())
    }

    @Test
    fun `应答尾部长度非法时拒绝解析`() {
        val legacy = StartOtaResponse(DeviceStatus.OK, 1L, 64).encode()
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeResponse(CommandType.START_OTA, legacy + byteArrayOf(0x01))
        }
    }

    @Test
    fun `新增续传错误码可映射`() {
        assertEquals(DeviceStatus.OTA_RESUME_OFFSET_INVALID, DeviceStatus.fromCode(0x11))
        assertEquals(DeviceStatus.OTA_RESUME_CONTEXT_INVALID, DeviceStatus.fromCode(0x12))
        assertEquals(DeviceStatus.OTA_FIRMWARE_MISMATCH, DeviceStatus.fromCode(0x13))
    }
}
