package com.valhalla.feature.ui

import com.valhalla.core.config.ConfigKeySpec
import com.valhalla.core.config.ConfigValueType
import com.valhalla.core.config.ParamSpec
import com.valhalla.core.config.ParamValueType
import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareHeader
import com.valhalla.core.firmware.FirmwareLibraryOutcome
import com.valhalla.core.firmware.FirmwareLibraryRepository
import com.valhalla.core.firmware.FirmwareLibrarySort
import com.valhalla.core.firmware.FirmwareSource
import com.valhalla.core.firmware.InMemoryFirmwareLibraryStore
import com.valhalla.core.firmware.InMemoryOtaCheckpointStore
import com.valhalla.core.logging.ConnectionQualityStats
import com.valhalla.core.logging.FrameDirection
import com.valhalla.core.logging.InMemoryPacketLog
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogExportEstimate
import com.valhalla.core.logging.PacketLogExportFormat
import com.valhalla.core.logging.PacketLogExportOptions
import com.valhalla.core.logging.PacketLogExportResult
import com.valhalla.core.logging.PacketLogFilter
import com.valhalla.core.logging.PacketLogIndex
import com.valhalla.core.logging.QualityFailure
import com.valhalla.core.metrics.DeviceMetricSample
import com.valhalla.core.profile.DeviceProfile
import com.valhalla.core.profile.OtaHistoryRecord
import com.valhalla.core.profile.OtaOutcome
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.data.share.ShareResult
import com.valhalla.data.share.ShareStatus
import com.valhalla.data.share.ShareableText
import com.valhalla.data.share.TextSharer
import com.valhalla.feature.ble.ScanEmptyReason
import com.valhalla.feature.config.ConfigEditorState
import com.valhalla.feature.config.ConfigFieldState
import com.valhalla.feature.detail.DeviceDetailState
import com.valhalla.feature.error.ErrorReporter
import com.valhalla.feature.export.FileSaveResult
import com.valhalla.feature.export.FileSaveStatus
import com.valhalla.feature.export.FileSaver
import com.valhalla.feature.export.SaveableFile
import com.valhalla.feature.firmware.FirmwarePicker
import com.valhalla.feature.firmware.FirmwareSelectionController
import com.valhalla.feature.firmware.PickedFirmware
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.ResolvedLanguage
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.i18n.render
import com.valhalla.feature.notification.NotificationEventType
import com.valhalla.feature.notification.NotificationPage
import com.valhalla.feature.notification.NotificationTarget
import com.valhalla.feature.ota.OtaState
import com.valhalla.feature.param.ParamEditorState
import com.valhalla.feature.param.ParamFieldState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class UiLogicTestBase {
    internal val strings = StringResolver(ResolvedLanguage.ENGLISH)
    internal val locale = LocaleFormat(ResolvedLanguage.ENGLISH)

    internal fun exportContext(
        state: PacketLogUiState,
        deps: PacketLogExportDeps,
        packetLog: InMemoryPacketLog,
        options: PacketLogExportOptions? = PacketLogExportOptions(),
        rangeError: String? = null,
        fromError: String? = null,
        toError: String? = null,
    ): PacketLogExportContext =
        PacketLogExportContext(
            state = state,
            packetLog = packetLog,
            deps = deps,
            options = options,
            filter = PacketLogFilter(),
            format = PacketLogExportFormat.TEXT,
            rangeError = rangeError,
            fromError = fromError,
            toError = toError,
        )

    internal fun deps(
        scope: CoroutineScope,
        fileSaver: FileSaver,
        sharer: TextSharer,
        names: MutableList<String>,
    ): PacketLogExportDeps =
        PacketLogExportDeps(
            scope = scope,
            errorReporter = ErrorReporter(),
            fileSaver = fileSaver,
            sharer = sharer,
            strings = strings,
            localeFormat = locale,
            exportedNames = names,
        )

    internal fun unusedSaver(): FileSaver = FixedFileSaver(FileSaveResult(FileSaveStatus.UNAVAILABLE))

    internal fun unusedSharer(): TextSharer = FixedTextSharer(ShareResult(ShareStatus.UNAVAILABLE))

    internal fun exportResult(
        text: String,
        entryCount: Int,
        byteSize: Int,
    ): PacketLogExportResult =
        PacketLogExportResult(
            text = text,
            fileName = "log.txt",
            format = PacketLogExportFormat.TEXT,
            entryCount = entryCount,
            sourceCount = entryCount,
            matchedCount = entryCount,
            truncated = false,
            byteSize = byteSize,
        )

    internal fun emptyStats(): ConnectionQualityStats =
        ConnectionQualityStats(
            requests = 0,
            responses = 0,
            successes = 0,
            deviceErrors = 0,
            noResponses = 0,
            retransmissions = 0,
            outOfOrderResponses = 0,
            unmatchedResponses = 0,
            averageLatencyMillis = null,
            maxLatencyMillis = null,
            successRate = null,
            errorCodes = emptyList(),
            commands = emptyList(),
            recentFailures = emptyList(),
            insufficientSample = true,
        )

    internal fun deviceInfo(model: String): DeviceInfoResponse =
        DeviceInfoResponse(
            status = DeviceStatus.OK,
            vendor = "DetailVendor",
            model = model,
            serialNumber = "SN",
            hardwareVersion = "hw",
            firmwareVersion = FirmwareVersion(1, 0, 0, 0, "stable"),
        )

    internal fun otaRecord(): OtaHistoryRecord =
        OtaHistoryRecord(
            firmwareVersion = "1.0.0 (build 2, release)",
            finishedAtEpochMillis = 100L,
            durationMillis = 50L,
            outcome = OtaOutcome.SUCCEEDED,
            sentBytes = 10,
            totalBytes = 10,
        )

    internal fun configSpec(
        key: String,
        displayName: String,
    ): ConfigKeySpec =
        ConfigKeySpec(
            key = key,
            displayName = displayName,
            type = ConfigValueType.STRING,
            editable = true,
        )

    internal fun paramSpec(
        id: Int,
        displayName: String,
    ): ParamSpec =
        ParamSpec(
            id = id,
            displayName = displayName,
            type = ParamValueType.STRING,
            writable = true,
        )

    internal fun packetEntry(): PacketLogEntry =
        PacketLogEntry(
            id = 1L,
            timestampMillis = 0L,
            direction = FrameDirection.TX,
            command = CommandType.AUTH,
            sequence = 0,
            isResponse = false,
            isNotification = false,
            hex = "00",
            summary = "AUTH",
        )

    internal class FixedFileSaver(
        private val result: FileSaveResult,
    ) : FileSaver {
        val savedFiles = mutableListOf<SaveableFile>()

        override suspend fun save(file: SaveableFile): FileSaveResult {
            savedFiles += file
            return result
        }
    }

    internal class FixedTextSharer(
        private val result: ShareResult,
    ) : TextSharer {
        val sharedPayloads = mutableListOf<ShareableText>()

        override suspend fun share(payload: ShareableText): ShareResult {
            sharedPayloads += payload
            return result
        }
    }

    internal class FixedPicker(
        private val picked: PickedFirmware?,
    ) : FirmwarePicker {
        override suspend fun pickFirmware(): PickedFirmware? = picked
    }
}

