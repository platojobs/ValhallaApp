package com.valhalla.core.command

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.message.DeviceStatus

/**
 * 指令传输抽象：BLE 层实现「发送一帧并等待一帧回复」，调度层只负责超时、重试与序号。
 */
interface CommandTransport {
    suspend fun send(frame: Frame): Frame
}

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val timeoutMillis: Long = 3_000,
    val initialRetryDelayMillis: Long = 100,
    val backoffMultiplier: Double = 2.0,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts 必须大于等于 1" }
        require(timeoutMillis > 0) { "timeoutMillis 必须大于 0" }
        require(initialRetryDelayMillis >= 0) { "initialRetryDelayMillis 不能为负" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier 必须大于等于 1" }
    }

    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt 从 1 开始" }
        var delay = initialRetryDelayMillis.toDouble()
        repeat(attempt - 1) { delay *= backoffMultiplier }
        return delay.toLong()
    }
}

sealed class CommandFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(val attempts: Int) :
        CommandFailure("指令在 $attempts 次尝试后全部超时")

    class TransportFailure(val attempts: Int, cause: Throwable) :
        CommandFailure("指令传输失败（已尝试 $attempts 次）: ${cause.message}", cause)

    class InvalidResponse(val reason: String) :
        CommandFailure("设备响应非法: $reason")

    /** 设备以非 0 状态码显式拒绝指令。 */
    class DeviceRejected(
        val command: CommandType,
        val status: DeviceStatus,
        val detail: String? = null,
    ) : CommandFailure(
        "设备拒绝指令 ${command.name}: ${status.describe()}" +
            if (detail.isNullOrBlank()) "" else "（$detail）"
    )
}
