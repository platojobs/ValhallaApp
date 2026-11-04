package com.valhalla.feature.ble

import com.valhalla.data.ble.BleConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 自动重连执行器：把 [ReconnectScheduler] 的退避决策落到真实连接尝试上。
 *
 * - 指数退避等待由 [delayMillis] 注入，测试可用虚拟时间精确核对序列；
 * - 蓝牙关闭时立即暂停并取消在途等待/连接，重新开启后从当前次数继续；
 * - 重连期间（WAITING / CONNECTING）[start] 不会产生第二条协程，避免重复发起。
 *
 * 异常边界：连接尝试抛出的非取消异常会经 [onError] 上报并记作一次失败，
 * 由调度器推进到下一次等待或耗尽；若异常发生在 CONNECTING 之后，
 * 不会让状态永久停在 CONNECTING。
 */
class AutoReconnectController(
    policy: ReconnectPolicy = ReconnectPolicy(),
    private val scope: CoroutineScope,
    private val isBluetoothOn: () -> Boolean,
    private val attemptConnect: suspend () -> BleConnection?,
    private val onConnected: (BleConnection) -> Unit,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val onError: (Throwable) -> Unit = {},
) {

    private val scheduler = ReconnectScheduler(policy)
    private val mutableState = MutableStateFlow(ReconnectState())
    val state: StateFlow<ReconnectState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** 链路断开后启动自动重连；蓝牙关闭时直接进入暂停。 */
    fun start() {
        if (job?.isActive == true) return
        scheduler.reset()
        val bluetoothOn = isBluetoothOn()
        scheduler.onDisconnected(bluetoothAvailable = bluetoothOn)
        publish()
        if (bluetoothOn && scheduler.state.phase == ReconnectPhase.WAITING) {
            job = scope.launch { loopSafely() }
        }
    }

    /** 手动取消；清空退避计数，不再自动尝试。 */
    fun cancel() {
        job?.cancel()
        job = null
        scheduler.cancel()
        publish()
    }

    /** 由 UI 在适配器状态变化时调用。 */
    fun onBluetoothStateChanged(available: Boolean) {
        val wasPaused = scheduler.state.phase == ReconnectPhase.PAUSED
        scheduler.onBluetoothStateChanged(available)
        if (!available) {
            job?.cancel()
            job = null
        } else if (wasPaused && job?.isActive != true) {
            job = scope.launch { loopSafely() }
        }
        publish()
    }

    /**
     * 异常收敛包装：任何非取消异常都上报并推进调度器，避免停留在 CONNECTING。
     *
     * - 异常发生在 CONNECTING：记作一次失败，由调度器推进到下一次等待或耗尽；
     * - 异常发生在等待/蓝牙检查阶段（例如注入的 delay 抛错）：直接进入 CANCELLED 终态，
     *   避免「同一异常反复触发、无限重试」的死循环。
     */
    private suspend fun loopSafely() {
        while (true) {
            try {
                loop()
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                onError(error)
                if (scheduler.state.phase == ReconnectPhase.CONNECTING) {
                    scheduler.onAttemptResult(succeeded = false)
                    publish()
                    if (scheduler.state.phase != ReconnectPhase.WAITING) return
                } else {
                    scheduler.cancel()
                    publish()
                    return
                }
            }
        }
    }

    private suspend fun loop() {
        while (true) {
            val current = scheduler.state
            if (current.phase != ReconnectPhase.WAITING) return
            val waitMillis = current.nextDelayMillis ?: return
            delayMillis(waitMillis)

            if (!isBluetoothOn()) {
                scheduler.onBluetoothStateChanged(false)
                publish()
                return
            }

            if (!scheduler.beginAttempt()) return
            publish()

            val connection = try {
                attemptConnect()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }

            if (!isBluetoothOn()) {
                scheduler.onBluetoothStateChanged(false)
                publish()
                return
            }

            scheduler.onAttemptResult(succeeded = connection != null)
            publish()

            if (connection != null) {
                onConnected(connection)
                return
            }
            if (scheduler.state.phase != ReconnectPhase.WAITING) return
        }
    }

    private fun publish() {
        mutableState.value = scheduler.state
    }
}