class ValhallaPacketLogLogicTest : UiLogicTestBase() {
    @Test
    fun packetLogDerivedHandlesBlankAndInvalidTimes() {
        val options = PacketLogExportOptions()

        val blank = packetLogDerived(PacketLogUiState(options), options, strings, zoneOffsetMinutes = 0)
        assertNull(blank.fromMillis)
        assertNull(blank.toMillis)
        assertNull(blank.fromError)
        assertNull(blank.toError)
        assertNull(blank.rangeError)
        assertNull(blank.filter.fromMillis)
        assertNull(blank.filter.toMillis)

        val state = PacketLogUiState(options)
        state.fromText = "not-a-time"
        state.toText = "bad"
        val invalid = packetLogDerived(state, options, strings, zoneOffsetMinutes = 0)
        assertNull(invalid.fromMillis)
        assertNull(invalid.toMillis)
        assertEquals(strings.t(StringKey.LOGS_TIME_FORMAT_ERROR), invalid.fromError)
        assertEquals(strings.t(StringKey.LOGS_TIME_FORMAT_ERROR), invalid.toError)

        val reversed = PacketLogUiState(options)
        reversed.fromText = "2024-02-01 00:00:00"
        reversed.toText = "2024-01-01 00:00:00"
        val range = packetLogDerived(reversed, options, strings, zoneOffsetMinutes = 0)
        assertTrue(range.fromMillis!! > range.toMillis!!)
        assertEquals(strings.t(StringKey.LOGS_TIME_RANGE_ERROR), range.rangeError)
    }

    @Test
    fun packetLogDerivedValidatesLimitsAndBuildsFilter() {
        val options = PacketLogExportOptions()

        val invalidEntry = PacketLogUiState(options)
        invalidEntry.maxEntriesText = "0"
        val entryDerived = packetLogDerived(invalidEntry, options, strings, zoneOffsetMinutes = 0)
        assertFalse(entryDerived.entryLimit.valid)
        assertTrue(entryDerived.entryLimit.error!!.isNotBlank())
        assertNull(entryDerived.options)

        val invalidBytes = PacketLogUiState(options)
        invalidBytes.maxBytesKbText = "abc"
        val byteDerived = packetLogDerived(invalidBytes, options, strings, zoneOffsetMinutes = 0)
        assertFalse(byteDerived.byteLimit.valid)
        assertTrue(byteDerived.byteLimit.error!!.isNotBlank())
        assertNull(byteDerived.options)

        val state = PacketLogUiState(options)
        state.fromText = "2024-03-01 00:00:00"
        state.toText = "2024-03-02 00:00:00"
        state.selectedCommands = setOf(CommandType.AUTH, CommandType.REBOOT)
        state.errorsOnly = true
        state.appliedQuery = "auth"
        val derived = packetLogDerived(state, options, strings, zoneOffsetMinutes = 0)

        assertTrue(derived.entryLimit.valid)
        assertTrue(derived.byteLimit.valid)
        val derivedOptions = derived.options
        assertNotNull(derivedOptions)
        assertEquals(200, derivedOptions.maxEntries)
        assertEquals(512 * 1024, derivedOptions.maxBytes)
        assertEquals(4096, derivedOptions.maxHexCharsPerEntry)
        assertEquals(setOf(CommandType.AUTH, CommandType.REBOOT), derived.filter.commands)
        assertTrue(derived.filter.errorsOnly)
        assertEquals("auth", derived.filter.keyword)
        assertEquals(derived.fromMillis, derived.filter.fromMillis)
        assertEquals(derived.toMillis, derived.filter.toMillis)
    }

