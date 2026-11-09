package com.valhalla.core.logging

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

private const val BYTES_PER_KIB = 1_024
private const val DEFAULT_MAX_BYTES = 524_288
private const val PROGRESS_YIELD_INTERVAL = 64
private const val FIRST_PRINTABLE_ASCII_CODE = 0x20
private const val MONTHS_PER_YEAR = 12
private const val MAX_DAYS_PER_MONTH = 31
private const val MAX_HOUR_OF_DAY = 23
private const val MAX_MINUTE_OF_HOUR = 59
private const val MAX_SECOND_OF_MINUTE = 59
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600
private const val MINUTES_PER_HOUR = 60
private const val CIVIL_EPOCH_DAY_OFFSET = 719_468L
private const val CIVIL_ERA_DAY_ADJUSTMENT = 146_096L
private const val DAYS_PER_ERA = 146_097L
private const val DAYS_PER_FOUR_YEARS_CYCLE = 1_460L
private const val DAYS_PER_CENTURY_CYCLE = 36_524L
private const val DAYS_PER_YEAR = 365L
private const val YEARS_PER_ERA = 400L
private const val YEARS_PER_ERA_COUNT = 400
private const val YEARS_PER_ERA_MINUS_ONE = 399
private const val MONTH_PHASE_MULTIPLIER = 5L
private const val MONTH_PHASE_DIVISOR = 153L
private const val MONTH_PHASE_THRESHOLD = 10L
private const val MONTH_PHASE_OVERFLOW = 9L
private const val WINTER_MONTH_SHIFT = 9

/** 导出格式；[extension] 与 [mimeType] 用于文件名与平台保存/分享。 */
enum class PacketLogExportFormat(
    val extension: String,
    val mimeType: String,
) {
    TEXT("txt", "text/plain"),
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
}

/** 截断方向；导出始终保留「最新优先」，因此只会丢弃更旧的一端。 */
enum class PacketLogTruncationTarget {
    OLDEST,
}

/**
 * 报文日志导出上限。
 *
 * 下限/上限的作用是避免误输入造成空文件或几百 MB 的导出：
 * - [maxEntries] 1..5000 条；
 * - [maxBytes] 512 B..16 MiB（最终文本 UTF-8 字节数）；
 * - [maxHexCharsPerEntry] 单条 HEX 字符数，超出时在条目内标注省略量。
 */
data class PacketLogExportOptions(
    val maxEntries: Int = 200,
    val maxBytes: Int = DEFAULT_MAX_BYTES,
    val maxHexCharsPerEntry: Int = 4096,
) {
    init {
        require(maxEntries in MIN_ENTRIES..MAX_ENTRIES) {
            "maxEntries 必须在 $MIN_ENTRIES..$MAX_ENTRIES 之间"
        }
        require(maxBytes in MIN_BYTES..MAX_BYTES) {
            "maxBytes 必须在 $MIN_BYTES..$MAX_BYTES 之间"
        }
        require(maxHexCharsPerEntry in 1..MAX_HEX_CHARS) {
            "maxHexCharsPerEntry 必须在 1..$MAX_HEX_CHARS 之间"
        }
    }

    companion object {
        const val MIN_ENTRIES = 1
        const val MAX_ENTRIES = 5000
        const val MIN_BYTES = 512
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_HEX_CHARS = 65_536

        /** UI 默认值；与 v1.4.0 保持一致的 200 条 / 512 KiB。 */
        val DEFAULT = PacketLogExportOptions()
    }
}

/** 文本输入的上限校验结果；[value] 非空表示合法。 */
data class PacketLogLimitCheck(
    val value: Int? = null,
    val error: String? = null,
) {
    val valid: Boolean get() = value != null
}

/** 校验“条数上限”输入（1..5000）。空、非整数、越界都给出中文原因。 */
fun validateEntryLimit(input: String): PacketLogLimitCheck {
    val text = input.trim()
    if (text.isEmpty()) {
        return PacketLogLimitCheck(error = "请输入 ${PacketLogExportOptions.MIN_ENTRIES}..${PacketLogExportOptions.MAX_ENTRIES} 之间的整数")
    }
    val value =
        text.toIntOrNull()
            ?: return PacketLogLimitCheck(error = "条数上限必须是整数")
    if (value !in PacketLogExportOptions.MIN_ENTRIES..PacketLogExportOptions.MAX_ENTRIES) {
        return PacketLogLimitCheck(
            error = "条数上限需在 ${PacketLogExportOptions.MIN_ENTRIES}..${PacketLogExportOptions.MAX_ENTRIES} 之间",
        )
    }
    return PacketLogLimitCheck(value = value)
}

