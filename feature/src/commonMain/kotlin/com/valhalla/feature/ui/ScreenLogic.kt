package com.valhalla.feature.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.valhalla.core.AppBuildInfo
import com.valhalla.core.diagnostics.DiagnosticReportError
import com.valhalla.core.diagnostics.DiagnosticReportInput
import com.valhalla.core.diagnostics.DiagnosticReportOta
import com.valhalla.core.diagnostics.DiagnosticReportValue
import com.valhalla.core.firmware.FirmwareLibraryOutcome
import com.valhalla.core.firmware.FirmwareLibraryRecord
import com.valhalla.core.firmware.FirmwareLibraryRepository
import com.valhalla.core.logging.ConnectionQualityStats
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogExportFormat
import com.valhalla.core.logging.PacketLogExportOptions
import com.valhalla.core.logging.PacketLogExportResult
import com.valhalla.core.logging.PacketLogExporter
import com.valhalla.core.logging.PacketLogFilter
import com.valhalla.core.logging.parseLocalDateTimeMillis
import com.valhalla.core.logging.validateByteLimitKb
import com.valhalla.core.logging.validateEntryLimit
import com.valhalla.core.metrics.DeviceMetricSample
import com.valhalla.core.profile.DeviceProfile
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.data.settings.StorageInventory
import com.valhalla.data.settings.StorageItem
import com.valhalla.data.settings.StorageLocation
import com.valhalla.data.settings.StoragePlatform
import com.valhalla.data.settings.currentStoragePlatform
import com.valhalla.data.share.ShareStatus
import com.valhalla.data.share.ShareableText
import com.valhalla.data.share.currentTimeZoneOffsetMinutes
import com.valhalla.feature.ble.ScanEmptyReason
import com.valhalla.feature.config.ConfigEditorState
import com.valhalla.feature.detail.DeviceDetailState
import com.valhalla.feature.diagnostics.DiagnosticPackageInput
import com.valhalla.feature.error.launchSafely
import com.valhalla.feature.export.FileSaveStatus
import com.valhalla.feature.export.SaveableFile
import com.valhalla.feature.firmware.FirmwareSelectionController
import com.valhalla.feature.firmware.FirmwareSelectionState
import com.valhalla.feature.i18n.AppStrings
import com.valhalla.feature.i18n.ResolvedLanguage
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.i18n.render
import com.valhalla.feature.notification.NotificationEvent
import com.valhalla.feature.notification.NotificationEventType
import com.valhalla.feature.notification.NotificationPage
import com.valhalla.feature.notification.NotificationTarget
import com.valhalla.feature.ota.OtaState
import com.valhalla.feature.param.ParamEditorState
import com.valhalla.feature.settings.AppSettings
import com.valhalla.feature.uilogic.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal fun emptyReasonText(
    reason: ScanEmptyReason,
    strings: StringResolver,
): String? =
    when (reason) {
        ScanEmptyReason.NONE -> null
        ScanEmptyReason.SCANNING -> strings.t(StringKey.SCAN_EMPTY_SCANNING)
        ScanEmptyReason.SCAN_TIMEOUT -> strings.t(StringKey.SCAN_EMPTY_TIMEOUT)
        ScanEmptyReason.NO_DEVICES -> strings.t(StringKey.SCAN_EMPTY_NO_DEVICES)
        ScanEmptyReason.NO_PERMISSION -> strings.t(StringKey.SCAN_EMPTY_NO_PERMISSION)
        ScanEmptyReason.PERMISSION_PERMANENTLY_DENIED ->
            strings.t(StringKey.SCAN_EMPTY_PERMISSION_PERMANENTLY_DENIED)
        ScanEmptyReason.BLUETOOTH_OFF -> strings.t(StringKey.SCAN_EMPTY_BLUETOOTH_OFF)
        ScanEmptyReason.BLUETOOTH_RESETTING -> strings.t(StringKey.SCAN_EMPTY_BLUETOOTH_RESETTING)
        ScanEmptyReason.UNSUPPORTED -> strings.t(StringKey.SCAN_EMPTY_UNSUPPORTED)
        ScanEmptyReason.UNKNOWN_AVAILABILITY -> strings.t(StringKey.SCAN_EMPTY_UNKNOWN)
        ScanEmptyReason.ERROR -> null
    }

