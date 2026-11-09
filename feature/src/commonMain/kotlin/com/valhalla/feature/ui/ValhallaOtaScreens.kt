package com.valhalla.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.firmware.FirmwareApplicabilityEvaluator
import com.valhalla.core.firmware.FirmwareLibraryFilter
import com.valhalla.core.firmware.FirmwareLibraryOutcome
import com.valhalla.core.firmware.FirmwareLibraryRecord
import com.valhalla.core.firmware.FirmwareLibraryRepository
import com.valhalla.core.firmware.FirmwareLibrarySort
import com.valhalla.core.firmware.FirmwarePrecheckResult
import com.valhalla.core.firmware.FirmwareReuseCheck
import com.valhalla.feature.detail.DeviceDetailState
import com.valhalla.feature.error.LocalErrorReporter
import com.valhalla.feature.error.launchSafely
import com.valhalla.feature.firmware.FirmwareImportOutcome
import com.valhalla.feature.firmware.FirmwareLibraryImporter
import com.valhalla.feature.firmware.FirmwareLibraryReuse
import com.valhalla.feature.firmware.FirmwarePicker
import com.valhalla.feature.firmware.FirmwarePrecheckFactory
import com.valhalla.feature.firmware.FirmwareSelectionController
import com.valhalla.feature.firmware.FirmwareSelectionState
import com.valhalla.feature.firmware.rememberFirmwarePicker
import com.valhalla.feature.firmware.rememberFirmwareSigningKeyPem
import com.valhalla.feature.health.labelKey
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.i18n.render
import com.valhalla.feature.notification.NotificationCenter
import com.valhalla.feature.notification.NotificationDecision
import com.valhalla.feature.notification.NotificationEvent
import com.valhalla.feature.notification.NotificationPolicy
import com.valhalla.feature.notification.NotificationPresenter
import com.valhalla.feature.ota.OtaController
import com.valhalla.feature.ota.OtaPrecheckRule
import com.valhalla.feature.ota.OtaPrecheckRuleEvaluator
import com.valhalla.feature.ota.OtaPrecheckRuleStatus
import com.valhalla.feature.ota.OtaState
import com.valhalla.feature.ota.OtaWizardFlow
import com.valhalla.feature.ota.OtaWizardStep
import com.valhalla.feature.settings.AppSettings
import com.valhalla.feature.theme.DesignTokens
import com.valhalla.feature.theme.statusColors
import com.valhalla.feature.uilogic.abortReasonText
import com.valhalla.feature.uilogic.firmwareReuseCheckMessage
import com.valhalla.feature.uilogic.firmwareReuseUnavailableText
import com.valhalla.feature.uilogic.hex16
import com.valhalla.feature.uilogic.hex32
import com.valhalla.feature.uilogic.label
import com.valhalla.feature.uilogic.labelKey
import com.valhalla.feature.uilogic.otaTimelineResult
import com.valhalla.feature.uilogic.otaTimelineSummaryKey
import com.valhalla.feature.uilogic.otaWizardStepTitleKey
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
internal fun OtaScreen(
    session: DeviceSession,
    library: FirmwareLibraryRepository,
    settings: AppSettings,
    notificationPresenter: NotificationPresenter,
    notificationCenter: NotificationCenter,
    onNotificationFallback: (NotificationEvent) -> Unit,
    onBack: () -> Unit,
    onOtaSucceeded: () -> Unit,
    writeLocked: Boolean,
) {
    val strings = LocalStrings.current
    val controller = session.ota
    val state by controller.state.collectAsState()
    val detailState by session.detail.state.collectAsState()
    val picker = rememberFirmwarePicker()
    val signingKeyPem = rememberFirmwareSigningKeyPem()
    val libraryUi = rememberOtaLibraryUiState()
    val selection =
        remember(picker, session.checkpointStore, signingKeyPem) {
            FirmwareSelectionController(
                picker = picker,
                checkpointStore = session.checkpointStore,
                prechecker = FirmwarePrecheckFactory.production(signingKeyPem),
            )
        }
    val selectionState by selection.state.collectAsState()
    val running = state is OtaState.Preparing || state is OtaState.Transferring || state is OtaState.Verifying
    var wizardStep by remember { mutableStateOf(OtaWizardStep.SELECT) }

    LaunchedEffect(selection) { selection.refreshResume() }
    OtaWizardStepEffect(state, wizardStep) { wizardStep = it }
    val terminal = state is OtaState.Succeeded || state is OtaState.Failed || state is OtaState.Aborted
    OtaTerminalEffect(
        session = session,
        state = state,
        selection = selection,
        selectionState = selectionState,
        library = library,
        notifyOta = NotificationPolicy.shouldNotifyOta(settings),
        notificationSink =
            OtaNotificationSink(notificationCenter, notificationPresenter, onNotificationFallback),
        onOtaSucceeded = onOtaSucceeded,
        onLibraryRecorded = { libraryUi.version++ },
    )
    DisposableEffect(selection) {
        onDispose { selection.close() }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(DesignTokens.Spacing.lg)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        OtaScreenHeader(running = running, controller = controller, onBack = onBack)

        OtaDeviceInfoRow(detailState = detailState, session = session)

        Text(
            strings.t(
                StringKey.OTA_WIZARD_STEP,
                "current" to wizardStep.index.toString(),
                "title" to strings.t(otaWizardStepTitleKey(wizardStep)),
            ),
            style = MaterialTheme.typography.titleSmall,
        )

        if (wizardStep == OtaWizardStep.SELECT) {
            OtaFirmwareStep(
                selection = selection,
                selectionState = selectionState,
                detailState = detailState,
                running = running,
                library = library,
                libraryUi = libraryUi,
                picker = picker,
            ) { wizardStep = it }
        }

        if (wizardStep == OtaWizardStep.PRECHECK) {
            OtaPrecheckStep(selection = selection, selectionState = selectionState, detailState = detailState)
        }
        if (wizardStep == OtaWizardStep.CONFIRM) {
            OtaConfirmStep(selectionState = selectionState, detailState = detailState)
        }
        if (wizardStep == OtaWizardStep.TRANSFER || wizardStep == OtaWizardStep.RESULT) {
            OtaTransferSection(state = state, wizardStep = wizardStep, selectionState = selectionState)
        }
        OtaWizardActions(
            wizardStep = wizardStep,
            selection = selection,
            selectionState = selectionState,
            controller = controller,
            writeLocked = writeLocked,
            running = running,
            onBack = onBack,
        ) { wizardStep = it }
    }
}

