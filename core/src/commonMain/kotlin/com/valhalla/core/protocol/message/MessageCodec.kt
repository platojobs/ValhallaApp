package com.valhalla.core.protocol.message

import com.valhalla.core.logging.LogLevel
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame

/**
 * 协议 payload 编解码入口。
 *
 * 设计目标：
 * - 请求侧：[decodeRequest] 让模拟设备/测试桩可以按协议字典解析请求；
 * - 应答侧：[decodeResponse] 先读状态码，状态非 0 时返回 [ErrorResponse]，
 *   状态为 0 时再按指令类型解析业务字段；
 * - 通知侧：[decodeNotification] 解析设备主动上报。
 *
 * 所有解码器都会校验 payload 是否被完整消费，字段布局与协议字典不一致时抛
 * [MessageDecodeException]，而不是静默截断。
 */
object MessageCodec {
    // ---------------------------------------------------------------------
    // 请求编码 / 解码
    // ---------------------------------------------------------------------

    fun encodeRequest(request: DeviceRequest): ByteArray = request.encode()

    fun decodeRequest(
        command: CommandType,
        payload: ByteArray,
    ): DeviceRequest {
        val reader = PayloadReader(payload)
        val request =
            when (command) {
                CommandType.AUTH ->
                    AuthRequest(
                        mode =
                            AuthMode.fromCode(reader.byte())
                                ?: throw MessageDecodeException("未知鉴权模式"),
                        credential = reader.bytes(reader.u16()),
                    )

                CommandType.GET_DEVICE_INFO -> GetDeviceInfoRequest()
                CommandType.GET_BATTERY -> GetBatteryRequest()
                CommandType.GET_FIRMWARE_VERSION -> GetFirmwareVersionRequest()
                CommandType.GET_TIME -> GetTimeRequest()

                CommandType.SET_CONFIG ->
                    SetConfigRequest(
                        key = reader.string1(),
                        value = reader.bytes(reader.u16()),
                    )

                CommandType.GET_CONFIG -> GetConfigRequest(reader.string1())

                CommandType.SET_TIME ->
                    SetTimeRequest(
                        epochSeconds = reader.u32(),
                        timezoneOffsetMinutes = reader.i16(),
                    )

                CommandType.SET_PARAM ->
                    SetParamRequest(
                        paramId = reader.u16(),
                        value = reader.bytes(reader.u16()),
                    )

                CommandType.GET_PARAM -> GetParamRequest(reader.u16())

                CommandType.FACTORY_RESET -> FactoryResetRequest(reader.string1())

                CommandType.START_OTA -> StartOtaRequest.decode(reader)

                CommandType.OTA_DATA ->
                    OtaDataRequest(
                        offset = reader.u32(),
                        data = reader.bytes(reader.u16()),
                    )

                CommandType.FINISH_OTA ->
                    FinishOtaRequest(
                        totalBytes = reader.u32(),
                        crc16 = reader.u16(),
                    )

                CommandType.ABORT_OTA ->
                    AbortOtaRequest(
                        OtaAbortReason.fromCode(reader.byte())
                            ?: throw MessageDecodeException("未知 OTA 中止原因"),
                    )

                CommandType.REBOOT ->
                    RebootRequest(
                        delayMillis = reader.u16(),
                        reason = reader.byte(),
                    )

                CommandType.LOG_DUMP ->
                    LogDumpRequest(
                        sinceSequence = reader.u32(),
                        maxEntries = reader.u16(),
                    )

                CommandType.HEARTBEAT -> HeartbeatRequest(reader.u32())

                else -> throw MessageDecodeException("指令 ${command.name} 没有请求模型")
            }
        reader.ensureExhausted()
        return request
    }

    // ---------------------------------------------------------------------
    // 应答编码 / 解码
    // ---------------------------------------------------------------------

    fun encodeResponse(response: DeviceResponse): ByteArray = response.encode()