    @Test
    fun savePacketLogExportMapsSaveStatuses() =
        runTest {
            val result = exportResult(text = "body", entryCount = 2, byteSize = 4)

            val savedNames = mutableListOf<String>()
            val saver = FixedFileSaver(FileSaveResult(FileSaveStatus.SAVED))
            val (savedOk, savedMessage) =
                savePacketLogExport(deps(this, saver, unusedSharer(), savedNames), "log.txt", result, PacketLogExportFormat.TEXT)
            assertTrue(savedOk)
            assertEquals(
                strings.t(
                    StringKey.LOGS_SAVE_SUCCESS,
                    "name" to "log.txt",
                    "entries" to locale.number(2),
                    "bytes" to locale.number(4),
                ),
                savedMessage,
            )
            assertEquals(listOf("log.txt"), savedNames)
            val savedFile = saver.savedFiles.single()
            assertEquals("log.txt", savedFile.fileName)
            assertEquals("body", savedFile.content)
            assertEquals(PacketLogExportFormat.TEXT.mimeType, savedFile.mimeType)

            val cancelledNames = mutableListOf<String>()
            val (cancelledOk, cancelledMessage) =
                savePacketLogExport(
                    deps(this, FixedFileSaver(FileSaveResult(FileSaveStatus.CANCELLED)), unusedSharer(), cancelledNames),
                    "log.txt",
                    result,
                    PacketLogExportFormat.TEXT,
                )
            assertFalse(cancelledOk)
            assertEquals(strings.t(StringKey.LOGS_SAVE_CANCELLED), cancelledMessage)
            assertTrue(cancelledNames.isEmpty())

            val unavailableNames = mutableListOf<String>()
            val (unavailableOk, unavailableMessage) =
                savePacketLogExport(
                    deps(this, FixedFileSaver(FileSaveResult(FileSaveStatus.UNAVAILABLE)), unusedSharer(), unavailableNames),
                    "log.txt",
                    result,
                    PacketLogExportFormat.TEXT,
                )
            assertFalse(unavailableOk)
            assertEquals(strings.t(StringKey.LOGS_SAVE_UNAVAILABLE), unavailableMessage)
            assertTrue(unavailableNames.isEmpty())

            val failedNames = mutableListOf<String>()
            val (failedOk, failedMessage) =
                savePacketLogExport(
                    deps(this, FixedFileSaver(FileSaveResult(FileSaveStatus.FAILED, "boom")), unusedSharer(), failedNames),
                    "log.txt",
                    result,
                    PacketLogExportFormat.TEXT,
                )
            assertFalse(failedOk)
            assertEquals(strings.t(StringKey.LOGS_SAVE_FAILED, "detail" to "boom"), failedMessage)
            assertTrue(failedNames.isEmpty())
        }

    @Test
    fun sharePacketLogExportMapsShareStatuses() =
        runTest {
            val result = exportResult(text = "body", entryCount = 3, byteSize = 6)

            val sharedNames = mutableListOf<String>()
            val sharer = FixedTextSharer(ShareResult(ShareStatus.SHARED))
            val (sharedOk, sharedMessage) =
                sharePacketLogExport(deps(this, unusedSaver(), sharer, sharedNames), "log.txt", result, PacketLogExportFormat.TEXT)
            assertTrue(sharedOk)
            assertEquals(
                strings.t(
                    StringKey.LOGS_SHARE_SUCCESS,
                    "name" to "log.txt",
                    "entries" to locale.number(3),
                    "bytes" to locale.number(6),
                ),
                sharedMessage,
            )
            assertEquals(listOf("log.txt"), sharedNames)
            val payload = sharer.sharedPayloads.single()
            assertEquals("log.txt", payload.fileName)
            assertEquals("body", payload.content)
            assertEquals(PacketLogExportFormat.TEXT.mimeType, payload.mimeType)

            val unavailableNames = mutableListOf<String>()
            val (unavailableOk, unavailableMessage) =
                sharePacketLogExport(
                    deps(this, unusedSaver(), FixedTextSharer(ShareResult(ShareStatus.UNAVAILABLE)), unavailableNames),
                    "log.txt",
                    result,
                    PacketLogExportFormat.TEXT,
                )
            assertFalse(unavailableOk)
            assertEquals(strings.t(StringKey.LOGS_SHARE_UNAVAILABLE), unavailableMessage)
            assertTrue(unavailableNames.isEmpty())

            val failedNames = mutableListOf<String>()
            val (failedOk, failedMessage) =
                sharePacketLogExport(
                    deps(this, unusedSaver(), FixedTextSharer(ShareResult(ShareStatus.FAILED, "nope")), failedNames),
                    "log.txt",
                    result,
                    PacketLogExportFormat.TEXT,
                )
            assertFalse(failedOk)
            assertEquals(strings.t(StringKey.LOGS_SHARE_FAILED, "detail" to "nope"), failedMessage)
            assertTrue(failedNames.isEmpty())
        }

