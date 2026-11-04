package com.valhalla.feature.ble

/**
 * 指数退避策略。
 *
 * 第 n 次尝试（n 从 1 开始）的等待时间为 `initialDelayMillis * multiplier^(n-1)`，
 * 并以 [maxDelayMillis] 封顶；最多尝试 [maxAttempts] 次。
 */
data class ReconnectPolicy(
    val initialDelayMillis: Long = 1_000L,
    val multiplier: Double = 2.0,
    val maxDelayMillis: Long = 30_000L,
    val maxAttempts: Int = 5,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis 必须大于 0" }
        require(multiplier >= 1.0) { "multiplier 必须不小于 1" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis 不能小于 initialDelayMillis" }
        require(maxAttempts > 0) { "maxAttempts 必须大于 0" }
    }

    /** 第 [attempt] 次尝试前的等待时间（attempt 从 1 开始）。 */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 1) return initialDelayMillis
        var delay = initialDelayMillis.toDouble()
        repeat(attempt - 1) {
            delay *= multiplier
            if (delay >= maxDelayMillis.toDouble()) return maxDelayMillis
        }
        return delay.toLong().coerceAtMost(maxDelayMillis)
    }

    /** 完整退避序列（长度为 [maxAttempts]），便于日志与测试核对。 */
    fun delaySequence(): List<Long> = (1..maxAttempts).map(::delayForAttempt)
}

/** 自动重连的当前阶段。 */
enum class ReconnectPhase {
    IDLE,
    /** 等待下一次尝试（[ReconnectState.nextDelayMillis] 为倒计时基数）。 */
    WAITING,
    /** 正在建立连接；此阶段禁止重复发起。 */
    CONNECTING,
    /** 达到次数上限。 */
    EXHAUSTED,
    /** 蓝牙关闭导致暂停，重新开启后恢复。 */
    PAUSED,
    /** 用户手动取消。 */
    CANCELLED,
    /** 重连成功。 */
    SUCCEEDED,
}

data class ReconnectState(
    val phase: ReconnectPhase = ReconnectPhase.IDLE,
    /** 已完成的尝试次数。 */
    val attempt: Int = 0,
    /** 下一次尝试的等待毫秒数；非 WAITING 阶段为 null。 */
    val nextDelayMillis: Long? = null,
    /** 暂停原因（目前只有蓝牙关闭）。 */
    val pausedByBluetoothOff: Boolean = false,
)

/**
 * 重连调度状态机（纯逻辑，不负责真正发起连接）。
 *
 * 关键约束：
 * - [WAITING] / [CONNECTING] 阶段 [beginAttempt] 返回 false，天然禁止重复发起；
 * - 达到 [ReconnectPolicy.maxAttempts] 后进入 [ReconnectPhase.EXHAUSTED]，不再自动重试；
 * - 蓝牙关闭时进入 [ReconnectPhase.PAUSED]；重新开启后从当前已用次数继续退避；
 * - [cancel] 之后为 [ReconnectPhase.CANCELLED]，直到下一次 [onDisconnected] 重新开始。
 */
class ReconnectScheduler(
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) {

    var state: ReconnectState = ReconnectState()
        private set

    /** 链路断开后调用；蓝牙关闭时直接暂停而不消耗次数。 */
    fun onDisconnected(bluetoothAvailable: Boolean = true): ReconnectState {
        if (state.phase == ReconnectPhase.WAITING || state.phase == ReconnectPhase.CONNECTING) {
            return state
        }
        state = if (!bluetoothAvailable) {
            ReconnectState(
                phase = ReconnectPhase.PAUSED,
                attempt = state.attempt,
                pausedByBluetoothOff = true,
            )
        } else {
            scheduleNext(attempt = state.attempt)
        }
        return state
    }

    /** 开始一次连接尝试；仅 WAITING 阶段允许。 */
    fun beginAttempt(): Boolean {
        if (state.phase != ReconnectPhase.WAITING) return false
        state = state.copy(phase = ReconnectPhase.CONNECTING, nextDelayMillis = null)
        return true
    }

    /** 一次连接尝试的结果。 */
    fun onAttemptResult(succeeded: Boolean): ReconnectState {
        if (state.phase != ReconnectPhase.CONNECTING) return state
        val attempts = state.attempt + 1
        state = when {
            succeeded -> ReconnectState(phase = ReconnectPhase.SUCCEEDED)
            attempts >= policy.maxAttempts -> ReconnectState(
                phase = ReconnectPhase.EXHAUSTED,
                attempt = attempts,
            )

            else -> scheduleNext(attempt = attempts)
        }
        return state
    }

    /** 蓝牙状态变化。关闭时暂停；从暂停恢复时继续退避。 */
    fun onBluetoothStateChanged(available: Boolean): ReconnectState {
        if (!available) {
            if (state.phase == ReconnectPhase.WAITING || state.phase == ReconnectPhase.CONNECTING) {
                state = ReconnectState(
                    phase = ReconnectPhase.PAUSED,
                    attempt = state.attempt,
                    pausedByBluetoothOff = true,
                )
            }
        } else if (state.phase == ReconnectPhase.PAUSED && state.pausedByBluetoothOff) {
            state = scheduleNext(attempt = state.attempt)
        }
        return state
    }

    /** 手动取消，清空退避计数。 */
    fun cancel(): ReconnectState {
        state = ReconnectState(phase = ReconnectPhase.CANCELLED)
        return state
    }

    /** 重置为初始状态（例如成功重连并开始新会话后）。 */
    fun reset(): ReconnectState {
        state = ReconnectState()
        return state
    }

    private fun scheduleNext(attempt: Int): ReconnectState {
        if (attempt >= policy.maxAttempts) {
            return ReconnectState(phase = ReconnectPhase.EXHAUSTED, attempt = attempt)
        }
        val next = attempt + 1
        return ReconnectState(
            phase = ReconnectPhase.WAITING,
            attempt = attempt,
            nextDelayMillis = policy.delayForAttempt(next),
        )
    }
}
