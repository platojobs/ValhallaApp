package com.valhalla.feature.metrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 信号样本来源：扫描/连接时快照，或连接建立后的周期实时读取。 */
enum class RssiSource {
    SNAPSHOT,
    CONNECTED_LIVE,
}

/** 一条信号读数；[source] 表明它来自快照还是实时订阅。 */
data class RssiReading(
    val rssi: Int,
    val source: RssiSource,
    val timestampMillis: Long,
)

/** 订阅降级原因；界面据此说明「为什么回到快照」。 */
enum class RssiDegradedReason {
    READ_FAILED,
    DISCONNECTED,
}

/** 订阅状态快照，UI 直接消费。 */
data class RssiSubscriptionState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val latest: RssiReading? = null,
    val source: RssiSource = RssiSource.SNAPSHOT,
    val degradedReason: RssiDegradedReason? = null,
)

/**
 * 连接期间实时 RSSI 订阅控制器。
 *
 * 约定：
 * - 只在链路已连接时采样；断开、关闭开关或连续 [failureThreshold] 次读取失败即停止；
 * - 读取失败不抛出到 UI，降级为扫描快照并记录原因；
 * - [run] 可被 Compose 的 LaunchedEffect 直接取消，CancellationException 原样透传；
 * - 样本仍写入既有 [com.valhalla.core.metrics.DeviceMetricRepository]，保持 50 点上限。
 */
@OptIn(ExperimentalTime::class)
class RssiSubscriptionController(
    private val readRssi: suspend () -> Int?,
    private val onSample: (RssiReading) -> Unit = {},
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
) {

    init {
        require(failureThreshold > 0) { "failureThreshold 必须为正数" }
    }

    private val mutableState = MutableStateFlow(RssiSubscriptionState())
    val state: StateFlow<RssiSubscriptionState> = mutableState.asStateFlow()

    private var consecutiveFailures: Int = 0

    /** 单次采样；返回 null 表示本次读取失败并已计入降级判断。 */
    suspend fun sampleOnce(): Int? {
        val value = try {
            readRssi()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        if (value == null) {
            consecutiveFailures++
            val degraded = consecutiveFailures >= failureThreshold
            mutableState.update { current ->
                current.copy(
                    active = if (degraded) false else current.active,
                    source = if (degraded) RssiSource.SNAPSHOT else current.source,
                    degradedReason = if (degraded) RssiDegradedReason.READ_FAILED else current.degradedReason,
                )
            }
            return null
        }
        consecutiveFailures = 0
        val reading = RssiReading(value, RssiSource.CONNECTED_LIVE, clockMillis())
        mutableState.update {
            it.copy(
                active = true,
                latest = reading,
                source = RssiSource.CONNECTED_LIVE,
                degradedReason = null,
            )
        }
        onSample(reading)
        return value
    }

    /**
     * 周期采样直到 [shouldContinue] 为 false、开关关闭或连续失败降级。
     *
     * @param periodMillis 采样周期，建议 1–10 秒；小于 1 秒按 1 秒执行，避免密集读取。
     */
    suspend fun run(periodMillis: Long, shouldContinue: () -> Boolean) {
        val period = periodMillis.coerceAtLeast(MIN_PERIOD_MILLIS)
        mutableState.update { it.copy(enabled = true, active = true, degradedReason = null) }
        try {
            while (shouldContinue() && mutableState.value.enabled) {
                sampleOnce()
                if (!shouldContinue()) break
                if (mutableState.value.degradedReason == RssiDegradedReason.READ_FAILED) break
                delay(period)
            }
        } finally {
            val disconnected = !shouldContinue()
            mutableState.update { current ->
                current.copy(
                    enabled = false,
                    active = false,
                    source = RssiSource.SNAPSHOT,
                    degradedReason = when {
                        current.degradedReason == RssiDegradedReason.READ_FAILED ->
                            RssiDegradedReason.READ_FAILED

                        disconnected -> RssiDegradedReason.DISCONNECTED
                        else -> null
                    },
                )
            }
        }
    }

    /** 用户关闭开关时调用：立即停止并回到快照来源。 */
    fun stop() {
        mutableState.update {
            it.copy(enabled = false, active = false, source = RssiSource.SNAPSHOT)
        }
    }

    /** 主动标记断开：不清空最后一次读数，方便界面解释「已回退到快照」。 */
    fun markDisconnected() {
        mutableState.update {
            it.copy(
                enabled = false,
                active = false,
                source = RssiSource.SNAPSHOT,
                degradedReason = RssiDegradedReason.DISCONNECTED,
            )
        }
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD: Int = 3
        const val MIN_PERIOD_MILLIS: Long = 1_000L
    }
}