    @Test
    fun startPacketLogExportGuardsAndRunsSuccess() =
        runTest {
            val state = PacketLogUiState(PacketLogExportOptions())
            val saver = FixedFileSaver(FileSaveResult(FileSaveStatus.SAVED))
            val deps = deps(this, saver, unusedSharer(), state.exportedNames)
            val packetLog = InMemoryPacketLog()
            packetLog.record(FrameDirection.TX, Frame(type = CommandType.AUTH, sequence = 0))

            state.exporting = true
            startPacketLogExport(exportContext(state, deps, packetLog), ExportAction.SAVE)
            assertTrue(state.exporting)
            assertNull(state.exportJob)

            state.exporting = false
            startPacketLogExport(exportContext(state, deps, packetLog, rangeError = "range"), ExportAction.SAVE)
            assertFalse(state.exporting)
            assertEquals("range", state.exportMessage)
            assertTrue(state.exportedNames.isEmpty())
            assertNull(state.exportJob)

            startPacketLogExport(exportContext(state, deps, packetLog, fromError = "from"), ExportAction.SAVE)
            assertFalse(state.exporting)
            assertEquals("from", state.exportMessage)
            assertNull(state.exportJob)

            startPacketLogExport(exportContext(state, deps, packetLog, toError = "to"), ExportAction.SAVE)
            assertFalse(state.exporting)
            assertEquals("to", state.exportMessage)
            assertNull(state.exportJob)

            startPacketLogExport(exportContext(state, deps, packetLog, options = null), ExportAction.SAVE)
            assertFalse(state.exporting)
            assertEquals(strings.t(StringKey.LOGS_INVALID_LIMIT), state.exportMessage)
            assertNull(state.exportJob)

            state.estimate =
                PacketLogExportEstimate(
                    sourceCount = 1,
                    matchedCount = 0,
                    estimatedEntries = 0,
                    estimatedBytes = 0,
                    truncated = false,
                )
            startPacketLogExport(exportContext(state, deps, packetLog), ExportAction.SAVE)
            assertFalse(state.exporting)
            assertEquals(strings.t(StringKey.LOGS_ESTIMATE_NO_MATCH), state.exportMessage)
            assertNull(state.exportJob)

            state.estimate = null
            startPacketLogExport(exportContext(state, deps, packetLog), ExportAction.SAVE)
            val job = state.exportJob!!
            assertTrue(state.exporting)
            job.join()
            assertFalse(state.exporting)
            assertTrue(state.exportSuccess)
            val savedFile = saver.savedFiles.single()
            assertEquals(listOf(savedFile.fileName), state.exportedNames)
            assertEquals(
                strings.t(
                    StringKey.LOGS_SAVE_SUCCESS,
                    "name" to savedFile.fileName,
                    "entries" to locale.number(1),
                    "bytes" to locale.number(savedFile.content.encodeToByteArray().size),
                ),
                state.exportMessage,
            )
            assertNull(state.exportJob)
        }
}

class ValhallaUiLogicTest : UiLogicTestBase() {
    @Test
    fun recordOtaLibraryEntryGuardsMetadataAndRecordsSuccess() =
        runTest {
            val targetVersion = FirmwareVersion(1, 1, 0, 0, "release")
            val currentVersion = FirmwareVersion(1, 0, 0, 1, "release")
            val payload = ByteArray(300) { index -> (index % 251).toByte() }
            val packageBytes =
                FirmwareHeader(
                    firmwareVersion = targetVersion,
                    targetModels = listOf("VH-100"),
                    payloadLength = payload.size.toLong(),
                    payloadCrc32 = Crc32.compute(payload),
                ).encode() + payload
            val picker =
                FixedPicker(
                    PickedFirmware(
                        name = "firmware.bin",
                        source = FirmwareSource.memory(packageBytes),
                        sourcePath = "mem://firmware.bin",
                    ),
                )
            val controller = FirmwareSelectionController(picker, InMemoryOtaCheckpointStore())
            assertTrue(controller.pickAndPrecheck(currentVersion, "VH-100"))

            val library = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
            val current = OtaState.Succeeded(totalBytes = payload.size, crc16 = 0, chunks = 1)
            val state = controller.state.value

            assertFalse(recordOtaLibraryEntry(current, controller, state.copy(version = null), library, "dev-1"))
            assertFalse(recordOtaLibraryEntry(current, controller, state.copy(sourcePath = null), library, "dev-1"))
            assertFalse(
                recordOtaLibraryEntry(
                    current,
                    controller,
                    state.copy(computedCrc32 = null, declaredCrc32 = null),
                    library,
                    "dev-1",
                ),
            )
            assertFalse(recordOtaLibraryEntry(current, controller, state.copy(packageName = null), library, "dev-1"))
            assertEquals(0, library.count())

            assertTrue(recordOtaLibraryEntry(current, controller, state, library, "dev-1"))
            val record = library.list().single()
            assertEquals("firmware.bin", record.fileName)
            assertEquals(targetVersion.toString(), record.version)
            assertEquals(listOf("VH-100"), record.modelWhitelist)
            assertEquals(Crc32.compute(payload), record.crc32)
            assertEquals(payload.size.toLong(), record.totalBytes)
            assertEquals("mem://firmware.bin", record.sourcePath)
            assertEquals(FirmwareLibraryOutcome.SUCCEEDED, record.lastOutcome)
            assertEquals("dev-1", record.lastDeviceId)
            controller.close()
        }

