package com.valhalla.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import com.valhalla.core.batch.BatchItemState
import com.valhalla.core.batch.BatchItemStatus
import com.valhalla.core.batch.BatchOperationKind
import com.valhalla.core.batch.BatchSummary
import com.valhalla.core.batch.BatchTarget
import com.valhalla.core.config.ConfigPreset
import com.valhalla.core.firmware.FirmwareLibraryRecord
import com.valhalla.core.ota.OtaQueueItem
import com.valhalla.core.ota.OtaQueueItemStatus
import com.valhalla.core.ota.OtaQueueStage
import com.valhalla.core.ota.OtaQueueSummary
import com.valhalla.core.ota.OtaQueueTarget
import com.valhalla.feature.batch.BatchDeviceOutcome
import com.valhalla.feature.batch.BatchOperationController
import com.valhalla.feature.error.LocalErrorReporter
import com.valhalla.feature.error.launchSafely
import com.valhalla.feature.export.SaveableFile
import com.valhalla.feature.export.rememberFileSaver
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.ota.OtaQueueController
import com.valhalla.feature.theme.DesignTokens
import com.valhalla.feature.uilogic.OtaQueueActions
import com.valhalla.feature.uilogic.batchStartEnabled
import com.valhalla.feature.uilogic.fileSaveMessage
import com.valhalla.feature.uilogic.otaQueueActions
import com.valhalla.feature.uilogic.selectedById
import kotlinx.coroutines.launch

internal fun BatchItemStatus.labelKey(): StringKey =
    when (this) {
        BatchItemStatus.QUEUED -> StringKey.BATCH_STATUS_QUEUED
        BatchItemStatus.RUNNING -> StringKey.BATCH_STATUS_RUNNING
        BatchItemStatus.SUCCEEDED -> StringKey.BATCH_STATUS_SUCCEEDED
        BatchItemStatus.SKIPPED -> StringKey.BATCH_STATUS_SKIPPED
        BatchItemStatus.FAILED -> StringKey.BATCH_STATUS_FAILURE
        BatchItemStatus.CANCELLED -> StringKey.BATCH_STATUS_CANCELLED
        BatchItemStatus.REMOVED -> StringKey.BATCH_STATUS_REMOVED
    }

/** 批量执行中的步骤代号到文案键；未知步骤直接原样展示。 */
internal fun batchStepLabelKey(step: String?): StringKey? =
    when (step) {
        "batch.started" -> StringKey.BATCH_STEP_STARTED
        "batch.health" -> StringKey.BATCH_STEP_HEALTH
        "batch.preset" -> StringKey.BATCH_STEP_PRESET
        null -> null
        else -> null
    }

internal fun OtaQueueItemStatus.labelKey(): StringKey =
    when (this) {
        OtaQueueItemStatus.QUEUED -> StringKey.QUEUE_STATUS_QUEUED
        OtaQueueItemStatus.RUNNING -> StringKey.QUEUE_STATUS_RUNNING
        OtaQueueItemStatus.PAUSED -> StringKey.QUEUE_STATUS_PAUSED
        OtaQueueItemStatus.SUCCEEDED -> StringKey.QUEUE_STATUS_SUCCEEDED
        OtaQueueItemStatus.FAILED -> StringKey.QUEUE_STATUS_FAILURE
        OtaQueueItemStatus.SKIPPED -> StringKey.QUEUE_STATUS_SKIPPED
        OtaQueueItemStatus.ABORTED -> StringKey.QUEUE_STATUS_ABORTED
        OtaQueueItemStatus.REMOVED -> StringKey.QUEUE_STATUS_REMOVED
    }

internal fun OtaQueueStage.labelKey(): StringKey =
    when (this) {
        OtaQueueStage.PRECHECK -> StringKey.QUEUE_STAGE_PRECHECK
        OtaQueueStage.TRANSFER -> StringKey.QUEUE_STAGE_TRANSFER
        OtaQueueStage.VERIFY -> StringKey.QUEUE_STAGE_VERIFY
        OtaQueueStage.DONE -> StringKey.QUEUE_STAGE_DONE
    }

/**
 * 批量操作页面：多选已连接设备，串行执行体检或应用预设。
 *
 * 执行器由调用方注入（[execute]），页面只负责选择、逐台状态、取消剩余与汇总。
 */
