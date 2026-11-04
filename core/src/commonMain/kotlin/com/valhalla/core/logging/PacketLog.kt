package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.FrameCodec
import com.valhalla.core.protocol.Hex
import com.valhalla.core.protocol.message.AbortOtaRequest
import com.valhalla.core.protocol.message.AuthRequest
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceNotification
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FinishOtaResponse
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.MessageCodec
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.OtaProgressNotification
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.protocol.message.StartOtaResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

enum class FrameDirection {
    TX,
    RX,
}

/**
 * 一条报文日志（协议帧级别，不混入业务文本日志）。
 *
 * [hex] 是完整帧的十六进制（含 SOF/CRC），[summary] 是解析出的可读摘要，
 * [error] 非空表示帧解析失败或设备返回非 0 状态码，UI 可据此过滤错误报文。
 */
data class PacketLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val direction: FrameDirection,
    val command: CommandType,
    val sequence: Int,
    val isResponse: Boolean,
    val isNotification: Boolean,
    val hex: String,
    val summary: String,
    val error: String? = null,
) {

    fun formatForCopy(): String = buildString {
        append('[')
        append(formatTimestamp(timestampMillis))
        append("] ")
        append(direction.name)
        append(" seq=")
        append(sequence)
        append(' ')
        append(command.name)
        if (isResponse) append(" RESPONSE")
        if (isNotification) append(" NOTIFY")
        append('\n')
        append(summary)
        if (error != null) {
            append('\n')
            append("错误：")
            append(error)
        }
        append('\n')
        append(hex)
    }
}

data class PacketLogFilter(
    val command: CommandType? = null,
    val errorsOnly: Boolean = false,
    val keyword: String = "",
)

/** 按时间倒序过滤报文：命令、错误标记、关键字（匹配命令名/摘要/HEX/seq）。 */
fun filterPacketEntries(
    entries: List<PacketLogEntry>,
    filter: PacketLogFilter,
    limit: Int = Int.MAX_VALUE,
): List<PacketLogEntry> {
    val keyword = filter.keyword.trim()
    return entries.asReversed()
        .asSequence()
        .filter { entry -> filter.command == null || entry.command == filter.command }
        .filter { entry -> !filter.errorsOnly || entry.error != null }
        .filter { entry ->
            keyword.isEmpty() || listOf(
                entry.command.name,
                entry.summary,
                entry.hex,
                entry.sequence.toString(),
                entry.error.orEmpty(),
            ).any { it.contains(keyword, ignoreCase = true) }
        }
        .take(limit)
        .toList()
}

/**
 * 内存环形报文日志。
 *
 * - 默认保留 [capacity] 条，超出后淘汰最旧记录，避免 OTA 长时间传输导致内存膨胀；
 * - [entries] 按写入顺序（最旧在前）暴露，UI 过滤时再按时间倒序；
 * - 写入采用整体替换不可变快照，读取侧永远拿到一致列表；并发极端场景允许丢日志，
 *   与 [InMemoryDeviceLogger] 的取舍一致。
 */
@OptIn(ExperimentalTime::class)
class InMemoryPacketLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    init {
        require(capacity > 0) { "capacity 必须大于 0" }
    }

    private val mutableEntries = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    private var nextId: Long = 0

    val entries: StateFlow<List<PacketLogEntry>> = mutableEntries.asStateFlow()

    fun record(direction: FrameDirection, frame: Frame): PacketLogEntry {
        val hex: String = try {
            Hex.encode(FrameCodec.encode(frame))
        } catch (_: Throwable) {
            ""
        }
        val described = PacketLogFormatter.describe(direction, frame)
        val entry = PacketLogEntry(
            id = nextId++,
            timestampMillis = clockMillis(),
            direction = direction,
            command = frame.type,
            sequence = frame.sequence,
            isResponse = frame.isResponse,
            isNotification = frame.isNotification,
            hex = hex,
            summary = described.summary,
            error = described.error,
        )
        val appended = mutableEntries.value + entry
        mutableEntries.value = if (appended.size > capacity) {
            appended.subList(appended.size - capacity, appended.size).toList()
        } else {
            appended
        }
        return entry
    }

    fun filter(filter: PacketLogFilter, limit: Int = Int.MAX_VALUE): List<PacketLogEntry> =
        filterPacketEntries(mutableEntries.value, filter, limit)

    fun clear() {
        mutableEntries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}

