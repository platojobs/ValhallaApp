package com.valhalla.core.protocol.message

import com.valhalla.core.protocol.CommandType

/** 中止 OTA 的原因码。 */
enum class OtaAbortReason(val code: Int) {
    USER_CANCELLED(0x00),
    TIMEOUT(0x01),
    VERIFY_FAILED(0x02),
    TRANSPORT_ERROR(0x03),
    DEVICE_REJECTED(0x04),
    ;

    companion object {
        fun fromCode(code: Int): OtaAbortReason? = entries.firstOrNull { it.code == code }
    }
}

/** OTA 阶段，用于进度通知。 */
enum class OtaStage(val code: Int) {
    PREPARING(0x00),
    TRANSFERRING(0x01),
    VERIFYING(0x02),
    APPLYING(0x03),
    COMPLETED(0x04),
    ;

    companion object {
        fun fromCode(code: Int): OtaStage? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 开始 OTA 会话。
 *
 * payload：`totalBytes:u32 | crc16:u16 | chunkSize:u16 | firmwareVersion`
 */
class StartOtaRequest(
    val totalBytes: Long,
    val crc16: Int,
    val chunkSize: Int,
    val firmwareVersion: FirmwareVersion,
) : DeviceRequest() {

    init {
        require(totalBytes in 1..0xFFFFFFFFL) { "totalBytes 超出范围: $totalBytes" }
        require(crc16 in 0..0xFFFF) { "crc16 超出范围: $crc16" }
        require(chunkSize in 1..0xFFFF) { "chunkSize 超出范围: $chunkSize" }
    }

    override val command: CommandType = CommandType.START_OTA

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(totalBytes)
        writer.u16(crc16)
        writer.u16(chunkSize)
        firmwareVersion.encodeTo(writer)
    }
}

/**
 * 开始 OTA 应答。
 *
 * body：`sessionId:u32 | chunkSize:u16`，设备可据此修正实际分片大小。
 */
class StartOtaResponse(
    status: DeviceStatus,
    val sessionId: Long,
    val chunkSize: Int,
) : DeviceResponse() {

    override val command: CommandType = CommandType.START_OTA

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(sessionId)
        writer.u16(chunkSize)
    }
}

/**
 * 下发一个 OTA 分片。
 *
 * payload：`offset:u32 | dataLen:u16 | data`
 * offset 为分片在固件中的起始字节偏移，便于设备校验乱序或重传。
 */
class OtaDataRequest(
    val offset: Long,
    val data: ByteArray,
) : DeviceRequest() {

    init {
        require(offset in 0..0xFFFFFFFFL) { "offset 超出范围: $offset" }
        require(data.isNotEmpty()) { "OTA 分片不能为空" }
    }

    override val command: CommandType = CommandType.OTA_DATA

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(offset)
        writer.u16(data.size)
        writer.bytes(data)
    }
}

/** OTA 分片应答：`receivedBytes:u32` 为设备已累计接收的字节数。 */
class OtaDataResponse(
    status: DeviceStatus,
    val receivedBytes: Long,
) : DeviceResponse() {

    init {
        require(receivedBytes in 0..0xFFFFFFFFL) { "receivedBytes 超出范围: $receivedBytes" }
    }

    override val command: CommandType = CommandType.OTA_DATA

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(receivedBytes)
    }
}

/** 结束 OTA：`totalBytes:u32 | crc16:u16`。 */
class FinishOtaRequest(
    val totalBytes: Long,
    val crc16: Int,
) : DeviceRequest() {

    init {
        require(totalBytes in 1..0xFFFFFFFFL) { "totalBytes 超出范围: $totalBytes" }
        require(crc16 in 0..0xFFFF) { "crc16 超出范围: $crc16" }
    }

    override val command: CommandType = CommandType.FINISH_OTA

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(totalBytes)
        writer.u16(crc16)
    }
}

/** 结束 OTA 应答：`rebootRequired:u8(0/1)`。 */
class FinishOtaResponse(
    status: DeviceStatus,
    val rebootRequired: Boolean,
) : DeviceResponse() {

    override val command: CommandType = CommandType.FINISH_OTA

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.byte(if (rebootRequired) 1 else 0)
    }
}

/** 中止 OTA：`reason:u8`。 */
class AbortOtaRequest(val reason: OtaAbortReason) : DeviceRequest() {

    override val command: CommandType = CommandType.ABORT_OTA

    override fun encodeBody(writer: PayloadWriter) {
        writer.byte(reason.code)
    }
}

/** 中止 OTA 应答，payload 只有状态码。 */
class AbortOtaResponse(status: DeviceStatus) : DeviceResponse() {

    override val command: CommandType = CommandType.ABORT_OTA

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) = Unit
}

/**
 * OTA 进度通知。
 *
 * payload：`sessionId:u32 | received:u32 | total:u32 | stage:u8`
 */
class OtaProgressNotification(
    val sessionId: Long,
    val receivedBytes: Long,
    val totalBytes: Long,
    val stage: OtaStage,
) : DeviceNotification() {

    init {
        require(sessionId in 0..0xFFFFFFFFL) { "sessionId 超出范围: $sessionId" }
        require(receivedBytes in 0..0xFFFFFFFFL) { "receivedBytes 超出范围: $receivedBytes" }
        require(totalBytes in 1..0xFFFFFFFFL) { "totalBytes 超出范围: $totalBytes" }
    }

    override val command: CommandType = CommandType.OTA_PROGRESS_NOTIFY

    val progress: Float
        get() = (receivedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(sessionId)
        writer.u32(receivedBytes)
        writer.u32(totalBytes)
        writer.byte(stage.code)
    }
}
