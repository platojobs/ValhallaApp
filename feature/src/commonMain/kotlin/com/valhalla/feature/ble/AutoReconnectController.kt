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
 */
class AutoReconnectController(
    policy: ReconnectPolicy = ReconnectPolicy(),
    private val scope: CoroutineScope,
    private val isBluetoothOn: () -> Boolean,
    private val attemptConnect: suspend () -> BleConnection?,
    private val onConnected: (BleConnection) -> Unit,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
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
            job = scope.launch { loop() }
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
            job = scope.launch { loop() }
        }
        publish()
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
