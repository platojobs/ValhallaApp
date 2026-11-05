package com.valhalla.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.valhalla.core.batch.BatchItemStatus
import com.valhalla.core.batch.BatchOperationKind
import com.valhalla.core.batch.BatchSummary
import com.valhalla.core.batch.BatchTarget
import com.valhalla.core.config.ConfigPreset
import com.valhalla.core.firmware.FirmwareLibraryRecord
import com.valhalla.core.ota.OtaQueueItem
import com.valhalla.core.ota.OtaQueueItemStatus
import com.valhalla.core.ota.OtaQueueStage
import com.valhalla.core.ota.OtaQueueTarget
import com.valhalla.feature.batch.BatchDeviceOutcome
import com.valhalla.feature.batch.BatchOperationController
import com.valhalla.feature.error.LocalErrorReporter
import com.valhalla.feature.error.launchSafely
import com.valhalla.feature.export.FileSaveStatus
import com.valhalla.feature.export.SaveableFile
import com.valhalla.feature.export.rememberFileSaver
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.ota.OtaQueueController
import com.valhalla.feature.theme.DesignTokens
import kotlinx.coroutines.launch

/** 批量/队列页面共用的「已连接设备」选项。 */
internal data class BatchDeviceOption(
    val deviceId: String,
    val displayName: String,
)

internal fun BatchItemStatus.labelKey(): StringKey = when (this) {
    BatchItemStatus.QUEUED -> StringKey.BATCH_STATUS_QUEUED
    BatchItemStatus.RUNNING -> StringKey.BATCH_STATUS_RUNNING
    BatchItemStatus.SUCCEEDED -> StringKey.BATCH_STATUS_SUCCEEDED
    BatchItemStatus.SKIPPED -> StringKey.BATCH_STATUS_SKIPPED
    BatchItemStatus.FAILED -> StringKey.BATCH_STATUS_FAILURE
    BatchItemStatus.CANCELLED -> StringKey.BATCH_STATUS_CANCELLED
    BatchItemStatus.REMOVED -> StringKey.BATCH_STATUS_REMOVED
}

/** 批量执行中的步骤代号到文案键；未知步骤直接原样展示。 */
internal fun batchStepLabelKey(step: String?): StringKey? = when (step) {
    "batch.started" -> StringKey.BATCH_STEP_STARTED
    "batch.health" -> StringKey.BATCH_STEP_HEALTH
    "batch.preset" -> StringKey.BATCH_STEP_PRESET
    null -> null
    else -> null
}

internal fun OtaQueueItemStatus.labelKey(): StringKey = when (this) {
    OtaQueueItemStatus.QUEUED -> StringKey.QUEUE_STATUS_QUEUED
    OtaQueueItemStatus.RUNNING -> StringKey.QUEUE_STATUS_RUNNING
    OtaQueueItemStatus.PAUSED -> StringKey.QUEUE_STATUS_PAUSED
    OtaQueueItemStatus.SUCCEEDED -> StringKey.QUEUE_STATUS_SUCCEEDED
    OtaQueueItemStatus.FAILED -> StringKey.QUEUE_STATUS_FAILURE
    OtaQueueItemStatus.SKIPPED -> StringKey.QUEUE_STATUS_SKIPPED
    OtaQueueItemStatus.ABORTED -> StringKey.QUEUE_STATUS_ABORTED
    OtaQueueItemStatus.REMOVED -> StringKey.QUEUE_STATUS_REMOVED
}