@Composable
internal fun BatchScreen(
    devices: List<BatchDeviceOption>,
    presets: List<ConfigPreset>,
    controller: BatchOperationController,
    onBack: () -> Unit,
    execute: suspend (BatchOperationKind, ConfigPreset?, BatchTarget, (String) -> Unit) -> BatchDeviceOutcome,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    val clipboard = LocalClipboardManager.current
    val fileSaver = rememberFileSaver()

    val items by controller.items.collectAsState()
    val running by controller.running.collectAsState()
    val cancelRequested by controller.cancelRequested.collectAsState()

    var kind by remember { mutableStateOf(BatchOperationKind.HEALTH_CHECK) }
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember(devices) { mutableStateOf(devices.map { it.deviceId }.toSet()) }
    var lastSummary by remember { mutableStateOf<BatchSummary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val selectedPreset = presets.firstOrNull { it.id == selectedPresetId }
    val canStart =
        batchStartEnabled(
            running = running,
            selectedCount = selectedIds.size,
            applyingPreset = kind == BatchOperationKind.APPLY_PRESET,
            hasSelectedPreset = selectedPreset != null,
        )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(DesignTokens.Spacing.lg)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        BatchHeader(onBack = onBack)
        BatchKindSection(
            kind = kind,
            presets = presets,
            selectedPresetId = selectedPresetId,
            running = running,
            onKindChange = { kind = it },
            onPresetSelect = { selectedPresetId = it },
        )
        BatchTargetsSection(
            devices = devices,
            selectedIds = selectedIds,
            running = running,
            onSelectedIdsChange = { selectedIds = it },
        )
        BatchActionSection(
            canStart = canStart,
            running = running,
            onStart = {
                message = null
                lastSummary = null
                scope.launchSafely(errorReporter, "batch-run") {
                    lastSummary =
                        controller.run(
                            kind = kind,
                            targets =
                                selectedById(devices, selectedIds) { it.deviceId }
                                    .map { BatchTarget(it.deviceId, it.displayName) },
                        ) { target, step ->
                            execute(kind, selectedPreset, target, step)
                        }
                }
            },
            onCancel = { controller.cancelRemaining() },
        )
        BatchStatusSection(
            devicesEmpty = devices.isEmpty(),
            running = running,
            cancelRequested = cancelRequested,
        )
        BatchItemList(items = items)
        lastSummary?.let { summary ->
            BatchSummarySection(
                summary = summary,
                onCopy = {
                    clipboard.setText(AnnotatedString(summary.toText()))
                    message = strings.t(StringKey.BATCH_COPIED)
                },
                onSave = {
                    scope.launch {
                        val fileName = "valhalla-batch-summary.txt"
                        val result =
                            fileSaver.save(
                                SaveableFile(
                                    fileName = fileName,
                                    content = summary.toText(),
                                    mimeType = "text/plain",
                                ),
                            )
                        message = fileSaveMessage(strings, result, fileName)
                    }
                },
            )
        }
        BatchMessageText(message = message)
        if (!running && items.isEmpty() && devices.isNotEmpty()) {
            EmptyStatePanel(body = strings.t(StringKey.BATCH_NO_TARGET))
        }
    }
}

@Composable
private fun BatchHeader(onBack: () -> Unit) {
    val strings = LocalStrings.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            strings.t(StringKey.BATCH_TITLE),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBack) { Text(strings.t(StringKey.BACK)) }
    }
    Text(strings.t(StringKey.BATCH_HINT), style = MaterialTheme.typography.bodySmall)
    Text(strings.t(StringKey.BATCH_LOCK_NOTE), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun BatchKindSection(
    kind: BatchOperationKind,
    presets: List<ConfigPreset>,
    selectedPresetId: String?,
    running: Boolean,
    onKindChange: (BatchOperationKind) -> Unit,
    onPresetSelect: (String) -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        FilterChip(
            selected = kind == BatchOperationKind.HEALTH_CHECK,
            onClick = { onKindChange(BatchOperationKind.HEALTH_CHECK) },
            label = { Text(strings.t(StringKey.BATCH_KIND_HEALTH)) },
            enabled = !running,
        )
        FilterChip(
            selected = kind == BatchOperationKind.APPLY_PRESET,
            onClick = { onKindChange(BatchOperationKind.APPLY_PRESET) },
            label = { Text(strings.t(StringKey.BATCH_KIND_PRESET)) },
            enabled = !running,
        )
    }

    if (kind != BatchOperationKind.APPLY_PRESET) return

    Text(strings.t(StringKey.BATCH_PRESET_LABEL), style = MaterialTheme.typography.titleSmall)
    if (presets.isEmpty()) {
        EmptyStatePanel(body = strings.t(StringKey.BATCH_PRESET_EMPTY))
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        presets.forEach { preset ->
            FilterChip(
                selected = selectedPresetId == preset.id,
                onClick = { onPresetSelect(preset.id) },
                label = { Text(preset.name) },
                enabled = !running,
            )
        }
    }
}

