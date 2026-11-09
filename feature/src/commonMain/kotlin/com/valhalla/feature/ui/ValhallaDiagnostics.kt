package com.valhalla.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import com.valhalla.core.diagnostics.DiagnosticReportComposer
import com.valhalla.core.diagnostics.DiagnosticReportEstimate
import com.valhalla.core.diagnostics.DiagnosticReportFormat
import com.valhalla.core.diagnostics.DiagnosticReportOptions
import com.valhalla.core.diagnostics.DiagnosticReportProgress
import com.valhalla.core.diagnostics.DiagnosticReportResult
import com.valhalla.core.logging.CommandQuality
import com.valhalla.core.logging.ConnectionQualityAnalyzer
import com.valhalla.core.logging.ConnectionQualityStats
import com.valhalla.core.logging.ErrorCodeCount
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.QualityFailure
import com.valhalla.core.metrics.DeviceMetricRepository
import com.valhalla.data.share.currentTimeZoneOffsetMinutes
import com.valhalla.feature.config.ConfigEditorState
import com.valhalla.feature.detail.DeviceDetailState
import com.valhalla.feature.diagnostics.DiagnosticPackageBuilder
import com.valhalla.feature.diagnostics.DiagnosticPackageProgress
import com.valhalla.feature.diagnostics.DiagnosticPackageResult
import com.valhalla.feature.error.LocalErrorReporter
import com.valhalla.feature.error.launchSafely
import com.valhalla.feature.export.FileSaveStatus
import com.valhalla.feature.export.FileSaver
import com.valhalla.feature.export.SaveableFile
import com.valhalla.feature.export.rememberFileSaver
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.param.ParamEditorState
import com.valhalla.feature.theme.DesignTokens
import com.valhalla.feature.theme.statusColors
import com.valhalla.feature.uilogic.formatKey
import com.valhalla.feature.uilogic.label
import com.valhalla.feature.uilogic.reportLabelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 连接质量看板 + 一键诊断报告。
 *
 * 质量统计只读本机报文日志；样本不足时只展示「样本不足（X 条）」，避免把个位数请求包装成结论。
 * 诊断报告在生成前给出小节与体积预估，生成在 Default 调度器执行，可显示进度并中途取消。
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun DiagnosticScreen(
    session: DeviceSession,
    metrics: DeviceMetricRepository,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val packetLog = session.packetLog
    val entries by packetLog.entries.collectAsState()
    val stats = remember(entries) { ConnectionQualityAnalyzer.analyze(entries) }
    val detail by session.detail.state.collectAsState()
    val configState by session.config.state.collectAsState()
    val paramState by session.param.state.collectAsState()
    val zoneOffsetMinutes =
        remember {
            currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds())
        }
    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        DiagnosticHeaderRow(onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
        ) {
            diagnosticReportItems(session, detail, configState, paramState, stats, entries, zoneOffsetMinutes)
            diagnosticPackageItems(session, detail, stats, entries, metrics, zoneOffsetMinutes)
            diagnosticQualityItems(stats, strings, format, zoneOffsetMinutes)
        }
    }
}

private suspend fun saveDiagnosticReportFile(
    fileSaver: FileSaver,
    result: DiagnosticReportResult,
    format: LocaleFormat,
    strings: StringResolver,
): Pair<Boolean, String> {
    val fileName = "valhalla-diagnostic-${Clock.System.now().toEpochMilliseconds()}.${result.format.extension}"
    val save = fileSaver.save(SaveableFile(fileName, result.text, result.format.mimeType))
    val saved = save.status == FileSaveStatus.SAVED
    val message =
        when (save.status) {
            FileSaveStatus.SAVED ->
                strings.t(
                    StringKey.DIAG_REPORT_SAVE_SUCCESS,
                    "name" to fileName,
                    "bytes" to format.number(result.byteSize.toLong()),
                )

            FileSaveStatus.CANCELLED -> strings.t(StringKey.DIAG_REPORT_SAVE_CANCELLED)
            FileSaveStatus.UNAVAILABLE -> strings.t(StringKey.LOGS_SAVE_UNAVAILABLE)
            FileSaveStatus.FAILED ->
                strings.t(
                    StringKey.LOGS_SAVE_FAILED,
                    "detail" to (save.message ?: strings.t(StringKey.UNKNOWN)),
                )
        }
    return saved to message
}

