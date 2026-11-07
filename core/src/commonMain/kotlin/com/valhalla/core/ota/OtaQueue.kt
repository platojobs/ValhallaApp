package com.valhalla.core.ota

import com.valhalla.core.logging.PacketLogRedaction
import com.valhalla.core.logging.SensitiveTextRedaction

/** OTA 队列条目的阶段。 */
enum class OtaQueueStage {
    PRECHECK,
    TRANSFER,
    VERIFY,
    DONE,
}

/** OTA 队列条目状态。 */
enum class OtaQueueItemStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    ABORTED,
    REMOVED,
    ;

    val terminal: Boolean
        get() =
            this == SUCCEEDED ||
                this == FAILED ||
                this == SKIPPED ||
                this == ABORTED ||
                this == REMOVED
}

/** 队列中的一次升级任务；同一设备在队列中只允许出现一次。 */
data class OtaQueueTarget(
    val deviceId: String,
    val displayName: String,
    val firmwareId: String,
    val firmwareLabel: String,
)

data class OtaQueueItem(
    val target: OtaQueueTarget,
    val status: OtaQueueItemStatus = OtaQueueItemStatus.QUEUED,
    val stage: OtaQueueStage = OtaQueueStage.PRECHECK,
    val progress: Float = 0f,
    val reason: String? = null,
) {
    /** 失败/暂停/取消时是否保留了断点，供界面与汇总说明。 */
    val checkpointRetained: Boolean get() = reason?.startsWith(CHECKPOINT_PREFIX) == true

    val userReason: String? get() = reason?.removePrefix(CHECKPOINT_PREFIX)?.takeIf { it.isNotBlank() }

    companion object {
        const val CHECKPOINT_PREFIX = "[checkpoint]"
    }
}

/** OTA 队列终态汇总。 */
data class OtaQueueSummary(
    val items: List<OtaQueueItem>,
) {
    val total: Int get() = items.size

    val succeeded: Int get() = items.count { it.status == OtaQueueItemStatus.SUCCEEDED }

    val failed: Int get() = items.count { it.status == OtaQueueItemStatus.FAILED }

    val skipped: Int get() = items.count { it.status == OtaQueueItemStatus.SKIPPED }

    val aborted: Int get() = items.count { it.status == OtaQueueItemStatus.ABORTED }

    val removed: Int get() = items.count { it.status == OtaQueueItemStatus.REMOVED }

    val pending: Int get() = items.count { !it.status.terminal }

    val finished: Boolean get() = items.all { it.status.terminal }

    val checkpointRetained: Int get() = items.count { it.status.terminal && it.checkpointRetained }

    /** 供剪贴板/导出使用的稳定文本；设备标识只保留不可逆哈希。 */
    fun toText(): String =
        buildString {
            appendLine("Valhalla ota queue summary")
            appendLine(
                "total=$total succeeded=$succeeded failed=$failed skipped=$skipped " +
                    "aborted=$aborted removed=$removed",
            )
            items.forEach { item ->
                append("- ")
                append(SensitiveTextRedaction.redactInline(item.target.displayName))
                append(' ')
                append(PacketLogRedaction.hashDeviceId(item.target.deviceId))
                append(' ')
                append(item.status.name)
                append(" stage=")
                append(item.stage.name)
                append(" progress=")
                append(((item.progress.coerceIn(0f, 1f)) * 100).toInt())
                append('%')
                if (item.checkpointRetained) append(" checkpoint=retained")
                item.userReason?.let {
                    append(" reason=")
                    append(SensitiveTextRedaction.redactInline(it))
                }
                append('\n')
            }
        }
}

/**
 * 纯内存 OTA 队列状态机。
 *
 * 队列只保存运行期状态；断点由既有 OtaCheckpointStore 在升级过程中单独持久化，
 * 因此队列本身没有任何「跨进程续跑」入口：App 退出后新实例必然为空。
 */
class OtaQueue {
    private val mutableItems = mutableListOf<OtaQueueItem>()
    private var pauseFlag = false

    val items: List<OtaQueueItem> get() = mutableItems.toList()

    val summary: OtaQueueSummary get() = OtaQueueSummary(items)

    val pauseRequested: Boolean get() = pauseFlag

    /** 入队；同一设备已存在时返回 false。 */
    fun enqueue(target: OtaQueueTarget): Boolean {
        if (mutableItems.any { it.target.deviceId == target.deviceId }) return false
        mutableItems += OtaQueueItem(target = target)
        return true
    }