@Composable
private fun BatchTargetsSection(
    devices: List<BatchDeviceOption>,
    selectedIds: Set<String>,
    running: Boolean,
    onSelectedIdsChange: (Set<String>) -> Unit,
) {
    val strings = LocalStrings.current
    Text(strings.t(StringKey.BATCH_TARGETS), style = MaterialTheme.typography.titleSmall)
    if (devices.isEmpty()) {
        EmptyStatePanel(body = strings.t(StringKey.DEVICES_EMPTY))
        return
    }
    devices.forEach { device ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = device.deviceId in selectedIds,
                onCheckedChange = { checked ->
                    onSelectedIdsChange(
                        if (checked) {
                            selectedIds + device.deviceId
                        } else {
                            selectedIds - device.deviceId
                        },
                    )
                },
                enabled = !running,
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            strings.t(StringKey.BATCH_TARGETS) + ": " + device.displayName
                    },
            )
            Text(device.displayName, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BatchActionSection(
    canStart: Boolean,
    running: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        Button(enabled = canStart, onClick = onStart) {
            Text(strings.t(StringKey.BATCH_START))
        }
        if (running) {
            OutlinedButton(onClick = onCancel) {
                Text(strings.t(StringKey.BATCH_CANCEL_REMAINING))
            }
        }
    }
}

