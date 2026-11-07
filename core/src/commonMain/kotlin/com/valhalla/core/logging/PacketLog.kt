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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
    fun formatForCopy(): String =
        buildString {
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

/**
 * 报文日志筛选条件。
 *
 * - [commands] 为多选命令集合；为空时退回 [command] 的单选语义，
 *   两者都为空表示不按命令过滤；
 * - [fromMillis] / [toMillis] 是闭区间边界（含端点），任一为 null 表示该侧不限；
 * - [errorsOnly] 只保留 [PacketLogEntry.error] 非空的条目；
 * - [keyword] 匹配命令名 / 摘要 / HEX / seq / 错误文本，忽略大小写。
 *
 * 过滤逻辑集中在 [matches]，UI 线性过滤、索引查询与导出一律复用，避免多处实现分叉。
 */
data class PacketLogFilter(
    val command: CommandType? = null,
    val commands: Set<CommandType> = emptySet(),
    val errorsOnly: Boolean = false,
    val keyword: String = "",
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
) {
    /** 实际生效的命令集合：多选优先，否则退回单选。 */
    val effectiveCommands: Set<CommandType>
        get() = if (commands.isNotEmpty()) commands else command?.let { setOf(it) } ?: emptySet()

    /** 时间范围是否倒序（起 > 止）；倒序范围在语义上不匹配任何条目。 */
    val timeRangeReversed: Boolean
        get() = fromMillis != null && toMillis != null && fromMillis > toMillis

    /** 单条日志是否满足当前筛选条件（含关键字）。 */
    fun matches(entry: PacketLogEntry): Boolean {
        if (!matchesFacets(entry)) return false
        val keyword = keyword.trim()
        return keyword.isEmpty() || entry.searchText().contains(keyword, ignoreCase = true)
    }
}

/** 除关键字外的筛选判断，供索引与预计算 haystack 复用。 */
internal fun PacketLogFilter.matchesFacets(entry: PacketLogEntry): Boolean {
    val commands = effectiveCommands
    if (commands.isNotEmpty() && entry.command !in commands) return false
    if (errorsOnly && entry.error == null) return false
    if (fromMillis != null && entry.timestampMillis < fromMillis) return false
    if (toMillis != null && entry.timestampMillis > toMillis) return false
    return true
}

/** 关键字搜索覆盖的原始字段；索引侧对该结果做一次小写缓存。 */
internal fun PacketLogEntry.searchText(): String =
    buildString {
        append(command.name)
        append('\n')
        append(summary)
        append('\n')
        append(hex)
        append('\n')
        append(sequence)
        append('\n')
        append(error.orEmpty())
    }

/** 按时间倒序过滤报文：命令集合、错误标记、时间闭区间与关键字。 */
fun filterPacketEntries(
    entries: List<PacketLogEntry>,
    filter: PacketLogFilter,
    limit: Int = Int.MAX_VALUE,
): List<PacketLogEntry> {
    if (limit <= 0) return emptyList()
    return entries
        .asReversed()
        .asSequence()
        .filter { entry -> filter.matches(entry) }
        .take(limit)
        .toList()
}

/**
 * 内存环形报文日志。
 *
 * - 默认保留 [capacity] 条，超出后淘汰最旧记录，避免 OTA 长时间传输导致内存膨胀；
 * - [entries] 按写入顺序（最旧在前）暴露，UI 过滤时再按时间倒序；
 * - 写入前统一执行 [PacketLogRedaction]：敏感字段（AUTH 的 credential/token/challenge、
 *   设备序列号）以等长掩码替换后再编码 HEX 与生成摘要，导出不可关闭、不可还原；
 * - 写入采用不可变快照 + CAS 更新，TX 发送与 RX 收集可并发调用：id 通过原子计数分配，
 *   极端并发下只可能发生条目交错，不会产生重复 id 或超容量列表。
 */
@OptIn(ExperimentalTime::class)
class InMemoryPacketLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** 脱敏开关，仅用于内部对照测试；生产环境恒为 true，不暴露给导出选项。 */
    internal val redactionEnabled: Boolean = true,
) {
    init {
        require(capacity > 0) { "capacity 必须大于 0" }
    }

    private val mutableEntries = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    private val idCounter = MutableStateFlow(0L)

    val entries: StateFlow<List<PacketLogEntry>> = mutableEntries.asStateFlow()

    fun record(
        direction: FrameDirection,
        frame: Frame,
    ): PacketLogEntry {
        val safeFrame = if (redactionEnabled) PacketLogRedaction.redactFrame(frame) else frame
        val hex: String =
            try {
                Hex.encode(FrameCodec.encode(safeFrame))
            } catch (_: Throwable) {
                ""
            }
        val described = PacketLogFormatter.describe(direction, safeFrame)
        val entry =
            PacketLogEntry(
                id = nextId(),
                timestampMillis = clockMillis(),
                direction = direction,
                command = safeFrame.type,
                sequence = safeFrame.sequence,
                isResponse = safeFrame.isResponse,
                isNotification = safeFrame.isNotification,
                hex = hex,
                summary = described.summary,
                error = described.error,
            )
        mutableEntries.update { current ->
            val appended = current + entry
            if (appended.size > capacity) {
                appended.subList(appended.size - capacity, appended.size).toList()
            } else {
                appended
            }
        }
        return entry
    }

    private fun nextId(): Long {
        // StateFlow.updateAndGet 在 KMP 各平台上都是原子的，无需引入 atomicfu。
        val next = idCounter.updateAndGet { it + 1 } - 1
        return next
    }

    fun filter(
        filter: PacketLogFilter,
        limit: Int = Int.MAX_VALUE,
    ): List<PacketLogEntry> = filterPacketEntries(mutableEntries.value, filter, limit)

    fun clear() {
        mutableEntries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}

/** 报文解析摘要生成器；解析失败时把异常原因写入 [Result.error]，不影响日志记录。 */
object PacketLogFormatter {
    data class Result(
        val summary: String,
        val error: String? = null,
    )

    fun describe(
        direction: FrameDirection,
        frame: Frame,
    ): Result =
        when {
            direction == FrameDirection.TX -> parseRequest(frame)
            frame.isNotification -> parseNotification(frame)
            else -> parseResponse(frame)
        }

    private fun parseRequest(frame: Frame): Result =
        try {
            val request = MessageCodec.decodeRequest(frame.type, frame.payload)
            Result(describeRequest(request))
        } catch (error: Throwable) {
            Result("${frame.type.name} payload=${frame.payload.size}B", error.message ?: "请求解析失败")
        }

    private fun parseNotification(frame: Frame): Result =
        try {
            Result(describeNotification(MessageCodec.decodeNotification(frame)))
        } catch (error: Throwable) {
            Result("${frame.type.name} payload=${frame.payload.size}B", error.message ?: "通知解析失败")
        }

    private fun parseResponse(frame: Frame): Result =
        try {
            val response = MessageCodec.decodeResponse(frame.type, frame.payload)
            if (response is ErrorResponse) {
                Result(
                    summary =
                        "${frame.type.name} 失败：${response.status.describe()}" +
                            (response.detail?.let { "（$it）" } ?: ""),
                    error = response.status.describe(),
                )
            } else {
                Result(describeResponse(response))
            }
        } catch (error: Throwable) {
            Result("${frame.type.name} payload=${frame.payload.size}B", error.message ?: "应答解析失败")
        }

    private fun describeRequest(request: DeviceRequest): String =
        when (request) {
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

    private fun describeResponse(response: DeviceResponse): String =
        when (response) {
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

    private fun describeNotification(notification: DeviceNotification): String =
        when (notification) {
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
