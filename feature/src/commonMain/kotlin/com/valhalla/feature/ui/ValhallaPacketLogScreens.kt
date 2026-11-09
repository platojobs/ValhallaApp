package com.valhalla.feature.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import com.valhalla.core.logging.InMemoryPacketLog
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogExportEstimate
import com.valhalla.core.logging.PacketLogExportFormat
import com.valhalla.core.logging.PacketLogExportOptions
import com.valhalla.core.logging.PacketLogExportProgress
import com.valhalla.core.logging.PacketLogExporter
import com.valhalla.core.logging.PacketLogFilter
import com.valhalla.core.logging.PacketLogIndex
import com.valhalla.core.logging.PacketLogLimitCheck
import com.valhalla.core.logging.formatForCopy
import com.valhalla.core.protocol.CommandType
import com.valhalla.data.share.TextSharer
import com.valhalla.data.share.createTextSharer
import com.valhalla.data.share.currentTimeZoneOffsetMinutes
import com.valhalla.feature.error.ErrorReporter
import com.valhalla.feature.error.LocalErrorReporter
import com.valhalla.feature.export.FileSaver
import com.valhalla.feature.export.rememberFileSaver
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.theme.DesignTokens
import com.valhalla.feature.theme.statusColors
import com.valhalla.feature.uilogic.availableCommandTypes
import com.valhalla.feature.uilogic.label
import com.valhalla.feature.uilogic.packetLogCanExport
import com.valhalla.feature.uilogic.packetLogEstimateLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal enum class ExportAction { SAVE, SHARE }

@OptIn(ExperimentalTime::class)
@Composable
internal fun PacketLogScreen(
    packetLog: InMemoryPacketLog,
    initialOptions: PacketLogExportOptions,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val localeFormat = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val entries by packetLog.entries.collectAsState()
    val state = remember(initialOptions) { PacketLogUiState(initialOptions) }
    val clipboard = LocalClipboardManager.current
    val sharer = remember { createTextSharer() }
    val fileSaver = rememberFileSaver()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    // 索引只在日志快照变化时重建；输入关键字只做一次包含判断，不逐条重新拼接。
    val logIndex = remember(entries) { PacketLogIndex(entries) }
    val nowMillis = Clock.System.now().toEpochMilliseconds()
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(nowMillis) }
    val availableCommands = remember(entries) { availableCommandTypes(entries) }
    // 输入去抖：停止输入 150ms 后才切换过滤结果，避免每个按键触发全量过滤。
    LaunchedEffect(state.query) {
        if (state.query != state.appliedQuery) {
            delay(150)
            state.appliedQuery = state.query
        }
    }
    val derived = packetLogDerived(state, initialOptions, strings, zoneOffsetMinutes)
    val filtered =
        remember(logIndex, derived.filter) {
            logIndex.query(filter = derived.filter, limit = InMemoryPacketLog.DEFAULT_CAPACITY)
        }
    // 预估与实际导出使用同一套筛选/上限逻辑，避免两处口径分叉。
    PacketLogEstimateEffect(
        PacketLogEstimateInput(
            entries = entries,
            logIndex = logIndex,
            filter = derived.filter,
            format = state.format,
            entryLimitValue = derived.entryLimit.value,
            byteLimitValue = derived.byteLimit.value,
            rangeError = derived.rangeError,
            options = derived.options,
            nowMillis = nowMillis,
            zoneOffsetMinutes = zoneOffsetMinutes,
            state = state,
        ),
    )
    val deps =
        PacketLogExportDeps(
            scope = scope,
            errorReporter = errorReporter,
            fileSaver = fileSaver,
            sharer = sharer,
            strings = strings,
            localeFormat = localeFormat,
            exportedNames = state.exportedNames,
        )
    val exportContext = buildPacketLogExportContext(state, packetLog, derived, deps)
    val canExport =
        packetLogCanExport(
            exporting = state.exporting,
            entriesEmpty = entries.isEmpty(),
            hasOptions = derived.options != null,
            rangeError = derived.rangeError,
            fromError = derived.fromError,
            toError = derived.toError,
            matchedCount = state.estimate?.matchedCount,
        )
    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        PacketLogHeaderRow(
            strings = strings,
            canExport = canExport,
            onSave = { startPacketLogExport(exportContext, ExportAction.SAVE) },
            onShare = { startPacketLogExport(exportContext, ExportAction.SHARE) },
            onBack = onBack,
        )
        PacketLogStatusPanels(state.exporting, entries.isEmpty())
        PacketLogSearchField(strings, state)
        PacketLogCommandFilterRow(strings, availableCommands, state)
        PacketLogTimeRangeRow(strings, state, derived.fromError, derived.toError, derived.rangeError)
        PacketLogFormatRow(state)
        PacketLogLimitsRow(strings, localeFormat, state, derived.entryLimit.valid, derived.byteLimit.valid)
        PacketLogEstimateRow(
            strings = strings,
            localeFormat = localeFormat,
            state = state,
            rangeError = derived.rangeError,
            fromError = derived.fromError,
            toError = derived.toError,
            options = derived.options,
            onCancel = { state.exportJob?.cancel() },
        )
        PacketLogExportProgress(state, strings, localeFormat, status)
        PacketLogDisplayCountRow(strings, localeFormat, filtered.size, entries.size) {
            packetLog.clear()
        }
        PacketLogExportMessage(state, status)
        if (filtered.isEmpty()) {
            EmptyStatePanel(
                body =
                    if (entries.isEmpty()) {
                        strings.t(StringKey.LOGS_EMPTY)
                    } else {
                        strings.t(StringKey.LOGS_NO_MATCH)
                    },
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            packetLogItems(filtered, nowMillis, zoneOffsetMinutes, localeFormat, clipboard)
        }
    }
}

