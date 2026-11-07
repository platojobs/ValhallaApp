package com.valhalla.core.diagnostics

import com.valhalla.core.logging.ConnectionQualityStats
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogRedaction
import com.valhalla.core.logging.SensitiveTextRedaction
import com.valhalla.core.logging.formatAbsoluteTime
import com.valhalla.core.logging.formatForCopy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 报告输出格式；两者都走既有保存/分享通道。 */
enum class DiagnosticReportFormat(
    val extension: String,
    val mimeType: String,
) {
    MARKDOWN("md", "text/markdown"),
    TEXT("txt", "text/plain"),
}

/** 报告包含的小节；用于生成前的「将包含哪些内容」预览。 */
enum class DiagnosticReportSection {
    DEVICE,
    FIRMWARE,
    BATTERY,
    CONFIG,
    PARAM,
    ERRORS,
    QUALITY,
    OTA,
    PACKET_LOG,
}

/** 一项配置/参数值；[status] 用于标注「不支持 / 读取失败」等非正常值的情况。 */
data class DiagnosticReportValue(
    val label: String,
    val value: String?,
    val status: String? = null,
)

/** 一条最近错误；来自连接质量统计或业务层的失败记录。 */
data class DiagnosticReportError(
    val timestampMillis: Long,
    val command: String,
    val detail: String,
    val errorCode: String? = null,
)

/** OTA 历史摘要项，字段与 `OtaHistoryRecord` 对齐但不直接依赖档案类型，便于测试构造。 */
data class DiagnosticReportOta(
    val firmwareVersion: String,
    val finishedAtEpochMillis: Long,
    val outcome: String,
    val durationMillis: Long,
    val errorCode: String? = null,
    val sentBytes: Int = 0,
    val totalBytes: Int = 0,
)

/** 生成诊断报告所需的全部输入；每一项都来自本机真实数据，不做任何估算或补造。 */
data class DiagnosticReportInput(
    val generatedAtEpochMillis: Long,
    val zoneOffsetMinutes: Int,
    val appVersion: String,
    val platform: String,
    val deviceId: String,
    val deviceModel: String? = null,
    val firmwareVersion: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val configValues: List<DiagnosticReportValue> = emptyList(),
    val paramValues: List<DiagnosticReportValue> = emptyList(),
    val recentErrors: List<DiagnosticReportError> = emptyList(),
    val quality: ConnectionQualityStats? = null,
    val otaHistory: List<DiagnosticReportOta> = emptyList(),
    val packetLog: List<PacketLogEntry> = emptyList(),
)

/** 生成参数。 */
data class DiagnosticReportOptions(
    val format: DiagnosticReportFormat = DiagnosticReportFormat.MARKDOWN,
    val recentErrorLimit: Int = 5,
    val otaLimit: Int = 5,
    val packetLogLimit: Int = 20,
)

/** 生成前预览：包含哪些小节、每节条目数、估算体积。 */
data class DiagnosticReportEstimate(
    val sections: Map<DiagnosticReportSection, Int>,
    val estimatedBytes: Int,
) {
    val sectionCount: Int get() = sections.size
    val empty: Boolean get() = estimatedBytes <= 0
}

enum class DiagnosticReportProgressPhase {
    RENDERING,
}

data class DiagnosticReportProgress(
    val phase: DiagnosticReportProgressPhase,
    val processedSections: Int,
    val totalSections: Int,
) {
    val fraction: Float
        get() = if (totalSections <= 0) 1f else (processedSections.toFloat() / totalSections).coerceIn(0f, 1f)
}

/** 生成结果；[text] 已整体脱敏，[byteSize] 为 UTF-8 字节数。 */
data class DiagnosticReportResult(
    val text: String,
    val format: DiagnosticReportFormat,
    val byteSize: Int,
    val sectionCounts: Map<DiagnosticReportSection, Int>,
    val packetLogCount: Int,
)

