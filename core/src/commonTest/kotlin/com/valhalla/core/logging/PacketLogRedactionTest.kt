package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.FrameCodec
import com.valhalla.core.protocol.Hex
import com.valhalla.core.protocol.message.AuthMode
import com.valhalla.core.protocol.message.AuthRequest
import com.valhalla.core.protocol.message.AuthResponse
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.MessageCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PacketLogRedactionTest {
    private val secretToken = "tok_live_9f8e7d6c5b4a"
    private val secretChallenge = "challenge-0123456789abcdef".encodeToByteArray()
    private val secretCredential = "super-secret-credential".encodeToByteArray()
    private val secretSerial = "SN-ABC123456789"

    private fun authRequestPayload(): ByteArray = MessageCodec.encodeRequest(AuthRequest(AuthMode.TOKEN, secretCredential))

    private fun authResponsePayload(): ByteArray =
        MessageCodec.encodeResponse(
            AuthResponse(
                status = DeviceStatus.OK,
                token = secretToken,
                expiresInSeconds = 3600,
                challenge = secretChallenge,
            ),
        )

    private fun deviceInfoPayload(): ByteArray =
        MessageCodec.encodeResponse(
            DeviceInfoResponse(
                status = DeviceStatus.OK,
                vendor = "Valhalla",
                model = "VH-100",
                serialNumber = secretSerial,
                hardwareVersion = "HW-1",
                firmwareVersion = FirmwareVersion(1, 2, 3, 4, "release"),
            ),
        )

    @Test
    fun authRequestCredentialIsMaskedWithSameLength() {
        val original = authRequestPayload()
        val redacted = PacketLogRedaction.redactPayload(CommandType.AUTH, isResponse = false, payload = original)

        assertEquals(original.size, redacted.size, "脱敏不能改变载荷长度")
        assertEquals(original[0], redacted[0], "mode 字节保留")
        assertEquals(original[1], redacted[1], "credentialLen 高字节保留")
        assertEquals(original[2], redacted[2], "credentialLen 低字节保留")
        for (index in 3 until redacted.size) {
            assertEquals(PacketLogRedaction.MASK_BYTE, redacted[index], "credential 字节必须全部掩码")
        }

        val decoded = MessageCodec.decodeRequest(CommandType.AUTH, redacted) as AuthRequest
        assertEquals(AuthMode.TOKEN, decoded.mode)
        assertEquals(secretCredential.size, decoded.credential.size)
        assertFalse(original.contentEquals(redacted), "脱敏结果必须与原文不同")
    }

    @Test
    fun authResponseTokenAndChallengeAreMaskedAndExpiryPreserved() {
        val original = authResponsePayload()
        val redacted = PacketLogRedaction.redactPayload(CommandType.AUTH, isResponse = true, payload = original)

        assertEquals(original.size, redacted.size)
        val decoded = MessageCodec.decodeResponse(CommandType.AUTH, redacted) as AuthResponse
        assertEquals(DeviceStatus.OK, decoded.status)
        assertEquals(secretToken.length, decoded.token.length)
        assertTrue(decoded.token.all { it == '*' }, "token 应全部为掩码")
        assertTrue(
            decoded.challenge.contentEquals(ByteArray(secretChallenge.size) { PacketLogRedaction.MASK_BYTE }),
            "challenge 应全部为掩码且长度不变",
        )
        assertEquals(3600L, decoded.expiresInSeconds, "expiresIn 非敏感，保留")
        assertFalse(original.contentEquals(redacted), "token/challenge 字节必须被改写")
    }

    @Test
    fun authFailureResponseIsKeptForDiagnostics() {
        val payload =
            MessageCodec.encodeResponse(
                AuthResponse(
                    status = DeviceStatus.AUTH_FAILED,
                    token = "",
                    expiresInSeconds = 0,
                    challenge = ByteArray(0),
                ),
            )
        val redacted = PacketLogRedaction.redactPayload(CommandType.AUTH, isResponse = true, payload = payload)
        assertSame(payload, redacted, "错误应答无敏感字段，应原样保留")
    }

    @Test
    fun deviceSerialKeepsOnlyPrefix() {
        val original = deviceInfoPayload()
        val redacted =
            PacketLogRedaction.redactPayload(
                CommandType.GET_DEVICE_INFO,
                isResponse = true,
                payload = original,
            )
        assertEquals(original.size, redacted.size)
        val decoded = MessageCodec.decodeResponse(CommandType.GET_DEVICE_INFO, redacted) as DeviceInfoResponse
        assertEquals("Valhalla", decoded.vendor, "非敏感字段不受影响")
        assertEquals("VH-100", decoded.model)
        assertEquals(
            secretSerial.take(PacketLogRedaction.DEVICE_ID_PREFIX_BYTES) +
                "*".repeat(secretSerial.length - PacketLogRedaction.DEVICE_ID_PREFIX_BYTES),
            decoded.serialNumber,
        )
        assertEquals(secretSerial.length, decoded.serialNumber.length)
    }

    @Test
    fun nonSensitiveCommandIsUntouched() {
        val payload = MessageCodec.encodeResponse(BatteryResponse(DeviceStatus.OK, 80, true, 4_000))
        assertSame(
            payload,
            PacketLogRedaction.redactPayload(CommandType.GET_BATTERY, isResponse = true, payload),
            "非敏感命令不应产生新数组",
        )
    }

    @Test
    fun redactedFrameKeepsHeaderAndProducesValidCrc() {
        val frame =
            Frame(
                type = CommandType.AUTH,
                sequence = 7,
                payload = authResponsePayload(),
                isResponse = true,
            )
        val redactedFrame = PacketLogRedaction.redactFrame(frame)
        assertEquals(frame.type, redactedFrame.type)
        assertEquals(frame.sequence, redactedFrame.sequence)
        assertEquals(frame.payload.size, redactedFrame.payload.size)

        // 重新编码不报错，说明长度与 CRC 均合法。
        val encoded = FrameCodec.encode(redactedFrame)
        assertEquals(FrameCodec.HEADER_SIZE + redactedFrame.payload.size + FrameCodec.CRC_SIZE, encoded.size)
    }

    @Test
    fun packetLogExportsAreRedactedInAllFormats() =
        runTest {
            val log = InMemoryPacketLog(capacity = 16, clockMillis = { 1_700_000_000_000 })
            log.record(
                FrameDirection.TX,
                Frame(type = CommandType.AUTH, sequence = 1, payload = authRequestPayload()),
            )
            log.record(
                FrameDirection.RX,
                Frame(
                    type = CommandType.AUTH,
                    sequence = 1,
                    payload = authResponsePayload(),
                    isResponse = true,
                ),
            )
            log.record(
                FrameDirection.RX,
                Frame(
                    type = CommandType.GET_DEVICE_INFO,
                    sequence = 2,
                    payload = deviceInfoPayload(),
                    isResponse = true,
                ),
            )

            val originalHex = Hex.encode(authRequestPayload())
            val entries = log.entries.value
            assertEquals(3, entries.size)
            entries.forEach { entry ->
                assertFalse(entry.hex.contains(originalHex), "脱敏后的 HEX 不能包含原始 credential")
                assertFalse(entry.summary.contains(secretToken), "摘要不能泄露 token")
                assertFalse(entry.summary.contains(secretSerial), "摘要不能泄露完整序列号")
            }

            val outputs =
                PacketLogExportFormat.entries.associateWith { format ->
                    PacketLogExporter
                        .build(
                            entries = entries,
                            nowMillis = 1_700_000_000_000,
                            zoneOffsetMinutes = 480,
                            format = format,
                        ).text
                }
            outputs.forEach { (format, text) ->
                assertFalse(text.contains(secretToken), "$format 导出不能包含 token")
                assertFalse(text.contains(Hex.encode(secretCredential)), "$format 导出不能包含 credential HEX")
                assertFalse(text.contains(secretSerial), "$format 导出不能包含完整序列号")
                assertTrue(text.contains("AUTH"), "$format 导出应保留命令名以便排障")
            }
            assertEquals(outputs.keys, setOf(PacketLogExportFormat.TEXT, PacketLogExportFormat.JSON, PacketLogExportFormat.CSV))
        }

    @Test
    fun deviceIdMaskingAndHashingAreStableAndIrreversible() {
        val mac = "AA:BB:CC:DD:EE:FF"
        val masked = PacketLogRedaction.maskDeviceId(mac)
        assertEquals(mac.length, masked.length)
        assertEquals("AA:B", masked.take(4))
        assertTrue(masked.drop(4).all { it == '*' })
        assertFalse(masked.contains("CC:DD"))

        val hash = PacketLogRedaction.hashDeviceId(mac)
        assertEquals(hash, PacketLogRedaction.hashDeviceId(mac), "哈希必须稳定")
        assertFalse(hash.contains("AA"), "哈希不能包含原文片段")
        assertTrue(hash.startsWith("dev#"))
        assertTrue(hash.removePrefix("dev#").all { it in "0123456789abcdef" })
        assertFalse(
            PacketLogRedaction.hashDeviceId("AA:BB:CC:DD:EE:01") == hash,
            "不同设备哈希必须不同",
        )
    }
}
