package com.valhalla.feature.detail

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.AuthMode
import com.valhalla.core.protocol.message.AuthRequest
import com.valhalla.core.protocol.message.AuthResponse
import com.valhalla.core.protocol.message.SignedAuthCredential
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.GetDeviceInfoRequest
import com.valhalla.core.protocol.message.GetFirmwareVersionRequest
import com.valhalla.core.protocol.message.HeartbeatRequest
import com.valhalla.core.protocol.message.HeartbeatResponse
import com.valhalla.core.protocol.message.RebootRequest
import com.valhalla.core.protocol.message.RebootResponse
import com.valhalla.core.protocol.message.FactoryResetRequest
import com.valhalla.core.protocol.message.FactoryResetResponse
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
import com.valhalla.core.security.AuthSignatureMessage
import com.valhalla.core.security.AuthToken
import com.valhalla.core.security.HmacAuthSigner
import com.valhalla.core.security.HmacSha256
import com.valhalla.core.security.InMemoryTokenStore
import com.valhalla.core.state.DeviceState
import com.valhalla.data.ble.BleException
import com.valhalla.feature.FakeFrameLink
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.asChinese
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDetailControllerTest {

    private val firmware = FirmwareVersion(1, 2, 3, 10, "stable")

    private fun infoResponder(): suspend (com.valhalla.core.protocol.message.DeviceRequest) ->
        com.valhalla.core.protocol.message.DeviceResponse? = { request ->
        when (request) {
            is AuthRequest -> AuthResponse(DeviceStatus.OK, "session-token", 3_600, ByteArray(0))
            is GetDeviceInfoRequest -> DeviceInfoResponse(
                status = DeviceStatus.OK,
                vendor = "Valhalla",
                model = "X1",
                serialNumber = "SN-001",
                hardwareVersion = "HW-1",
                firmwareVersion = firmware,
            )

            is GetFirmwareVersionRequest -> FirmwareVersionResponse(DeviceStatus.OK, firmware)
            is GetBatteryRequest -> BatteryResponse(DeviceStatus.OK, percent = 85, charging = true, voltageMillivolts = 4_100)
            else -> null
        }
    }

    @Test
    fun `鉴权成功进入 READY 并发送强类型 AUTH 请求`() = runTest {
        val link = FakeFrameLink(infoResponder())
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        assertEquals(DeviceState.CONNECTED, controller.state.value.deviceState)

        val success = controller.authenticate("token-123")
        assertTrue(success)
        assertEquals(DeviceState.READY, controller.state.value.deviceState)
        assertEquals("session-token", controller.sessionToken)
        assertEquals(3_600, controller.tokenExpiresInSeconds)
        assertEquals(0, controller.serverChallenge?.size)

        val frame = link.frames.single()
        assertEquals(CommandType.AUTH, frame.type)
        assertFalse(frame.isResponse)
        assertFalse(frame.isNotification)

        val request = link.requests.single()
        assertTrue(request is AuthRequest)
        assertEquals(AuthMode.TOKEN, request.mode)
        assertEquals("token-123", request.credential.decodeToString())
        assertNull(controller.state.value.error)
    }

    @Test
    fun `鉴权被设备拒绝时 FAULT 并传播错误码`() = runTest {
        val link = FakeFrameLink {
            if (it is AuthRequest) {
                ErrorResponse(CommandType.AUTH, DeviceStatus.AUTH_FAILED, "令牌已过期")
            } else {
                null
            }
        }
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        val success = controller.authenticate("bad-token")

        assertFalse(success)
        assertEquals(DeviceState.FAULT, controller.state.value.deviceState)
        val error = controller.state.value.error
        assertNotNull(error)
        assertTrue(error.asChinese().contains("鉴权失败"))
        assertTrue(error.asChinese().contains("0x05"))
        assertTrue(error.asChinese().contains("令牌已过期"))
        assertEquals(DeviceStatus.AUTH_FAILED, controller.state.value.errorCode)
        assertTrue(controller.state.value.logs.any { it.contains("鉴权失败") && it.contains("0x05") })
    }

    @Test
    fun `鉴权写入异常时 FAULT 并保留失败原因`() = runTest {
        val link = FakeFrameLink()
        link.onSend = { _, _ -> throw BleException("写入超时") }
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        val success = controller.authenticate("token")

        assertFalse(success)
        assertEquals(DeviceState.FAULT, controller.state.value.deviceState)
        assertTrue(controller.state.value.error!!.asChinese().contains("写入超时"))
        assertTrue(controller.state.value.logs.any { it.contains("鉴权失败") && it.contains("写入超时") })
        // 调度器按重试策略共尝试 3 次
        assertEquals(3, link.frames.size)
    }

    @Test
    fun `刷新信息并行请求乱序应答仍精确配对`() = runTest {
        val link = FakeFrameLink {
            when (it) {
                is GetDeviceInfoRequest -> {
                    delay(30)
                    DeviceInfoResponse(DeviceStatus.OK, "Valhalla", "X1", "SN-001", "HW-1", firmware)
                }

                is GetFirmwareVersionRequest -> {
                    delay(20)
                    FirmwareVersionResponse(DeviceStatus.OK, firmware)
                }

                is GetBatteryRequest -> {
                    delay(5)
                    BatteryResponse(DeviceStatus.OK, percent = 85, charging = true, voltageMillivolts = 4_100)
                }

                else -> null
            }
        }
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        controller.refreshInfo()

        assertEquals("1.2.3 (build 10, stable)", controller.state.value.firmwareVersion)
        assertEquals(85, controller.state.value.batteryPercent)
        assertTrue(controller.state.value.charging == true)
        assertEquals(4_100, controller.state.value.voltageMillivolts)
        assertEquals("X1", controller.state.value.deviceInfo?.model)
        assertNull(controller.state.value.error)

        assertEquals(3, link.requests.size)
        assertTrue(link.requests.any { it is GetDeviceInfoRequest })
        assertTrue(link.requests.any { it is GetFirmwareVersionRequest })
        assertTrue(link.requests.any { it is GetBatteryRequest })
    }

    @Test
    fun `完整会话：鉴权后读取版本与电量`() = runTest {
        val link = FakeFrameLink(infoResponder())
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        assertTrue(controller.authenticate("token-123"))
        controller.refreshInfo()

        assertEquals(DeviceState.READY, controller.state.value.deviceState)
        assertEquals("1.2.3 (build 10, stable)", controller.state.value.firmwareVersion)
        assertEquals(85, controller.state.value.batteryPercent)
        assertNull(controller.state.value.error)

        assertEquals(
            listOf(CommandType.AUTH, CommandType.GET_DEVICE_INFO, CommandType.GET_FIRMWARE_VERSION, CommandType.GET_BATTERY)
                .sortedBy { it.code },
            link.frames.map { it.type }.sortedBy { it.code },
        )
        assertEquals(link.frames.size, link.frames.map { it.sequence }.distinct().size)
    }

    @Test
    fun `读取异常时保留旧值并给出错误`() = runTest {
        val link = FakeFrameLink { request ->
            ErrorResponse(request.command, DeviceStatus.INTERNAL_ERROR)
        }
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        controller.refreshInfo()

        assertEquals(StringKey.INFO_REFRESH_FAILED, controller.state.value.error?.key)
        assertTrue(controller.state.value.error!!.asChinese().contains("设备信息读取失败"))
        assertNull(controller.state.value.firmwareVersion)
        assertEquals(DeviceStatus.INTERNAL_ERROR, controller.state.value.errorCode)
    }

    @Test
    fun `配置读写使用强类型消息并同步本地缓存`() = runTest {
        val value = byteArrayOf(1, 2, 3)
        val link = FakeFrameLink { request ->
            when (request) {
                is SetConfigRequest -> SetConfigResponse(DeviceStatus.OK)
                is GetConfigRequest -> ConfigResponse(DeviceStatus.OK, request.key, value)
                else -> null
            }
        }
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        assertTrue(controller.writeConfig("mode", value))
        assertNull(controller.state.value.error)

        val read = controller.readConfig("mode")
        assertNotNull(read)
        assertContentEquals(value, read)
        assertContentEquals(value, controller.state.value.configuration["mode"])
    }

    @Test
    fun `主动断开回到未连接`() = runTest {
        var disconnectCalled = false
        val controller = DeviceDetailController(
            dispatcher = ConcurrentCommandDispatcher(FakeFrameLink(), scope = backgroundScope),
            deviceId = "dev-1",
            disconnectLink = { disconnectCalled = true },
        )

        controller.disconnect()

        assertEquals(DeviceState.DISCONNECTED, controller.state.value.deviceState)
        assertTrue(disconnectCalled)
    }

    private val authChallenge = ByteArray(16) { index -> (index + 1).toByte() }
    private val authSecret = "feature-auth-secret".encodeToByteArray()
    private val deviceDigest = "device-digest-001"

    private fun signedAuthResponder(
        challenge: ByteArray = authChallenge,
        secret: ByteArray = authSecret,
        expectedDigest: String = deviceDigest,
        status: DeviceStatus = DeviceStatus.OK,
    ): suspend (com.valhalla.core.protocol.message.DeviceRequest) ->
        com.valhalla.core.protocol.message.DeviceResponse? = { request ->
        when (request) {
            is AuthRequest -> when (request.mode) {
                AuthMode.CHALLENGE -> AuthResponse(status, "challenge-token", 3_600, challenge)
                AuthMode.SIGNED -> {
                    val credential = SignedAuthCredential.decode(request.credential)
                    val message = AuthSignatureMessage.canonical(
                        credential.challenge,
                        credential.clientNonce,
                        credential.deviceDigest,
                    )
                    val valid = status == DeviceStatus.OK &&
                        credential.deviceDigest == expectedDigest &&
                        HmacSha256.verify(secret, message, credential.signature)
                    if (valid) {
                        AuthResponse(DeviceStatus.OK, "session-token", 3_600, ByteArray(0))
                    } else {
                        ErrorResponse(CommandType.AUTH, DeviceStatus.AUTH_FAILED, "签名校验失败")
                    }
                }
                AuthMode.TOKEN -> AuthResponse(DeviceStatus.OK, "session-token", 3_600, ByteArray(0))
            }
            else -> null
        }
    }

    @Test
    fun `签名挑战应答成功进入 READY`() = runTest {
        val link = FakeFrameLink(signedAuthResponder())
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")
        val nonce = ByteArray(16) { 0x40 }

        val success = controller.authenticateSigned(
            signer = HmacAuthSigner(authSecret),
            clientNonce = nonce,
            deviceDigest = deviceDigest,
        )

        assertTrue(success)
        assertEquals(DeviceState.READY, controller.state.value.deviceState)
        assertEquals("session-token", controller.sessionToken)
        assertContentEquals(authChallenge, controller.serverChallenge)
        assertNull(controller.state.value.error)

        assertEquals(2, link.frames.size)
        val modes = link.requests.filterIsInstance<AuthRequest>().map { it.mode }
        assertEquals(listOf(AuthMode.CHALLENGE, AuthMode.SIGNED), modes)
    }

    @Test
    fun `签名密钥不匹配时鉴权失败并进入 FAULT`() = runTest {
        val link = FakeFrameLink(signedAuthResponder())
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        val success = controller.authenticateSigned(
            signer = HmacAuthSigner("wrong-secret".encodeToByteArray()),
            clientNonce = ByteArray(16),
            deviceDigest = deviceDigest,
        )

        assertFalse(success)
        assertEquals(DeviceState.FAULT, controller.state.value.deviceState)
        assertEquals(DeviceStatus.AUTH_FAILED, controller.state.value.errorCode)
        assertNotNull(controller.state.value.error)
        assertNull(controller.sessionToken)
    }

    @Test
    fun `设备摘要不匹配时签名鉴权失败`() = runTest {
        val link = FakeFrameLink(signedAuthResponder())
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        val success = controller.authenticateSigned(
            signer = HmacAuthSigner(authSecret),
            clientNonce = ByteArray(16),
            deviceDigest = "another-device",
        )

        assertFalse(success)
        assertEquals(DeviceState.FAULT, controller.state.value.deviceState)
        assertTrue(controller.state.value.error!!.asChinese().contains("鉴权失败"))
    }

    @Test
    fun `签名鉴权成功后令牌写入安全存储并可按过期时间读取`() = runTest {
        val store = InMemoryTokenStore()
        val link = FakeFrameLink(signedAuthResponder())
        val controller = DeviceDetailController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            deviceId = "dev-1",
            tokenStore = store,
            clockMillis = { 1_000L },
        )

        assertTrue(
            controller.authenticateSigned(
                signer = HmacAuthSigner(authSecret),
                clientNonce = ByteArray(16),
                deviceDigest = deviceDigest,
            ),
        )

        assertEquals("session-token", store.load()?.value)
        assertEquals(1_000L + 3_600_000L, store.load()?.expiresAtEpochMillis)
        assertEquals("session-token", controller.loadPersistedToken()?.value)

        controller.clearPersistedToken()
        assertNull(store.load())
        assertNull(controller.loadPersistedToken())
    }

    @Test
    fun `过期令牌读取时返回空并清除存储`() = runTest {
        val store = InMemoryTokenStore()
        store.save(AuthToken("expired-token", expiresAtEpochMillis = 5_000))
        val link = FakeFrameLink()
        val controller = DeviceDetailController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            deviceId = "dev-1",
            tokenStore = store,
            clockMillis = { 6_000L },
        )

        assertNull(controller.loadPersistedToken())
        assertNull(store.load())
    }

    @Test
    fun `重启成功进入 Succeeded 并保留延迟参数`() = runTest {
        val link = FakeFrameLink { request ->
            if (request is RebootRequest) RebootResponse(DeviceStatus.OK) else null
        }
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        assertTrue(controller.reboot(1_500))
        val state = controller.state.value.quickAction
        assertTrue(state is QuickActionState.Succeeded)
        assertEquals(QuickActionType.REBOOT, state.type)
        assertEquals(1_500, state.delayMillis)
        assertTrue(link.requests.any { it is RebootRequest && it.delayMillis == 1_500 })
    }

    @Test
    fun `心跳成功回读 uptime 与电量`() = runTest {
        val link = FakeFrameLink { request ->
            if (request is HeartbeatRequest) {
                HeartbeatResponse(DeviceStatus.OK, uptimeSeconds = 3_600, batteryPercent = 42)
            } else {
                null
            }
        }
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        assertTrue(controller.heartbeat())
        assertEquals(3_600L, controller.state.value.uptimeSeconds)
        assertEquals(42, controller.state.value.batteryPercent)
        val state = controller.state.value.quickAction
        assertTrue(state is QuickActionState.Succeeded)
        assertEquals(QuickActionType.HEARTBEAT, state.type)
        assertEquals(3_600L, state.uptimeSeconds)
        assertEquals(42, state.batteryPercent)
    }

    @Test
    fun `恢复出厂失败进入 Failed 并携带设备错误码`() = runTest {
        val link = FakeFrameLink { request ->
            if (request is FactoryResetRequest) {
                ErrorResponse(CommandType.FACTORY_RESET, DeviceStatus.AUTH_FAILED, "需要管理员权限")
            } else {
                null
            }
        }
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        assertFalse(controller.factoryReset("token"))
        val state = controller.state.value.quickAction
        assertTrue(state is QuickActionState.Failed)
        assertEquals(QuickActionType.FACTORY_RESET, state.type)
        assertEquals(DeviceStatus.AUTH_FAILED, state.status)
        assertTrue(state.error.asChinese().contains("操作失败"))
    }

    @Test
    fun `同一时刻只允许一个快捷操作在途`() = runTest {
        val link = FakeFrameLink { request ->
            if (request is RebootRequest) {
                delay(50)
                RebootResponse(DeviceStatus.OK)
            } else {
                null
            }
        }
        val controller = DeviceDetailController(ConcurrentCommandDispatcher(link, scope = backgroundScope), "dev-1")

        val first = launch { controller.reboot(0) }
        testScheduler.runCurrent()
        assertTrue(controller.state.value.quickAction is QuickActionState.Running)
        assertFalse(controller.reboot(0))
        first.join()
        assertTrue(controller.state.value.quickAction is QuickActionState.Succeeded)
    }
}
