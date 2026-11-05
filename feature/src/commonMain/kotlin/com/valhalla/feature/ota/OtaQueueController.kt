package com.valhalla.feature.ota

import com.valhalla.core.ota.OtaQueue
import com.valhalla.core.ota.OtaQueueItem
import com.valhalla.core.ota.OtaQueueItemStatus
import com.valhalla.core.ota.OtaQueueStage
import com.valhalla.core.ota.OtaQueueSummary
import com.valhalla.core.ota.OtaQueueTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 队列执行器返回的终态。 */
sealed interface OtaQueueExecutionResult {

    data object Succeeded : OtaQueueExecutionResult

    data class Failed(
        val reason: String,
        val checkpointRetained: Boolean,
    ) : OtaQueueExecutionResult

    data class Paused(
        val progress: Float,
        val checkpointRetained: Boolean,
    ) : OtaQueueExecutionResult

    data class Aborted(
        val reason: String,
        val checkpointRetained: Boolean,
    ) : OtaQueueExecutionResult

    data class Skipped(val reason: String) : OtaQueueExecutionResult
}

/** 单台设备升级的执行适配器；实现方负责预检、打开固件与调用 OtaController。 */
interface OtaQueueExecutor {

    suspend fun execute(
        item: OtaQueueItem,
        progress: (OtaQueueStage, Float) -> Unit,
        pauseRequested: () -> Boolean,
        cancelRequested: () -> Boolean,
    ): OtaQueueExecutionResult
}

data class OtaQueueSnapshot(
    val items: List<OtaQueueItem> = emptyList(),
    val running: Boolean = false,
    val pauseRequested: Boolean = false,
    val currentDeviceId: String? = null,
) {
    val summary: OtaQueueSummary get() = OtaQueueSummary(items)
}

/**
 * 串行 OTA 队列控制器。
 *
 * 队列只在内存中推进；断点由 OtaController/OtaCheckpointStore 按既有规则持久化。
 * App 退出后控制器与队列都不再存在，新的队列从空开始（不提供跨进程续跑）。
 */
class OtaQueueController(
    private val queue: OtaQueue = OtaQueue(),
    private val scope: CoroutineScope,
    private val executor: OtaQueueExecutor,
) {

    private val mutableState = MutableStateFlow(OtaQueueSnapshot())
    val state: StateFlow<OtaQueueSnapshot> = mutableState.asStateFlow()

    private var job: Job? = null
    private var currentDeviceId: String? = null
    private var cancelCurrentRequested = false

    fun enqueue(target: OtaQueueTarget): Boolean {
        val added = queue.enqueue(target)
        publish()
        return added
    }

    fun removeQueued(deviceId: String): Boolean {
        val removed = queue.removeQueued(deviceId)
        publish()
        return removed
    }

    fun pause() {
        if (currentDeviceId == null) return
        queue.requestPause()
        publish()
    }

    fun resume() {
        if (job?.isActive == true) return
        queue.resumePaused()
        cancelCurrentRequested = false
        publish()
        start()
    }

    /** 取消当前设备（走 OtaController 的 Abort 语义），随后继续下一台。 */
    fun cancelCurrent() {
        if (currentDeviceId != null) {
            cancelCurrentRequested = true
        }
    }

    /** 失败设备保留断点，仅把汇总状态改为跳过。 */
    fun skipFailed(deviceId: String): Boolean {
        val changed = queue.skipFailed(deviceId)
        publish()
        return changed
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runQueue() }
        publish()
    }

    private suspend fun runQueue() {
        try {
            while (true) {
                val next = queue.nextQueued() ?: break
                currentDeviceId = next.target.deviceId
                cancelCurrentRequested = false
                queue.markRunning(next.target.deviceId)
                publish()

                val result = try {
                    executor.execute(
                        item = next,
                        progress = { stage, progress ->
                            queue.updateProgress(next.target.deviceId, stage, progress)
                            publish()
                        },
                        pauseRequested = { queue.pauseRequested },
                        cancelRequested = { cancelCurrentRequested },
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    OtaQueueExecutionResult.Failed(
                        reason = error.message ?: "queue.execution_error",
                        checkpointRetained = false,
                    )
                }

                when (result) {
                    is OtaQueueExecutionResult.Succeeded ->
                        queue.markSucceeded(next.target.deviceId)

                    is OtaQueueExecutionResult.Failed ->
                        queue.markFailed(
                            next.target.deviceId,
                            result.reason,
                            result.checkpointRetained,
                        )

                    is OtaQueueExecutionResult.Paused ->
                        queue.markPausedAtSafePoint(
                            next.target.deviceId,
                            result.progress,
                            result.checkpointRetained,
                        )

                    is OtaQueueExecutionResult.Aborted ->
                        queue.markAborted(
                            next.target.deviceId,
                            result.reason,
                            result.checkpointRetained,
                        )

                    is OtaQueueExecutionResult.Skipped ->
                        queue.markSkipped(next.target.deviceId, result.reason)
                }
                currentDeviceId = null
                publish()

                if (result is OtaQueueExecutionResult.Paused) {
                    break
                }
            }
        } finally {
            job = null
            currentDeviceId = null
            publish()
        }
    }

    private fun publish() {
        mutableState.value = OtaQueueSnapshot(
            items = queue.items,
            running = job?.isActive == true,
            pauseRequested = queue.pauseRequested,
            currentDeviceId = currentDeviceId,
        )
    }
}
