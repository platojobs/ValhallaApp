package com.valhalla.feature.uilogic

import com.valhalla.core.config.ParamSpec
import com.valhalla.core.config.ParamValueType
import com.valhalla.core.diagnostics.DiagnosticReportFormat
import com.valhalla.core.diagnostics.DiagnosticReportSection
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.firmware.FirmwareApplicabilityReason
import com.valhalla.core.firmware.FirmwareReuseCheck
import com.valhalla.core.firmware.FirmwareReuseReason
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogExportEstimate
import com.valhalla.core.logging.PacketLogExportOptions
import com.valhalla.core.logging.PacketLogExportPhase
import com.valhalla.core.metrics.UsageRange
import com.valhalla.core.profile.OtaOutcome
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.OtaAbortReason
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.feature.ble.ReconnectPhase
import com.valhalla.feature.ble.ReconnectState
import com.valhalla.feature.config.ConfigFieldState
import com.valhalla.feature.export.FileSaveResult
import com.valhalla.feature.export.FileSaveStatus
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.ota.OtaState
import com.valhalla.feature.ota.OtaWizardStep
import com.valhalla.feature.param.ParamFieldState
import com.valhalla.feature.scan.RssiBand
import com.valhalla.feature.settings.AppSettings

private const val SECONDS_PER_HOUR = 3_600
private const val SECONDS_PER_MINUTE = 60

internal fun rssiBandLabel(
    band: RssiBand,
    strings: StringResolver,
): String =
    when (band) {
        RssiBand.STRONG -> strings.t(StringKey.SCAN_RSSI_STRONG)
        RssiBand.MEDIUM -> strings.t(StringKey.SCAN_RSSI_MEDIUM)
        RssiBand.WEAK -> strings.t(StringKey.SCAN_RSSI_WEAK)
    }

