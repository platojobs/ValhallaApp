package com.valhalla.core.logging

/**
 * 报文日志导出选项。
 *
 * 上限保护的目的是避免一次导出几百 MB：
 * - [maxEntries] 限制条目数（按时间倒序取最新）；
 * - [maxBytes] 限制最终文本的 UTF-8 字节数；
 * - [maxHexCharsPerEntry] 限制单条 HEX 字符数，超长时在条目内标注省略量。
 */
data class PacketLogExportOptions(
    val maxEntries: Int = 200,
    val maxBytes: Int = 512 * 1024,
    val maxHexCharsPerEntry: Int = 4096,
) {

    init {
        require(maxEntries > 0) { "maxEntries 必须大于 0" }
        require(maxBytes > 0) { "maxBytes 必须大于 0" }
        require(maxHexCharsPerEntry > 0) { "maxHexCharsPerEntry 必须大于 0" }
    }
}

data class PacketLogExportResult(
    val text: String,
    val fileName: String,
    /** 实际写入的条目数。 */
    val entryCount: Int,
    /** 导出前的日志总条数。 */
    val sourceCount: Int,
    /** 是否因条目数或字节上限而丢弃了更旧的条目。 */
    val truncated: Boolean,
    val byteSize: Int,
)

/**
 * 报文日志导出为纯文本。
 *
 * 排序口径与 UI 一致：按 [PacketLogEntry.timestampMillis] 倒序，时间戳相同再按 id 倒序，
 * 因此导出内容即使被截断也保持「最新在前」的相对顺序。
 */
object PacketLogExporter {

    fun build(
        entries: List<PacketLogEntry>,
        nowMillis: Long,
        zoneOffsetMinutes: Int,
        options: PacketLogExportOptions = PacketLogExportOptions(),
    ): PacketLogExportResult {
        val ordered = entries.sortedWith(
            compareByDescending<PacketLogEntry> { it.timestampMillis }.thenByDescending { it.id },
        )
        val selected = mutableListOf<String>()
        var usedBytes = 0
        var droppedEntries = false

        for (entry in ordered) {
            if (selected.size >= options.maxEntries) {
                droppedEntries = true
                break
            }
            val block = formatEntry(entry, nowMillis, zoneOffsetMinutes, options.maxHexCharsPerEntry)
            val blockBytes = block.encodeToByteArray().size
            if (usedBytes + blockBytes > options.maxBytes) {
                droppedEntries = true
                break
            }
            selected += block
            usedBytes += blockBytes
        }

        var header = buildHeader(
            exported = selected.size,
            sourceCount = entries.size,
            truncated = droppedEntries,
            nowMillis = nowMillis,
            zoneOffsetMinutes = zoneOffsetMinutes,
            options = options,
        )
        var text = render(header, selected)
        var bytes = text.encodeToByteArray().size
        // 头部也有字节开销：超出预算时继续从尾部（最旧）丢弃条目。
        while (bytes > options.maxBytes && selected.isNotEmpty()) {
            selected.removeAt(selected.lastIndex)
            droppedEntries = true
            header = buildHeader(
                exported = selected.size,
                sourceCount = entries.size,
                truncated = true,
                nowMillis = nowMillis,
                zoneOffsetMinutes = zoneOffsetMinutes,
                options = options,
            )
            text = render(header, selected)
            bytes = text.encodeToByteArray().size
        }

        return PacketLogExportResult(
            text = text,
            fileName = fileName(nowMillis, zoneOffsetMinutes),
            entryCount = selected.size,
            sourceCount = entries.size,
            truncated = droppedEntries,
            byteSize = bytes,
        )
    }

    private fun render(header: String, blocks: List<String>): String = buildString {
        append(header)
        if (blocks.isNotEmpty()) {
            append('\n')
            blocks.forEachIndexed { index, block ->
                if (index > 0) append('\n')
                append(block)
            }
            append('\n')
        }
    }

    private fun buildHeader(
        exported: Int,
        sourceCount: Int,
        truncated: Boolean,
        nowMillis: Long,
        zoneOffsetMinutes: Int,
        options: PacketLogExportOptions,
    ): String = buildString {
        append("ValhallaApp 报文日志\n")
        append("导出时间：")
        append(formatAbsoluteTime(nowMillis, zoneOffsetMinutes))
        append('\n')
        append("条目：$exported / $sourceCount")
        if (truncated) append("（已按上限截断，仅保留最新条目）")
        append('\n')
        append(
            "上限：${options.maxEntries} 条 / ${options.maxBytes} 字节 / 单条 HEX ${options.maxHexCharsPerEntry} 字符\n",
        )
        append("字段：时间（绝对 | 相对） 方向 [RESPONSE/NOTIFY] seq 命令 / 摘要 / 错误 / HEX\n")
    }

