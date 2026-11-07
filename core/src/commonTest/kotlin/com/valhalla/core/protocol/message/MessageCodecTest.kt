package com.valhalla.core.protocol.message

import com.valhalla.core.logging.LogLevel
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.state.DeviceState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageCodecTest {
    private val firmware = FirmwareVersion(1, 2, 3, 456, "stable")

    private fun assertRequestRoundTrip(request: DeviceRequest) {
        val encoded = request.encode()
        val decoded = MessageCodec.decodeRequest(request.command, encoded)
        assertEquals(request.command, decoded.command)
        assertContentEquals(encoded, decoded.encode(), "请求 ${request.command.name} 往返不一致")
    }

    private fun assertResponseRoundTrip(response: DeviceResponse) {
        val encoded = response.encode()
        val decoded = MessageCodec.decodeResponse(response.command, encoded)
        assertTrue(decoded.isSuccess)
        assertContentEquals(encoded, decoded.encode(), "应答 ${response.command.name} 往返不一致")
    }

    // -----------------------------------------------------------------
    // 请求编解码往返
    // -----------------------------------------------------------------

    @Test
    fun `全部请求命令编解码往返一致`() {
        val requests =
            listOf(
                AuthRequest(AuthMode.TOKEN, "token-abc".encodeToByteArray()),
                AuthRequest(AuthMode.CHALLENGE, ByteArray(16) { it.toByte() }),
                GetDeviceInfoRequest(),
                SetConfigRequest("led.brightness", byteArrayOf(0x10, 0x20)),
                GetConfigRequest("led.brightness"),
                GetBatteryRequest(),
                GetFirmwareVersionRequest(),
                SetTimeRequest(1_700_000_000L, 480),
                GetTimeRequest(),
                SetParamRequest(0x0102, byteArrayOf(0x03, 0x04)),
                GetParamRequest(0x0102),
                FactoryResetRequest("factory-token"),
                StartOtaRequest(2_048L, 0x1234, 64, firmware),
                OtaDataRequest(128L, ByteArray(32) { (it + 1).toByte() }),
                FinishOtaRequest(2_048L, 0x1234),
                AbortOtaRequest(OtaAbortReason.USER_CANCELLED),
                RebootRequest(100, 2),
                LogDumpRequest(9L, 64),
                HeartbeatRequest(123_456L),
            )
        requests.forEach(::assertRequestRoundTrip)
    }

    @Test
    fun `鉴权请求字段可被设备侧解析`() {
        val request = AuthRequest(AuthMode.CHALLENGE, ByteArray(16) { it.toByte() })
        val decoded = MessageCodec.decodeRequest(CommandType.AUTH, request.encode()) as AuthRequest

        assertEquals(AuthMode.CHALLENGE, decoded.mode)
        assertContentEquals(request.credential, decoded.credential)
    }

    @Test
    fun `OTA 数据请求携带偏移与分片`() {
        val chunk = ByteArray(64) { (it * 2).toByte() }
        val decoded =
            MessageCodec.decodeRequest(
                CommandType.OTA_DATA,
                OtaDataRequest(1_024L, chunk).encode(),
            ) as OtaDataRequest

        assertEquals(1_024L, decoded.offset)
        assertContentEquals(chunk, decoded.data)
    }

    // -----------------------------------------------------------------
    // 应答编解码往返
    // -----------------------------------------------------------------

    @Test
    fun `全部应答命令编解码往返一致`() {
        val responses =
            listOf(
                AuthResponse(DeviceStatus.OK, "session-token", 3_600L, ByteArray(8) { it.toByte() }),
                DeviceInfoResponse(DeviceStatus.OK, "Valhalla", "VH-100", "SN20260001", "HW-1.0", firmware),
                SetConfigResponse(DeviceStatus.OK),
                ConfigResponse(DeviceStatus.OK, "led.brightness", byteArrayOf(0x10)),
                BatteryResponse(DeviceStatus.OK, 87, true, 4_120),
                FirmwareVersionResponse(DeviceStatus.OK, firmware),
                SetTimeResponse(DeviceStatus.OK, 1_700_000_000L),
                TimeResponse(DeviceStatus.OK, 1_700_000_000L, -300),
                SetParamResponse(DeviceStatus.OK),
                ParamResponse(DeviceStatus.OK, 0x0102, byteArrayOf(0x0A)),
                FactoryResetResponse(DeviceStatus.OK),
                StartOtaResponse(DeviceStatus.OK, 0x10203040L, 64),
                OtaDataResponse(DeviceStatus.OK, 1_024L),
                FinishOtaResponse(DeviceStatus.OK, true),
                AbortOtaResponse(DeviceStatus.OK),
                RebootResponse(DeviceStatus.OK),
                LogDumpResponse(
                    DeviceStatus.OK,
                    listOf(
                        DeviceLogEntry(LogLevel.INFO, 1_700_000_000L, "系统启动"),
                        DeviceLogEntry(LogLevel.ERROR, 1_700_000_100L, "BLE 断链"),
                    ),
                ),
                HeartbeatResponse(DeviceStatus.OK, 1_000L, 87),
            )
        responses.forEach(::assertResponseRoundTrip)
    }

    @Test
    fun `鉴权成功应答解析令牌与挑战`() {
        val challenge = byteArrayOf(0x55, 0x66, 0x77)
        val response = AuthResponse(DeviceStatus.OK, "token-xyz", 7_200L, challenge)
        val decoded = MessageCodec.decodeResponse(CommandType.AUTH, response.encode()) as AuthResponse

        assertEquals("token-xyz", decoded.token)
        assertEquals(7_200L, decoded.expiresInSeconds)
        assertContentEquals(challenge, decoded.challenge)
        assertTrue(decoded.hasChallenge)
    }

    @Test
    fun `鉴权成功应答无挑战时标记为空`() {
        val decoded =
            MessageCodec.decodeResponse(
                CommandType.AUTH,
                AuthResponse(DeviceStatus.OK, "token", 60L, ByteArray(0)).encode(),
            ) as AuthResponse

        assertFalse(decoded.hasChallenge)
        assertContentEquals(ByteArray(0), decoded.challenge)
    }

    @Test
    fun `鉴权失败应答映射为错误状态`() {
        val decoded =
            MessageCodec.decodeResponse(
                CommandType.AUTH,
                byteArrayOf(DeviceStatus.AUTH_FAILED.code.toByte()),
            )

        assertTrue(decoded is ErrorResponse)
        decoded as ErrorResponse
        assertEquals(CommandType.AUTH, decoded.command)
        assertEquals(DeviceStatus.AUTH_FAILED, decoded.status)
        assertFalse(decoded.isSuccess)
    }

    @Test
    fun `失败应答携带可读详情`() {
        val payload =
            byteArrayOf(DeviceStatus.AUTH_FAILED.code.toByte()) +
                "挑战已过期".encodeToByteArray()
        val decoded = MessageCodec.decodeResponse(CommandType.AUTH, payload) as ErrorResponse

        assertEquals("挑战已过期", decoded.detail)
        assertEquals(DeviceStatus.AUTH_FAILED, decoded.status)
    }

    @Test
    fun `错误码表已知取值与未知取值映射`() {
        assertEquals(DeviceStatus.OK, DeviceStatus.fromCode(0x00))
        assertEquals(DeviceStatus.OTA_VERIFY_FAILED, DeviceStatus.fromCode(0x0C))
        assertEquals(DeviceStatus.UNKNOWN_ERROR, DeviceStatus.fromCode(0x5A))
        assertTrue(DeviceStatus.AUTH_FAILED.describe().contains("0x05"))
        assertTrue(DeviceStatus.AUTH_FAILED.describe().contains("鉴权失败"))
    }

    // -----------------------------------------------------------------
    // 通知解析
    // -----------------------------------------------------------------

    @Test
    fun `三类通知帧编解码往返一致`() {
        val notifications =
            listOf(
                HeartbeatReplyNotification(88, 5_000L),
                DeviceStatusNotification(DeviceState.READY, 80, 0x0000),
                OtaProgressNotification(0x10203040L, 512L, 2_048L, OtaStage.TRANSFERRING),
            )
        notifications.forEach { notification ->
            val frame =
                Frame(
                    type = notification.command,
                    sequence = 7,
                    payload = notification.encode(),
                    isNotification = true,
                )
            val decoded = MessageCodec.decodeNotification(frame)
            assertEquals(notification.command, decoded.command)
            assertContentEquals(notification.encode(), decoded.encode())
        }
    }

    @Test
    fun `OTA 进度通知解析进度字段`() {
        val encoded = OtaProgressNotification(9L, 1_024L, 2_048L, OtaStage.VERIFYING).encode()
        val frame = Frame(CommandType.OTA_PROGRESS_NOTIFY, 3, encoded, isNotification = true)
        val decoded = MessageCodec.decodeNotification(frame) as OtaProgressNotification

        assertEquals(9L, decoded.sessionId)
        assertEquals(1_024L, decoded.receivedBytes)
        assertEquals(2_048L, decoded.totalBytes)
        assertEquals(OtaStage.VERIFYING, decoded.stage)
        assertEquals(0.5f, decoded.progress)
    }

    @Test
    fun `设备状态通知映射到核心状态机`() {
        val encoded = DeviceStatusNotification(DeviceState.AUTHENTICATING, 75, 0x0102).encode()
        val frame = Frame(CommandType.DEVICE_STATUS_NOTIFY, 1, encoded, isNotification = true)
        val decoded = MessageCodec.decodeNotification(frame) as DeviceStatusNotification

        assertEquals(DeviceState.AUTHENTICATING, decoded.deviceState)
        assertEquals(75, decoded.batteryPercent)
        assertEquals(0x0102, decoded.faultCode)
    }

    // -----------------------------------------------------------------
    // 非法 payload 与边界
    // -----------------------------------------------------------------

    @Test
    fun `空应答 payload 抛出解析异常`() {
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeResponse(CommandType.AUTH, ByteArray(0))
        }
    }

    @Test
    fun `应答字段截断抛出解析异常`() {
        // 只有状态码 + 电量百分比，缺少充电状态与电压
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeResponse(CommandType.GET_BATTERY, byteArrayOf(0x00, 0x50))
        }
    }

    @Test
    fun `布尔字段非法取值抛出解析异常`() {
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeResponse(
                CommandType.GET_BATTERY,
                byteArrayOf(0x00, 0x50, 0x02, 0x10, 0x27),
            )
        }
    }

    @Test
    fun `应答尾部多余字节抛出解析异常`() {
        val encoded = ConfigResponse(DeviceStatus.OK, "k", byteArrayOf(0x01)).encode()
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeResponse(CommandType.GET_CONFIG, encoded + byteArrayOf(0x01))
        }
    }

    @Test
    fun `未知鉴权模式与中止原因抛出解析异常`() {
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeRequest(
                CommandType.AUTH,
                byteArrayOf(0x09, 0x02, 0x00, 0x01, 0x02),
            )
        }
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeRequest(CommandType.ABORT_OTA, byteArrayOf(0x09))
        }
    }

    @Test
    fun `未知日志等级抛出解析异常`() {
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeResponse(
                CommandType.LOG_DUMP,
                byteArrayOf(0x00, 0x01, 0x00, 0x09),
            )
        }
    }

    @Test
    fun `通知帧标记缺失时拒绝解析`() {
        val frame =
            Frame(
                type = CommandType.HEARTBEAT_REPLY,
                sequence = 1,
                payload = HeartbeatReplyNotification(50, 10L).encode(),
                isResponse = false,
                isNotification = false,
            )
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeNotification(frame)
        }
    }

    @Test
    fun `OTA 分片不得为空`() {
        assertFailsWith<IllegalArgumentException> {
            OtaDataRequest(0L, ByteArray(0))
        }
    }

    @Test
    fun `过长的字符串在编码阶段被拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            PayloadWriter().string1("x".repeat(300))
        }
    }

    @Test
    fun `请求与应答指令不匹配时拒绝解析`() {
        val request = GetBatteryRequest()
        val frame =
            Frame(
                type = CommandType.GET_DEVICE_INFO,
                sequence = 1,
                payload = BatteryResponse(DeviceStatus.OK, 50, false, 3_700).encode(),
                isResponse = true,
            )
        assertFailsWith<MessageDecodeException> {
            MessageCodec.decodeResponse(request, frame)
        }
    }
}