private class OtaNotificationSink(
    val center: NotificationCenter,
    val presenter: NotificationPresenter,
    val onFallback: (NotificationEvent) -> Unit,
)

internal class OtaLibraryUiState {
    var version by mutableStateOf(0)
    var busyId by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var modelFilter by mutableStateOf("")
    var versionQuery by mutableStateOf("")
    var sort by mutableStateOf(FirmwareLibrarySort.RECENT)
    var renameId by mutableStateOf<String?>(null)
    var renameText by mutableStateOf("")
    var importing by mutableStateOf(false)
}

private data class OtaLibraryRow(
    val record: FirmwareLibraryRecord,
    val availability: FirmwareReuseCheck?,
    val applicable: Boolean,
    val reasonKey: StringKey?,
)

@Composable
private fun rememberOtaLibraryUiState(): OtaLibraryUiState = remember { OtaLibraryUiState() }

@Composable
private fun OtaWizardStepEffect(
    state: OtaState,
    current: OtaWizardStep,
    onStep: (OtaWizardStep) -> Unit,
) {
    LaunchedEffect(state) {
        onStep(
            when (state) {
                is OtaState.Preparing, is OtaState.Transferring, is OtaState.Verifying -> OtaWizardFlow.onRunStarted()
                is OtaState.Succeeded, is OtaState.Failed, is OtaState.Aborted -> OtaWizardFlow.onTerminal()
                is OtaState.Paused -> current
                OtaState.Idle -> current
            },
        )
    }
}

@Composable
private fun OtaTerminalEffect(
    session: DeviceSession,
    state: OtaState,
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    library: FirmwareLibraryRepository,
    notifyOta: Boolean,
    notificationSink: OtaNotificationSink,
    onOtaSucceeded: () -> Unit,
    onLibraryRecorded: () -> Unit,
) {
    val strings = LocalStrings.current
    val terminal = state is OtaState.Succeeded || state is OtaState.Failed || state is OtaState.Aborted
    LaunchedEffect(terminal, state) {
        if (!terminal) return@LaunchedEffect
        val current = state
        if (current !is OtaState.Succeeded && current !is OtaState.Failed && current !is OtaState.Aborted) {
            return@LaunchedEffect
        }
        if (current is OtaState.Succeeded) selection.clearResume() else selection.refreshResume()

        // 事件时间线：终端状态只记一次（key 为 state 实例变化才重跑）。
        session.events.record(
            deviceId = session.device.deviceId,
            type = DeviceEventType.OTA,
            summary = strings.t(otaTimelineSummaryKey(current)),
            result = otaTimelineResult(current),
            detail = (current as? OtaState.Failed)?.let { strings.render(it.reason) },
            errorCode = (current as? OtaState.Aborted)?.reason,
        )
        if (current is OtaState.Succeeded) onOtaSucceeded()

        if (recordOtaLibraryEntry(current, selection, selectionState, library, session.device.deviceId)) {
            onLibraryRecorded()
        }

        otaNotificationEvent(current, selectionState.version, session.device.deviceId, strings)?.let { event ->
            val decision =
                notificationSink.center.dispatch(
                    event = event,
                    enabled = notifyOta,
                    canNotify = notificationSink.presenter.canNotify,
                ) { title, body, target -> notificationSink.presenter.show(title, body, target) }
            if (decision == NotificationDecision.FALLBACK_BANNER) {
                notificationSink.onFallback(event)
            }
        }
    }
}

@Composable
private fun OtaScreenHeader(
    running: Boolean,
    controller: OtaController,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            strings.t(StringKey.OTA_TITLE),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                if (running) scope.launchSafely(errorReporter, "ota-abort-back") { controller.abort() }
                onBack()
            },
        ) { Text(strings.t(StringKey.BACK)) }
    }
}

@Composable
private fun OtaDeviceInfoRow(
    detailState: DeviceDetailState,
    session: DeviceSession,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        Text(
            strings.t(
                StringKey.OTA_DEVICE_LINE,
                "version" to (detailState.firmwareVersion ?: strings.t(StringKey.UNKNOWN)),
                "model" to (detailState.deviceInfo?.model ?: strings.t(StringKey.UNKNOWN)),
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { scope.launchSafely(errorReporter, "refresh-device-info") { session.detail.refreshInfo() } }) {
            Text(strings.t(StringKey.REFRESH_DEVICE_INFO))
        }
    }
}

@Composable
private fun OtaFirmwareStep(
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    detailState: DeviceDetailState,
    running: Boolean,
    library: FirmwareLibraryRepository,
    libraryUi: OtaLibraryUiState,
    picker: FirmwarePicker,
    onWizardStep: (OtaWizardStep) -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    Button(
        onClick = {
            scope.launchSafely(errorReporter, "firmware-pick") {
                val before = selection.state.value.precheck
                selection.pickAndPrecheck(
                    currentVersion = detailState.firmware,
                    currentModel = detailState.deviceInfo?.model,
                )
                val after = selection.state.value
                if (after.packageName != null && after.precheck != null && after.precheck !== before) {
                    onWizardStep(OtaWizardStep.PRECHECK)
                }
            }
        },
        enabled = !running && !selectionState.picking && !selectionState.prechecking,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (selectionState.selected) {
                strings.t(StringKey.RESELECT_FIRMWARE)
            } else {
                strings.t(StringKey.SELECT_FIRMWARE)
            },
        )
    }

    if (selectionState.picking) {
        LoadingStatePanel(body = strings.t(StringKey.OPENING_PICKER))
    }
    OtaPrecheckProgressSection(selection, selectionState)

    FirmwareCard(
        selectionState = selectionState,
        onRecheck = {
            scope.launchSafely(errorReporter, "firmware-recheck") {
                selection.recheck(
                    currentVersion = detailState.firmware,
                    currentModel = detailState.deviceInfo?.model,
                )
            }
        },
    )

    libraryUi.message?.let { message ->
        Text(
            message,
            color = status.warning,
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = message
                },
        )
    }
    OtaFirmwareLibraryCard(selection, selectionState, detailState, running, library, libraryUi, picker)
}

