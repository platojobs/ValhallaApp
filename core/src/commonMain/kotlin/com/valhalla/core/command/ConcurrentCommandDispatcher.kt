package com.valhalla.core.command

import com.valhalla.core.logging.DeviceLogger
import com.valhalla.core.logging.LogLevel
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.message.DeviceNotification
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.MessageCodec
import com.valhalla.core.protocol.message.MessageDecodeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 并发请求-应答调度器。
 *
 * 与 [CommandDispatcher] 的区别：
 * - 所有在途请求共享一条上行帧流，按 sequence 精确配对，因此多个请求可以并发发起；
 * - 序号在发出前分配并注册等待者，重试沿用同一序号，避免设备把重试当成新请求；
 * - 乱序应答按 sequence 路由到各自的等待者；
 * - 重复应答、迟到应答、无主应答、指令类型不匹配的应答都会被丢弃并记录日志；
 * - 通知帧不会误配给业务请求，而是通过 [notifications] 单独广播。
 *
 * 失败统一以 [CommandFailure] 的结构化子类抛出：
 * [CommandFailure.Timeout]、[CommandFailure.TransportFailure]、
 * [CommandFailure.InvalidResponse]、[CommandFailure.DeviceRejected]。
 *
 * 稳定性约束：
 * - 上行收集协程带有 [CoroutineExceptionHandler]；收集流意外异常只会记录日志并结束该协程，
 *   不会沿默认 Handler 崩溃 App，下一次 [execute] 会检测到 job 已结束并重建收集器；
 * - 收集器死亡瞬间的在途请求无人应答，按各自超时收敛为 [CommandFailure.Timeout]，
 *   不会永久挂起。
 */
