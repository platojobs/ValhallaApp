package com.valhalla.core.logging

enum class LogLevel(val priority: Int, val label: String) {
    VERBOSE(0, "V"),
    DEBUG(1, "D"),
    INFO(2, "I"),
    WARN(3, "W"),
    ERROR(4, "E"),
}

data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun format(): String = "[${level.label}] $tag - $message"
}

/**
 * 设备日志接口。实现方需保证 UI 侧只读快照即可渲染，避免直接暴露可变集合。
 */
interface DeviceLogger {
    fun log(level: LogLevel, tag: String, message: String, timestampMillis: Long = 0L)

    fun entries(): List<LogEntry>

    fun clear()
}

/**
 * 内存环形缓冲日志。
 *
 * 说明：写入采用「整体替换不可变快照」的方式，读侧永远拿到一致的列表；
 * 并发写入时允许丢失极端场景下的日志，但不会产生结构性损坏。
 */
class InMemoryDeviceLogger(private val capacity: Int = 200) : DeviceLogger {

    init {
        require(capacity > 0) { "capacity 必须大于 0" }
    }

    private var snapshot: List<LogEntry> = emptyList()
    private var nextId: Long = 0

    override fun log(level: LogLevel, tag: String, message: String, timestampMillis: Long) {
        val entry = LogEntry(
            id = nextId++,
            timestampMillis = timestampMillis,
            level = level,
            tag = tag,
            message = message,
        )
        val appended = snapshot + entry
        snapshot = if (appended.size > capacity) {
            appended.subList(appended.size - capacity, appended.size).toList()
        } else {
            appended
        }
    }

    override fun entries(): List<LogEntry> = snapshot

    override fun clear() {
        snapshot = emptyList()
    }
}
