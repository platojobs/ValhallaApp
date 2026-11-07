package com.valhalla.core.protocol.message

import com.valhalla.core.protocol.CommandType

/**
 * 强类型请求模型。
 *
 * 子类只负责业务字段的编码，[encode] 统一把字段写入 payload。
 * 对应指令的应答模型见 [MessageCodec.decodeResponse]。
 */
sealed class DeviceRequest {
    abstract val command: CommandType

    fun encode(): ByteArray {
        val writer = PayloadWriter()
        encodeBody(writer)
        return writer.toByteArray()
    }

    protected abstract fun encodeBody(writer: PayloadWriter)
}

/**
 * 强类型应答模型。payload 第 0 字节固定为状态码，其后为各指令自定义的应答字段。
 */
sealed class DeviceResponse {
    abstract val command: CommandType

    abstract val status: DeviceStatus

    val isSuccess: Boolean get() = status.isSuccess

    fun encode(): ByteArray {
        val writer = PayloadWriter()
        writer.byte(status.code)
        encodeBody(writer)
        return writer.toByteArray()
    }

    protected abstract fun encodeBody(writer: PayloadWriter)
}

/**
 * 失败应答。
 *
 * 协议约定：状态码非 0 时，状态码之后的字节是可选的 UTF-8 错误详情；
 * 上层可据此直接展示可读原因，而不用关心具体指令的字段布局。
 */
class ErrorResponse(
    override val command: CommandType,
    override val status: DeviceStatus,
    val detail: String? = null,
) : DeviceResponse() {
    override fun encodeBody(writer: PayloadWriter) {
        if (!detail.isNullOrEmpty()) {
            writer.bytes(detail.encodeToByteArray())
        }
    }

    override fun toString(): String = "ErrorResponse(command=$command, status=${status.describe()}, detail=$detail)"
}

/**
 * 设备主动通知帧。通知没有状态码字节，payload 全部为业务字段。
 */
sealed class DeviceNotification {
    abstract val command: CommandType

    fun encode(): ByteArray {
        val writer = PayloadWriter()
        encodeBody(writer)
        return writer.toByteArray()
    }

    protected abstract fun encodeBody(writer: PayloadWriter)
}