internal fun notificationTargetRoute(
    target: NotificationTarget,
    sessionIds: Set<String>,
): Route? =
    when (target.page) {
        NotificationPage.HOME -> Route.Scan
        NotificationPage.DETAIL ->
            target.deviceId
                ?.takeIf { sessionIds.contains(it) }
                ?.let { Route.Detail(it) }

        NotificationPage.OTA ->
            target.deviceId
                ?.takeIf { sessionIds.contains(it) }
                ?.let { Route.Ota(it) }

        NotificationPage.DIAGNOSTIC ->
            target.deviceId
                ?.takeIf { sessionIds.contains(it) }
                ?.let { Route.Diagnostic(it) }
    }

internal fun buildDiagnosticPackageInput(
    detail: DeviceDetailState,
    profile: DeviceProfile?,
    stats: ConnectionQualityStats,
    entries: List<PacketLogEntry>,
    zoneOffsetMinutes: Int,
    healthReport: String?,
    metricSamples: List<DeviceMetricSample>,
): DiagnosticPackageInput {
    val quality =
        buildString {
            append("requests=").append(stats.requests)
            append("; sampleCount=").append(stats.sampleCount)
            append("; successRate=").append(stats.successRate?.toString() ?: "null")
            append("; averageLatencyMillis=").append(stats.averageLatencyMillis?.toString() ?: "null")
            append("; maxLatencyMillis=").append(stats.maxLatencyMillis?.toString() ?: "null")
            append("; retransmissions=").append(stats.retransmissions)
            append("; outOfOrder=").append(stats.outOfOrderResponses)
            append("; noResponses=").append(stats.noResponses)
            append("; unmatchedResponses=").append(stats.unmatchedResponses)
            append("; insufficientSample=").append(stats.insufficientSample)
        }
    val profileSummary =
        buildString {
            append("deviceId=").append(detail.deviceId).append('\n')
            append("displayName=").append(profile?.displayName ?: "-").append('\n')
            append("vendor=").append(profile?.vendor ?: "-").append('\n')
            append("model=").append(profile?.model ?: detail.deviceInfo?.model ?: "-").append('\n')
            append("firmware=")
                .append(profile?.lastKnownFirmwareVersion ?: detail.firmwareVersion ?: "-")
                .append('\n')
            append("batteryPercent=")
                .append(profile?.lastKnownBatteryPercent ?: detail.batteryPercent ?: "-")
                .append('\n')
            append("charging=").append(profile?.lastKnownCharging ?: detail.charging ?: "-").append('\n')
            append("otaRecords=").append(profile?.otaHistory?.size ?: 0).append('\n')
            append("lastConnectedAtMillis=").append(profile?.lastConnectedAtEpochMillis ?: 0L)
        }
    return DiagnosticPackageInput(
        generatedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        zoneOffsetMinutes = zoneOffsetMinutes,
        appVersion = AppBuildInfo.VERSION_NAME,
        buildNumber = AppBuildInfo.VERSION_CODE.toString(),
        buildChannel = AppBuildInfo.BUILD_CHANNEL,
        buildCommit = AppBuildInfo.BUILD_COMMIT,
        deviceId = detail.deviceId,
        healthReport = healthReport,
        connectionQuality = quality,
        deviceProfileSummary = profileSummary,
        packetLogEntries = entries,
        metricSamples = metricSamples,
        packetLogOptions = AppSettings.DEFAULTS.exportOptions(),
    )
}

