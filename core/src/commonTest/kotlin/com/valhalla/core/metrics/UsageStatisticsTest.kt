package com.valhalla.core.metrics

import com.valhalla.core.event.DeviceEvent
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.profile.DeviceProfile
import com.valhalla.core.profile.OtaHistoryRecord
import com.valhalla.core.profile.OtaOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageStatisticsTest {
    private val now = 100L * UsageRange.DAY_MILLIS

    private fun event(
        id: Long,
        deviceId: String,
        type: DeviceEventType,
        timestamp: Long,
        result: DeviceEventResult = DeviceEventResult.SUCCESS,
    ) = DeviceEvent(
        id = id,
        deviceId = deviceId,
        type = type,
        timestampMillis = timestamp,
        summary = type.name,
        result = result,
    )

    private fun profile(
        deviceId: String,
        ota: List<OtaHistoryRecord> = emptyList(),
        lastConnected: Long = now,
    ) = DeviceProfile(
        deviceId = deviceId,
        displayName = "Device $deviceId",
        lastConnectedAtEpochMillis = lastConnected,
        otaHistory = ota,
    )

    private fun ota(
        finishedAt: Long,
        duration: Long,
        outcome: OtaOutcome,
    ) = OtaHistoryRecord(
        firmwareVersion = "1.4.0",
        finishedAtEpochMillis = finishedAt,
        durationMillis = duration,
        outcome = outcome,
        sentBytes = 100,
        totalBytes = 100,
    )

    @Test
    fun `各维度只使用真实本地数据`() {
        val day = UsageRange.DAY_MILLIS
        val events =
            mapOf(
                "A" to
                    listOf(
                        event(1, "A", DeviceEventType.CONNECTION, now - 2 * day),
                        event(2, "A", DeviceEventType.DISCONNECTION, now - 2 * day + 60_000),
                        event(3, "A", DeviceEventType.CONNECTION, now - day),
                        event(4, "A", DeviceEventType.CONFIG_CHANGE, now - 3 * day),
                        event(5, "A", DeviceEventType.CONFIG_CHANGE, now - 2 * day),
                        event(6, "A", DeviceEventType.PARAM_CHANGE, now - day),
                        event(7, "A", DeviceEventType.HEALTH, now - day),
                        event(8, "A", DeviceEventType.CONFIG_CHANGE, now - 40 * day),
                        event(11, "A", DeviceEventType.CONFIG_CHANGE, now - 10 * day),
                    ),
                "B" to
                    listOf(
                        event(9, "B", DeviceEventType.CONNECTION, now - day),
                        event(10, "B", DeviceEventType.DISCONNECTION, now - day + 30_000),
                    ),
            )
        val profiles =
            listOf(
                profile(
                    "A",
                    ota =
                        listOf(
                            ota(now - day, 30_000, OtaOutcome.SUCCEEDED),
                            ota(now - 2 * day, 10_000, OtaOutcome.FAILED),
                        ),
                ),
                profile("B", ota = listOf(ota(now - 20 * day, 20_000, OtaOutcome.SUCCEEDED))),
            )

        val seven =
            UsageStatisticsCalculator.compute(
                profiles,
                events,
                now,
                UsageRange.LAST_7_DAYS,
            )
        // A 两次连接、B 一次连接，共 3 次；平均时长只统计完整会话：
        // A 的 60s + B 的 30s，A 第二次连接未断开不计入。
        assertEquals(3, seven.connectionCount)
        assertEquals(45_000L, seven.connectionAverageMillis)
        assertEquals(2, seven.otaAttempts)
        assertEquals(1, seven.otaSuccesses)
        assertEquals(0.5, seven.otaSuccessRate)
        assertEquals(30_000L, seven.otaAverageMillis)
        assertEquals(2, seven.configWrites)
        assertEquals(1, seven.paramWrites)
        assertEquals(1, seven.healthChecks)
        assertEquals(2, seven.devices.size)
        assertTrue(seven.hasAnyData)

        val thirty =
            UsageStatisticsCalculator.compute(
                profiles,
                events,
                now,
                UsageRange.LAST_30_DAYS,
            )
        assertEquals(3, thirty.otaAttempts)
        assertEquals(2, thirty.otaSuccesses)
        assertEquals(25_000L, thirty.otaAverageMillis)
        assertEquals(3, thirty.configWrites)

        val all = UsageStatisticsCalculator.compute(profiles, events, now, UsageRange.ALL)
        assertEquals(4, all.configWrites)
    }

    @Test
    fun `没有数据时不估算`() {
        val stats =
            UsageStatisticsCalculator.compute(
                profiles = emptyList(),
                eventsByDevice = emptyMap(),
                nowMillis = now,
                range = UsageRange.LAST_7_DAYS,
            )
        assertEquals(0, stats.connectionCount)
        assertNull(stats.connectionAverageMillis)
        assertEquals(0, stats.otaAttempts)
        assertNull(stats.otaSuccessRate)
        assertNull(stats.otaAverageMillis)
        assertTrue(stats.devices.isEmpty())
        assertFalse(stats.hasAnyData)
    }

    @Test
    fun `未结束的会话不计入平均时长`() {
        val events =
            mapOf(
                "A" to listOf(event(1, "A", DeviceEventType.CONNECTION, now - 1_000)),
            )
        val stats =
            UsageStatisticsCalculator.compute(
                profiles = listOf(profile("A")),
                eventsByDevice = events,
                nowMillis = now,
                range = UsageRange.ALL,
            )
        assertEquals(1, stats.connectionCount)
        assertNull(stats.connectionAverageMillis)
    }
}