/** 报文解析摘要生成器；解析失败时把异常原因写入 [Result.error]，不影响日志记录。 */
object PacketLogFormatter {

    data class Result(val summary: String, val error: String? = null)

    fun describe(direction: FrameDirection, frame: Frame): Result = when {
        direction == FrameDirection.TX -> parseRequest(frame)
        frame.isNotification -> parseNotification(frame)
        else -> parseResponse(frame)
    }

    private fun parseRequest(frame: Frame): Result = try {
        val request = MessageCodec.decodeRequest(frame.type, frame.payload)
        Result(describeRequest(request))
    } catch (error: Throwable) {
        Result("${frame.type.name} payload=${frame.payload.size}B", error.message ?: "请求解析失败")
    }

    private fun parseNotification(frame: Frame): Result = try {
        Result(describeNotification(MessageCodec.decodeNotification(frame)))
    } catch (error: Throwable) {
        Result("${frame.type.name} payload=${frame.payload.size}B", error.message ?: "通知解析失败")
    }

    private fun parseResponse(frame: Frame): Result = try {
        val response = MessageCodec.decodeResponse(frame.type, frame.payload)
        if (response is ErrorResponse) {
            Result(
                summary = "${frame.type.name} 失败：${response.status.describe()}" +
                    (response.detail?.let { "（$it）" } ?: ""),
                error = response.status.describe(),
            )
        } else {
            Result(describeResponse(response))
        }
    } catch (error: Throwable) {
        Result("${frame.type.name} payload=${frame.payload.size}B", error.message ?: "应答解析失败")
    }

    private fun describeRequest(request: DeviceRequest): String = when (request) {
        is AuthRequest -> "AUTH mode=${request.mode.name} credential=${request.credential.size}B"
        is GetConfigRequest -> "GET_CONFIG key=${request.key}"
        is SetConfigRequest -> "SET_CONFIG key=${request.key} value=${request.value.size}B"
        is StartOtaRequest ->
            "START_OTA total=${request.totalBytes} chunk=${request.chunkSize}" +
                if (request.hasResumeExtension) {
                    " resume=${request.resumeSessionId}@${request.resumeOffset}"
                } else {
                    " (无续传扩展)"
                }

        is OtaDataRequest -> "OTA_DATA offset=${request.offset} len=${request.data.size}B"
        is FinishOtaRequest -> "FINISH_OTA total=${request.totalBytes} crc=0x${request.crc16.toString(16)}"
        is AbortOtaRequest -> "ABORT_OTA reason=${request.reason.name}"
        else -> "${request.command.name} payload=${request.encode().size}B"
    }

    private fun describeResponse(response: DeviceResponse): String = when (response) {
        is ConfigResponse -> "CONFIG key=${response.key} value=${response.value.size}B"
        is DeviceInfoResponse ->
            "INFO vendor=${response.vendor} model=${response.model} serial=${response.serialNumber}"

        is BatteryResponse -> "BATTERY ${response.percent}% charging=${response.charging}"
        is FirmwareVersionResponse -> "VERSION ${response.version}"
        is StartOtaResponse ->
            "START_OK session=${response.sessionId} chunk=${response.chunkSize}" +
                " accepted=${response.acceptedOffset}"

        is OtaDataResponse -> "DATA_OK received=${response.receivedBytes}"
        is FinishOtaResponse -> "FINISH_OK reboot=${response.rebootRequired}"
        else -> "${response.command.name} OK"
    }

    private fun describeNotification(notification: DeviceNotification): String = when (notification) {
        is OtaProgressNotification ->
            "OTA_PROGRESS session=${notification.sessionId}" +
                " ${notification.receivedBytes}/${notification.totalBytes} stage=${notification.stage.name}"

        else -> notification.command.name
    }
}

private fun formatTimestamp(timestampMillis: Long): String {
    val millisOfDay = timestampMillis % 86_400_000L
    val hours = millisOfDay / 3_600_000L
    val minutes = (millisOfDay / 60_000L) % 60
    val seconds = (millisOfDay / 1_000L) % 60
    val millis = millisOfDay % 1_000L
    return buildString {
        append(hours.toString().padStart(2, '0'))
        append(':')
        append(minutes.toString().padStart(2, '0'))
        append(':')
        append(seconds.toString().padStart(2, '0'))
        append('.')
        append(millis.toString().padStart(3, '0'))
    }
}
