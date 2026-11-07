package com.valhalla.feature.detail

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.profile.DeviceProfileRepository
import com.valhalla.core.profile.InMemoryDeviceProfileStore
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.GetDeviceInfoRequest
import com.valhalla.core.protocol.message.GetFirmwareVersionRequest
import com.valhalla.feature.FakeFrameLink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DeviceProfileIntegrationTest {
    private val firmware = FirmwareVersion(1, 2, 3, 10, "stable")

    private fun responder(): suspend (com.valhalla.core.protocol.message.DeviceRequest) -> DeviceResponse? =
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

                is GetFirmwareVersionRequest -> FirmwareVersionResponse(DeviceStatus.OK, firmware)
                is GetBatteryRequest ->
                    BatteryResponse(DeviceStatus.OK, percent = 85, charging = true, voltageMillivolts = 4_100)

                else -> null
            }
        }

    @Test
    fun `连接建立即建档并在刷新信息后更新快照`() =
        runTest {
            val repository = DeviceProfileRepository(InMemoryDeviceProfileStore(), clockMillis = { 1_700 })
            val link = FakeFrameLink(responder())
            val controller =
                DeviceDetailController(
                    dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    deviceId = "dev-1",
                    profileRepository = repository,
                    deviceDisplayName = "手柄",
                    clockMillis = { 1_700 },
                )

            val created = repository.get("dev-1")
            assertNotNull(created)
            assertEquals("手柄", created.displayName)
            assertEquals(1_700, created.lastConnectedAtEpochMillis)

            controller.refreshInfo()

            val updated = repository.get("dev-1")!!
            assertEquals("Valhalla", updated.vendor)
            assertEquals("X1", updated.model)
            assertEquals(firmware.toString(), updated.lastKnownFirmwareVersion)
            assertEquals(85, updated.lastKnownBatteryPercent)
            assertEquals(true, updated.lastKnownCharging)
        }

    @Test
    fun `未配置档案仓储时连接流程不受影响`() =
        runTest {
            val link = FakeFrameLink(responder())
            val controller =
                DeviceDetailController(
                    dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    deviceId = "dev-1",
                )

            controller.refreshInfo()

            assertEquals(
                "X1",
                controller.state.value.deviceInfo
                    ?.model,
            )
        }
}