    /**
     * 按指令类型解析应答 payload。
     *
     * 状态码非 0 时返回 [ErrorResponse]，detail 为状态码之后剩余字节的 UTF-8 文本。
     */
    fun decodeResponse(
        command: CommandType,
        payload: ByteArray,
    ): DeviceResponse {
        val reader = PayloadReader(payload)
        val status = DeviceStatus.fromCode(reader.byte())
        if (!status.isSuccess) {
            val detail =
                if (reader.remaining > 0) {
                    reader.bytes(reader.remaining).decodeToString()
                } else {
                    null
                }
            return ErrorResponse(command, status, detail)
        }

        val response =
            when (command) {
                CommandType.AUTH ->
                    AuthResponse(
                        status = status,
                        token = reader.string2(),
                        expiresInSeconds = reader.u32(),
                        challenge = reader.bytes(reader.u16()),
                    )

                CommandType.GET_DEVICE_INFO ->
                    DeviceInfoResponse(
                        status = status,
                        vendor = reader.string1(),
                        model = reader.string1(),
                        serialNumber = reader.string1(),
                        hardwareVersion = reader.string1(),
                        firmwareVersion = FirmwareVersion.decode(reader),
                    )

                CommandType.SET_CONFIG -> SetConfigResponse(status)
                CommandType.SET_PARAM -> SetParamResponse(status)
                CommandType.FACTORY_RESET -> FactoryResetResponse(status)
                CommandType.REBOOT -> RebootResponse(status)
                CommandType.ABORT_OTA -> AbortOtaResponse(status)

                CommandType.GET_CONFIG ->
                    ConfigResponse(
                        status = status,
                        key = reader.string1(),
                        value = reader.bytes(reader.u16()),
                    )

                CommandType.GET_BATTERY ->
                    BatteryResponse(
                        status = status,
                        percent = reader.byte(),
                        charging = readBoolean(reader, "充电状态"),
                        voltageMillivolts = reader.u16(),
                    )

                CommandType.GET_FIRMWARE_VERSION ->
                    FirmwareVersionResponse(
                        status = status,
                        version = FirmwareVersion.decode(reader),
                    )

                CommandType.SET_TIME ->
                    SetTimeResponse(
                        status = status,
                        appliedEpochSeconds = reader.u32(),
                    )

                CommandType.GET_TIME ->
                    TimeResponse(
                        status = status,
                        epochSeconds = reader.u32(),
                        timezoneOffsetMinutes = reader.i16(),
                    )

                CommandType.GET_PARAM ->
                    ParamResponse(
                        status = status,
                        paramId = reader.u16(),
                        value = reader.bytes(reader.u16()),
                    )

                CommandType.START_OTA -> StartOtaResponse.decode(status, reader)

                CommandType.OTA_DATA ->
                    OtaDataResponse(
                        status = status,
                        receivedBytes = reader.u32(),
                    )

                CommandType.FINISH_OTA ->
                    FinishOtaResponse(
                        status = status,
                        rebootRequired = readBoolean(reader, "重启标记"),
                    )

                CommandType.LOG_DUMP ->
                    LogDumpResponse(
                        status = status,
                        entries = readLogEntries(reader),
                    )

                CommandType.HEARTBEAT ->
                    HeartbeatResponse(
                        status = status,
                        uptimeSeconds = reader.u32(),
                        batteryPercent = reader.byte(),
                    )

                else -> throw MessageDecodeException("指令 ${command.name} 没有应答模型")
            }
        reader.ensureExhausted()
        return response
    }

    fun decodeResponse(
        request: DeviceRequest,
        frame: Frame,
    ): DeviceResponse {
        requireResponseFrame(frame)
        if (frame.type != request.command) {
            throw MessageDecodeException(
                "应答指令不匹配: 期望 ${request.command.name}，实际 ${frame.type.name}",
            )
        }
        return decodeResponse(request.command, frame.payload)
    }

    // ---------------------------------------------------------------------
    // 通知解码
    // ---------------------------------------------------------------------

    fun decodeNotification(frame: Frame): DeviceNotification {
        if (!frame.isNotification) {
            throw MessageDecodeException("帧未标记为通知: ${frame.type.name}")
        }
        val reader = PayloadReader(frame.payload)
        val notification =
            when (frame.type) {
                CommandType.HEARTBEAT_REPLY ->
                    HeartbeatReplyNotification(
                        batteryPercent = reader.byte(),
                        uptimeSeconds = reader.u32(),
                    )

                CommandType.DEVICE_STATUS_NOTIFY ->
                    DeviceStatusNotification(
                        deviceState =
                            DeviceStatusNotification.deviceStateFromCode(reader.byte())
                                ?: throw MessageDecodeException("未知设备状态"),
                        batteryPercent = reader.byte(),
                        faultCode = reader.u16(),
                    )

                CommandType.OTA_PROGRESS_NOTIFY ->
                    OtaProgressNotification(
                        sessionId = reader.u32(),
                        receivedBytes = reader.u32(),
                        totalBytes = reader.u32(),
                        stage =
                            OtaStage.fromCode(reader.byte())
                                ?: throw MessageDecodeException("未知 OTA 阶段"),
                    )

                else -> throw MessageDecodeException("指令 ${frame.type.name} 不是通知类型")
            }
        reader.ensureExhausted()
        return notification
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    private fun requireResponseFrame(frame: Frame) {
        if (!frame.isResponse) {
            throw MessageDecodeException("帧未标记为应答: ${frame.type.name}")
        }
    }

    private fun readBoolean(
        reader: PayloadReader,
        field: String,
    ): Boolean =
        when (val value = reader.byte()) {
            0 -> false
            1 -> true
            else -> throw MessageDecodeException("$field 取值非法: $value（仅允许 0/1）")
        }

    private fun readLogEntries(reader: PayloadReader): List<DeviceLogEntry> {
        val count = reader.u16()
        return List(count) {
            val priority = reader.byte()
            val level =
                LogLevel.entries.firstOrNull { it.priority == priority }
                    ?: throw MessageDecodeException("未知日志等级: $priority")
            DeviceLogEntry(
                level = level,
                timestampSeconds = reader.u32(),
                message = reader.string2(),
            )
        }
    }
}
