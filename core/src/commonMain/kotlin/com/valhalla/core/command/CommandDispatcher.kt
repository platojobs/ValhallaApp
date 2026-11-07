package com.valhalla.core.command

import com.valhalla.core.logging.DeviceLogger
import com.valhalla.core.logging.LogLevel
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 指令调度器：统一分配序号、执行超时控制、失败重试与响应校验。
 *
 * 同一 dispatcher 上的指令串行执行（BLE 写入本身是串行资源），
 * 不同设备应各自持有独立的 dispatcher。
 */
class CommandDispatcher(
    private val transport: CommandTransport,
    private val defaultPolicy: RetryPolicy = RetryPolicy(),
    private val logger: DeviceLogger? = null,
) {
    private val mutex = Mutex()
    private var nextSequenceValue: Int = 0

    suspend fun execute(
        type: CommandType,
        payload: ByteArray = ByteArray(0),
        policy: RetryPolicy = defaultPolicy,
    ): Frame =
        mutex.withLock {
            val frame = Frame(type = type, sequence = takeSequence(), payload = payload)
            var lastTransportError: Throwable? = null
            var lastInvalidResponseReason: String? = null

            for (attempt in 1..policy.maxAttempts) {
                var transportError: Throwable? = null
                val response =
                    withTimeoutOrNull(policy.timeoutMillis) {
                        try {
                            transport.send(frame)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            transportError = error
                            null
                        }
                    }

                when {
                    response != null && response.isResponse && response.sequence == frame.sequence -> {
                        logger?.log(
                            LogLevel.DEBUG,
                            TAG,
                            "指令 ${type.name} 第 $attempt 次尝试成功，seq=${frame.sequence}",
                        )
                        return@withLock response
                    }

                    response != null -> {
                        lastInvalidResponseReason =
                            "seq 不匹配或缺少响应标记（期望 ${frame.sequence}/response，实际 ${response.sequence}/response=${response.isResponse}）"
                        logger?.log(LogLevel.WARN, TAG, "指令 ${type.name} 收到非法响应: $lastInvalidResponseReason")
                    }

                    transportError != null -> {
                        lastTransportError = transportError
                        logger?.log(
                            LogLevel.WARN,
                            TAG,
                            "指令 ${type.name} 第 $attempt 次传输异常: ${transportError.message}",
                        )
                    }

                    else -> {
                        logger?.log(
                            LogLevel.WARN,
                            TAG,
                            "指令 ${type.name} 第 $attempt 次超时（${policy.timeoutMillis}ms）",
                        )
                    }
                }

                if (attempt < policy.maxAttempts) {
                    delay(policy.delayForAttempt(attempt))
                }
            }

            logger?.log(LogLevel.ERROR, TAG, "指令 ${type.name} 在 ${policy.maxAttempts} 次尝试后失败")
            val transportFailure = lastTransportError
            val invalidResponse = lastInvalidResponseReason
            throw when {
                transportFailure != null -> CommandFailure.TransportFailure(policy.maxAttempts, transportFailure)
                invalidResponse != null -> CommandFailure.InvalidResponse(invalidResponse)
                else -> CommandFailure.Timeout(policy.maxAttempts)
            }
        }

    private fun takeSequence(): Int {
        val current = nextSequenceValue
        nextSequenceValue = (nextSequenceValue + 1) and Frame.MAX_SEQUENCE
        return current
    }

    private companion object {
        const val TAG = "CommandDispatcher"
    }
}