/** 校验“大小上限”输入（单位 KB，1..16384 KB，即 1 KiB..16 MiB），返回字节值。 */
fun validateByteLimitKb(input: String): PacketLogLimitCheck {
    val text = input.trim()
    val maxKb = PacketLogExportOptions.MAX_BYTES / BYTES_PER_KIB
    if (text.isEmpty()) {
        return PacketLogLimitCheck(error = "请输入 1..$maxKb 之间的整数（KB）")
    }
    val value =
        text.toIntOrNull()
            ?: return PacketLogLimitCheck(error = "大小上限必须是整数（KB）")
    if (value !in 1..maxKb) {
        return PacketLogLimitCheck(error = "大小上限需在 1..$maxKb KB 之间")
    }
    return PacketLogLimitCheck(value = value * 1024)
}

/** 导出前的预估结果；用于“超限将截断最旧条目”的提示。 */
data class PacketLogExportEstimate(
    val sourceCount: Int,
    val matchedCount: Int,
    val estimatedEntries: Int,
    val estimatedBytes: Int,
    val truncated: Boolean,
    val truncationTarget: PacketLogTruncationTarget? = null,
    val notice: String? = null,
) {
    val empty: Boolean get() = matchedCount == 0 || estimatedEntries == 0
}

data class PacketLogExportResult(
    val text: String,
    val fileName: String,
    val format: PacketLogExportFormat,
    /** 实际写入的条目数。 */
    val entryCount: Int,
    /** 导出前的日志总条数。 */
    val sourceCount: Int,
    /** 命中筛选条件的条目数（截断前）。 */
    val matchedCount: Int,
    /** 是否因条目数或字节上限而丢弃了更旧的条目。 */
    val truncated: Boolean,
    val truncationTarget: PacketLogTruncationTarget? = null,
    val byteSize: Int,
    val notice: String? = null,
    /**
     * 流式拼装期间单块缓冲达到过的最大字符数；恒 ≤ [PacketLogExporter.STREAM_CHUNK_CHARS]，
     * 用于验证峰值缓冲不随条目数线性增长。预估（不拼装）时为 0。
     */
    val peakBufferChars: Int = 0,
) {
    val empty: Boolean get() = entryCount == 0 || text.isEmpty()
}

enum class PacketLogExportPhase {
    FILTERING,
    RENDERING,
    ASSEMBLING,
}