/**
 * 一键诊断报告生成器。
 *
 * 口径与隐私约束：
 * - 内容只来自传入的真实快照（设备信息、档案、报文日志、连接质量统计），缺失的项标注「暂无数据」；
 * - 所有自由文本在写入报告前统一经 [SensitiveTextRedaction]；设备 id 只输出不可逆短哈希；
 * - 报文 HEX 逐条复用 `formatForCopy` 后再次脱敏，避免日志窗口外的二次泄漏；
 * - 生成是 suspend 的，逐小节检查协程取消，UI 可显示进度并中途取消；取消不返回部分结果。
 */
object DiagnosticReportComposer {
    suspend fun build(
        input: DiagnosticReportInput,
        options: DiagnosticReportOptions = DiagnosticReportOptions(),
        onProgress: (DiagnosticReportProgress) -> Unit = {},
    ): DiagnosticReportResult {
        val rendered = render(input, options, onProgress)
        return DiagnosticReportResult(
            text = rendered.text,
            format = options.format,
            byteSize = rendered.text.encodeToByteArray().size,
            sectionCounts = rendered.sections,
            packetLogCount = rendered.packetLogCount,
        )
    }

    /** 生成前预览；与 [build] 同一套渲染逻辑，保证「预览的体积」与最终结果一致。 */
    fun estimate(
        input: DiagnosticReportInput,
        options: DiagnosticReportOptions = DiagnosticReportOptions(),
    ): DiagnosticReportEstimate {
        val rendered = renderSync(input, options)
        return DiagnosticReportEstimate(
            sections = rendered.sections,
            estimatedBytes = rendered.text.encodeToByteArray().size,
        )
    }

    private data class Rendered(
        val text: String,
        val sections: Map<DiagnosticReportSection, Int>,
        val packetLogCount: Int,
    )

    private suspend fun render(
        input: DiagnosticReportInput,
        options: DiagnosticReportOptions,
        onProgress: (DiagnosticReportProgress) -> Unit,
    ): Rendered {
        val total = DiagnosticReportSection.entries.size
        var processed = 0
        val sections = linkedMapOf<DiagnosticReportSection, Int>()

        fun mark(
            section: DiagnosticReportSection,
            count: Int,
        ) {
            sections[section] = count
            processed++
            onProgress(DiagnosticReportProgress(DiagnosticReportProgressPhase.RENDERING, processed, total))
        }

        val packetEntries = selectedPacketLog(input, options)
        val text = StringBuilder()
        appendHeader(text, input, options)
        currentCoroutineContext().ensureActive()

        appendDevice(text, input)
        mark(DiagnosticReportSection.DEVICE, 1)
        currentCoroutineContext().ensureActive()

        appendFirmware(text, input)
        mark(DiagnosticReportSection.FIRMWARE, if (input.firmwareVersion == null) 0 else 1)
        currentCoroutineContext().ensureActive()

        appendBattery(text, input)
        mark(DiagnosticReportSection.BATTERY, if (input.batteryPercent == null) 0 else 1)
        currentCoroutineContext().ensureActive()

        appendValues(text, "配置当前值", input.configValues)
        mark(DiagnosticReportSection.CONFIG, input.configValues.size)
        currentCoroutineContext().ensureActive()

        appendValues(text, "参数当前值", input.paramValues)
        mark(DiagnosticReportSection.PARAM, input.paramValues.size)
        currentCoroutineContext().ensureActive()

        val errors = input.recentErrors.take(options.recentErrorLimit)
        appendErrors(text, input, errors)
        mark(DiagnosticReportSection.ERRORS, errors.size)
        currentCoroutineContext().ensureActive()

        appendQuality(text, input)
        mark(DiagnosticReportSection.QUALITY, if (input.quality == null) 0 else 1)
        currentCoroutineContext().ensureActive()

        val ota = input.otaHistory.take(options.otaLimit)
        appendOta(text, input, ota)
        mark(DiagnosticReportSection.OTA, ota.size)
        currentCoroutineContext().ensureActive()

        appendPacketLog(text, input, packetEntries)
        mark(DiagnosticReportSection.PACKET_LOG, packetEntries.size)

        return Rendered(
            text = text.toString(),
            sections = sections,
            packetLogCount = packetEntries.size,
        )
    }