    @Test
    fun otaNotificationEventMapsTerminalStates() {
        val version = FirmwareVersion(1, 2, 3, 4, "stable")

        val succeeded =
            otaNotificationEvent(
                current = OtaState.Succeeded(totalBytes = 100, crc16 = 7, chunks = 2),
                version = version,
                deviceId = "dev-1",
                strings = strings,
            )!!
        assertEquals(NotificationEventType.OTA_SUCCESS, succeeded.type)
        assertEquals(strings.t(StringKey.NOTIFICATION_OTA_SUCCESS_TITLE), succeeded.title)
        assertEquals(
            strings.t(StringKey.NOTIFICATION_OTA_SUCCESS_BODY, "version" to version.toString()),
            succeeded.body,
        )
        assertEquals(NotificationTarget(NotificationPage.OTA, "dev-1"), succeeded.target)
        assertEquals("dev-1", succeeded.deviceId)

        val reason = UiMessage.of(StringKey.UNKNOWN)
        val failed =
            otaNotificationEvent(
                current = OtaState.Failed(reason = reason, sentBytes = 0),
                version = version,
                deviceId = "dev-2",
                strings = strings,
            )!!
        assertEquals(NotificationEventType.OTA_FAILED, failed.type)
        assertEquals(strings.t(StringKey.NOTIFICATION_OTA_FAILED_TITLE), failed.title)
        assertEquals(
            strings.t(
                StringKey.NOTIFICATION_OTA_FAILED_BODY,
                "version" to version.toString(),
                "reason" to strings.render(reason),
            ),
            failed.body,
        )
        assertEquals(NotificationTarget(NotificationPage.OTA, "dev-2"), failed.target)
        assertEquals("dev-2", failed.deviceId)

        val unknownVersion =
            otaNotificationEvent(
                current = OtaState.Succeeded(totalBytes = 100, crc16 = 7, chunks = 2),
                version = null,
                deviceId = "dev-3",
                strings = strings,
            )!!
        assertEquals(
            strings.t(StringKey.NOTIFICATION_OTA_SUCCESS_BODY, "version" to strings.t(StringKey.UNKNOWN)),
            unknownVersion.body,
        )

        assertNull(
            otaNotificationEvent(
                current = OtaState.Idle,
                version = version,
                deviceId = "dev-4",
                strings = strings,
            ),
        )
        assertNull(
            otaNotificationEvent(
                current = OtaState.Aborted(reason = "USER", sentBytes = 1, totalBytes = 2),
                version = version,
                deviceId = "dev-5",
                strings = strings,
            ),
        )
    }

    @Test
    fun emptyReasonTextMapsEveryReason() {
        assertNull(emptyReasonText(ScanEmptyReason.NONE, strings))
        assertNull(emptyReasonText(ScanEmptyReason.ERROR, strings))
        assertEquals(strings.t(StringKey.SCAN_EMPTY_SCANNING), emptyReasonText(ScanEmptyReason.SCANNING, strings))
        assertEquals(strings.t(StringKey.SCAN_EMPTY_TIMEOUT), emptyReasonText(ScanEmptyReason.SCAN_TIMEOUT, strings))
        assertEquals(strings.t(StringKey.SCAN_EMPTY_NO_DEVICES), emptyReasonText(ScanEmptyReason.NO_DEVICES, strings))
        assertEquals(
            strings.t(StringKey.SCAN_EMPTY_NO_PERMISSION),
            emptyReasonText(ScanEmptyReason.NO_PERMISSION, strings),
        )
        assertEquals(
            strings.t(StringKey.SCAN_EMPTY_PERMISSION_PERMANENTLY_DENIED),
            emptyReasonText(ScanEmptyReason.PERMISSION_PERMANENTLY_DENIED, strings),
        )
        assertEquals(
            strings.t(StringKey.SCAN_EMPTY_BLUETOOTH_OFF),
            emptyReasonText(ScanEmptyReason.BLUETOOTH_OFF, strings),
        )
        assertEquals(
            strings.t(StringKey.SCAN_EMPTY_BLUETOOTH_RESETTING),
            emptyReasonText(ScanEmptyReason.BLUETOOTH_RESETTING, strings),
        )
        assertEquals(strings.t(StringKey.SCAN_EMPTY_UNSUPPORTED), emptyReasonText(ScanEmptyReason.UNSUPPORTED, strings))
        assertEquals(
            strings.t(StringKey.SCAN_EMPTY_UNKNOWN),
            emptyReasonText(ScanEmptyReason.UNKNOWN_AVAILABILITY, strings),
        )
        ScanEmptyReason.entries
            .filter { it != ScanEmptyReason.NONE && it != ScanEmptyReason.ERROR }
            .forEach { reason -> assertTrue(emptyReasonText(reason, strings)!!.isNotBlank()) }
    }

