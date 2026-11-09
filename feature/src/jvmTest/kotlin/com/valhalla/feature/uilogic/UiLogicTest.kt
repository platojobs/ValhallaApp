package com.valhalla.feature.uilogic

import com.valhalla.core.config.ConfigKeySpec
import com.valhalla.core.config.ConfigValueType
import com.valhalla.core.config.ParamValueType
import com.valhalla.core.diagnostics.DiagnosticReportFormat
import com.valhalla.core.diagnostics.DiagnosticReportSection
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.firmware.FirmwareApplicabilityReason
import com.valhalla.core.firmware.FirmwareReuseCheck
import com.valhalla.core.logging.FrameDirection
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogExportPhase
import com.valhalla.core.metrics.UsageRange
import com.valhalla.core.profile.OtaOutcome
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.feature.config.ConfigFieldState
import com.valhalla.feature.export.FileSaveResult
import com.valhalla.feature.export.FileSaveStatus
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.ResolvedLanguage
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.ota.OtaState
import com.valhalla.feature.ota.OtaWizardStep
import com.valhalla.feature.scan.RssiBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiLogicTest {
    private val en = StringResolver(ResolvedLanguage.ENGLISH)

    @Test
    fun rssiBandLabelMapsAllBands() {
        assertEquals("Strong signal", rssiBandLabel(RssiBand.STRONG, en))
        assertEquals("Medium signal", rssiBandLabel(RssiBand.MEDIUM, en))
        assertEquals("Weak signal", rssiBandLabel(RssiBand.WEAK, en))
    }

    @Test
    fun formatUptimeFormatsMinutesAndHours() {
        assertEquals("0m 0s", formatUptime(0, en))
        assertEquals("0m 59s", formatUptime(59, en))
        assertEquals("1m 0s", formatUptime(60, en))
        assertEquals("59m 59s", formatUptime(3599, en))
        assertEquals("1h 0m", formatUptime(3600, en))
        assertEquals("1h 1m", formatUptime(3661, en))
    }

    @Test
    fun formatUptimeClampsNegativeSeconds() {
        assertEquals("0m 0s", formatUptime(-1, en))
        assertEquals("0m 0s", formatUptime(-3_600, en))
    }

    @Test
    fun paramTypeKeyMapsAllTypes() {
        assertEquals(StringKey.PARAM_TYPE_INTEGER, paramTypeKey(ParamValueType.INTEGER))
        assertEquals(StringKey.PARAM_TYPE_BOOLEAN, paramTypeKey(ParamValueType.BOOLEAN))
        assertEquals(StringKey.PARAM_TYPE_ENUM, paramTypeKey(ParamValueType.ENUM))
        assertEquals(StringKey.PARAM_TYPE_STRING, paramTypeKey(ParamValueType.STRING))
    }

    @Test
    fun packetLogExportPhaseLabelMapsPhases() {
        assertEquals(en.t(StringKey.LOGS_EXPORT_PHASE_FILTERING), PacketLogExportPhase.FILTERING.label(en))
        assertEquals(en.t(StringKey.LOGS_EXPORT_PHASE_RENDERING), PacketLogExportPhase.RENDERING.label(en))
        assertEquals(en.t(StringKey.LOGS_EXPORT_PHASE_ASSEMBLING), PacketLogExportPhase.ASSEMBLING.label(en))
    }

    @Test
    fun otaTimelineMapsTerminalStates() {
        val succeeded = OtaState.Succeeded(totalBytes = 0, crc16 = 0, chunks = 0)
        val failed = OtaState.Failed(reason = UiMessage.of(StringKey.UNKNOWN), sentBytes = 0)
        assertEquals(StringKey.TIMELINE_EVENT_OTA_DONE, otaTimelineSummaryKey(succeeded))
        assertEquals(DeviceEventResult.SUCCESS, otaTimelineResult(succeeded))
        assertEquals(StringKey.TIMELINE_EVENT_OTA_FAILED, otaTimelineSummaryKey(failed))
        assertEquals(DeviceEventResult.FAILURE, otaTimelineResult(failed))
        assertEquals(StringKey.TIMELINE_EVENT_OTA_ABORTED, otaTimelineSummaryKey(OtaState.Idle))
        assertEquals(DeviceEventResult.INFO, otaTimelineResult(OtaState.Idle))
    }

    @Test
    fun firmwareReuseUnavailableTextHandlesMissingAndNone() {
        assertNull(firmwareReuseUnavailableText(null, en))
        assertTrue(firmwareReuseUnavailableText(FirmwareReuseCheck.NotFound, en)!!.isNotBlank())
        assertTrue(firmwareReuseCheckMessage(FirmwareReuseCheck.NotFound, opened = false, strings = en).isNotBlank())
    }

    @Test
    fun otaWizardStepTitleKeys() {
        assertEquals(StringKey.OTA_STEP_SELECT, otaWizardStepTitleKey(OtaWizardStep.SELECT))
        assertEquals(StringKey.OTA_STEP_PRECHECK, otaWizardStepTitleKey(OtaWizardStep.PRECHECK))
        assertEquals(StringKey.OTA_STEP_CONFIRM, otaWizardStepTitleKey(OtaWizardStep.CONFIRM))
        assertEquals(StringKey.OTA_STEP_TRANSFER, otaWizardStepTitleKey(OtaWizardStep.TRANSFER))
        assertEquals(StringKey.OTA_STEP_RESULT, otaWizardStepTitleKey(OtaWizardStep.RESULT))
    }

    @Test
    fun otaStageLabelMapsStages() {
        assertEquals(en.t(StringKey.OTA_STAGE_PREPARING), OtaStage.PREPARING.label(en))
        assertEquals(en.t(StringKey.OTA_STAGE_VERIFYING), OtaStage.VERIFYING.label(en))
        assertEquals(en.t(StringKey.OTA_STAGE_COMPLETED), OtaStage.COMPLETED.label(en))
    }

    @Test
    fun otaOutcomeLabelMapsOutcomes() {
        assertEquals(en.t(StringKey.OTA_OUTCOME_SUCCEEDED), OtaOutcome.SUCCEEDED.label(en))
        assertEquals(en.t(StringKey.OTA_OUTCOME_FAILED), OtaOutcome.FAILED.label(en))
        assertEquals(en.t(StringKey.OTA_OUTCOME_ABORTED), OtaOutcome.ABORTED.label(en))
    }

    @Test
    fun abortReasonTextMapsKnownReasons() {
        assertEquals(en.t(StringKey.OTA_ABORT_TIMEOUT), abortReasonText("TIMEOUT", en))
        assertEquals(en.t(StringKey.OTA_ABORT_VERIFY_FAILED), abortReasonText("VERIFY_FAILED", en))
        assertEquals(en.t(StringKey.OTA_ABORT_TRANSPORT), abortReasonText("TRANSPORT_ERROR", en))
        assertEquals(en.t(StringKey.OTA_ABORT_DEVICE_REJECTED), abortReasonText("DEVICE_REJECTED", en))
        assertEquals(en.t(StringKey.OTA_ABORT_USER), abortReasonText("USER_CANCELLED", en))
    }

    @Test
    fun abortReasonTextFallsBackToUserForUnknownName() {
        assertEquals(en.t(StringKey.OTA_ABORT_USER), abortReasonText("", en))
        assertEquals(en.t(StringKey.OTA_ABORT_USER), abortReasonText("NOT_A_REASON", en))
    }

    @Test
    fun diagnosticReportSectionLabelKeys() {
        assertEquals(StringKey.DIAG_REPORT_SECTION_DEVICE, DiagnosticReportSection.DEVICE.reportLabelKey())
        assertEquals(StringKey.DIAG_REPORT_SECTION_CONFIG, DiagnosticReportSection.CONFIG.reportLabelKey())
        assertEquals(StringKey.DIAG_REPORT_SECTION_QUALITY, DiagnosticReportSection.QUALITY.reportLabelKey())
        assertEquals(StringKey.DIAG_REPORT_SECTION_PACKET_LOG, DiagnosticReportSection.PACKET_LOG.reportLabelKey())
    }

    @Test
    fun diagnosticReportFormatKeys() {
        assertEquals(StringKey.DIAG_REPORT_FORMAT_MARKDOWN, DiagnosticReportFormat.MARKDOWN.formatKey())
        assertEquals(StringKey.DIAG_REPORT_FORMAT_TEXT, DiagnosticReportFormat.TEXT.formatKey())
    }

    @Test
    fun deviceEventTypeTimelineKeys() {
        assertEquals(StringKey.TIMELINE_TYPE_CONNECTION, DeviceEventType.CONNECTION.timelineKey())
        assertEquals(StringKey.TIMELINE_TYPE_CONFIG, DeviceEventType.CONFIG_CHANGE.timelineKey())
        assertEquals(StringKey.TIMELINE_TYPE_OTA, DeviceEventType.OTA.timelineKey())
        assertEquals(StringKey.TIMELINE_TYPE_ERROR, DeviceEventType.ERROR.timelineKey())
    }

    @Test
    fun firmwareApplicabilityReasonLabelKeys() {
        assertNull(FirmwareApplicabilityReason.NONE.labelKey())
        assertEquals(StringKey.LIB_REASON_MODEL_UNKNOWN, FirmwareApplicabilityReason.MODEL_UNKNOWN.labelKey())
        assertEquals(StringKey.LIB_REASON_VERSION_ROLLBACK, FirmwareApplicabilityReason.VERSION_ROLLBACK.labelKey())
        assertEquals(
            StringKey.LIB_REASON_VERSION_UNPARSEABLE,
            FirmwareApplicabilityReason.VERSION_UNPARSEABLE.labelKey(),
        )
    }

    @Test
    fun configFieldStatusDefaultsToEditable() {
        val status = status()
        assertEquals(ConfigFieldStatusKind.EDITABLE, status.kind)
        assertNull(status.detail)
    }

    @Test
    fun configFieldStatusLoadingWinsOverErrors() {
        val status = status(loading = true, loadError = "load", validationError = "validation", saving = true, dirty = true)
        assertEquals(ConfigFieldStatusKind.LOADING, status.kind)
        assertNull(status.detail)
    }

    @Test
    fun configFieldStatusLoadErrorWinsOverValidationError() {
        val status = status(loadError = "load", validationError = "validation")
        assertEquals(ConfigFieldStatusKind.LOAD_ERROR, status.kind)
        assertEquals("load", status.detail)
    }

    @Test
    fun configFieldStatusValidationErrorCarriesDetail() {
        val status = status(validationError = "validation", saving = true)
        assertEquals(ConfigFieldStatusKind.VALIDATION_ERROR, status.kind)
        assertEquals("validation", status.detail)
    }

    @Test
    fun configFieldStatusReadOnlyCarriesReason() {
        val status = status(readOnlyReason = "locked", saving = true)
        assertEquals(ConfigFieldStatusKind.READ_ONLY, status.kind)
        assertEquals("locked", status.detail)
    }

    @Test
    fun configFieldStatusSaving() {
        val status = status(saving = true, lastSavedAtEpochMillis = 10L, dirty = false)
        assertEquals(ConfigFieldStatusKind.SAVING, status.kind)
        assertNull(status.detail)
    }

    @Test
    fun configFieldStatusSavedOnlyWhenSavedAndNotDirty() {
        assertEquals(ConfigFieldStatusKind.SAVED, status(lastSavedAtEpochMillis = 10L, dirty = false).kind)
        assertEquals(ConfigFieldStatusKind.DIRTY, status(lastSavedAtEpochMillis = 10L, dirty = true).kind)
        assertEquals(ConfigFieldStatusKind.EDITABLE, status(lastSavedAtEpochMillis = null, dirty = false).kind)
    }

    @Test
    fun configFieldStatusDirty() {
        val status = status(dirty = true)
        assertEquals(ConfigFieldStatusKind.DIRTY, status.kind)
        assertNull(status.detail)
    }

    @Test
    fun configFieldActionsEditAndSaveCombinations() {
        assertTrue(configFieldActions(editable = true, dirty = false, saving = false, savingAny = false).canEditText)
        assertFalse(configFieldActions(editable = true, dirty = false, saving = false, savingAny = false).canSave)
        assertTrue(configFieldActions(editable = true, dirty = true, saving = false, savingAny = false).canSave)
        assertFalse(configFieldActions(editable = false, dirty = true, saving = false, savingAny = false).canEditText)
        assertFalse(configFieldActions(editable = false, dirty = true, saving = false, savingAny = false).canSave)
        assertFalse(configFieldActions(editable = true, dirty = true, saving = true, savingAny = false).canEditText)
        assertFalse(configFieldActions(editable = true, dirty = true, saving = true, savingAny = false).canSave)
        assertFalse(configFieldActions(editable = true, dirty = true, saving = false, savingAny = true).canEditText)
        assertFalse(configFieldActions(editable = true, dirty = true, saving = false, savingAny = true).canSave)
    }

    @Test
    fun paramReloadEnabledTruthTable() {
        assertTrue(paramReloadEnabled(connected = true, busy = false))
        assertFalse(paramReloadEnabled(connected = true, busy = true))
        assertFalse(paramReloadEnabled(connected = false, busy = false))
        assertFalse(paramReloadEnabled(connected = false, busy = true))
    }

    @Test
    fun quickActionEnabledTruthTable() {
        assertTrue(quickActionEnabled(connected = true, writeLocked = false, busy = false))
        assertFalse(quickActionEnabled(connected = true, writeLocked = true, busy = false))
        assertFalse(quickActionEnabled(connected = true, writeLocked = false, busy = true))
        assertFalse(quickActionEnabled(connected = false, writeLocked = false, busy = false))
    }

    @Test
    fun editableLoadedConfigFieldsFiltersLoadedAndEditable() {
        val fields =
            listOf(
                configFieldState(key = "loaded-editable", loaded = true, editable = true),
                configFieldState(key = "not-loaded", loaded = false, editable = true),
                configFieldState(key = "read-only", loaded = true, editable = false),
                configFieldState(key = "loaded-editable-2", loaded = true, editable = true),
            )
        val result = editableLoadedConfigFields(fields)
        assertEquals(listOf("loaded-editable", "loaded-editable-2"), result.map { it.spec.key })
    }

    @Test
    fun availableCommandTypesRemovesDuplicatesAndSortsByName() {
        val entries =
            listOf(
                entry(CommandType.REBOOT),
                entry(CommandType.AUTH),
                entry(CommandType.REBOOT),
                entry(CommandType.HEARTBEAT),
                entry(CommandType.AUTH),
            )
        assertEquals(
            listOf(CommandType.AUTH, CommandType.HEARTBEAT, CommandType.REBOOT),
            availableCommandTypes(entries),
        )
    }

    @Test
    fun selectedByIdKeepsSelectedItemsInOriginalOrder() {
        val items = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "c"), selectedById(items, setOf("c", "a")) { it })
        assertEquals(emptyList<String>(), selectedById(items, emptySet()) { it })
    }

    @Test
    fun hex32FormatsNullLowercaseAndWidth() {
        assertEquals("—", hex32(null))
        assertEquals("0x00000000", hex32(0L))
        assertEquals("0xdeadbeef", hex32(0xDEADBEEFL))
        assertEquals("0x0000000f", hex32(15L))
    }

    @Test
    fun hex16FormatsNullUppercaseAndWidth() {
        assertEquals("—", hex16(null))
        assertEquals("0x000A", hex16(0xA))
        assertEquals("0x1A2B", hex16(0x1A2B))
    }

    @Test
    fun batchStartEnabledTruthTable() {
        assertTrue(
            batchStartEnabled(
                running = false,
                selectedCount = 2,
                applyingPreset = false,
                hasSelectedPreset = false,
            ),
        )
        assertFalse(
            batchStartEnabled(
                running = true,
                selectedCount = 2,
                applyingPreset = false,
                hasSelectedPreset = false,
            ),
        )
        assertFalse(
            batchStartEnabled(
                running = false,
                selectedCount = 0,
                applyingPreset = false,
                hasSelectedPreset = false,
            ),
        )
        assertFalse(
            batchStartEnabled(
                running = false,
                selectedCount = 2,
                applyingPreset = true,
                hasSelectedPreset = false,
            ),
        )
        assertTrue(
            batchStartEnabled(
                running = false,
                selectedCount = 2,
                applyingPreset = true,
                hasSelectedPreset = true,
            ),
        )
    }

    @Test
    fun otaQueueActionsTruthTable() {
        val idle = otaQueueActions(running = false, hasFirmware = true, selectedCount = 1, hasQueued = true, hasPaused = false)
        assertTrue(idle.canEnqueue)
        assertTrue(idle.canStart)
        assertFalse(idle.canPause)
        assertFalse(idle.canResume)
        assertFalse(idle.canCancel)

        val running = otaQueueActions(running = true, hasFirmware = true, selectedCount = 1, hasQueued = false, hasPaused = false)
        assertFalse(running.canEnqueue)
        assertFalse(running.canStart)
        assertTrue(running.canPause)
        assertFalse(running.canResume)
        assertTrue(running.canCancel)

        val paused = otaQueueActions(running = false, hasFirmware = false, selectedCount = 0, hasQueued = false, hasPaused = true)
        assertFalse(paused.canEnqueue)
        assertFalse(paused.canStart)
        assertTrue(paused.canResume)
    }

    @Test
    fun fileSaveMessageMapsAllStatuses() {
        assertEquals(
            en.t(StringKey.BATCH_SAVED, "name" to "summary.txt"),
            fileSaveMessage(en, FileSaveResult(FileSaveStatus.SAVED), "summary.txt"),
        )
        assertEquals(
            en.t(StringKey.BATCH_SAVE_CANCELLED),
            fileSaveMessage(en, FileSaveResult(FileSaveStatus.CANCELLED), "summary.txt"),
        )
        assertEquals(
            en.t(StringKey.BATCH_SAVE_UNAVAILABLE),
            fileSaveMessage(en, FileSaveResult(FileSaveStatus.UNAVAILABLE), "summary.txt"),
        )
        assertEquals(
            en.t(StringKey.BATCH_SAVE_FAILED, "detail" to "boom"),
            fileSaveMessage(en, FileSaveResult(FileSaveStatus.FAILED, "boom"), "summary.txt"),
        )
        assertEquals(
            en.t(StringKey.BATCH_SAVE_FAILED, "detail" to en.t(StringKey.UNKNOWN)),
            fileSaveMessage(en, FileSaveResult(FileSaveStatus.FAILED), "summary.txt"),
        )
    }

    @Test
    fun usageRangeLabelKeys() {
        assertEquals(StringKey.STATS_RANGE_7, UsageRange.LAST_7_DAYS.labelKey())
        assertEquals(StringKey.STATS_RANGE_30, UsageRange.LAST_30_DAYS.labelKey())
        assertEquals(StringKey.STATS_RANGE_ALL, UsageRange.ALL.labelKey())
    }

    @Test
    fun usageCountTextHidesZeroAndFormatsCounts() {
        val format = LocaleFormat(ResolvedLanguage.ENGLISH)
        assertNull(usageCountText(0, en, format))
        assertEquals(en.t(StringKey.STATS_COUNT_UNIT, "count" to "1,500"), usageCountText(1_500, en, format))
    }

    @Test
    fun usageDurationTextHidesNullAndFormatsSeconds() {
        val format = LocaleFormat(ResolvedLanguage.ENGLISH)
        assertNull(usageDurationText(null, en, format))
        assertEquals(en.t(StringKey.STATS_DURATION_UNIT, "seconds" to "2"), usageDurationText(2_500, en, format))
    }

    @Test
    fun parseSettingIntAcceptsTrimmedValuesWithinRangeOnly() {
        assertEquals(5, parseSettingInt(" 5 ", 1, 5))
        assertEquals(1, parseSettingInt("1", 1, 5))
        assertNull(parseSettingInt("0", 1, 5))
        assertNull(parseSettingInt("6", 1, 5))
        assertNull(parseSettingInt("abc", 1, 5))
        assertNull(parseSettingInt("", 1, 5))
    }

    @Test
    fun settingsValueDraftRequiresEveryFieldValid() {
        val valid =
            settingsValueDraft(
                attempts = "5",
                delay = "30",
                capacity = "500",
                entries = "200",
                bytes = "512",
                hex = "8",
                rssiPeriod = "3",
            )
        assertEquals(5, valid?.reconnectMaxAttempts)
        assertEquals(30, valid?.reconnectMaxDelaySeconds)
        assertEquals(500, valid?.logCapacity)
        assertEquals(200, valid?.exportEntries)
        assertEquals(512, valid?.exportBytesKb)
        assertEquals(8, valid?.exportHexChars)
        assertEquals(3, valid?.rssiSamplePeriodSeconds)

        assertNull(
            settingsValueDraft(
                attempts = "0",
                delay = "30",
                capacity = "500",
                entries = "200",
                bytes = "512",
                hex = "8",
                rssiPeriod = "3",
            ),
        )
        assertNull(
            settingsValueDraft(
                attempts = "5",
                delay = "30",
                capacity = "500",
                entries = "200",
                bytes = "512",
                hex = "0",
                rssiPeriod = "3",
            ),
        )
    }

    private fun status(
        loading: Boolean = false,
        loadError: String? = null,
        validationError: String? = null,
        readOnlyReason: String? = null,
        saving: Boolean = false,
        lastSavedAtEpochMillis: Long? = null,
        dirty: Boolean = false,
    ): ConfigFieldStatus =
        configFieldStatus(
            loading = loading,
            loadError = loadError,
            validationError = validationError,
            readOnlyReason = readOnlyReason,
            saving = saving,
            lastSavedAtEpochMillis = lastSavedAtEpochMillis,
            dirty = dirty,
        )

    private fun configFieldState(
        key: String,
        loaded: Boolean,
        editable: Boolean,
    ): ConfigFieldState =
        ConfigFieldState(
            spec =
                ConfigKeySpec(
                    key = key,
                    displayName = key,
                    type = ConfigValueType.STRING,
                    editable = editable,
                ),
            loaded = loaded,
        )

    private fun entry(command: CommandType): PacketLogEntry =
        PacketLogEntry(
            id = 0L,
            timestampMillis = 0L,
            direction = FrameDirection.TX,
            command = command,
            sequence = 0,
            isResponse = false,
            isNotification = false,
            hex = "00",
            summary = command.name,
        )
}
