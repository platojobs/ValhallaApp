package com.valhalla.feature

import com.valhalla.core.command.CommandFailure
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage

/**
 * 把调度器抛出的结构化失败翻译成 UI 可读文案，同时保留错误码用于状态展示。
 *
 * [message] 供日志/诊断使用（协议层原始描述），[ui] 供界面展示（可随语言切换）。
 */
internal data class FailureInfo(
    val message: String,
    val ui: UiMessage,
    /** 仅含状态码/设备详情，供外层已表达「拒绝」语义的文案复用。 */
    val statusUi: UiMessage? = null,
    val status: DeviceStatus? = null,
)

internal fun Throwable.toFailureInfo(): FailureInfo =
    when (this) {
        is CommandFailure.DeviceRejected -> {
            val detail = detail?.takeIf { it.isNotBlank() }
            val hex =
                "0x" +
                    status.code
                        .toString(16)
                        .uppercase()
                        .padStart(2, '0')
            val statusUi =
                if (detail == null) {
                    UiMessage.of(StringKey.FAILURE_STATUS, "code" to hex)
                } else {
                    UiMessage.of(StringKey.FAILURE_STATUS_DETAIL, "code" to hex, "detail" to detail)
                }
            FailureInfo(
                message = status.describe() + if (detail == null) "" else "（$detail）",
                ui =
                    UiMessage(
                        StringKey.FAILURE_DEVICE_REJECTED,
                        nested = mapOf("status" to statusUi),
                    ),
                statusUi = statusUi,
                status = status,
            )
        }

        is CommandFailure.Timeout ->
            FailureInfo(
                message = "设备无响应（$attempts 次尝试后超时）",
                ui = UiMessage.of(StringKey.FAILURE_TIMEOUT, "attempts" to attempts.toString()),
            )

        is CommandFailure.TransportFailure ->
            FailureInfo(
                message = "传输失败：${cause?.message ?: "链路异常"}",
                ui =
                    UiMessage.of(
                        StringKey.FAILURE_TRANSPORT,
                        "detail" to (cause?.message ?: StringKey.UNKNOWN.name),
                    ),
            )

        is CommandFailure.InvalidResponse ->
            FailureInfo(
                message = "应答解析失败：$reason",
                ui = UiMessage.of(StringKey.FAILURE_INVALID_RESPONSE, "detail" to reason),
            )

        else ->
            FailureInfo(
                message = message ?: "未知错误",
                ui =
                    UiMessage.of(
                        StringKey.FAILURE_UNKNOWN,
                        "detail" to (message ?: StringKey.UNKNOWN.name),
                    ),
            )
    }
