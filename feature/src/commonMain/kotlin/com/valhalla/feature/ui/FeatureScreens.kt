package com.valhalla.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.valhalla.core.automation.AutomationAction
import com.valhalla.core.automation.AutomationRule
import com.valhalla.core.automation.AutomationRuleRepository
import com.valhalla.core.automation.AutomationTrigger
import com.valhalla.core.config.ConfigCatalog
import com.valhalla.core.config.ConfigPreset
import com.valhalla.core.config.ConfigPresetDiffer
import com.valhalla.core.config.ConfigPresetRepository
import com.valhalla.core.config.PresetFieldDiff
import com.valhalla.core.config.PresetFieldStatus
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventTimeline
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.metrics.DeviceUsage
import com.valhalla.core.metrics.UsageRange
import com.valhalla.core.metrics.UsageStatistics
import com.valhalla.core.metrics.UsageStatisticsCalculator
import com.valhalla.core.profile.DeviceProfileRepository
import com.valhalla.data.event.createDeviceEventStore
import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.share.currentTimeZoneOffsetMinutes
import com.valhalla.feature.config.ConfigEditorController
import com.valhalla.feature.config.ConfigPresetApplier
import com.valhalla.feature.config.PresetApplyReport
import com.valhalla.feature.config.PresetApplyStatus
import com.valhalla.feature.export.FileSaveStatus
import com.valhalla.feature.export.SaveableFile
import com.valhalla.feature.export.rememberFileSaver
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.i18n.render
import com.valhalla.feature.settings.BackupParseResult
import com.valhalla.feature.settings.BackupPlanItem
import com.valhalla.feature.settings.BackupSection
import com.valhalla.feature.settings.PreferencesBackupParser
import com.valhalla.feature.settings.PreferencesBackupService
import com.valhalla.feature.settings.PreferencesReset
import com.valhalla.feature.theme.DesignTokens
import com.valhalla.feature.uilogic.editableLoadedConfigFields
import com.valhalla.feature.uilogic.labelKey
import com.valhalla.feature.uilogic.usageCountText
import com.valhalla.feature.uilogic.usageDurationText
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 自动化动作的本地化文案。 */
internal fun AutomationAction.stringKey(): StringKey =
    when (this) {
        AutomationAction.READ_DEVICE_INFO -> StringKey.AUTOMATION_ACTION_READ_INFO
        AutomationAction.READ_BATTERY -> StringKey.AUTOMATION_ACTION_READ_BATTERY
        AutomationAction.RUN_HEALTH_CHECK -> StringKey.AUTOMATION_ACTION_HEALTH
        AutomationAction.APPLY_PRESET -> StringKey.AUTOMATION_ACTION_APPLY_PRESET
        AutomationAction.READ_FIRMWARE_VERSION -> StringKey.AUTOMATION_ACTION_READ_FIRMWARE
        AutomationAction.GENERATE_DIAGNOSTIC -> StringKey.AUTOMATION_ACTION_DIAGNOSTIC
    }

internal fun AutomationTrigger.stringKey(): StringKey =
    when (this) {
        AutomationTrigger.CONNECTED -> StringKey.AUTOMATION_TRIGGER_CONNECTED
        AutomationTrigger.OTA_SUCCESS -> StringKey.AUTOMATION_TRIGGER_OTA
    }

internal fun BackupSection.stringKey(): StringKey =
    when (this) {
        BackupSection.LANGUAGE -> StringKey.BACKUP_SECTION_LANGUAGE
        BackupSection.THEME -> StringKey.BACKUP_SECTION_THEME
        BackupSection.RECONNECT -> StringKey.BACKUP_SECTION_RECONNECT
        BackupSection.LOG_CAPACITY -> StringKey.BACKUP_SECTION_LOG_CAPACITY
        BackupSection.EXPORT_DEFAULTS -> StringKey.BACKUP_SECTION_EXPORT_DEFAULTS
        BackupSection.PRESETS -> StringKey.BACKUP_SECTION_PRESETS
        BackupSection.AUTOMATION_RULES -> StringKey.BACKUP_SECTION_AUTOMATION_RULES
        BackupSection.ONBOARDING -> StringKey.BACKUP_SECTION_ONBOARDING
    }

