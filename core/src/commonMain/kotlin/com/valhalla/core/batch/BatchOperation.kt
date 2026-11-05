package com.valhalla.core.batch

import com.valhalla.core.logging.PacketLogRedaction
import com.valhalla.core.logging.SensitiveTextRedaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 批量操作类型。 */
enum class BatchOperationKind {
    HEALTH_CHECK,
    APPLY_PRESET,
}

/** 单台设备在批量队列中的状态。 */
enum class BatchItemStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    SKIPPED,
    FAILED,
    CANCELLED,
    REMOVED,
    ;

    val terminal: Boolean
        get() = this == SUCCEEDED || this == SKIPPED || this == FAILED ||
            this == CANCELLED || this == REMOVED
}

/** 批量操作的参与者；[displayName] 只用于展示，导出时仍会脱敏。 */
data class BatchTarget(
    val deviceId: String,
    val displayName: String,
)

/** 单台设备的执行快照。 */
data class BatchItemState(
    val target: BatchTarget,
    val status: BatchItemStatus = BatchItemStatus.QUEUED,
    val step: String? = null,
    val reason: String? = null,
    val detail: String? = null,
)

/** 批量执行的终态汇总；计数只统计终态条目。 */
data class BatchSummary(
    val kind: BatchOperationKind,
    val items: List<BatchItemState>,
    val cancelRequested: Boolean = false,
) {

    val total: Int get() = items.size

    val succeeded: Int get() = items.count { it.status == BatchItemStatus.SUCCEEDED }

    val skipped: Int get() = items.count { it.status == BatchItemStatus.SKIPPED }

    val failed: Int get() = items.count { it.status == BatchItemStatus.FAILED }

    val cancelled: Int get() = items.count { it.status == BatchItemStatus.CANCELLED }

    val removed: Int get() = items.count { it.status == BatchItemStatus.REMOVED }

    val finished: Boolean get() = items.all { it.status.terminal }

    /** 供剪贴板/导出使用的稳定文本；设备标识只保留不可逆哈希。 */
    fun toText(): String = buildString {
        appendLine("Valhalla batch summary")
        appendLine("operation=${kind.name}")
        appendLine(
            "total=$total succeeded=$succeeded skipped=$skipped failed=$failed " +
                "cancelled=$cancelled removed=$removed",
        )
        items.forEach { item ->
            append("- ")
            append(SensitiveTextRedaction.redactInline(item.target.displayName))
            append(' ')
            append(PacketLogRedaction.hashDeviceId(item.target.deviceId))
            append(' ')
            append(item.status.name)
            item.step?.let {
                append(" step=")
                append(SensitiveTextRedaction.redactInline(it))
            }
            item.reason?.let {
                append(" reason=")
                append(SensitiveTextRedaction.redactInline(it))
            }
            item.detail?.let {
                append(" detail=")
                append(SensitiveTextRedaction.redactInline(it))
            }
            append('\n')
        }
    }
}

/** 一次批量锁定的结果：成功锁定的设备与已被其它操作占用的设备。 */
data class BatchLockResult(
    val locked: Set<String>,
    val busy: Set<String>,
)

/**
 * 设备写操作锁。
 *
 * 批量执行期间对参与者加锁，配置、参数、OTA、快捷操作等写入口都应查询该锁；
 * 未参与批量的设备不在集合内，因此不受影响。锁只在内存中，进程退出自动释放。
 */
class DeviceOperationLocks {

    private val mutableLocked = MutableStateFlow<Set<String>>(emptySet())
    val locked: StateFlow<Set<String>> = mutableLocked.asStateFlow()

    fun tryLock(deviceIds: Collection<String>): BatchLockResult {
        val requested = deviceIds.toSet()
        val busy = requested intersect mutableLocked.value
        val acquired = requested - busy
        if (acquired.isNotEmpty()) {
            mutableLocked.value = mutableLocked.value + acquired
        }
        return BatchLockResult(locked = acquired, busy = busy)
    }

    fun release(deviceIds: Collection<String>) {
        if (deviceIds.isEmpty()) return
        val remaining = mutableLocked.value - deviceIds.toSet()
        if (remaining != mutableLocked.value) {
            mutableLocked.value = remaining
        }
    }

    fun isLocked(deviceId: String): Boolean = mutableLocked.value.contains(deviceId)

    fun lockedIds(): Set<String> = mutableLocked.value
}