internal class PacketLogDerived(
    val fromMillis: Long?,
    val toMillis: Long?,
    val fromError: String?,
    val toError: String?,
    val rangeError: String?,
    val entryLimit: PacketLogLimitCheck,
    val byteLimit: PacketLogLimitCheck,
    val options: PacketLogExportOptions?,
    val filter: PacketLogFilter,
)

internal fun buildPacketLogExportContext(
    state: PacketLogUiState,
    packetLog: InMemoryPacketLog,
    derived: PacketLogDerived,
    deps: PacketLogExportDeps,
): PacketLogExportContext =
    PacketLogExportContext(
        state = state,
        packetLog = packetLog,
        deps = deps,
        options = derived.options,
        filter = derived.filter,
        format = state.format,
        rangeError = derived.rangeError,
        fromError = derived.fromError,
        toError = derived.toError,
    )

internal class PacketLogUiState(
    initialOptions: PacketLogExportOptions,
) {
    var query by mutableStateOf("")
    var appliedQuery by mutableStateOf("")
    var errorsOnly by mutableStateOf(false)
    var selectedCommands by mutableStateOf<Set<CommandType>>(emptySet())
    var fromText by mutableStateOf("")
    var toText by mutableStateOf("")
    var format by mutableStateOf(PacketLogExportFormat.TEXT)
    var maxEntriesText by mutableStateOf(initialOptions.maxEntries.toString())
    var maxBytesKbText by mutableStateOf((initialOptions.maxBytes / 1024).toString())
    var exporting by mutableStateOf(false)
    var progress by mutableStateOf<PacketLogExportProgress?>(null)
    var exportJob by mutableStateOf<Job?>(null)
    var exportMessage by mutableStateOf<String?>(null)
    var exportSuccess by mutableStateOf(false)
    var estimate by mutableStateOf<PacketLogExportEstimate?>(null)
    val exportedNames = mutableListOf<String>()
}

internal class PacketLogExportDeps(
    val scope: CoroutineScope,
    val errorReporter: ErrorReporter,
    val fileSaver: FileSaver,
    val sharer: TextSharer,
    val strings: StringResolver,
    val localeFormat: LocaleFormat,
    val exportedNames: MutableList<String>,
)

internal class PacketLogExportContext(
    val state: PacketLogUiState,
    val packetLog: InMemoryPacketLog,
    val deps: PacketLogExportDeps,
    val options: PacketLogExportOptions?,
    val filter: PacketLogFilter,
    val format: PacketLogExportFormat,
    val rangeError: String?,
    val fromError: String?,
    val toError: String?,
)

internal class PacketLogEstimateInput(
    val entries: List<PacketLogEntry>,
    val logIndex: PacketLogIndex,
    val filter: PacketLogFilter,
    val format: PacketLogExportFormat,
    val entryLimitValue: Int?,
    val byteLimitValue: Int?,
    val rangeError: String?,
    val options: PacketLogExportOptions?,
    val nowMillis: Long,
    val zoneOffsetMinutes: Int,
    val state: PacketLogUiState,
)

@Composable
private fun PacketLogEstimateEffect(input: PacketLogEstimateInput) {
    with(input) {
        LaunchedEffect(logIndex, filter, format, entryLimitValue, byteLimitValue, rangeError) {
            val opts = options
            if (opts == null || rangeError != null) {
                state.estimate = null
                return@LaunchedEffect
            }
            delay(200)
            state.estimate =
                withContext(Dispatchers.Default) {
                    PacketLogExporter.estimate(
                        entries = entries,
                        nowMillis = nowMillis,
                        zoneOffsetMinutes = zoneOffsetMinutes,
                        options = opts,
                        filter = filter,
                        format = format,
                    )
                }
        }
    }
}