@Composable
private fun OtaPrecheckProgressSection(
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    if (!selectionState.prechecking) return
    LinearProgressIndicator(
        progress = { selectionState.precheckProgress },
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription =
                    strings.t(
                        StringKey.PRECHECK_PROGRESS,
                        "percent" to (selectionState.precheckProgress * 100).toInt().toString(),
                    )
                progressBarRangeInfo = ProgressBarRangeInfo(selectionState.precheckProgress, 0f..1f)
            },
        color = status.inProgress,
    )
    Text(
        strings.t(
            StringKey.PRECHECK_PROGRESS,
            "percent" to (selectionState.precheckProgress * 100).toInt().toString(),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { selection.cancelPrecheck() }) {
        Text(strings.t(StringKey.CANCEL_PRECHECK))
    }
}

@Composable
private fun OtaPrecheckStep(
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    detailState: DeviceDetailState,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    OtaPrecheckProgressSection(selection, selectionState)
    val precheck = selectionState.precheck
    if (precheck == null) {
        OtaPrecheckMissingMessage(selectionState.error)
    } else {
        OtaPrecheckRulesSection(precheck)
    }
    selectionState.error?.let { message ->
        val errorText = strings.render(message) + (selectionState.errorCode?.let { " ($it)" } ?: "")
        Text(
            errorText,
            color = status.error,
            modifier =
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = errorText
                },
        )
    }
    TextButton(
        onClick = {
            scope.launchSafely(errorReporter, "firmware-recheck") {
                selection.recheck(
                    currentVersion = detailState.firmware,
                    currentModel = detailState.deviceInfo?.model,
                )
            }
        },
        enabled = !selectionState.prechecking,
    ) {
        Text(strings.t(StringKey.RECHECK_FIRMWARE))
    }
}

@Composable
private fun OtaPrecheckMissingMessage(error: UiMessage?) {
    val strings = LocalStrings.current
    val status = statusColors()
    val precheckMessage =
        error?.let { strings.render(it) }
            ?: strings.t(StringKey.FIRMWARE_NOT_PRECHECKED)
    Text(
        precheckMessage,
        color =
            if (error != null) {
                status.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier =
            Modifier.semantics {
                liveRegion =
                    if (error != null) {
                        LiveRegionMode.Assertive
                    } else {
                        LiveRegionMode.Polite
                    }
                contentDescription = precheckMessage
            },
    )
}

@Composable
private fun OtaPrecheckRulesSection(precheck: FirmwarePrecheckResult) {
    val strings = LocalStrings.current
    val status = statusColors()
    Text(
        strings.t(StringKey.OTA_PRECHECK_RULES),
        style = MaterialTheme.typography.titleSmall,
    )
    OtaPrecheckRuleEvaluator.evaluate(precheck).forEach { rule ->
        val ruleLabel =
            when (rule.rule) {
                OtaPrecheckRule.FILE_STRUCTURE -> strings.t(StringKey.OTA_RULE_STRUCTURE)
                OtaPrecheckRule.SIZE_LIMIT -> strings.t(StringKey.OTA_RULE_SIZE)
                OtaPrecheckRule.DEVICE_MODEL -> strings.t(StringKey.OTA_RULE_MODEL)
                OtaPrecheckRule.VERSION_DIRECTION -> strings.t(StringKey.OTA_RULE_VERSION)
                OtaPrecheckRule.PAYLOAD_CRC -> strings.t(StringKey.OTA_RULE_CRC)
                OtaPrecheckRule.SIGNATURE -> strings.t(StringKey.OTA_RULE_SIGNATURE)
            }
        val blocking = rule.status == OtaPrecheckRuleStatus.BLOCKING
        Text(
            ruleLabel + " · " +
                strings.t(
                    if (blocking) StringKey.OTA_RULE_BLOCKING else StringKey.OTA_RULE_PASSED,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = if (blocking) status.error else status.success,
        )
        rule.detail?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = status.error,
            )
        }
    }
    if (precheck.warnings.isNotEmpty()) {
        Text(
            strings.t(StringKey.OTA_PRECHECK_WARNINGS),
            style = MaterialTheme.typography.labelSmall,
            color = status.warning,
        )
        precheck.warnings.forEach { warning ->
            Text(
                warning,
                style = MaterialTheme.typography.bodySmall,
                color = status.warning,
            )
        }
    }
    Text(
        strings.t(
            if (precheck.passed) {
                StringKey.FIRMWARE_PRECHECK_PASSED
            } else {
                StringKey.FIRMWARE_PRECHECK_FAILED
            },
        ),
        color = if (precheck.passed) status.success else status.error,
    )
}

@Composable
private fun OtaConfirmStep(
    selectionState: FirmwareSelectionState,
    detailState: DeviceDetailState,
) {
    OtaConfirmSummaryCard(selectionState, detailState)
    OtaConfirmResumeHint(selectionState)
}

