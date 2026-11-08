package com.valhalla.core.metrics

import com.valhalla.core.event.DeviceEvent
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.profile.DeviceProfile
import com.valhalla.core.profile.OtaOutcome

/** 使用统计的时间范围。 */
enum class UsageRange {
    LAST_7_DAYS,
    LAST_30_DAYS,
    ALL,
    ;

    /** 起始时间；[ALL] 返回 null 表示不设下限。 */
    fun startMillis(nowMillis: Long): Long? =
        when (this) {
            LAST_7_DAYS -> nowMillis - DAY_MILLIS * LAST_7_DAYS_DAY_COUNT
            LAST_30_DAYS -> nowMillis - DAY_MILLIS * LAST_30_DAYS_DAY_COUNT
            ALL -> null
        }

    companion object {
        const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
        const val LAST_7_DAYS_DAY_COUNT: Int = 7
        const val LAST_30_DAYS_DAY_COUNT: Int = 30
    }
}

/** 单个设备在本时间范围内的真实使用数据。 */
data class DeviceUsage(
    val deviceId: String,
    val displayName: String?,
    val connections: Int,
    val otaAttempts: Int,
    val configWrites: Int,
    val paramWrites: Int,
    val healthChecks: Int,
    val lastConnectedAtEpochMillis: Long?,
) {
    val totalActions: Int get() = connections + otaAttempts + configWrites + paramWrites + healthChecks
}

/**
 * 本机使用统计快照。
 *
 * 所有字段都来自真实本地数据：连接/写入/体检来自事件时间线，OTA 来自设备档案；
 * 没有任何数据时指标为 0 或 null，界面显示「暂无数据」，不做估算。
 */
data class UsageStatistics(
    val range: UsageRange,
    val connectionCount: Int = 0,
    val connectionAverageMillis: Long? = null,
    val otaAttempts: Int = 0,
    val otaSuccesses: Int = 0,
    val otaSuccessRate: Double? = null,
    val otaAverageMillis: Long? = null,
    val configWrites: Int = 0,
    val paramWrites: Int = 0,
    val healthChecks: Int = 0,
    val devices: List<DeviceUsage> = emptyList(),
) {
    val hasAnyData: Boolean
        get() =
            connectionCount > 0 ||
                otaAttempts > 0 ||
                configWrites > 0 ||
                paramWrites > 0 ||
                healthChecks > 0 ||
                devices.any { it.totalActions > 0 }
}

/**
 * 从本机真实数据聚合统计。
 *
 * 口径：
 * - 连接次数：事件时间线中成功的 `CONNECTION` 事件；
 * - 平均连接时长：同一设备相邻的「连接成功」与「断开」事件配对后的完整会话，
 *   未结束的会话不计入，避免估算；
 * - OTA：设备档案中的历史记录，按结束时间筛选；
 * - 配置/参数写入：事件时间线中成功的 `CONFIG_CHANGE` / `PARAM_CHANGE`；
 * - 体检次数：`HEALTH` 事件条数（每次运行落一条）；
 * - 按设备分布：设备档案 + 上述事件归属。
 */
