package com.valhalla.feature.ota

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareHeader
import com.valhalla.core.firmware.FirmwarePackage
import com.valhalla.core.firmware.FirmwareParser
import com.valhalla.core.firmware.FirmwareSource
import com.valhalla.core.firmware.InMemoryOtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FinishOtaResponse
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.protocol.message.StartOtaResponse
import com.valhalla.feature.FakeFrameLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaControllerPackageTest {

    private val version = FirmwareVersion(1, 1, 0, 0, "release")
    private val payload = ByteArray(300) { index -> (index % 251).toByte() }

    @Test
    fun `从固件包升级时只传输 payload`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val link = FakeFrameLink(responder(sessionId = 42))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )
        val firmware = buildPackage(payload)

        val result = controller.startPackage(firmware)

        assertTrue(result is OtaState.Succeeded)
        assertEquals(300, result.totalBytes)
        assertEquals(Crc16.ccittFalse(payload), result.crc16)
        assertNull(store.load())

        val start = link.requests.filterIsInstance<StartOtaRequest>().single()
        assertEquals(300L, start.totalBytes)
        assertEquals(version, start.firmwareVersion)
        assertEquals(Crc16.ccittFalse(payload), start.crc16)

        val dataRequests = link.requests.filterIsInstance<OtaDataRequest>()
        assertEquals(listOf(0L, 64L, 128L, 192L, 256L), dataRequests.map { it.offset })
        assertTrue(dataRequests.first().data.contentEquals(payload.copyOfRange(0, 64)))
        assertEquals(300L, link.requests.filterIsInstance<FinishOtaRequest>().single().totalBytes)
    }

    @Test
    fun `传输中失败会持久化精确断点`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val connectionState = MutableStateFlow(true)
        val link = FakeFrameLink(responder(sessionId = 42))
        link.onSend = { index, _ ->
            if (index == 3) connectionState.value = false
        }
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            connectionState = connectionState,
            checkpointStore = store,
        )
        val firmware = buildPackage(payload)

        val result = controller.startPackage(firmware)

        assertTrue(result is OtaState.Failed)
        val point = result.resumePoint
        assertNotNull(point)
        assertEquals(128, point.sentBytes)
        assertEquals(300, point.totalBytes)
        assertEquals(42, point.sessionId)
        assertEquals(Crc16.ccittFalse(payload), point.crc16)
        assertEquals(Crc32.compute(payload), point.crc32)
        assertEquals(version, point.firmwareVersion)
        assertEquals(point, store.load())
    }

    @Test
    fun `同会话且固件一致时从断点继续`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val crc16 = Crc16.ccittFalse(payload)
        val point = OtaResumePoint(42, 128, 300, crc16, version, Crc32.compute(payload))
        store.save(point)

        val link = FakeFrameLink(responder(sessionId = 42))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        val result = controller.startPackage(firmware, resumeFrom = store.load())

        assertTrue(result is OtaState.Succeeded)
        val dataRequests = link.requests.filterIsInstance<OtaDataRequest>()
        assertEquals(listOf(128L, 192L, 256L), dataRequests.map { it.offset })
        assertEquals(listOf(64, 64, 44), dataRequests.map { it.data.size })
        assertNull(store.load())
    }

    @Test
    fun `设备会话变化时不使用断点`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload))
        store.save(point)

        val link = FakeFrameLink(responder(sessionId = 99))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        controller.startPackage(firmware, resumeFrom = point)

        val dataRequests = link.requests.filterIsInstance<OtaDataRequest>()
        assertEquals(0L, dataRequests.first().offset)
    }

    @Test
    fun `固件身份不一致时不使用断点`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val mismatched = OtaResumePoint(
            sessionId = 42,
            sentBytes = 128,
            totalBytes = 300,
            crc16 = Crc16.ccittFalse(payload),
            firmwareVersion = version.copy(patch = 9),
            crc32 = Crc32.compute(payload),
        )
        store.save(mismatched)

        val link = FakeFrameLink(responder(sessionId = 42))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        controller.startPackage(firmware, resumeFrom = mismatched)

        assertEquals(0L, link.requests.filterIsInstance<OtaDataRequest>().first().offset)
    }

    private fun responder(sessionId: Long): suspend (DeviceRequest) -> DeviceResponse? = { request ->
        when (request) {
            is StartOtaRequest -> StartOtaResponse(DeviceStatus.OK, sessionId, 64)
            is OtaDataRequest -> OtaDataResponse(DeviceStatus.OK, request.offset + request.data.size)
            is FinishOtaRequest -> FinishOtaResponse(DeviceStatus.OK, rebootRequired = false)
            else -> null
        }
    }

    private suspend fun buildPackage(body: ByteArray): FirmwarePackage {
        val header = FirmwareHeader(
            firmwareVersion = version,
            targetModels = listOf("VH-100"),
            payloadLength = body.size.toLong(),
            payloadCrc32 = Crc32.compute(body),
        ).encode()
        return FirmwareParser.parse("firmware.bin", FirmwareSource.memory(header + body))
    }
}
