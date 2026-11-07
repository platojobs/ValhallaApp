package com.valhalla.core.protocol.message

import com.valhalla.core.protocol.CommandType

/**
 * 写配置项。
 *
 * payload：`key:str1 | valueLen:u16 | value`
 * value 的具体格式由配置键定义，协议层只负责透传。
 */
class SetConfigRequest(
    val key: String,
    val value: ByteArray,
) : DeviceRequest() {
    override val command: CommandType = CommandType.SET_CONFIG

    override fun encodeBody(writer: PayloadWriter) {
        writer.string1(key)
        writer.u16(value.size)
        writer.bytes(value)
    }
}

/** 写配置应答，payload 只有状态码。 */
class SetConfigResponse(
    status: DeviceStatus,
) : DeviceResponse() {
    override val command: CommandType = CommandType.SET_CONFIG

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) = Unit
}

/** 读配置项，payload：`key:str1`。 */
class GetConfigRequest(
    val key: String,
) : DeviceRequest() {
    override val command: CommandType = CommandType.GET_CONFIG

    override fun encodeBody(writer: PayloadWriter) {
        writer.string1(key)
    }
}

/** 配置项应答：`key:str1 | valueLen:u16 | value`。 */
class ConfigResponse(
    status: DeviceStatus,
    val key: String,
    val value: ByteArray,
) : DeviceResponse() {
    override val command: CommandType = CommandType.GET_CONFIG

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.string1(key)
        writer.u16(value.size)
        writer.bytes(value)
    }
}

/**
 * 写参数。
 *
 * payload：`paramId:u16 | valueLen:u16 | value`
 * paramId 由设备参数表定义，协议层不维护具体含义。
 */
class SetParamRequest(
    val paramId: Int,
    val value: ByteArray,
) : DeviceRequest() {
    init {
        require(paramId in 0..0xFFFF) { "paramId 超出范围: $paramId" }
    }

    override val command: CommandType = CommandType.SET_PARAM

    override fun encodeBody(writer: PayloadWriter) {
        writer.u16(paramId)
        writer.u16(value.size)
        writer.bytes(value)
    }
}

/** 写参数应答，payload 只有状态码。 */
class SetParamResponse(
    status: DeviceStatus,
) : DeviceResponse() {
    override val command: CommandType = CommandType.SET_PARAM

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) = Unit
}

/** 读参数，payload：`paramId:u16`。 */
class GetParamRequest(
    val paramId: Int,
) : DeviceRequest() {
    init {
        require(paramId in 0..0xFFFF) { "paramId 超出范围: $paramId" }
    }

    override val command: CommandType = CommandType.GET_PARAM

    override fun encodeBody(writer: PayloadWriter) {
        writer.u16(paramId)
    }
}

/** 参数应答：`paramId:u16 | valueLen:u16 | value`。 */
class ParamResponse(
    status: DeviceStatus,
    val paramId: Int,
    val value: ByteArray,
) : DeviceResponse() {
    override val command: CommandType = CommandType.GET_PARAM

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.u16(paramId)
        writer.u16(value.size)
        writer.bytes(value)
    }
}