@Composable
private fun BatchStatusSection(
    devicesEmpty: Boolean,
    running: Boolean,
    cancelRequested: Boolean,
) {
    val strings = LocalStrings.current
    when {
        devicesEmpty -> DisabledStatePanel(StringKey.STATE_DISABLED_NOT_CONNECTED)
        running -> DisabledStatePanel(StringKey.STATE_DISABLED_BUSY)
    }
    if (running) {
        LoadingStatePanel(body = strings.t(StringKey.BATCH_RUNNING))
    }
    if (cancelRequested) {
        Text(
            strings.t(StringKey.BATCH_CANCEL_REQUESTED),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun BatchItemList(items: List<BatchItemState>) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        items.forEach { item ->
            BatchItemCard(item = item)
        }
    }
}

@Composable
private fun BatchItemCard(item: BatchItemState) {
    val strings = LocalStrings.current
    var expanded by remember(item.target.deviceId) { mutableStateOf(false) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription =
                        item.target.displayName + " " + strings.t(item.status.labelKey())
                },
    ) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.target.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    strings.t(item.status.labelKey()),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (item.status == BatchItemStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier =
                        Modifier.semantics {
                            liveRegion =
                                if (item.status == BatchItemStatus.FAILED) {
                                    LiveRegionMode.Assertive
                                } else {
                                    LiveRegionMode.Polite
                                }
                        },
                )
            }
            if (expanded) {
                item.step?.let { step ->
                    val key = batchStepLabelKey(step)
                    Text(
                        key?.let { strings.t(it) } ?: step,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item.reason?.let { reason ->
                    val key = batchReasonLabelKey(reason)
                    Text(
                        key?.let { strings.t(it) } ?: reason,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item.detail?.let { detail ->
                    Text(
                        strings.t(StringKey.TECHNICAL_DETAIL, "detail" to detail),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchSummarySection(
    summary: BatchSummary,
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalStrings.current
    Text(strings.t(StringKey.BATCH_SUMMARY_TITLE), style = MaterialTheme.typography.titleMedium)
    Text(
        strings.t(
            StringKey.BATCH_SUMMARY_COUNTS,
            "total" to summary.total.toString(),
            "succeeded" to summary.succeeded.toString(),
            "skipped" to summary.skipped.toString(),
            "failed" to summary.failed.toString(),
            "cancelled" to summary.cancelled.toString(),
            "removed" to summary.removed.toString(),
        ),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        TextButton(onClick = onCopy) { Text(strings.t(StringKey.BATCH_COPY)) }
        TextButton(onClick = onSave) { Text(strings.t(StringKey.BATCH_SAVE)) }
    }
}

@Composable
private fun BatchMessageText(message: String?) {
    val text = message ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

internal fun batchReasonLabelKey(reason: String): StringKey? =
    when (reason) {
        "batch.device_locked" -> StringKey.BATCH_REASON_DEVICE_LOCKED
        "batch.disconnected" -> StringKey.BATCH_REASON_DISCONNECTED
        "batch.disconnected_after_step" -> StringKey.BATCH_REASON_DISCONNECTED_AFTER
        "batch.cancelled_remaining" -> StringKey.BATCH_REASON_CANCELLED_REMAINING
        "batch.job_cancelled" -> StringKey.BATCH_REASON_JOB_CANCELLED
        "batch.execution_error" -> StringKey.BATCH_REASON_EXECUTION_ERROR
        "batch.no_preset" -> StringKey.BATCH_REASON_NO_PRESET
        else -> null
    }

/**
 * OTA 队列页面：已连接设备 + 固件库包排成队列，逐台串行升级。
 *
 * 队列只在内存中推进；页面固定展示「退出 App 会中断队列」的说明。
 */
@Composable
internal fun OtaQueueScreen(
    devices: List<BatchDeviceOption>,
    firmwareRecords: List<FirmwareLibraryRecord>,
    controller: OtaQueueController,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val fileSaver = rememberFileSaver()

    val snapshot by controller.state.collectAsState()
    var selectedIds by remember(devices) { mutableStateOf(devices.map { it.deviceId }.toSet()) }
    var firmwareId by remember(firmwareRecords) { mutableStateOf(firmwareRecords.firstOrNull()?.id) }
    var message by remember { mutableStateOf<String?>(null) }

    val selectedFirmware = firmwareRecords.firstOrNull { it.id == firmwareId }
    val hasPaused = snapshot.items.any { it.status == OtaQueueItemStatus.PAUSED }
    val hasQueued = snapshot.items.any { it.status == OtaQueueItemStatus.QUEUED }
    val currentDevice =
        snapshot.currentDeviceId
            ?.let { id ->
                snapshot.items
                    .firstOrNull { it.target.deviceId == id }
                    ?.target
                    ?.displayName
            }
    val actions =
        otaQueueActions(
            running = snapshot.running,
            hasFirmware = selectedFirmware != null,
            selectedCount = selectedIds.size,
            hasQueued = hasQueued,
            hasPaused = hasPaused,
        )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(DesignTokens.Spacing.lg)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        OtaQueueHeader(onBack = onBack)
        OtaQueueTargets(
            devices = devices,
            selectedIds = selectedIds,
            running = snapshot.running,
            onSelectedIdsChange = { selectedIds = it },
        )
        OtaFirmwareSection(
            firmwareRecords = firmwareRecords,
            firmwareId = firmwareId,
            running = snapshot.running,
            onFirmwareSelect = { firmwareId = it },
        )
        OtaQueueControls(
            actions = actions,
            onEnqueue = {
                val firmware = selectedFirmware ?: return@OtaQueueControls
                var added = 0
                selectedById(devices, selectedIds) { it.deviceId }.forEach { device ->
                    val ok =
                        controller.enqueue(
                            OtaQueueTarget(
                                deviceId = device.deviceId,
                                displayName = device.displayName,
                                firmwareId = firmware.id,
                                firmwareLabel = firmware.displayName,
                            ),
                        )
                    if (ok) added++
                }
                message = if (added == 0) strings.t(StringKey.QUEUE_DUPLICATE) else null
            },
            onStart = { controller.start() },
            onPause = { controller.pause() },
            onResume = { controller.resume() },
            onCancel = { controller.cancelCurrent() },
        )
        OtaQueueStatusSection(
            running = snapshot.running,
            pauseRequested = snapshot.pauseRequested,
            devicesEmpty = devices.isEmpty(),
            firmwareEmpty = firmwareRecords.isEmpty(),
            currentDevice = currentDevice,
        )
        OtaQueueItemList(
            items = snapshot.items,
            onSkipFailed = { controller.skipFailed(it) },
            onRemove = { controller.removeQueued(it) },
        )
        OtaQueueSummarySection(
            items = snapshot.items,
            summary = snapshot.summary,
            onCopy = {
                clipboard.setText(AnnotatedString(snapshot.summary.toText()))
                message = strings.t(StringKey.BATCH_COPIED)
            },
            onSave = {
                scope.launch {
                    val fileName = "valhalla-ota-queue-summary.txt"
                    val result =
                        fileSaver.save(
                            SaveableFile(
                                fileName = fileName,
                                content = snapshot.summary.toText(),
                                mimeType = "text/plain",
                            ),
                        )
                    message = fileSaveMessage(strings, result, fileName)
                }
            },
        )
        BatchMessageText(message = message)
    }
}

@Composable
private fun OtaQueueHeader(onBack: () -> Unit) {
    val strings = LocalStrings.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            strings.t(StringKey.QUEUE_TITLE),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBack) { Text(strings.t(StringKey.BACK)) }
    }
    Text(strings.t(StringKey.QUEUE_HINT), style = MaterialTheme.typography.bodySmall)
    Text(strings.t(StringKey.QUEUE_MEMORY_ONLY), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun OtaQueueTargets(
    devices: List<BatchDeviceOption>,
    selectedIds: Set<String>,
    running: Boolean,
    onSelectedIdsChange: (Set<String>) -> Unit,
) {
    val strings = LocalStrings.current
    Text(strings.t(StringKey.QUEUE_TARGETS), style = MaterialTheme.typography.titleSmall)
    if (devices.isEmpty()) {
        EmptyStatePanel(body = strings.t(StringKey.DEVICES_EMPTY))
        return
    }
    devices.forEach { device ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = device.deviceId in selectedIds,
                onCheckedChange = { checked ->
                    onSelectedIdsChange(
                        if (checked) {
                            selectedIds + device.deviceId
                        } else {
                            selectedIds - device.deviceId
                        },
                    )
                },
                enabled = !running,
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            strings.t(StringKey.QUEUE_TARGETS) + ": " + device.displayName
                    },
            )
            Text(device.displayName, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun OtaFirmwareSection(
    firmwareRecords: List<FirmwareLibraryRecord>,
    firmwareId: String?,
    running: Boolean,
    onFirmwareSelect: (String) -> Unit,
) {
    val strings = LocalStrings.current
    Text(strings.t(StringKey.QUEUE_FIRMWARE), style = MaterialTheme.typography.titleSmall)
    if (firmwareRecords.isEmpty()) {
        EmptyStatePanel(body = strings.t(StringKey.QUEUE_FIRMWARE_EMPTY))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs)) {
        firmwareRecords.forEach { record ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = firmwareId == record.id,
                    onClick = { onFirmwareSelect(record.id) },
                    label = { Text(record.displayName) },
                    enabled = !running,
                )
                Text(
                    record.version,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = DesignTokens.Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun OtaQueueControls(
    actions: OtaQueueActions,
    onEnqueue: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        OutlinedButton(enabled = actions.canEnqueue, onClick = onEnqueue) {
            Text(strings.t(StringKey.QUEUE_ENQUEUE))
        }
        Button(enabled = actions.canStart, onClick = onStart) {
            Text(strings.t(StringKey.QUEUE_START))
        }
        OutlinedButton(enabled = actions.canPause, onClick = onPause) {
            Text(strings.t(StringKey.QUEUE_PAUSE))
        }
        OutlinedButton(enabled = actions.canResume, onClick = onResume) {
            Text(strings.t(StringKey.QUEUE_RESUME))
        }
        OutlinedButton(enabled = actions.canCancel, onClick = onCancel) {
            Text(strings.t(StringKey.QUEUE_CANCEL_CURRENT))
        }
    }
}

@Composable
private fun OtaQueueStatusSection(
    running: Boolean,
    pauseRequested: Boolean,
    devicesEmpty: Boolean,
    firmwareEmpty: Boolean,
    currentDevice: String?,
) {
    val strings = LocalStrings.current
    when {
        running -> DisabledStatePanel(StringKey.STATE_DISABLED_BUSY)
        devicesEmpty -> DisabledStatePanel(StringKey.STATE_DISABLED_NOT_CONNECTED)
        firmwareEmpty -> DisabledStatePanel(StringKey.STATE_UNAVAILABLE_HINT)
    }
    if (pauseRequested) {
        Text(
            strings.t(StringKey.QUEUE_PAUSE_REQUESTED),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (running) {
        LoadingStatePanel(
            body = currentDevice?.let { name -> strings.t(StringKey.QUEUE_CURRENT, "device" to name) },
        )
    }
}

@Composable
private fun OtaQueueItemList(
    items: List<OtaQueueItem>,
    onSkipFailed: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        items.forEach { item ->
            QueueItemCard(
                item = item,
                onSkipFailed = { onSkipFailed(item.target.deviceId) },
                onRemove = { onRemove(item.target.deviceId) },
            )
        }
    }
}

@Composable
private fun OtaQueueSummarySection(
    items: List<OtaQueueItem>,
    summary: OtaQueueSummary,
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalStrings.current
    if (items.isEmpty()) {
        EmptyStatePanel(body = strings.t(StringKey.QUEUE_EMPTY))
        return
    }
    Text(strings.t(StringKey.QUEUE_SUMMARY_TITLE), style = MaterialTheme.typography.titleMedium)
    Text(
        strings.t(
            StringKey.QUEUE_SUMMARY_COUNTS,
            "total" to summary.total.toString(),
            "succeeded" to summary.succeeded.toString(),
            "failed" to summary.failed.toString(),
            "skipped" to summary.skipped.toString(),
            "aborted" to summary.aborted.toString(),
            "removed" to summary.removed.toString(),
            "pending" to summary.pending.toString(),
        ),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (summary.checkpointRetained > 0) {
        Text(
            strings.t(
                StringKey.QUEUE_CHECKPOINT_RETAINED,
                "count" to summary.checkpointRetained.toString(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        TextButton(onClick = onCopy) { Text(strings.t(StringKey.BATCH_COPY)) }
        TextButton(onClick = onSave) { Text(strings.t(StringKey.BATCH_SAVE)) }
    }
}

@Composable
private fun QueueItemCard(
    item: OtaQueueItem,
    onSkipFailed: () -> Unit,
    onRemove: () -> Unit,
) {
    val strings = LocalStrings.current
    val progress = item.progress.coerceIn(0f, 1f)
    val progressText =
        strings.t(
            StringKey.QUEUE_PROGRESS,
            "stage" to strings.t(item.stage.labelKey()),
            "percent" to (progress * 100).toInt().toString(),
        )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.target.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    strings.t(item.status.labelKey()),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (item.status == OtaQueueItemStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier =
                        Modifier.semantics {
                            liveRegion =
                                if (item.status == OtaQueueItemStatus.FAILED) {
                                    LiveRegionMode.Assertive
                                } else {
                                    LiveRegionMode.Polite
                                }
                        },
                )
            }
            Text(item.target.firmwareLabel, style = MaterialTheme.typography.bodySmall)
            Text(progressText, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { progress },
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        contentDescription = progressText
                    },
            )
            item.userReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier.semantics {
                            liveRegion =
                                if (item.status == OtaQueueItemStatus.FAILED) {
                                    LiveRegionMode.Assertive
                                } else {
                                    LiveRegionMode.Polite
                                }
                        },
                )
            }
            if (item.checkpointRetained) {
                Text(
                    strings.t(StringKey.QUEUE_CHECKPOINT_RETAINED, "count" to "1"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                if (item.status == OtaQueueItemStatus.FAILED) {
                    TextButton(onClick = onSkipFailed) {
                        Text(strings.t(StringKey.QUEUE_SKIP_FAILED_DEVICES))
                    }
                }
                if (item.status == OtaQueueItemStatus.QUEUED) {
                    TextButton(onClick = onRemove) {
                        Text(strings.t(StringKey.QUEUE_REMOVE))
                    }
                }
            }
        }
    }
}