class ConcurrentCommandDispatcher(
    private val link: FrameLink,
    private val defaultPolicy: RetryPolicy = RetryPolicy(),
    private val logger: DeviceLogger? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private class PendingRequest(
        val command: CommandType,
        val deferred: CompletableDeferred<Frame> = CompletableDeferred(),
    )

    private val stateMutex = Mutex()
    private val startMutex = Mutex()
    private val pending = mutableMapOf<Int, PendingRequest>()
    private var nextSequenceValue: Int = 0
    private var collectorJob: Job? = null

    private val notificationSink = MutableSharedFlow<DeviceNotification>(extraBufferCapacity = 64)

    /**
     * 收集协程的异常边界：把原始异常类型与消息写入日志后结束该协程。
     * 不使用默认 uncaught handler，避免 Android 主线程默认行为直接崩溃进程。
     */
    private val collectorExceptionHandler = CoroutineExceptionHandler { _, error ->
        if (error !is CancellationException) {
            logger?.log(
                LogLevel.ERROR,
                TAG,
                "上行收集协程异常终止（${error::class.simpleName}）：${error.message}；" +
                    "将在下一次请求时重建收集协程",
            )
        }
    }

    /** 设备主动通知流。解码失败的通知会被丢弃并记录日志，不打断业务请求。 */
    val notifications: SharedFlow<DeviceNotification> = notificationSink.asSharedFlow()

    /** 发送强类型请求并解析为强类型应答；状态码非 0 时抛出 [CommandFailure.DeviceRejected]。 */
    suspend fun execute(
        request: DeviceRequest,
        policy: RetryPolicy = defaultPolicy,
    ): DeviceResponse {
        val frame = executeRaw(request.command, request.encode(), policy)
        val response = try {
            MessageCodec.decodeResponse(request, frame)
        } catch (error: MessageDecodeException) {
            throw CommandFailure.InvalidResponse(error.message ?: "payload 解析失败")
        }
        if (!response.isSuccess) {
            throw CommandFailure.DeviceRejected(
                command = request.command,
                status = response.status,
                detail = (response as? ErrorResponse)?.detail,
            )
        }
        return response
    }

    /** 发送原始帧请求，返回序号与指令类型均匹配的应答帧。 */
    suspend fun executeRaw(
        type: CommandType,
        payload: ByteArray = ByteArray(0),
        policy: RetryPolicy = defaultPolicy,
    ): Frame {
        ensureCollectorStarted()

        val sequence = allocateSequence()
        val pendingRequest = PendingRequest(type)
        stateMutex.withLock { pending[sequence] = pendingRequest }

        val frame = Frame(type = type, sequence = sequence, payload = payload)
        var lastTransportError: Throwable? = null

        try {
            for (attempt in 1..policy.maxAttempts) {
                var transportError: Throwable? = null
                try {
                    link.send(frame)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    transportError = error
                    lastTransportError = error
                    logger?.log(
                        LogLevel.WARN,
                        TAG,
                        "指令 ${type.name} 第 $attempt 次发送异常: ${error.message}",
                    )
                }

                if (transportError == null) {
                    val response = withTimeoutOrNull(policy.timeoutMillis) {
                        pendingRequest.deferred.await()
                    }
                    if (response != null) {
                        logger?.log(
                            LogLevel.DEBUG,
                            TAG,
                            "指令 ${type.name} 第 $attempt 次尝试成功，seq=$sequence",
                        )
                        return response
                    }
                    logger?.log(
                        LogLevel.WARN,
                        TAG,
                        "指令 ${type.name} 第 $attempt 次超时（${policy.timeoutMillis}ms，seq=$sequence）",
                    )
                }

                if (attempt < policy.maxAttempts) {
                    delay(policy.delayForAttempt(attempt))
                }
            }

            logger?.log(LogLevel.ERROR, TAG, "指令 ${type.name} 在 ${policy.maxAttempts} 次尝试后失败")
            throw when (val transportFailure = lastTransportError) {
                null -> CommandFailure.Timeout(policy.maxAttempts)
                else -> CommandFailure.TransportFailure(policy.maxAttempts, transportFailure)
            }
        } finally {
            stateMutex.withLock { pending.remove(sequence) }
        }
    }

    /** 取消上行收集并终结所有在途请求。 */
    suspend fun close() {
        val toCancel = stateMutex.withLock {
            collectorJob?.cancel()
            collectorJob = null
            val values = pending.values.toList()
            pending.clear()
            values
        }
        toCancel.forEach { it.deferred.cancel() }
    }

    private suspend fun ensureCollectorStarted() {
        startMutex.withLock {
            if (collectorJob?.isActive != true) {
                val previous = collectorJob
                collectorJob = scope.launch(collectorExceptionHandler) {
                    link.incoming.collect { frame -> dispatch(frame) }
                }
                if (previous != null) {
                    logger?.log(LogLevel.INFO, TAG, "已重建上行收集协程")
                }
            }
        }
    }

    private suspend fun dispatch(frame: Frame) {
        when {
            frame.isNotification -> {
                val notification = try {
                    MessageCodec.decodeNotification(frame)
                } catch (error: MessageDecodeException) {
                    logger?.log(LogLevel.WARN, TAG, "通知帧 ${frame.type.name} 解析失败: ${error.message}")
                    null
                }
                if (notification != null) {
                    notificationSink.tryEmit(notification)
                }
            }

            frame.isResponse -> {
                val target = stateMutex.withLock { pending[frame.sequence] }
                when {
                    target == null -> logger?.log(
                        LogLevel.DEBUG,
                        TAG,
                        "丢弃无主或迟到应答 seq=${frame.sequence}，type=${frame.type.name}",
                    )

                    target.command != frame.type -> logger?.log(
                        LogLevel.WARN,
                        TAG,
                        "应答指令不匹配 seq=${frame.sequence}：期望 ${target.command.name}，实际 ${frame.type.name}",
                    )

                    !target.deferred.complete(frame) -> logger?.log(
                        LogLevel.DEBUG,
                        TAG,
                        "重复应答已忽略 seq=${frame.sequence}，type=${frame.type.name}",
                    )
                }
            }

            else -> logger?.log(
                LogLevel.WARN,
                TAG,
                "丢弃非应答/非通知帧 seq=${frame.sequence}，type=${frame.type.name}",
            )
        }
    }

    private suspend fun allocateSequence(): Int = stateMutex.withLock {
        val current = nextSequenceValue
        nextSequenceValue = (nextSequenceValue + 1) and Frame.MAX_SEQUENCE
        current
    }

    private companion object {
        const val TAG = "ConcurrentCommandDispatcher"
    }
}
