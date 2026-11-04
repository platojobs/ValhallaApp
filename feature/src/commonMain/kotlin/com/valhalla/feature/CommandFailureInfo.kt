package com.valhalla.feature

import com.valhalla.core.command.CommandFailure
import com.valhalla.core.protocol.message.DeviceStatus

/** 把调度器抛出的结构化失败翻译成 UI 可读文案，同时保留错误码用于状态展示。 */
internal data class FailureInfo(
    val message: String,
    val status: DeviceStatus? = null,
)

internal fun Throwable.toFailureInfo(): FailureInfo = when (this) {
    is CommandFailure.DeviceRejected -> {
        val detail = detail?.takeIf { it.isNotBlank() }
        FailureInfo(
            message = status.describe() + if (detail == null) "" else "（$detail）",
            status = status,
        )
    }

    is CommandFailure.Timeout -> FailureInfo("设备无响应（$attempts 次尝试后超时）")
    is CommandFailure.TransportFailure -> FailureInfo("传输失败：${cause?.message ?: "链路异常"}")
    is CommandFailure.InvalidResponse -> FailureInfo("应答解析失败：$reason")
    else -> FailureInfo(message ?: "未知错误")
}