/** 导出进度；[fraction] 已归一到 0..1，可直接驱动进度条。 */
data class PacketLogExportProgress(
    val processed: Int,
    val total: Int,
    val phase: PacketLogExportPhase,
) {
    val fraction: Float
        get() = if (total <= 0) 1f else (processed.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 报文日志导出。
 *
 * 排序口径与 UI 一致：按 [PacketLogEntry.timestampMillis] 倒序，时间戳相同再按 id 倒序，
 * 因此导出内容即使被截断也保持「最新在前」；截断只会发生在最旧的一端
 * （[PacketLogTruncationTarget.OLDEST]）。
 */
object PacketLogExporter {
    /**
     * 流式拼装的分块大小（字符）。条目逐条序列化后写入 [ChunkedTextBuilder]，
     * 单块缓冲从不超过该值，因此拼装阶段的内存占用与条目总数无关。
     */
    const val STREAM_CHUNK_CHARS = 8192

    /**
     * 构建导出内容。
     *
     * 该函数是 `suspend` 的：过滤、渲染与拼装阶段都会周期性 `yield()` 并检查协程取消，
     * UI 应放在后台调度器执行，进度通过 [onProgress] 回传。内容全部构建完成后才返回，
     * 平台写文件发生在调用方，因此取消不会留下半成品文件。
     *
     * 拼装采用流式写入：条目逐条序列化并追加到分块缓冲，不再保留全部条目字符串，
     * 最终文本只是各分块的顺序拼接，输出与整体拼装字节层面等价。
     */
    suspend fun build(
        entries: List<PacketLogEntry>,
        nowMillis: Long,
        zoneOffsetMinutes: Int,
        options: PacketLogExportOptions = PacketLogExportOptions(),
        filter: PacketLogFilter = PacketLogFilter(),
        format: PacketLogExportFormat = PacketLogExportFormat.TEXT,
        onProgress: (PacketLogExportProgress) -> Unit = {},
    ): PacketLogExportResult {
        val computed =
            compute(
                entries = entries,
                filter = filter,
                nowMillis = nowMillis,
                zoneOffsetMinutes = zoneOffsetMinutes,
                format = format,
                options = options,
                onProgress = onProgress,
                assembleOutput = true,
            )
        return PacketLogExportResult(
            text = computed.text,
            fileName = fileName(nowMillis, zoneOffsetMinutes, format),
            format = format,
            entryCount = computed.selectedCount,
            sourceCount = entries.size,
            matchedCount = computed.matchedCount,
            truncated = computed.truncated,
            truncationTarget = if (computed.truncated) PacketLogTruncationTarget.OLDEST else null,
            byteSize = computed.byteSize,
            notice = computed.notice,
            peakBufferChars = computed.peakBufferChars,
        )
    }

    /** 导出前预估：不产生最终文本，只走同一套筛选与上限逻辑，保证与实际导出口径一致。 */
    suspend fun estimate(
        entries: List<PacketLogEntry>,
        nowMillis: Long,
        zoneOffsetMinutes: Int,
        options: PacketLogExportOptions = PacketLogExportOptions(),
        filter: PacketLogFilter = PacketLogFilter(),
        format: PacketLogExportFormat = PacketLogExportFormat.TEXT,
    ): PacketLogExportEstimate {
        val computed =
            compute(
                entries = entries,
                filter = filter,
                nowMillis = nowMillis,
                zoneOffsetMinutes = zoneOffsetMinutes,
                format = format,
                options = options,
                onProgress = {},
                assembleOutput = false,
            )
        return PacketLogExportEstimate(
            sourceCount = entries.size,
            matchedCount = computed.matchedCount,
            estimatedEntries = computed.selectedCount,
            estimatedBytes = computed.byteSize,
            truncated = computed.truncated,
            truncationTarget = if (computed.truncated) PacketLogTruncationTarget.OLDEST else null,
            notice = computed.notice,
        )
    }

    private data class Computed(
        val text: String,
        val selectedCount: Int,
        val matchedCount: Int,
        val truncated: Boolean,
        val byteSize: Int,
        val notice: String?,
        val peakBufferChars: Int = 0,
    )

    private suspend fun compute(
        entries: List<PacketLogEntry>,
        filter: PacketLogFilter,
        nowMillis: Long,
        zoneOffsetMinutes: Int,
        format: PacketLogExportFormat,
        options: PacketLogExportOptions,
        onProgress: (PacketLogExportProgress) -> Unit,
        assembleOutput: Boolean,
    ): Computed {
        currentCoroutineContext().ensureActive()

        val matched = collectMatchedEntries(entries, filter, onProgress)
        if (matched.isEmpty()) {
            onProgress(PacketLogExportProgress(1, 1, PacketLogExportPhase.ASSEMBLING))
            return Computed("", 0, 0, false, 0, null)
        }

        matched.sortWith(
            compareByDescending<PacketLogEntry> { it.timestampMillis }.thenByDescending { it.id },
        )
        currentCoroutineContext().ensureActive()

        val renderer = rendererFor(format, nowMillis, zoneOffsetMinutes, filter, entries.size, matched.size, options)
        val budget = budgetEntries(matched, renderer, options, onProgress)

        if (budget.selectedCount == 0) {
            onProgress(PacketLogExportProgress(1, 1, PacketLogExportPhase.ASSEMBLING))
            return Computed(
                text = "",
                selectedCount = 0,
                matchedCount = matched.size,
                truncated = true,
                byteSize = 0,
                notice = "大小上限过小，无法容纳任何条目，请提高上限后重试",
            )
        }

        onProgress(PacketLogExportProgress(1, 1, PacketLogExportPhase.ASSEMBLING))
        yield()
        currentCoroutineContext().ensureActive()

        // 预估只走预算，不拼装文本；字节数由头部/条目/分隔符/尾部的字节数求和得出，与拼装结果一致。
        return if (assembleOutput) {
            assembleComputed(matched, renderer, budget)
        } else {
            Computed(
                text = "",
                selectedCount = budget.selectedCount,
                matchedCount = matched.size,
                truncated = budget.truncated,
                byteSize = budget.total,
                notice = null,
            )
        }
    }

    /** 按过滤条件收集条目，并按固定间隔让出线程并上报进度。 */
    private suspend fun collectMatchedEntries(
        entries: List<PacketLogEntry>,
        filter: PacketLogFilter,
        onProgress: (PacketLogExportProgress) -> Unit,
    ): MutableList<PacketLogEntry> {
        val matched = ArrayList<PacketLogEntry>(entries.size)
        entries.forEachIndexed { index, entry ->
            if (filter.matches(entry)) matched += entry
            if (index % PROGRESS_YIELD_INTERVAL == 0 || index == entries.lastIndex) {
                currentCoroutineContext().ensureActive()
                onProgress(PacketLogExportProgress(index + 1, entries.size, PacketLogExportPhase.FILTERING))
                yield()
            }
        }
        return matched
    }

    /** 预算阶段只保留每条条目的字节数，不保留渲染结果，避免整份导出内容常驻内存。 */
    private suspend fun budgetEntries(
        matched: List<PacketLogEntry>,
        renderer: FormatRenderer,
        options: PacketLogExportOptions,
        onProgress: (PacketLogExportProgress) -> Unit,
    ): ExportBudget {
        val sizes = IntArray(minOf(matched.size, options.maxEntries))
        var selectedCount = 0
        var bodyBytes = 0
        var truncated = false

        for ((index, entry) in matched.withIndex()) {
            if (selectedCount >= options.maxEntries) {
                truncated = true
                break
            }
            val blockBytes = renderer.entry(entry).encodeToByteArray().size
            val separatorBytes = if (selectedCount == 0) 0 else renderer.separatorBytes
            val count = selectedCount + 1
            // 若不是最后一条，停下就意味着截断，预算时按带“已截断”标注的头部计算。
            val willTruncate = count < matched.size
            val projected =
                renderer.headerBytes(count, willTruncate) +
                    bodyBytes + separatorBytes + blockBytes + renderer.footerBytes
            if (projected > options.maxBytes) {
                truncated = true
                break
            }
            sizes[selectedCount] = blockBytes
            selectedCount = count
            bodyBytes += separatorBytes + blockBytes

            if (index % PROGRESS_YIELD_INTERVAL == 0 || index == matched.lastIndex) {
                currentCoroutineContext().ensureActive()
                onProgress(PacketLogExportProgress(index + 1, matched.size, PacketLogExportPhase.RENDERING))
                yield()
            }
        }

        val finalTruncated = truncated || selectedCount < matched.size
        val total = renderer.headerBytes(selectedCount, finalTruncated) + bodyBytes + renderer.footerBytes
        return trimToFit(renderer, options, sizes, selectedCount, bodyBytes, total, finalTruncated)
    }

    /** 头部会随导出的条数/截断标记变化，极端情况下需要继续从最旧一端丢弃。 */
    private suspend fun trimToFit(
        renderer: FormatRenderer,
        options: PacketLogExportOptions,
        sizes: IntArray,
        initialCount: Int,
        initialBodyBytes: Int,
        initialTotal: Int,
        initialTruncated: Boolean,
    ): ExportBudget {
        var selectedCount = initialCount
        var bodyBytes = initialBodyBytes
        var total = initialTotal
        var truncated = initialTruncated
        while (selectedCount > 0 && total > options.maxBytes) {
            val removedBytes =
                sizes[selectedCount - 1] +
                    if (selectedCount - 1 == 0) 0 else renderer.separatorBytes
            selectedCount--
            bodyBytes -= removedBytes
            truncated = true
            total = renderer.headerBytes(selectedCount, truncated) + bodyBytes + renderer.footerBytes
            currentCoroutineContext().ensureActive()
        }
        return ExportBudget(selectedCount, total, truncated)
    }

    private suspend fun assembleComputed(
        matched: List<PacketLogEntry>,
        renderer: FormatRenderer,
        budget: ExportBudget,
    ): Computed {
        val builder = ChunkedTextBuilder()
        builder.append(renderer.header(budget.selectedCount, budget.truncated))
        for (index in 0 until budget.selectedCount) {
            if (index > 0) builder.append(renderer.separator)
            builder.append(renderer.entry(matched[index]))
            if (index % PROGRESS_YIELD_INTERVAL == 0 || index == budget.selectedCount - 1) {
                currentCoroutineContext().ensureActive()
                yield()
            }
        }
        builder.append(renderer.footer)

        return Computed(
            text = builder.assemble(),
            selectedCount = budget.selectedCount,
            matchedCount = matched.size,
            truncated = budget.truncated,
            byteSize = budget.total,
            notice = null,
            peakBufferChars = builder.peakBufferedChars,
        )
    }

    private data class ExportBudget(
        val selectedCount: Int,
        val total: Int,
        val truncated: Boolean,
    )

    private fun rendererFor(
        format: PacketLogExportFormat,
        nowMillis: Long,
        zoneOffsetMinutes: Int,
        filter: PacketLogFilter,
        sourceCount: Int,
        matchedCount: Int,
        options: PacketLogExportOptions,
    ): FormatRenderer =
        when (format) {
            PacketLogExportFormat.TEXT ->
                TextRenderer(
                    nowMillis,
                    zoneOffsetMinutes,
                    filter,
                    sourceCount,
                    matchedCount,
                    options,
                )

            PacketLogExportFormat.JSON ->
                JsonRenderer(
                    nowMillis,
                    zoneOffsetMinutes,
                    filter,
                    sourceCount,
                    matchedCount,
                    options,
                )

            PacketLogExportFormat.CSV -> CsvRenderer(zoneOffsetMinutes, options)
        }

    /** 文件名带本地时间与格式后缀，避免多次导出互相覆盖。 */
    fun fileName(
        nowMillis: Long,
        zoneOffsetMinutes: Int,
        format: PacketLogExportFormat = PacketLogExportFormat.TEXT,
    ): String {
        val parts = localParts(nowMillis, zoneOffsetMinutes)
        return "valhalla-packet-log-" +
            parts.year.toString().padStart(4, '0') +
            parts.month.toString().padStart(2, '0') +
            parts.day.toString().padStart(2, '0') + "-" +
            parts.hour.toString().padStart(2, '0') +
            parts.minute.toString().padStart(2, '0') +
            parts.second.toString().padStart(2, '0') + "." + format.extension
    }

    /**
     * 重名冲突处理：若 [fileName] 已存在，依次尝试 `base-1.ext`、`base-2.ext`…
     * 比较忽略大小写（iOS / Android 文件系统默认大小写不敏感）。
     */
    fun resolveFileName(
        fileName: String,
        existingNames: Collection<String>,
    ): String {
        fun taken(candidate: String) = existingNames.any { it.equals(candidate, ignoreCase = true) }
        if (!taken(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base-$index$extension"
            if (!taken(candidate)) return candidate
            index++
        }
    }
}

/** 各格式的头部/分隔符/条目/尾部片段；字节预算与截断逻辑在 [PacketLogExporter.compute] 中统一。 */
private interface FormatRenderer {
    val separator: String
    val separatorBytes: Int
    val footer: String
    val footerBytes: Int

    fun header(
        exported: Int,
        truncated: Boolean,
    ): String

    fun headerBytes(
        exported: Int,
        truncated: Boolean,
    ): Int = header(exported, truncated).encodeToByteArray().size

    fun entry(entry: PacketLogEntry): String
}

/**
 * 分块文本缓冲：追加内容按 [chunkChars] 切块，任何时刻待写入的单块不超过该值。
 *
 * 导出时条目逐条序列化后写入，不会像 `joinToString` 那样同时持有「全部条目字符串 + 完整结果」；
 * [peakBufferedChars] 记录单块达到过的最大字符数，可直接断言峰值不随条目数增长。
 */
internal class ChunkedTextBuilder(
    private val chunkChars: Int = PacketLogExporter.STREAM_CHUNK_CHARS,
) {
    init {
        require(chunkChars > 0) { "chunkChars 必须为正数" }
    }

    private val chunks = ArrayList<String>()
    private val current = StringBuilder(chunkChars)

    /** 单块缓冲达到过的最大字符数；恒 ≤ [chunkChars]。 */
    var peakBufferedChars: Int = 0
        private set

    fun append(text: String) {
        if (text.isEmpty()) return
        var start = 0
        while (start < text.length) {
            val room = chunkChars - current.length
            val end = minOf(start + room, text.length)
            current.append(text, start, end)
            if (current.length > peakBufferedChars) peakBufferedChars = current.length
            start = end
            if (current.length == chunkChars) flush()
        }
    }

    private fun flush() {
        if (current.isEmpty()) return
        chunks += current.toString()
        current.clear()
    }

    /** 顺序拼接所有分块；拼接前的待写入缓冲始终不超过 [chunkChars]。 */
    fun assemble(): String {
        flush()
        val builder = StringBuilder(chunks.sumOf { it.length })
        chunks.forEach { builder.append(it) }
        return builder.toString()
    }
}

private class TextRenderer(
    private val nowMillis: Long,
    private val zoneOffsetMinutes: Int,
    private val filter: PacketLogFilter,
    private val sourceCount: Int,
    private val matchedCount: Int,
    private val options: PacketLogExportOptions,
) : FormatRenderer {
    override val separator: String = "\n"
    override val separatorBytes: Int = 1
    override val footer: String = "\n"
    override val footerBytes: Int = 1

    override fun entry(entry: PacketLogEntry): String = formatTextEntry(entry, nowMillis, zoneOffsetMinutes, options.maxHexCharsPerEntry)

    override fun header(
        exported: Int,
        truncated: Boolean,
    ): String =
        buildString {
            append("ValhallaApp 报文日志\n")
            append("导出时间：")
            append(formatAbsoluteTime(nowMillis, zoneOffsetMinutes))
            append('\n')
            append("筛选：")
            append(filterSummary(filter, zoneOffsetMinutes))
            append('\n')
            append("来源：")
            append(sourceCount)
            append(" 条\n")
            append("条目：")
            append(exported)
            append(" / ")
            append(matchedCount)
            if (truncated) append("（已按上限截断，仅保留最新条目，截断端=最旧）")
            append('\n')
            append(
                "上限：${options.maxEntries} 条 / ${options.maxBytes} 字节 / 单条 HEX " +
                    "${options.maxHexCharsPerEntry} 字符\n",
            )
            append("字段：时间（绝对 | 相对） 方向 [RESPONSE/NOTIFY] seq 命令 / 摘要 / 错误 / HEX")
            append('\n')
        }
}

private class JsonRenderer(
    private val nowMillis: Long,
    private val zoneOffsetMinutes: Int,
    private val filter: PacketLogFilter,
    private val sourceCount: Int,
    private val matchedCount: Int,
    private val options: PacketLogExportOptions,
) : FormatRenderer {
    override val separator: String = ",\n"
    override val separatorBytes: Int = 2
    override val footer: String = "\n  ]\n}\n"
    override val footerBytes: Int = footer.encodeToByteArray().size

    override fun entry(entry: PacketLogEntry): String =
        buildString {
            append("    {")
            append("\"id\":").append(entry.id)
            append(",\"timestampMillis\":").append(entry.timestampMillis)
            append(",\"timestamp\":").append(jsonString(formatAbsoluteTime(entry.timestampMillis, zoneOffsetMinutes)))
            append(",\"relativeTime\":").append(jsonString(formatRelativeTime(entry.timestampMillis, nowMillis)))
            append(",\"timezoneOffsetMinutes\":").append(zoneOffsetMinutes)
            append(",\"direction\":").append(jsonString(entry.direction.name))
            append(",\"command\":").append(jsonString(entry.command.name))
            append(",\"sequence\":").append(entry.sequence)
            append(",\"response\":").append(entry.isResponse)
            append(",\"notification\":").append(entry.isNotification)
            val hex = hexSlice(entry.hex, options.maxHexCharsPerEntry)
            append(",\"hex\":").append(jsonString(hex.text))
            append(",\"hexOmittedChars\":").append(hex.omittedChars)
            append(",\"summary\":").append(jsonString(entry.summary))
            append(",\"error\":").append(entry.error?.let { jsonString(it) } ?: "null")
            append('}')
        }

    override fun header(
        exported: Int,
        truncated: Boolean,
    ): String =
        buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"exportedAt\": ").append(jsonString(formatAbsoluteTime(nowMillis, zoneOffsetMinutes))).append(",\n")
            append("  \"exportedAtMillis\": ").append(nowMillis).append(",\n")
            append("  \"timezoneOffsetMinutes\": ").append(zoneOffsetMinutes).append(",\n")
            append("  \"sourceCount\": ").append(sourceCount).append(",\n")
            append("  \"matchedCount\": ").append(matchedCount).append(",\n")
            append("  \"exportedCount\": ").append(exported).append(",\n")
            append("  \"truncated\": ").append(truncated).append(",\n")
            append("  \"truncatedEnd\": ")
                .append(if (truncated) jsonString(PacketLogTruncationTarget.OLDEST.name) else "null")
                .append(",\n")
            append("  \"limits\": {")
            append("\"maxEntries\": ").append(options.maxEntries)
            append(", \"maxBytes\": ").append(options.maxBytes)
            append(", \"maxHexCharsPerEntry\": ").append(options.maxHexCharsPerEntry)
            append("},\n")
            append("  \"filter\": {")
            append("\"commands\": [")
            append(
                filter.effectiveCommands
                    .map { it.name }
                    .sorted()
                    .joinToString(",") { jsonString(it) },
            )
            append("], \"errorsOnly\": ").append(filter.errorsOnly)
            append(", \"keyword\": ").append(jsonString(filter.keyword.trim()))
            append(", \"fromMillis\": ").append(filter.fromMillis?.toString() ?: "null")
            append(", \"toMillis\": ").append(filter.toMillis?.toString() ?: "null")
            append("},\n")
            append("  \"entries\": [\n")
        }
}

private class CsvRenderer(
    private val zoneOffsetMinutes: Int,
    private val options: PacketLogExportOptions,
) : FormatRenderer {
    override val separator: String = "\n"
    override val separatorBytes: Int = 1
    override val footer: String = "\n"
    override val footerBytes: Int = 1

    private val headerLine: String =
        "id,timestampMillis,timestamp,timezoneOffsetMinutes,direction,command,sequence," +
            "response,notification,hex,hexOmittedChars,summary,error\n"

    override fun header(
        exported: Int,
        truncated: Boolean,
    ): String = headerLine

    override fun entry(entry: PacketLogEntry): String {
        val hex = hexSlice(entry.hex, options.maxHexCharsPerEntry)
        return listOf(
            entry.id.toString(),
            entry.timestampMillis.toString(),
            formatAbsoluteTime(entry.timestampMillis, zoneOffsetMinutes),
            zoneOffsetMinutes.toString(),
            entry.direction.name,
            entry.command.name,
            entry.sequence.toString(),
            entry.isResponse.toString(),
            entry.isNotification.toString(),
            hex.text,
            hex.omittedChars.toString(),
            entry.summary,
            entry.error ?: "",
        ).joinToString(",") { csvField(it) }
    }
}

private fun formatTextEntry(
    entry: PacketLogEntry,
    nowMillis: Long,
    zoneOffsetMinutes: Int,
    maxHexChars: Int,
): String =
    buildString {
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
        val hex = hexSlice(entry.hex, maxHexChars)
        append(hex.text)
        if (hex.omittedChars > 0) {
            append("…（省略 ")
            append(hex.omittedChars)
            append(" 字符）")
        }
    }

private data class HexSlice(
    val text: String,
    val omittedChars: Int,
)

private fun hexSlice(
    hex: String,
    maxChars: Int,
): HexSlice =
    if (hex.length > maxChars) {
        HexSlice(hex.substring(0, maxChars), hex.length - maxChars)
    } else {
        HexSlice(hex, 0)
    }

private fun filterSummary(
    filter: PacketLogFilter,
    zoneOffsetMinutes: Int,
): String {
    val parts = mutableListOf<String>()
    val commands = filter.effectiveCommands
    if (commands.isNotEmpty()) {
        parts += "命令=" + commands.map { it.name }.sorted().joinToString(",")
    }
    if (filter.errorsOnly) parts += "仅错误"
    val keyword = filter.keyword.trim()
    if (keyword.isNotEmpty()) parts += "关键字=\"$keyword\""
    if (filter.fromMillis != null || filter.toMillis != null) {
        parts += "时间=" +
            (filter.fromMillis?.let { formatAbsoluteTime(it, zoneOffsetMinutes) } ?: "不限") +
            " ~ " +
            (filter.toMillis?.let { formatAbsoluteTime(it, zoneOffsetMinutes) } ?: "不限")
    }
    return if (parts.isEmpty()) "无" else parts.joinToString("；")
}

/** JSON 字符串转义：引号、反斜杠、换行/回车/制表符与控制字符。 */
private fun jsonString(value: String): String =
    buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (char.code < FIRST_PRINTABLE_ASCII_CODE) {
                        append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
            }
        }
        append('"')
    }