@OptIn(ExperimentalTime::class)
@Composable
internal fun PresetSection(
    presets: ConfigPresetRepository,
    applier: ConfigPresetApplier,
    config: ConfigEditorController,
    deviceId: String,
    events: DeviceEventTimeline,
    writeLocked: Boolean = false,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val scope = rememberCoroutineScope()
    val configState by config.state.collectAsState()
    val now = remember { Clock.System.now().toEpochMilliseconds() }
    var version by remember { mutableIntStateOf(0) }
    val items = remember(version, configState.fields) { presets.list() }
    val currentValues = remember(configState) { editableLoadedConfigFields(configState.visibleFields).associate { it.spec.key to it.text } }
    var nameDialog by remember { mutableStateOf<PresetNameDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<ConfigPreset?>(null) }
    var applyTarget by remember { mutableStateOf<Pair<ConfigPreset, List<PresetFieldDiff>>?>(null) }
    var applyReport by remember { mutableStateOf<Pair<ConfigPreset, PresetApplyReport>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val configError = configState.error

    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        PresetStatusSection(
            loading = configState.loading,
            errorText = configError?.let { strings.render(it) },
            currentValueCount = currentValues.size,
            writeLocked = writeLocked,
            presetsEmpty = items.isEmpty(),
            onRetry = { scope.launch { config.load() } },
        )
        PresetListSection(
            items = items,
            canApply = currentValues.isNotEmpty() && !writeLocked,
            now = now,
            format = format,
            onApplyClick = { preset ->
                val diffs = ConfigPresetDiffer.diff(preset, configState.target, currentValues)
                if (diffs.none { it.status == PresetFieldStatus.WILL_CHANGE }) {
                    message = strings.t(StringKey.PRESET_NO_DIFF)
                } else {
                    applyTarget = preset to diffs
                }
            },
            onRename = { nameDialog = PresetNameDialog.Rename(it) },
            onDuplicate = { preset ->
                if (presets.duplicate(preset.id) != null) {
                    version++
                    message = strings.t(StringKey.PRESET_DUPLICATED_TOAST)
                }
            },
            onDelete = { deleteTarget = it },
        )
        PresetSaveButton(
            enabled = currentValues.isNotEmpty(),
            onClick = { nameDialog = newPresetSaveDialog(items.size, strings) },
        )
        PresetMessageText(message = message)
    }

    nameDialog?.let { dialog ->
        PresetNameDialogContent(
            dialog = dialog,
            currentValues = currentValues,
            presets = presets,
            onDismiss = { nameDialog = null },
            onSaved = { text ->
                nameDialog = null
                version++
                message = text
            },
        )
    }

    deleteTarget?.let { preset ->
        PresetDeleteDialog(
            preset = preset,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                if (presets.delete(preset.id)) {
                    version++
                    message = strings.t(StringKey.PRESET_DELETED_TOAST)
                }
                deleteTarget = null
            },
        )
    }

    applyTarget?.let { (preset, diffs) ->
        PresetApplyDialog(
            preset = preset,
            diffs = diffs,
            onDismiss = { applyTarget = null },
            onConfirm = {
                applyTarget = null
                scope.launch {
                    val (report, resultText) =
                        applyPreset(applier, presets, config, events, deviceId, preset, diffs, strings)
                    applyReport = preset to report
                    version++
                    message = resultText
                }
            },
        )
    }

    applyReport?.let { (preset, report) ->
        PresetApplyReportDialog(
            preset = preset,
            report = report,
            onDismiss = { applyReport = null },
        )
    }
}