internal fun formatUptime(
    seconds: Long,
    strings: StringResolver,
): String {
    val safe = seconds.coerceAtLeast(0)
    return if (safe < SECONDS_PER_HOUR) {
        strings.t(
            StringKey.DASH_UPTIME_MS,
            "minutes" to (safe / SECONDS_PER_MINUTE).toString(),
            "seconds" to (safe % SECONDS_PER_MINUTE).toString(),
        )
    } else {
        strings.t(
            StringKey.DASH_UPTIME_HM,
            "hours" to (safe / SECONDS_PER_HOUR).toString(),
            "minutes" to ((safe % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toString(),
        )
    }
}

internal fun DiagnosticReportSection.reportLabelKey(): StringKey =
    when (this) {
        DiagnosticReportSection.DEVICE -> StringKey.DIAG_REPORT_SECTION_DEVICE
        DiagnosticReportSection.FIRMWARE -> StringKey.DIAG_REPORT_SECTION_FIRMWARE
        DiagnosticReportSection.BATTERY -> StringKey.DIAG_REPORT_SECTION_BATTERY
        DiagnosticReportSection.CONFIG -> StringKey.DIAG_REPORT_SECTION_CONFIG
        DiagnosticReportSection.PARAM -> StringKey.DIAG_REPORT_SECTION_PARAM
        DiagnosticReportSection.ERRORS -> StringKey.DIAG_REPORT_SECTION_ERRORS
        DiagnosticReportSection.QUALITY -> StringKey.DIAG_REPORT_SECTION_QUALITY
        DiagnosticReportSection.OTA -> StringKey.DIAG_REPORT_SECTION_OTA
        DiagnosticReportSection.PACKET_LOG -> StringKey.DIAG_REPORT_SECTION_PACKET_LOG
    }

internal fun DiagnosticReportFormat.formatKey(): StringKey =
    when (this) {
        DiagnosticReportFormat.MARKDOWN -> StringKey.DIAG_REPORT_FORMAT_MARKDOWN
        DiagnosticReportFormat.TEXT -> StringKey.DIAG_REPORT_FORMAT_TEXT
    }

internal fun DeviceEventType.timelineKey(): StringKey =
    when (this) {
        DeviceEventType.CONNECTION -> StringKey.TIMELINE_TYPE_CONNECTION
        DeviceEventType.DISCONNECTION -> StringKey.TIMELINE_TYPE_DISCONNECTION
        DeviceEventType.AUTH -> StringKey.TIMELINE_TYPE_AUTH
        DeviceEventType.CONFIG_CHANGE -> StringKey.TIMELINE_TYPE_CONFIG
        DeviceEventType.PARAM_CHANGE -> StringKey.TIMELINE_TYPE_PARAM
        DeviceEventType.OTA -> StringKey.TIMELINE_TYPE_OTA
        DeviceEventType.HEALTH -> StringKey.TIMELINE_TYPE_HEALTH
        DeviceEventType.AUTOMATION -> StringKey.TIMELINE_TYPE_AUTOMATION
        DeviceEventType.ERROR -> StringKey.TIMELINE_TYPE_ERROR
    }

internal fun paramTypeKey(type: ParamValueType): StringKey =
    when (type) {
        ParamValueType.INTEGER -> StringKey.PARAM_TYPE_INTEGER
        ParamValueType.BOOLEAN -> StringKey.PARAM_TYPE_BOOLEAN
        ParamValueType.ENUM -> StringKey.PARAM_TYPE_ENUM
        ParamValueType.STRING -> StringKey.PARAM_TYPE_STRING
    }

internal fun PacketLogExportPhase.label(strings: StringResolver): String =
    when (this) {
        PacketLogExportPhase.FILTERING -> strings.t(StringKey.LOGS_EXPORT_PHASE_FILTERING)
        PacketLogExportPhase.RENDERING -> strings.t(StringKey.LOGS_EXPORT_PHASE_RENDERING)
        PacketLogExportPhase.ASSEMBLING -> strings.t(StringKey.LOGS_EXPORT_PHASE_ASSEMBLING)
    }

internal fun OtaOutcome.label(strings: StringResolver): String =
    when (this) {
        OtaOutcome.SUCCEEDED -> strings.t(StringKey.OTA_OUTCOME_SUCCEEDED)
        OtaOutcome.FAILED -> strings.t(StringKey.OTA_OUTCOME_FAILED)
        OtaOutcome.ABORTED -> strings.t(StringKey.OTA_OUTCOME_ABORTED)
    }

internal fun abortReasonText(
    reasonName: String,
    strings: StringResolver,
): String =
    when (
        OtaAbortReason.entries.firstOrNull { it.name == reasonName }
    ) {
        OtaAbortReason.TIMEOUT -> strings.t(StringKey.OTA_ABORT_TIMEOUT)
        OtaAbortReason.VERIFY_FAILED -> strings.t(StringKey.OTA_ABORT_VERIFY_FAILED)
        OtaAbortReason.TRANSPORT_ERROR -> strings.t(StringKey.OTA_ABORT_TRANSPORT)
        OtaAbortReason.DEVICE_REJECTED -> strings.t(StringKey.OTA_ABORT_DEVICE_REJECTED)
        OtaAbortReason.USER_CANCELLED -> strings.t(StringKey.OTA_ABORT_USER)
        null -> strings.t(StringKey.OTA_ABORT_USER)
    }

internal fun OtaStage.label(strings: StringResolver): String =
    when (this) {
        OtaStage.PREPARING -> strings.t(StringKey.OTA_STAGE_PREPARING)
        OtaStage.TRANSFERRING -> strings.t(StringKey.OTA_STAGE_TRANSFERRING)
        OtaStage.VERIFYING -> strings.t(StringKey.OTA_STAGE_VERIFYING)
        OtaStage.APPLYING -> strings.t(StringKey.OTA_STAGE_APPLYING)
        OtaStage.COMPLETED -> strings.t(StringKey.OTA_STAGE_COMPLETED)
    }

internal fun otaTimelineSummaryKey(state: OtaState): StringKey =
    when (state) {
        is OtaState.Succeeded -> StringKey.TIMELINE_EVENT_OTA_DONE
        is OtaState.Failed -> StringKey.TIMELINE_EVENT_OTA_FAILED
        else -> StringKey.TIMELINE_EVENT_OTA_ABORTED
    }

internal fun otaTimelineResult(state: OtaState): DeviceEventResult =
    when (state) {
        is OtaState.Succeeded -> DeviceEventResult.SUCCESS
        is OtaState.Failed -> DeviceEventResult.FAILURE
        else -> DeviceEventResult.INFO
    }

internal fun firmwareReuseCheckMessage(
    check: FirmwareReuseCheck,
    opened: Boolean,
    strings: StringResolver,
): String =
    when (check) {
        is FirmwareReuseCheck.Available ->
            if (opened) {
                strings.t(StringKey.OTA_LIBRARY_AVAILABLE)
            } else {
                strings.t(StringKey.OTA_LIBRARY_USE_FAILED, "detail" to "open failed")
            }

        is FirmwareReuseCheck.Missing ->
            strings.t(
                StringKey.OTA_LIBRARY_MISSING,
                "reason" to
                    strings.t(
                        when (check.reason) {
                            FirmwareReuseReason.ACCESS_DENIED -> StringKey.OTA_LIBRARY_MISSING_ACCESS
                            FirmwareReuseReason.READ_FAILED -> StringKey.OTA_LIBRARY_MISSING_READ
                        },
                    ),
            )

        is FirmwareReuseCheck.CrcMismatch ->
            strings.t(
                StringKey.OTA_LIBRARY_CRC_MISMATCH,
                "expected" to hex32(check.expectedCrc32),
                "actual" to (check.actualCrc32?.let(::hex32) ?: "-"),
            )

        FirmwareReuseCheck.NotFound -> strings.t(StringKey.OTA_LIBRARY_USE_FAILED, "detail" to "not found")
    }

internal fun firmwareReuseUnavailableText(
    availability: FirmwareReuseCheck?,
    strings: StringResolver,
): String? =
    when (availability) {
        is FirmwareReuseCheck.Missing ->
            strings.t(
                StringKey.OTA_LIBRARY_MISSING,
                "reason" to
                    strings.t(
                        when (availability.reason) {
                            FirmwareReuseReason.ACCESS_DENIED -> StringKey.OTA_LIBRARY_MISSING_ACCESS
                            FirmwareReuseReason.READ_FAILED -> StringKey.OTA_LIBRARY_MISSING_READ
                        },
                    ),
            )

        is FirmwareReuseCheck.CrcMismatch ->
            strings.t(
                StringKey.OTA_LIBRARY_CRC_MISMATCH,
                "expected" to hex32(availability.expectedCrc32),
                "actual" to (availability.actualCrc32?.let(::hex32) ?: "-"),
            )

        FirmwareReuseCheck.NotFound -> strings.t(StringKey.OTA_LIBRARY_USE_FAILED, "detail" to "not found")

        else -> null
    }

internal fun otaWizardStepTitleKey(step: OtaWizardStep): StringKey =
    when (step) {
        OtaWizardStep.SELECT -> StringKey.OTA_STEP_SELECT
        OtaWizardStep.PRECHECK -> StringKey.OTA_STEP_PRECHECK
        OtaWizardStep.CONFIRM -> StringKey.OTA_STEP_CONFIRM
        OtaWizardStep.TRANSFER -> StringKey.OTA_STEP_TRANSFER
        OtaWizardStep.RESULT -> StringKey.OTA_STEP_RESULT
    }

internal fun FirmwareApplicabilityReason.labelKey(): StringKey? =
    when (this) {
        FirmwareApplicabilityReason.NONE -> null
        FirmwareApplicabilityReason.MODEL_UNKNOWN -> StringKey.LIB_REASON_MODEL_UNKNOWN
        FirmwareApplicabilityReason.MODEL_MISMATCH -> StringKey.LIB_REASON_MODEL_MISMATCH
        FirmwareApplicabilityReason.VERSION_UNKNOWN -> StringKey.LIB_REASON_VERSION_UNKNOWN
        FirmwareApplicabilityReason.VERSION_UNPARSEABLE -> StringKey.LIB_REASON_VERSION_UNPARSEABLE
        FirmwareApplicabilityReason.VERSION_ROLLBACK -> StringKey.LIB_REASON_VERSION_ROLLBACK
    }

internal fun hex32(value: Long?): String = value?.let { "0x" + it.toString(16).padStart(8, '0') } ?: "—"

internal fun hex16(value: Int?): String = value?.let { "0x" + it.toString(16).uppercase().padStart(4, '0') } ?: "—"

internal enum class ConfigFieldStatusKind { LOADING, LOAD_ERROR, VALIDATION_ERROR, READ_ONLY, SAVING, SAVED, DIRTY, EDITABLE }

internal data class ConfigFieldStatus(
    val kind: ConfigFieldStatusKind,
    val detail: String? = null,
)

internal fun configFieldStatus(
    loading: Boolean,
    loadError: String?,
    validationError: String?,
    readOnlyReason: String?,
    saving: Boolean,
    lastSavedAtEpochMillis: Long?,
    dirty: Boolean,
): ConfigFieldStatus =
    when {
        loading -> ConfigFieldStatus(ConfigFieldStatusKind.LOADING)
        loadError != null -> ConfigFieldStatus(ConfigFieldStatusKind.LOAD_ERROR, loadError)
        validationError != null -> ConfigFieldStatus(ConfigFieldStatusKind.VALIDATION_ERROR, validationError)
        readOnlyReason != null -> ConfigFieldStatus(ConfigFieldStatusKind.READ_ONLY, readOnlyReason)
        saving -> ConfigFieldStatus(ConfigFieldStatusKind.SAVING)
        lastSavedAtEpochMillis != null && !dirty -> ConfigFieldStatus(ConfigFieldStatusKind.SAVED)
        dirty -> ConfigFieldStatus(ConfigFieldStatusKind.DIRTY)
        else -> ConfigFieldStatus(ConfigFieldStatusKind.EDITABLE)
    }

internal data class ConfigFieldActions(
    val canEditText: Boolean,
    val canSave: Boolean,
)

internal fun configFieldActions(
    editable: Boolean,
    dirty: Boolean,
    saving: Boolean,
    savingAny: Boolean,
): ConfigFieldActions =
    ConfigFieldActions(
        canEditText = editable && !saving && !savingAny,
        canSave = editable && dirty && !saving && !savingAny,
    )

internal fun paramReloadEnabled(
    connected: Boolean,
    busy: Boolean,
): Boolean = connected && !busy

internal fun quickActionEnabled(
    connected: Boolean,
    writeLocked: Boolean,
    busy: Boolean,
): Boolean = connected && !writeLocked && !busy

internal fun editableLoadedConfigFields(fields: List<ConfigFieldState>): List<ConfigFieldState> = fields.filter { it.loaded && it.editable }

internal fun availableCommandTypes(entries: List<PacketLogEntry>): List<CommandType> = entries.map { it.command }.distinct().sortedBy { it.name }

/** 报文导出按钮可用性：导出中、空日志、上限非法、时间错误或无匹配时都不可用。 */
internal fun packetLogCanExport(
    exporting: Boolean,
    entriesEmpty: Boolean,
    hasOptions: Boolean,
    rangeError: String?,
    fromError: String?,
    toError: String?,
    matchedCount: Int?,
): Boolean =
    !exporting &&
        !entriesEmpty &&
        hasOptions &&
        rangeError == null &&
        fromError == null &&
        toError == null &&
        matchedCount != 0

/** 预估提示行：优先展示时间/上限错误，其次加载中、无匹配、截断与实际预估。 */
internal fun packetLogEstimateLine(
    rangeError: String?,
    fromError: String?,
    toError: String?,
    hasOptions: Boolean,
    estimate: PacketLogExportEstimate?,
    strings: StringResolver,
    format: LocaleFormat,
): String =
    when {
        rangeError != null ->
            strings.t(
                StringKey.LOGS_ESTIMATE_INVALID_RANGE,
                "detail" to rangeError,
            )

        fromError != null || toError != null -> strings.t(StringKey.LOGS_TIME_FORMAT_ERROR)
        !hasOptions -> strings.t(StringKey.LOGS_ESTIMATE_INVALID_LIMIT, "detail" to "")
        estimate == null -> strings.t(StringKey.LOGS_ESTIMATE_LOADING)
        estimate.matchedCount == 0 -> strings.t(StringKey.LOGS_ESTIMATE_NO_MATCH)
        estimate.truncated ->
            strings.t(
                StringKey.LOGS_ESTIMATE_TRUNCATED,
                "entries" to format.number(estimate.estimatedEntries),
                "bytes" to format.bytes(estimate.estimatedBytes.toLong()),
                "matched" to format.number(estimate.matchedCount),
            )

        else ->
            strings.t(
                StringKey.LOGS_ESTIMATE,
                "entries" to format.number(estimate.estimatedEntries),
                "bytes" to format.bytes(estimate.estimatedBytes.toLong()),
            )
    }

/** 参数取值范围提示：布尔类型不展示范围。 */
internal fun paramRangeLabel(
    spec: ParamSpec,
    strings: StringResolver,
): String? =
    when (spec.type) {
        ParamValueType.INTEGER ->
            strings.t(StringKey.PARAM_RANGE, "range" to "${spec.intRange.first}..${spec.intRange.last}")

        ParamValueType.ENUM ->
            strings.t(
                StringKey.PARAM_RANGE,
                "range" to
                    spec.enumValues.keys
                        .sorted()
                        .joinToString("/"),
            )

        ParamValueType.STRING -> strings.t(StringKey.PARAM_RANGE, "range" to "≤ ${spec.maxLength}")
        ParamValueType.BOOLEAN -> null
    }

/** 参数详情行：类型、范围、单位与十六进制 ID。 */
internal fun paramDetailLine(
    spec: ParamSpec,
    typeLabel: String,
    rangeLabel: String?,
    strings: StringResolver,
): String =
    buildList {
        add(typeLabel)
        rangeLabel?.let(::add)
        spec.unit?.let(::add)
        add(
            strings.t(
                StringKey.PARAM_ID_LINE,
                "id" to
                    spec.id
                        .toString(16)
                        .uppercase()
                        .padStart(4, '0'),
            ),
        )
    }.joinToString(" · ")

/** 参数输入框下方的提示：加载/错误/只读/保存中/已保存/未保存，最后回落到普通标签。 */
internal fun paramSupportingText(
    field: ParamFieldState,
    spec: ParamSpec,
    strings: StringResolver,
): String =
    when {
        field.loading -> strings.t(StringKey.PARAM_LOADING)
        field.loadError != null -> strings.t(StringKey.PARAM_LOAD_ERROR) + " · " + field.loadError
        field.validationError != null -> field.validationError!!
        !spec.writable -> strings.t(StringKey.PARAM_READ_ONLY_REASON)
        field.saving -> strings.t(StringKey.PARAM_SAVING)
        field.lastSavedAtEpochMillis != null && !field.dirty ->
            strings.t(StringKey.PARAM_SAVED_FIELD, "name" to spec.displayName)

        field.dirty -> strings.t(StringKey.PARAM_UNSAVED)
        else -> strings.t(StringKey.PARAM_VALUE_LABEL)
    }

/** 自动重连提示文案；IDLE 阶段不展示任何提示。 */
internal fun disconnectReconnectHint(
    state: ReconnectState,
    strings: StringResolver,
): String? =
    when (state.phase) {
        ReconnectPhase.WAITING ->
            strings.t(
                StringKey.RECONNECT_WAITING,
                "seconds" to ((state.nextDelayMillis ?: 0L) / 1000).toString(),
                "attempt" to (state.attempt + 1).toString(),
            )

        ReconnectPhase.CONNECTING ->
            strings.t(
                StringKey.RECONNECT_CONNECTING,
                "attempt" to (state.attempt + 1).toString(),
            )

        ReconnectPhase.PAUSED ->
            if (state.pausedByBluetoothOff) {
                strings.t(StringKey.RECONNECT_PAUSED_BLUETOOTH_OFF)
            } else {
                strings.t(StringKey.RECONNECT_PAUSED)
            }

        ReconnectPhase.EXHAUSTED ->
            strings.t(
                StringKey.RECONNECT_EXHAUSTED,
                "attempt" to state.attempt.toString(),
            )

        ReconnectPhase.CANCELLED -> strings.t(StringKey.RECONNECT_CANCELLED)
        ReconnectPhase.SUCCEEDED -> strings.t(StringKey.RECONNECT_SUCCEEDED)
        ReconnectPhase.IDLE -> null
    }

internal fun <T> selectedById(
    items: List<T>,
    selectedIds: Set<String>,
    idOf: (T) -> String,
): List<T> = items.filter { idOf(it) in selectedIds }

/** 批量页「开始」按钮可用性：运行中、未选设备、应用预设但未选预设时都不可用。 */
internal fun batchStartEnabled(
    running: Boolean,
    selectedCount: Int,
    applyingPreset: Boolean,
    hasSelectedPreset: Boolean,
): Boolean = !running && selectedCount > 0 && (!applyingPreset || hasSelectedPreset)

internal fun fileSaveMessage(
    strings: StringResolver,
    result: FileSaveResult,
    fileName: String,
): String =
    when (result.status) {
        FileSaveStatus.SAVED -> strings.t(StringKey.BATCH_SAVED, "name" to fileName)
        FileSaveStatus.CANCELLED -> strings.t(StringKey.BATCH_SAVE_CANCELLED)
        FileSaveStatus.UNAVAILABLE -> strings.t(StringKey.BATCH_SAVE_UNAVAILABLE)
        FileSaveStatus.FAILED ->
            strings.t(
                StringKey.BATCH_SAVE_FAILED,
                "detail" to (result.message ?: strings.t(StringKey.UNKNOWN)),
            )
    }

internal data class OtaQueueActions(
    val canEnqueue: Boolean,
    val canStart: Boolean,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
)

internal fun otaQueueActions(
    running: Boolean,
    hasFirmware: Boolean,
    selectedCount: Int,
    hasQueued: Boolean,
    hasPaused: Boolean,
): OtaQueueActions =
    OtaQueueActions(
        canEnqueue = !running && hasFirmware && selectedCount > 0,
        canStart = !running && hasQueued,
        canPause = running,
        canResume = !running && hasPaused,
        canCancel = running,
    )

internal fun UsageRange.labelKey(): StringKey =
    when (this) {
        UsageRange.LAST_7_DAYS -> StringKey.STATS_RANGE_7
        UsageRange.LAST_30_DAYS -> StringKey.STATS_RANGE_30
        UsageRange.ALL -> StringKey.STATS_RANGE_ALL
    }

internal fun usageCountText(
    count: Int,
    strings: StringResolver,
    format: LocaleFormat,
): String? = if (count == 0) null else strings.t(StringKey.STATS_COUNT_UNIT, "count" to format.number(count))

internal fun usageDurationText(
    millis: Long?,
    strings: StringResolver,
    format: LocaleFormat,
): String? = millis?.let { strings.t(StringKey.STATS_DURATION_UNIT, "seconds" to format.number(it / 1000L)) }

internal fun parseSettingInt(
    text: String,
    min: Int,
    max: Int,
): Int? = text.trim().toIntOrNull()?.takeIf { it in min..max }

internal data class SettingsValueDraft(
    val reconnectMaxAttempts: Int,
    val reconnectMaxDelaySeconds: Int,
    val logCapacity: Int,
    val exportEntries: Int,
    val exportBytesKb: Int,
    val exportHexChars: Int,
    val rssiSamplePeriodSeconds: Int,
)

/** 解析设置页七个数字字段；任一非法返回 null（对应「应用」按钮禁用）。 */
internal fun settingsValueDraft(
    attempts: String,
    delay: String,
    capacity: String,
    entries: String,
    bytes: String,
    hex: String,
    rssiPeriod: String,
): SettingsValueDraft? {
    val attemptsValue =
        parseSettingInt(attempts, AppSettings.MIN_RECONNECT_ATTEMPTS, AppSettings.MAX_RECONNECT_ATTEMPTS) ?: return null
    val delayValue =
        parseSettingInt(delay, AppSettings.MIN_RECONNECT_MAX_DELAY_SECONDS, AppSettings.MAX_RECONNECT_MAX_DELAY_SECONDS)
            ?: return null
    val capacityValue = parseSettingInt(capacity, AppSettings.MIN_LOG_CAPACITY, AppSettings.MAX_LOG_CAPACITY) ?: return null
    val entriesValue =
        parseSettingInt(entries, PacketLogExportOptions.MIN_ENTRIES, PacketLogExportOptions.MAX_ENTRIES) ?: return null
    val bytesValue =
        parseSettingInt(bytes, AppSettings.MIN_EXPORT_BYTES_KB, AppSettings.MAX_EXPORT_BYTES_KB) ?: return null
    val hexValue = parseSettingInt(hex, 1, PacketLogExportOptions.MAX_HEX_CHARS) ?: return null
    val rssiPeriodValue =
        parseSettingInt(rssiPeriod, AppSettings.MIN_RSSI_SAMPLE_PERIOD_SECONDS, AppSettings.MAX_RSSI_SAMPLE_PERIOD_SECONDS)
            ?: return null
    return SettingsValueDraft(
        reconnectMaxAttempts = attemptsValue,
        reconnectMaxDelaySeconds = delayValue,
        logCapacity = capacityValue,
        exportEntries = entriesValue,
        exportBytesKb = bytesValue,
        exportHexChars = hexValue,
        rssiSamplePeriodSeconds = rssiPeriodValue,
    )
}
