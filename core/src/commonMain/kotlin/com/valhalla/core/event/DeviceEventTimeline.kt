package com.valhalla.core.event

import com.valhalla.core.logging.SensitiveTextRedaction
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 事件类别；界面按此筛选。 */
enum class DeviceEventType {
    CONNECTION,
    DISCONNECTION,
    AUTH,
    CONFIG_CHANGE,
    PARAM_CHANGE,
    OTA,
    ERROR,
}

/** 事件结果；让时间线能区分「发生了什么」与「结果如何」。 */
enum class DeviceEventResult {
    INFO,
    SUCCESS,
    FAILURE,
}

/**
 * 一条设备事件。
 *
 * [summary] 与 [detail] 在落盘前统一经 [SensitiveTextRedaction] 处理，时间线展示与导出
 * 不会出现令牌、序列号原文；写入失败只记录布尔结果，绝不回滚业务。
 */
data class DeviceEvent(
    val id: Long,
    val deviceId: String,
    val type: DeviceEventType,
    val timestampMillis: Long,
    val summary: String,
    val result: DeviceEventResult = DeviceEventResult.INFO,
    val detail: String? = null,
    val errorCode: String? = null,
) {

    init {
        require(deviceId.isNotBlank()) { "设备 id 不能为空" }
        require(timestampMillis >= 0) { "事件时间不能为负" }
        require(summary.isNotBlank()) { "事件摘要不能为空" }
    }
}

/** 按设备分开存储事件；平台实现只负责字符串读写。 */
interface DeviceEventStore {

    fun loadRaw(deviceId: String): String?

    fun saveRaw(deviceId: String, raw: String)

    fun clear(deviceId: String)
}

/** 内存实现：JVM 测试与测试宿主使用。 */
class InMemoryDeviceEventStore :
    DeviceEventStore {

    private val values = mutableMapOf<String, String>()

    override fun loadRaw(deviceId: String): String? = values[deviceId]

    override fun saveRaw(deviceId: String, raw: String) {
        values[deviceId] = raw
    }

    override fun clear(deviceId: String) {
        values.remove(deviceId)
    }
}

/**
 * 事件时间线的版本化行式编码（每设备一份）。
 *
 * 格式：
 * ```
 * v1
 * E|id|type|timestamp|result|summary|detail|errorCode
 * ```
 * 字段百分号转义；空字符串表示 null。任何结构损坏都跳过该行，读取不抛异常。
 */
object DeviceEventCodec {

    private const val VERSION = "v1"
    private const val EVENT = "E"
    private const val SEPARATOR = '|'
    private const val PARTS = 8
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun encode(events: List<DeviceEvent>): String = buildString {
        append(VERSION)
        append('\n')
        events.sortedBy { it.id }.forEach { event ->
            append(EVENT)
            append(SEPARATOR)
            append(event.id)
            append(SEPARATOR)
            append(event.type.name)
            append(SEPARATOR)
            append(event.timestampMillis)
            append(SEPARATOR)
            append(event.result.name)
            append(SEPARATOR)
            append(escape(redactInline(event.summary)))
            append(SEPARATOR)
            append(escape(event.detail?.let(::redactInline)))
            append(SEPARATOR)
            append(escape(event.errorCode?.let(::redactInline)))
            append('\n')
        }
    }