private suspend fun saveDiagnosticPackageFile(
    fileSaver: FileSaver,
    result: DiagnosticPackageResult,
    format: LocaleFormat,
    strings: StringResolver,
): Pair<Boolean, String> {
    val save = fileSaver.save(SaveableFile(result.fileName, result.text, "application/octet-stream"))
    val saved = save.status == FileSaveStatus.SAVED
    val message =
        when (save.status) {
            FileSaveStatus.SAVED ->
                strings.t(
                    StringKey.DIAG_PACKAGE_SAVE_SUCCESS,
                    "name" to result.fileName,
                    "bytes" to format.number(result.byteSize.toLong()),
                )

            FileSaveStatus.CANCELLED -> strings.t(StringKey.DIAG_PACKAGE_SAVE_CANCELLED)
            FileSaveStatus.UNAVAILABLE -> strings.t(StringKey.LOGS_SAVE_UNAVAILABLE)
            FileSaveStatus.FAILED ->
                strings.t(
                    StringKey.DIAG_PACKAGE_FAILED,
                    "reason" to (save.message ?: strings.t(StringKey.UNKNOWN)),
                )
        }
    return saved to message
}

@Composable
private fun DiagnosticHeaderRow(onBack: () -> Unit) {
    val strings = LocalStrings.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            strings.t(StringKey.DIAG_TITLE),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBack) { Text(strings.t(StringKey.BACK)) }
    }
}

private fun LazyListScope.diagnosticReportItems(
    session: DeviceSession,
    detail: DeviceDetailState,
    configState: ConfigEditorState,
    paramState: ParamEditorState,
    stats: ConnectionQualityStats,
    entries: List<PacketLogEntry>,
    zoneOffsetMinutes: Int,
) {
    item {
        DiagnosticReportCard(
            session = session,
            detail = detail,
            configState = configState,
            paramState = paramState,
            stats = stats,
            entries = entries,
            zoneOffsetMinutes = zoneOffsetMinutes,
        )
    }
}

private fun LazyListScope.diagnosticPackageItems(
    session: DeviceSession,
    detail: DeviceDetailState,
    stats: ConnectionQualityStats,
    entries: List<PacketLogEntry>,
    metrics: DeviceMetricRepository,
    zoneOffsetMinutes: Int,
) {
    item {
        DiagnosticPackageCard(session, detail, stats, entries, metrics, zoneOffsetMinutes)
    }
}

