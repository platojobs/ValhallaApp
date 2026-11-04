package com.valhalla.feature.ota

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwareHeader
import com.valhalla.core.firmware.FirmwarePackage
import com.valhalla.core.firmware.FirmwareParser
import com.valhalla.core.firmware.FirmwareSource
import com.valhalla.core.firmware.InMemoryOtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FinishOtaResponse
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.protocol.message.StartOtaResponse
import com.valhalla.feature.FakeFrameLink
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.i18n.asChinese
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `设备接受续传偏移时从断点继续`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val crc16 = Crc16.ccittFalse(payload)
        val point = OtaResumePoint(42, 128, 300, crc16, version, Crc32.compute(payload))
        store.save(point)

        val link = FakeFrameLink(responder(sessionId = 42, acceptedOffset = 128))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        val result = controller.startPackage(firmware, resumeFrom = store.load())

        assertTrue(result is OtaState.Succeeded)
        val start = link.requests.filterIsInstance<StartOtaRequest>().single()
        assertTrue(start.hasResumeExtension, "续传请求应携带扩展字段")
        assertEquals(42L, start.resumeSessionId)
        assertEquals(128L, start.resumeOffset)
        assertEquals(Crc32.compute(payload), start.payloadCrc32)

        val dataRequests = link.requests.filterIsInstance<OtaDataRequest>()
        assertEquals(listOf(128L, 192L, 256L), dataRequests.map { it.offset })
        assertEquals(listOf(64, 64, 44), dataRequests.map { it.data.size })
        assertNull(store.load())
    }

    @Test
    fun `设备下调续传偏移时从设备确认值继续`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload),
        )
        store.save(point)

        val link = FakeFrameLink(responder(sessionId = 42, acceptedOffset = 96))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        val result = controller.startPackage(firmware, resumeFrom = point)

        assertTrue(result is OtaState.Succeeded)
        val dataRequests = link.requests.filterIsInstance<OtaDataRequest>()
        assertEquals(listOf(96L, 160L, 224L, 288L), dataRequests.map { it.offset })
        assertEquals(listOf(64, 64, 64, 12), dataRequests.map { it.data.size })
        assertTrue(dataRequests.first().data.contentEquals(payload.copyOfRange(96, 160)))
    }

    @Test
    fun `设备拒绝续传时回退旧格式并从零开始`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload),
        )
        store.save(point)

        var startCount = 0
        val link = FakeFrameLink { request ->
            when (request) {
                is StartOtaRequest -> {
                    startCount++
                    if (request.hasResumeExtension) {
                        ErrorResponse(
                            CommandType.START_OTA,
                            DeviceStatus.OTA_RESUME_CONTEXT_INVALID,
                            "续传上下文已失效",
                        )
                    } else {
                        StartOtaResponse(DeviceStatus.OK, 99, 64)
                    }
                }

                is OtaDataRequest -> OtaDataResponse(DeviceStatus.OK, request.offset + request.data.size)
                is FinishOtaRequest -> FinishOtaResponse(DeviceStatus.OK, rebootRequired = false)
                else -> null
            }
        }
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        val result = controller.startPackage(firmware, resumeFrom = point)

        assertTrue(result is OtaState.Succeeded)
        assertEquals(2, startCount)
        val starts = link.requests.filterIsInstance<StartOtaRequest>()
        assertTrue(starts[0].hasResumeExtension)
        assertEquals(0L, starts[1].resumeOffset, "回退请求必须使用旧格式（无扩展）")
        assertFalse(starts[1].hasResumeExtension)

        val dataRequests = link.requests.filterIsInstance<OtaDataRequest>()
        assertEquals(0L, dataRequests.first().offset)
        assertEquals(listOf(0L, 64L, 128L, 192L, 256L), dataRequests.map { it.offset })
        assertNull(store.load())
    }

    @Test
    fun `设备拒绝续传后回退原因记录在传输状态`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload),
        )
        store.save(point)

        lateinit var controller: OtaController
        var observed: OtaState? = null
        val link = FakeFrameLink { request ->
            when (request) {
                is StartOtaRequest -> if (request.hasResumeExtension) {
                    ErrorResponse(CommandType.START_OTA, DeviceStatus.OTA_RESUME_OFFSET_INVALID, "偏移非法")
                } else {
                    StartOtaResponse(DeviceStatus.OK, 99, 64)
                }

                is OtaDataRequest -> {
                    if (request.offset == 0L) observed = controller.state.value
                    OtaDataResponse(DeviceStatus.OK, request.offset + request.data.size)
                }

                is FinishOtaRequest -> FinishOtaResponse(DeviceStatus.OK, rebootRequired = false)
                else -> null
            }
        }
        controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        controller.startPackage(firmware, resumeFrom = point)

        val transferring = observed as? OtaState.Transferring
        assertNotNull(transferring)
        assertNotNull(transferring.resumeFallbackReason)
        assertTrue(transferring.resumeFallbackReason!!.asChinese().contains("回退"))
        assertEquals(0, transferring.initialOffset)
    }

    @Test
    fun `设备确认偏移大于请求偏移时报错`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload),
        )
        store.save(point)

        val link = FakeFrameLink(responder(sessionId = 42, acceptedOffset = 160))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        val result = controller.startPackage(firmware, resumeFrom = point)

        assertTrue(result is OtaState.Failed)
        val failed = result
        assertTrue(failed.reason.asChinese().contains("大于请求偏移"))
        assertTrue(link.requests.filterIsInstance<OtaDataRequest>().isEmpty())
        // 新会话已建立但未传输：旧断点必须清除。
        assertNull(store.load())
    }

    @Test
    fun `设备确认偏移超出总量时报错`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload),
        )
        store.save(point)

        val link = FakeFrameLink(responder(sessionId = 42, acceptedOffset = 300))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        val result = controller.startPackage(firmware, resumeFrom = point)

        assertTrue(result is OtaState.Failed)
        assertTrue(result.reason.asChinese().contains("超出范围"))
    }

    @Test
    fun `未请求续传但设备返回非零偏移时报错`() = runTest {
        val link = FakeFrameLink(responder(sessionId = 42, acceptedOffset = 64))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
        )

        val result = controller.startPackage(buildPackage(payload))

        assertTrue(result is OtaState.Failed)
        assertTrue(result.reason.asChinese().contains("未请求续传"))
    }

    @Test
    fun `续传后进度以总长为分母计算`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload),
        )
        store.save(point)

        lateinit var controller: OtaController
        var observed: OtaState? = null
        val link = FakeFrameLink { request ->
            when (request) {
                is StartOtaRequest -> StartOtaResponse(DeviceStatus.OK, 42, 64, acceptedOffset = 128)
                is OtaDataRequest -> {
                    if (request.offset == 128L) observed = controller.state.value
                    OtaDataResponse(DeviceStatus.OK, request.offset + request.data.size)
                }

                is FinishOtaRequest -> FinishOtaResponse(DeviceStatus.OK, rebootRequired = false)
                else -> null
            }
        }
        controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        controller.startPackage(firmware, resumeFrom = point)

        val transferring = observed as? OtaState.Transferring
        assertNotNull(transferring)
        assertEquals(128, transferring.sentBytes)
        assertEquals(300, transferring.totalBytes)
        assertEquals(128f / 300f, transferring.progress, 1e-6f)
        assertEquals(128, transferring.initialOffset)
        assertNull(transferring.resumeFallbackReason)
    }

    @Test
    fun `设备未确认续传偏移时从零开始`() = runTest {
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

        val start = link.requests.filterIsInstance<StartOtaRequest>().single()
        assertTrue(start.hasResumeExtension)
        assertEquals(0L, link.requests.filterIsInstance<OtaDataRequest>().first().offset)
    }

    @Test
    fun `固件身份不一致时不携带续传扩展`() = runTest {
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

        val start = link.requests.filterIsInstance<StartOtaRequest>().single()
        assertFalse(start.hasResumeExtension, "固件标识不一致时不得发送续传扩展")
        assertEquals(0L, link.requests.filterIsInstance<OtaDataRequest>().first().offset)
    }

    @Test
    fun `CRC16 不一致时不携带续传扩展`() = runTest {
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(
            sessionId = 42,
            sentBytes = 128,
            totalBytes = 300,
            crc16 = Crc16.ccittFalse(payload) xor 0xFFFF,
            firmwareVersion = version,
            crc32 = Crc32.compute(payload),
        )

        val link = FakeFrameLink(responder(sessionId = 42))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
        )

        controller.startPackage(firmware, resumeFrom = point)

        val start = link.requests.filterIsInstance<StartOtaRequest>().single()
        assertFalse(start.hasResumeExtension)
        assertEquals(0L, link.requests.filterIsInstance<OtaDataRequest>().first().offset)
    }

    @Test
    fun `设备下调偏移后立即落盘调整后的断点`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload),
        )
        store.save(point)

        var checkpointAtFirstChunk: Int? = null
        val link = FakeFrameLink { request ->
            when (request) {
                is StartOtaRequest -> StartOtaResponse(DeviceStatus.OK, 42, 64, acceptedOffset = 96)
                is OtaDataRequest -> {
                    if (checkpointAtFirstChunk == null) {
                        checkpointAtFirstChunk = store.load()?.sentBytes
                    }
                    OtaDataResponse(DeviceStatus.OK, request.offset + request.data.size)
                }

                is FinishOtaRequest -> FinishOtaResponse(DeviceStatus.OK, rebootRequired = false)
                else -> null
            }
        }
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        val result = controller.startPackage(firmware, resumeFrom = point)

        assertTrue(result is OtaState.Succeeded)
        assertEquals(96, checkpointAtFirstChunk, "START 成功后应立即落盘设备确认的偏移")
        assertNull(store.load(), "成功后断点应清除")
    }

    @Test
    fun `START 未建立时保留旧断点`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val point = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload),
        )
        store.save(point)

        // 设备不应答 START_OTA，会话未建立。
        val link = FakeFrameLink { null }
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            checkpointStore = store,
        )

        val result = controller.startPackage(firmware, resumeFrom = point)

        assertTrue(result is OtaState.Failed)
        assertEquals(point, store.load())
    }

    @Test
    fun `本地断点与固件不一致时记录回退原因`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val firmware = buildPackage(payload)
        val stalePoint = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload) xor 0xFFFF, version, Crc32.compute(payload),
        )
        store.save(stalePoint)

        lateinit var controller: OtaController
        var reasonAtFirstChunk: UiMessage? = null
        val link = FakeFrameLink { request ->
            when (request) {
                is StartOtaRequest -> StartOtaResponse(DeviceStatus.OK, 42, 64)
                is OtaDataRequest -> {
                    if (reasonAtFirstChunk == null) {
                        reasonAtFirstChunk =
                            (controller.state.value as? OtaState.Transferring)?.resumeFallbackReason
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
            checkpointStore = store,
        )

        val result = controller.startPackage(firmware, resumeFrom = stalePoint)

        assertTrue(result is OtaState.Succeeded)
        assertEquals(0L, link.requests.filterIsInstance<OtaDataRequest>().first().offset)
        assertTrue(
            reasonAtFirstChunk?.asChinese()?.contains("不匹配") == true,
            "本地门控拒绝续传时应给出可操作原因，实际 $reasonAtFirstChunk",
        )
    }

    @Test
    fun `传入预检 CRC16 时启动前不再扫描固件`() = runTest {
        val header = FirmwareHeader(
            firmwareVersion = version,
            targetModels = listOf("VH-100"),
            payloadLength = payload.size.toLong(),
            payloadCrc32 = Crc32.compute(payload),
        ).encode()
        val source = FailableSource(header + payload)
        val firmware = FirmwareParser.parse("firmware.bin", source)
        val crc16 = firmware.computePayloadChecksums().crc16
        source.failReads = true

        val link = FakeFrameLink(responder(sessionId = 42))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
        )

        val result = controller.startPackage(firmware, knownCrc16 = crc16)

        assertTrue(
            link.requests.any { it is StartOtaRequest },
            "复用预检 CRC16 时不应在 START_OTA 前重扫 payload",
        )
        assertTrue(result is OtaState.Failed)
        assertTrue(
            result.reason.asChinese().contains("读取固件失败"),
            "读取失败应给出可操作提示: ${result.reason.asChinese()}",
        )

        // 反证：不传 CRC16 时会先重扫 payload，因此 START_OTA 根本发不出去。
        val withoutReuse = FakeFrameLink(responder(sessionId = 42))
        OtaController(
            dispatcher = ConcurrentCommandDispatcher(withoutReuse, scope = backgroundScope),
            chunkSize = 64,
        ).startPackage(firmware)
        assertTrue(withoutReuse.requests.none { it is StartOtaRequest })
    }

    @Test
    fun `resume 在内存断点为空时回退持久化断点`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val point = OtaResumePoint(
            42, 128, 300, Crc16.ccittFalse(payload), version, Crc32.compute(payload),
        )
        store.save(point)
        val link = FakeFrameLink(responder(sessionId = 42, acceptedOffset = 128))
        val controller = OtaController(
            dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
            chunkSize = 64,
            firmwareVersion = version,
            checkpointStore = store,
        )

        val result = controller.resume(payload)

        assertTrue(result is OtaState.Succeeded)
        assertEquals(128L, link.requests.filterIsInstance<StartOtaRequest>().single().resumeOffset)
    }

    private class FailableSource(private val bytes: ByteArray) : FirmwareSource {
        var failReads: Boolean = false
        override val size: Long get() = bytes.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray {
            if (failReads) throw FirmwareException.ReadFailure(offset, length)
            val start = offset.toInt()
            val end = (start + length).coerceAtMost(bytes.size)
            return bytes.copyOfRange(start, end)
        }

        override fun close() = Unit
    }

    private fun responder(
        sessionId: Long,
        acceptedOffset: Long = 0,
    ): suspend (DeviceRequest) -> DeviceResponse? = { request ->
        when (request) {
            is StartOtaRequest -> StartOtaResponse(
                status = DeviceStatus.OK,
                sessionId = sessionId,
                chunkSize = 64,
                acceptedOffset = acceptedOffset,
            )

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
