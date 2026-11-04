package com.valhalla.feature.config

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
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
class ConfigEditorControllerTest {

    private val values = mapOf(
        "device_name" to "Valhalla",
        "led_enabled" to "true",
        "sample_interval_ms" to "1500",
        "tx_power_dbm" to "3",
        "serial_number" to "SN-001",
        "hardware_version" to "HW-1",
    )

    private fun responder(): suspend (DeviceRequest) -> DeviceResponse? = { request ->
        when (request) {
            is GetConfigRequest -> ConfigResponse(
                DeviceStatus.OK,
                request.key,
                values.getValue(request.key).encodeToByteArray(),
            )

            is SetConfigRequest -> SetConfigResponse(DeviceStatus.OK)
            else -> null
        }
    }

    @Test
    fun `读取命令表全部配置并格式化`() = runTest {
        val link = FakeFrameLink(responder())
        val controller = ConfigEditorController(ConcurrentCommandDispatcher(link, scope = backgroundScope))

        assertTrue(controller.load())
        val state = controller.state.value
        assertFalse(state.loading)
        assertEquals(6, state.fields.size)
        assertEquals("Valhalla", field(state, "device_name").text)
        assertEquals("true", field(state, "led_enabled").text)
        assertEquals("1500", field(state, "sample_interval_ms").text)
        assertEquals("SN-001", field(state, "serial_number").text)
        assertTrue(state.fields.all { it.loaded })
        assertEquals(6, link.requests.filterIsInstance<GetConfigRequest>().size)
    }

    @Test
    fun `本地校验失败不发请求并提示原因`() = runTest {
        val link = FakeFrameLink(responder())
        val controller = ConfigEditorController(ConcurrentCommandDispatcher(link, scope = backgroundScope))
        controller.load()

        controller.updateText("sample_interval_ms", "20")
        val saved = controller.save("sample_interval_ms")

        assertFalse(saved)
        val field = field(controller.state.value, "sample_interval_ms")
        assertNotNull(field.validationError)
        assertTrue(field.validationError.contains("100..60000"))
        assertTrue(link.requests.none { it is SetConfigRequest })
    }

    @Test
    fun `保存成功更新原值与状态`() = runTest {
        val link = FakeFrameLink(responder())
        val controller = ConfigEditorController(ConcurrentCommandDispatcher(link, scope = backgroundScope))
        controller.load()

        controller.updateText("device_name", "新名字")
        val saved = controller.save("device_name")

        assertTrue(saved)
        val request = link.requests.filterIsInstance<SetConfigRequest>().single()
        assertEquals("device_name", request.key)
        assertContentEquals("新名字".encodeToByteArray(), request.value)
        val field = field(controller.state.value, "device_name")
        assertEquals("新名字", field.originalText)
        assertFalse(field.dirty)
        assertNotNull(field.lastSavedAtEpochMillis)
        assertTrue(controller.state.value.message!!.contains("已保存"))
    }

    @Test
    fun `只读与未知键拒绝保存`() = runTest {
        val link = FakeFrameLink(responder())
        val controller = ConfigEditorController(ConcurrentCommandDispatcher(link, scope = backgroundScope))

        val serial = controller.save("serial_number")
        assertFalse(serial)
        assertTrue(controller.state.value.error!!.contains("只读"))

        val unknown = controller.save("mystery")
        assertFalse(unknown)
        assertTrue(controller.state.value.error!!.contains("未知配置项"))
        assertTrue(link.requests.isEmpty())
    }

    @Test
    fun `保存在途时拒绝重复提交`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val link = FakeFrameLink { request ->
            when (request) {
                is GetConfigRequest -> ConfigResponse(
                    DeviceStatus.OK,
                    request.key,
                    values.getValue(request.key).encodeToByteArray(),
                )

                is SetConfigRequest -> {
                    gate.await()
                    SetConfigResponse(DeviceStatus.OK)
                }

                else -> null
            }
        }
        val controller = ConfigEditorController(ConcurrentCommandDispatcher(link, scope = backgroundScope))
        controller.load()
        controller.updateText("device_name", "排队中")

        var firstResult: Boolean? = null
        val first = launch {
            firstResult = controller.save("device_name")
        }
        runCurrent()
        assertEquals("device_name", controller.state.value.savingKey)

        val second = controller.save("device_name")
        assertFalse(second)
        assertTrue(controller.state.value.error!!.contains("正在保存"))
        assertEquals(1, link.requests.filterIsInstance<SetConfigRequest>().size)

        gate.complete(Unit)
        runCurrent()
        first.join()
        assertTrue(firstResult == true)
        assertEquals(null, controller.state.value.savingKey)
    }

    @Test
    fun `设备拒绝保存时回传错误码且清除保存中状态`() = runTest {
        val link = FakeFrameLink { request ->
            when (request) {
                is GetConfigRequest -> ConfigResponse(
                    DeviceStatus.OK,
                    request.key,
                    values.getValue(request.key).encodeToByteArray(),
                )

                is SetConfigRequest -> ErrorResponse(
                    CommandType.SET_CONFIG,
                    DeviceStatus.INVALID_PARAMETER,
                    "配置值非法",
                )

                else -> null
            }
        }
        val controller = ConfigEditorController(ConcurrentCommandDispatcher(link, scope = backgroundScope))
        controller.load()
        controller.updateText("device_name", "非法名称")

        val saved = controller.save("device_name")

        assertFalse(saved)
        assertEquals(null, controller.state.value.savingKey)
        assertNotNull(controller.state.value.error)
        assertEquals(DeviceStatus.INVALID_PARAMETER, controller.state.value.errorCode)
        assertFalse(field(controller.state.value, "device_name").saving)
    }

    @Test
    fun `单个配置读取失败不影响其它项`() = runTest {
        val link = FakeFrameLink { request ->
            if (request is GetConfigRequest && request.key == "tx_power_dbm") {
                ErrorResponse(CommandType.GET_CONFIG, DeviceStatus.BUSY, "设备忙")
            } else {
                responder().invoke(request)
            }
        }
        val controller = ConfigEditorController(ConcurrentCommandDispatcher(link, scope = backgroundScope))

        controller.load()

        val failed = field(controller.state.value, "tx_power_dbm")
        assertFalse(failed.loaded)
        assertNotNull(failed.loadError)
        assertTrue(field(controller.state.value, "device_name").loaded)
        assertFalse(controller.state.value.loading)
    }

    private fun field(state: ConfigEditorState, key: String): ConfigFieldState =
        state.fields.first { it.spec.key == key }
}