    @Test
    fun notificationTargetRouteMapsPagesAndSessionIds() {
        val sessions = setOf("dev-1")

        assertEquals(Route.Scan, notificationTargetRoute(NotificationTarget(NotificationPage.HOME), sessions))
        assertEquals(Route.Detail("dev-1"), notificationTargetRoute(NotificationTarget(NotificationPage.DETAIL, "dev-1"), sessions))
        assertEquals(Route.Ota("dev-1"), notificationTargetRoute(NotificationTarget(NotificationPage.OTA, "dev-1"), sessions))
        assertEquals(
            Route.Diagnostic("dev-1"),
            notificationTargetRoute(NotificationTarget(NotificationPage.DIAGNOSTIC, "dev-1"), sessions),
        )

        assertNull(notificationTargetRoute(NotificationTarget(NotificationPage.DETAIL, "dev-2"), sessions))
        assertNull(notificationTargetRoute(NotificationTarget(NotificationPage.OTA, "dev-2"), sessions))
        assertNull(notificationTargetRoute(NotificationTarget(NotificationPage.DIAGNOSTIC, "dev-2"), sessions))
        assertNull(notificationTargetRoute(NotificationTarget(NotificationPage.DETAIL, null), sessions))
        assertNull(notificationTargetRoute(NotificationTarget(NotificationPage.OTA, null), sessions))
        assertNull(notificationTargetRoute(NotificationTarget(NotificationPage.DIAGNOSTIC, null), sessions))
    }

    @Test
    fun packetLogUiStateAndOtaLibraryUiStateDefaults() {
        val state = PacketLogUiState(PacketLogExportOptions())
        assertEquals("", state.query)
        assertEquals("", state.appliedQuery)
        assertFalse(state.errorsOnly)
        assertEquals(emptySet(), state.selectedCommands)
        assertEquals("", state.fromText)
        assertEquals("", state.toText)
        assertEquals(PacketLogExportFormat.TEXT, state.format)
        assertEquals("200", state.maxEntriesText)
        assertEquals("512", state.maxBytesKbText)
        assertFalse(state.exporting)
        assertNull(state.progress)
        assertNull(state.exportJob)
        assertNull(state.exportMessage)
        assertFalse(state.exportSuccess)
        assertNull(state.estimate)
        assertTrue(state.exportedNames.isEmpty())

        val library = OtaLibraryUiState()
        assertEquals(0, library.version)
        assertNull(library.busyId)
        assertNull(library.message)
        assertEquals("", library.modelFilter)
        assertEquals("", library.versionQuery)
        assertEquals(FirmwareLibrarySort.RECENT, library.sort)
        assertNull(library.renameId)
        assertEquals("", library.renameText)
        assertFalse(library.importing)
    }

    @Test
    fun packetLogEstimateInputAndExportContextCarryFields() =
        runTest {
            val state = PacketLogUiState(PacketLogExportOptions())
            val entries = listOf(packetEntry())
            val filter = PacketLogFilter(command = CommandType.AUTH, errorsOnly = true, keyword = "kw", fromMillis = 1, toMillis = 2)
            val options = PacketLogExportOptions()

            val input =
                PacketLogEstimateInput(
                    entries = entries,
                    logIndex = PacketLogIndex(entries),
                    filter = filter,
                    format = PacketLogExportFormat.JSON,
                    entryLimitValue = 200,
                    byteLimitValue = 512 * 1024,
                    rangeError = "range",
                    options = options,
                    nowMillis = 10L,
                    zoneOffsetMinutes = 60,
                    state = state,
                )
            assertEquals(entries, input.entries)
            assertEquals(filter, input.filter)
            assertEquals(PacketLogExportFormat.JSON, input.format)
            assertEquals(200, input.entryLimitValue)
            assertEquals(512 * 1024, input.byteLimitValue)
            assertEquals("range", input.rangeError)
            assertEquals(options, input.options)
            assertEquals(10L, input.nowMillis)
            assertEquals(60, input.zoneOffsetMinutes)
            assertEquals(state, input.state)
            assertEquals(entries.size, input.logIndex.size)

            val packetLog = InMemoryPacketLog()
            val names = mutableListOf("first.txt")
            val deps = deps(this, unusedSaver(), unusedSharer(), names)
            val context =
                PacketLogExportContext(
                    state = state,
                    packetLog = packetLog,
                    deps = deps,
                    options = options,
                    filter = filter,
                    format = PacketLogExportFormat.CSV,
                    rangeError = "range",
                    fromError = "from",
                    toError = "to",
                )
            assertEquals(state, context.state)
            assertEquals(packetLog, context.packetLog)
            assertEquals(deps, context.deps)
            assertEquals(options, context.options)
            assertEquals(filter, context.filter)
            assertEquals(PacketLogExportFormat.CSV, context.format)
            assertEquals("range", context.rangeError)
            assertEquals("from", context.fromError)
            assertEquals("to", context.toError)
        }
}