internal fun buildDiagnosticReportInput(
    detail: DeviceDetailState,
    profile: DeviceProfile?,
    configState: ConfigEditorState,
    paramState: ParamEditorState,
    stats: ConnectionQualityStats,
    entries: List<PacketLogEntry>,
    zoneOffsetMinutes: Int,
    generatedAt: Long,
): DiagnosticReportInput {
    val configValues =
        configState.visibleFields.map { field ->
            DiagnosticReportValue(
                label = field.spec.displayName,
                value = if (field.loaded) field.text else null,
                status = field.loadError,
            )
        }
    val paramValues =
        paramState.fields.map { field ->
            DiagnosticReportValue(
                label = field.spec.displayName,
                value = if (field.loaded) field.text else null,
                status = field.loadError,
            )
        }
    val otaHistory =
        profile?.otaHistory.orEmpty().map { record ->
            DiagnosticReportOta(
                firmwareVersion = record.firmwareVersion,
                finishedAtEpochMillis = record.finishedAtEpochMillis,
                outcome = record.outcome.name,
                durationMillis = record.durationMillis,
                errorCode = record.errorCode,
                sentBytes = record.sentBytes,
                totalBytes = record.totalBytes,
            )
        }
    val recentErrors =
        stats.recentFailures.map { failure ->
            DiagnosticReportError(
                timestampMillis = failure.timestampMillis,
                command = failure.command.name,
                detail = failure.detail,
            )
        }
    return DiagnosticReportInput(
        generatedAtEpochMillis = generatedAt,
        zoneOffsetMinutes = zoneOffsetMinutes,
        appVersion = AppBuildInfo.VERSION_NAME,
        platform = platformDisplayName(),
        deviceId = detail.deviceId,
        deviceModel = detail.deviceInfo?.model ?: profile?.model,
        firmwareVersion = detail.firmwareVersion ?: detail.firmware?.toString(),
        batteryPercent = detail.batteryPercent,
        charging = detail.charging,
        configValues = configValues,
        paramValues = paramValues,
        recentErrors = recentErrors,
        quality = stats,
        otaHistory = otaHistory,
        packetLog = entries,
    )
}

internal fun otaNotificationEvent(
    current: OtaState,
    version: FirmwareVersion?,
    deviceId: String,
    strings: StringResolver,
): NotificationEvent? =
    when (current) {
        is OtaState.Succeeded ->
            NotificationEvent(
                type = NotificationEventType.OTA_SUCCESS,
                title = strings.t(StringKey.NOTIFICATION_OTA_SUCCESS_TITLE),
                body =
                    strings.t(
                        StringKey.NOTIFICATION_OTA_SUCCESS_BODY,
                        "version" to (version?.toString() ?: strings.t(StringKey.UNKNOWN)),
                    ),
                target = NotificationTarget(NotificationPage.OTA, deviceId),
                deviceId = deviceId,
            )

        is OtaState.Failed ->
            NotificationEvent(
                type = NotificationEventType.OTA_FAILED,
                title = strings.t(StringKey.NOTIFICATION_OTA_FAILED_TITLE),
                body =
                    strings.t(
                        StringKey.NOTIFICATION_OTA_FAILED_BODY,
                        "version" to (version?.toString() ?: strings.t(StringKey.UNKNOWN)),
                        "reason" to strings.render(current.reason),
                    ),
                target = NotificationTarget(NotificationPage.OTA, deviceId),
                deviceId = deviceId,
            )

        else -> null
    }

internal fun recordOtaLibraryEntry(
    current: OtaState,
    selection: FirmwareSelectionController,
    selectionState: FirmwareSelectionState,
    library: FirmwareLibraryRepository,
    deviceId: String,
): Boolean {
    // 固件库：只落元信息与原始引用，不复制二进制。
    val version = selectionState.version
    val path = selectionState.sourcePath
    val crc = selectionState.computedCrc32 ?: selectionState.declaredCrc32
    val firmware = selection.firmware
    val completePackageName = selectionState.packageName
    if (version == null || path == null) {
        return false
    }
    if (crc == null || firmware == null) {
        return false
    }
    if (completePackageName == null) {
        return false
    }
    val insurer =
        library.record(
            FirmwareLibraryRecord(
                fileName = completePackageName,
                version = version.toString(),
                channel = version.channel,
                modelWhitelist = firmware.header.targetModels.toList(),
                totalBytes = selectionState.payloadBytes,
                crc32 = crc,
                addedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                sourcePath = path,
            ),
        )
    val outcome =
        when (current) {
            is OtaState.Succeeded -> FirmwareLibraryOutcome.SUCCEEDED
            is OtaState.Failed -> FirmwareLibraryOutcome.FAILED
            else -> FirmwareLibraryOutcome.ABORTED
        }
    library.markUsed(insurer.id, deviceId, outcome)
    return true
}

