package com.valhalla.feature.batch

import com.valhalla.core.batch.BatchItemState
import com.valhalla.core.batch.BatchItemStatus
import com.valhalla.core.batch.BatchOperationKind
import com.valhalla.core.batch.BatchSummary
import com.valhalla.core.batch.BatchTarget
import com.valhalla.core.batch.DeviceOperationLocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 单台设备的执行结果；批量控制器只负责汇总与继续下一台。 */
data class BatchDeviceOutcome(
    val status: BatchItemStatus,
    val step: String? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    init {
        require(status.terminal) { "执行结果必须是终态: $status" }
    }
}

/**
 * 串行批量执行器。
 *
 * - 一次只执行一台，避免 GATT 并发冲突；
 * - 单台失败/断链只标记该台并继续下一台；
 * - [cancelRemaining] 只取消尚未开始的条目，当前条目允许自然结束；
 * - 执行期间对参与者加 [DeviceOperationLocks] 锁，结束后在 finally 中释放。
 */
class BatchOperationController(
    private val locks: DeviceOperationLocks,
    private val isConnected: (String) -> Boolean = { true },
) {
    private val mutableItems = MutableStateFlow<List<BatchItemState>>(emptyList())
    val items: StateFlow<List<BatchItemState>> = mutableItems.asStateFlow()

    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    private val mutableCancelRequested = MutableStateFlow(false)
    val cancelRequested: StateFlow<Boolean> = mutableCancelRequested.asStateFlow()

    /** 请求取消尚未开始的条目；当前条目完成后生效。 */
    fun cancelRemaining() {
        if (mutableRunning.value) mutableCancelRequested.value = true
    }

    suspend fun run(
        kind: BatchOperationKind,
        targets: List<BatchTarget>,
        execute: suspend (BatchTarget, (String) -> Unit) -> BatchDeviceOutcome,
    ): BatchSummary {
        require(!mutableRunning.value) { "批量任务已在执行" }
        mutableRunning.value = true
        mutableCancelRequested.value = false
        mutableItems.value = targets.map { BatchItemState(target = it) }

        val lock = locks.tryLock(targets.map { it.deviceId })
        val lockedIds = lock.locked
        try {
            lock.busy.forEach { deviceId ->
                update(deviceId) {
                    copy(
                        status = BatchItemStatus.SKIPPED,
                        reason = "batch.device_locked",
                    )
                }
            }

            var cancelled = false
            for (target in targets) {
                if (target.deviceId !in lockedIds) continue
                if (cancelled || mutableCancelRequested.value) {
                    cancelled = true
                    update(target.deviceId) {
                        copy(status = BatchItemStatus.CANCELLED, reason = "batch.cancelled_remaining")
                    }
                    continue
                }
                if (!isConnected(target.deviceId)) {
                    update(target.deviceId) {
                        copy(status = BatchItemStatus.REMOVED, reason = "batch.disconnected")
                    }
                    continue
                }

                update(target.deviceId) {
                    copy(status = BatchItemStatus.RUNNING, step = "batch.started", reason = null)
                }
                val outcome =
                    try {
                        execute(target) { step ->
                            update(target.deviceId) { copy(step = step) }
                        }
                    } catch (cancelledError: CancellationException) {
                        update(target.deviceId) {
                            copy(status = BatchItemStatus.CANCELLED, reason = "batch.job_cancelled")
                        }
                        markRemainingCancelled(targets, fromDeviceId = target.deviceId)
                        throw cancelledError
                    } catch (error: Throwable) {
                        BatchDeviceOutcome(
                            status = BatchItemStatus.FAILED,
                            reason = "batch.execution_error",
                            detail = error.message,
                        )
                    }

                update(target.deviceId) {
                    copy(
                        status =
                            if (!isConnected(target.deviceId) &&
                                outcome.status == BatchItemStatus.SUCCEEDED
                            ) {
                                BatchItemStatus.REMOVED
                            } else {
                                outcome.status
                            },
                        step = outcome.step,
                        reason =
                            if (!isConnected(target.deviceId) &&
                                outcome.status == BatchItemStatus.SUCCEEDED
                            ) {
                                "batch.disconnected_after_step"
                            } else {
                                outcome.reason
                            },
                        detail = outcome.detail,
                    )
                }

                if (mutableCancelRequested.value) {
                    cancelled = true
                    markRemainingCancelled(targets, fromDeviceId = target.deviceId)
                }
            }
        } finally {
            locks.release(lockedIds)
            mutableRunning.value = false
        }
        return summary(kind)
    }

    private fun markRemainingCancelled(
        targets: List<BatchTarget>,
        fromDeviceId: String,
    ) {
        val started = targets.indexOfFirst { it.deviceId == fromDeviceId }
        targets.drop(started + 1).forEach { target ->
            update(target.deviceId) {
                if (status == BatchItemStatus.QUEUED) {
                    copy(status = BatchItemStatus.CANCELLED, reason = "batch.cancelled_remaining")
                } else {
                    this
                }
            }
        }
    }

    private fun update(
        deviceId: String,
        transform: BatchItemState.() -> BatchItemState,
    ) {
        val updated =
            mutableItems.value.map { item ->
                if (item.target.deviceId == deviceId) item.transform() else item
            }
        mutableItems.value = updated
    }

    private fun summary(kind: BatchOperationKind): BatchSummary =
        BatchSummary(
            kind = kind,
            items = mutableItems.value,
            cancelRequested = mutableCancelRequested.value,
        )
}
