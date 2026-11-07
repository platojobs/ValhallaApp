package com.valhalla.feature.config

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ConfigPreset
import com.valhalla.core.config.ConfigPresetDiffer
import com.valhalla.core.config.DeviceConfigTarget
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
import com.valhalla.feature.FakeFrameLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigPresetApplierTest {
    private val target =
        DeviceConfigTarget(
            firmwareVersion = FirmwareVersion(1, 3, 0, 0, "stable"),
            model = "VH-1000",
        )

    private val preset =
        ConfigPreset(
            id = "p1",
            name = "演示",
            values =
                mapOf(
                    "device_name" to "Valhalla Demo",
                    "led_enabled" to "true",
                    "sample_interval_ms" to "1500",
                ),
            createdAtMillis = 0,
            lastUsedAtMillis = 0,
        )

    private val current =
        mapOf(
            "device_name" to "Valhalla",
            "led_enabled" to "false",
            "sample_interval_ms" to "1000",
        )

    @Test
    fun `逐键写入且单键失败不影响后续`() =
        runTest {
            val link =
                FakeFrameLink { request ->
                    when (request) {
                        is SetConfigRequest ->
                            if (request.key == "led_enabled") {
                                SetConfigResponse(DeviceStatus.INVALID_PARAMETER)
                            } else {
                                SetConfigResponse(DeviceStatus.OK)
                            }

                        else -> null
                    }
                }
            val applier =
                ConfigPresetApplier(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                )
            val report = applier.apply(ConfigPresetDiffer.diff(preset, target, current))

            assertEquals(3, report.results.size)
            assertEquals(2, report.writtenCount)
            assertEquals(1, report.failedCount)
            assertEquals(0, report.skippedCount)
            assertTrue(!report.allSucceeded)
            val failed = report.results.single { it.status == PresetApplyStatus.FAILED }
            assertEquals("led_enabled", failed.key)
            assertEquals("0x03", failed.errorCode)
            val sent = link.requests.filterIsInstance<SetConfigRequest>().map { it.key }
            assertEquals(listOf("device_name", "led_enabled", "sample_interval_ms"), sent)
        }

    @Test
    fun `能力不满足的键跳过且不发请求`() =
        runTest {
            val oldTarget =
                DeviceConfigTarget(
                    firmwareVersion = FirmwareVersion(1, 2, 0, 0, "stable"),
                    model = "VH-1000",
                )
            val withTxPower = preset.copy(values = preset.values + ("tx_power_dbm" to "5"))
            val link =
                FakeFrameLink { request ->
                    if (request is SetConfigRequest) SetConfigResponse(DeviceStatus.OK) else null
                }
            val applier =
                ConfigPresetApplier(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                )
            val diffs = ConfigPresetDiffer.diff(withTxPower, oldTarget, current)
            val report = applier.apply(diffs)

            assertEquals(1, report.skippedCount)
            assertEquals(0, report.failedCount)
            val skipped = report.results.single { it.status == PresetApplyStatus.SKIPPED }
            assertEquals("tx_power_dbm", skipped.key)
            assertTrue(!skipped.detail.isNullOrBlank())
            assertTrue(link.requests.filterIsInstance<SetConfigRequest>().none { it.key == "tx_power_dbm" })
        }

    @Test
    fun `已是目标值不重复下发`() =
        runTest {
            val link =
                FakeFrameLink { request ->
                    if (request is SetConfigRequest) SetConfigResponse(DeviceStatus.OK) else null
                }
            val applier =
                ConfigPresetApplier(
                    ConcurrentCommandDispatcher(link, scope = backgroundScope),
                )
            val same = preset.copy(values = mapOf("device_name" to "Valhalla"))
            val report =
                applier.apply(
                    ConfigPresetDiffer.diff(
                        same,
                        target,
                        mapOf("device_name" to "Valhalla"),
                    ),
                )
            assertEquals(1, report.alreadyCount)
            assertEquals(0, report.writtenCount)
            assertEquals(0, link.requests.filterIsInstance<SetConfigRequest>().size)
        }
}
