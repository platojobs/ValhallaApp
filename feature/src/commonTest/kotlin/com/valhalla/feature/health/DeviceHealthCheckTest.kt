package com.valhalla.feature.health

import com.valhalla.core.command.CommandFailure
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.command.RetryPolicy
import com.valhalla.core.config.ConfigCatalog
import com.valhalla.core.config.ParamCatalog
import com.valhalla.core.profile.OtaHistoryRecord
import com.valhalla.core.profile.OtaOutcome
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.GetDeviceInfoRequest
import com.valhalla.core.protocol.message.GetFirmwareVersionRequest
import com.valhalla.core.protocol.message.GetParamRequest
import com.valhalla.core.protocol.message.HeartbeatRequest
import com.valhalla.core.protocol.message.HeartbeatResponse
import com.valhalla.core.protocol.message.ParamResponse
import com.valhalla.feature.FakeFrameLink
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.i18n.asChinese
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceHealthCheckTest {
    private val firmware = FirmwareVersion(1, 4, 0, 10, "stable")

    private fun healthyResponder(
        battery: Int = 85,
        charging: Boolean = false,
    ): suspend (DeviceRequest) -> DeviceResponse? =
        { request ->
            when (request) {
                is GetDeviceInfoRequest ->
                    DeviceInfoResponse(
                        status = DeviceStatus.OK,
                        vendor = "Valhalla",
                        model = "X1",
                        serialNumber = "SN-001",
                        hardwareVersion = "HW-1",
                        firmwareVersion = firmware,
                    )

                is GetBatteryRequest ->
                    BatteryResponse(
                        status = DeviceStatus.OK,
                        percent = battery,
                        charging = charging,
                        voltageMillivolts = 4_000,
                    )

                is GetFirmwareVersionRequest -> FirmwareVersionResponse(DeviceStatus.OK, firmware)
                is HeartbeatRequest ->
                    HeartbeatResponse(
                        status = DeviceStatus.OK,
                        uptimeSeconds = 3_600,
                        batteryPercent = battery,
                    )

                is GetConfigRequest -> ConfigResponse(DeviceStatus.OK, request.key, ByteArray(1) { 1 })
                is GetParamRequest -> ParamResponse(DeviceStatus.OK, request.paramId, ByteArray(1) { 2 })
                else -> null
            }
        }

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        responder: suspend (DeviceRequest) -> DeviceResponse?,
        dispatcherPolicy: RetryPolicy =
            RetryPolicy(
                maxAttempts = 1,
                timeoutMillis = 20,
                initialRetryDelayMillis = 0,
            ),
        isLinkAlive: () -> Boolean = { true },
        otaHistory: () -> List<OtaHistoryRecord> = {
            listOf(
                OtaHistoryRecord(
                    firmwareVersion = "1.4.0",
                    finishedAtEpochMillis = 1_000,
                    durationMillis = 500,
                    outcome = OtaOutcome.SUCCEEDED,
                ),
            )
        },
    ): Pair<DeviceHealthCheckController, FakeFrameLink> {
        val link = FakeFrameLink(responder)
        val dispatcher =
            ConcurrentCommandDispatcher(
                link = link,
                defaultPolicy = dispatcherPolicy,
                scope = scope,
            )
        val controller =
            DeviceHealthCheckController(
                dispatcher = dispatcher,
                isLinkAlive = isLinkAlive,
                configCatalog = ConfigCatalog,
                paramCatalog = ParamCatalog,
                otaHistoryProvider = otaHistory,
                clockMillis = { 9_000L },
            )
        return controller to link
    }

    @Test
    fun `全部可读时七项都判定正常`() =
        runTest {
            val (controller, _) = controller(backgroundScope, healthyResponder())
            val result = assertNotNull(controller.run())

            assertEquals(HealthCheckItemType.ordered, result.items.map { it.type })
            assertTrue(result.items.all { it.status == HealthCheckStatus.OK })
            assertEquals(HealthCheckStatus.OK, result.overall)
            assertEquals(0, result.abnormalCount)
            assertEquals(0, result.failedCount)
        }

    @Test
    fun `低电量与心跳回读差异判定异常且不阻断后续`() =
        runTest {
            val responder: suspend (DeviceRequest) -> DeviceResponse? = { request ->
                when (request) {
                    is GetBatteryRequest -> BatteryResponse(DeviceStatus.OK, 12, false, 3_600)
                    is HeartbeatRequest -> HeartbeatResponse(DeviceStatus.OK, 120, 90)
                    else -> healthyResponder()(request)
                }
            }
            val (controller, _) = controller(backgroundScope, responder)
            val result = assertNotNull(controller.run())

            val battery = result.items.first { it.type == HealthCheckItemType.BATTERY }
            assertEquals(HealthCheckStatus.ABNORMAL, battery.status)
            assertEquals(StringKey.HEALTH_SUGGEST_CHARGE, battery.suggestion?.key)

            val heartbeat = result.items.first { it.type == HealthCheckItemType.HEARTBEAT }
            assertEquals(HealthCheckStatus.ABNORMAL, heartbeat.status)
            assertEquals(StringKey.HEALTH_DETAIL_HEARTBEAT_MISMATCH, heartbeat.detail?.key)

            assertTrue(result.items.any { it.type == HealthCheckItemType.PARAM && it.status == HealthCheckStatus.OK })
            assertEquals(HealthCheckStatus.ABNORMAL, result.overall)
            assertEquals(2, result.abnormalCount)
        }

    @Test
    fun `设备不支持电量时跳过并继续执行后续项`() =
        runTest {
            val responder: suspend (DeviceRequest) -> DeviceResponse? = { request ->
                if (request is GetBatteryRequest) {
                    ErrorResponse(CommandType.GET_BATTERY, DeviceStatus.UNSUPPORTED, "unsupported")
                } else {
                    healthyResponder()(request)
                }
            }
            val (controller, _) = controller(backgroundScope, responder)
            val result = assertNotNull(controller.run())

            val battery = result.items.first { it.type == HealthCheckItemType.BATTERY }
            assertEquals(HealthCheckStatus.SKIPPED, battery.status)
            assertEquals(StringKey.HEALTH_SKIP_UNSUPPORTED, battery.detail?.key)
            assertEquals(HealthCheckStatus.OK, result.items.first { it.type == HealthCheckItemType.FIRMWARE }.status)
            assertEquals(1, result.skippedCount)
            assertEquals(HealthCheckStatus.OK, result.overall)
        }

    @Test
    fun `单项超时不阻断后续可执行项`() =
        runTest {
            val responder: suspend (DeviceRequest) -> DeviceResponse? = { request ->
                if (request is GetBatteryRequest) null else healthyResponder()(request)
            }
            val (controller, _) = controller(backgroundScope, responder)
            val result = assertNotNull(controller.run())

            val battery = result.items.first { it.type == HealthCheckItemType.BATTERY }
            assertEquals(HealthCheckStatus.FAILED, battery.status)
            assertEquals(StringKey.HEALTH_FAIL_TIMEOUT, battery.detail?.key)
            assertNotNull(battery.suggestion)
            assertEquals(HealthCheckStatus.OK, result.items.first { it.type == HealthCheckItemType.HEARTBEAT }.status)
            assertEquals(1, result.failedCount)
            assertEquals(HealthCheckStatus.FAILED, result.overall)
        }

    @Test
    fun `链路断开时中止并保留已完成项`() =
        runTest {
            var alive = true
            val responder: suspend (DeviceRequest) -> DeviceResponse? = { request ->
                if (request is GetBatteryRequest) alive = false
                healthyResponder()(request)
            }
            val (controller, _) = controller(backgroundScope, responder, isLinkAlive = { alive })
            val result = assertNotNull(controller.run())

            assertTrue(result.abortedByLinkLoss)
            assertEquals(3, result.items.size)
            assertEquals(HealthCheckStatus.FAILED, result.items.last().status)
            assertEquals(
                StringKey.HEALTH_FAIL_LINK,
                result.items
                    .last()
                    .detail
                    ?.key,
            )
        }

    @Test
    fun `设备拒绝读取时记录错误码`() =
        runTest {
            val responder: suspend (DeviceRequest) -> DeviceResponse? = { request ->
                if (request is GetFirmwareVersionRequest) {
                    ErrorResponse(CommandType.GET_FIRMWARE_VERSION, DeviceStatus.BUSY, "busy")
                } else {
                    healthyResponder()(request)
                }
            }
            val (controller, _) = controller(backgroundScope, responder)
            val result = assertNotNull(controller.run())

            val firmwareItem = result.items.first { it.type == HealthCheckItemType.FIRMWARE }
            assertEquals(HealthCheckStatus.ABNORMAL, firmwareItem.status)
            assertTrue(firmwareItem.errorCode!!.startsWith("0x"))
            assertEquals(HealthCheckStatus.OK, result.items.first { it.type == HealthCheckItemType.PARAM }.status)
        }

    @Test
    fun `失败归类覆盖超时与传输失败`() {
        val timeout =
            DeviceHealthCheckController.failureItem(
                HealthCheckItemType.PARAM,
                CommandFailure.Timeout(1),
            )
        assertEquals(HealthCheckStatus.FAILED, timeout.status)
        assertEquals(StringKey.HEALTH_FAIL_TIMEOUT, timeout.detail?.key)

        val transport =
            DeviceHealthCheckController.failureItem(
                HealthCheckItemType.PARAM,
                CommandFailure.TransportFailure(1, IllegalStateException("ble")),
            )
        assertEquals(StringKey.HEALTH_FAIL_TRANSPORT, transport.detail?.key)

        val unsupported =
            DeviceHealthCheckController.failureItem(
                HealthCheckItemType.CONFIG,
                CommandFailure.DeviceRejected(CommandType.GET_CONFIG, DeviceStatus.UNSUPPORTED, "no"),
            )
        assertEquals(HealthCheckStatus.SKIPPED, unsupported.status)
    }

    @Test
    fun `报告包含总体结论并脱敏自由文本`() {
        val result =
            HealthCheckResult(
                items =
                    listOf(
                        HealthCheckItem(
                            type = HealthCheckItemType.DEVICE_INFO,
                            status = HealthCheckStatus.OK,
                            detail =
                                UiMessage(
                                    StringKey.HEALTH_DETAIL_DEVICE,
                                    args =
                                        mapOf(
                                            "vendor" to "token=secret-value",
                                            "model" to "X1",
                                            "hardware" to "HW-1",
                                        ),
                                ),
                        ),
                    ),
                completedAtMillis = 1_000,
            )
        val text =
            HealthCheckReportFormatter.format(
                result = result,
                generatedAtText = "2026-11-05 10:00",
                appVersion = "2.6.0",
                platform = "Android",
                resolve = { it.asChinese() },
            )
        assertTrue(text.contains("总体：全部正常"))
        assertTrue(text.contains("[正常]"))
        assertTrue(!text.contains("secret-value"))
        assertTrue(text.contains("token=***"))
    }
}