internal fun OtaQueueStage.labelKey(): StringKey = when (this) {
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
    val canStart = !running && selectedIds.isNotEmpty() &&
        (kind == BatchOperationKind.HEALTH_CHECK || selectedPreset != null)

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            FilterChip(
                selected = kind == BatchOperationKind.HEALTH_CHECK,
                onClick = { kind = BatchOperationKind.HEALTH_CHECK },
                label = { Text(strings.t(StringKey.BATCH_KIND_HEALTH)) },
                enabled = !running,
            )
            FilterChip(
                selected = kind == BatchOperationKind.APPLY_PRESET,
                onClick = { kind = BatchOperationKind.APPLY_PRESET },
                label = { Text(strings.t(StringKey.BATCH_KIND_PRESET)) },
                enabled = !running,
            )
        }

        if (kind == BatchOperationKind.APPLY_PRESET) {
            Text(strings.t(StringKey.BATCH_PRESET_LABEL), style = MaterialTheme.typography.titleSmall)
            if (presets.isEmpty()) {
                Text(strings.t(StringKey.BATCH_PRESET_EMPTY), style = MaterialTheme.typography.bodySmall)
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = selectedPresetId == preset.id,
                            onClick = { selectedPresetId = preset.id },
                            label = { Text(preset.name) },
                            enabled = !running,
                        )
                    }
                }
            }
        }

        Text(strings.t(StringKey.BATCH_TARGETS), style = MaterialTheme.typography.titleSmall)
        if (devices.isEmpty()) {
            Text(strings.t(StringKey.DEVICES_EMPTY), style = MaterialTheme.typography.bodySmall)
        } else {
            devices.forEach { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = device.deviceId in selectedIds,
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) {
                                selectedIds + device.deviceId
                            } else {
                                selectedIds - device.deviceId
                            }
                        },
                        enabled = !running,
                    )
                    Text(device.displayName, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
            Button(
                enabled = canStart,
                onClick = {
                    message = null
                    lastSummary = null
                    scope.launchSafely(errorReporter, "batch-run") {
                        val summary = controller.run(
                            kind = kind,
                            targets = devices
                                .filter { it.deviceId in selectedIds }
                                .map { BatchTarget(it.deviceId, it.displayName) },
                        ) { target, step ->
                            execute(kind, selectedPreset, target, step)
                        }
                        lastSummary = summary
                    }
                },
            ) { Text(strings.t(StringKey.BATCH_START)) }

            if (running) {
                OutlinedButton(onClick = { controller.cancelRemaining() }) {
                    Text(strings.t(StringKey.BATCH_CANCEL_REMAINING))
                }
            }
        }

        if (running) {
            Text(strings.t(StringKey.BATCH_RUNNING), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (cancelRequested) {
            Text(
                strings.t(StringKey.BATCH_CANCEL_REQUESTED),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            items(items, key = { it.target.deviceId }) { item ->
                var expanded by remember(item.target.deviceId) { mutableStateOf(false) }
                Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
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
                                color = if (item.status == BatchItemStatus.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
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
        }

        lastSummary?.let { summary ->
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
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(summary.toText()))
                    message = strings.t(StringKey.BATCH_COPIED)
                }) { Text(strings.t(StringKey.BATCH_COPY)) }
                TextButton(onClick = {
                    scope.launch {
                        val result = fileSaver.save(
                            SaveableFile(
                                fileName = "valhalla-batch-summary.txt",
                                content = summary.toText(),
                                mimeType = "text/plain",
                            ),
                        )
                        message = when (result.status) {
                            FileSaveStatus.SAVED -> strings.t(
                                StringKey.BATCH_SAVED,
                                "name" to "valhalla-batch-summary.txt",
                            )

                            FileSaveStatus.CANCELLED -> strings.t(StringKey.BATCH_SAVE_CANCELLED)
                            FileSaveStatus.UNAVAILABLE -> strings.t(StringKey.BATCH_SAVE_UNAVAILABLE)
                            FileSaveStatus.FAILED -> strings.t(
                                StringKey.BATCH_SAVE_FAILED,
                                "detail" to (result.message ?: strings.t(StringKey.UNKNOWN)),
                            )
                        }
                    }
                }) { Text(strings.t(StringKey.BATCH_SAVE)) }
            }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        if (!running && items.isEmpty() && devices.isNotEmpty()) {
            Text(strings.t(StringKey.BATCH_NO_TARGET), style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun batchReasonLabelKey(reason: String): StringKey? = when (reason) {
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
    val currentDevice = snapshot.currentDeviceId
        ?.let { id -> snapshot.items.firstOrNull { it.target.deviceId == id }?.target?.displayName }

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
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

        Text(strings.t(StringKey.QUEUE_TARGETS), style = MaterialTheme.typography.titleSmall)
        if (devices.isEmpty()) {
            Text(strings.t(StringKey.DEVICES_EMPTY), style = MaterialTheme.typography.bodySmall)
        } else {
            devices.forEach { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = device.deviceId in selectedIds,
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) {
                                selectedIds + device.deviceId
                            } else {
                                selectedIds - device.deviceId
                            }
                        },
                        enabled = !snapshot.running,
                    )
                    Text(device.displayName, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Text(strings.t(StringKey.QUEUE_FIRMWARE), style = MaterialTheme.typography.titleSmall)
        if (firmwareRecords.isEmpty()) {
            Text(strings.t(StringKey.QUEUE_FIRMWARE_EMPTY), style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs)) {
                firmwareRecords.forEach { record ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = firmwareId == record.id,
                            onClick = { firmwareId = record.id },
                            label = { Text(record.displayName) },
                            enabled = !snapshot.running,
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

        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
            OutlinedButton(
                enabled = !snapshot.running && selectedFirmware != null && selectedIds.isNotEmpty(),
                onClick = {
                    val firmware = selectedFirmware ?: return@OutlinedButton
                    var added = 0
                    devices.filter { it.deviceId in selectedIds }.forEach { device ->
                        val ok = controller.enqueue(
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
            ) { Text(strings.t(StringKey.QUEUE_ENQUEUE)) }

            Button(
                enabled = !snapshot.running && hasQueued,
                onClick = { controller.start() },
            ) { Text(strings.t(StringKey.QUEUE_START)) }

            OutlinedButton(
                enabled = snapshot.running,
                onClick = { controller.pause() },
            ) { Text(strings.t(StringKey.QUEUE_PAUSE)) }

            OutlinedButton(
                enabled = !snapshot.running && hasPaused,
                onClick = { controller.resume() },
            ) { Text(strings.t(StringKey.QUEUE_RESUME)) }

            OutlinedButton(
                enabled = snapshot.running,
                onClick = { controller.cancelCurrent() },
            ) { Text(strings.t(StringKey.QUEUE_CANCEL_CURRENT)) }
        }

        if (snapshot.pauseRequested) {
            Text(
                strings.t(StringKey.QUEUE_PAUSE_REQUESTED),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        currentDevice?.let { name ->
            Text(
                strings.t(StringKey.QUEUE_CURRENT, "device" to name),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            items(snapshot.items, key = { it.target.deviceId }) { item ->
                QueueItemCard(
                    item = item,
                    onSkipFailed = { controller.skipFailed(item.target.deviceId) },
                    onRemove = { controller.removeQueued(item.target.deviceId) },
                )
            }
        }

        if (snapshot.items.isNotEmpty()) {
            val summary = snapshot.summary
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
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(summary.toText()))
                    message = strings.t(StringKey.BATCH_COPIED)
                }) { Text(strings.t(StringKey.BATCH_COPY)) }
                TextButton(onClick = {
                    scope.launch {
                        val result = fileSaver.save(
                            SaveableFile(
                                fileName = "valhalla-ota-queue-summary.txt",
                                content = summary.toText(),
                                mimeType = "text/plain",
                            ),
                        )
                        message = when (result.status) {
                            FileSaveStatus.SAVED -> strings.t(
                                StringKey.BATCH_SAVED,
                                "name" to "valhalla-ota-queue-summary.txt",
                            )

                            FileSaveStatus.CANCELLED -> strings.t(StringKey.BATCH_SAVE_CANCELLED)
                            FileSaveStatus.UNAVAILABLE -> strings.t(StringKey.BATCH_SAVE_UNAVAILABLE)
                            FileSaveStatus.FAILED -> strings.t(
                                StringKey.BATCH_SAVE_FAILED,
                                "detail" to (result.message ?: strings.t(StringKey.UNKNOWN)),
                            )
                        }
                    }
                }) { Text(strings.t(StringKey.BATCH_SAVE)) }
            }
        } else {
            Text(strings.t(StringKey.QUEUE_EMPTY), style = MaterialTheme.typography.bodySmall)
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun QueueItemCard(
    item: OtaQueueItem,
    onSkipFailed: () -> Unit,
    onRemove: () -> Unit,
) {
    val strings = LocalStrings.current
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
                    color = if (item.status == OtaQueueItemStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(item.target.firmwareLabel, style = MaterialTheme.typography.bodySmall)
            Text(
                strings.t(
                    StringKey.QUEUE_PROGRESS,
                    "stage" to strings.t(item.stage.labelKey()),
                    "percent" to ((item.progress.coerceIn(0f, 1f)) * 100).toInt().toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { item.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            item.userReason?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall)
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
