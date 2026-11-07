package com.valhalla.feature.error

import androidx.compose.runtime.staticCompositionLocalOf
import com.valhalla.core.command.CommandFailure
import com.valhalla.data.ble.BleTimeoutException
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 异常分类，四类语义互不吞并。 */
enum class ErrorKind {
    /** 用户主动取消（例如点击取消按钮、OTA 中止）。 */
    USER_CANCELLED,

    /** 协程取消（作用域销毁、宿主退出等），属于正常控制流。 */
    COROUTINE_CANCELLED,

    /** 操作超时（蓝牙建链超时、指令应答超时、withTimeout 到期）。 */
    TIMEOUT,

    /** 其余真实失败。 */
    FAILURE,
}

/** 用户主动取消的显式信号，供调用方与协程取消区分。 */
class UserCancelledException(
    message: String = "用户取消",
) : CancellationException(message)

/**
 * 把任意异常归类为四类之一。
 *
 * 判定顺序很重要：用户取消与超时都继承自 [CancellationException]，
 * 若先按协程取消处理会被误吞，导致 UI 无法区分「用户取消」与「宿主销毁」。
 */
fun classifyError(error: Throwable): ErrorKind =
    when {
        error is UserCancelledException -> ErrorKind.USER_CANCELLED
        error is TimeoutCancellationException -> ErrorKind.TIMEOUT
        error is CancellationException -> ErrorKind.COROUTINE_CANCELLED
        error is BleTimeoutException -> ErrorKind.TIMEOUT
        error is CommandFailure.Timeout -> ErrorKind.TIMEOUT
        else -> ErrorKind.FAILURE
    }

/**
 * 结构化错误记录。
 *
 * [type] 保留原始异常类型（`simpleName`），[message] 保留原始消息，
 * [stackTrace] 截断到 [MAX_STACK_TRACE_CHARS] 字符，避免长栈撑大内存。
 */
data class AppError(
    val id: Long,
    val timestampMillis: Long,
    val kind: ErrorKind,
    val origin: String,
    val type: String,
    val message: String,
    val stackTrace: String,
) {
    /** 界面展示用文案：现象 + 原始类型与消息，便于用户反馈。 */
    fun toUiMessage(): UiMessage =
        UiMessage(
            StringKey.FAILURE_UNKNOWN,
            mapOf("detail" to "$type: $message".trim()),
        )
}

/**
 * 内存环形错误记录器（默认保留最近 [capacity] 条）。
 *
 * 写入与读取均为线程安全：id 用 [MutableStateFlow.updateAndGet] 原子分配，
 * 列表通过 CAS 整体替换；并发下只可能条目顺序交错，不会丢 id 或越界。
 */
@OptIn(ExperimentalTime::class)
class ErrorReporter(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** 可选的远端批量上报调度器；默认 null（仅本地环形记录）。 */
    private val remoteDispatcher: RemoteErrorDispatcher? = null,
) {
    init {
        require(capacity > 0) { "capacity 必须大于 0" }
    }

    private val mutableItems = MutableStateFlow<List<AppError>>(emptyList())
    private val counter = MutableStateFlow(0L)

    /** 最近错误，最旧在前。 */
    val items: StateFlow<List<AppError>> = mutableItems.asStateFlow()

    val latest: AppError? get() = mutableItems.value.lastOrNull()

    fun record(
        origin: String,
        error: Throwable,
        kind: ErrorKind = classifyError(error),
    ): AppError {
        val entry =
            AppError(
                id = counter.updateAndGet { it + 1 } - 1,
                timestampMillis = clockMillis(),
                kind = kind,
                origin = origin,
                type = error::class.simpleName ?: "Throwable",
                message = error.message ?: kind.name,
                stackTrace = error.stackTraceToString().take(MAX_STACK_TRACE_CHARS),
            )
        mutableItems.update { current ->
            val appended = current + entry
            if (appended.size > capacity) {
                appended.subList(appended.size - capacity, appended.size).toList()
            } else {
                appended
            }
        }
        // 本地记录先落盘（内存），再异步批量投递；投递失败不影响本地记录。
        remoteDispatcher?.submit(entry)
        return entry
    }

    fun clear() {
        mutableItems.value = emptyList()
    }

    /** 停止远端上报并异步清空队列；本地记录仍可继续使用。 */
    fun closeRemoteReporting() {
        remoteDispatcher?.close()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 50
        const val MAX_STACK_TRACE_CHARS: Int = 2_048
    }
}

/**
 * 界面层统一错误边界：协程体抛出的非取消异常会被记录到 [ErrorReporter]，
 * 而不是沿默认未捕获处理器传播（Android 上会直接崩溃进程）。
 *
 * - [TimeoutCancellationException] 记录为 [ErrorKind.TIMEOUT]，不再向上抛；
 * - [UserCancelledException] 记录为 [ErrorKind.USER_CANCELLED]，不再向上抛；
 * - 其他 [CancellationException] 原样重抛，保持结构化并发语义；
 * - 其余异常记录为 [ErrorKind.FAILURE]，由 [onError] 决定是否展示给用户。
 */
fun CoroutineScope.launchSafely(
    reporter: ErrorReporter,
    origin: String,
    onError: ((AppError) -> Unit)? = null,
    block: suspend CoroutineScope.() -> Unit,
): Job =
    launch {
        try {
            block()
        } catch (timeout: TimeoutCancellationException) {
            val error = reporter.record(origin, timeout, ErrorKind.TIMEOUT)
            onError?.invoke(error)
        } catch (cancelled: CancellationException) {
            if (cancelled is UserCancelledException) {
                val error = reporter.record(origin, cancelled, ErrorKind.USER_CANCELLED)
                onError?.invoke(error)
            } else {
                throw cancelled
            }
        } catch (error: Throwable) {
            val recorded = reporter.record(origin, error)
            onError?.invoke(recorded)
        }
    }

/** 全局错误记录器；根界面会覆盖此默认值，保证整个 UI 共用一份记录。 */
val LocalErrorReporter =
    staticCompositionLocalOf<ErrorReporter> {
        ErrorReporter()
    }