class ValhallaDiagnosticsLogicTest : UiLogicTestBase() {
    @Test
    fun buildDiagnosticReportInputMapsProfileConfigParamAndFailures() {
        val record = otaRecord()
        val profile =
            DeviceProfile(
                deviceId = "dev-1",
                displayName = "ProfileName",
                vendor = "ProfileVendor",
                model = "ProfileModel",
                lastConnectedAtEpochMillis = 100L,
                lastKnownFirmwareVersion = "ProfileFW",
                lastKnownBatteryPercent = 88,
                otaHistory = listOf(record),
            )
        val detail =
            DeviceDetailState(
                deviceId = "dev-1",
                firmwareVersion = "DetailFW",
                batteryPercent = 42,
                charging = true,
            )
        val configState =
            ConfigEditorState(
                fields =
                    listOf(
                        ConfigFieldState(spec = configSpec("k1", "K1"), text = "v1", loaded = true),
                        ConfigFieldState(spec = configSpec("k2", "K2"), text = "draft", loaded = false, loadError = "load-2"),
                    ),
            )
        val paramState =
            ParamEditorState(
                fields =
                    listOf(
                        ParamFieldState(spec = paramSpec(1, "P1"), text = "p1", loaded = true),
                        ParamFieldState(spec = paramSpec(2, "P2"), text = "draft", loaded = false, loadError = "param-err"),
                    ),
            )
        val stats = emptyStats().copy(recentFailures = listOf(QualityFailure(CommandType.REBOOT, 500L, "no response", true)))
        val entries = listOf(packetEntry())

        val input =
            buildDiagnosticReportInput(
                detail = detail,
                profile = profile,
                configState = configState,
                paramState = paramState,
                stats = stats,
                entries = entries,
                zoneOffsetMinutes = 60,
                generatedAt = 1_234L,
            )

        assertEquals(1_234L, input.generatedAtEpochMillis)
        assertEquals(60, input.zoneOffsetMinutes)
        assertEquals("dev-1", input.deviceId)
        assertEquals("ProfileModel", input.deviceModel)
        assertEquals("DetailFW", input.firmwareVersion)
        assertEquals(42, input.batteryPercent)
        assertEquals(true, input.charging)
        assertEquals(entries, input.packetLog)

        assertEquals(2, input.configValues.size)
        assertEquals("K1", input.configValues[0].label)
        assertEquals("v1", input.configValues[0].value)
        assertNull(input.configValues[0].status)
        assertEquals("K2", input.configValues[1].label)
        assertNull(input.configValues[1].value)
        assertEquals("load-2", input.configValues[1].status)

        assertEquals(2, input.paramValues.size)
        assertEquals("P1", input.paramValues[0].label)
        assertEquals("p1", input.paramValues[0].value)
        assertNull(input.paramValues[0].status)
        assertEquals("P2", input.paramValues[1].label)
        assertNull(input.paramValues[1].value)
        assertEquals("param-err", input.paramValues[1].status)

        assertEquals(1, input.otaHistory.size)
        assertEquals(record.firmwareVersion, input.otaHistory[0].firmwareVersion)
        assertEquals(OtaOutcome.SUCCEEDED.name, input.otaHistory[0].outcome)
        assertEquals(record.durationMillis, input.otaHistory[0].durationMillis)

        assertEquals(1, input.recentErrors.size)
        assertEquals(500L, input.recentErrors[0].timestampMillis)
        assertEquals("REBOOT", input.recentErrors[0].command)
        assertEquals("no response", input.recentErrors[0].detail)
    }

    @Test
    fun buildDiagnosticReportInputWithoutProfileUsesDetailAndFirmwareFallback() {
        val firmware = FirmwareVersion(2, 0, 1, 1, "beta")
        val detail =
            DeviceDetailState(
                deviceId = "dev-2",
                firmwareVersion = null,
                firmware = firmware,
                deviceInfo = deviceInfo("DetailModel"),
                batteryPercent = 30,
                charging = true,
            )

        val input =
            buildDiagnosticReportInput(
                detail = detail,
                profile = null,
                configState = ConfigEditorState(),
                paramState = ParamEditorState(),
                stats = emptyStats(),
                entries = emptyList(),
                zoneOffsetMinutes = 0,
                generatedAt = 9L,
            )

        assertEquals("DetailModel", input.deviceModel)
        assertEquals(firmware.toString(), input.firmwareVersion)
        assertEquals(30, input.batteryPercent)
        assertTrue(input.otaHistory.isEmpty())
        assertTrue(input.configValues.isEmpty())
        assertTrue(input.paramValues.isEmpty())
        assertTrue(input.recentErrors.isEmpty())

        val missing =
            buildDiagnosticReportInput(
                detail = detail.copy(deviceInfo = null, firmware = null),
                profile = null,
                configState = ConfigEditorState(),
                paramState = ParamEditorState(),
                stats = emptyStats(),
                entries = emptyList(),
                zoneOffsetMinutes = 0,
                generatedAt = 9L,
            )
        assertNull(missing.deviceModel)
        assertNull(missing.firmwareVersion)
    }