    private fun formatEntry(
        entry: PacketLogEntry,
        nowMillis: Long,
        zoneOffsetMinutes: Int,
        maxHexChars: Int,
    ): String = buildString {
        append('[')
        append(formatAbsoluteTime(entry.timestampMillis, zoneOffsetMinutes))
        append(" | ")
        append(formatRelativeTime(entry.timestampMillis, nowMillis))
        append("] ")
        append(entry.direction.name)
        if (entry.isResponse) append(" RESPONSE")
        if (entry.isNotification) append(" NOTIFY")
        append(" seq=")
        append(entry.sequence)
        append(' ')
        append(entry.command.name)
        append('\n')
        append("  摘要：")
        append(entry.summary)
        if (entry.error != null) {
            append('\n')
            append("  错误：")
            append(entry.error)
        }
        append('\n')
        append("  HEX：")
        if (entry.hex.length > maxHexChars) {
            append(entry.hex, 0, maxHexChars)
            append("…（省略 ")
            append(entry.hex.length - maxHexChars)
            append(" 字符）")
        } else {
            append(entry.hex)
        }
    }

    /** 文件名带本地时间，避免多次导出互相覆盖。 */
    fun fileName(nowMillis: Long, zoneOffsetMinutes: Int): String {
        val parts = localParts(nowMillis, zoneOffsetMinutes)
        return "valhalla-packet-log-" +
            parts.year.toString().padStart(4, '0') +
            parts.month.toString().padStart(2, '0') +
            parts.day.toString().padStart(2, '0') + "-" +
            parts.hour.toString().padStart(2, '0') +
            parts.minute.toString().padStart(2, '0') +
            parts.second.toString().padStart(2, '0') + ".txt"
    }
}

/** 单条日志的本地化文本，供「复制单条」使用；格式与导出条目一致。 */
fun PacketLogEntry.formatForCopy(nowMillis: Long, zoneOffsetMinutes: Int): String = buildString {
    append('[')
    append(formatAbsoluteTime(timestampMillis, zoneOffsetMinutes))
    append(" | ")
    append(formatRelativeTime(timestampMillis, nowMillis))
    append("] ")
    append(direction.name)
    if (isResponse) append(" RESPONSE")
    if (isNotification) append(" NOTIFY")
    append(" seq=")
    append(sequence)
    append(' ')
    append(command.name)
    append('\n')
    append(summary)
    error?.let {
        append('\n')
        append("错误：")
        append(it)
    }
    append('\n')
    append("HEX ")
    append(hex)
}

/** 本地绝对时间：`YYYY-MM-DD HH:mm:ss.SSS +08:00`。 */
fun formatAbsoluteTime(epochMillis: Long, zoneOffsetMinutes: Int): String {
    val parts = localParts(epochMillis, zoneOffsetMinutes)
    return buildString {
        append(parts.year.toString().padStart(4, '0'))
        append('-')
        append(parts.month.toString().padStart(2, '0'))
        append('-')
        append(parts.day.toString().padStart(2, '0'))
        append(' ')
        append(parts.hour.toString().padStart(2, '0'))
        append(':')
        append(parts.minute.toString().padStart(2, '0'))
        append(':')
        append(parts.second.toString().padStart(2, '0'))
        append('.')
        append(parts.millis.toString().padStart(3, '0'))
        append(' ')
        append(formatOffset(zoneOffsetMinutes))
    }
}

/** 相对当前时间的可读描述；未来时间会标注方向，避免导出/查看时钟漂移时出现负数。 */
fun formatRelativeTime(entryMillis: Long, nowMillis: Long): String {
    val delta = nowMillis - entryMillis
    return when {
        delta < -1_000L -> "未来 ${formatDuration(-delta)}"
        delta < 1_000L -> "刚刚"
        else -> "${formatDuration(delta)}前"
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000L
    return when {
        totalSeconds < 60 -> "${totalSeconds}s"
        totalSeconds < 3_600 ->
            "${totalSeconds / 60}m${(totalSeconds % 60).toString().padStart(2, '0')}s"

        else ->
            "${totalSeconds / 3_600}h${((totalSeconds % 3_600) / 60).toString().padStart(2, '0')}m"
    }
}

private fun formatOffset(offsetMinutes: Int): String {
    val sign = if (offsetMinutes < 0) '-' else '+'
    val absolute = if (offsetMinutes < 0) -offsetMinutes else offsetMinutes
    return buildString {
        append(sign)
        append((absolute / 60).toString().padStart(2, '0'))
        append(':')
        append((absolute % 60).toString().padStart(2, '0'))
    }
}

private data class LocalParts(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millis: Int,
)

private const val MILLIS_PER_DAY = 86_400_000L

private fun localParts(epochMillis: Long, zoneOffsetMinutes: Int): LocalParts {
    val shifted = epochMillis + zoneOffsetMinutes.toLong() * 60_000L
    val days = floorDiv(shifted, MILLIS_PER_DAY)
    val millisOfDay = shifted - days * MILLIS_PER_DAY
    val (year, month, day) = civilFromDays(days)
    return LocalParts(
        year = year,
        month = month,
        day = day,
        hour = (millisOfDay / 3_600_000L).toInt(),
        minute = ((millisOfDay / 60_000L) % 60L).toInt(),
        second = ((millisOfDay / 1_000L) % 60L).toInt(),
        millis = (millisOfDay % 1_000L).toInt(),
    )
}

private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && (remainder < 0) != (divisor < 0)) quotient - 1 else quotient
}

/** Howard Hinnant 的 civil_from_days，用于在 commonMain 中把天数转换为公历日期。 */
private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = if (monthPrime < 10L) monthPrime + 3L else monthPrime - 9L
    return Triple(
        (if (month <= 2L) year + 1L else year).toInt(),
        month.toInt(),
        day.toInt(),
    )
}