internal fun startPacketLogExport(
    context: PacketLogExportContext,
    action: ExportAction,
) {
    val state = context.state
    val strings = context.deps.strings
    if (state.exporting) return
    val error = context.rangeError ?: context.fromError ?: context.toError
    if (error != null) {
        state.exportSuccess = false
        state.exportMessage = error
        return
    }
    val options = context.options
    if (options == null) {
        state.exportSuccess = false
        state.exportMessage = strings.t(StringKey.LOGS_INVALID_LIMIT)
        return
    }
    if (state.estimate?.matchedCount == 0) {
        state.exportSuccess = false
        state.exportMessage = strings.t(StringKey.LOGS_ESTIMATE_NO_MATCH)
        return
    }

    state.exportSuccess = false
    state.exportMessage = null
    state.exporting = true
    val snapshot = context.packetLog.entries.value
    val format = context.format
    state.exportJob =
        context.deps.scope.launchSafely(context.deps.errorReporter, "packet-export") {
            try {
                val now = Clock.System.now().toEpochMilliseconds()
                // 渲染在 Default 调度器执行，避免大日志阻塞 UI；取消会在进度检查点生效。
                val result =
                    withContext(Dispatchers.Default) {
                        PacketLogExporter.build(
                            entries = snapshot,
                            nowMillis = now,
                            zoneOffsetMinutes = currentTimeZoneOffsetMinutes(now),
                            options = options,
                            filter = context.filter,
                            format = format,
                            onProgress = { state.progress = it },
                        )
                    }
                state.progress = null
                if (result.empty) {
                    state.exportSuccess = false
                    state.exportMessage = strings.t(StringKey.LOGS_ESTIMATE_NO_MATCH)
                    return@launchSafely
                }
                val fileName = PacketLogExporter.resolveFileName(result.fileName, context.deps.exportedNames)
                val (ok, message) =
                    when (action) {
                        ExportAction.SAVE -> savePacketLogExport(context.deps, fileName, result, format)
                        ExportAction.SHARE -> sharePacketLogExport(context.deps, fileName, result, format)
                    }
                state.exportSuccess = ok
                state.exportMessage = message
            } catch (cancellation: CancellationException) {
                state.exportSuccess = false
                state.exportMessage = strings.t(StringKey.LOGS_EXPORT_CANCELLED)
                throw cancellation
            } catch (error: Throwable) {
                // 渲染/保存/分享的意外异常也要展示给用户并收敛 exporting 状态。
                state.exportSuccess = false
                state.exportMessage =
                    strings.t(
                        StringKey.FAILURE_UNKNOWN,
                        "detail" to (error.message ?: error::class.simpleName ?: "unknown"),
                    )
                context.deps.errorReporter.record("packet-export", error)
            } finally {
                state.exporting = false
                state.progress = null
                state.exportJob = null
            }
        }
}

internal suspend fun sharePacketLogExport(
    deps: PacketLogExportDeps,
    fileName: String,
    result: PacketLogExportResult,
    format: PacketLogExportFormat,
): Pair<Boolean, String> {
    val shared = deps.sharer.share(ShareableText(fileName, result.text, format.mimeType))
    return when (shared.status) {
        ShareStatus.SHARED -> {
            deps.exportedNames += fileName
            true to
                deps.strings.t(
                    StringKey.LOGS_SHARE_SUCCESS,
                    "name" to fileName,
                    "entries" to deps.localeFormat.number(result.entryCount),
                    "bytes" to deps.localeFormat.number(result.byteSize),
                )
        }

        ShareStatus.UNAVAILABLE -> false to deps.strings.t(StringKey.LOGS_SHARE_UNAVAILABLE)
        ShareStatus.FAILED ->
            false to
                deps.strings.t(
                    StringKey.LOGS_SHARE_FAILED,
                    "detail" to (shared.message ?: deps.strings.t(StringKey.UNKNOWN)),
                )
    }
}

internal suspend fun savePacketLogExport(
    deps: PacketLogExportDeps,
    fileName: String,
    result: PacketLogExportResult,
    format: PacketLogExportFormat,
): Pair<Boolean, String> {
    val save = deps.fileSaver.save(SaveableFile(fileName, result.text, format.mimeType))
    return when (save.status) {
        FileSaveStatus.SAVED -> {
            deps.exportedNames += fileName
            true to
                deps.strings.t(
                    StringKey.LOGS_SAVE_SUCCESS,
                    "name" to fileName,
                    "entries" to deps.localeFormat.number(result.entryCount),
                    "bytes" to deps.localeFormat.number(result.byteSize),
                )
        }

        FileSaveStatus.CANCELLED -> false to deps.strings.t(StringKey.LOGS_SAVE_CANCELLED)
        FileSaveStatus.UNAVAILABLE -> false to deps.strings.t(StringKey.LOGS_SAVE_UNAVAILABLE)
        FileSaveStatus.FAILED ->
            false to
                deps.strings.t(
                    StringKey.LOGS_SAVE_FAILED,
                    "detail" to (save.message ?: deps.strings.t(StringKey.UNKNOWN)),
                )
    }
}