    @Test
    fun buildDiagnosticPackageInputFormatsConnectionQuality() {
        val stats =
            ConnectionQualityStats(
                requests = 3,
                responses = 2,
                successes = 1,
                deviceErrors = 1,
                noResponses = 4,
                retransmissions = 2,
                outOfOrderResponses = 1,
                unmatchedResponses = 5,
                averageLatencyMillis = 12.5,
                maxLatencyMillis = 30L,
                successRate = 0.5,
                errorCodes = emptyList(),
                commands = emptyList(),
                recentFailures = emptyList(),
                insufficientSample = true,
            )
        val samples = listOf(DeviceMetricSample(timestampMillis = 1L, batteryPercent = 42, rssi = -60))
        val entries = listOf(packetEntry())

        val input =
            buildDiagnosticPackageInput(
                detail = DeviceDetailState(deviceId = "dev-1"),
                profile = null,
                stats = stats,
                entries = entries,
                zoneOffsetMinutes = 60,
                healthReport = "health",
                metricSamples = samples,
            )

        val expected =
            listOf(
                "requests=3",
                "sampleCount=3",
                "successRate=0.5",
                "averageLatencyMillis=12.5",
                "maxLatencyMillis=30",
                "retransmissions=2",
                "outOfOrder=1",
                "noResponses=4",
                "unmatchedResponses=5",
                "insufficientSample=true",
            ).joinToString("; ")
        assertEquals(expected, input.connectionQuality)
        assertEquals(60, input.zoneOffsetMinutes)
        assertEquals("dev-1", input.deviceId)
        assertEquals("health", input.healthReport)
        assertEquals(entries, input.packetLogEntries)
        assertEquals(samples, input.metricSamples)

        val nulls =
            buildDiagnosticPackageInput(
                detail = DeviceDetailState(deviceId = "dev-1"),
                profile = null,
                stats = emptyStats(),
                entries = emptyList(),
                zoneOffsetMinutes = 0,
                healthReport = null,
                metricSamples = emptyList(),
            )
        val expectedNulls =
            listOf(
                "requests=0",
                "sampleCount=0",
                "successRate=null",
                "averageLatencyMillis=null",
                "maxLatencyMillis=null",
                "retransmissions=0",
                "outOfOrder=0",
                "noResponses=0",
                "unmatchedResponses=0",
                "insufficientSample=true",
            ).joinToString("; ")
        assertEquals(expectedNulls, nulls.connectionQuality)
        assertNull(nulls.healthReport)
    }

    @Test
    fun buildDiagnosticPackageInputProfileSummaryPrefersProfileThenDetailThenDash() {
        val detail =
            DeviceDetailState(
                deviceId = "dev-1",
                firmwareVersion = "DetailFW",
                batteryPercent = 42,
                charging = false,
                deviceInfo = deviceInfo("DetailModel"),
            )
        val profile =
            DeviceProfile(
                deviceId = "dev-1",
                displayName = "ProfileName",
                vendor = "ProfileVendor",
                model = "ProfileModel",
                lastConnectedAtEpochMillis = 100L,
                lastKnownFirmwareVersion = "ProfileFW",
                lastKnownBatteryPercent = 88,
                lastKnownCharging = true,
                otaHistory = listOf(otaRecord()),
            )

        val withProfile =
            buildDiagnosticPackageInput(
                detail = detail,
                profile = profile,
                stats = emptyStats(),
                entries = emptyList(),
                zoneOffsetMinutes = 0,
                healthReport = null,
                metricSamples = emptyList(),
            ).deviceProfileSummary
        val expectedWithProfile =
            listOf(
                "deviceId=dev-1",
                "displayName=ProfileName",
                "vendor=ProfileVendor",
                "model=ProfileModel",
                "firmware=ProfileFW",
                "batteryPercent=88",
                "charging=true",
                "otaRecords=1",
                "lastConnectedAtMillis=100",
            ).joinToString("\n")
        assertEquals(expectedWithProfile, withProfile)

        val withoutProfile =
            buildDiagnosticPackageInput(
                detail = detail,
                profile = null,
                stats = emptyStats(),
                entries = emptyList(),
                zoneOffsetMinutes = 0,
                healthReport = null,
                metricSamples = emptyList(),
            ).deviceProfileSummary
        val expectedWithoutProfile =
            listOf(
                "deviceId=dev-1",
                "displayName=-",
                "vendor=-",
                "model=DetailModel",
                "firmware=DetailFW",
                "batteryPercent=42",
                "charging=false",
                "otaRecords=0",
                "lastConnectedAtMillis=0",
            ).joinToString("\n")
        assertEquals(expectedWithoutProfile, withoutProfile)

        val emptyDetail =
            buildDiagnosticPackageInput(
                detail = DeviceDetailState(deviceId = "dev-3"),
                profile = null,
                stats = emptyStats(),
                entries = emptyList(),
                zoneOffsetMinutes = 0,
                healthReport = null,
                metricSamples = emptyList(),
            ).deviceProfileSummary
        val expectedEmpty =
            listOf(
                "deviceId=dev-3",
                "displayName=-",
                "vendor=-",
                "model=-",
                "firmware=-",
                "batteryPercent=-",
                "charging=-",
                "otaRecords=0",
                "lastConnectedAtMillis=0",
            ).joinToString("\n")
        assertEquals(expectedEmpty, emptyDetail)
    }
}