@Composable
private fun OtaConfirmSummaryCard(
    selectionState: FirmwareSelectionState,
    detailState: DeviceDetailState,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(
                strings.t(
                    StringKey.OTA_CONFIRM_VERSION,
                    "from" to (detailState.firmwareVersion ?: strings.t(StringKey.UNKNOWN)),
                    "to" to (selectionState.version?.toString() ?: strings.t(StringKey.UNKNOWN)),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                strings.t(StringKey.OTA_CURRENT_VERSION) + ": " +
                    (detailState.firmwareVersion ?: strings.t(StringKey.UNKNOWN)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                strings.t(StringKey.OTA_TARGET_VERSION) + ": " +
                    (selectionState.version?.toString() ?: strings.t(StringKey.UNKNOWN)),
                style = MaterialTheme.typography.bodySmall,
            )
            val battery = detailState.batteryPercent
            Text(
                if (battery != null) {
                    strings.t(StringKey.OTA_CONFIRM_BATTERY, "percent" to battery.toString())
                } else {
                    strings.t(StringKey.OTA_CONFIRM_BATTERY_UNKNOWN)
                },
                color =
                    if (battery != null && battery >= 50) {
                        status.success
                    } else {
                        status.warning
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                strings.t(StringKey.OTA_CONFIRM_KEEP_CONNECTED),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                strings.t(StringKey.OTA_CONFIRM_RESUME_NOTE),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OtaConfirmResumeHint(selectionState: FirmwareSelectionState) {
    val strings = LocalStrings.current
    val status = statusColors()
    val resumePoint = selectionState.resumePoint
    if (resumePoint != null && selectionState.canResume) {
        Text(
            strings.t(
                StringKey.OTA_RESUME_FROM,
                "percent" to
                    OtaWizardFlow
                        .resumePercent(
                            resumePoint.sentBytes.toLong(),
                            resumePoint.totalBytes.toLong(),
                        ).toString(),
            ),
            color = status.inProgress,
        )
    } else {
        selectionState.resumeBlockedReason?.let { reason ->
            Text(
                strings.t(StringKey.OTA_RESUME_INVALID, "reason" to strings.render(reason)),
                color = status.warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (selectionState.resumeBlockedReason == null) {
            Text(
                strings.t(StringKey.OTA_RESULT_RESUME_NONE),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OtaTransferSection(
    state: OtaState,
    wizardStep: OtaWizardStep,
    selectionState: FirmwareSelectionState,
) {
    when (val current = state) {
        OtaState.Idle -> Text(LocalStrings.current.t(StringKey.OTA_IDLE))
        is OtaState.Preparing -> OtaPreparingContent(current)
        is OtaState.Transferring -> OtaTransferringContent(current)
        is OtaState.Verifying -> OtaVerifyingContent(current)
        is OtaState.Succeeded -> OtaSucceededContent(current)
        is OtaState.Paused -> OtaPausedContent(current)
        is OtaState.Aborted -> OtaAbortedContent(current)
        is OtaState.Failed -> OtaFailedContent(current)
    }
    if (wizardStep == OtaWizardStep.RESULT) {
        OtaResultResumeHint(state, selectionState)
    }
}

@Composable
private fun OtaPreparingContent(current: OtaState.Preparing) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val preparingText =
        strings.t(
            StringKey.OTA_PREPARING,
            "bytes" to format.bytes(current.totalBytes.toLong()),
            "crc" to hex16(current.crc16),
            "session" to (
                current.sessionId?.let {
                    strings.t(StringKey.OTA_SESSION_SUFFIX, "id" to it.toString())
                } ?: ""
            ),
        )
    Text(
        preparingText,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = preparingText
            },
    )
}

@Composable
private fun OtaTransferringContent(current: OtaState.Transferring) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val progressText =
        strings.t(
            StringKey.OTA_TRANSFERRING,
            "stage" to current.stage.label(strings),
            "sent" to format.number(current.sentBytes),
            "total" to format.number(current.totalBytes),
            "percent" to (current.progress * 100).toInt().toString(),
            "resume" to "",
        )
    LinearProgressIndicator(
        progress = { current.progress },
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = progressText
                progressBarRangeInfo = ProgressBarRangeInfo(current.progress, 0f..1f)
            },
        color = status.inProgress,
    )
    val resumeNote =
        if (current.initialOffset > 0) {
            strings.t(StringKey.OTA_RESUME_NOTE, "offset" to format.bytes(current.initialOffset.toLong()))
        } else {
            ""
        }
    val transferringText =
        strings.t(
            StringKey.OTA_TRANSFERRING,
            "stage" to current.stage.label(strings),
            "sent" to format.number(current.sentBytes),
            "total" to format.number(current.totalBytes),
            "percent" to (current.progress * 100).toInt().toString(),
            "resume" to resumeNote,
        )
    Text(
        transferringText,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = transferringText
            },
    )
    if (current.retransmissions > 0) {
        Text(
            strings.t(
                StringKey.OTA_RETRANSMISSIONS,
                "count" to format.number(current.retransmissions),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    current.resumeFallbackReason?.let { reason ->
        Text(
            strings.render(reason),
            color = status.warning,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun OtaVerifyingContent(current: OtaState.Verifying) {
    val strings = LocalStrings.current
    val verifyingText = strings.t(StringKey.OTA_VERIFYING, "crc" to hex16(current.crc16))
    Text(
        verifyingText,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = verifyingText
            },
    )
}

@Composable
private fun OtaSucceededContent(current: OtaState.Succeeded) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val succeededText =
        strings.t(
            StringKey.OTA_SUCCEEDED,
            "chunks" to format.number(current.chunks),
            "crc" to hex16(current.crc16),
        ) + if (current.rebootRequired) strings.t(StringKey.OTA_REBOOT_REQUIRED) else ""
    Text(
        succeededText,
        color = status.success,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = succeededText
            },
    )
}

@Composable
private fun OtaPausedContent(current: OtaState.Paused) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val pausedText =
        strings.t(
            StringKey.OTA_PAUSED_SAFE_POINT,
            "sent" to format.number(current.sentBytes),
            "total" to format.number(current.totalBytes),
        )
    Text(
        pausedText,
        color = status.warning,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = pausedText
            },
    )
}

@Composable
private fun OtaAbortedContent(current: OtaState.Aborted) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val abortedText =
        strings.t(
            StringKey.OTA_ABORTED,
            "reason" to abortReasonText(current.reason, strings),
            "sent" to format.number(current.sentBytes),
            "total" to format.number(current.totalBytes),
        )
    Text(
        abortedText,
        color = status.warning,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = abortedText
            },
    )
}

@Composable
private fun OtaFailedContent(current: OtaState.Failed) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        val failedText = strings.t(StringKey.OTA_FAILED, "reason" to strings.render(current.reason))
        Text(
            failedText,
            color = status.error,
            modifier =
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = failedText
                },
        )
        Text(
            strings.t(
                StringKey.OTA_SENT_BYTES,
                "sent" to format.number(current.sentBytes),
                "total" to format.number(current.totalBytes),
            ),
        )
        current.resumePoint?.let { point ->
            Text(
                strings.t(
                    StringKey.OTA_RESUME_SAVED,
                    "sent" to format.number(point.sentBytes),
                    "total" to format.number(point.totalBytes),
                    "session" to format.number(point.sessionId),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OtaResultResumeHint(
    state: OtaState,
    selectionState: FirmwareSelectionState,
) {
    val strings = LocalStrings.current
    val resultPoint = (state as? OtaState.Failed)?.resumePoint ?: selectionState.resumePoint
    if (resultPoint != null) {
        Text(
            strings.t(
                StringKey.OTA_RESULT_RESUME_SAVED,
                "percent" to
                    OtaWizardFlow
                        .resumePercent(
                            resultPoint.sentBytes.toLong(),
                            resultPoint.totalBytes.toLong(),
                        ).toString(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            strings.t(StringKey.OTA_RESULT_RESUME_NONE),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun OtaWizardActions(
    wizardStep: OtaWizardStep,
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    controller: OtaController,
    writeLocked: Boolean,
    running: Boolean,
    onBack: () -> Unit,
    onWizardStep: (OtaWizardStep) -> Unit,
) {
    when (wizardStep) {
        OtaWizardStep.SELECT ->
            OtaSelectActions(selection, selectionState, onBack, onWizardStep)
        OtaWizardStep.PRECHECK ->
            OtaPrecheckActions(selection, selectionState, running, onBack, onWizardStep)
        OtaWizardStep.CONFIRM ->
            OtaConfirmActions(selection, selectionState, controller, writeLocked, running, onBack, onWizardStep)
        OtaWizardStep.TRANSFER -> OtaTransferActions(controller, running)
        OtaWizardStep.RESULT -> OtaResultActions(controller, selection, onBack, onWizardStep)
    }
}

@Composable
private fun OtaSelectActions(
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    onBack: () -> Unit,
    onWizardStep: (OtaWizardStep) -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        OutlinedButton(
            onClick = {
                selection.clearSelection()
                onBack()
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.t(StringKey.OTA_CANCEL))
        }
        Button(
            onClick = {
                onWizardStep(
                    OtaWizardFlow.next(
                        OtaWizardStep.SELECT,
                        selectionReady = selectionState.packageName != null,
                        precheckPassed = selectionState.precheck?.passed == true,
                    ),
                )
            },
            enabled =
                selectionState.packageName != null &&
                    !selectionState.picking &&
                    !selectionState.prechecking,
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.t(StringKey.OTA_NEXT))
        }
    }
}

@Composable
private fun OtaPrecheckActions(
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    running: Boolean,
    onBack: () -> Unit,
    onWizardStep: (OtaWizardStep) -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        TextButton(
            onClick = {
                onWizardStep(OtaWizardFlow.previous(OtaWizardStep.PRECHECK, running))
            },
        ) {
            Text(strings.t(StringKey.OTA_PREVIOUS))
        }
        OutlinedButton(
            onClick = {
                selection.clearSelection()
                onBack()
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.t(StringKey.OTA_CANCEL))
        }
        Button(
            onClick = {
                onWizardStep(
                    OtaWizardFlow.next(
                        OtaWizardStep.PRECHECK,
                        selectionReady = selectionState.packageName != null,
                        precheckPassed = selectionState.precheck?.passed == true,
                    ),
                )
            },
            enabled = selectionState.precheck?.passed == true && !selectionState.prechecking,
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.t(StringKey.OTA_NEXT))
        }
    }
}

@Composable
private fun OtaConfirmActions(
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    controller: OtaController,
    writeLocked: Boolean,
    running: Boolean,
    onBack: () -> Unit,
    onWizardStep: (OtaWizardStep) -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    Column(
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
    ) {
        if (writeLocked) {
            DisabledStatePanel(reason = StringKey.STATE_DISABLED_READ_ONLY)
        }
        if (running) {
            DisabledStatePanel(reason = StringKey.STATE_DISABLED_BUSY)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            TextButton(
                onClick = {
                    onWizardStep(OtaWizardFlow.previous(OtaWizardStep.CONFIRM, running))
                },
            ) {
                Text(strings.t(StringKey.OTA_PREVIOUS))
            }
            Button(
                onClick = {
                    val firmware = selection.firmware
                    if (firmware != null) {
                        scope.launchSafely(errorReporter, "ota-start") {
                            controller.startPackage(
                                firmware,
                                knownCrc16 = selectionState.computedCrc16,
                            )
                        }
                    }
                },
                enabled = !running && selectionState.readyForOta && !writeLocked,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings.t(StringKey.OTA_START_FRESH))
            }
            TextButton(
                onClick = {
                    selection.clearSelection()
                    onBack()
                },
            ) {
                Text(strings.t(StringKey.OTA_CANCEL))
            }
        }
        val resumePoint = selectionState.resumePoint
        if (resumePoint != null && selectionState.canResume) {
            OutlinedButton(
                onClick = {
                    val firmware = selection.firmware
                    if (firmware != null) {
                        scope.launchSafely(errorReporter, "ota-resume") {
                            controller.startPackage(
                                firmware,
                                resumeFrom = resumePoint,
                                knownCrc16 = selectionState.computedCrc16,
                            )
                        }
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    strings.t(
                        StringKey.OTA_RESUME_FROM,
                        "percent" to
                            OtaWizardFlow
                                .resumePercent(
                                    resumePoint.sentBytes.toLong(),
                                    resumePoint.totalBytes.toLong(),
                                ).toString(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun OtaTransferActions(
    controller: OtaController,
    running: Boolean,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    OutlinedButton(
        onClick = {
            scope.launchSafely(errorReporter, "ota-abort") { controller.abort() }
        },
        enabled = running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(strings.t(StringKey.ABORT_OTA))
    }
}

@Composable
private fun OtaResultActions(
    controller: OtaController,
    selection: FirmwareSelectionController,
    onBack: () -> Unit,
    onWizardStep: (OtaWizardStep) -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
    ) {
        TextButton(
            onClick = {
                controller.reset()
                selection.clearSelection()
                onWizardStep(OtaWizardFlow.previous(OtaWizardStep.RESULT, running = false))
            },
        ) {
            Text(strings.t(StringKey.OTA_WIZARD_AGAIN))
        }
        Button(
            onClick = { onBack() },
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.t(StringKey.OTA_WIZARD_DONE))
        }
    }
}

@Composable
private fun OtaFirmwareLibraryCard(
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    detailState: DeviceDetailState,
    running: Boolean,
    library: FirmwareLibraryRepository,
    libraryUi: OtaLibraryUiState,
    picker: FirmwarePicker,
) {
    val strings = LocalStrings.current
    val reuse = remember(picker) { FirmwareLibraryReuse(picker) }
    val libraryImporter = remember(picker, library) { FirmwareLibraryImporter(picker, library) }
    val otaBusy = libraryUi.busyId != null || running || selectionState.picking || selectionState.prechecking
    var libraryAvailability by remember { mutableStateOf<Map<String, FirmwareReuseCheck>>(emptyMap()) }
    val libraryFilter =
        remember(libraryUi.modelFilter, libraryUi.versionQuery) {
            FirmwareLibraryFilter(
                model = libraryUi.modelFilter.takeIf { it.isNotBlank() },
                versionQuery = libraryUi.versionQuery.takeIf { it.isNotBlank() },
            )
        }
    val libraryRecords =
        remember(libraryUi.version, library, libraryFilter, libraryUi.sort) {
            library.list(libraryFilter, libraryUi.sort)
        }
    LaunchedEffect(libraryUi.version, libraryFilter, libraryUi.sort) {
        val checks = mutableMapOf<String, FirmwareReuseCheck>()
        libraryRecords.take(LIBRARY_VISIBLE_LIMIT).forEach { record ->
            val check = runCatching { reuse.check(record) }.getOrNull() ?: return@forEach
            checks[record.id] = check
        }
        libraryAvailability = checks
    }
    val currentModel = detailState.deviceInfo?.model
    val currentFirmwareVersion =
        FirmwareApplicabilityEvaluator.parseVersion(
            detailState.firmwareVersion.orEmpty(),
        )
    val latestApplicable =
        FirmwareApplicabilityEvaluator.latestApplicable(
            libraryRecords,
            currentModel,
            currentFirmwareVersion,
        )
    val rows =
        libraryRecords.take(LIBRARY_VISIBLE_LIMIT).map { record ->
            val applicability =
                FirmwareApplicabilityEvaluator.evaluate(
                    record,
                    currentModel,
                    currentFirmwareVersion,
                )
            OtaLibraryRow(
                record = record,
                availability = libraryAvailability[record.id],
                applicable = applicability.applicable,
                reasonKey = if (applicability.applicable) null else applicability.reason.labelKey(),
            )
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(strings.t(StringKey.OTA_LIBRARY_TITLE), style = MaterialTheme.typography.titleMedium)
            if (otaBusy) {
                DisabledStatePanel(reason = StringKey.STATE_DISABLED_BUSY)
            }
            Text(
                strings.t(StringKey.OTA_LIBRARY_NOTE),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                strings.t(StringKey.LIB_IMPORT_NOTE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OtaLibraryActionsRow(
                libraryImporter = libraryImporter,
                selection = selection,
                detailState = detailState,
                currentModel = currentModel,
                latestApplicable = latestApplicable,
                running = running,
                libraryUi = libraryUi,
            )
            if (latestApplicable == null && libraryRecords.isNotEmpty()) {
                Text(
                    strings.t(StringKey.LIB_NO_APPLICABLE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OtaLibraryFilterRow(libraryUi)
            if (libraryRecords.isEmpty()) {
                EmptyStatePanel(body = strings.t(StringKey.OTA_LIBRARY_EMPTY))
            } else {
                OtaLibraryRecordsList(
                    rows = rows,
                    selection = selection,
                    selectionState = selectionState,
                    detailState = detailState,
                    library = library,
                    libraryUi = libraryUi,
                    running = running,
                    currentModel = currentModel,
                    reuse = reuse,
                )
            }
        }
    }
    libraryUi.renameId?.let {
        OtaLibraryRenameDialog(library, libraryUi)
    }
}

@Composable
private fun OtaLibraryActionsRow(
    libraryImporter: FirmwareLibraryImporter,
    selection: FirmwareSelectionController,
    detailState: DeviceDetailState,
    currentModel: String?,
    latestApplicable: FirmwareLibraryRecord?,
    running: Boolean,
    libraryUi: OtaLibraryUiState,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        Button(
            enabled = !libraryUi.importing && !running,
            onClick = {
                scope.launchSafely(errorReporter, "firmware-library-import") {
                    libraryUi.importing = true
                    libraryUi.message =
                        when (val outcome = libraryImporter.importOne()) {
                            is FirmwareImportOutcome.Imported -> {
                                libraryUi.version++
                                strings.t(StringKey.LIB_IMPORT_SUCCESS, "name" to outcome.recordName)
                            }

                            is FirmwareImportOutcome.Duplicate -> {
                                libraryUi.version++
                                strings.t(StringKey.LIB_IMPORT_DUPLICATE, "name" to outcome.recordName)
                            }

                            is FirmwareImportOutcome.Rejected ->
                                strings.t(
                                    StringKey.LIB_IMPORT_REJECTED,
                                    "code" to outcome.code,
                                    "detail" to (outcome.detail ?: strings.t(StringKey.UNKNOWN)),
                                )

                            FirmwareImportOutcome.Cancelled ->
                                strings.t(StringKey.LIB_IMPORT_CANCELLED)
                        }
                    libraryUi.importing = false
                }
            },
        ) {
            Text(
                if (libraryUi.importing) {
                    strings.t(StringKey.LIB_IMPORTING)
                } else {
                    strings.t(StringKey.LIB_IMPORT)
                },
            )
        }
        Button(
            enabled = latestApplicable != null && !running && libraryUi.busyId == null,
            onClick = {
                val record = latestApplicable ?: return@Button
                scope.launchSafely(errorReporter, "firmware-library-latest") {
                    libraryUi.busyId = record.id
                    val opened =
                        selection.openFromLibrary(
                            sourcePath = record.sourcePath,
                            currentVersion = detailState.firmware,
                            currentModel = currentModel,
                        )
                    libraryUi.busyId = null
                    libraryUi.message =
                        if (opened) {
                            strings.t(StringKey.LIB_SELECTED, "name" to record.displayName)
                        } else {
                            strings.t(StringKey.OTA_LIBRARY_USE_FAILED, "detail" to "open failed")
                        }
                }
            },
        ) { Text(strings.t(StringKey.LIB_SELECT_LATEST)) }
    }
}

@Composable
private fun OtaLibraryFilterRow(libraryUi: OtaLibraryUiState) {
    val strings = LocalStrings.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = libraryUi.modelFilter,
            onValueChange = { libraryUi.modelFilter = it },
            label = { Text(strings.t(StringKey.LIB_FILTER_MODEL_ALL)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = libraryUi.versionQuery,
            onValueChange = { libraryUi.versionQuery = it },
            label = { Text(strings.t(StringKey.LIB_FILTER_VERSION)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        FilterChip(
            selected = libraryUi.sort == FirmwareLibrarySort.RECENT,
            onClick = { libraryUi.sort = FirmwareLibrarySort.RECENT },
            label = { Text(strings.t(StringKey.LIB_SORT_RECENT)) },
        )
        FilterChip(
            selected = libraryUi.sort == FirmwareLibrarySort.VERSION,
            onClick = { libraryUi.sort = FirmwareLibrarySort.VERSION },
            label = { Text(strings.t(StringKey.LIB_SORT_VERSION)) },
        )
    }
}

@Composable
private fun OtaLibraryRecordsList(
    rows: List<OtaLibraryRow>,
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    detailState: DeviceDetailState,
    library: FirmwareLibraryRepository,
    libraryUi: OtaLibraryUiState,
    running: Boolean,
    currentModel: String?,
    reuse: FirmwareLibraryReuse,
) {
    rows.forEach { row ->
        OtaLibraryRecordRow(
            row = row,
            selection = selection,
            selectionState = selectionState,
            detailState = detailState,
            library = library,
            libraryUi = libraryUi,
            running = running,
            currentModel = currentModel,
            reuse = reuse,
        )
    }
}

@Composable
private fun OtaLibraryRecordRow(
    row: OtaLibraryRow,
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    detailState: DeviceDetailState,
    library: FirmwareLibraryRepository,
    libraryUi: OtaLibraryUiState,
    running: Boolean,
    currentModel: String?,
    reuse: FirmwareLibraryReuse,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val record = row.record
    val unavailableText = firmwareReuseUnavailableText(row.availability, strings)
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OtaLibraryRecordDetails(record, row, format, status, strings, unavailableText)
            OtaLibraryUseButton(
                record = record,
                selection = selection,
                selectionState = selectionState,
                detailState = detailState,
                libraryUi = libraryUi,
                running = running,
                currentModel = currentModel,
                reuse = reuse,
            )
        }
        OtaLibraryRecordActions(record, library, libraryUi)
        OtaLibraryReselectAction(unavailableText, selection, detailState, currentModel)
    }
}

@Composable
private fun RowScope.OtaLibraryRecordDetails(
    record: FirmwareLibraryRecord,
    row: OtaLibraryRow,
    format: LocaleFormat,
    status: DesignTokens.StatusColors,
    strings: StringResolver,
    unavailableText: String?,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            record.displayName,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${record.version} · ${format.bytes(record.totalBytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (row.applicable) {
                strings.t(StringKey.LIB_APPLICABLE)
            } else {
                val reasonKey = row.reasonKey
                if (reasonKey == null) {
                    strings.t(StringKey.LIB_INAPPLICABLE)
                } else {
                    strings.t(StringKey.LIB_INAPPLICABLE) + " · " +
                        strings.t(reasonKey)
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color =
                if (row.applicable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        unavailableText?.let {
            Text(
                strings.t(StringKey.LIB_UNAVAILABLE, "reason" to it),
                style = MaterialTheme.typography.labelSmall,
                color = status.error,
            )
        }
        record.lastDeviceId?.let { device ->
            Text(
                strings.t(
                    StringKey.OTA_LIBRARY_LAST_USED,
                    "device" to device,
                    "outcome" to
                        strings.t(
                            when (record.lastOutcome) {
                                FirmwareLibraryOutcome.SUCCEEDED -> StringKey.OTA_LIBRARY_OUTCOME_SUCCEEDED
                                FirmwareLibraryOutcome.FAILED -> StringKey.OTA_LIBRARY_OUTCOME_FAIL
                                else -> StringKey.OTA_LIBRARY_OUTCOME_ABORTED
                            },
                        ),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OtaLibraryUseButton(
    record: FirmwareLibraryRecord,
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    detailState: DeviceDetailState,
    libraryUi: OtaLibraryUiState,
    running: Boolean,
    currentModel: String?,
    reuse: FirmwareLibraryReuse,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    val otaBusy = libraryUi.busyId != null || running || selectionState.picking || selectionState.prechecking
    TextButton(
        onClick = {
            if (otaBusy) {
                return@TextButton
            }
            scope.launchSafely(errorReporter, "firmware-library-use") {
                libraryUi.busyId = record.id
                libraryUi.message = strings.t(StringKey.OTA_LIBRARY_CHECKING)
                val check = reuse.check(record)
                libraryUi.busyId = null
                val opened =
                    if (check is FirmwareReuseCheck.Available) {
                        selection.openFromLibrary(
                            sourcePath = record.sourcePath,
                            currentVersion = detailState.firmware,
                            currentModel = currentModel,
                        )
                    } else {
                        false
                    }
                libraryUi.message = firmwareReuseCheckMessage(check, opened, strings)
            }
        },
        enabled = !running && libraryUi.busyId == null,
    ) {
        Text(
            if (libraryUi.busyId == record.id) {
                strings.t(StringKey.OTA_LIBRARY_CHECKING)
            } else {
                strings.t(StringKey.OTA_LIBRARY_USE)
            },
        )
    }
}

@Composable
private fun OtaLibraryRecordActions(
    record: FirmwareLibraryRecord,
    library: FirmwareLibraryRepository,
    libraryUi: OtaLibraryUiState,
) {
    val strings = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        TextButton(
            onClick = {
                library.setFavorite(record.id, !record.favorite)
                libraryUi.version++
            },
        ) {
            Text(
                strings.t(
                    if (record.favorite) {
                        StringKey.OTA_LIBRARY_UNMARK_FAVORITE
                    } else {
                        StringKey.OTA_LIBRARY_MARK_FAVORITE
                    },
                ),
            )
        }
        TextButton(
            onClick = {
                libraryUi.renameId = record.id
                libraryUi.renameText = record.alias.orEmpty()
            },
        ) {
            Text(strings.t(StringKey.LIB_RENAME))
        }
        TextButton(
            onClick = {
                library.delete(record.id)
                libraryUi.version++
            },
        ) {
            Text(strings.t(StringKey.OTA_LIBRARY_DELETE))
        }
    }
}

@Composable
private fun OtaLibraryReselectAction(
    unavailableText: String?,
    selection: FirmwareSelectionController,
    detailState: DeviceDetailState,
    currentModel: String?,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    unavailableText?.let {
        TextButton(onClick = {
            scope.launchSafely(errorReporter, "firmware-library-reselect") {
                selection.pickAndPrecheck(
                    currentVersion = detailState.firmware,
                    currentModel = currentModel,
                )
            }
        }) { Text(strings.t(StringKey.LIB_UNAVAILABLE_ACTION)) }
    }
}

@Composable
private fun OtaLibraryRenameDialog(
    library: FirmwareLibraryRepository,
    libraryUi: OtaLibraryUiState,
) {
    val strings = LocalStrings.current
    val id = libraryUi.renameId ?: return
    AlertDialog(
        onDismissRequest = { libraryUi.renameId = null },
        title = { Text(strings.t(StringKey.LIB_RENAME_TITLE)) },
        text = {
            OutlinedTextField(
                value = libraryUi.renameText,
                onValueChange = { libraryUi.renameText = it },
                label = { Text(strings.t(StringKey.LIB_RENAME_HINT)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                library.rename(id, libraryUi.renameText)
                libraryUi.version++
                libraryUi.renameId = null
            }) { Text(strings.t(StringKey.SAVE)) }
        },
        dismissButton = {
            TextButton(onClick = { libraryUi.renameId = null }) {
                Text(strings.t(StringKey.CANCEL))
            }
        },
    )
}

@Composable
private fun FirmwareCard(
    selectionState: FirmwareSelectionState,
    onRecheck: () -> Unit,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    if (selectionState.packageName == null && selectionState.error == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            selectionState.packageName?.let { name ->
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.t(
                        StringKey.FIRMWARE_VERSION_LINE,
                        "version" to selectionState.version.toString(),
                        "bytes" to format.bytes(selectionState.payloadBytes.toLong()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    strings.t(
                        StringKey.FIRMWARE_CRC32_LINE,
                        "declared" to hex32(selectionState.declaredCrc32),
                        "computed" to hex32(selectionState.computedCrc32),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    strings.t(StringKey.FIRMWARE_CRC16_LINE, "value" to hex16(selectionState.computedCrc16)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val resumePoint = selectionState.resumePoint
            if (resumePoint != null && selectionState.canResume) {
                Text(
                    strings.t(
                        StringKey.FIRMWARE_RESUME_READY,
                        "offset" to format.bytes(resumePoint.sentBytes.toLong()),
                        "sent" to format.number(resumePoint.sentBytes),
                        "total" to format.number(resumePoint.totalBytes),
                    ),
                    color = status.inProgress,
                )
            }
            selectionState.resumeBlockedReason?.let { reason ->
                val resumeBlockedText = strings.render(reason)
                Text(
                    resumeBlockedText,
                    color = status.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Assertive
                            contentDescription = resumeBlockedText
                        },
                )
            }

            val precheck = selectionState.precheck
            if (precheck == null) {
                if (selectionState.error == null) {
                    Text(strings.t(StringKey.FIRMWARE_NOT_PRECHECKED), style = MaterialTheme.typography.bodySmall)
                }
            } else if (precheck.passed) {
                Text(
                    strings.t(StringKey.FIRMWARE_PRECHECK_PASSED),
                    color = status.success,
                    modifier =
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            } else {
                Text(
                    strings.t(StringKey.FIRMWARE_PRECHECK_FAILED),
                    color = status.error,
                    modifier =
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Assertive
                        },
                )
                precheck.errors.forEach { error ->
                    val errorText = "· " + strings.t(StringKey.TECHNICAL_DETAIL, "detail" to (error.message ?: ""))
                    Text(
                        errorText,
                        color = status.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.semantics {
                                liveRegion = LiveRegionMode.Assertive
                                contentDescription = errorText
                            },
                    )
                }
            }

            selectionState.error?.let { message ->
                val firmwareErrorText =
                    strings.render(message) +
                        (selectionState.errorCode?.let { " ($it)" } ?: "")
                Text(
                    firmwareErrorText,
                    color = status.error,
                    modifier =
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Assertive
                            contentDescription = firmwareErrorText
                        },
                )
            }

            TextButton(onClick = onRecheck) { Text(strings.t(StringKey.RECHECK_FIRMWARE)) }
        }
    }
}
