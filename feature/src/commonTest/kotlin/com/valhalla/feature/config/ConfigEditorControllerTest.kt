package com.valhalla.feature.config

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ConfigCapabilityStatus
import com.valhalla.core.config.DeviceConfigTarget
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
import com.valhalla.feature.FakeFrameLink
import com.valhalla.feature.i18n.AppStrings
import com.valhalla.feature.i18n.asChinese
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
    private val values =
        mapOf(
            "device_name" to "Valhalla",
            "led_enabled" to "true",
            "sample_interval_ms" to "1500",
            "tx_power_dbm" to "3",
            "serial_number" to "SN-001",
            "hardware_version" to "HW-1",
        )

    /** 全功能设备快照：v1.3.0 + VH-1000，命令表全部键可见。 */
    private val capableTarget =
        DeviceConfigTarget(
            firmwareVersion = FirmwareVersion(1, 3, 0, 0, "stable"),
            model = "VH-1000",
        )

    private fun responder(): suspend (DeviceRequest) -> DeviceResponse? =
        { request ->
            when (request) {
                is GetConfigRequest ->
                    ConfigResponse(
                        DeviceStatus.OK,
                        request.key,
                        values.getValue(request.key).encodeToByteArray(),
                    )

                is SetConfigRequest -> SetConfigResponse(DeviceStatus.OK)
                else -> null
            }
        }

    @Test
    fun `读取命令表全部配置并格式化`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget = capableTarget,
                )

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
    fun `本地校验失败不发请求并提示原因`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget = capableTarget,
                )
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
    fun `保存成功更新原值与状态`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget = capableTarget,
                )
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
            assertTrue(
                controller.state.value.message!!
                    .asChinese()
                    .contains("已保存"),
            )
        }

    @Test
    fun `只读与未知键拒绝保存`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget = capableTarget,
                )

            val serial = controller.save("serial_number")
            assertFalse(serial)
            assertTrue(
                controller.state.value.error!!
                    .asChinese()
                    .contains("只读"),
            )

            val unknown = controller.save("mystery")
            assertFalse(unknown)
            assertTrue(
                controller.state.value.error!!
                    .asChinese()
                    .contains("未知配置项"),
            )
            assertTrue(link.requests.isEmpty())
        }

    @Test
    fun `保存在途时拒绝重复提交`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val link =
                FakeFrameLink { request ->
                    when (request) {
                        is GetConfigRequest ->
                            ConfigResponse(
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
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget = capableTarget,
                )
            controller.load()
            controller.updateText("device_name", "排队中")

            var firstResult: Boolean? = null
            val first =
                launch {
                    firstResult = controller.save("device_name")
                }
            runCurrent()
            assertEquals("device_name", controller.state.value.savingKey)

            val second = controller.save("device_name")
            assertFalse(second)
            assertTrue(
                controller.state.value.error!!
                    .asChinese()
                    .contains("正在保存"),
            )
            assertEquals(1, link.requests.filterIsInstance<SetConfigRequest>().size)

            gate.complete(Unit)
            runCurrent()
            first.join()
            assertTrue(firstResult == true)
            assertEquals(null, controller.state.value.savingKey)
        }

    @Test
    fun `设备拒绝保存时回传错误码且清除保存中状态`() =
        runTest {
            val link =
                FakeFrameLink { request ->
                    when (request) {
                        is GetConfigRequest ->
                            ConfigResponse(
                                DeviceStatus.OK,
                                request.key,
                                values.getValue(request.key).encodeToByteArray(),
                            )

                        is SetConfigRequest ->
                            ErrorResponse(
                                CommandType.SET_CONFIG,
                                DeviceStatus.INVALID_PARAMETER,
                                "配置值非法",
                            )

                        else -> null
                    }
                }
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget = capableTarget,
                )
            controller.load()
            controller.updateText("device_name", "非法名称")

            val saved = controller.save("device_name")

            assertFalse(saved)
            assertEquals(null, controller.state.value.savingKey)
            assertNotNull(controller.state.value.error)
            assertEquals(DeviceStatus.INVALID_PARAMETER, controller.state.value.errorCode)
            assertFalse(field(controller.state.value, "device_name").saving)
            assertEquals("非法名称", field(controller.state.value, "device_name").text, "失败后不得清空用户输入")
        }

    @Test
    fun `单个配置读取失败不影响其它项`() =
        runTest {
            val link =
                FakeFrameLink { request ->
                    if (request is GetConfigRequest && request.key == "tx_power_dbm") {
                        ErrorResponse(CommandType.GET_CONFIG, DeviceStatus.BUSY, "设备忙")
                    } else {
                        responder().invoke(request)
                    }
                }
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget = capableTarget,
                )

            controller.load()

            val failed = field(controller.state.value, "tx_power_dbm")
            assertFalse(failed.loaded)
            assertNotNull(failed.loadError)
            assertTrue(field(controller.state.value, "device_name").loaded)
            assertFalse(controller.state.value.loading)
        }

    @Test
    fun `能力未知时仅读取通用键并提示刷新设备信息`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller = ConfigEditorController(ConcurrentCommandDispatcher(link, scope = backgroundScope))

            assertTrue(controller.load())

            val state = controller.state.value
            assertEquals(6, state.fields.size)
            assertEquals(
                listOf("device_name", "serial_number", "hardware_version"),
                state.visibleFields.map { it.spec.key },
            )
            assertEquals(3, link.requests.filterIsInstance<GetConfigRequest>().size)
            val noticeKey = state.capabilityNoticeKey
            assertNotNull(noticeKey)
            assertTrue(AppStrings.entry(noticeKey)!!.zh.contains("刷新设备信息"))
        }

    @Test
    fun `低版本设备隐藏高版本配置且不发起读取`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget =
                        DeviceConfigTarget(
                            firmwareVersion = FirmwareVersion(1, 0, 0, 0, "stable"),
                            model = "VH-1000",
                        ),
                )

            controller.load()

            val state = controller.state.value
            assertEquals(
                listOf("device_name", "led_enabled", "serial_number", "hardware_version"),
                state.visibleFields.map { it.spec.key },
            )
            assertEquals(listOf("sample_interval_ms", "tx_power_dbm"), state.hiddenFields.map { it.spec.key })
            assertEquals(4, link.requests.filterIsInstance<GetConfigRequest>().size)
            assertTrue(
                state.hiddenFields
                    .first { it.spec.key == "sample_interval_ms" }
                    .capability.reason!!
                    .contains("1.2.0"),
            )
        }

    @Test
    fun `型号不匹配时隐藏发射功率`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget =
                        DeviceConfigTarget(
                            firmwareVersion = FirmwareVersion(1, 3, 0, 0, "stable"),
                            model = "VH-3000",
                        ),
                )

            controller.load()

            val state = controller.state.value
            val txPower = field(state, "tx_power_dbm")
            assertFalse(txPower.visible)
            assertTrue(txPower.capability.reason!!.contains("VH-3000"))
            assertEquals(5, link.requests.filterIsInstance<GetConfigRequest>().size)
        }

    @Test
    fun `下发前二次能力校验阻止已失效的配置`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                ConfigEditorController(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    initialTarget = capableTarget,
                )
            controller.load()
            controller.updateText("tx_power_dbm", "5")

            // 读取版本后设备信息发生变化（这里模拟固件降级），保存前必须重新判定能力。
            controller.updateTarget(
                DeviceConfigTarget(
                    firmwareVersion = FirmwareVersion(1, 2, 0, 0, "stable"),
                    model = "VH-1000",
                ),
            )

            val saved = controller.save("tx_power_dbm")

            assertFalse(saved)
            assertTrue(
                controller.state.value.error!!
                    .asChinese()
                    .contains("不支持"),
            )
            assertTrue(link.requests.none { it is SetConfigRequest }, "能力失效时不得下发 SET_CONFIG")
            val field = field(controller.state.value, "tx_power_dbm")
            assertEquals("5", field.text, "失败时保留用户输入")
            assertEquals(ConfigCapabilityStatus.VERSION_TOO_OLD, field.capability.status)
        }

    @Test
    fun `能力未知时通用键仍可保存`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller = ConfigEditorController(ConcurrentCommandDispatcher(link, scope = backgroundScope))
            controller.load()
            controller.updateText("device_name", "通用键改名")

            assertTrue(controller.save("device_name"))
            assertEquals(1, link.requests.filterIsInstance<SetConfigRequest>().size)
            assertEquals("通用键改名", field(controller.state.value, "device_name").originalText)
        }

    private fun field(
        state: ConfigEditorState,
        key: String,
    ): ConfigFieldState = state.fields.first { it.spec.key == key }
}
