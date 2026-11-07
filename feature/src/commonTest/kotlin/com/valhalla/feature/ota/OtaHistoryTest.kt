package com.valhalla.feature.ota

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.profile.DeviceProfileRepository
import com.valhalla.core.profile.InMemoryDeviceProfileStore
import com.valhalla.core.profile.OtaOutcome
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FinishOtaResponse
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.protocol.message.StartOtaResponse
import com.valhalla.feature.FakeFrameLink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaHistoryTest {
    private val payload = ByteArray(300) { index -> (index % 251).toByte() }

    private fun responder(): suspend (DeviceRequest) -> DeviceResponse? =
        { request ->
            when (request) {
                is StartOtaRequest -> StartOtaResponse(DeviceStatus.OK, 42, 64, 0)
                is OtaDataRequest -> OtaDataResponse(DeviceStatus.OK, request.offset + request.data.size)
                is FinishOtaRequest -> FinishOtaResponse(DeviceStatus.OK, rebootRequired = false)
                else -> null
            }
        }

    @Test
    fun `成功后档案新增一条成功记录与耗时`() =
        runTest {
            var now = 1_000L
            val repository =
                DeviceProfileRepository(
                    store = InMemoryDeviceProfileStore(),
                    clockMillis = { now },
                )
            val link = FakeFrameLink(responder())
            link.onSend = { index, _ -> if (index == 2) now = 4_000 }
            val controller =
                OtaController(
                    dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    chunkSize = 64,
                    deviceId = "dev-1",
                    profileRepository = repository,
                    clockMillis = { now },
                )

            val result = controller.start(payload)

            assertTrue(result is OtaState.Succeeded)
            val record = repository.get("dev-1")!!.lastOtaRecord
            assertNotNull(record)
            assertEquals(OtaOutcome.SUCCEEDED, record.outcome)
            assertEquals(3_000, record.durationMillis)
            assertEquals(300, record.totalBytes)
            assertEquals(300, record.sentBytes)
            assertNull(record.errorCode)
            assertEquals(4_000, record.finishedAtEpochMillis)
        }

    @Test
    fun `失败后档案记录失败原因与错误码`() =
        runTest {
            val repository =
                DeviceProfileRepository(
                    store = InMemoryDeviceProfileStore(),
                    clockMillis = { 10_000 },
                )
            val link =
                FakeFrameLink { request ->
                    if (request is StartOtaRequest) {
                        StartOtaResponse(DeviceStatus.CRC_MISMATCH, 0, 64, 0)
                    } else {
                        responder().invoke(request)
                    }
                }
            val controller =
                OtaController(
                    dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    chunkSize = 64,
                    deviceId = "dev-1",
                    profileRepository = repository,
                    clockMillis = { 10_000 },
                )

            val result = controller.start(payload)

            assertTrue(result is OtaState.Failed)
            val record = repository.get("dev-1")!!.lastOtaRecord
            assertNotNull(record)
            assertEquals(OtaOutcome.FAILED, record.outcome)
            assertNotNull(record.errorCode)
            assertTrue(record.errorCode!!.contains("CRC"))
            assertNotNull(record.detail)
        }

    @Test
    fun `中止后档案记录中止结果且不覆盖历史`() =
        runTest {
            var now = 20_000L
            val repository =
                DeviceProfileRepository(
                    store = InMemoryDeviceProfileStore(),
                    clockMillis = { now },
                )
            lateinit var controller: OtaController
            val link = FakeFrameLink(responder())
            link.onSend = { index, _ ->
                if (index == 3) {
                    now = 21_000
                    controller.abort()
                }
            }
            controller =
                OtaController(
                    dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    chunkSize = 64,
                    deviceId = "dev-1",
                    profileRepository = repository,
                    clockMillis = { now },
                )

            val result = controller.start(payload)

            assertTrue(result is OtaState.Aborted)
            val profile = repository.get("dev-1")!!
            assertEquals(1, profile.otaHistory.size)
            val record = profile.lastOtaRecord!!
            assertEquals(OtaOutcome.ABORTED, record.outcome)
            assertEquals("USER_CANCELLED", record.detail)
            assertEquals(1_000, record.durationMillis)
        }

    @Test
    fun `未配置仓储或设备 id 时不写历史`() =
        runTest {
            val repository = DeviceProfileRepository(InMemoryDeviceProfileStore())
            val link = FakeFrameLink(responder())
            val controller =
                OtaController(
                    dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    chunkSize = 64,
                    profileRepository = repository,
                )

            val result = controller.start(payload)

            assertTrue(result is OtaState.Succeeded)
            assertEquals(0, repository.count())
        }
}
