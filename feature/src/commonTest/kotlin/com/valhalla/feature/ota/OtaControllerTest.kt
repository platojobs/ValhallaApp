package com.valhalla.feature.ota

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.AbortOtaRequest
import com.valhalla.core.protocol.message.AbortOtaResponse
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FinishOtaResponse
import com.valhalla.core.protocol.message.OtaAbortReason
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.OtaProgressNotification
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.protocol.message.StartOtaResponse
import com.valhalla.feature.FakeFrameLink
import com.valhalla.feature.i18n.asChinese
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            is AbortOtaRequest -> AbortOtaResponse(DeviceStatus.OK)
            else -> null
        }
    }

    private fun firmware(size: Int = 300): ByteArray = ByteArray(size) { it.toByte() }

    @Test
    fun `正常升级路径按协议偏移分片并完成校验`() = runTest {
        val link = FakeFrameLink(otaResponder())
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope), chunkSize = 64)
        val firmware = firmware()

        val result = controller.start(firmware)

        assertTrue(result is OtaState.Succeeded)
        assertEquals(
            OtaState.Succeeded(
                totalBytes = 300,
                crc16 = Crc16.ccittFalse(firmware),
                chunks = 5,
                retransmissions = 0,
                rebootRequired = true,
            ),
            result,
        )

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
        assertNull(controller.lastResumePoint)
    }

    @Test
    fun `设备返回的分片大小覆盖本地默认值`() = runTest {
        val link = FakeFrameLink(otaResponder(deviceChunkSize = 128))
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope), chunkSize = 64)

        val result = controller.start(firmware())

        assertTrue(result is OtaState.Succeeded)
        assertEquals(3, result.chunks)
        val dataRequests = link.requests.filterIsInstance<OtaDataRequest>()
        assertEquals(listOf(0L, 128L, 256L), dataRequests.map { it.offset })
        assertEquals(listOf(128, 128, 44), dataRequests.map { it.data.size })
    }

    @Test
    fun `设备拒绝分片时按同一偏移重传`() = runTest {
        var rejected = false
        val link = FakeFrameLink { request ->
            when (request) {
                is StartOtaRequest -> StartOtaResponse(DeviceStatus.OK, 42, 64)
                is OtaDataRequest -> {
                    if (request.offset == 192L && !rejected) {
                        rejected = true
                        ErrorResponse(CommandType.OTA_DATA, DeviceStatus.BUSY, "设备忙")
                    } else {
                        OtaDataResponse(DeviceStatus.OK, request.offset + request.data.size)
                    }
                }

                is FinishOtaRequest -> FinishOtaResponse(DeviceStatus.OK, rebootRequired = true)
                else -> null
            }
        }
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope), chunkSize = 64)

        val result = controller.start(firmware())

        assertTrue(result is OtaState.Succeeded)
        assertEquals(1, result.retransmissions)
        assertEquals(5, result.chunks)
        assertEquals(
            2,
            link.requests.filterIsInstance<OtaDataRequest>().count { it.offset == 192L },
        )
    }

    @Test
    fun `结束校验 CRC 不匹配时进入失败并携带错误码`() = runTest {
        val link = FakeFrameLink { request ->
            when (request) {
                is StartOtaRequest -> StartOtaResponse(DeviceStatus.OK, 42, 64)
                is OtaDataRequest -> OtaDataResponse(DeviceStatus.OK, request.offset + request.data.size)
                is FinishOtaRequest -> ErrorResponse(CommandType.FINISH_OTA, DeviceStatus.CRC_MISMATCH, "CRC 不一致")
                else -> null
            }
        }
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope), chunkSize = 64)

        val result = controller.start(firmware())

        assertTrue(result is OtaState.Failed)
        val failed = result
        assertEquals(300, failed.sentBytes)
        assertEquals(300, failed.totalBytes)
        assertEquals(DeviceStatus.CRC_MISMATCH, failed.status)
        assertTrue(failed.reason.asChinese().contains("0x0E"))
        assertTrue(failed.reason.asChinese().contains("CRC 不一致"))
        assertNull(failed.resumePoint)
    }

    @Test
    fun `中止升级发送 ABORT_OTA 并保留已发送字节`() = runTest {
        lateinit var controller: OtaController
        val link = FakeFrameLink(otaResponder())
        link.onSend = { index, _ ->
            if (index == 3) controller.abort()
        }
        controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
        )

        val result = controller.start(firmware())

        assertTrue(result is OtaState.Aborted)
        val aborted = result
        assertEquals(OtaAbortReason.USER_CANCELLED.name, aborted.reason)
        assertEquals(128, aborted.sentBytes)
        assertEquals(300, aborted.totalBytes)
        assertTrue(link.requests.any { it is AbortOtaRequest })
        assertEquals(aborted, controller.state.value)
    }

    @Test
    fun `断连后快速失败并可复位到空闲`() = runTest {
        val connectionState = MutableStateFlow(true)
        lateinit var controller: OtaController
        val link = FakeFrameLink(otaResponder())
        link.onSend = { index, _ ->
            if (index == 3) connectionState.value = false
        }
        controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            connectionState = connectionState,
        )

        val result = controller.start(firmware())

        assertTrue(result is OtaState.Failed)
        val failed = result
        assertTrue(failed.reason.asChinese().contains("链路已断开"))
        assertEquals(128, failed.sentBytes)
        val point = failed.resumePoint
        assertNotNull(point)
        assertEquals(128, point.sentBytes)
        assertEquals(42, point.sessionId)

        controller.reset()
        assertEquals(OtaState.Idle, controller.state.value)
        assertNull(controller.lastResumePoint)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `OTA 进度通知驱动阶段与进度`() = runTest {
        lateinit var controller: OtaController
        lateinit var link: FakeFrameLink
        var observed: OtaState? = null
        link = FakeFrameLink { request ->
            when (request) {
                is StartOtaRequest -> StartOtaResponse(DeviceStatus.OK, 42, 64)
                is OtaDataRequest -> {
                    if (request.offset == 128L) {
                        link.emitNotification(
                            OtaProgressNotification(
                                sessionId = 42,
                                receivedBytes = 192,
                                totalBytes = 300,
                                stage = OtaStage.APPLYING,
                            ),
                        )
                        runCurrent()
                        observed = controller.state.value
                    }
                    OtaDataResponse(DeviceStatus.OK, request.offset + request.data.size)
                }

                is FinishOtaRequest -> FinishOtaResponse(DeviceStatus.OK, rebootRequired = false)
                else -> null
            }
        }
        controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            scope = backgroundScope,
        )

        controller.start(firmware())

        val transferring = observed as? OtaState.Transferring
        assertNotNull(transferring)
        assertEquals(OtaStage.APPLYING, transferring.stage)
        assertEquals(192, transferring.sentBytes)
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
        val failed = result
        assertTrue(failed.reason.asChinese().contains("0x0D"))
        assertTrue(failed.reason.asChinese().contains("已有会话"))
        assertEquals(0, failed.sentBytes)
        assertEquals(DeviceStatus.OTA_STATE_INVALID, failed.status)
    }

    @Test
    fun `空固件被拒绝`() = runTest {
        val link = FakeFrameLink(otaResponder())
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope))

        val result = controller.start(ByteArray(0))

        assertTrue(result is OtaState.Failed)
        assertEquals("固件内容为空。请重新选择有效的固件文件。", result.reason.asChinese())
        assertTrue(link.frames.isEmpty())
    }

    @Test
    fun `reset 回到空闲状态`() = runTest {
        val link = FakeFrameLink(otaResponder())
        val controller = OtaController(ConcurrentCommandDispatcher(link, scope = backgroundScope))
        controller.start(ByteArray(32))
        assertTrue(controller.state.value is OtaState.Succeeded)

        controller.reset()
        assertEquals(OtaState.Idle, controller.state.value)
        assertFalse(link.frames.isEmpty())
    }
}