private fun LazyListScope.diagnosticQualityItems(
    stats: ConnectionQualityStats,
    strings: StringResolver,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
) {
    item {
        Text(strings.t(StringKey.DIAG_HINT), style = MaterialTheme.typography.bodySmall)
    }
    if (stats.requests == 0) {
        item { EmptyStatePanel(body = strings.t(StringKey.DIAG_EMPTY)) }
        return
    }
    if (stats.insufficientSample) {
        item { DiagnosticSampleInsufficientCard(stats) }
        return
    }
    item { DiagnosticQualitySummaryCard(stats, format) }
    item {
        Text(
            strings.t(StringKey.DIAG_ERROR_TITLE),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    if (stats.errorCodes.isEmpty()) {
        item { EmptyStatePanel(body = strings.t(StringKey.DIAG_NO_FAILURE)) }
    } else {
        items(stats.errorCodes) { error ->
            DiagnosticErrorCodeLine(error, format)
        }
    }
    item {
        Text(
            strings.t(StringKey.DIAG_COMMANDS_TITLE),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    items(stats.commands) { command ->
        DiagnosticCommandCard(command)
    }
    item {
        Text(
            strings.t(StringKey.DIAG_RECENT_FAILURES),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    if (stats.recentFailures.isEmpty()) {
        item { EmptyStatePanel(body = strings.t(StringKey.DIAG_NO_FAILURE)) }
    } else {
        items(stats.recentFailures) { failure ->
            DiagnosticFailureLine(failure, format, zoneOffsetMinutes)
        }
    }
}

@Composable
private fun DiagnosticReportCard(
    session: DeviceSession,
    detail: DeviceDetailState,
    configState: ConfigEditorState,
    paramState: ParamEditorState,
    stats: ConnectionQualityStats,
    entries: List<PacketLogEntry>,
    zoneOffsetMinutes: Int,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val clipboard = LocalClipboardManager.current
    val fileSaver = rememberFileSaver()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    var reportFormat by remember { mutableStateOf(DiagnosticReportFormat.MARKDOWN) }
    var reportGenerating by remember { mutableStateOf(false) }
    var reportProgress by remember { mutableStateOf<DiagnosticReportProgress?>(null) }
    var reportJob by remember { mutableStateOf<Job?>(null) }
    var reportResult by remember { mutableStateOf<DiagnosticReportResult?>(null) }
    var reportMessage by remember { mutableStateOf<String?>(null) }
    var reportMessageSuccess by remember { mutableStateOf(false) }
    val previewAt = remember { Clock.System.now().toEpochMilliseconds() }
    val preview =
        remember(
            reportFormat,
            entries,
            detail.firmwareVersion,
            detail.batteryPercent,
            detail.charging,
            configState.fields,
            paramState.fields,
        ) {
            DiagnosticReportComposer.estimate(
                input =
                    buildDiagnosticReportInput(
                        detail,
                        session.profileRepository.get(detail.deviceId),
                        configState,
                        paramState,
                        stats,
                        entries,
                        zoneOffsetMinutes,
                        previewAt,
                    ),
                options = DiagnosticReportOptions(format = reportFormat),
            )
        }

    fun saveReport(result: DiagnosticReportResult) {
        scope.launchSafely(errorReporter, "diagnostic-report-save") {
            val (saved, message) = saveDiagnosticReportFile(fileSaver, result, format, strings)
            reportMessageSuccess = saved
            reportMessage = message
        }
    }

    fun startReport() {
        if (reportGenerating) return
        reportGenerating = true
        reportResult = null
        reportProgress = null
        reportMessage = null
        val input =
            buildDiagnosticReportInput(
                detail,
                session.profileRepository.get(detail.deviceId),
                configState,
                paramState,
                stats,
                entries,
                zoneOffsetMinutes,
                Clock.System.now().toEpochMilliseconds(),
            )
        val options = DiagnosticReportOptions(format = reportFormat)
        reportJob =
            scope.launchSafely(errorReporter, "diagnostic-report") {
                try {
                    reportResult =
                        withContext(Dispatchers.Default) {
                            DiagnosticReportComposer.build(input, options) { reportProgress = it }
                        }
                } catch (cancellation: CancellationException) {
                    reportMessageSuccess = false
                    reportMessage = strings.t(StringKey.DIAG_REPORT_CANCELLED)
                    throw cancellation
                } catch (error: Throwable) {
                    reportMessageSuccess = false
                    reportMessage =
                        strings.t(
                            StringKey.DIAG_REPORT_FAILED,
                            "detail" to (error.message ?: error::class.simpleName ?: "unknown"),
                        )
                    errorReporter.record("diagnostic-report", error)
                } finally {
                    reportGenerating = false
                    reportProgress = null
                    reportJob = null
                }
            }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            DiagnosticReportHeader(
                preview = preview,
                format = format,
                selected = reportFormat,
                generating = reportGenerating,
                onFormatChange = {
                    reportFormat = it
                    reportResult = null
                    reportMessage = null
                },
            )
            if (reportGenerating) {
                DiagnosticReportProgress(reportProgress, reportJob)
            } else {
                Button(onClick = { startReport() }) {
                    Text(strings.t(StringKey.DIAG_REPORT_GENERATE))
                }
            }
            reportResult?.let { built ->
                DiagnosticReportResultActions(
                    onCopy = {
                        clipboard.setText(AnnotatedString(built.text))
                        reportMessageSuccess = true
                        reportMessage = strings.t(StringKey.DIAG_REPORT_COPIED)
                    },
                    onSave = { saveReport(built) },
                )
            }
            reportMessage?.let { message ->
                DiagnosticMessage(message, reportMessageSuccess)
            }
        }
    }
}

@Composable
private fun DiagnosticReportHeader(
    preview: DiagnosticReportEstimate,
    format: LocaleFormat,
    selected: DiagnosticReportFormat,
    generating: Boolean,
    onFormatChange: (DiagnosticReportFormat) -> Unit,
) {
    val strings = LocalStrings.current
    Text(
        strings.t(StringKey.DIAG_REPORT_TITLE),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        strings.t(
            StringKey.DIAG_REPORT_PREVIEW,
            "sections" to preview.sectionCount.toString(),
            "size" to format.bytes(preview.estimatedBytes.toLong()),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        strings.t(
            StringKey.DIAG_REPORT_SECTION_LIST,
            "list" to
                preview.sections.keys.joinToString(", ") {
                    strings.t(it.reportLabelKey())
                },
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        DiagnosticReportFormat.entries.forEach { candidate ->
            FilterChip(
                selected = selected == candidate,
                enabled = !generating,
                onClick = { onFormatChange(candidate) },
                label = { Text(strings.t(candidate.formatKey())) },
            )
        }
    }
}

@Composable
private fun DiagnosticReportProgress(
    progress: DiagnosticReportProgress?,
    job: Job?,
) {
    val strings = LocalStrings.current
    val performance = progress?.fraction ?: 0f
    val text =
        strings.t(
            StringKey.DIAG_REPORT_GENERATING,
            "percent" to (performance * 100).toInt().toString(),
        )
    LinearProgressIndicator(
        progress = { performance },
        modifier =
            Modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(performance, 0f..1f)
                contentDescription = text
            },
    )
    Text(text, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { job?.cancel() }) {
        Text(strings.t(StringKey.DIAG_REPORT_CANCEL))
    }
}

@Composable
private fun DiagnosticReportResultActions(
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        OutlinedButton(onClick = onCopy) {
            Text(strings.t(StringKey.DIAG_REPORT_COPY))
        }
        OutlinedButton(onClick = onSave) {
            Text(strings.t(StringKey.DIAG_REPORT_SAVE))
        }
    }
}

@Composable
private fun DiagnosticPackageCard(
    session: DeviceSession,
    detail: DeviceDetailState,
    stats: ConnectionQualityStats,
    entries: List<PacketLogEntry>,
    metrics: DeviceMetricRepository,
    zoneOffsetMinutes: Int,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val fileSaver = rememberFileSaver()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    var packageGenerating by remember { mutableStateOf(false) }
    var packageProgress by remember { mutableStateOf<DiagnosticPackageProgress?>(null) }
    var packageJob by remember { mutableStateOf<Job?>(null) }
    var packageMessage by remember { mutableStateOf<String?>(null) }
    var packageMessageSuccess by remember { mutableStateOf(false) }
    var packageResult by remember { mutableStateOf<DiagnosticPackageResult?>(null) }

    fun startPackage() {
        if (packageGenerating) return
        packageGenerating = true
        packageProgress = null
        packageResult = null
        packageMessage = null
        val input =
            buildDiagnosticPackageInput(
                detail = detail,
                profile = session.profileRepository.get(detail.deviceId),
                stats = stats,
                entries = entries,
                zoneOffsetMinutes = zoneOffsetMinutes,
                healthReport = session.lastHealthReport,
                metricSamples = metrics.series(detail.deviceId).samples,
            )
        packageJob =
            scope.launchSafely(errorReporter, "diagnostic-package") {
                try {
                    val built =
                        withContext(Dispatchers.Default) {
                            DiagnosticPackageBuilder.build(input) { packageProgress = it }
                        }
                    packageResult = built
                    // 构建完成前不触碰文件；保存失败也不会留下半成品。
                    val (saved, message) = saveDiagnosticPackageFile(fileSaver, built, format, strings)
                    packageMessageSuccess = saved
                    packageMessage = message
                } catch (cancellation: CancellationException) {
                    packageMessageSuccess = false
                    packageMessage = strings.t(StringKey.DIAG_PACKAGE_CANCELLED)
                    throw cancellation
                } catch (error: Throwable) {
                    packageMessageSuccess = false
                    packageMessage =
                        strings.t(
                            StringKey.DIAG_PACKAGE_FAILED,
                            "reason" to (error.message ?: error::class.simpleName ?: "unknown"),
                        )
                    errorReporter.record("diagnostic-package", error)
                } finally {
                    packageGenerating = false
                    packageProgress = null
                    packageJob = null
                }
            }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(
                strings.t(StringKey.DIAG_PACKAGE_TITLE),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                strings.t(StringKey.DIAG_PACKAGE_HINT),
                style = MaterialTheme.typography.bodySmall,
            )
            if (packageGenerating) {
                DiagnosticPackageProgress(packageProgress, packageJob)
            } else {
                Button(onClick = { startPackage() }) {
                    Text(strings.t(StringKey.DIAG_PACKAGE_EXPORT))
                }
            }
            packageResult?.let { DiagnosticPackageResultContent(it) }
            Text(
                strings.t(StringKey.DIAG_PACKAGE_EXCLUDED),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                strings.t(StringKey.DIAG_PACKAGE_REDACTED),
                style = MaterialTheme.typography.labelSmall,
            )
            packageMessage?.let { DiagnosticMessage(it, packageMessageSuccess) }
        }
    }
}

@Composable
private fun DiagnosticPackageProgress(
    progress: DiagnosticPackageProgress?,
    job: Job?,
) {
    val strings = LocalStrings.current
    val fraction = progress?.fraction ?: 0f
    val text = strings.t(StringKey.DIAG_PACKAGE_EXPORTING)
    LinearProgressIndicator(
        progress = { fraction },
        modifier =
            Modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                contentDescription = text
            },
    )
    Text(text, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { job?.cancel() }) {
        Text(strings.t(StringKey.DIAG_PACKAGE_CANCEL))
    }
}

@Composable
private fun DiagnosticPackageResultContent(result: DiagnosticPackageResult) {
    val strings = LocalStrings.current
    val packageEntriesText =
        strings.t(
            StringKey.DIAG_PACKAGE_ENTRIES,
            "names" to result.entries.joinToString(", ") { it.name },
        )
    Text(
        packageEntriesText,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = packageEntriesText
            },
    )
    if (result.truncated) {
        Text(
            strings.t(StringKey.DIAG_PACKAGE_TRUNCATED),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DiagnosticMessage(
    message: String,
    success: Boolean,
) {
    val cards = statusColors()
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (success) cards.success else cards.error,
        modifier =
            Modifier.semantics {
                liveRegion =
                    if (success) {
                        LiveRegionMode.Polite
                    } else {
                        LiveRegionMode.Assertive
                    }
                contentDescription = message
            },
    )
}

@Composable
private fun DiagnosticSampleInsufficientCard(stats: ConnectionQualityStats) {
    val strings = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(
                strings.t(
                    StringKey.DIAG_SAMPLE_INSUFFICIENT,
                    "count" to stats.sampleCount.toString(),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                strings.t(
                    StringKey.DIAG_SAMPLE_NOTE,
                    "min" to ConnectionQualityAnalyzer.MIN_SAMPLES.toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DiagnosticQualitySummaryCard(
    stats: ConnectionQualityStats,
    format: LocaleFormat,
) {
    val strings = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs),
        ) {
            Text(
                strings.t(
                    StringKey.DIAG_REQUESTS,
                    "count" to format.number(stats.requests),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            stats.successRate?.let { rate ->
                Text(
                    strings.t(
                        StringKey.DIAG_SUCCESS_RATE,
                        "rate" to formatOneDecimal(rate * 100),
                    ),
                )
            }
            stats.averageLatencyMillis?.let { value ->
                Text(
                    strings.t(
                        StringKey.DIAG_LATENCY_AVG,
                        "value" to formatOneDecimal(value),
                    ),
                )
            }
            stats.maxLatencyMillis?.let { value ->
                Text(
                    strings.t(
                        StringKey.DIAG_LATENCY_MAX,
                        "value" to format.number(value),
                    ),
                )
            }
            Text(
                strings.t(
                    StringKey.DIAG_RETRANSMISSIONS,
                    "count" to format.number(stats.retransmissions),
                ),
            )
            Text(
                strings.t(
                    StringKey.DIAG_OUT_OF_ORDER,
                    "count" to format.number(stats.outOfOrderResponses),
                ),
            )
            Text(
                strings.t(
                    StringKey.DIAG_NO_RESPONSE,
                    "count" to format.number(stats.noResponses),
                ),
            )
            if (stats.unmatchedResponses > 0) {
                Text(
                    strings.t(StringKey.DIAG_UNKNOWN_ERROR) + " · " + format.number(stats.unmatchedResponses),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticErrorCodeLine(
    error: ErrorCodeCount,
    format: LocaleFormat,
) {
    val strings = LocalStrings.current
    Text(
        strings.t(
            StringKey.DIAG_ERROR_CODE_LINE,
            "code" to error.code,
            "title" to error.title,
            "count" to format.number(error.count),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DiagnosticCommandCard(command: CommandQuality) {
    val strings = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs),
        ) {
            Text(
                strings.t(
                    StringKey.DIAG_COMMAND_LINE,
                    "command" to command.command.name,
                    "requests" to command.requests.toString(),
                    "successes" to command.successes.toString(),
                    "failures" to (command.deviceErrors + command.noResponses).toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            val average = command.averageLatencyMillis
            val maximum = command.maxLatencyMillis
            if (average != null && maximum != null) {
                Text(
                    strings.t(
                        StringKey.DIAG_COMMAND_LATENCY,
                        "avg" to formatOneDecimal(average),
                        "max" to maximum.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (command.retransmissions > 0) {
                Text(
                    strings.t(
                        StringKey.DIAG_RETRANSMISSIONS,
                        "count" to command.retransmissions.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticFailureLine(
    failure: QualityFailure,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
) {
    val strings = LocalStrings.current
    val cards = statusColors()
    val failureText =
        strings.t(
            StringKey.DIAG_FAILURE_LINE,
            "time" to format.absoluteTime(failure.timestampMillis, zoneOffsetMinutes),
            "command" to failure.command.name,
            "detail" to failure.detail,
        )
    Text(
        failureText,
        style = MaterialTheme.typography.bodySmall,
        color = cards.error,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = failureText
            },
    )
}

/** 一位小数，避免把估算值展示成精确值。 */
internal fun formatOneDecimal(value: Double): String {
    val scaled = (value * ONE_DECIMAL_FACTOR).toLong()
    return "${scaled / ONE_DECIMAL_FACTOR}.${scaled % ONE_DECIMAL_FACTOR}"
}

private const val ONE_DECIMAL_FACTOR = 10