    private fun renderSync(
        input: DiagnosticReportInput,
        options: DiagnosticReportOptions,
    ): Rendered {
        val sections = linkedMapOf<DiagnosticReportSection, Int>()
        val packetEntries = selectedPacketLog(input, options)
        val errors = input.recentErrors.take(options.recentErrorLimit)
        val ota = input.otaHistory.take(options.otaLimit)
        val text = StringBuilder()
        appendHeader(text, input, options)
        appendDevice(text, input)
        sections[DiagnosticReportSection.DEVICE] = 1
        appendFirmware(text, input)
        sections[DiagnosticReportSection.FIRMWARE] = if (input.firmwareVersion == null) 0 else 1
        appendBattery(text, input)
        sections[DiagnosticReportSection.BATTERY] = if (input.batteryPercent == null) 0 else 1
        appendValues(text, "配置当前值", input.configValues)
        sections[DiagnosticReportSection.CONFIG] = input.configValues.size
        appendValues(text, "参数当前值", input.paramValues)
        sections[DiagnosticReportSection.PARAM] = input.paramValues.size
        appendErrors(text, input, errors)
        sections[DiagnosticReportSection.ERRORS] = errors.size
        appendQuality(text, input)
        sections[DiagnosticReportSection.QUALITY] = if (input.quality == null) 0 else 1
        appendOta(text, input, ota)
        sections[DiagnosticReportSection.OTA] = ota.size
        appendPacketLog(text, input, packetEntries)
        sections[DiagnosticReportSection.PACKET_LOG] = packetEntries.size
        return Rendered(text.toString(), sections, packetEntries.size)
    }

    private fun selectedPacketLog(
        input: DiagnosticReportInput,
        options: DiagnosticReportOptions,
    ): List<PacketLogEntry> {
        if (options.packetLogLimit <= 0) return emptyList()
        return input.packetLog
            .sortedWith(compareByDescending<PacketLogEntry> { it.timestampMillis }.thenByDescending { it.id })
            .take(options.packetLogLimit)
    }

    private fun appendHeader(
        text: StringBuilder,
        input: DiagnosticReportInput,
        options: DiagnosticReportOptions,
    ) {
        if (options.format == DiagnosticReportFormat.MARKDOWN) {
            text.append("# Valhalla 设备诊断报告\n\n")
        } else {
            text.append("Valhalla 设备诊断报告\n\n")
        }
        text
            .append("- 生成时间：")
            .append(formatAbsoluteTime(input.generatedAtEpochMillis, input.zoneOffsetMinutes))
            .append('\n')
        text.append("- App 版本：").append(redact(input.appVersion)).append('\n')
        text.append("- 平台：").append(redact(input.platform)).append('\n')
        text.append("- 设备型号：").append(redact(input.deviceModel ?: "未知")).append('\n')
        text.append("- 数据来源：本机日志（报文、设备档案与本次会话）\n\n")
    }

    private fun appendDevice(
        text: StringBuilder,
        input: DiagnosticReportInput,
    ) {
        heading(text, "设备信息")
        line(text, "设备 ID（脱敏）", PacketLogRedaction.hashDeviceId(input.deviceId))
        line(text, "设备名称 / 型号", input.deviceModel ?: "暂无数据")
    }

    private fun appendFirmware(
        text: StringBuilder,
        input: DiagnosticReportInput,
    ) {
        heading(text, "固件版本")
        line(text, "当前固件", input.firmwareVersion ?: "暂无数据（本次会话未读取）")
    }

    private fun appendBattery(
        text: StringBuilder,
        input: DiagnosticReportInput,
    ) {
        heading(text, "电量与充电状态")
        val percent = input.batteryPercent
        if (percent == null) {
            line(text, "电量", "暂无数据")
        } else {
            line(text, "电量", "$percent%")
        }
        line(
            text,
            "充电状态",
            when (input.charging) {
                true -> "充电中"
                false -> "未充电"
                null -> "暂无数据"
            },
        )
    }