object UsageStatisticsCalculator {
    fun compute(
        profiles: List<DeviceProfile>,
        eventsByDevice: Map<String, List<DeviceEvent>>,
        nowMillis: Long,
        range: UsageRange,
    ): UsageStatistics {
        val start = range.startMillis(nowMillis)
        val profileById = profiles.associateBy { it.deviceId }

        val connectionCounts = mutableMapOf<String, Int>()
        val configCounts = mutableMapOf<String, Int>()
        val paramCounts = mutableMapOf<String, Int>()
        val healthCounts = mutableMapOf<String, Int>()
        val durations = mutableListOf<Long>()

        eventsByDevice.forEach { (deviceId, events) ->
            val inRange =
                events.filter { event ->
                    event.timestampMillis <= nowMillis && (start == null || event.timestampMillis >= start)
                }
            connectionCounts[deviceId] =
                inRange.count {
                    it.type == DeviceEventType.CONNECTION && it.result == DeviceEventResult.SUCCESS
                }
            configCounts[deviceId] =
                inRange.count {
                    it.type == DeviceEventType.CONFIG_CHANGE && it.result == DeviceEventResult.SUCCESS
                }
            paramCounts[deviceId] =
                inRange.count {
                    it.type == DeviceEventType.PARAM_CHANGE && it.result == DeviceEventResult.SUCCESS
                }
            healthCounts[deviceId] = inRange.count { it.type == DeviceEventType.HEALTH }
            durations += completedSessionDurations(events, start, nowMillis)
        }

        val otaByDevice = mutableMapOf<String, List<com.valhalla.core.profile.OtaHistoryRecord>>()
        profiles.forEach { profile ->
            otaByDevice[profile.deviceId] =
                profile.otaHistory.filter { record ->
                    record.finishedAtEpochMillis <= nowMillis &&
                        (start == null || record.finishedAtEpochMillis >= start)
                }
        }
        val otaRecords = otaByDevice.values.flatten()
        val successes = otaRecords.count { it.outcome == OtaOutcome.SUCCEEDED }
        val succeededDurations =
            otaRecords
                .filter { it.outcome == OtaOutcome.SUCCEEDED }
                .map { it.durationMillis }

        val deviceIds = (profiles.map { it.deviceId } + eventsByDevice.keys).distinct()
        val devices =
            deviceIds
                .map { deviceId ->
                    val profile = profileById[deviceId]
                    DeviceUsage(
                        deviceId = deviceId,
                        displayName = profile?.displayName ?: profile?.model,
                        connections = connectionCounts[deviceId] ?: 0,
                        otaAttempts = otaByDevice[deviceId]?.size ?: 0,
                        configWrites = configCounts[deviceId] ?: 0,
                        paramWrites = paramCounts[deviceId] ?: 0,
                        healthChecks = healthCounts[deviceId] ?: 0,
                        lastConnectedAtEpochMillis = profile?.lastConnectedAtEpochMillis,
                    )
                }.sortedWith(
                    compareByDescending<DeviceUsage> { it.totalActions }.thenBy { it.deviceId },
                )

        return UsageStatistics(
            range = range,
            connectionCount = connectionCounts.values.sum(),
            connectionAverageMillis = durations.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            otaAttempts = otaRecords.size,
            otaSuccesses = successes,
            otaSuccessRate = if (otaRecords.isEmpty()) null else successes.toDouble() / otaRecords.size,
            otaAverageMillis = succeededDurations.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            configWrites = configCounts.values.sum(),
            paramWrites = paramCounts.values.sum(),
            healthChecks = healthCounts.values.sum(),
            devices = devices.filter { it.totalActions > 0 },
        )
    }

    /**
     * 把事件按时间正序配对：连接成功开启会话，断开事件结束会话。
     * 只有「连接时间落在范围内」的完整会话才计入平均时长。
     */
    private fun completedSessionDurations(
        events: List<DeviceEvent>,
        start: Long?,
        nowMillis: Long,
    ): List<Long> {
        val sorted =
            events.sortedWith(
                compareBy<DeviceEvent> { it.timestampMillis }.thenBy { it.id },
            )
        val durations = mutableListOf<Long>()
        var openAt: Long? = null
        sorted.forEach { event ->
            when (event.type) {
                DeviceEventType.CONNECTION -> {
                    if (event.result == DeviceEventResult.SUCCESS) openAt = event.timestampMillis
                }

                DeviceEventType.DISCONNECTION -> {
                    val started = openAt ?: return@forEach
                    val ended = event.timestampMillis
                    openAt = null
                    val withinWindow = ended <= nowMillis
                    val startsAfterFilter = start == null || started >= start
                    val hasValidSpan = ended >= started
                    if (withinWindow && startsAfterFilter && hasValidSpan) {
                        durations += ended - started
                    }
                }

                else -> Unit
            }
        }
        return durations
    }
}