    fun decode(raw: String?): List<DeviceEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != VERSION) return emptyList()
        val result = mutableListOf<DeviceEvent>()
        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split(SEPARATOR)
            if (parts.size != PARTS || parts[0] != EVENT) return@forEach
            parse(parts)?.let(result::add)
        }
        return result
    }

    private fun parse(parts: List<String>): DeviceEvent? {
        val id = parts[1].toLongOrNull() ?: return null
        val type = DeviceEventType.entries.firstOrNull { it.name == parts[2] } ?: return null
        val timestamp = parts[3].toLongOrNull() ?: return null
        val result = DeviceEventResult.entries.firstOrNull { it.name == parts[4] }
            ?: DeviceEventResult.INFO
        val summary = unescape(parts[5]) ?: return null
        return runCatching {
            DeviceEvent(
                id = id,
                // 设备 id 不逐行落盘（每设备一份存储），由仓储读取后补齐。
                deviceId = DEVICE_ID_PLACEHOLDER,
                type = type,
                timestampMillis = timestamp,
                summary = summary,
                result = result,
                detail = unescape(parts[6]),
                errorCode = unescape(parts[7]),
            )
        }.getOrNull()
    }

    private const val DEVICE_ID_PLACEHOLDER = "-"

    private fun redactInline(value: String): String = SensitiveTextRedaction.redactInline(value)

    private fun escape(value: String?): String {
        if (value == null) return ""
        return buildString {
            value.encodeToByteArray().forEach { byte ->
                val code = byte.toInt() and 0xFF
                if (isAsciiSafe(code)) {
                    append(code.toChar())
                } else {
                    append('%')
                    append(HEX_DIGITS[code shr 4])
                    append(HEX_DIGITS[code and 0x0F])
                }
            }
        }
    }

    private fun isAsciiSafe(code: Int): Boolean =
        code in 0x30..0x39 ||
            code in 0x41..0x5A ||
            code in 0x61..0x7A ||
            code == '.'.code ||
            code == '_'.code ||
            code == '~'.code ||
            code == '-'.code

    private fun unescape(value: String): String? {
        if (value.isEmpty()) return null
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                if (index + 2 >= value.length) return null
                val high = value[index + 1].digitToIntOrNull(16) ?: return null
                val low = value[index + 2].digitToIntOrNull(16) ?: return null
                bytes += ((high shl 4) or low).toByte()
                index += 3
            } else {
                if (char.code > 0x7E) return null
                bytes += char.code.toByte()
                index++
            }
        }
        return runCatching { bytes.toByteArray().decodeToString() }.getOrNull()
    }
}

/**
 * 设备事件时间线。
 *
 * - 每设备保留最近 [maxPerDevice] 条（默认 100），超出后淘汰最旧；
 * - 每次读取都从平台存储重新解码，保证跨进程可读（不依赖进程内缓存）；
 * - 写入失败只返回 false，绝不向调用方抛出：事件记录是旁路能力，不得阻塞连接/OTA。
 */
@OptIn(ExperimentalTime::class)
class DeviceEventTimeline(
    private val store: DeviceEventStore,
    private val maxPerDevice: Int = DEFAULT_MAX_PER_DEVICE,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    init {
        require(maxPerDevice > 0) { "maxPerDevice 必须大于 0" }
    }

    /** 记录一条事件；返回是否成功落盘（失败不影响业务）。 */
    fun record(
        deviceId: String,
        type: DeviceEventType,
        summary: String,
        result: DeviceEventResult = DeviceEventResult.INFO,
        detail: String? = null,
        errorCode: String? = null,
        timestampMillis: Long = clockMillis(),
    ): Boolean = runCatching {
        val existing = load(deviceId)
        val nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1
        val event = DeviceEvent(
            id = nextId,
            deviceId = deviceId,
            type = type,
            timestampMillis = timestampMillis,
            summary = summary,
            result = result,
            detail = detail,
            errorCode = errorCode,
        )
        val appended = existing + event
        val trimmed = appended.sortedByDescending { it.timestampMillis }
            .take(maxPerDevice)
            .sortedBy { it.id }
        store.saveRaw(deviceId, DeviceEventCodec.encode(trimmed))
        true
    }.getOrDefault(false)

    /** 按时间倒序返回事件；[type] 非空时只返回该类别。 */
    fun list(deviceId: String, type: DeviceEventType? = null): List<DeviceEvent> {
        val events = load(deviceId)
        val filtered = if (type == null) events else events.filter { it.type == type }
        return filtered.sortedWith(
            compareByDescending<DeviceEvent> { it.timestampMillis }.thenByDescending { it.id },
        )
    }

    fun count(deviceId: String): Int = load(deviceId).size

    fun clear(deviceId: String) {
        runCatching { store.clear(deviceId) }
    }

    private fun load(deviceId: String): List<DeviceEvent> = runCatching {
        DeviceEventCodec.decode(store.loadRaw(deviceId)).map { it.copy(deviceId = deviceId) }
    }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_MAX_PER_DEVICE = 100
    }
}