@Composable
private fun PacketLogHeaderRow(
    strings: StringResolver,
    canExport: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            strings.t(StringKey.LOGS_TITLE),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = canExport, onClick = onSave) { Text(strings.t(StringKey.SAVE_FILE)) }
        TextButton(enabled = canExport, onClick = onShare) { Text(strings.t(StringKey.SHARE)) }
        TextButton(onClick = onBack) { Text(strings.t(StringKey.BACK)) }
    }
}

@Composable
private fun PacketLogStatusPanels(
    exporting: Boolean,
    entriesEmpty: Boolean,
) {
    if (exporting) {
        DisabledStatePanel(reason = StringKey.STATE_DISABLED_BUSY)
    } else if (entriesEmpty) {
        DisabledStatePanel(reason = StringKey.STATE_UNAVAILABLE_HINT)
    }
}

@Composable
private fun PacketLogSearchField(
    strings: StringResolver,
    state: PacketLogUiState,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = { state.query = it },
        label = { Text(strings.t(StringKey.LOGS_SEARCH)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PacketLogCommandFilterRow(
    strings: StringResolver,
    availableCommands: List<CommandType>,
    state: PacketLogUiState,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        FilterChip(
            selected = state.selectedCommands.isEmpty(),
            onClick = { state.selectedCommands = emptySet() },
            label = { Text(strings.t(StringKey.LOGS_ALL_COMMANDS)) },
        )
        availableCommands.forEach { item ->
            val selected = item in state.selectedCommands
            FilterChip(
                selected = selected,
                onClick = {
                    state.selectedCommands =
                        if (selected) {
                            state.selectedCommands - item
                        } else {
                            state.selectedCommands + item
                        }
                },
                label = { Text(item.name) },
            )
        }
        FilterChip(
            selected = state.errorsOnly,
            onClick = { state.errorsOnly = !state.errorsOnly },
            label = { Text(strings.t(StringKey.LOGS_ERRORS_ONLY)) },
        )
    }
}

@Composable
private fun PacketLogTimeRangeRow(
    strings: StringResolver,
    state: PacketLogUiState,
    fromError: String?,
    toError: String?,
    rangeError: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        OutlinedTextField(
            value = state.fromText,
            onValueChange = { state.fromText = it },
            label = { Text(strings.t(StringKey.LOGS_START_TIME)) },
            placeholder = { Text(strings.t(StringKey.LOGS_TIME_PLACEHOLDER)) },
            singleLine = true,
            isError = fromError != null || rangeError != null,
            supportingText = {
                Text(fromError ?: rangeError ?: strings.t(StringKey.LOGS_TIME_HINT))
            },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = state.toText,
            onValueChange = { state.toText = it },
            label = { Text(strings.t(StringKey.LOGS_END_TIME)) },
            placeholder = { Text(strings.t(StringKey.LOGS_TIME_PLACEHOLDER)) },
            singleLine = true,
            isError = toError != null || rangeError != null,
            supportingText = {
                Text(toError ?: rangeError ?: strings.t(StringKey.LOGS_TIME_HINT))
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PacketLogFormatRow(state: PacketLogUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        PacketLogExportFormat.entries.forEach { item ->
            FilterChip(
                selected = state.format == item,
                onClick = { state.format = item },
                label = { Text(item.name) },
            )
        }
    }
}

@Composable
private fun PacketLogLimitsRow(
    strings: StringResolver,
    localeFormat: LocaleFormat,
    state: PacketLogUiState,
    entriesValid: Boolean,
    bytesValid: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        OutlinedTextField(
            value = state.maxEntriesText,
            onValueChange = { state.maxEntriesText = it },
            label = { Text(strings.t(StringKey.LOGS_ENTRY_LIMIT)) },
            singleLine = true,
            isError = !entriesValid,
            supportingText = {
                Text(
                    if (entriesValid) {
                        strings.t(
                            StringKey.LOGS_ENTRY_RANGE,
                            "min" to localeFormat.number(PacketLogExportOptions.MIN_ENTRIES),
                            "max" to localeFormat.number(PacketLogExportOptions.MAX_ENTRIES),
                        )
                    } else {
                        strings.t(StringKey.LOGS_INVALID_LIMIT)
                    },
                )
            },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = state.maxBytesKbText,
            onValueChange = { state.maxBytesKbText = it },
            label = { Text(strings.t(StringKey.LOGS_BYTES_LIMIT)) },
            singleLine = true,
            isError = !bytesValid,
            supportingText = {
                Text(
                    if (bytesValid) {
                        strings.t(
                            StringKey.LOGS_BYTES_RANGE,
                            "max" to localeFormat.number(PacketLogExportOptions.MAX_BYTES / 1024),
                        )
                    } else {
                        strings.t(StringKey.LOGS_INVALID_LIMIT)
                    },
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PacketLogEstimateRow(
    strings: StringResolver,
    localeFormat: LocaleFormat,
    state: PacketLogUiState,
    rangeError: String?,
    fromError: String?,
    toError: String?,
    options: PacketLogExportOptions?,
    onCancel: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val estimateLine =
            packetLogEstimateLine(
                rangeError = rangeError,
                fromError = fromError,
                toError = toError,
                hasOptions = options != null,
                estimate = state.estimate,
                strings = strings,
                format = localeFormat,
            )
        Text(
            estimateLine,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (state.exporting) {
            TextButton(onClick = onCancel) { Text(strings.t(StringKey.CANCEL)) }
        }
    }
}

@Composable
private fun PacketLogExportProgress(
    state: PacketLogUiState,
    strings: StringResolver,
    localeFormat: LocaleFormat,
    status: DesignTokens.StatusColors,
) {
    if (!state.exporting) return
    val current = state.progress
    val phaseText =
        current?.phase?.let { it.label(strings) }
            ?: strings.t(StringKey.LOGS_EXPORT_PREPARING)
    val exportingText =
        strings.t(
            StringKey.LOGS_EXPORTING,
            "phase" to phaseText,
            "processed" to localeFormat.number(current?.processed ?: 0),
            "total" to localeFormat.number(current?.total ?: 0),
        )
    val exportFraction = current?.fraction ?: 0f
    Text(
        exportingText,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = exportingText
            },
    )
    LinearProgressIndicator(
        progress = { exportFraction },
        modifier =
            Modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(exportFraction, 0f..1f)
                contentDescription = exportingText
            },
        color = status.inProgress,
    )
}

@Composable
private fun PacketLogDisplayCountRow(
    strings: StringResolver,
    localeFormat: LocaleFormat,
    shown: Int,
    total: Int,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            strings.t(
                StringKey.LOGS_DISPLAY_COUNT,
                "shown" to localeFormat.number(shown),
                "total" to localeFormat.number(total),
                "capacity" to localeFormat.number(InMemoryPacketLog.DEFAULT_CAPACITY),
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text(strings.t(StringKey.CLEAR)) }
    }
}

@Composable
private fun PacketLogExportMessage(
    state: PacketLogUiState,
    status: DesignTokens.StatusColors,
) {
    state.exportMessage?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.exportSuccess) status.success else status.error,
            modifier =
                Modifier.semantics {
                    liveRegion =
                        if (state.exportSuccess) {
                            LiveRegionMode.Polite
                        } else {
                            LiveRegionMode.Assertive
                        }
                    contentDescription = message
                },
        )
    }
}

private fun LazyListScope.packetLogItems(
    filtered: List<PacketLogEntry>,
    nowMillis: Long,
    zoneOffsetMinutes: Int,
    format: LocaleFormat,
    clipboard: ClipboardManager,
) {
    items(filtered, key = { it.id }) { entry ->
        PacketLogCard(
            entry = entry,
            nowMillis = nowMillis,
            zoneOffsetMinutes = zoneOffsetMinutes,
            format = format,
            onCopy = {
                clipboard.setText(
                    AnnotatedString(entry.formatForCopy(nowMillis, zoneOffsetMinutes)),
                )
            },
        )
    }
}

@Composable
private fun PacketLogCard(
    entry: PacketLogEntry,
    nowMillis: Long,
    zoneOffsetMinutes: Int,
    format: LocaleFormat,
    onCopy: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${entry.direction.name} · ${entry.command.name} · seq=${entry.sequence}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy) { Text(strings.t(StringKey.COPY)) }
            }
            Text(
                format.absoluteTime(entry.timestampMillis, zoneOffsetMinutes) + " · " +
                    format.relativeTime(entry.timestampMillis, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(entry.summary, style = MaterialTheme.typography.bodySmall)
            entry.error?.let { error ->
                Text(
                    strings.t(StringKey.ERROR_PREFIX, "detail" to error),
                    color = status.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(strings.t(StringKey.LOGS_HEX, "hex" to entry.hex), style = MaterialTheme.typography.bodySmall)
        }
    }
}
