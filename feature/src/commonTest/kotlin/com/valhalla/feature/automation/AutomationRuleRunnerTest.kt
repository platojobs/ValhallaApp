package com.valhalla.feature.automation

import com.valhalla.core.automation.AutomationAction
import com.valhalla.core.automation.AutomationRuleRepository
import com.valhalla.core.automation.AutomationTrigger
import com.valhalla.core.automation.InMemoryAutomationRuleStore
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ConfigPresetRepository
import com.valhalla.core.config.InMemoryConfigPresetStore
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventTimeline
import com.valhalla.core.event.InMemoryDeviceEventStore
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.feature.FakeFrameLink
import com.valhalla.feature.config.ConfigPresetApplier
import com.valhalla.feature.detail.DeviceDetailController
import com.valhalla.feature.health.DeviceHealthCheckController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationRuleRunnerTest {

    private val deviceId = "dev-1"

    private fun responder(batteryStatus: DeviceStatus = DeviceStatus.OK):
        suspend (DeviceRequest) -> DeviceResponse? = { request ->
        when (request) {
            is com.valhalla.core.protocol.message.GetBatteryRequest -> BatteryResponse(
                batteryStatus,
                percent = 80,
                charging = false,
                voltageMillivolts = 3900,
            )

            is com.valhalla.core.protocol.message.GetFirmwareVersionRequest ->
                FirmwareVersionResponse(DeviceStatus.OK, FirmwareVersion(1, 3, 0, 0, "stable"))

            else -> null
        }
    }

    private class Fixture(
        val link: FakeFrameLink,
        val events: DeviceEventTimeline,
        val rules: AutomationRuleRepository,
        val runner: AutomationRuleRunner,
        val context: AutomationContext,
    )

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        batteryStatus: DeviceStatus = DeviceStatus.OK,
        foreground: Boolean = true,
        configure: (AutomationRuleRepository) -> Unit,
    ): Fixture {
        val link = FakeFrameLink(responder(batteryStatus))
        val dispatcher = ConcurrentCommandDispatcher(link, scope = scope)
        val eventStore = InMemoryDeviceEventStore()
        val events = DeviceEventTimeline(eventStore, clockMillis = { 100 })
        val rules = AutomationRuleRepository(
            InMemoryAutomationRuleStore(),
            clockMillis = { 1L },
        )
        configure(rules)
        val presets = ConfigPresetRepository(
            InMemoryConfigPresetStore(),
            clockMillis = { 1L },
        )
        val applier = ConfigPresetApplier(dispatcher)
        val detail = DeviceDetailController(dispatcher, deviceId)
        val runner = AutomationRuleRunner(
            rules = rules,
            presets = presets,
            presetApplier = applier,
            events = events,
            isForeground = { foreground },
            eventSummary = { "automation" },
            clockMillis = { 100 },
        )
        val context = AutomationContext(
            deviceId = deviceId,
            dispatcher = dispatcher,
            detail = detail,
            events = events,
            profileProvider = { null },
            healthCheckFactory = {
                DeviceHealthCheckController(
                    dispatcher = dispatcher,
                    isLinkAlive = { true },
                )
            },
            isLinkAlive = { true },
            platformDisplayName = "test",
        )
        return Fixture(link, events, rules, runner, context)
    }

    @Test
    fun `前台关闭时不执行任何规则`() = runTest {
        val fixture = fixture(backgroundScope, foreground = false) { repo ->
            repo.add(AutomationTrigger.CONNECTED, AutomationAction.READ_BATTERY)
        }
        val report = fixture.runner.run(AutomationTrigger.CONNECTED, fixture.context)
        assertTrue(report.skippedBecauseNotForeground)
        assertTrue(report.executions.isEmpty())
        assertTrue(fixture.events.list(deviceId).isEmpty())
    }

    @Test
    fun `只执行匹配触发时机且启用的规则`() = runTest {
        val fixture = fixture(backgroundScope) { repo ->
            repo.add(AutomationTrigger.CONNECTED, AutomationAction.READ_BATTERY)
            repo.add(AutomationTrigger.OTA_SUCCESS, AutomationAction.READ_FIRMWARE_VERSION)
            val disabled = repo.add(AutomationTrigger.CONNECTED, AutomationAction.READ_DEVICE_INFO)!!
            repo.setEnabled(disabled.id, false)
        }
        val report = fixture.runner.run(AutomationTrigger.CONNECTED, fixture.context)
        assertEquals(1, report.executions.size)
        assertEquals(AutomationAction.READ_BATTERY, report.executions.single().action)
        assertTrue(report.executions.single().success)
        assertEquals(1, fixture.events.list(deviceId).size)
        assertEquals(DeviceEventResult.SUCCESS, fixture.events.list(deviceId).single().result)
    }

    @Test
    fun `单条失败记录到时间线且继续执行后续规则`() = runTest {
        val fixture = fixture(backgroundScope, batteryStatus = DeviceStatus.BUSY) { repo ->
            repo.add(AutomationTrigger.CONNECTED, AutomationAction.READ_BATTERY)
            repo.add(AutomationTrigger.CONNECTED, AutomationAction.READ_FIRMWARE_VERSION)
        }
        val report = fixture.runner.run(AutomationTrigger.CONNECTED, fixture.context)
        assertEquals(2, report.executions.size)
        assertFalse(report.executions[0].success)
        assertEquals("0x07", report.executions[0].errorCode)
        assertTrue(report.executions[1].success)

        // 时间线按时间倒序展示；同一时间戳的事件按 id 排序，保证断言稳定。
        val events = fixture.events.list(deviceId).sortedBy { it.id }
        assertEquals(2, events.size)
        assertEquals(DeviceEventResult.FAILURE, events[0].result)
        assertEquals(DeviceEventResult.SUCCESS, events[1].result)
        assertEquals("automation", events[0].summary)
    }
}
