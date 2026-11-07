package com.valhalla.feature.param

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ParamCatalog
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.GetParamRequest
import com.valhalla.core.protocol.message.ParamResponse
import com.valhalla.core.protocol.message.SetParamRequest
import com.valhalla.core.protocol.message.SetParamResponse
import com.valhalla.feature.FakeFrameLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ParamEditorControllerTest {
    private val values =
        mapOf(
            0x0001 to byteArrayOf(0, 0, 0x03, 0xE8.toByte()), // 1000 ms
            0x0002 to byteArrayOf(1),
            0x0003 to byteArrayOf(3),
            0x0004 to "VH-UNIT".encodeToByteArray(),
            0x0005 to byteArrayOf(0, 0, 0x04, 0x00),
        )

    private fun responder(failOnWrite: Int? = null): suspend (DeviceRequest) -> DeviceResponse? =
        { request ->
            when (request) {
                is GetParamRequest ->
                    ParamResponse(
                        DeviceStatus.OK,
                        request.paramId,
                        values.getValue(request.paramId),
                    )

                is SetParamRequest ->
                    if (request.paramId == failOnWrite) {
                        ErrorResponse(CommandType.SET_PARAM, DeviceStatus.INVALID_PARAMETER)
                    } else {
                        SetParamResponse(DeviceStatus.OK)
                    }

                else -> null
            }
        }

    private fun field(
        controller: ParamEditorController,
        paramId: Int,
    ): ParamFieldState =
        controller.state.value.fields
            .first { it.spec.id == paramId }

    @Test
    fun `串行读取全部参数并格式化`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ParamEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                )

            assertTrue(controller.load())
            val state = controller.state.value
            assertFalse(state.loading)
            assertEquals(5, state.fields.size)
            assertEquals("1000", field(controller, 0x0001).text)
            assertEquals("1", field(controller, 0x0002).text)
            assertEquals("3", field(controller, 0x0003).text)
            assertEquals("VH-UNIT", field(controller, 0x0004).text)
            assertEquals("1024", field(controller, 0x0005).text)
            assertTrue(state.fields.all { it.loaded })
            assertEquals(5, link.requests.filterIsInstance<GetParamRequest>().size)
        }

    @Test
    fun `本地校验失败不下发并提示范围`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ParamEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                )
            controller.load()

            controller.updateText(0x0001, "99")
            val saved = controller.save(0x0001)

            assertFalse(saved)
            val target = field(controller, 0x0001)
            assertNotNull(target.validationError)
            assertTrue(target.validationError.contains("100..60000"))
            assertTrue(link.requests.none { it is SetParamRequest })
        }

    @Test
    fun `写入成功更新原值与保存时间`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ParamEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    clockMillis = { 1_700_000_000_000 },
                )
            controller.load()

            controller.updateText(0x0003, "4")
            assertTrue(controller.save(0x0003))

            val request = link.requests.filterIsInstance<SetParamRequest>().single()
            assertEquals(0x0003, request.paramId)
            assertContentEquals(ParamCatalog.encodeInt(4), request.value)
            val target = field(controller, 0x0003)
            assertEquals("4", target.originalText)
            assertFalse(target.dirty)
            assertEquals(1_700_000_000_000, target.lastSavedAtEpochMillis)
            assertNotNull(controller.state.value.message)
            assertEquals(null, controller.state.value.errorCode)
        }

    @Test
    fun `只读参数拒绝写入且不发请求`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ParamEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                )
            controller.load()

            controller.updateText(0x0004, "NEW")
            assertFalse(controller.save(0x0004))

            assertTrue(link.requests.none { it is SetParamRequest })
            assertNotNull(controller.state.value.error)
        }

    @Test
    fun `设备拒绝时展示错误码并恢复可重试`() =
        runTest {
            val link = FakeFrameLink(responder(failOnWrite = 0x0001))
            val controller =
                ParamEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                )
            controller.load()

            controller.updateText(0x0001, "2000")
            assertFalse(controller.save(0x0001))

            val state = controller.state.value
            assertEquals(DeviceStatus.INVALID_PARAMETER, state.errorCode)
            assertNotNull(state.error)
            assertFalse(state.saving)
            assertFalse(field(controller, 0x0001).saving)
        }

    @Test
    fun `同一时刻只允许一个写在途`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val link =
                FakeFrameLink { request ->
                    when (request) {
                        is GetParamRequest ->
                            ParamResponse(
                                DeviceStatus.OK,
                                request.paramId,
                                values.getValue(request.paramId),
                            )

                        is SetParamRequest -> {
                            gate.await()
                            SetParamResponse(DeviceStatus.OK)
                        }

                        else -> null
                    }
                }
            val controller =
                ParamEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                )
            controller.load()

            val first = launch { assertTrue(controller.save(0x0003)) }
            runCurrent()
            controller.updateText(0x0001, "2000")
            assertFalse(controller.save(0x0001))
            assertNotNull(controller.state.value.error)
            assertTrue(controller.state.value.saving)

            gate.complete(Unit)
            first.join()
            assertFalse(controller.state.value.saving)
        }

    @Test
    fun `读取在途时拒绝写入`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val link =
                FakeFrameLink { request ->
                    when (request) {
                        is GetParamRequest -> {
                            gate.await()
                            ParamResponse(DeviceStatus.OK, request.paramId, values.getValue(request.paramId))
                        }

                        else -> null
                    }
                }
            val controller =
                ParamEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                )
            val loading = launch { controller.load() }
            runCurrent()
            assertTrue(controller.state.value.loading)

            controller.updateText(0x0001, "2000")
            assertFalse(controller.save(0x0001))
            assertNotNull(controller.state.value.error)

            gate.complete(Unit)
            loading.join()
            assertFalse(controller.state.value.loading)
        }
}