    /** 只允许移除尚未开始的条目。 */
    fun removeQueued(deviceId: String): Boolean {
        val index =
            mutableItems.indexOfFirst {
                it.target.deviceId == deviceId && it.status == OtaQueueItemStatus.QUEUED
            }
        if (index < 0) return false
        mutableItems.removeAt(index)
        return true
    }

    fun markRunning(deviceId: String): Boolean =
        update(deviceId) {
            if (it.status != OtaQueueItemStatus.QUEUED) return@update null
            it.copy(
                status = OtaQueueItemStatus.RUNNING,
                stage = OtaQueueStage.PRECHECK,
                progress = 0f,
                reason = null,
            )
        }

    fun updateProgress(
        deviceId: String,
        stage: OtaQueueStage,
        progress: Float,
    ): Boolean =
        update(deviceId) { item ->
            if (item.status != OtaQueueItemStatus.RUNNING) return@update null
            item.copy(stage = stage, progress = progress.coerceIn(0f, 1f))
        }

    fun markSucceeded(deviceId: String): Boolean =
        update(deviceId) { item ->
            if (item.status != OtaQueueItemStatus.RUNNING) return@update null
            item.copy(
                status = OtaQueueItemStatus.SUCCEEDED,
                stage = OtaQueueStage.DONE,
                progress = 1f,
                reason = null,
            )
        }

    fun markFailed(
        deviceId: String,
        reason: String,
        checkpointRetained: Boolean,
    ): Boolean =
        update(deviceId) { item ->
            if (item.status != OtaQueueItemStatus.RUNNING) return@update null
            item.copy(
                status = OtaQueueItemStatus.FAILED,
                reason = reason.encode(checkpointRetained),
            )
        }

    fun markSkipped(
        deviceId: String,
        reason: String,
    ): Boolean =
        update(deviceId) { item ->
            if (item.status != OtaQueueItemStatus.RUNNING) return@update null
            item.copy(status = OtaQueueItemStatus.SKIPPED, reason = reason)
        }

    fun markAborted(
        deviceId: String,
        reason: String,
        checkpointRetained: Boolean,
    ): Boolean =
        update(deviceId) { item ->
            if (item.status != OtaQueueItemStatus.RUNNING) return@update null
            item.copy(
                status = OtaQueueItemStatus.ABORTED,
                reason = reason.encode(checkpointRetained),
            )
        }

    /** 暂停只在当前分片完成后的安全点生效；由执行器确认后调用。 */
    fun markPausedAtSafePoint(
        deviceId: String,
        progress: Float,
        checkpointRetained: Boolean,
    ): Boolean =
        update(deviceId) { item ->
            if (item.status != OtaQueueItemStatus.RUNNING) return@update null
            item.copy(
                status = OtaQueueItemStatus.PAUSED,
                progress = progress.coerceIn(0f, 1f),
                reason = "queue.paused".encode(checkpointRetained),
            )
        }

    /** 失败设备保留断点；用户点「跳过失败设备」后计为跳过，但不断言断点被清除。 */
    fun skipFailed(deviceId: String): Boolean =
        update(deviceId) { item ->
            if (item.status != OtaQueueItemStatus.FAILED) return@update null
            item.copy(status = OtaQueueItemStatus.SKIPPED)
        }

    /** 恢复被暂停的条目，使其重新进入可执行队列。 */
    fun resumePaused(): Boolean {
        var changed = false
        for (index in mutableItems.indices) {
            val item = mutableItems[index]
            if (item.status == OtaQueueItemStatus.PAUSED) {
                mutableItems[index] = item.copy(status = OtaQueueItemStatus.QUEUED, reason = null)
                changed = true
            }
        }
        pauseFlag = false
        return changed
    }

    fun requestPause() {
        pauseFlag = true
    }

    fun clearPauseRequest() {
        pauseFlag = false
    }

    /** 下一条可执行条目：只取 QUEUED，PAUSED 必须先显式恢复。 */
    fun nextQueued(): OtaQueueItem? = mutableItems.firstOrNull { it.status == OtaQueueItemStatus.QUEUED }

    fun byDevice(deviceId: String): OtaQueueItem? = mutableItems.firstOrNull { it.target.deviceId == deviceId }

    private fun update(
        deviceId: String,
        transform: (OtaQueueItem) -> OtaQueueItem?,
    ): Boolean {
        val index = mutableItems.indexOfFirst { it.target.deviceId == deviceId }
        if (index < 0) return false
        val updated = transform(mutableItems[index]) ?: return false
        mutableItems[index] = updated
        return true
    }

    private fun String.encode(checkpointRetained: Boolean): String = if (checkpointRetained) OtaQueueItem.CHECKPOINT_PREFIX + this else this
}