internal fun packetLogDerived(
    state: PacketLogUiState,
    initialOptions: PacketLogExportOptions,
    strings: StringResolver,
    zoneOffsetMinutes: Int,
): PacketLogDerived {
    val fromMillis = if (state.fromText.isBlank()) null else parseLocalDateTimeMillis(state.fromText, zoneOffsetMinutes)
    val toMillis = if (state.toText.isBlank()) null else parseLocalDateTimeMillis(state.toText, zoneOffsetMinutes)
    val fromError =
        if (state.fromText.isNotBlank() && fromMillis == null) {
            strings.t(StringKey.LOGS_TIME_FORMAT_ERROR)
        } else {
            null
        }
    val toError =
        if (state.toText.isNotBlank() && toMillis == null) {
            strings.t(StringKey.LOGS_TIME_FORMAT_ERROR)
        } else {
            null
        }
    val rangeError =
        if (fromMillis != null && toMillis != null && fromMillis > toMillis) {
            strings.t(StringKey.LOGS_TIME_RANGE_ERROR)
        } else {
            null
        }
    val entryLimit = validateEntryLimit(state.maxEntriesText)
    val byteLimit = validateByteLimitKb(state.maxBytesKbText)
    val options =
        if (entryLimit.valid && byteLimit.valid) {
            PacketLogExportOptions(
                maxEntries = entryLimit.value!!,
                maxBytes = byteLimit.value!!,
                maxHexCharsPerEntry = initialOptions.maxHexCharsPerEntry,
            )
        } else {
            null
        }
    val filter =
        PacketLogFilter(
            commands = state.selectedCommands,
            errorsOnly = state.errorsOnly,
            keyword = state.appliedQuery,
            fromMillis = fromMillis,
            toMillis = toMillis,
        )
    return PacketLogDerived(
        fromMillis = fromMillis,
        toMillis = toMillis,
        fromError = fromError,
        toError = toError,
        rangeError = rangeError,
        entryLimit = entryLimit,
        byteLimit = byteLimit,
        options = options,
        filter = filter,
    )
}

internal fun platformDisplayName(): String =
    when (currentStoragePlatform()) {
        StoragePlatform.ANDROID -> "Android"
        StoragePlatform.IOS -> "iOS"
        StoragePlatform.JVM -> "JVM"
    }

internal fun storageDescriptionKey(item: StorageItem): StringKey =
    when (item) {
        StorageItem.DEVICE_PROFILES -> StringKey.PRIVACY_ITEM_PROFILES
        StorageItem.OTA_CHECKPOINT -> StringKey.PRIVACY_ITEM_OTA
        StorageItem.AUTH_TOKEN -> StringKey.PRIVACY_ITEM_TOKEN
        StorageItem.PACKET_LOG -> StringKey.PRIVACY_ITEM_LOGS
        StorageItem.APP_SETTINGS -> StringKey.PRIVACY_ITEM_SETTINGS
        StorageItem.BLE_PERMISSION_FLAG -> StringKey.PRIVACY_ITEM_PERMISSION_FLAG
        StorageItem.FIRMWARE_LIBRARY -> StringKey.PRIVACY_ITEM_FIRMWARE_LIBRARY
        StorageItem.DEVICE_EVENTS -> StringKey.PRIVACY_ITEM_EVENTS
        StorageItem.DEVICE_METRICS -> StringKey.PRIVACY_ITEM_METRICS
        StorageItem.CONFIG_PRESETS -> StringKey.PRIVACY_ITEM_PRESETS
        StorageItem.AUTOMATION_RULES -> StringKey.PRIVACY_ITEM_AUTOMATION
    }

/** 供测试断言：界面展示的存储位置必须覆盖 [StorageInventory] 的全部条目。 */
fun storageLocationItems(platform: StoragePlatform): List<StorageLocation> = StorageInventory.forPlatform(platform)

/** 供测试断言：隐私页文案键与存储类别一一对应。 */
fun privacyKeysForAllItems(): Map<StorageItem, StringKey> = StorageItem.entries.associateWith { storageDescriptionKey(it) }

/** 供测试读取当前语言下隐私声明的实际文本。 */
fun privacyNetworkDisclosure(language: ResolvedLanguage): String = AppStrings.get(language, StringKey.PRIVACY_NETWORK_BODY)