/** CSV 字段转义：包含逗号、引号、换行或回车时用双引号包裹，内部引号加倍。 */
private fun csvField(value: String): String {
    val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuote) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}

/** 单条日志的本地化文本，供「复制单条」使用；格式与导出条目一致。 */
fun PacketLogEntry.formatForCopy(
    nowMillis: Long,
    zoneOffsetMinutes: Int,
): String =
    buildString {
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
fun formatAbsoluteTime(
    epochMillis: Long,
    zoneOffsetMinutes: Int,
): String {
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

/**
 * 解析本地时间输入 `YYYY-MM-DD HH:mm:ss`（可带 `.SSS`，也允许 `T` 分隔），
 * 结合时区偏移转换为 epoch 毫秒；格式或日期非法时返回 null。
 * 用于日志页的时间范围输入，保证边界与 [formatAbsoluteTime] 同一口径。
 */
fun parseLocalDateTimeMillis(
    input: String,
    zoneOffsetMinutes: Int,
): Long? {
    val text = input.trim().replace('T', ' ')
    if (text.isEmpty()) return null
    val parts = text.split(' ', limit = 2).filter { it.isNotEmpty() }
    if (parts.size != 2) return null

    val date = parseDateParts(parts[0]) ?: return null
    val millisOfDay = parseMillisOfDay(parts[1]) ?: return null

    val days = daysFromCivil(date.first, date.second, date.third)
    val roundTrip = civilFromDays(days)
    if (roundTrip.first != date.first || roundTrip.second != date.second || roundTrip.third != date.third) return null

    return days * MILLIS_PER_DAY + millisOfDay - zoneOffsetMinutes.toLong() * MILLIS_PER_MINUTE
}

/** 解析 `YYYY-MM-DD`，字段范围或日历日期非法时返回 null。 */
private fun parseDateParts(text: String): Triple<Int, Int, Int>? {
    val date = text.split('-')
    if (date.size != 3) return null
    val year = date[0].toIntOrNull() ?: return null
    val month = date[1].toIntOrNull() ?: return null
    val day = date[2].toIntOrNull() ?: return null
    if (month !in 1..MONTHS_PER_YEAR || day !in 1..MAX_DAYS_PER_MONTH) return null
    return Triple(year, month, day)
}

/** 解析 `HH:mm[:ss[.SSS]]` 为当日毫秒数；格式或字段非法时返回 null。 */
private fun parseMillisOfDay(text: String): Long? {
    val time = text.split(':')
    if (time.size !in 2..3) return null
    val hour = time[0].toIntOrNull() ?: return null
    val minute = time[1].toIntOrNull() ?: return null
    if (hour !in 0..MAX_HOUR_OF_DAY || minute !in 0..MAX_MINUTE_OF_HOUR) return null

    val clock = parseSecondsAndMillis(time.getOrNull(2)) ?: return null
    return hour * MILLIS_PER_HOUR + minute * MILLIS_PER_MINUTE + clock.first * MILLIS_PER_SECOND + clock.second
}

/** 解析秒与可选毫秒；未提供秒时按 0 处理。 */
private fun parseSecondsAndMillis(text: String?): Pair<Int, Int>? {
    if (text == null) return 0 to 0
    val dot = text.indexOf('.')
    val secondsText = if (dot >= 0) text.substring(0, dot) else text
    val second = secondsText.toIntOrNull() ?: return null
    if (second !in 0..MAX_SECOND_OF_MINUTE) return null
    if (dot < 0) return second to 0
    val fraction = text.substring(dot + 1)
    if (fraction.isEmpty() || fraction.any { !it.isDigit() }) return null
    return second to fraction.take(3).padEnd(3, '0').toInt()
}

/**
 * 相对当前时间的可读描述；未来时间会标注方向，避免导出/查看时钟漂移时出现负数。
 *
 * 默认中文标签；英文界面传入 `justNow = "just now"`、`agoSuffix = " ago"`、
 * `futurePrefix = "in "` 即可复用同一套时长计算，避免两套时间格式化实现。
 */
fun formatRelativeTime(
    entryMillis: Long,
    nowMillis: Long,
    justNow: String = "刚刚",
    agoSuffix: String = "前",
    futurePrefix: String = "未来 ",
): String {
    val delta = nowMillis - entryMillis
    return when {
        delta < -MILLIS_PER_SECOND -> "$futurePrefix${formatDuration(-delta)}"
        delta < 1_000L -> justNow
        else -> "${formatDuration(delta)}$agoSuffix"
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000L
    return when {
        totalSeconds < SECONDS_PER_MINUTE -> "${totalSeconds}s"
        totalSeconds < SECONDS_PER_HOUR ->
            "${totalSeconds / SECONDS_PER_MINUTE}m${(totalSeconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}s"

        else ->
            "${totalSeconds / SECONDS_PER_HOUR}h${((totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toString().padStart(2, '0')}m"
    }
}

private fun formatOffset(offsetMinutes: Int): String {
    val sign = if (offsetMinutes < 0) '-' else '+'
    val absolute = if (offsetMinutes < 0) -offsetMinutes else offsetMinutes
    return buildString {
        append(sign)
        append((absolute / MINUTES_PER_HOUR).toString().padStart(2, '0'))
        append(':')
        append((absolute % MINUTES_PER_HOUR).toString().padStart(2, '0'))
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

private fun localParts(
    epochMillis: Long,
    zoneOffsetMinutes: Int,
): LocalParts {
    val shifted = epochMillis + zoneOffsetMinutes.toLong() * MILLIS_PER_MINUTE
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

private fun floorDiv(
    value: Long,
    divisor: Long,
): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && (remainder < 0) != (divisor < 0)) quotient - 1 else quotient
}

/** Howard Hinnant 的 civil_from_days，用于在 commonMain 中把天数转换为公历日期。 */
private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + CIVIL_EPOCH_DAY_OFFSET
    val era = (if (z >= 0) z else z - CIVIL_ERA_DAY_ADJUSTMENT) / DAYS_PER_ERA
    val dayOfEra = z - era * DAYS_PER_ERA
    val yearOfEra =
        (
            dayOfEra - dayOfEra / DAYS_PER_FOUR_YEARS_CYCLE + dayOfEra / DAYS_PER_CENTURY_CYCLE -
                dayOfEra / CIVIL_ERA_DAY_ADJUSTMENT
        ) / DAYS_PER_YEAR
    val year =
        yearOfEra + era * YEARS_PER_ERA
    val dayOfYear = dayOfEra - (DAYS_PER_YEAR * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val mp = (MONTH_PHASE_MULTIPLIER * dayOfYear + 2L) / MONTH_PHASE_DIVISOR
    val day = dayOfYear - (MONTH_PHASE_DIVISOR * mp + 2L) / MONTH_PHASE_MULTIPLIER + 1L
    val month = if (mp < MONTH_PHASE_THRESHOLD) mp + 3L else mp - MONTH_PHASE_OVERFLOW
    return Triple(
        (if (month <= 2L) year + 1L else year).toInt(),
        month.toInt(),
        day.toInt(),
    )
}

/** Hinnant 的 days_from_civil：公历日期 → 距 1970-01-01 的天数。 */
private fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - YEARS_PER_ERA_MINUS_ONE) / YEARS_PER_ERA_COUNT
    val yearOfEra = y - era * YEARS_PER_ERA_COUNT
    val monthShift = if (month > 2) month - 3 else month + WINTER_MONTH_SHIFT
    val dayOfYear = (MONTH_PHASE_DIVISOR * monthShift + 2L) / MONTH_PHASE_MULTIPLIER + day - 1L
    val dayOfEra = yearOfEra.toLong() * DAYS_PER_YEAR + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era.toLong() * DAYS_PER_ERA + dayOfEra - CIVIL_EPOCH_DAY_OFFSET
}
