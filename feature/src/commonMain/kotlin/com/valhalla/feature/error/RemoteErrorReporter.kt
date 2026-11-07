package com.valhalla.feature.error

import com.valhalla.core.logging.SensitiveTextRedaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 已脱敏的远端错误载荷。
 *
 * 字段均为字符串，方便直接映射到任意远端 SDK（Crashlytics、Sentry、自建上报）。
 * 构造只能经 [RemoteErrorDispatcher]，保证所有字段先过脱敏规则。
 */
data class RemoteErrorPayload(
    val timestampMillis: Long,
    val kind: String,
    val origin: String,
    val type: String,
    val message: String,
    val stackTrace: String,
)

/**
 * 远端错误上报通道（可插拔）。
 *
 * 约定：
 * - **批量**：[report] 一次接收一批载荷，实现方应自行处理网络重试；
 * - **异步**：调用方通过 [RemoteErrorDispatcher] 在协程中投递，不阻塞 UI；
 * - **可关闭**：[close] 释放底层资源，重复调用应幂等。
 *
 * 当前项目**未接入任何真实远端 SDK**，默认使用 [NoopRemoteErrorReporter]。
 * 接入时只需替换实现并在应用入口注入（见 `docs/architecture.md`）。
 */
interface RemoteErrorReporter {
    /** 是否启用；false 时调度器直接丢弃，不产生网络请求。 */
    val enabled: Boolean get() = true

    suspend fun report(batch: List<RemoteErrorPayload>)

    fun close()
}

/** 默认空实现：不做任何网络请求，也不保留数据。 */
object NoopRemoteErrorReporter : RemoteErrorReporter {
    override val enabled: Boolean = false

    override suspend fun report(batch: List<RemoteErrorPayload>) = Unit

    override fun close() = Unit
}

/**
 * 错误上报调度器：把 [AppError] 转成脱敏载荷，按批异步投递。
 *
 * 线程模型：内部使用 [Channel]（无界队列）+ 单个消费协程；[submit] 可从任意线程调用。
 * 批次策略：攒够 [batchSize] 立即发送；不足时等待 [flushIntervalMillis] 后发送，
 * 避免错误风暴把网络打满。关闭时先停止接收，再把队列剩余条目作为最后一批投递。
 */
class RemoteErrorDispatcher(
    private val reporter: RemoteErrorReporter,
    scope: CoroutineScope,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val flushIntervalMillis: Long = DEFAULT_FLUSH_INTERVAL_MILLIS,
) {
    init {
        require(batchSize > 0) { "batchSize 必须大于 0" }
        require(flushIntervalMillis >= 0) { "flushIntervalMillis 不能为负" }
    }

    private val queue = Channel<AppError>(Channel.UNLIMITED)
    private var closed = false

    private val job: Job =
        scope.launch {
            val buffer = mutableListOf<AppError>()
            while (isActive) {
                val first = queue.receiveCatching().getOrNull() ?: break
                buffer.add(first)
                while (buffer.size < batchSize) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    buffer.add(next)
                }
                if (buffer.size < batchSize && flushIntervalMillis > 0) {
                    delay(flushIntervalMillis)
                    while (buffer.size < batchSize) {
                        val next = queue.tryReceive().getOrNull() ?: break
                        buffer.add(next)
                    }
                }
                flush(buffer)
                buffer.clear()
            }
            // 关闭后把剩余条目全部投递，保证不丢最后一批。
            while (true) {
                val next = queue.tryReceive().getOrNull() ?: break
                buffer.add(next)
                if (buffer.size >= batchSize) {
                    flush(buffer)
                    buffer.clear()
                }
            }
            flush(buffer)
            reporter.close()
        }

    /** 提交一条错误；上报关闭后静默丢弃，不影响本地环形记录。 */
    fun submit(error: AppError) {
        if (closed || !reporter.enabled) return
        queue.trySend(error)
    }

    /** 停止接收并异步清空队列；可重复调用。 */
    fun close() {
        if (closed) return
        closed = true
        queue.close()
    }

    /** 等待消费协程退出（测试与优雅停机用）。 */
    suspend fun awaitCompletion() {
        job.join()
    }

    private suspend fun flush(buffer: List<AppError>) {
        if (buffer.isEmpty()) return
        runCatching { reporter.report(buffer.map(::toPayload)) }
    }

    private fun toPayload(error: AppError): RemoteErrorPayload =
        RemoteErrorPayload(
            timestampMillis = error.timestampMillis,
            kind = error.kind.name,
            origin = SensitiveTextRedaction.redactInline(error.origin),
            type = SensitiveTextRedaction.redactInline(error.type),
            message = SensitiveTextRedaction.redactInline(error.message),
            stackTrace = SensitiveTextRedaction.redact(error.stackTrace),
        )

    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 20
        const val DEFAULT_FLUSH_INTERVAL_MILLIS: Long = 500
    }
}
