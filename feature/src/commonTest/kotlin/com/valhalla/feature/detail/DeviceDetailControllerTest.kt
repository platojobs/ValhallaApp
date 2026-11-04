package com.valhalla.feature.detail

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.AuthMode
import com.valhalla.core.protocol.message.AuthRequest
import com.valhalla.core.protocol.message.AuthResponse
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
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
import com.valhalla.core.state.DeviceState
import com.valhalla.data.ble.BleException
import com.valhalla.feature.FakeFrameLink
import kotlinx.coroutines.delay
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
        assertTrue(error.contains("鉴权失败"))
        assertTrue(error.contains("0x05"))
        assertTrue(error.contains("令牌已过期"))
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
        assertTrue(controller.state.value.error!!.contains("写入超时"))
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

        assertEquals("设备信息读取失败", controller.state.value.error)
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
}
