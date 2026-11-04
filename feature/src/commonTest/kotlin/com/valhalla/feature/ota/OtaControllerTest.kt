package com.valhalla.feature.ota

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FinishOtaResponse
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.protocol.message.StartOtaResponse
import com.valhalla.data.ble.BleException
import com.valhalla.feature.FakeFrameLink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtaControllerTest {

    private fun otaResponder(
        deviceChunkSize: Int = 64,
    ): suspend (DeviceRequest) -> DeviceResponse? = { request ->
        when (request) {
            is StartOtaRequest -> StartOtaResponse(
                status = DeviceStatus.OK,
                sessionId = 42,
                chunkSize = deviceChunkSize,
            )

            is OtaDataRequest -> OtaDataResponse(
                status = DeviceStatus.OK,
                receivedBytes = request.offset + request.data.size,
            )

            is FinishOtaRequest -> FinishOtaResponse(DeviceStatus.OK, rebootRequired = true)
            else -> null
        }
    }

    @Test
    fun `正常升级路径按协议偏移分片并完成校验`() = runTest {
        val link = FakeFrameLink(otaResponder())
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope), chunkSize = 64)
        val firmware = ByteArray(300) { it.toByte() }

        val result = controller.start(firmware)

        assertTrue(result is OtaState.Succeeded)
        assertEquals(OtaState.Succeeded(300, Crc16.ccittFalse(firmware), 5), result)

        assertEquals(7, link.frames.size)
        assertEquals(CommandType.START_OTA, link.frames.first().type)
        assertEquals(CommandType.FINISH_OTA, link.frames.last().type)
        assertTrue(link.frames.all { !it.isResponse })

        val start = link.requests.filterIsInstance<StartOtaRequest>().single()
        assertEquals(300L, start.totalBytes)
        assertEquals(Crc16.ccittFalse(firmware), start.crc16)
        assertEquals(64, start.chunkSize)
        assertEquals(firmware.size.toLong(), link.requests.filterIsInstance<FinishOtaRequest>().single().totalBytes)

        val dataRequests = link.requests.filterIsInstance<OtaDataRequest>()
        assertEquals(listOf(0L, 64L, 128L, 192L, 256L), dataRequests.map { it.offset })
        assertEquals(listOf(64, 64, 64, 64, 44), dataRequests.map { it.data.size })
        assertTrue(dataRequests.last().data.contentEquals(firmware.copyOfRange(256, 300)))
    }

    @Test
    fun `设备返回的分片大小覆盖本地默认值`() = runTest {
        val link = FakeFrameLink(otaResponder(deviceChunkSize = 128))
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope), chunkSize = 64)

        val result = controller.start(ByteArray(300) { it.toByte() })

        assertTrue(result is OtaState.Succeeded)
        assertEquals(3, (result as OtaState.Succeeded).chunks)
        val dataRequests = link.requests.filterIsInstance<OtaDataRequest>()
        assertEquals(listOf(0L, 128L, 256L), dataRequests.map { it.offset })
        assertEquals(listOf(128, 128, 44), dataRequests.map { it.data.size })
    }

    @Test
    fun `传输中断返回失败并记录已发送字节`() = runTest {
        val link = FakeFrameLink(otaResponder())
        link.onSend = { index, _ ->
            if (index >= 3) throw BleException("链路断开")
        }
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope), chunkSize = 64)

        val result = controller.start(ByteArray(300) { it.toByte() })

        assertTrue(result is OtaState.Failed)
        result as OtaState.Failed
        assertTrue(result.reason.contains("链路断开"))
        assertEquals(64, result.sentBytes)
        assertEquals(OtaState.Failed(result.reason, 64), controller.state.value)
    }

    @Test
    fun `设备拒绝开始 OTA 时携带错误状态`() = runTest {
        val link = FakeFrameLink { request ->
            if (request is StartOtaRequest) {
                ErrorResponse(CommandType.START_OTA, DeviceStatus.OTA_STATE_INVALID, "已有会话")
            } else {
                null
            }
        }
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope))

        val result = controller.start(ByteArray(64))

        assertTrue(result is OtaState.Failed)
        result as OtaState.Failed
        assertTrue(result.reason.contains("0x0D"))
        assertTrue(result.reason.contains("已有会话"))
        assertEquals(0, result.sentBytes)
    }

    @Test
    fun `空固件被拒绝`() = runTest {
        val link = FakeFrameLink(otaResponder())
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope))

        val result = controller.start(ByteArray(0))

        assertTrue(result is OtaState.Failed)
        assertEquals("固件包为空", (result as OtaState.Failed).reason)
        assertTrue(link.frames.isEmpty())
    }

    @Test
    fun `reset 回到空闲状态`() = runTest {
        val link = FakeFrameLink(otaResponder())
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope))
        controller.start(ByteArray(32))
        assertEquals(OtaState.Succeeded(32, Crc16.ccittFalse(ByteArray(32)), 1), controller.state.value)

        controller.reset()
        assertEquals(OtaState.Idle, controller.state.value)
        assertFalse(link.frames.isEmpty())
    }
}
