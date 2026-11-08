package com.valhalla.core.protocol.message

import com.valhalla.core.logging.LogLevel
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.state.DeviceState

private const val UINT8_MAX = 0xFF
private const val UINT16_MAX = 0xFFFF
private const val UINT32_MAX = 0xFFFFFFFFL
private const val DEVICE_STATE_DISCONNECTING = 0x05
private const val DEVICE_STATE_FAULT = 0x06

/** 恢复出厂设置，payload：`confirmToken:str1`（可为空串）。 */
class FactoryResetRequest(
    val confirmToken: String = "",
) : DeviceRequest() {
    override val command: CommandType = CommandType.FACTORY_RESET

    override fun encodeBody(writer: PayloadWriter) {
        writer.string1(confirmToken)
    }
}

/** 恢复出厂设置应答，payload 只有状态码。 */
class FactoryResetResponse(
    status: DeviceStatus,
) : DeviceResponse() {
    override val command: CommandType = CommandType.FACTORY_RESET

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) = Unit
}

/** 重启设备：`delayMillis:u16 | reason:u8`。 */
class RebootRequest(
    val delayMillis: Int = 0,
    val reason: Int = 0,
) : DeviceRequest() {
    init {
        require(delayMillis in 0..UINT16_MAX) { "delayMillis 超出范围: $delayMillis" }
        require(reason in 0..UINT8_MAX) { "reason 超出范围: $reason" }
    }

    override val command: CommandType = CommandType.REBOOT

    override fun encodeBody(writer: PayloadWriter) {
        writer.u16(delayMillis)
        writer.byte(reason)
    }
}

/** 重启应答，payload 只有状态码。 */
class RebootResponse(
    status: DeviceStatus,
) : DeviceResponse() {
    override val command: CommandType = CommandType.REBOOT

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) = Unit
}

/** 读取设备日志：`sinceSequence:u32 | maxEntries:u16`。 */
class LogDumpRequest(
    val sinceSequence: Long = 0,
    val maxEntries: Int = 64,
) : DeviceRequest() {
    init {
        require(sinceSequence in 0..UINT32_MAX) { "sinceSequence 超出范围: $sinceSequence" }
        require(maxEntries in 1..UINT16_MAX) { "maxEntries 超出范围: $maxEntries" }
    }

    override val command: CommandType = CommandType.LOG_DUMP

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(sinceSequence)
        writer.u16(maxEntries)
    }
}

/** 设备日志条目。 */
data class DeviceLogEntry(
    val level: LogLevel,
    val timestampSeconds: Long,
    val message: String,
)

/** 日志应答：`count:u16 | entries[level:u8,timestamp:u32,message:str2]...`。 */
class LogDumpResponse(
    status: DeviceStatus,
    val entries: List<DeviceLogEntry>,
) : DeviceResponse() {
    override val command: CommandType = CommandType.LOG_DUMP

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.u16(entries.size)
        entries.forEach { entry ->
            writer.byte(entry.level.priority)
            writer.u32(entry.timestampSeconds)
            writer.string2(entry.message)
        }
    }
}

/** 心跳请求：`uptimeSeconds:u32`。 */
class HeartbeatRequest(
    val uptimeSeconds: Long,
) : DeviceRequest() {
    init {
        require(uptimeSeconds in 0..UINT32_MAX) { "uptimeSeconds 超出范围: $uptimeSeconds" }
    }

    override val command: CommandType = CommandType.HEARTBEAT

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(uptimeSeconds)
    }
}

/** 心跳应答：`uptimeSeconds:u32 | batteryPercent:u8`。 */
class HeartbeatResponse(
    status: DeviceStatus,
    val uptimeSeconds: Long,
    val batteryPercent: Int,
) : DeviceResponse() {
    init {
        require(batteryPercent in 0..100) { "电池百分比超出范围: $batteryPercent" }
    }

    override val command: CommandType = CommandType.HEARTBEAT

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(uptimeSeconds)
        writer.byte(batteryPercent)
    }
}

/** 设备主动心跳通知：`batteryPercent:u8 | uptimeSeconds:u32`。 */
class HeartbeatReplyNotification(
    val batteryPercent: Int,
    val uptimeSeconds: Long,
) : DeviceNotification() {
    init {
        require(batteryPercent in 0..100) { "电池百分比超出范围: $batteryPercent" }
        require(uptimeSeconds in 0..UINT32_MAX) { "uptimeSeconds 超出范围: $uptimeSeconds" }
    }

    override val command: CommandType = CommandType.HEARTBEAT_REPLY

    override fun encodeBody(writer: PayloadWriter) {
        writer.byte(batteryPercent)
        writer.u32(uptimeSeconds)
    }
}

/** 设备状态通知：`state:u8 | batteryPercent:u8 | faultCode:u16`。 */
class DeviceStatusNotification(
    val deviceState: DeviceState,
    val batteryPercent: Int,
    val faultCode: Int,
) : DeviceNotification() {
    init {
        require(batteryPercent in 0..100) { "电池百分比超出范围: $batteryPercent" }
        require(faultCode in 0..UINT16_MAX) { "faultCode 超出范围: $faultCode" }
    }

    override val command: CommandType = CommandType.DEVICE_STATUS_NOTIFY

    override fun encodeBody(writer: PayloadWriter) {
        writer.byte(deviceStateCode(deviceState))
        writer.byte(batteryPercent)
        writer.u16(faultCode)
    }

    companion object {
        fun deviceStateCode(state: DeviceState): Int =
            when (state) {
                DeviceState.DISCONNECTED -> 0x00
                DeviceState.CONNECTING -> 0x01
                DeviceState.CONNECTED -> 0x02
                DeviceState.AUTHENTICATING -> 0x03
                DeviceState.READY -> 0x04
                DeviceState.DISCONNECTING -> DEVICE_STATE_DISCONNECTING
                DeviceState.FAULT -> DEVICE_STATE_FAULT
            }

        fun deviceStateFromCode(code: Int): DeviceState? =
            when (code) {
                0x00 -> DeviceState.DISCONNECTED
                0x01 -> DeviceState.CONNECTING
                0x02 -> DeviceState.CONNECTED
                0x03 -> DeviceState.AUTHENTICATING
                0x04 -> DeviceState.READY
                DEVICE_STATE_DISCONNECTING -> DeviceState.DISCONNECTING
                DEVICE_STATE_FAULT -> DeviceState.FAULT
                else -> null
            }
    }
}