@Composable
private fun PresetStatusSection(
    loading: Boolean,
    errorText: String?,
    currentValueCount: Int,
    writeLocked: Boolean,
    presetsEmpty: Boolean,
    onRetry: () -> Unit,
) {
    val strings = LocalStrings.current
    Text(strings.t(StringKey.PRESET_SECTION_TITLE), style = MaterialTheme.typography.titleMedium)
    Text(
        strings.t(StringKey.PRESET_KEY_COUNT, "count" to currentValueCount.toString()),
        style = MaterialTheme.typography.bodySmall,
    )
    if (loading) {
        LoadingStatePanel()
    }
    errorText?.let { error ->
        ErrorStatePanel(detail = error, onRetry = onRetry)
    }
    if (currentValueCount == 0 && !loading) {
        Text(strings.t(StringKey.PRESET_NOT_LOADED), style = MaterialTheme.typography.bodySmall)
    }
    if (writeLocked) {
        Text(
            strings.t(StringKey.BATCH_LOCKED_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        DisabledStatePanel(StringKey.STATE_DISABLED_READ_ONLY)
    }
    if (presetsEmpty && !loading && errorText == null) {
        EmptyStatePanel(body = strings.t(StringKey.PRESET_EMPTY))
    }
}

@Composable
private fun PresetListSection(
    items: List<ConfigPreset>,
    canApply: Boolean,
    now: Long,
    format: LocaleFormat,
    onApplyClick: (ConfigPreset) -> Unit,
    onRename: (ConfigPreset) -> Unit,
    onDuplicate: (ConfigPreset) -> Unit,
    onDelete: (ConfigPreset) -> Unit,
) {
    val strings = LocalStrings.current
    items.forEach { preset ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(DesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
            ) {
                Text(preset.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    strings.t(StringKey.PRESET_KEY_COUNT, "count" to preset.values.size.toString()) +
                        " · " + format.relativeTime(preset.lastUsedAtMillis, now),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                ) {
                    TextButton(
                        enabled = canApply,
                        onClick = { onApplyClick(preset) },
                    ) { Text(strings.t(StringKey.PRESET_APPLY)) }
                    TextButton(onClick = { onRename(preset) }) {
                        Text(strings.t(StringKey.PRESET_RENAME))
                    }
                    TextButton(onClick = { onDuplicate(preset) }) {
                        Text(strings.t(StringKey.PRESET_DUPLICATE))
                    }
                    TextButton(onClick = { onDelete(preset) }) {
                        Text(strings.t(StringKey.PRESET_DELETE))
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetDeleteDialog(
    preset: ConfigPreset,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t(StringKey.PRESET_DELETE_TITLE)) },
        text = {
            Text(
                strings.t(
                    StringKey.PRESET_DELETE_MESSAGE,
                    "name" to preset.name,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(strings.t(StringKey.PRESET_DELETE)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.t(StringKey.CANCEL)) }
        },
    )
}

@Composable
private fun PresetApplyReportDialog(
    preset: ConfigPreset,
    report: PresetApplyReport,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t(StringKey.PRESET_RESULT_TITLE)) },
        text = {
            Column(
                modifier =
                    Modifier.semantics {
                        liveRegion =
                            if (report.failedCount > 0) {
                                LiveRegionMode.Assertive
                            } else {
                                LiveRegionMode.Polite
                            }
                    },
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
            ) {
                Text(
                    strings.t(
                        StringKey.PRESET_RESULT_SUMMARY,
                        "written" to report.writtenCount.toString(),
                        "already" to report.alreadyCount.toString(),
                        "skipped" to report.skippedCount.toString(),
                        "failed" to report.failedCount.toString(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(preset.name, style = MaterialTheme.typography.bodySmall)
                report.results.forEach { result ->
                    val statusKey =
                        when (result.status) {
                            PresetApplyStatus.WRITTEN -> StringKey.PRESET_RESULT_WRITTEN
                            PresetApplyStatus.ALREADY_TARGET -> StringKey.PRESET_RESULT_ALREADY
                            PresetApplyStatus.SKIPPED -> StringKey.PRESET_RESULT_SKIPPED
                            PresetApplyStatus.FAILED -> StringKey.PRESET_RESULT_FAILED_LABEL
                        }
                    Text(
                        ConfigCatalog
                            .find(result.key)
                            ?.displayName
                            .orEmpty()
                            .ifEmpty { result.key } +
                            " · " + strings.t(statusKey) +
                            result.errorCode?.let { " · $it" }.orEmpty() +
                            " " + result.detail.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.t(StringKey.CLOSE)) }
        },
    )
}

@Composable
private fun PresetSaveButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val strings = LocalStrings.current
    Button(enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(strings.t(StringKey.PRESET_SAVE_CURRENT))
    }
}

private fun newPresetSaveDialog(
    itemCount: Int,
    strings: StringResolver,
): PresetNameDialog =
    PresetNameDialog.Save(
        suggested = strings.t(StringKey.PRESET_DEFAULT_NAME, "index" to (itemCount + 1).toString()),
    )

@Composable
private fun PresetMessageText(message: String?) {
    val text = message ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private suspend fun applyPreset(
    applier: ConfigPresetApplier,
    presets: ConfigPresetRepository,
    config: ConfigEditorController,
    events: DeviceEventTimeline,
    deviceId: String,
    preset: ConfigPreset,
    diffs: List<PresetFieldDiff>,
    strings: StringResolver,
): Pair<PresetApplyReport, String> {
    val report = applier.apply(diffs)
    presets.markUsed(preset.id)
    val resultText =
        strings.t(
            StringKey.PRESET_RESULT_SUMMARY,
            "written" to report.writtenCount.toString(),
            "already" to report.alreadyCount.toString(),
            "skipped" to report.skippedCount.toString(),
            "failed" to report.failedCount.toString(),
        )
    recordPresetApplyEvents(events, deviceId, preset, report, strings)
    config.load()
    return report to resultText
}

private fun recordPresetApplyEvents(
    events: DeviceEventTimeline,
    deviceId: String,
    preset: ConfigPreset,
    report: PresetApplyReport,
    strings: StringResolver,
) {
    report.results.forEach { result ->
        when (result.status) {
            PresetApplyStatus.WRITTEN ->
                events.record(
                    deviceId = deviceId,
                    type = DeviceEventType.CONFIG_CHANGE,
                    summary = strings.t(StringKey.TIMELINE_EVENT_CONFIG_SAVED),
                    result = DeviceEventResult.SUCCESS,
                    detail = "${preset.name}: ${result.key}",
                )

            PresetApplyStatus.FAILED ->
                events.record(
                    deviceId = deviceId,
                    type = DeviceEventType.CONFIG_CHANGE,
                    summary = strings.t(StringKey.TIMELINE_EVENT_CONFIG_SAVED),
                    result = DeviceEventResult.FAILURE,
                    detail = "${preset.name}: ${result.key} ${result.detail.orEmpty()}",
                    errorCode = result.errorCode,
                )

            else -> Unit
        }
    }
}

private sealed interface PresetNameDialog {
    data class Save(
        val suggested: String,
    ) : PresetNameDialog

    data class Rename(
        val preset: ConfigPreset,
    ) : PresetNameDialog
}

@Composable
private fun PresetNameDialogContent(
    dialog: PresetNameDialog,
    currentValues: Map<String, String>,
    presets: ConfigPresetRepository,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember(dialog) {
        mutableStateOf(
            when (dialog) {
                is PresetNameDialog.Rename -> dialog.preset.name
                is PresetNameDialog.Save -> dialog.suggested
            },
        )
    }
    val canSave = name.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                strings.t(
                    when (dialog) {
                        is PresetNameDialog.Rename -> StringKey.PRESET_RENAME_TITLE
                        is PresetNameDialog.Save -> StringKey.PRESET_NAME_LABEL
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(strings.t(StringKey.PRESET_NAME_LABEL)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    when (dialog) {
                        is PresetNameDialog.Rename -> {
                            if (presets.rename(dialog.preset.id, name)) {
                                onSaved(strings.t(StringKey.PRESET_RENAMED_TOAST))
                            }
                        }

                        is PresetNameDialog.Save -> {
                            val saved = presets.saveFromValues(name, currentValues)
                            onSaved(
                                saved?.let {
                                    strings.t(StringKey.PRESET_SAVED_TOAST, "name" to it.name)
                                } ?: strings.t(StringKey.PRESET_EMPTY),
                            )
                        }
                    }
                },
            ) { Text(strings.t(StringKey.SAVE)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.t(StringKey.CANCEL)) }
        },
    )
}

@Composable
private fun PresetApplyDialog(
    preset: ConfigPreset,
    diffs: List<com.valhalla.core.config.PresetFieldDiff>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t(StringKey.PRESET_APPLY_TITLE, "name" to preset.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                diffs.forEach { diff ->
                    val statusKey =
                        when (diff.status) {
                            PresetFieldStatus.WILL_CHANGE -> StringKey.PRESET_STATUS_CHANGE
                            PresetFieldStatus.ALREADY_TARGET -> StringKey.PRESET_STATUS_ALREADY
                            PresetFieldStatus.UNSUPPORTED -> StringKey.PRESET_STATUS_UNSUPPORTED
                        }
                    Text(
                        (ConfigCatalog.find(diff.key)?.displayName ?: diff.key) +
                            " · " + strings.t(statusKey),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        strings.t(
                            StringKey.PRESET_DIFF_CURRENT_TO_TARGET,
                            "current" to (diff.currentValue ?: "—"),
                            "target" to diff.targetValue,
                        ) + diff.reason?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(strings.t(StringKey.PRESET_APPLY_CONFIRM)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.t(StringKey.CANCEL)) }
        },
    )
}

@OptIn(ExperimentalTime::class)
@Composable
fun AutomationScreen(
    rules: AutomationRuleRepository,
    presets: ConfigPresetRepository,
    lastDiagnosticReport: String?,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    var version by remember { mutableIntStateOf(0) }
    val items = remember(version) { rules.list() }
    val presetItems = remember(version) { presets.list() }
    var adding by remember { mutableStateOf(false) }

    InfoScreen(title = strings.t(StringKey.AUTOMATION_TITLE), onBack = onBack) {
        Text(
            strings.t(StringKey.AUTOMATION_FOREGROUND_ONLY),
            style = MaterialTheme.typography.bodySmall,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(DesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            ) {
                if (items.isEmpty()) {
                    EmptyStatePanel(body = strings.t(StringKey.AUTOMATION_EMPTY))
                }
                items.forEachIndexed { index, rule ->
                    AutomationRuleRow(
                        ui =
                            AutomationRuleRowUi(
                                rule = rule,
                                actionLabel = strings.t(rule.action.stringKey()),
                                triggerLabel = strings.t(rule.trigger.stringKey()),
                                enabledLabel = strings.t(StringKey.AUTOMATION_ENABLED),
                                canMoveUp = index > 0,
                                canMoveDown = index < items.lastIndex,
                                upLabel = strings.t(StringKey.AUTOMATION_MOVE_UP),
                                downLabel = strings.t(StringKey.AUTOMATION_MOVE_DOWN),
                                deleteLabel = strings.t(StringKey.AUTOMATION_DELETE),
                            ),
                        actions =
                            AutomationRuleRowActions(
                                onToggle = {
                                    rules.setEnabled(rule.id, !rule.enabled)
                                    version++
                                },
                                onMoveUp = {
                                    if (rules.move(rule.id, -1)) version++
                                },
                                onMoveDown = {
                                    if (rules.move(rule.id, 1)) version++
                                },
                                onDelete = {
                                    if (rules.delete(rule.id)) version++
                                },
                            ),
                    )
                }
            }
        }
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
            Text(strings.t(StringKey.AUTOMATION_ADD))
        }
        lastDiagnosticReport?.takeIf { it.isNotBlank() }?.let { report ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                ) {
                    Text(
                        strings.t(StringKey.AUTOMATION_ACTION_DIAGNOSTIC),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }

    if (adding) {
        AddRuleDialog(
            presets = presetItems,
            onDismiss = { adding = false },
            onAdd = { trigger, action, presetId ->
                val added = rules.add(trigger, action, presetId)
                adding = false
                if (added != null) version++
            },
        )
    }
}

private data class AutomationRuleRowUi(
    val rule: AutomationRule,
    val actionLabel: String,
    val triggerLabel: String,
    val enabledLabel: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val upLabel: String,
    val downLabel: String,
    val deleteLabel: String,
)

private data class AutomationRuleRowActions(
    val onToggle: () -> Unit,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
private fun AutomationRuleRow(
    ui: AutomationRuleRowUi,
    actions: AutomationRuleRowActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        Text("${ui.triggerLabel} · ${ui.actionLabel}", style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(ui.enabledLabel, style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = ui.rule.enabled,
                onCheckedChange = { actions.onToggle() },
                modifier =
                    Modifier.semantics { contentDescription = ui.enabledLabel },
            )
            TextButton(onClick = actions.onMoveUp, enabled = ui.canMoveUp) { Text(ui.upLabel) }
            TextButton(onClick = actions.onMoveDown, enabled = ui.canMoveDown) { Text(ui.downLabel) }
            TextButton(onClick = actions.onDelete) { Text(ui.deleteLabel) }
        }
    }
}

@Composable
private fun AddRuleDialog(
    presets: List<ConfigPreset>,
    onDismiss: () -> Unit,
    onAdd: (AutomationTrigger, AutomationAction, String?) -> Unit,
) {
    val strings = LocalStrings.current
    var trigger by remember { mutableStateOf(AutomationTrigger.CONNECTED) }
    var action by remember { mutableStateOf(AutomationAction.READ_DEVICE_INFO) }
    var presetId by remember { mutableStateOf<String?>(presets.firstOrNull()?.id) }
    val needsPreset = action == AutomationAction.APPLY_PRESET
    val canAdd = !needsPreset || presetId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t(StringKey.AUTOMATION_ADD)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(strings.t(StringKey.AUTOMATION_TRIGGER), style = MaterialTheme.typography.labelMedium)
                AutomationTrigger.entries.forEach { entry ->
                    FilterChip(
                        selected = trigger == entry,
                        onClick = { trigger = entry },
                        label = { Text(strings.t(entry.stringKey())) },
                    )
                }
                Text(strings.t(StringKey.AUTOMATION_ACTION), style = MaterialTheme.typography.labelMedium)
                AutomationAction.entries.forEach { entry ->
                    FilterChip(
                        selected = action == entry,
                        onClick = {
                            action = entry
                            if (entry == AutomationAction.APPLY_PRESET && presetId == null) {
                                presetId = presets.firstOrNull()?.id
                            }
                        },
                        label = { Text(strings.t(entry.stringKey())) },
                    )
                }
                if (needsPreset) {
                    Text(strings.t(StringKey.AUTOMATION_PRESET_SELECT), style = MaterialTheme.typography.labelMedium)
                    if (presets.isEmpty()) {
                        EmptyStatePanel(body = strings.t(StringKey.AUTOMATION_NO_PRESET))
                        DisabledStatePanel(StringKey.STATE_UNAVAILABLE_HINT)
                    } else {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = presetId == preset.id,
                                onClick = { presetId = preset.id },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canAdd,
                onClick = { onAdd(trigger, action, if (needsPreset) presetId else null) },
            ) { Text(strings.t(StringKey.AUTOMATION_SAVE_RULE)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.t(StringKey.CANCEL)) }
        },
    )
}

@OptIn(ExperimentalTime::class)
@Composable
fun UsageStatsScreen(
    profileRepository: DeviceProfileRepository,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    var range by remember { mutableStateOf(UsageRange.LAST_7_DAYS) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val now = remember(refreshKey) { Clock.System.now().toEpochMilliseconds() }
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds()) }
    val eventTimeline = remember { DeviceEventTimeline(createDeviceEventStore()) }
    val profiles = remember(refreshKey) { profileRepository.list() }
    val eventsByDevice =
        remember(refreshKey, profiles) {
            profiles.associate { profile -> profile.deviceId to eventTimeline.list(profile.deviceId) }
        }
    val stats =
        remember(refreshKey, range, profiles) {
            UsageStatisticsCalculator.compute(profiles, eventsByDevice, now, range)
        }

    InfoScreen(title = strings.t(StringKey.STATS_TITLE), onBack = onBack) {
        Text(strings.t(StringKey.STATS_LOCAL_ONLY), style = MaterialTheme.typography.bodySmall)
        UsageRangeSelector(range = range, onRangeChange = { range = it })
        TextButton(onClick = { refreshKey++ }) { Text(strings.t(StringKey.REFRESH)) }
        UsageStatsCards(stats = stats, format = format)
        Text(strings.t(StringKey.STATS_DEVICES), style = MaterialTheme.typography.titleMedium)
        if (stats.devices.isEmpty()) {
            EmptyStatePanel(body = strings.t(StringKey.STATS_NO_DATA))
        } else {
            UsageStatsDeviceList(
                devices = stats.devices,
                format = format,
                zoneOffsetMinutes = zoneOffsetMinutes,
            )
        }
    }
}

@Composable
private fun UsageRangeSelector(
    range: UsageRange,
    onRangeChange: (UsageRange) -> Unit,
) {
    val strings = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        UsageRange.entries.forEach { entry ->
            FilterChip(
                selected = range == entry,
                onClick = { onRangeChange(entry) },
                label = { Text(strings.t(entry.labelKey())) },
            )
        }
    }
}

@Composable
private fun UsageStatsCards(
    stats: UsageStatistics,
    format: LocaleFormat,
) {
    val strings = LocalStrings.current
    StatRow(strings.t(StringKey.STATS_CONNECTIONS), usageCountText(stats.connectionCount, strings, format))
    StatRow(strings.t(StringKey.STATS_CONNECTION_AVG), usageDurationText(stats.connectionAverageMillis, strings, format))
    StatRow(strings.t(StringKey.STATS_OTA), usageCountText(stats.otaAttempts, strings, format))
    StatRow(
        strings.t(StringKey.STATS_OTA_SUCCESS_RATE),
        stats.otaSuccessRate?.let { format.percent((it * 100.0).toLong(), 100L) },
    )
    StatRow(strings.t(StringKey.STATS_OTA_AVG), usageDurationText(stats.otaAverageMillis, strings, format))
    StatRow(strings.t(StringKey.STATS_CONFIG_WRITES), usageCountText(stats.configWrites, strings, format))
    StatRow(strings.t(StringKey.STATS_PARAM_WRITES), usageCountText(stats.paramWrites, strings, format))
    StatRow(strings.t(StringKey.STATS_HEALTH), usageCountText(stats.healthChecks, strings, format))
}

@Composable
private fun UsageStatsDeviceList(
    devices: List<DeviceUsage>,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
) {
    val strings = LocalStrings.current
    devices.forEach { device ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(DesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
            ) {
                Text(
                    device.displayName ?: device.deviceId,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    strings.t(
                        StringKey.STATS_DEVICE_ROW,
                        "connections" to format.number(device.connections),
                        "ota" to format.number(device.otaAttempts),
                        "writes" to format.number(device.configWrites + device.paramWrites),
                        "health" to format.number(device.healthChecks),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                device.lastConnectedAtEpochMillis?.let { last ->
                    Text(
                        strings.t(
                            StringKey.STATS_LAST_CONNECTED,
                            "time" to format.absoluteTime(last, zoneOffsetMinutes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String?,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value ?: strings.t(StringKey.STATS_NO_DATA),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalTime::class)
@Composable
fun BackupScreen(
    store: AppSettingsStore,
    presets: ConfigPresetRepository,
    rules: AutomationRuleRepository,
    onReloadPersisted: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val fileSaver = rememberFileSaver()
    var jsonText by remember { mutableStateOf("") }
    var parseResult by remember { mutableStateOf<BackupParseResult?>(null) }
    var plan by remember { mutableStateOf<List<BackupPlanItem>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var resetConfirm by remember { mutableStateOf(false) }

    InfoScreen(title = strings.t(StringKey.BACKUP_TITLE), onBack = onBack) {
        BackupOverviewCard()
        BackupExportSection(
            onExport = {
                val snapshot = PreferencesBackupService.snapshot(store, presets, rules)
                val fileName = "valhalla-preferences-${Clock.System.now().toEpochMilliseconds()}.json"
                scope.launch {
                    val result = fileSaver.save(SaveableFile(fileName, snapshot.toJson(), "application/json"))
                    message =
                        when (result.status) {
                            FileSaveStatus.SAVED -> strings.t(StringKey.LOGS_SAVE_SUCCESS)
                            FileSaveStatus.CANCELLED -> strings.t(StringKey.LOGS_SAVE_CANCELLED)
                            else ->
                                strings.t(
                                    StringKey.LOGS_SAVE_FAILED,
                                    "detail" to result.message.orEmpty(),
                                )
                        }
                }
            },
        )
        BackupImportSection(
            jsonText = jsonText,
            onJsonChange = {
                jsonText = it
                parseResult = null
                plan = emptyList()
            },
            onValidate = {
                when (val parsed = PreferencesBackupParser.parse(jsonText)) {
                    is BackupParseResult.Failure -> {
                        parseResult = parsed
                        message = strings.t(StringKey.BACKUP_PARSE_FAIL, "reason" to parsed.reason)
                    }

                    is BackupParseResult.Success -> {
                        parseResult = parsed
                        message = parsed.warning
                        plan =
                            if (parsed.canApply) {
                                PreferencesBackupService.plan(parsed.backup, store, presets, rules)
                            } else {
                                emptyList()
                            }
                    }
                }
            },
        )

        val parsed = parseResult
        if (parsed is BackupParseResult.Failure) {
            ErrorStatePanel(detail = parsed.reason)
        }
        if (parsed is BackupParseResult.Success) {
            BackupPreview(parsed, plan) {
                PreferencesBackupService.apply(parsed.backup, store, presets, rules)
                onReloadPersisted()
                parseResult = null
                plan = emptyList()
                jsonText = ""
                message = strings.t(StringKey.BACKUP_IMPORTED_TOAST)
            }
        }

        BackupMessageText(message = message, isError = parseResult is BackupParseResult.Failure)
        BackupFactoryResetSection(onReset = { resetConfirm = true })
    }

    if (resetConfirm) {
        BackupFactoryResetDialog(
            onDismiss = { resetConfirm = false },
            onConfirm = {
                PreferencesReset.clearAll(store, presets, rules)
                onReloadPersisted()
                resetConfirm = false
                message = strings.t(StringKey.BACKUP_FACTORY_RESET_DONE)
            },
        )
    }
}

@Composable
private fun BackupOverviewCard() {
    val strings = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(strings.t(StringKey.BACKUP_FIELDS_TITLE), style = MaterialTheme.typography.titleMedium)
            Text(strings.t(StringKey.BACKUP_FIELD_LIST), style = MaterialTheme.typography.bodySmall)
            Text(strings.t(StringKey.BACKUP_EXCLUDED_TITLE), style = MaterialTheme.typography.titleMedium)
            Text(strings.t(StringKey.BACKUP_EXCLUDED_LIST), style = MaterialTheme.typography.bodySmall)
            Text(strings.t(StringKey.BACKUP_EXCLUDED_CONTENT), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BackupExportSection(onExport: () -> Unit) {
    val strings = LocalStrings.current
    Text(strings.t(StringKey.BACKUP_EXPORT), style = MaterialTheme.typography.titleMedium)
    Text(strings.t(StringKey.BACKUP_EXPORT_DESC), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(
        onClick = onExport,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(strings.t(StringKey.BACKUP_EXPORT)) }
}

@Composable
private fun BackupImportSection(
    jsonText: String,
    onJsonChange: (String) -> Unit,
    onValidate: () -> Unit,
) {
    val strings = LocalStrings.current
    Text(strings.t(StringKey.BACKUP_IMPORT), style = MaterialTheme.typography.titleMedium)
    Text(strings.t(StringKey.BACKUP_IMPORT_DESC), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = jsonText,
        onValueChange = onJsonChange,
        label = { Text(strings.t(StringKey.BACKUP_JSON_LABEL)) },
        placeholder = { Text(strings.t(StringKey.BACKUP_PASTE_HINT)) },
        minLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onValidate,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(strings.t(StringKey.BACKUP_VALIDATE)) }
}

@Composable
private fun BackupMessageText(
    message: String?,
    isError: Boolean,
) {
    val text = message ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier.semantics {
                liveRegion =
                    if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
    )
}

@Composable
private fun BackupFactoryResetSection(onReset: () -> Unit) {
    val strings = LocalStrings.current
    Text(strings.t(StringKey.BACKUP_FACTORY_RESET), style = MaterialTheme.typography.titleMedium)
    Text(strings.t(StringKey.BACKUP_FACTORY_RESET_DESC), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(
        onClick = onReset,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(strings.t(StringKey.BACKUP_FACTORY_RESET)) }
}

@Composable
private fun BackupFactoryResetDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t(StringKey.BACKUP_FACTORY_RESET_TITLE)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                Text(strings.t(StringKey.BACKUP_FACTORY_RESET_MESSAGE))
                Text(strings.t(StringKey.BACKUP_FACTORY_RESET_KEEP))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(strings.t(StringKey.CONFIRM)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.t(StringKey.CANCEL)) }
        },
    )
}

@Composable
private fun BackupPreview(
    parsed: BackupParseResult.Success,
    plan: List<BackupPlanItem>,
    onApply: () -> Unit,
) {
    val strings = LocalStrings.current
    parsed.warning?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    if (parsed.issues.isNotEmpty()) {
        Text(strings.t(StringKey.BACKUP_ISSUES_TITLE), style = MaterialTheme.typography.titleMedium)
        parsed.issues.forEach { issue ->
            Text(
                strings.t(
                    if (issue.missing) StringKey.BACKUP_ISSUE_MISSING else StringKey.BACKUP_ISSUE_INVALID,
                    "field" to issue.field,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Text(strings.t(StringKey.BACKUP_PREVIEW_TITLE), style = MaterialTheme.typography.titleMedium)
    if (parsed.canApply && plan.isEmpty()) {
        EmptyStatePanel()
    }
    plan.forEach { item ->
        Text(
            strings.t(item.section.stringKey()) + "  " +
                strings.t(
                    StringKey.BACKUP_PREVIEW_ROW,
                    "current" to item.currentValue,
                    "incoming" to item.incomingValue,
                ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (!parsed.canApply) {
        DisabledStatePanel(StringKey.STATE_UNAVAILABLE_HINT)
    }
    Button(
        onClick = onApply,
        enabled = parsed.canApply,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(strings.t(StringKey.BACKUP_IMPORT_CONFIRM)) }
}
