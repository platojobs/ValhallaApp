package com.valhalla.core.protocol.message

import com.valhalla.core.protocol.CommandType

/** 中止 OTA 的原因码。 */
enum class OtaAbortReason(
    val code: Int,
) {
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
enum class OtaStage(
    val code: Int,
) {
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
 * 基础 payload（与旧协议完全一致，旧设备可继续解析）：
 * `totalBytes:u32 | crc16:u16 | chunkSize:u16 | firmwareVersion`
 *
 * 续传扩展（仅在 [resumeOffset] > 0 时追加，共 14 字节）：
 * `extVersion:u8(=1) | flags:u8(bit0=续传) | resumeSessionId:u32 | resumeOffset:u32 | payloadCrc32:u32`
 *
 * 兼容策略：不续传时不追加任何字节，旧设备按原格式交互；续传时旧设备会因尾部多余字节
 * 返回 `INVALID_PAYLOAD`，控制器据此回退为不带扩展的 `START_OTA` 并从 0 开始。
 */
class StartOtaRequest(
    val totalBytes: Long,
    val crc16: Int,
    val chunkSize: Int,
    val firmwareVersion: FirmwareVersion,
    val resumeSessionId: Long = 0,
    val resumeOffset: Long = 0,
    val payloadCrc32: Long = 0,
) : DeviceRequest() {
    init {
        require(totalBytes in 1..0xFFFFFFFFL) { "totalBytes 超出范围: $totalBytes" }
        require(crc16 in 0..0xFFFF) { "crc16 超出范围: $crc16" }
        require(chunkSize in 1..0xFFFF) { "chunkSize 超出范围: $chunkSize" }
        require(resumeSessionId in 0..0xFFFFFFFFL) { "resumeSessionId 超出范围: $resumeSessionId" }
        require(resumeOffset in 0..0xFFFFFFFFL) { "resumeOffset 超出范围: $resumeOffset" }
        require(payloadCrc32 in 0..0xFFFFFFFFL) { "payloadCrc32 超出范围: $payloadCrc32" }
        require(resumeOffset < totalBytes) { "resumeOffset 必须小于 totalBytes: $resumeOffset >= $totalBytes" }
    }

    override val command: CommandType = CommandType.START_OTA

    /** 是否携带续传扩展字段。 */
    val hasResumeExtension: Boolean get() = resumeOffset > 0

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(totalBytes)
        writer.u16(crc16)
        writer.u16(chunkSize)
        firmwareVersion.encodeTo(writer)
        if (hasResumeExtension) {
            writer.byte(EXTENSION_VERSION)
            writer.byte(FLAG_RESUME)
            writer.u32(resumeSessionId)
            writer.u32(resumeOffset)
            writer.u32(payloadCrc32)
        }
    }

    companion object {
        const val EXTENSION_VERSION: Int = 1
        const val FLAG_RESUME: Int = 0x01
        const val EXTENSION_SIZE: Int = 14

        /** 解析基础字段，并按尾部长度决定是否读取续传扩展。 */
        fun decode(reader: PayloadReader): StartOtaRequest {
            val totalBytes = reader.u32()
            val crc16 = reader.u16()
            val chunkSize = reader.u16()
            val firmwareVersion = FirmwareVersion.decode(reader)
            return when (reader.remaining) {
                0 -> StartOtaRequest(totalBytes, crc16, chunkSize, firmwareVersion)
                EXTENSION_SIZE -> {
                    val extensionVersion = reader.byte()
                    if (extensionVersion != EXTENSION_VERSION) {
                        throw MessageDecodeException("不支持的 OTA 续传扩展版本: $extensionVersion")
                    }
                    val flags = reader.byte()
                    if ((flags and FLAG_RESUME) == 0) {
                        throw MessageDecodeException("OTA 续传扩展缺少续传标志")
                    }
                    if ((flags and FLAG_RESUME.inv() and 0xFF) != 0) {
                        throw MessageDecodeException("OTA 续传扩展包含未知标志位: 0x${flags.toString(16)}")
                    }
                    StartOtaRequest(
                        totalBytes = totalBytes,
                        crc16 = crc16,
                        chunkSize = chunkSize,
                        firmwareVersion = firmwareVersion,
                        resumeSessionId = reader.u32(),
                        resumeOffset = reader.u32(),
                        payloadCrc32 = reader.u32(),
                    )
                }

                else -> throw MessageDecodeException("START_OTA payload 尾部长度非法: ${reader.remaining}")
            }
        }
    }
}

/**
 * 开始 OTA 应答。
 *
 * 基础 body：`sessionId:u32 | chunkSize:u16`，设备可据此修正实际分片大小。
 *
 * 续传扩展：当设备接受非 0 偏移时追加 `acceptedOffset:u32`（实际生效的起始偏移）。
 * 旧设备只返回基础字段，解码为 `acceptedOffset = 0`；设备接受 0 时也按基础格式返回。
 */
class StartOtaResponse(
    status: DeviceStatus,
    val sessionId: Long,
    val chunkSize: Int,
    val acceptedOffset: Long = 0,
) : DeviceResponse() {
    init {
        require(sessionId in 0..0xFFFFFFFFL) { "sessionId 超出范围: $sessionId" }
        require(chunkSize in 1..0xFFFF) { "chunkSize 超出范围: $chunkSize" }
        require(acceptedOffset in 0..0xFFFFFFFFL) { "acceptedOffset 超出范围: $acceptedOffset" }
    }

    override val command: CommandType = CommandType.START_OTA

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(sessionId)
        writer.u16(chunkSize)
        if (acceptedOffset > 0) writer.u32(acceptedOffset)
    }

    companion object {
        /** 解析基础字段，并按尾部长度决定是否读取设备确认偏移。 */
        fun decode(
            status: DeviceStatus,
            reader: PayloadReader,
        ): StartOtaResponse {
            val sessionId = reader.u32()
            val chunkSize = reader.u16()
            val acceptedOffset =
                when (reader.remaining) {
                    0 -> 0L
                    4 -> reader.u32()
                    else -> throw MessageDecodeException("START_OTA 应答尾部长度非法: ${reader.remaining}")
                }
            return StartOtaResponse(status, sessionId, chunkSize, acceptedOffset)
        }
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
class AbortOtaRequest(
    val reason: OtaAbortReason,
) : DeviceRequest() {
    override val command: CommandType = CommandType.ABORT_OTA

    override fun encodeBody(writer: PayloadWriter) {
        writer.byte(reason.code)
    }
}

/** 中止 OTA 应答，payload 只有状态码。 */
class AbortOtaResponse(
    status: DeviceStatus,
) : DeviceResponse() {
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