    private fun appendValues(
        text: StringBuilder,
        title: String,
        values: List<DiagnosticReportValue>,
    ) {
        heading(text, title)
        if (values.isEmpty()) {
            text.append("暂无数据（能力不支持或未读取）\n\n")
            return
        }
        values.forEach { value ->
            val rendered = value.value ?: value.status ?: "暂无数据"
            val suffix = if (value.value != null && value.status != null) "（${value.status}）" else ""
            line(text, value.label, rendered + suffix)
        }
        text.append('\n')
    }

    private fun appendErrors(
        text: StringBuilder,
        input: DiagnosticReportInput,
        errors: List<DiagnosticReportError>,
    ) {
        heading(text, "最近错误")
        if (errors.isEmpty()) {
            text.append("暂无错误记录\n\n")
            return
        }
        errors.forEach { error ->
            val time = formatAbsoluteTime(error.timestampMillis, input.zoneOffsetMinutes)
            val code = error.errorCode?.let { "（$it）" }.orEmpty()
            text
                .append("- ")
                .append(time)
                .append("  ")
                .append(redact(error.command))
                .append(code)
                .append("：")
                .append(redactInline(error.detail))
                .append('\n')
        }
        text.append('\n')
    }

    private fun appendQuality(
        text: StringBuilder,
        input: DiagnosticReportInput,
    ) {
        heading(text, "连接质量摘要")
        val quality = input.quality
        if (quality == null) {
            text.append("暂无数据（本次会话未产生统计样本）\n\n")
            return
        }
        line(text, "请求 / 应答", "${quality.requests} / ${quality.responses}")
        line(
            text,
            "成功率",
            if (quality.insufficientSample) {
                "样本不足（${quality.sampleCount} 条）"
            } else {
                quality.successRate?.let { "${(it * 100).roundToInt()}%" } ?: "暂无数据"
            },
        )
        line(
            text,
            "平均时延",
            quality.averageLatencyMillis?.let { "${it.roundToLong()} ms" } ?: "暂无数据",
        )
        line(text, "无应答 / 重传", "${quality.noResponses} / ${quality.retransmissions}")
        line(text, "设备错误 / 乱序", "${quality.deviceErrors} / ${quality.outOfOrderResponses}")
        text.append('\n')
    }

    private fun appendOta(
        text: StringBuilder,
        input: DiagnosticReportInput,
        ota: List<DiagnosticReportOta>,
    ) {
        heading(text, "OTA 历史摘要")
        if (ota.isEmpty()) {
            text.append("暂无 OTA 记录\n\n")
            return
        }
        ota.forEach { record ->
            val time = formatAbsoluteTime(record.finishedAtEpochMillis, input.zoneOffsetMinutes)
            val code = record.errorCode?.let { " $it" }.orEmpty()
            val progress =
                if (record.totalBytes > 0) {
                    " ${(record.sentBytes * 100 / record.totalBytes)}%"
                } else {
                    ""
                }
            text
                .append("- ")
                .append(time)
                .append("  ")
                .append(redactInline(record.firmwareVersion))
                .append("  ")
                .append(record.outcome)
                .append(code)
                .append("  耗时 ")
                .append(record.durationMillis)
                .append(" ms")
                .append(progress)
                .append('\n')
        }
        text.append('\n')
    }

    private fun appendPacketLog(
        text: StringBuilder,
        input: DiagnosticReportInput,
        entries: List<PacketLogEntry>,
    ) {
        heading(text, "报文日志（最近 ${entries.size} 条，已脱敏）")
        if (entries.isEmpty()) {
            text.append("暂无报文日志\n\n")
            return
        }
        entries.forEach { entry ->
            val formatted = entry.formatForCopy(input.generatedAtEpochMillis, input.zoneOffsetMinutes)
            text.append(redact(formatted)).append("\n\n")
        }
    }

    private fun heading(
        text: StringBuilder,
        title: String,
    ) {
        text.append("## ").append(title).append("\n\n")
    }

    private fun line(
        text: StringBuilder,
        label: String,
        value: String,
    ) {
        text
            .append("- ")
            .append(redactInline(label))
            .append("：")
            .append(redactInline(value))
            .append('\n')
    }

    private fun redact(value: String): String = SensitiveTextRedaction.redact(value)

    private fun redactInline(value: String): String = SensitiveTextRedaction.redactInline(value)
}
