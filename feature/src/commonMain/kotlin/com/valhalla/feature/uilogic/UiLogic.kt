package com.valhalla.feature.uilogic

import com.valhalla.core.config.ParamValueType
import com.valhalla.core.diagnostics.DiagnosticReportFormat
import com.valhalla.core.diagnostics.DiagnosticReportSection
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.firmware.FirmwareApplicabilityReason
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogExportPhase
import com.valhalla.core.profile.OtaOutcome
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.OtaAbortReason
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.feature.config.ConfigFieldState
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.scan.RssiBand

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

internal fun <T> selectedById(
    items: List<T>,
    selectedIds: Set<String>,
    idOf: (T) -> String,
): List<T> = items.filter { idOf(it) in selectedIds }
