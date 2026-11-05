package com.valhalla.feature.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.intl.Locale
import kotlin.random.Random
import com.valhalla.core.AppBuildInfo
import com.valhalla.core.diagnostics.DiagnosticReportComposer
import com.valhalla.core.diagnostics.DiagnosticReportError
import com.valhalla.core.diagnostics.DiagnosticReportFormat
import com.valhalla.core.diagnostics.DiagnosticReportInput
import com.valhalla.core.diagnostics.DiagnosticReportOptions
import com.valhalla.core.diagnostics.DiagnosticReportOta
import com.valhalla.core.diagnostics.DiagnosticReportProgress
import com.valhalla.core.diagnostics.DiagnosticReportResult
import com.valhalla.core.diagnostics.DiagnosticReportSection
import com.valhalla.core.diagnostics.DiagnosticReportValue
import com.valhalla.core.logging.PacketLogRedaction
import com.valhalla.core.protocol.message.AuthMode
import com.valhalla.core.security.HmacAuthSigner
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.command.PacketRecordingFrameLink
import com.valhalla.core.config.DeviceConfigTarget
import com.valhalla.core.config.ConfigPresetDiffer
import com.valhalla.core.automation.AutomationRuleRepository
import com.valhalla.core.automation.AutomationTrigger
import com.valhalla.core.config.ConfigPresetRepository
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.logging.InMemoryPacketLog
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogExportEstimate
import com.valhalla.core.logging.PacketLogExportFormat
import com.valhalla.core.logging.PacketLogExportOptions
import com.valhalla.core.logging.PacketLogExportPhase
import com.valhalla.core.logging.PacketLogExportProgress
import com.valhalla.core.logging.PacketLogExporter
import com.valhalla.core.logging.PacketLogFilter
import com.valhalla.core.logging.PacketLogIndex
import com.valhalla.core.logging.formatForCopy
import com.valhalla.core.logging.formatAbsoluteTime
import com.valhalla.core.metrics.DeviceMetricRepository
import com.valhalla.core.metrics.DeviceMetricSample
import com.valhalla.core.metrics.DeviceMetricSeries
import com.valhalla.feature.diagnostics.DiagnosticPackageBuilder
import com.valhalla.feature.diagnostics.DiagnosticPackageInput
import com.valhalla.feature.diagnostics.DiagnosticPackageProgress
import com.valhalla.feature.diagnostics.DiagnosticPackageResult
import com.valhalla.data.metrics.createDeviceMetricStore
import com.valhalla.feature.health.DeviceHealthCheckController
import com.valhalla.feature.health.HealthCheckItemType
import com.valhalla.feature.health.HealthCheckReportFormatter
import com.valhalla.feature.health.HealthCheckResult
import com.valhalla.core.batch.BatchItemStatus
import com.valhalla.core.batch.BatchOperationKind
import com.valhalla.core.batch.DeviceOperationLocks
import com.valhalla.core.ota.OtaQueueItem
import com.valhalla.core.ota.OtaQueueStage
import com.valhalla.core.ota.OtaQueueTarget
import com.valhalla.core.firmware.FirmwareApplicabilityEvaluator
import com.valhalla.core.firmware.FirmwareApplicabilityReason
import com.valhalla.core.firmware.FirmwareLibraryFilter
import com.valhalla.core.firmware.FirmwareLibraryImportResult
import com.valhalla.feature.batch.BatchDeviceOutcome
import com.valhalla.feature.batch.BatchOperationController
import com.valhalla.feature.firmware.FirmwareImportOutcome
import com.valhalla.feature.firmware.FirmwareLibraryImporter
import com.valhalla.feature.firmware.FirmwarePicker
import com.valhalla.feature.health.HealthCheckState
import com.valhalla.feature.health.HealthCheckStatus
import com.valhalla.feature.health.labelKey
import com.valhalla.feature.ota.OtaQueueController
import com.valhalla.feature.ota.OtaQueueExecutionResult
import com.valhalla.feature.ota.OtaQueueExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import com.valhalla.core.logging.parseLocalDateTimeMillis
import com.valhalla.core.logging.validateByteLimitKb
import com.valhalla.core.logging.validateEntryLimit
import com.valhalla.core.profile.DeviceProfile
import com.valhalla.core.profile.DeviceProfileRepository
import com.valhalla.core.profile.OtaOutcome
import com.valhalla.core.profile.OtaHistoryRecord
import com.valhalla.feature.device.ManagedDeviceInfo
import com.valhalla.feature.device.MultiDeviceConnectResult
import com.valhalla.feature.device.MultiDeviceManager
import com.valhalla.feature.device.MultiDeviceState
import com.valhalla.feature.detail.QuickActionState
import com.valhalla.feature.detail.QuickActionType
import com.valhalla.feature.scan.RssiBand
import com.valhalla.feature.scan.ScanSortMode
import com.valhalla.feature.error.ErrorReporter
import com.valhalla.feature.error.LocalErrorReporter
import com.valhalla.feature.error.installUncaughtExceptionBridge
import com.valhalla.feature.error.launchSafely
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.OtaAbortReason
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.data.ble.BleAdapterState
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.BleFrameLink
import com.valhalla.data.ble.BleTimeoutException
import com.valhalla.data.ble.createBleClient
import com.valhalla.data.firmware.createOtaCheckpointStore
import com.valhalla.data.profile.createDeviceProfileStore
import com.valhalla.data.security.createTokenStore
import com.valhalla.data.settings.createAppSettingsStore
import com.valhalla.data.share.ShareStatus
import com.valhalla.data.share.ShareableText
import com.valhalla.data.share.createTextSharer
import com.valhalla.data.share.currentTimeZoneOffsetMinutes
import com.valhalla.feature.ble.AutoReconnectController
import com.valhalla.feature.ble.BleAvailability
import com.valhalla.feature.ble.BleGuidanceAction
import com.valhalla.feature.ble.BlePermissionController
import com.valhalla.feature.ble.BlePermissionState
import com.valhalla.feature.ble.DisconnectAction
import com.valhalla.feature.ble.DisconnectInfo
import com.valhalla.feature.ble.DisconnectSignals
import com.valhalla.feature.ble.ReconnectPhase
import com.valhalla.feature.ble.ReconnectPolicy
import com.valhalla.feature.ble.ReconnectState
import com.valhalla.feature.ble.ScanEmptyReason
import com.valhalla.feature.ble.allowsConnection
import com.valhalla.feature.ble.classifyDisconnect
import com.valhalla.feature.ble.classifyScanEmpty
import com.valhalla.feature.ble.guidance
import com.valhalla.feature.ble.rememberBlePermissionController
import com.valhalla.feature.ble.resolveBleAvailability
import com.valhalla.feature.config.ConfigEditorController
import com.valhalla.feature.config.ConfigEditorState
import com.valhalla.feature.config.ConfigFieldState
import com.valhalla.feature.config.ConfigPresetApplier
import com.valhalla.feature.automation.AutomationContext
import com.valhalla.feature.automation.AutomationRuleRunner
import com.valhalla.feature.detail.DeviceDetailController
import com.valhalla.feature.detail.DeviceDetailState
import com.valhalla.feature.export.FileSaveStatus
import com.valhalla.feature.export.SaveableFile
import com.valhalla.feature.export.rememberFileSaver
import com.valhalla.feature.firmware.FirmwarePrecheckFactory
import com.valhalla.feature.firmware.FirmwareSelectionController
import com.valhalla.feature.firmware.FirmwareSelectionState
import com.valhalla.feature.firmware.rememberFirmwarePicker
import com.valhalla.feature.firmware.rememberFirmwareSigningKeyPem
import com.valhalla.feature.i18n.AppLanguageMode
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.i18n.render
import com.valhalla.feature.i18n.resolveLanguage
import com.valhalla.feature.ota.OtaController
import com.valhalla.feature.ota.OtaPrecheckRule
import com.valhalla.feature.ota.OtaPrecheckRuleEvaluator
import com.valhalla.feature.ota.OtaPrecheckRuleStatus
import com.valhalla.feature.ota.OtaState
import com.valhalla.feature.ota.OtaWizardFlow
import com.valhalla.feature.ota.OtaWizardStep
import com.valhalla.feature.onboarding.OnboardingController
import com.valhalla.feature.onboarding.OnboardingStep
import com.valhalla.feature.param.ParamEditorController
import com.valhalla.feature.param.ParamEditorState
import com.valhalla.feature.param.ParamFieldState
import com.valhalla.feature.scan.ScanController
import com.valhalla.feature.scan.ScanUiState
import com.valhalla.feature.settings.AppSettings
import com.valhalla.feature.settings.AppSettingsRepository
import com.valhalla.feature.settings.PreferencesAutomationRuleStore
import com.valhalla.feature.settings.PreferencesConfigPresetStore
import com.valhalla.core.logging.ConnectionQualityAnalyzer
import com.valhalla.core.config.ParamCatalog
import com.valhalla.core.config.ParamValueType
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventTimeline
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.firmware.FirmwareLibraryOutcome
import com.valhalla.core.firmware.FirmwareLibraryRecord
import com.valhalla.core.firmware.FirmwareLibraryRepository
import com.valhalla.core.firmware.FirmwareLibrarySort
import com.valhalla.core.firmware.FirmwareReuseCheck
import com.valhalla.core.firmware.FirmwareReuseReason
import com.valhalla.data.event.createDeviceEventStore
import com.valhalla.data.firmware.createFirmwareLibraryStore
import com.valhalla.feature.firmware.FirmwareLibraryReuse
import com.valhalla.feature.metrics.RssiDegradedReason
import com.valhalla.feature.metrics.RssiSource
import com.valhalla.feature.metrics.RssiSubscriptionController
import com.valhalla.feature.notification.NotificationCenter
import com.valhalla.feature.notification.NotificationDecision
import com.valhalla.feature.notification.NotificationEvent
import com.valhalla.feature.notification.NotificationEventType
import com.valhalla.feature.notification.NotificationPage
import com.valhalla.feature.notification.NotificationPresenter
import com.valhalla.feature.notification.NotificationTarget
import com.valhalla.feature.notification.LowBatteryAlertTracker
import com.valhalla.feature.notification.NotificationPolicy
import com.valhalla.feature.notification.rememberNotificationPresenter
import com.valhalla.feature.theme.AppPreferenceStore
import com.valhalla.feature.theme.DesignTokens
import com.valhalla.feature.theme.ThemeMode
import com.valhalla.feature.theme.ValhallaTheme
import com.valhalla.feature.theme.statusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 一次已建立连接对应的协议栈与控制器，详情页与 OTA 页共享同一调度器与断点存储。 */
private class DeviceSession(
    val device: BleDevice,
    val connection: BleConnection,
    val dispatcher: ConcurrentCommandDispatcher,
    val detail: DeviceDetailController,
    val ota: OtaController,
    val checkpointStore: OtaCheckpointStore,
    val config: ConfigEditorController,
    val param: ParamEditorController,
    val packetLog: InMemoryPacketLog,
    val profileRepository: DeviceProfileRepository,
    val events: DeviceEventTimeline,
) {
    /** 用户主动返回断开时为 true，用于区分「设备侧断开」与「主动断开」。 */
    var userDisconnected: Boolean = false

    /** 最近一次体检报告的脱敏文本，供诊断包复用（未做过体检时为 null）。 */
    var lastHealthReport: String? = null
}

/** 正在进行的自动重连：保留设备与报文日志，成功后沿用同一份日志。 */
private class ReconnectSession(
    val device: BleDevice,
    val packetLog: InMemoryPacketLog,
    val controller: AutoReconnectController,
)

private sealed interface Route {
    data object Scan : Route
    data object Devices : Route
    data object Settings : Route
    data object About : Route
    data object Help : Route
    data object Privacy : Route
    data class Onboarding(val review: Boolean) : Route
    data class Detail(val deviceId: String) : Route
    data class Ota(val deviceId: String) : Route
    data class Logs(val deviceId: String) : Route
    data class Diagnostic(val deviceId: String) : Route
    data object Automation : Route
    data object Stats : Route
    data object Backup : Route
    data object Batch : Route
    data object OtaQueue : Route
}

/**
 * Compose Multiplatform 根界面：扫描 → 设备详情 → OTA 三段式流程。
 * Android / iOS 共用同一套页面，平台差异封装在 [createBleClient] 之后。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ValhallaApp(startRoute: String? = null) {
    val settingsStore = remember { createAppSettingsStore() }
    var preferences by remember { mutableStateOf(AppPreferenceStore.load(settingsStore)) }
    var appSettings by remember { mutableStateOf(AppSettingsRepository.load(settingsStore)) }
    val language = resolveLanguage(preferences.language, Locale.current.language)
    val strings = remember(language) { StringResolver(language) }
    val showOnboardingOnLaunch = remember {
        OnboardingController.shouldShowOnLaunch(settingsStore)
    }
    val errorReporter = remember {
        ErrorReporter().also { installUncaughtExceptionBridge(it) }
    }

    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalErrorReporter provides errorReporter,
    ) {
        ValhallaTheme(preferences.theme) {
            ValhallaContent(
                preferences = preferences,
                settings = appSettings,
                showOnboardingOnLaunch = showOnboardingOnLaunch,
                startRoute = startRoute,
                onLanguageChange = { mode ->
                    preferences = preferences.copy(language = mode)
                    AppPreferenceStore.saveLanguage(settingsStore, mode)
                },
                onThemeChange = { mode ->
                    preferences = preferences.copy(theme = mode)
                    AppPreferenceStore.saveTheme(settingsStore, mode)
                },
                onSettingsChange = { updated ->
                    AppSettingsRepository.saveReconnectMaxAttempts(settingsStore, updated.reconnectMaxAttempts)
                    AppSettingsRepository.saveReconnectMaxDelaySeconds(
                        settingsStore,
                        updated.reconnectMaxDelaySeconds,
                    )
                    AppSettingsRepository.saveLogCapacity(settingsStore, updated.logCapacity)
                    AppSettingsRepository.saveExportEntries(settingsStore, updated.exportEntries)
                    AppSettingsRepository.saveExportBytesKb(settingsStore, updated.exportBytesKb)
                    AppSettingsRepository.saveExportHexChars(settingsStore, updated.exportHexChars)
                    AppSettingsRepository.saveNotifyMasterEnabled(
                        settingsStore,
                        updated.notifyMasterEnabled,
                    )
                    AppSettingsRepository.saveNotifyOtaEnabled(settingsStore, updated.notifyOtaEnabled)
                    AppSettingsRepository.saveNotifyDisconnectEnabled(
                        settingsStore,
                        updated.notifyDisconnectEnabled,
                    )
                    AppSettingsRepository.saveNotifyLowBatteryEnabled(
                        settingsStore,
                        updated.notifyLowBatteryEnabled,
                    )
                    AppSettingsRepository.saveNotifyReconnectEnabled(
                        settingsStore,
                        updated.notifyReconnectEnabled,
                    )
                    AppSettingsRepository.saveNotifyHealthEnabled(
                        settingsStore,
                        updated.notifyHealthEnabled,
                    )
                    AppSettingsRepository.saveLowBatteryThreshold(settingsStore, updated.lowBatteryThreshold)
                    AppSettingsRepository.saveRssiSubscriptionEnabled(
                        settingsStore,
                        updated.rssiSubscriptionEnabled,
                    )
                    AppSettingsRepository.saveRssiSamplePeriodSeconds(
                        settingsStore,
                        updated.rssiSamplePeriodSeconds,
                    )
                    appSettings = AppSettingsRepository.load(settingsStore)
                },
                onboardingStore = settingsStore,
                onReloadPersisted = {
                    preferences = AppPreferenceStore.load(settingsStore)
                    appSettings = AppSettingsRepository.load(settingsStore)
                },
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun ValhallaContent(
    preferences: com.valhalla.feature.theme.AppPreferences,
    settings: AppSettings,
    showOnboardingOnLaunch: Boolean,
    startRoute: String?,
    onboardingStore: com.valhalla.data.settings.AppSettingsStore,
    onLanguageChange: (AppLanguageMode) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    onReloadPersisted: () -> Unit,
) {
    val bleClient = remember { createBleClient() }
    val permissionController = rememberBlePermissionController(bleClient)
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val errorReporter = LocalErrorReporter.current
    // 档案仓储跨连接共享，保证扫描/详情页看到同一份设备历史。
    val profileRepository = remember { DeviceProfileRepository(createDeviceProfileStore()) }
    // 事件时间线跨进程共享（存储层每次读取都走平台键值），固件库在同一 App 进程内共享。
    val eventStore = remember { createDeviceEventStore() }
    val firmwareLibrary = remember { FirmwareLibraryRepository(createFirmwareLibraryStore()) }
    // 指标采样只保存在本机，按设备分片，最多 50 个点。
    val metrics = remember { DeviceMetricRepository(createDeviceMetricStore()) }
    // 预设与自动化规则复用平台偏好存储；损坏数据在仓储层回退为空。
    val presetRepository = remember {
        ConfigPresetRepository(
            store = PreferencesConfigPresetStore(onboardingStore),
            clockMillis = { Clock.System.now().toEpochMilliseconds() },
        )
    }
    val automationRepository = remember {
        AutomationRuleRepository(
            store = PreferencesAutomationRuleStore(onboardingStore),
            clockMillis = { Clock.System.now().toEpochMilliseconds() },
        )
    }
    var lastDiagnosticReport by remember { mutableStateOf<String?>(null) }
    val notificationPresenter = rememberNotificationPresenter()
    val notificationCenter = remember {
        NotificationCenter(nowMillis = { Clock.System.now().toEpochMilliseconds() })
    }
    var notificationBanner by remember { mutableStateOf<NotificationEvent?>(null) }
    val scanController = remember {
        ScanController(
            bleClient = bleClient,
            scope = scope,
            onError = { error -> errorReporter.record("scan", error) },
            lastConnectedAtProvider = { id ->
                profileRepository.get(id)?.lastConnectedAtEpochMillis
            },
        )
    }

    // 多设备管理：容量上限、槽位、当前操作设备与断开都经同一管理器。
    val deviceManager = remember {
        MultiDeviceManager(bleClient = bleClient, capacity = 4)
    }
    var sessions by remember { mutableStateOf<Map<String, DeviceSession>>(emptyMap()) }
    val deviceState by deviceManager.state.collectAsState()

    // v2.9.0：批量与 OTA 队列都在进程内串行执行，共享同一把设备写锁。
    val deviceLocks = remember { DeviceOperationLocks() }
    val lockedIds by deviceLocks.locked.collectAsState()
    val sessionsRef = rememberUpdatedState(sessions)
    val batchController = remember {
        BatchOperationController(deviceLocks) { deviceId ->
            sessionsRef.value[deviceId]?.connection?.connected?.value == true
        }
    }
    val queuePicker = rememberFirmwarePicker()
    val queueSigningKeyPem = rememberFirmwareSigningKeyPem()
    val stringsRef = rememberUpdatedState(strings)
    val queueController = remember {
        OtaQueueController(
            scope = scope,
            executor = SessionOtaQueueExecutor(
                sessions = { sessionsRef.value },
                library = firmwareLibrary,
                picker = queuePicker,
                signingKeyPem = queueSigningKeyPem,
                renderReason = { message -> stringsRef.value.render(message) },
            ),
        )
    }

    val permissionState by permissionController.state.collectAsState()
    val adapterState by bleClient.adapterState.collectAsState()
    val availability = remember(adapterState, permissionState) {
        resolveBleAvailability(adapterState, permissionState)
    }

    var route by remember {
        mutableStateOf<Route>(
            if (showOnboardingOnLaunch) Route.Onboarding(review = false) else Route.Scan,
        )
    }

    fun notificationRoute(target: NotificationTarget): Route? = when (target.page) {
        NotificationPage.HOME -> Route.Scan
        NotificationPage.DETAIL -> target.deviceId
            ?.takeIf { sessions.containsKey(it) }
            ?.let { Route.Detail(it) }

        NotificationPage.OTA -> target.deviceId
            ?.takeIf { sessions.containsKey(it) }
            ?.let { Route.Ota(it) }

        NotificationPage.DIAGNOSTIC -> target.deviceId
            ?.takeIf { sessions.containsKey(it) }
            ?.let { Route.Diagnostic(it) }
    }

    LaunchedEffect(startRoute) {
        val target = NotificationTarget.fromWire(startRoute) ?: return@LaunchedEffect
        notificationRoute(target)?.let { route = it }
    }
    var connectingDeviceId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<UiMessage?>(null) }
    var reconnect by remember { mutableStateOf<ReconnectSession?>(null) }

    fun createSession(
        connection: BleConnection,
        device: BleDevice,
        packetLog: InMemoryPacketLog,
    ): DeviceSession {
        val dispatcher = ConcurrentCommandDispatcher(
            PacketRecordingFrameLink(BleFrameLink(connection), packetLog),
        )
        val checkpointStore = createOtaCheckpointStore()
        return DeviceSession(
            device = device,
            connection = connection,
            dispatcher = dispatcher,
            detail = DeviceDetailController(
                dispatcher = dispatcher,
                deviceId = connection.deviceId,
                disconnectLink = { connection.disconnect() },
                tokenStore = createTokenStore(),
                profileRepository = profileRepository,
                deviceDisplayName = device.name,
            ),
            ota = OtaController(
                dispatcher = dispatcher,
                scope = scope,
                connectionState = connection.connected,
                checkpointStore = checkpointStore,
                deviceId = connection.deviceId,
                profileRepository = profileRepository,
                errorReporter = errorReporter,
            ),
            checkpointStore = checkpointStore,
            config = ConfigEditorController(
                dispatcher = dispatcher,
                onError = { error -> errorReporter.record("config", error) },
            ),
            param = ParamEditorController(
                dispatcher = dispatcher,
                onError = { error -> errorReporter.record("param", error) },
            ),
            packetLog = packetLog,
            profileRepository = profileRepository,
            events = DeviceEventTimeline(eventStore),
        )
    }

    /**
     * 前台自动化：只在连接/OTA 成功的 UI 回调里调用。
     * App 没有后台服务或后台唤起路径，因此这里的 isForeground 恒为 true；
     * 若将来加入后台执行，必须改为真实生命周期状态。
     */
    fun triggerAutomation(trigger: AutomationTrigger, session: DeviceSession) {
        val healthCheck = DeviceHealthCheckController(
            dispatcher = session.dispatcher,
            isLinkAlive = { session.connection.connected.value },
            otaHistoryProvider = {
                session.profileRepository.get(session.connection.deviceId)?.otaHistory.orEmpty()
            },
            knownUptimeSeconds = { session.detail.state.value.uptimeSeconds ?: 0L },
        )
        val runner = AutomationRuleRunner(
            rules = automationRepository,
            presets = presetRepository,
            presetApplier = ConfigPresetApplier(session.dispatcher),
            events = session.events,
            isForeground = { true },
            eventSummary = { rule ->
                strings.t(
                    StringKey.AUTOMATION_EVENT_SUMMARY,
                    "action" to strings.t(rule.action.stringKey()),
                )
            },
            onDiagnosticReport = { lastDiagnosticReport = it },
        )
        scope.launchSafely(errorReporter, "automation") {
            runner.run(
                trigger = trigger,
                context = AutomationContext(
                    deviceId = session.connection.deviceId,
                    dispatcher = session.dispatcher,
                    detail = session.detail,
                    events = session.events,
                    profileProvider = { session.profileRepository.get(session.connection.deviceId) },
                    healthCheckFactory = { healthCheck },
                    isLinkAlive = { session.connection.connected.value },
                    platformDisplayName = platformDisplayName(),
                ),
            )
        }
    }

    fun handleBleAction(action: BleGuidanceAction) {
        when (action) {
            BleGuidanceAction.REQUEST_PERMISSION -> permissionController.requestPermission()
            BleGuidanceAction.OPEN_SETTINGS -> permissionController.openAppSettings()
            BleGuidanceAction.ENABLE_BLUETOOTH -> permissionController.requestEnableBluetooth()
            BleGuidanceAction.RETRY -> {
                bleClient.refreshAdapterState()
                permissionController.refresh()
            }

            BleGuidanceAction.NONE -> Unit
        }
    }

    fun startReconnect(session: DeviceSession) {
        val existing = reconnect
        if (existing != null) {
            val phase = existing.controller.state.value.phase
            // 进行中或等待中的重连不允许重复发起；已耗尽/已取消才允许手动重开。
            if (phase == ReconnectPhase.WAITING ||
                phase == ReconnectPhase.CONNECTING ||
                phase == ReconnectPhase.PAUSED
            ) {
                return
            }
            existing.controller.cancel()
            reconnect = null
        }

        val device = session.device
        val packetLog = session.packetLog
        val controller = AutoReconnectController(
            policy = ReconnectPolicy(
                maxDelayMillis = settings.reconnectMaxDelaySeconds * 1_000L,
                maxAttempts = settings.reconnectMaxAttempts,
            ),
            scope = scope,
            isBluetoothOn = { bleClient.adapterState.value == BleAdapterState.POWERED_ON },
            attemptConnect = { runCatching { bleClient.connect(device.deviceId) }.getOrNull() },
            onConnected = { connection ->
                scope.launchSafely(errorReporter, "reconnect-swap") {
                    runCatching { session.dispatcher.close() }
                }
                reconnect = null
                val refreshed = createSession(connection, device, packetLog)
                refreshed.events.record(
                    deviceId = device.deviceId,
                    type = DeviceEventType.CONNECTION,
                    summary = strings.t(StringKey.TIMELINE_EVENT_RECONNECTED),
                    result = DeviceEventResult.SUCCESS,
                )
                sessions = sessions + (device.deviceId to refreshed)
                deviceManager.replaceConnection(device.deviceId, connection)
                route = Route.Detail(device.deviceId)
                triggerAutomation(AutomationTrigger.CONNECTED, refreshed)
            },
            onError = { error -> errorReporter.record("reconnect", error) },
        )
        reconnect = ReconnectSession(device, packetLog, controller)
        controller.start()
    }

    // 自动重连耗尽：仅在 App 存活期间提醒；权限缺失时降级为应用内横幅。
    val reconnectSnapshot = reconnect
    val reconnectPhase = reconnectSnapshot?.controller?.state?.collectAsState()?.value?.phase
    LaunchedEffect(reconnectPhase, reconnectSnapshot) {
        if (reconnectPhase != ReconnectPhase.EXHAUSTED || reconnectSnapshot == null) {
            return@LaunchedEffect
        }
        if (!NotificationPolicy.shouldNotifyReconnectExhausted(settings)) return@LaunchedEffect
        val event = NotificationEvent(
            type = NotificationEventType.RECONNECT_EXHAUSTED,
            title = strings.t(StringKey.NOTIFICATION_RECONNECT_EXHAUSTED_TITLE),
            body = strings.t(
                StringKey.NOTIFICATION_RECONNECT_EXHAUSTED_BODY,
                "device" to (reconnectSnapshot.device.name ?: reconnectSnapshot.device.deviceId),
                "attempts" to settings.reconnectMaxAttempts.toString(),
            ),
            target = NotificationTarget(NotificationPage.DETAIL, reconnectSnapshot.device.deviceId),
            deviceId = reconnectSnapshot.device.deviceId,
        )
        val decision = notificationCenter.dispatch(
            event = event,
            enabled = true,
            canNotify = notificationPresenter.canNotify,
        ) { title, body, target -> notificationPresenter.show(title, body, target) }
        if (decision == NotificationDecision.FALLBACK_BANNER) {
            notificationBanner = event
        }
    }

    // 蓝牙开关变化时暂停/恢复自动重连。
    LaunchedEffect(adapterState) {
        reconnect?.controller?.onBluetoothStateChanged(
            available = adapterState == BleAdapterState.POWERED_ON,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            reconnect?.controller?.cancel()
            scanController.stopScan()
            bleClient.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    when (val current = route) {
        Route.Scan -> ScanScreen(
            state = scanController.state.collectAsState().value,
            availability = availability,
            connectingDeviceId = connectingDeviceId,
            errorMessage = errorMessage,
            deviceCount = deviceState.occupied,
            profileLookup = { id -> profileRepository.get(id) },
            onStart = {
                if (!availability.allowsConnection()) {
                    errorMessage = availability.guidance()?.message
                        ?: UiMessage(StringKey.BLUETOOTH_UNAVAILABLE)
                    return@ScanScreen
                }
                errorMessage = null
                scanController.startScan()
            },
            onStop = { scanController.stopScan() },
            onSortChange = { scanController.setSortMode(it) },
            onOpenDevices = { route = Route.Devices },
            onSelect = select@{ device ->
                if (!availability.allowsConnection()) {
                    errorMessage = availability.guidance()?.message
                        ?: UiMessage(StringKey.BLUETOOTH_UNAVAILABLE)
                    return@select
                }
                scope.launchSafely(errorReporter, "connect") {
                    connectingDeviceId = device.deviceId
                    errorMessage = null
                    try {
                        when (val result = deviceManager.connect(device)) {
                            is MultiDeviceConnectResult.Connected -> {
                                scanController.stopScan()
                                val newSession = createSession(
                                    result.device.connection,
                                    device,
                                    InMemoryPacketLog(capacity = settings.logCapacity),
                                )
                                newSession.events.record(
                                    deviceId = device.deviceId,
                                    type = DeviceEventType.CONNECTION,
                                    summary = strings.t(StringKey.TIMELINE_EVENT_CONNECTED),
                                    result = DeviceEventResult.SUCCESS,
                                )
                                sessions = sessions + (device.deviceId to newSession)
                                deviceManager.select(device.deviceId)
                                route = Route.Detail(device.deviceId)
                                triggerAutomation(AutomationTrigger.CONNECTED, newSession)
                            }

                            MultiDeviceConnectResult.AlreadyConnected -> {
                                deviceManager.select(device.deviceId)
                                route = Route.Detail(device.deviceId)
                            }

                            is MultiDeviceConnectResult.LimitReached -> {
                                errorMessage = UiMessage(
                                    StringKey.DEVICES_LIMIT_REACHED,
                                    mapOf("capacity" to result.capacity.toString()),
                                )
                            }

                            is MultiDeviceConnectResult.Failed -> {
                                errorMessage = UiMessage(
                                    StringKey.ERROR_PREFIX,
                                    mapOf("detail" to result.message),
                                )
                            }

                            MultiDeviceConnectResult.InProgress -> Unit
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } finally {
                        connectingDeviceId = null
                    }
                }
            },
            onBleAction = { handleBleAction(it) },
            onOpenSettings = { route = Route.Settings },
        )

        Route.Devices -> DevicesScreen(
            state = deviceState,
            onSwitch = { id ->
                deviceManager.select(id)
                route = Route.Detail(id)
            },
            onDisconnect = { id ->
                scope.launchSafely(errorReporter, "disconnect-device") {
                    sessions[id]?.let { session ->
                        session.events.record(
                            deviceId = id,
                            type = DeviceEventType.DISCONNECTION,
                            summary = strings.t(StringKey.TIMELINE_EVENT_DISCONNECTED),
                            result = DeviceEventResult.INFO,
                        )
                        runCatching { session.detail.disconnect() }
                        runCatching { session.dispatcher.close() }
                    }
                    sessions = sessions - id
                    deviceManager.disconnect(id)
                    val currentRoute = route
                    if (currentRoute is Route.Detail && currentRoute.deviceId == id) {
                        route = Route.Scan
                    }
                }
            },
            onDisconnectAll = {
                scope.launchSafely(errorReporter, "disconnect-all") {
                    sessions.values.forEach { session ->
                        session.events.record(
                            deviceId = session.device.deviceId,
                            type = DeviceEventType.DISCONNECTION,
                            summary = strings.t(StringKey.TIMELINE_EVENT_DISCONNECTED),
                            result = DeviceEventResult.INFO,
                        )
                        runCatching { session.detail.disconnect() }
                        runCatching { session.dispatcher.close() }
                    }
                    sessions = emptyMap()
                    deviceManager.disconnectAll()
                    route = Route.Scan
                }
            },
            onOpenBatch = { route = Route.Batch },
            onOpenQueue = { route = Route.OtaQueue },
            onBack = { route = Route.Scan },
        )

        Route.Settings -> SettingsScreen(
            language = preferences.language,
            theme = preferences.theme,
            settings = settings,
            notificationPresenter = notificationPresenter,
            onLanguageChange = onLanguageChange,
            onThemeChange = onThemeChange,
            onSettingsChange = onSettingsChange,
            onBack = { route = Route.Scan },
            onAbout = { route = Route.About },
            onHelp = { route = Route.Help },
            onPrivacy = { route = Route.Privacy },
            onOnboardingReview = { route = Route.Onboarding(review = true) },
            onOpenAutomation = { route = Route.Automation },
            onOpenStats = { route = Route.Stats },
            onOpenBackup = { route = Route.Backup },
        )

        Route.Automation -> AutomationScreen(
            rules = automationRepository,
            presets = presetRepository,
            lastDiagnosticReport = lastDiagnosticReport,
            onBack = { route = Route.Settings },
        )

        Route.Stats -> UsageStatsScreen(
            profileRepository = profileRepository,
            onBack = { route = Route.Settings },
        )

        Route.Backup -> BackupScreen(
            store = onboardingStore,
            presets = presetRepository,
            rules = automationRepository,
            onReloadPersisted = onReloadPersisted,
            onBack = { route = Route.Settings },
        )

        Route.Batch -> BatchScreen(
            devices = deviceState.devices.map { BatchDeviceOption(it.deviceId, it.displayName) },
            presets = presetRepository.list(),
            controller = batchController,
            onBack = { route = Route.Devices },
            execute = execute@{ kind, preset, target, step ->
                val session = sessions[target.deviceId]
                    ?: return@execute BatchDeviceOutcome(
                        status = BatchItemStatus.REMOVED,
                        reason = "batch.disconnected",
                    )
                if (!session.connection.connected.value) {
                    return@execute BatchDeviceOutcome(
                        status = BatchItemStatus.REMOVED,
                        reason = "batch.disconnected",
                    )
                }
                when (kind) {
                    BatchOperationKind.HEALTH_CHECK -> {
                        step("batch.health")
                        val healthCheck = DeviceHealthCheckController(
                            dispatcher = session.dispatcher,
                            isLinkAlive = { session.connection.connected.value },
                            otaHistoryProvider = {
                                session.profileRepository
                                    .get(session.connection.deviceId)
                                    ?.otaHistory
                                    .orEmpty()
                            },
                            knownUptimeSeconds = {
                                session.detail.state.value.uptimeSeconds ?: 0L
                            },
                        )
                        val result = healthCheck.run()
                            ?: return@execute BatchDeviceOutcome(
                                status = BatchItemStatus.FAILED,
                                reason = "batch.execution_error",
                            )
                        if (result.abortedByLinkLoss) {
                            BatchDeviceOutcome(
                                status = BatchItemStatus.REMOVED,
                                reason = "batch.disconnected",
                            )
                        } else {
                            BatchDeviceOutcome(
                                status = BatchItemStatus.SUCCEEDED,
                                step = "batch.health",
                                detail = "ok=${result.okCount} abnormal=${result.abnormalCount} " +
                                    "skipped=${result.skippedCount} failed=${result.failedCount}",
                            )
                        }
                    }

                    BatchOperationKind.APPLY_PRESET -> {
                        val chosen = preset
                            ?: return@execute BatchDeviceOutcome(
                                status = BatchItemStatus.FAILED,
                                reason = "batch.no_preset",
                            )
                        step("batch.preset")
                        val detail = session.detail.state.value
                        session.config.updateTarget(
                            DeviceConfigTarget(
                                firmwareVersion = detail.firmware,
                                model = detail.deviceInfo?.model,
                                vendor = detail.deviceInfo?.vendor,
                            ),
                        )
                        session.config.load()
                        val current = session.config.state.value.visibleFields
                            .filter { it.loaded && it.editable }
                            .associate { it.spec.key to it.text }
                        val diffs = ConfigPresetDiffer.diff(
                            chosen,
                            session.config.state.value.target,
                            current,
                        )
                        val report = ConfigPresetApplier(session.dispatcher).apply(diffs)
                        val detailText = "written=${report.writtenCount} " +
                            "already=${report.alreadyCount} skipped=${report.skippedCount} " +
                            "failed=${report.failedCount}"
                        if (report.failedCount > 0) {
                            BatchDeviceOutcome(
                                status = BatchItemStatus.FAILED,
                                step = "batch.preset",
                                detail = detailText,
                            )
                        } else {
                            BatchDeviceOutcome(
                                status = BatchItemStatus.SUCCEEDED,
                                step = "batch.preset",
                                detail = detailText,
                            )
                        }
                    }
                }
            },
        )

        Route.OtaQueue -> OtaQueueScreen(
            devices = deviceState.devices.map { BatchDeviceOption(it.deviceId, it.displayName) },
            firmwareRecords = firmwareLibrary.list(FirmwareLibrarySort.RECENT),
            controller = queueController,
            onBack = { route = Route.Devices },
        )

        Route.About -> AboutScreen(onBack = { route = Route.Settings })
        Route.Help -> HelpScreen(onBack = { route = Route.Settings })
        Route.Privacy -> PrivacyScreen(onBack = { route = Route.Settings })

        is Route.Onboarding -> OnboardingScreen(
            reviewMode = current.review,
            store = onboardingStore,
            availability = availability,
            onBleAction = { handleBleAction(it) },
            onFinished = {
                route = if (current.review) Route.Settings else Route.Scan
            },
        )

        is Route.Detail -> {
            val session = sessions[current.deviceId]
            if (session == null) {
                LaunchedEffect(current.deviceId) { route = Route.Devices }
            } else {
                val connected by session.connection.connected.collectAsState()
                val disconnectInfo = if (!connected) {
                    classifyDisconnect(
                        DisconnectSignals(
                            userRequested = session.userDisconnected,
                            peerTerminated = !session.userDisconnected,
                            adapterState = adapterState,
                            permissionState = permissionState,
                        ),
                    )
                } else {
                    null
                }
                DetailScreen(
                    session = session,
                    settings = settings,
                    metrics = metrics,
                    presets = presetRepository,
                    notificationPresenter = notificationPresenter,
                    notificationCenter = notificationCenter,
                    onNotificationFallback = { notificationBanner = it },
                    connectedCount = deviceState.occupied,
                    disconnectInfo = disconnectInfo,
                    reconnectState = reconnect
                        ?.takeIf { it.device.deviceId == session.device.deviceId }
                        ?.controller
                        ?.state
                        ?.collectAsState()
                        ?.value,
                    onConnectionLost = {
                        if (!session.userDisconnected) startReconnect(session)
                    },
                    onRetryReconnect = { startReconnect(session) },
                    onCancelReconnect = {
                        reconnect?.controller?.cancel()
                        reconnect = null
                    },
                    onBleAction = { handleBleAction(it) },
                    onOpenDevices = { route = Route.Devices },
                    onBack = {
                        session.userDisconnected = true
                        reconnect?.controller?.cancel()
                        reconnect = null
                        session.events.record(
                            deviceId = current.deviceId,
                            type = DeviceEventType.DISCONNECTION,
                            summary = strings.t(StringKey.TIMELINE_EVENT_DISCONNECTED),
                            result = DeviceEventResult.INFO,
                        )
                        scope.launchSafely(errorReporter, "disconnect-back") {
                            session.detail.disconnect()
                            session.dispatcher.close()
                            sessions = sessions - current.deviceId
                            deviceManager.disconnect(current.deviceId)
                            route = if (deviceManager.state.value.occupied > 1) {
                                Route.Devices
                            } else {
                                Route.Scan
                            }
                        }
                    },
                    onOpenOta = { route = Route.Ota(current.deviceId) },
                    onOpenLogs = { route = Route.Logs(current.deviceId) },
                    onOpenDiagnostics = { route = Route.Diagnostic(current.deviceId) },
                    writeLocked = current.deviceId in lockedIds,
                )
            }
        }

        is Route.Ota -> {
            val session = sessions[current.deviceId]
            if (session == null) {
                LaunchedEffect(current.deviceId) { route = Route.Devices }
            } else {
                OtaScreen(
                    session = session,
                    library = firmwareLibrary,
                    settings = settings,
                    notificationPresenter = notificationPresenter,
                    notificationCenter = notificationCenter,
                    onNotificationFallback = { notificationBanner = it },
                    onBack = { route = Route.Detail(current.deviceId) },
                    onOtaSucceeded = { triggerAutomation(AutomationTrigger.OTA_SUCCESS, session) },
                    writeLocked = current.deviceId in lockedIds,
                )
            }
        }

        is Route.Logs -> {
            val session = sessions[current.deviceId]
            if (session == null) {
                LaunchedEffect(current.deviceId) { route = Route.Devices }
            } else {
                PacketLogScreen(
                    packetLog = session.packetLog,
                    initialOptions = settings.exportOptions(),
                    onBack = { route = Route.Detail(current.deviceId) },
                )
            }
        }

        is Route.Diagnostic -> {
            val session = sessions[current.deviceId]
            if (session == null) {
                LaunchedEffect(current.deviceId) { route = Route.Devices }
            } else {
                DiagnosticScreen(
                    session = session,
                    metrics = metrics,
                    onBack = { route = Route.Detail(current.deviceId) },
                )
            }
        }
    }
        notificationBanner?.let { event ->
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(DesignTokens.Spacing.lg),
            ) {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                ) {
                    Text(
                        strings.t(StringKey.NOTIFICATION_BANNER_TITLE),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(event.title, style = MaterialTheme.typography.titleSmall)
                    Text(event.body, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                        TextButton(onClick = { notificationBanner = null }) {
                            Text(strings.t(StringKey.CLOSE))
                        }
                        val target = notificationRoute(event.target)
                        if (target != null) {
                            TextButton(
                                onClick = {
                                    route = target
                                    notificationBanner = null
                                },
                            ) {
                                Text(strings.t(StringKey.REFRESH))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanScreen(
    state: ScanUiState,
    availability: BleAvailability,
    connectingDeviceId: String?,
    errorMessage: UiMessage?,
    deviceCount: Int,
    profileLookup: (String) -> DeviceProfile?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSortChange: (ScanSortMode) -> Unit,
    onOpenDevices: () -> Unit,
    onSelect: (BleDevice) -> Unit,
    onBleAction: (BleGuidanceAction) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds()) }
    val canScan = availability.allowsConnection()
    val guidance = availability.guidance()
    val emptyReason = classifyScanEmpty(
        scanning = state.scanning,
        deviceCount = state.devices.size,
        availability = availability,
        error = state.error?.key?.name,
        timedOut = state.timedOut,
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.t(StringKey.SCAN_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenDevices) {
                Text(strings.t(StringKey.DEVICES_ENTRY, "count" to deviceCount.toString()))
            }
            TextButton(onClick = onOpenSettings) { Text(strings.t(StringKey.SETTINGS)) }
        }
        Text(
            strings.t(StringKey.SCAN_HINT),
            style = MaterialTheme.typography.bodySmall,
        )

        guidance?.let { guide ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                ) {
                    Text(strings.render(guide.title), style = MaterialTheme.typography.titleSmall)
                    Text(strings.render(guide.message), style = MaterialTheme.typography.bodySmall)
                    guide.actionLabel?.let { label ->
                        Button(onClick = { onBleAction(guide.action) }) {
                            Text(strings.render(label))
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Button(
                onClick = onStart,
                enabled = canScan && !state.scanning,
                modifier = Modifier.semantics { contentDescription = strings.t(StringKey.START_SCAN) },
            ) {
                Text(strings.t(StringKey.START_SCAN))
            }
            OutlinedButton(
                onClick = onStop,
                enabled = state.scanning,
                modifier = Modifier.semantics { contentDescription = strings.t(StringKey.STOP_SCAN) },
            ) {
                Text(strings.t(StringKey.STOP_SCAN))
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings.t(StringKey.SCAN_SORT_LABEL),
                style = MaterialTheme.typography.bodySmall,
            )
            FilterChip(
                selected = state.sortMode == ScanSortMode.SIGNAL,
                onClick = { onSortChange(ScanSortMode.SIGNAL) },
                label = { Text(strings.t(StringKey.SCAN_SORT_SIGNAL)) },
            )
            FilterChip(
                selected = state.sortMode == ScanSortMode.NAME,
                onClick = { onSortChange(ScanSortMode.NAME) },
                label = { Text(strings.t(StringKey.SCAN_SORT_NAME)) },
            )
            FilterChip(
                selected = state.sortMode == ScanSortMode.LAST_CONNECTED,
                onClick = { onSortChange(ScanSortMode.LAST_CONNECTED) },
                label = { Text(strings.t(StringKey.SCAN_SORT_LAST_CONNECTED)) },
            )
        }

        if (state.scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = strings.t(StringKey.SCAN_EMPTY_SCANNING)
                },
            )
        }

        errorMessage?.let { message ->
            Text(
                strings.render(message),
                color = status.error,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = strings.render(message)
                },
            )
        }

        if (state.devices.isEmpty() && errorMessage == null) {
            val emptyText = emptyReasonText(emptyReason, strings)
            if (emptyText != null) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (emptyReason == ScanEmptyReason.SCAN_TIMEOUT) {
                        status.warning
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            state.error?.let { message ->
                Text(
                    strings.render(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            items(state.devices, key = { it.deviceId }) { device ->
                val band = RssiBand.of(device.rssi)
                val bandLabel = rssiBandLabel(band, strings)
                val profile = profileLookup(device.deviceId)
                val itemDescription = buildString {
                    append(device.name ?: strings.t(StringKey.UNNAMED_DEVICE))
                    append(", ")
                    append(bandLabel)
                    if (profile != null) {
                        append(", ")
                        append(strings.t(StringKey.SCAN_PROFILE_MARK))
                    }
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { contentDescription = itemDescription }
                        .clickable(enabled = canScan) { onSelect(device) },
                ) {
                    Column(modifier = Modifier.padding(DesignTokens.Spacing.md)) {
                        Text(
                            device.name ?: strings.t(StringKey.UNNAMED_DEVICE),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(bandLabel, style = MaterialTheme.typography.bodySmall)
                        if (profile != null) {
                            Text(
                                strings.t(StringKey.SCAN_PROFILE_MARK) + " · " +
                                    strings.t(
                                        StringKey.SCAN_LAST_CONNECTED,
                                        "time" to format.absoluteTime(
                                            profile.lastConnectedAtEpochMillis,
                                            zoneOffsetMinutes,
                                        ),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                strings.t(StringKey.SCAN_NEVER_CONNECTED),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        connectingDeviceId?.let {
            Text(
                strings.t(StringKey.SCAN_CONNECTING, "device" to it),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun rssiBandLabel(band: RssiBand, strings: StringResolver): String = when (band) {
    RssiBand.STRONG -> strings.t(StringKey.SCAN_RSSI_STRONG)
    RssiBand.MEDIUM -> strings.t(StringKey.SCAN_RSSI_MEDIUM)
    RssiBand.WEAK -> strings.t(StringKey.SCAN_RSSI_WEAK)
}

private fun emptyReasonText(reason: ScanEmptyReason, strings: StringResolver): String? = when (reason) {
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

@Composable
private fun DevicesScreen(
    state: MultiDeviceState,
    onSwitch: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onOpenBatch: () -> Unit,
    onOpenQueue: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.t(StringKey.DEVICES_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text(strings.t(StringKey.BACK)) }
        }

        Text(strings.t(StringKey.DEVICES_LIMIT_POLICY), style = MaterialTheme.typography.bodySmall)

        if (state.devices.isEmpty()) {
            Text(strings.t(StringKey.DEVICES_EMPTY), style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            items(state.devices, key = { it.deviceId }) { item ->
                DeviceRow(
                    item = item,
                    capacity = state.capacity,
                    active = item.deviceId == state.activeDeviceId,
                    format = format,
                    zoneOffsetMinutes = zoneOffsetMinutes,
                    onSwitch = { onSwitch(item.deviceId) },
                    onDisconnect = { onDisconnect(item.deviceId) },
                )
            }
        }

        if (state.devices.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            ) {
                Button(onClick = onOpenBatch, modifier = Modifier.weight(1f)) {
                    Text(strings.t(StringKey.BATCH_TITLE))
                }
                OutlinedButton(onClick = onOpenQueue, modifier = Modifier.weight(1f)) {
                    Text(strings.t(StringKey.QUEUE_TITLE))
                }
            }
            OutlinedButton(onClick = onDisconnectAll, modifier = Modifier.fillMaxWidth()) {
                Text(strings.t(StringKey.DEVICES_DISCONNECT_ALL))
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(strings.t(StringKey.DEVICES_BACK_TO_SCAN))
        }
    }
}

@Composable
private fun DeviceRow(
    item: ManagedDeviceInfo,
    capacity: Int,
    active: Boolean,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
    onSwitch: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val strings = LocalStrings.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (active) {
                    Text(
                        strings.t(StringKey.DEVICES_ACTIVE),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(item.deviceId, style = MaterialTheme.typography.bodySmall)
            Text(
                strings.t(
                    StringKey.DEVICES_SLOT,
                    "slot" to item.slot.toString(),
                    "capacity" to capacity.toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                strings.t(
                    StringKey.DEVICES_CONNECTED_AT,
                    "time" to format.absoluteTime(item.connectedAtMillis, zoneOffsetMinutes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                OutlinedButton(onClick = onSwitch, enabled = !active) {
                    Text(strings.t(StringKey.DEVICES_SWITCH))
                }
                TextButton(onClick = onDisconnect) {
                    Text(strings.t(StringKey.DEVICES_DISCONNECT))
                }
            }
        }
    }
}

@Composable
private fun DisconnectBanner(
    info: DisconnectInfo,
    reconnectState: ReconnectState?,
    onRetryReconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
    onBleAction: (BleGuidanceAction) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val reconnectRunning = reconnectState?.phase == ReconnectPhase.WAITING ||
        reconnectState?.phase == ReconnectPhase.CONNECTING

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(
                strings.render(info.title),
                style = MaterialTheme.typography.titleSmall,
                color = status.error,
            )
            Text(strings.render(info.message), style = MaterialTheme.typography.bodySmall)

            reconnectState?.let { rs ->
                val hint = when (rs.phase) {
                    ReconnectPhase.WAITING -> strings.t(
                        StringKey.RECONNECT_WAITING,
                        "seconds" to ((rs.nextDelayMillis ?: 0L) / 1000).toString(),
                        "attempt" to (rs.attempt + 1).toString(),
                    )

                    ReconnectPhase.CONNECTING -> strings.t(
                        StringKey.RECONNECT_CONNECTING,
                        "attempt" to (rs.attempt + 1).toString(),
                    )

                    ReconnectPhase.PAUSED ->
                        if (rs.pausedByBluetoothOff) {
                            strings.t(StringKey.RECONNECT_PAUSED_BLUETOOTH_OFF)
                        } else {
                            strings.t(StringKey.RECONNECT_PAUSED)
                        }

                    ReconnectPhase.EXHAUSTED -> strings.t(
                        StringKey.RECONNECT_EXHAUSTED,
                        "attempt" to rs.attempt.toString(),
                    )

                    ReconnectPhase.CANCELLED -> strings.t(StringKey.RECONNECT_CANCELLED)
                    ReconnectPhase.SUCCEEDED -> strings.t(StringKey.RECONNECT_SUCCEEDED)
                    ReconnectPhase.IDLE -> null
                }
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                if (reconnectRunning) {
                    OutlinedButton(onClick = onCancelReconnect) {
                        Text(strings.t(StringKey.CANCEL_RECONNECT))
                    }
                } else if (info.autoReconnectAllowed && info.action == DisconnectAction.RECONNECT) {
                    Button(onClick = onRetryReconnect) {
                        Text(strings.t(StringKey.RECONNECT_NOW))
                    }
                }
                when (info.action) {
                    DisconnectAction.OPEN_SETTINGS -> {
                        Button(onClick = { onBleAction(BleGuidanceAction.OPEN_SETTINGS) }) {
                            Text(strings.t(StringKey.ACTION_OPEN_SETTINGS))
                        }
                        OutlinedButton(onClick = onBack) {
                            Text(strings.t(StringKey.ACTION_RESCAN))
                        }
                    }

                    DisconnectAction.ENABLE_BLUETOOTH -> Button(
                        onClick = { onBleAction(BleGuidanceAction.ENABLE_BLUETOOTH) },
                    ) { Text(strings.t(StringKey.ACTION_ENABLE_BLUETOOTH)) }

                    DisconnectAction.RESCAN -> OutlinedButton(onClick = onBack) {
                        Text(strings.t(StringKey.ACTION_RESCAN))
                    }

                    DisconnectAction.RECONNECT,
                    DisconnectAction.NONE,
                    -> Unit
                }
            }
        }
    }
}

private const val TIMELINE_VISIBLE_LIMIT = 20
private const val LIBRARY_VISIBLE_LIMIT = 8

@Composable
private fun DeviceDashboardCard(
    detail: DeviceDetailState,
    series: DeviceMetricSeries,
    rssi: Int?,
    rssiSource: RssiSource,
    degradedReason: RssiDegradedReason?,
    legacyMetricsIgnored: Boolean,
    observedAtMillis: Long,
    strings: StringResolver,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
    accent: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(strings.t(StringKey.DASH_TITLE), style = MaterialTheme.typography.titleMedium)
            DashboardRow(
                strings.t(StringKey.DASH_BATTERY),
                detail.batteryPercent?.let { "$it%" } ?: strings.t(StringKey.DASH_NO_DATA),
            )
            DashboardRow(
                strings.t(StringKey.DASH_CHARGING),
                detail.charging?.let {
                    strings.t(if (it) StringKey.HEALTH_CHARGING else StringKey.HEALTH_NOT_CHARGING)
                } ?: strings.t(StringKey.DASH_NO_DATA),
            )
            DashboardRow(
                strings.t(StringKey.DASH_FIRMWARE),
                detail.firmwareVersion ?: strings.t(StringKey.DASH_NO_DATA),
            )
            DashboardRow(
                strings.t(StringKey.DASH_UPTIME),
                detail.uptimeSeconds?.let { formatUptime(it, strings) }
                    ?: strings.t(StringKey.DASH_NO_DATA),
            )
            DashboardRow(
                strings.t(StringKey.DASH_SIGNAL),
                rssi?.let { value ->
                    val band = strings.t(
                        when (RssiBand.of(value)) {
                            RssiBand.STRONG -> StringKey.SCAN_RSSI_STRONG
                            RssiBand.MEDIUM -> StringKey.SCAN_RSSI_MEDIUM
                            RssiBand.WEAK -> StringKey.SCAN_RSSI_WEAK
                        },
                    )
                    "$band ($value dBm)"
                } ?: strings.t(StringKey.DASH_NO_DATA),
            )
            if (rssi != null) {
                Text(
                    strings.t(
                        StringKey.DASH_SIGNAL_SOURCE,
                        "source" to strings.t(
                            if (rssiSource == RssiSource.CONNECTED_LIVE) {
                                StringKey.DASH_SOURCE_LIVE
                            } else {
                                StringKey.DASH_SOURCE_CONNECT
                            },
                        ),
                        "time" to formatAbsoluteTime(observedAtMillis, zoneOffsetMinutes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            degradedReason?.let { reason ->
                Text(
                    strings.t(
                        StringKey.DASH_RSSI_DEGRADED,
                        "reason" to strings.t(
                            if (reason == RssiDegradedReason.READ_FAILED) {
                                StringKey.DASH_RSSI_READ_ERROR
                            } else {
                                StringKey.DASH_RSSI_DISCONNECTED
                            },
                        ),
                        "source" to strings.t(StringKey.DASH_SOURCE_CONNECT),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            Text(
                strings.t(StringKey.DASH_SAMPLE_NOTE),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
            if (legacyMetricsIgnored) {
                Text(
                    strings.t(StringKey.DASH_LEGACY_METRICS),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
            if (series.canDrawBattery || series.canDrawRssi) {
                Text(
                    strings.t(
                        StringKey.DASH_TREND_TITLE,
                        "count" to series.samples.size.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (series.canDrawBattery) {
                    TrendLine(
                        values = series.batteryPoints.map { it.second.toFloat() },
                        minValue = 0f,
                        maxValue = 100f,
                        color = accent,
                    )
                }
                if (series.canDrawRssi) {
                    TrendLine(
                        values = series.rssiPoints.map { it.second.toFloat() },
                        minValue = -100f,
                        maxValue = -30f,
                        color = accent,
                    )
                }
            } else {
                Text(
                    strings.t(StringKey.DASH_TREND_INSUFFICIENT),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
    }
}

@Composable
private fun DashboardRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TrendLine(
    values: List<Float>,
    minValue: Float,
    maxValue: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
        if (values.size < 2) return@Canvas
        val range = (maxValue - minValue).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1)
        val points = values.mapIndexed { index, value ->
            Offset(
                x = index * stepX,
                y = size.height - ((value - minValue) / range) * size.height,
            )
        }
        for (index in 1 until points.size) {
            drawLine(
                color = color,
                start = points[index - 1],
                end = points[index],
                strokeWidth = 2f,
            )
        }
    }
}

private fun healthReportText(
    result: HealthCheckResult,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
    strings: StringResolver,
): String = HealthCheckReportFormatter.format(
    result = result,
    generatedAtText = formatAbsoluteTime(result.completedAtMillis, zoneOffsetMinutes),
    appVersion = AppBuildInfo.VERSION_NAME,
    platform = platformDisplayName(),
    resolve = { strings.render(it) },
)

private fun formatUptime(seconds: Long, strings: StringResolver): String {
    val safe = seconds.coerceAtLeast(0)
    return if (safe < 3_600) {
        strings.t(
            StringKey.DASH_UPTIME_MS,
            "minutes" to (safe / 60).toString(),
            "seconds" to (safe % 60).toString(),
        )
    } else {
        strings.t(
            StringKey.DASH_UPTIME_HM,
            "hours" to (safe / 3_600).toString(),
            "minutes" to ((safe % 3_600) / 60).toString(),
        )
    }
}

private fun DiagnosticReportSection.reportLabelKey(): StringKey = when (this) {
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

private fun DiagnosticReportFormat.formatKey(): StringKey = when (this) {
    DiagnosticReportFormat.MARKDOWN -> StringKey.DIAG_REPORT_FORMAT_MARKDOWN
    DiagnosticReportFormat.TEXT -> StringKey.DIAG_REPORT_FORMAT_TEXT
}

private fun DeviceEventType.timelineKey(): StringKey = when (this) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailScreen(
    session: DeviceSession,
    settings: AppSettings,
    metrics: DeviceMetricRepository,
    presets: ConfigPresetRepository,
    notificationPresenter: NotificationPresenter,
    notificationCenter: NotificationCenter,
    onNotificationFallback: (NotificationEvent) -> Unit,
    connectedCount: Int,
    disconnectInfo: DisconnectInfo?,
    reconnectState: ReconnectState?,
    onConnectionLost: () -> Unit,
    onRetryReconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
    onBleAction: (BleGuidanceAction) -> Unit,
    onOpenDevices: () -> Unit,
    onBack: () -> Unit,
    onOpenOta: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    writeLocked: Boolean,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val controller = session.detail
    val state by controller.state.collectAsState()
    val configState by session.config.state.collectAsState()
    val paramState by session.param.state.collectAsState()
    val connected by session.connection.connected.collectAsState()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    val clipboard = LocalClipboardManager.current
    val profile = session.profileRepository.get(state.deviceId)
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds()) }
    var authMode by remember { mutableStateOf(AuthMode.TOKEN) }
    var credential by remember { mutableStateOf("") }
    var timelineFilter by remember { mutableStateOf<DeviceEventType?>(null) }
    var disconnectNotified by remember(session) { mutableStateOf(false) }
    val lowBatteryTracker = remember(session, settings.lowBatteryThreshold) {
        LowBatteryAlertTracker(settings.lowBatteryThreshold)
    }
    // 体检控制器绑定当前会话：链路断开时立即停止；不读取上次会话的缓存。
    val healthCheck = remember(session) {
        DeviceHealthCheckController(
            dispatcher = session.dispatcher,
            isLinkAlive = { session.connection.connected.value },
            otaHistoryProvider = {
                session.profileRepository.get(session.connection.deviceId)?.otaHistory.orEmpty()
            },
            knownUptimeSeconds = { session.detail.state.value.uptimeSeconds ?: 0L },
        )
    }
    val healthState by healthCheck.state.collectAsState()
    var metricsVersion by remember { mutableStateOf(0) }
    val metricLoad = remember(metricsVersion, state.deviceId) { metrics.seriesWithStatus(state.deviceId) }
    val metricSeries = metricLoad.series
    var healthMessage by remember { mutableStateOf<String?>(null) }
    var healthMessageSuccess by remember { mutableStateOf(false) }
    var healthJob by remember { mutableStateOf<Job?>(null) }
    val fileSaver = rememberFileSaver()
    val connectObservedAt = remember(session) { Clock.System.now().toEpochMilliseconds() }
    val rssiController = remember(session) {
        RssiSubscriptionController(readRssi = { session.connection.readRssi() })
    }
    val rssiState by rssiController.state.collectAsState()

    LaunchedEffect(healthState.result) {
        healthState.result?.let { result ->
            session.lastHealthReport = healthReportText(result, format, zoneOffsetMinutes, strings)
        }
    }

    // 连接期间周期读取实时 RSSI；断开自动停止，开关关闭时回到扫描快照。
    LaunchedEffect(connected, settings.rssiSubscriptionEnabled, settings.rssiSamplePeriodSeconds, rssiController) {
        if (!connected) {
            rssiController.markDisconnected()
            return@LaunchedEffect
        }
        if (!settings.rssiSubscriptionEnabled) {
            rssiController.stop()
            return@LaunchedEffect
        }
        rssiController.run(settings.rssiSamplePeriodSeconds * 1_000L) {
            session.connection.connected.value
        }
    }

    // 每次信息刷新或实时样本到达都采样一次；存储层按设备保留最近 50 个点。
    LaunchedEffect(
        state.deviceId,
        state.loading,
        state.batteryPercent,
        state.uptimeSeconds,
        rssiState.latest?.timestampMillis,
    ) {
        if (state.loading) return@LaunchedEffect
        val percent = state.batteryPercent ?: return@LaunchedEffect
        val liveRssi = rssiState.latest
            ?.takeIf { it.source == RssiSource.CONNECTED_LIVE }
            ?.rssi
        metrics.record(state.deviceId, percent, liveRssi ?: session.device.rssi)
        metricsVersion++
    }

    fun dispatchNotification(
        event: NotificationEvent,
        enabled: Boolean,
        pageVisible: Boolean = false,
    ) {
        val decision = notificationCenter.dispatch(
            event = event,
            enabled = enabled,
            canNotify = notificationPresenter.canNotify,
            pageVisible = pageVisible,
        ) { title, body, target -> notificationPresenter.show(title, body, target) }
        if (decision == NotificationDecision.FALLBACK_BANNER) {
            onNotificationFallback(event)
        }
    }

    LaunchedEffect(session, connected) {
        if (!connected && !session.userDisconnected) {
            onConnectionLost()
        }
    }

    // 低电量提醒：只在未充电且跌破阈值时提醒一次，电量回升或开始充电后由 tracker 复位。
    LaunchedEffect(state.batteryPercent, state.charging, settings.notifyLowBatteryEnabled, settings.lowBatteryThreshold) {
        val percent = state.batteryPercent ?: return@LaunchedEffect
        val shouldNotify = NotificationPolicy.shouldNotifyLowBattery(settings, percent, state.charging)
        val triggered = lowBatteryTracker.update(percent, state.charging)
        if (shouldNotify && triggered) {
            dispatchNotification(
                NotificationEvent(
                    type = NotificationEventType.LOW_BATTERY,
                    title = strings.t(StringKey.NOTIFICATION_LOW_BATTERY_TITLE),
                    body = strings.t(
                        StringKey.NOTIFICATION_LOW_BATTERY_BODY,
                        "device" to (state.deviceInfo?.model ?: state.deviceId),
                        "percent" to percent.toString(),
                        "threshold" to settings.lowBatteryThreshold.toString(),
                    ),
                    target = NotificationTarget(NotificationPage.DETAIL, state.deviceId),
                    deviceId = state.deviceId,
                ),
                enabled = shouldNotify,
            )
        }
    }

    // 断开提醒：每次掉线最多提醒一次，重连成功后复位。
    LaunchedEffect(connected, settings.notifyDisconnectEnabled) {
        if (connected) {
            disconnectNotified = false
        } else if (
            !session.userDisconnected &&
            !disconnectNotified &&
            NotificationPolicy.shouldNotifyDisconnect(settings)
        ) {
            disconnectNotified = true
            dispatchNotification(
                NotificationEvent(
                    type = NotificationEventType.DISCONNECTED,
                    title = strings.t(StringKey.NOTIFICATION_DISCONNECTED_TITLE),
                    body = strings.t(
                        StringKey.NOTIFICATION_DISCONNECTED_BODY,
                        "device" to (state.deviceInfo?.model ?: state.deviceId),
                    ),
                    target = NotificationTarget(NotificationPage.DETAIL, state.deviceId),
                    deviceId = state.deviceId,
                ),
                enabled = true,
            )
        }
    }

    // 错误入时间线：写入失败只返回 false，不影响主流程。
    LaunchedEffect(state.error, state.errorCode) {
        val message = state.error ?: return@LaunchedEffect
        session.events.record(
            deviceId = state.deviceId,
            type = DeviceEventType.ERROR,
            summary = strings.t(StringKey.TIMELINE_EVENT_ERROR, "detail" to strings.render(message)),
            result = DeviceEventResult.FAILURE,
            detail = state.errorCode?.let { "${it.name} (0x${it.code.toString(16)})" },
            errorCode = state.errorCode?.name,
        )
    }

    LaunchedEffect(session.config, state.firmware, state.deviceInfo?.model) {
        session.config.updateTarget(
            DeviceConfigTarget(
                firmwareVersion = state.firmware,
                model = state.deviceInfo?.model,
                vendor = state.deviceInfo?.vendor,
            ),
        )
        // 首次进入或能力从未判定过时才读取；已加载的键不因刷新设备信息被清空。
        if (session.config.state.value.fields.none { it.loaded }) {
            try {
                session.config.load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                errorReporter.record("config-autoload", error)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.t(StringKey.DETAIL_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenDevices) {
                Text(strings.t(StringKey.DEVICES_ENTRY, "count" to connectedCount.toString()))
            }
            TextButton(onClick = onBack) { Text(strings.t(StringKey.BACK)) }
        }

        disconnectInfo?.let { info ->
            DisconnectBanner(
                info = info,
                reconnectState = reconnectState,
                onRetryReconnect = onRetryReconnect,
                onCancelReconnect = onCancelReconnect,
                onBleAction = onBleAction,
                onBack = onBack,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
        ) {
            item {
                val liveReading = rssiState.latest
                    ?.takeIf { it.source == RssiSource.CONNECTED_LIVE }
                DeviceDashboardCard(
                    detail = state,
                    series = metricSeries,
                    rssi = liveReading?.rssi ?: session.device.rssi,
                    rssiSource = if (liveReading != null) RssiSource.CONNECTED_LIVE else RssiSource.SNAPSHOT,
                    degradedReason = if (liveReading == null) rssiState.degradedReason else null,
                    legacyMetricsIgnored = metricLoad.legacyIgnored,
                    observedAtMillis = liveReading?.timestampMillis ?: connectObservedAt,
                    strings = strings,
                    format = format,
                    zoneOffsetMinutes = zoneOffsetMinutes,
                    accent = MaterialTheme.colorScheme.primary,
                    muted = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                val result = healthState.result
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(DesignTokens.Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                    ) {
                        Text(strings.t(StringKey.HEALTH_TITLE), style = MaterialTheme.typography.titleMedium)
                        if (healthState.running) {
                            LinearProgressIndicator(
                                progress = {
                                    if (healthState.total > 0) {
                                        healthState.completed.toFloat() / healthState.total
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                strings.t(
                                    StringKey.HEALTH_RUNNING,
                                    "done" to healthState.completed.toString(),
                                    "total" to healthState.total.toString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = { healthJob?.cancel() }) {
                                Text(strings.t(StringKey.HEALTH_CANCEL))
                            }
                        } else {
                            Button(
                                onClick = {
                                    healthMessage = null
                                    healthJob = scope.launchSafely(errorReporter, "health-check") {
                                        val checked = healthCheck.run()
                                        if (checked != null) {
                                            session.events.record(
                                                deviceId = session.detail.deviceId,
                                                type = DeviceEventType.HEALTH,
                                                summary = strings.t(StringKey.HEALTH_TITLE),
                                                result = if (checked.abortedByLinkLoss) {
                                                    DeviceEventResult.FAILURE
                                                } else {
                                                    DeviceEventResult.SUCCESS
                                                },
                                                detail = "ok=${checked.okCount}, abnormal=${checked.abnormalCount}, " +
                                                    "failed=${checked.failedCount}, skipped=${checked.skippedCount}",
                                            )
                                            if (checked.abnormalCount > 0) {
                                                dispatchNotification(
                                                    NotificationEvent(
                                                        type = NotificationEventType.HEALTH_ABNORMAL,
                                                        title = strings.t(
                                                            StringKey.NOTIFICATION_HEALTH_ABNORMAL_TITLE,
                                                        ),
                                                        body = strings.t(
                                                            StringKey.NOTIFICATION_HEALTH_ABNORMAL_BODY,
                                                            "device" to (state.deviceInfo?.model ?: state.deviceId),
                                                            "count" to checked.abnormalCount.toString(),
                                                        ),
                                                        target = NotificationTarget(
                                                            NotificationPage.DIAGNOSTIC,
                                                            state.deviceId,
                                                        ),
                                                        deviceId = state.deviceId,
                                                    ),
                                                    enabled = NotificationPolicy.shouldNotifyHealthAbnormal(settings),
                                                )
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text(strings.t(StringKey.HEALTH_RUN))
                            }
                        }
                        result?.let { checked ->
                            val overallText = when {
                                checked.abortedByLinkLoss -> strings.t(StringKey.HEALTH_OVERALL_ABORTED)
                                checked.abnormalCount > 0 -> strings.t(
                                    StringKey.HEALTH_OVERALL_ABNORMAL,
                                    "count" to checked.abnormalCount.toString(),
                                )
                                checked.failedCount > 0 -> strings.t(
                                    StringKey.HEALTH_OVERALL_FAILED,
                                    "count" to checked.failedCount.toString(),
                                )
                                else -> strings.t(StringKey.HEALTH_OVERALL_OK)
                            }
                            Text(
                                overallText,
                                style = MaterialTheme.typography.titleSmall,
                                color = when (checked.overall) {
                                    HealthCheckStatus.OK -> status.success
                                    HealthCheckStatus.ABNORMAL -> status.warning
                                    else -> status.error
                                },
                            )
                            checked.items.forEach { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        strings.t(item.type.labelKey()) + " · " +
                                            strings.t(item.status.labelKey()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (item.status) {
                                            HealthCheckStatus.OK -> status.success
                                            HealthCheckStatus.ABNORMAL -> status.warning
                                            HealthCheckStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                                            HealthCheckStatus.FAILED -> status.error
                                        },
                                    )
                                    item.detail?.let {
                                        Text(strings.render(it), style = MaterialTheme.typography.bodySmall)
                                    }
                                    item.errorCode?.let { code ->
                                        Text(
                                            strings.t(StringKey.HEALTH_DETAIL_DEVICE_REJECTED) + " ($code)",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    item.suggestion?.let {
                                        Text(strings.render(it), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(
                                            AnnotatedString(
                                                healthReportText(checked, format, zoneOffsetMinutes, strings),
                                            ),
                                        )
                                        healthMessageSuccess = true
                                        healthMessage = strings.t(StringKey.HEALTH_COPIED)
                                    },
                                ) {
                                    Text(strings.t(StringKey.DIAG_REPORT_COPY))
                                }
                                OutlinedButton(
                                    onClick = {
                                        val text = healthReportText(checked, format, zoneOffsetMinutes, strings)
                                        scope.launchSafely(errorReporter, "health-check-save") {
                                            val fileName = "valhalla-health-${checked.completedAtMillis}.txt"
                                            val save = fileSaver.save(SaveableFile(fileName, text, "text/plain"))
                                            healthMessageSuccess = save.status == FileSaveStatus.SAVED
                                            healthMessage = when (save.status) {
                                                FileSaveStatus.SAVED -> strings.t(
                                                    StringKey.DIAG_REPORT_SAVE_SUCCESS,
                                                    "name" to fileName,
                                                    "bytes" to format.number(text.length.toLong()),
                                                )

                                                FileSaveStatus.CANCELLED -> strings.t(StringKey.DIAG_REPORT_SAVE_CANCELLED)
                                                FileSaveStatus.UNAVAILABLE -> strings.t(StringKey.LOGS_SAVE_UNAVAILABLE)
                                                FileSaveStatus.FAILED -> strings.t(
                                                    StringKey.LOGS_SAVE_FAILED,
                                                    "detail" to (save.message ?: strings.t(StringKey.UNKNOWN)),
                                                )
                                            }
                                        }
                                    },
                                    enabled = !healthState.running,
                                ) {
                                    Text(strings.t(StringKey.HEALTH_SAVE))
                                }
                            }
                        }
                        healthMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (healthMessageSuccess) status.success else status.error,
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                    Text(
                        state.deviceId,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboard.setText(AnnotatedString(state.deviceId))
                            },
                        ),
                    )
                    Text(
                        strings.t(StringKey.LONG_PRESS_TO_COPY),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(strings.t(StringKey.STATE_LABEL, "state" to state.deviceState.toString()))
                    Text(
                        strings.t(
                            StringKey.FIRMWARE_LABEL,
                            "version" to (state.firmwareVersion ?: strings.t(StringKey.UNKNOWN)),
                        ),
                    )
                    Text(
                        strings.t(
                            StringKey.BATTERY_LABEL,
                            "percent" to (
                                state.batteryPercent?.let { "$it%" }
                                    ?: strings.t(StringKey.UNKNOWN)
                                ),
                        ) + if (state.charging == true) strings.t(StringKey.CHARGING) else "",
                    )
                    state.deviceInfo?.let { info ->
                        Text(
                            strings.t(
                                StringKey.MODEL_LINE,
                                "vendor" to info.vendor,
                                "model" to info.model,
                                "serial" to info.serialNumber,
                            ),
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                    Text(strings.t(StringKey.AUTH_MODE_LABEL), style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = authMode == AuthMode.TOKEN,
                            onClick = { authMode = AuthMode.TOKEN },
                            label = { Text(strings.t(StringKey.AUTH_MODE_TOKEN)) },
                        )
                        FilterChip(
                            selected = authMode == AuthMode.SIGNED,
                            onClick = { authMode = AuthMode.SIGNED },
                            label = { Text(strings.t(StringKey.AUTH_MODE_SIGNED)) },
                        )
                    }
                    Text(
                        strings.t(
                            if (authMode == AuthMode.SIGNED) StringKey.AUTH_SIGNED_HINT
                            else StringKey.AUTH_SECRET_HINT,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = credential,
                        onValueChange = { credential = it },
                        label = { Text(strings.t(StringKey.AUTH_CREDENTIAL_LABEL)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            if (credential.isBlank()) {
                                Text(strings.t(StringKey.AUTH_SECRET_HINT), style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        Button(
                            enabled = credential.isNotBlank(),
                            onClick = {
                                val secret = credential
                                scope.launchSafely(errorReporter, "auth") {
                                    val ok = if (authMode == AuthMode.SIGNED) {
                                        controller.authenticateSigned(
                                            signer = HmacAuthSigner(secret.encodeToByteArray()),
                                            clientNonce = Random.nextBytes(16),
                                            deviceDigest = PacketLogRedaction.hashDeviceId(state.deviceId),
                                        )
                                    } else {
                                        controller.authenticate(secret)
                                    }
                                    session.events.record(
                                        deviceId = state.deviceId,
                                        type = DeviceEventType.AUTH,
                                        summary = strings.t(
                                            if (ok) StringKey.TIMELINE_EVENT_AUTH_OK
                                            else StringKey.TIMELINE_EVENT_AUTH_FAILED,
                                        ),
                                        result = if (ok) DeviceEventResult.SUCCESS else DeviceEventResult.FAILURE,
                                    )
                                }
                            },
                        ) {
                            Text(strings.t(StringKey.AUTHENTICATE))
                        }
                        OutlinedButton(onClick = { scope.launchSafely(errorReporter, "refresh-info") { controller.refreshInfo() } }) {
                            Text(strings.t(StringKey.REFRESH_INFO))
                        }
                    }
                }
            }

            if (state.loading) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                        Text(strings.t(StringKey.LOADING_DETAIL), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                    OutlinedButton(
                        onClick = onOpenOta,
                        enabled = !writeLocked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.t(StringKey.ENTER_OTA))
                    }
                    OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.t(StringKey.OPEN_LOGS))
                    }
                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.t(StringKey.DIAG_ENTRY))
                    }
                    if (writeLocked) {
                        Text(
                            strings.t(StringKey.BATCH_LOCKED_HINT),
                            color = status.warning,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item {
                QuickActionSection(
                    controller = controller,
                    quickAction = state.quickAction,
                    connected = connected,
                    writeLocked = writeLocked,
                )
            }

            state.error?.let { message ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                        Text(
                            strings.render(message),
                            color = status.error,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { clipboard.setText(AnnotatedString(strings.render(message))) },
                                )
                                .semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    contentDescription = strings.render(message)
                                },
                        )
                        state.errorCode?.let { code ->
                            Text(
                                strings.t(
                                    StringKey.ERROR_CODE_LABEL,
                                    "code" to "${code.name} (0x${code.code.toString(16).uppercase().padStart(4, '0')}): ${code.title}",
                                ),
                                color = status.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launchSafely(errorReporter, "retry-info") { controller.refreshInfo() }
                                },
                            ) {
                                Text(strings.t(StringKey.ACTION_RETRY))
                            }
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(strings.render(message))) },
                            ) {
                                Text(strings.t(StringKey.LONG_PRESS_TO_COPY))
                            }
                        }
                    }
                }
            }

            (state.quickAction as? QuickActionState.Failed)?.let { failure ->
                item {
                    Text(
                        strings.render(failure.error),
                        color = status.error,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboard.setText(AnnotatedString(strings.render(failure.error)))
                            },
                        ),
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                    Text(strings.t(StringKey.TIMELINE_TITLE), style = MaterialTheme.typography.titleMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = timelineFilter == null,
                            onClick = { timelineFilter = null },
                            label = { Text(strings.t(StringKey.TIMELINE_FILTER_ALL)) },
                        )
                        DeviceEventType.entries.forEach { type ->
                            FilterChip(
                                selected = timelineFilter == type,
                                onClick = { timelineFilter = type },
                                label = { Text(strings.t(type.timelineKey())) },
                            )
                        }
                    }
                    val events = session.events.list(state.deviceId, timelineFilter)
                    if (events.isEmpty()) {
                        Text(strings.t(StringKey.TIMELINE_EMPTY), style = MaterialTheme.typography.bodySmall)
                    } else {
                        events.take(TIMELINE_VISIBLE_LIMIT).forEach { event ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                            ) {
                                Text(
                                    format.absoluteTime(event.timestampMillis, zoneOffsetMinutes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    event.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (event.result) {
                                        DeviceEventResult.FAILURE -> status.error
                                        DeviceEventResult.SUCCESS -> status.success
                                        DeviceEventResult.INFO -> MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .combinedClickable(
                                            onClick = {},
                                            onLongClick = {
                                                clipboard.setText(
                                                    AnnotatedString(
                                                        listOfNotNull(event.summary, event.detail)
                                                            .joinToString(" · "),
                                                    ),
                                                )
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
            }

            profile?.let { snapshot ->
                item { ProfileCard(snapshot, format, zoneOffsetMinutes) }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(DesignTokens.Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                    ) {
                        PresetSection(
                            presets = presets,
                            applier = remember(session.dispatcher) { ConfigPresetApplier(session.dispatcher) },
                            config = session.config,
                            deviceId = session.device.deviceId,
                            events = session.events,
                            writeLocked = writeLocked,
                        )
                    }
                }
            }

            item {
                ConfigSection(
                    controller = session.config,
                    state = configState,
                    writeLocked = writeLocked,
                    onRefreshDevice = { scope.launchSafely(errorReporter, "refresh-info") { controller.refreshInfo() } },
                    onSaved = { ok ->
                        session.events.record(
                            deviceId = state.deviceId,
                            type = DeviceEventType.CONFIG_CHANGE,
                            summary = strings.t(
                                if (ok) StringKey.TIMELINE_EVENT_CONFIG_SAVED
                                else StringKey.TIMELINE_EVENT_CONFIG_FAILED,
                            ),
                            result = if (ok) DeviceEventResult.SUCCESS else DeviceEventResult.FAILURE,
                        )
                    },
                )
            }

            item {
                ParamSection(
                    controller = session.param,
                    state = paramState,
                    connected = connected,
                    writeLocked = writeLocked,
                    onSaved = { ok ->
                        session.events.record(
                            deviceId = state.deviceId,
                            type = DeviceEventType.PARAM_CHANGE,
                            summary = strings.t(
                                if (ok) StringKey.TIMELINE_EVENT_PARAM_SAVED
                                else StringKey.TIMELINE_EVENT_PARAM_FAILED,
                            ),
                            result = if (ok) DeviceEventResult.SUCCESS else DeviceEventResult.FAILURE,
                        )
                    },
                )
            }

            item { Text(strings.t(StringKey.DEVICE_LOGS), style = MaterialTheme.typography.titleMedium) }
            items(state.logs) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics {
                        contentDescription = strings.t(StringKey.DEVICE_LOGS) + ": " + line
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickActionSection(
    controller: DeviceDetailController,
    quickAction: QuickActionState,
    connected: Boolean,
    writeLocked: Boolean,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    var showRebootDialog by remember { mutableStateOf(false) }
    var showFactoryDialog by remember { mutableStateOf(false) }
    var delayText by remember { mutableStateOf("0") }
    var confirmToken by remember { mutableStateOf("") }
    val busy = quickAction.busy
    val enabled = connected && !busy && !writeLocked

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            Text(strings.t(StringKey.QUICK_ACTIONS_TITLE), style = MaterialTheme.typography.titleMedium)
            if (writeLocked) {
                Text(
                    strings.t(StringKey.BATCH_LOCKED_HINT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                OutlinedButton(onClick = { showRebootDialog = true }, enabled = enabled) {
                    Text(strings.t(StringKey.QUICK_REBOOT))
                }
                OutlinedButton(onClick = { showFactoryDialog = true }, enabled = enabled) {
                    Text(strings.t(StringKey.QUICK_FACTORY_RESET))
                }
                Button(
                    enabled = enabled,
                    onClick = {
                        scope.launchSafely(errorReporter, "quick-heartbeat") {
                            controller.heartbeat()
                        }
                    },
                ) {
                    Text(strings.t(StringKey.QUICK_HEARTBEAT))
                }
            }

            when (quickAction) {
                QuickActionState.Idle -> Unit
                is QuickActionState.Running -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(strings.t(StringKey.QUICK_RUNNING), style = MaterialTheme.typography.bodySmall)
                }

                is QuickActionState.Succeeded -> when (quickAction.type) {
                    QuickActionType.REBOOT -> Text(
                        strings.t(
                            StringKey.QUICK_REBOOT_SUCCESS,
                            "delay" to (quickAction.delayMillis ?: 0).toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    QuickActionType.FACTORY_RESET -> Text(
                        strings.t(StringKey.QUICK_FACTORY_SUCCESS),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    QuickActionType.HEARTBEAT -> Text(
                        strings.t(
                            StringKey.QUICK_HEARTBEAT_SUCCESS,
                            "uptime" to "${quickAction.uptimeSeconds ?: 0}s",
                            "percent" to (quickAction.batteryPercent?.toString()
                                ?: strings.t(StringKey.UNKNOWN)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is QuickActionState.Failed -> Unit // 失败文案统一在页面错误区展示，支持长按复制。
            }
        }
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text(strings.t(StringKey.QUICK_REBOOT_CONFIRM_TITLE)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                    Text(strings.t(StringKey.QUICK_REBOOT_CONFIRM_MESSAGE))
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = { value ->
                            if (value.all { it.isDigit() } && value.length <= 5) delayText = value
                        },
                        label = { Text(strings.t(StringKey.QUICK_REBOOT_DELAY_LABEL)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebootDialog = false
                        val delay = delayText.toIntOrNull()?.coerceIn(0, 65_535) ?: 0
                        scope.launchSafely(errorReporter, "quick-reboot") {
                            controller.reboot(delay)
                        }
                    },
                ) { Text(strings.t(StringKey.CONFIRM)) }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text(strings.t(StringKey.CANCEL))
                }
            },
        )
    }

    if (showFactoryDialog) {
        AlertDialog(
            onDismissRequest = { showFactoryDialog = false },
            title = { Text(strings.t(StringKey.QUICK_FACTORY_CONFIRM_TITLE)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                    Text(strings.t(StringKey.QUICK_FACTORY_CONFIRM_MESSAGE))
                    Text(
                        strings.t(StringKey.QUICK_FACTORY_IRREVERSIBLE),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        strings.t(StringKey.QUICK_FIRMWARE_CONFIRM),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = confirmToken,
                        onValueChange = { confirmToken = it },
                        label = { Text(strings.t(StringKey.QUICK_CONFIRM_TOKEN_LABEL)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFactoryDialog = false
                        scope.launchSafely(errorReporter, "quick-factory-reset") {
                            controller.factoryReset(confirmToken)
                        }
                    },
                ) { Text(strings.t(StringKey.CONFIRM)) }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryDialog = false }) {
                    Text(strings.t(StringKey.CANCEL))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileCard(
    profile: DeviceProfile,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    var expandedIndex by remember(profile.deviceId) { mutableStateOf<Int?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(strings.t(StringKey.PROFILE_TITLE), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.t(
                    StringKey.PROFILE_LAST_CONNECTED,
                    "time" to format.absoluteTime(profile.lastConnectedAtEpochMillis, zoneOffsetMinutes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                strings.t(
                    StringKey.PROFILE_SUMMARY,
                    "version" to (profile.lastKnownFirmwareVersion ?: strings.t(StringKey.UNKNOWN)),
                    "percent" to (
                        profile.lastKnownBatteryPercent?.let { "$it%" }
                            ?: strings.t(StringKey.UNKNOWN)
                        ),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(strings.t(StringKey.OTA_HISTORY_TITLE), style = MaterialTheme.typography.titleSmall)
            if (profile.otaHistory.isEmpty()) {
                Text(
                    strings.t(StringKey.OTA_HISTORY_EMPTY),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    strings.t(StringKey.OTA_HISTORY_PRIVACY),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            profile.otaHistory.forEachIndexed { index, record ->
                OtaHistoryRow(
                    record = record,
                    expanded = expandedIndex == index,
                    onToggle = { expandedIndex = if (expandedIndex == index) null else index },
                    format = format,
                    zoneOffsetMinutes = zoneOffsetMinutes,
                    status = status,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OtaHistoryRow(
    record: OtaHistoryRecord,
    expanded: Boolean,
    onToggle: () -> Unit,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
    status: DesignTokens.StatusColors,
) {
    val strings = LocalStrings.current
    val failed = record.outcome == OtaOutcome.FAILED
    val outcomeColor = if (failed) status.error else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = {})
            .padding(vertical = DesignTokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                record.firmwareVersion,
                style = MaterialTheme.typography.bodyMedium,
                color = outcomeColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                record.outcome.label(strings),
                style = MaterialTheme.typography.labelMedium,
                color = outcomeColor,
            )
        }
        Text(
            strings.t(
                StringKey.OTA_HISTORY_TIME,
                "time" to format.absoluteTime(record.finishedAtEpochMillis, zoneOffsetMinutes),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Text(
                strings.t(
                    StringKey.PROFILE_OTA_RECORD,
                    "outcome" to record.outcome.label(strings),
                    "version" to record.firmwareVersion,
                    "duration" to format.number(record.durationMillis),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                strings.t(
                    StringKey.OTA_HISTORY_BYTES,
                    "sent" to record.sentBytes.toString(),
                    "total" to record.totalBytes.toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            record.errorCode?.let {
                Text(
                    strings.t(StringKey.ERROR_CODE_LABEL, "code" to it),
                    color = status.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            record.detail?.let {
                Text(
                    strings.t(StringKey.TECHNICAL_DETAIL, "detail" to it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            strings.t(
                if (expanded) StringKey.OTA_HISTORY_COLLAPSE else StringKey.OTA_HISTORY_EXPAND,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ConfigSection(
    controller: ConfigEditorController,
    state: ConfigEditorState,
    writeLocked: Boolean,
    onRefreshDevice: () -> Unit,
    onSaved: (Boolean) -> Unit = {},
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    strings.t(StringKey.CONFIG_TITLE),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { scope.launchSafely(errorReporter, "config-load") { controller.load() } },
                    enabled = !state.loading && !state.saving,
                ) {
                    Text(
                        if (state.loading) {
                            strings.t(StringKey.LOADING)
                        } else {
                            strings.t(StringKey.CONFIG_RELOAD)
                        },
                    )
                }
            }
            Text(
                strings.t(StringKey.CONFIG_HINT),
                style = MaterialTheme.typography.bodySmall,
            )
            if (writeLocked) {
                Text(
                    strings.t(StringKey.BATCH_LOCKED_HINT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            state.capabilityNoticeKey?.let { noticeKey ->
                val notice = if (noticeKey == StringKey.CONFIG_HIDDEN_UNKNOWN_TARGET) {
                    strings.t(noticeKey, "count" to state.hiddenFields.size.toString())
                } else {
                    strings.t(
                        noticeKey,
                        "count" to state.hiddenFields.size.toString(),
                        "firmware" to (
                            state.target.firmwareVersion
                                ?.let { "${it.major}.${it.minor}.${it.patch}" }
                                ?: strings.t(StringKey.UNKNOWN)
                            ),
                        "model" to (state.target.model ?: strings.t(StringKey.UNKNOWN)),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                    Text(
                        notice,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefreshDevice) {
                        Text(strings.t(StringKey.REFRESH_DEVICE_INFO))
                    }
                }
                state.hiddenFields.forEach { field ->
                    field.capability.reason?.let { reason ->
                        Text(
                            strings.t(StringKey.TECHNICAL_DETAIL, "detail" to reason),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            state.visibleFields.forEach { field ->
                ConfigFieldRow(
                    field = field,
                    savingAny = state.saving,
                    onTextChange = { controller.updateText(field.spec.key, it) },
                    onSave = {
                        if (writeLocked) {
                            return@ConfigFieldRow
                        }
                        scope.launchSafely(errorReporter, "config-save") {
                            val ok = controller.save(field.spec.key)
                            onSaved(ok)
                        }
                    },
                )
            }
            state.message?.let { message ->
                Text(
                    strings.render(message),
                    color = status.success,
                )
            }
            state.error?.let { message ->
                Text(
                    strings.render(message),
                    color = status.error,
                )
            }
        }
    }
}

@Composable
private fun ConfigFieldRow(
    field: ConfigFieldState,
    savingAny: Boolean,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val supporting = when {
        field.loading -> strings.t(StringKey.CONFIG_FIELD_LOADING)
        field.loadError != null ->
            strings.t(StringKey.TECHNICAL_DETAIL, "detail" to field.loadError!!)
        field.validationError != null ->
            strings.t(StringKey.TECHNICAL_DETAIL, "detail" to field.validationError!!)
        !field.editable ->
            strings.t(StringKey.CONFIG_FIELD_READ_ONLY) +
                (field.spec.readOnlyReason?.let {
                    " · " + strings.t(StringKey.TECHNICAL_DETAIL, "detail" to it)
                } ?: "")
        field.saving -> strings.t(StringKey.CONFIG_FIELD_SAVING)
        field.lastSavedAtEpochMillis != null && !field.dirty -> strings.t(StringKey.CONFIG_FIELD_SAVED)
        field.dirty -> strings.t(StringKey.CONFIG_FIELD_UNSAVED)
        else -> strings.t(StringKey.CONFIG_FIELD_EDITABLE)
    }
    val isError = field.loadError != null || field.validationError != null
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
            OutlinedTextField(
                value = field.text,
                onValueChange = onTextChange,
                enabled = field.editable && !field.saving && !savingAny,
                label = { Text(field.spec.key.toString()) },
                singleLine = true,
                supportingText = {
                    Text(
                        supporting,
                        color = if (isError) status.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onSave,
                enabled = field.editable && field.dirty && !field.saving && !savingAny,
            ) {
                Text(
                    if (field.saving) {
                        strings.t(StringKey.CONFIG_FIELD_SAVING)
                    } else {
                        strings.t(StringKey.SAVE)
                    },
                )
            }
        }
    }
}

private enum class ExportAction { SAVE, SHARE }

/**
 * 设备参数面板。
 *
 * 参数表来自 [ParamCatalog] 的约定值，界面在标题区固定标注「需固件确认」，
 * 不允许把未确认的参数表包装成设备能力。
 */
@Composable
private fun ParamSection(
    controller: ParamEditorController,
    state: ParamEditorState,
    connected: Boolean,
    writeLocked: Boolean,
    onSaved: (Boolean) -> Unit = {},
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    strings.t(StringKey.PARAM_TITLE),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { scope.launchSafely(errorReporter, "param-load") { controller.load() } },
                    enabled = connected && !state.busy,
                ) {
                    Text(
                        if (state.loading) {
                            strings.t(StringKey.PARAM_LOADING)
                        } else {
                            strings.t(StringKey.PARAM_RELOAD)
                        },
                    )
                }
            }
            Text(strings.t(StringKey.PARAM_HINT), style = MaterialTheme.typography.bodySmall)
            if (writeLocked) {
                Text(
                    strings.t(StringKey.BATCH_LOCKED_HINT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                Text(
                    strings.t(StringKey.PARAM_FIRMWARE_UNCONFIRMED),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(strings.t(StringKey.PARAM_FIRMWARE_NOTE), style = MaterialTheme.typography.bodySmall)

            state.fields.forEach { field ->
                ParamFieldRow(
                    field = field,
                    stateBusy = state.busy,
                    connected = connected,
                    onTextChange = { controller.updateText(field.spec.id, it) },
                    onSave = {
                        if (writeLocked) {
                            return@ParamFieldRow
                        }
                        scope.launchSafely(errorReporter, "param-save") {
                            val ok = controller.save(field.spec.id)
                            onSaved(ok)
                        }
                    },
                )
            }

            state.message?.let { message ->
                Text(strings.render(message), color = status.success)
            }
            state.error?.let { message ->
                Text(strings.render(message), color = status.error)
                state.errorCode?.let { code ->
                    Text(
                        strings.t(
                            StringKey.ERROR_CODE_LABEL,
                            "code" to "${code.name} (0x${code.code.toString(16).uppercase().padStart(4, '0')}): ${code.title}",
                        ),
                        color = status.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ParamFieldRow(
    field: ParamFieldState,
    stateBusy: Boolean,
    connected: Boolean,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val spec = field.spec
    val typeLabel = strings.t(paramTypeKey(spec.type))
    val rangeLabel = when (spec.type) {
        ParamValueType.INTEGER ->
            strings.t(StringKey.PARAM_RANGE, "range" to "${spec.intRange.first}..${spec.intRange.last}")

        ParamValueType.ENUM ->
            strings.t(StringKey.PARAM_RANGE, "range" to spec.enumValues.keys.sorted().joinToString("/"))

        ParamValueType.STRING ->
            strings.t(StringKey.PARAM_RANGE, "range" to "≤ ${spec.maxLength}")

        ParamValueType.BOOLEAN -> null
    }
    val detailLine = buildList {
        add(typeLabel)
        rangeLabel?.let(::add)
        spec.unit?.let(::add)
        add(
            strings.t(
                StringKey.PARAM_ID_LINE,
                "id" to spec.id.toString(16).uppercase().padStart(4, '0'),
            ),
        )
    }.joinToString(" · ")

    val supporting = when {
        field.loading -> strings.t(StringKey.PARAM_LOADING)
        field.loadError != null ->
            strings.t(StringKey.PARAM_LOAD_ERROR) + " · " + field.loadError

        field.validationError != null -> field.validationError!!
        !spec.writable -> strings.t(StringKey.PARAM_READ_ONLY_REASON)
        field.saving -> strings.t(StringKey.PARAM_SAVING)
        field.lastSavedAtEpochMillis != null && !field.dirty ->
            strings.t(StringKey.PARAM_SAVED_FIELD, "name" to spec.displayName)

        field.dirty -> strings.t(StringKey.PARAM_UNSAVED)
        else -> strings.t(StringKey.PARAM_VALUE_LABEL)
    }
    val isError = field.loadError != null || field.validationError != null

    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(spec.displayName, style = MaterialTheme.typography.titleSmall)
            if (!spec.writable) {
                Text(
                    strings.t(StringKey.PARAM_READ_ONLY),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(detailLine, style = MaterialTheme.typography.bodySmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = field.text,
                onValueChange = onTextChange,
                enabled = spec.writable && connected && !stateBusy,
                label = { Text(strings.t(StringKey.PARAM_VALUE_LABEL)) },
                singleLine = true,
                isError = isError,
                supportingText = {
                    Text(
                        supporting,
                        color = if (isError) status.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onSave,
                enabled = spec.writable && connected && field.dirty && !stateBusy,
            ) {
                Text(
                    if (field.saving) {
                        strings.t(StringKey.PARAM_SAVING)
                    } else {
                        strings.t(StringKey.PARAM_SAVE)
                    },
                )
            }
        }
    }
}

private fun paramTypeKey(type: ParamValueType): StringKey = when (type) {
    ParamValueType.INTEGER -> StringKey.PARAM_TYPE_INTEGER
    ParamValueType.BOOLEAN -> StringKey.PARAM_TYPE_BOOLEAN
    ParamValueType.ENUM -> StringKey.PARAM_TYPE_ENUM
    ParamValueType.STRING -> StringKey.PARAM_TYPE_STRING
}

/**
 * 连接质量看板 + 一键诊断报告。
 *
 * 质量统计只读本机报文日志；样本不足时只展示「样本不足（X 条）」，避免把个位数请求包装成结论。
 * 诊断报告在生成前给出小节与体积预估，生成在 Default 调度器执行，可显示进度并中途取消。
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun DiagnosticScreen(
    session: DeviceSession,
    metrics: DeviceMetricRepository,
    onBack: () -> Unit,
) {
    val packetLog = session.packetLog
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val entries by packetLog.entries.collectAsState()
    val stats = remember(entries) { ConnectionQualityAnalyzer.analyze(entries) }
    val detail by session.detail.state.collectAsState()
    val configState by session.config.state.collectAsState()
    val paramState by session.param.state.collectAsState()
    val zoneOffsetMinutes = remember {
        currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds())
    }
    val cards = statusColors()
    val clipboard = LocalClipboardManager.current
    val fileSaver = rememberFileSaver()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    var reportFormat by remember { mutableStateOf(DiagnosticReportFormat.MARKDOWN) }
    var reportGenerating by remember { mutableStateOf(false) }
    var reportProgress by remember { mutableStateOf<DiagnosticReportProgress?>(null) }
    var reportJob by remember { mutableStateOf<Job?>(null) }
    var reportResult by remember { mutableStateOf<DiagnosticReportResult?>(null) }
    var reportMessage by remember { mutableStateOf<String?>(null) }
    var reportMessageSuccess by remember { mutableStateOf(false) }
    var packageGenerating by remember { mutableStateOf(false) }
    var packageProgress by remember { mutableStateOf<DiagnosticPackageProgress?>(null) }
    var packageJob by remember { mutableStateOf<Job?>(null) }
    var packageMessage by remember { mutableStateOf<String?>(null) }
    var packageMessageSuccess by remember { mutableStateOf(false) }
    var packageResult by remember { mutableStateOf<DiagnosticPackageResult?>(null) }

    // 输入只来自真实快照：本会话状态 + 设备档案 + 本机报文日志，缺失项由报告标注「暂无数据」。
    fun currentReportInput(generatedAt: Long): DiagnosticReportInput {
        val profile = session.profileRepository.get(detail.deviceId)
        val configValues = configState.visibleFields.map { field ->
            DiagnosticReportValue(
                label = field.spec.displayName,
                value = if (field.loaded) field.text else null,
                status = field.loadError,
            )
        }
        val paramValues = paramState.fields.map { field ->
            DiagnosticReportValue(
                label = field.spec.displayName,
                value = if (field.loaded) field.text else null,
                status = field.loadError,
            )
        }
        val otaHistory = profile?.otaHistory.orEmpty().map { record ->
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
        val recentErrors = stats.recentFailures.map { failure ->
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

    val previewAt = remember { Clock.System.now().toEpochMilliseconds() }
    val preview = remember(
        reportFormat,
        entries,
        detail.firmwareVersion,
        detail.batteryPercent,
        detail.charging,
        configState.fields,
        paramState.fields,
    ) {
        DiagnosticReportComposer.estimate(
            input = currentReportInput(previewAt),
            options = DiagnosticReportOptions(format = reportFormat),
        )
    }

    fun saveReport(result: DiagnosticReportResult) {
        scope.launchSafely(errorReporter, "diagnostic-report-save") {
            val fileName = "valhalla-diagnostic-${Clock.System.now().toEpochMilliseconds()}.${result.format.extension}"
            val save = fileSaver.save(SaveableFile(fileName, result.text, result.format.mimeType))
            reportMessageSuccess = save.status == FileSaveStatus.SAVED
            reportMessage = when (save.status) {
                FileSaveStatus.SAVED -> strings.t(
                    StringKey.DIAG_REPORT_SAVE_SUCCESS,
                    "name" to fileName,
                    "bytes" to format.number(result.byteSize.toLong()),
                )

                FileSaveStatus.CANCELLED -> strings.t(StringKey.DIAG_REPORT_SAVE_CANCELLED)
                FileSaveStatus.UNAVAILABLE -> strings.t(StringKey.LOGS_SAVE_UNAVAILABLE)
                FileSaveStatus.FAILED -> strings.t(
                    StringKey.LOGS_SAVE_FAILED,
                    "detail" to (save.message ?: strings.t(StringKey.UNKNOWN)),
                )
            }
        }
    }

    fun packageInput(): DiagnosticPackageInput {
        val profile = session.profileRepository.get(detail.deviceId)
        val quality = buildString {
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
        val profileSummary = buildString {
            append("deviceId=").append(detail.deviceId).append('\n')
            append("displayName=").append(profile?.displayName ?: "-").append('\n')
            append("vendor=").append(profile?.vendor ?: "-").append('\n')
            append("model=").append(profile?.model ?: detail.deviceInfo?.model ?: "-").append('\n')
            append("firmware=").append(profile?.lastKnownFirmwareVersion ?: detail.firmwareVersion ?: "-")
                .append('\n')
            append("batteryPercent=").append(profile?.lastKnownBatteryPercent ?: detail.batteryPercent ?: "-")
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
            healthReport = session.lastHealthReport,
            connectionQuality = quality,
            deviceProfileSummary = profileSummary,
            packetLogEntries = entries,
            metricSamples = metrics.series(detail.deviceId).samples,
            packetLogOptions = AppSettings.DEFAULTS.exportOptions(),
        )
    }

    fun startPackage() {
        if (packageGenerating) return
        packageGenerating = true
        packageProgress = null
        packageResult = null
        packageMessage = null
        val input = packageInput()
        packageJob = scope.launchSafely(errorReporter, "diagnostic-package") {
            try {
                val built = withContext(Dispatchers.Default) {
                    DiagnosticPackageBuilder.build(input) { packageProgress = it }
                }
                packageResult = built
                // 构建完成前不触碰文件；保存失败也不会留下半成品。
                val save = fileSaver.save(
                    SaveableFile(built.fileName, built.text, "application/octet-stream"),
                )
                packageMessageSuccess = save.status == FileSaveStatus.SAVED
                packageMessage = when (save.status) {
                    FileSaveStatus.SAVED -> strings.t(
                        StringKey.DIAG_PACKAGE_SAVE_SUCCESS,
                        "name" to built.fileName,
                        "bytes" to format.number(built.byteSize.toLong()),
                    )

                    FileSaveStatus.CANCELLED -> strings.t(StringKey.DIAG_PACKAGE_SAVE_CANCELLED)
                    FileSaveStatus.UNAVAILABLE -> strings.t(StringKey.LOGS_SAVE_UNAVAILABLE)
                    FileSaveStatus.FAILED -> strings.t(
                        StringKey.DIAG_PACKAGE_FAILED,
                        "reason" to (save.message ?: strings.t(StringKey.UNKNOWN)),
                    )
                }
            } catch (cancellation: CancellationException) {
                packageMessageSuccess = false
                packageMessage = strings.t(StringKey.DIAG_PACKAGE_CANCELLED)
                throw cancellation
            } catch (error: Throwable) {
                packageMessageSuccess = false
                packageMessage = strings.t(
                    StringKey.DIAG_PACKAGE_FAILED,
                    "reason" to (error.message ?: error::class.simpleName ?: "unknown"),
                )
                errorReporter.record("diagnostic-package", error)
            } finally {
                packageGenerating = false
                packageProgress = null
                packageJob = null
            }
        }
    }

    fun startReport() {
        if (reportGenerating) return
        reportGenerating = true
        reportResult = null
        reportProgress = null
        reportMessage = null
        val input = currentReportInput(Clock.System.now().toEpochMilliseconds())
        val options = DiagnosticReportOptions(format = reportFormat)
        reportJob = scope.launchSafely(errorReporter, "diagnostic-report") {
            try {
                val built = withContext(Dispatchers.Default) {
                    DiagnosticReportComposer.build(input, options) { reportProgress = it }
                }
                reportResult = built
            } catch (cancellation: CancellationException) {
                reportMessageSuccess = false
                reportMessage = strings.t(StringKey.DIAG_REPORT_CANCELLED)
                throw cancellation
            } catch (error: Throwable) {
                reportMessageSuccess = false
                reportMessage = strings.t(
                    StringKey.DIAG_REPORT_FAILED,
                    "detail" to (error.message ?: error::class.simpleName ?: "unknown"),
                )
                errorReporter.record("diagnostic-report", error)
            } finally {
                reportGenerating = false
                reportProgress = null
                reportJob = null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.t(StringKey.DIAG_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text(strings.t(StringKey.BACK)) }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(DesignTokens.Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                    ) {
                        Text(
                            strings.t(StringKey.DIAG_REPORT_TITLE),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            strings.t(
                                StringKey.DIAG_REPORT_PREVIEW,
                                "sections" to preview.sectionCount.toString(),
                                "size" to format.bytes(preview.estimatedBytes.toLong()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            strings.t(
                                StringKey.DIAG_REPORT_SECTION_LIST,
                                "list" to preview.sections.keys.joinToString(", ") {
                                    strings.t(it.reportLabelKey())
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                            DiagnosticReportFormat.entries.forEach { candidate ->
                                FilterChip(
                                    selected = reportFormat == candidate,
                                    enabled = !reportGenerating,
                                    onClick = {
                                        reportFormat = candidate
                                        reportResult = null
                                        reportMessage = null
                                    },
                                    label = { Text(strings.t(candidate.formatKey())) },
                                )
                            }
                        }
                        if (reportGenerating) {
                            LinearProgressIndicator(
                                progress = { reportProgress?.fraction ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                strings.t(
                                    StringKey.DIAG_REPORT_GENERATING,
                                    "percent" to ((reportProgress?.fraction ?: 0f) * 100).toInt().toString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = { reportJob?.cancel() }) {
                                Text(strings.t(StringKey.DIAG_REPORT_CANCEL))
                            }
                        } else {
                            Button(onClick = { startReport() }) {
                                Text(strings.t(StringKey.DIAG_REPORT_GENERATE))
                            }
                        }
                        reportResult?.let { built ->
                            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(built.text))
                                        reportMessageSuccess = true
                                        reportMessage = strings.t(StringKey.DIAG_REPORT_COPIED)
                                    },
                                ) {
                                    Text(strings.t(StringKey.DIAG_REPORT_COPY))
                                }
                                OutlinedButton(onClick = { saveReport(built) }) {
                                    Text(strings.t(StringKey.DIAG_REPORT_SAVE))
                                }
                            }
                        }
                        reportMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (reportMessageSuccess) cards.success else cards.error,
                            )
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(DesignTokens.Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                    ) {
                        Text(
                            strings.t(StringKey.DIAG_PACKAGE_TITLE),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            strings.t(StringKey.DIAG_PACKAGE_HINT),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (packageGenerating) {
                            LinearProgressIndicator(
                                progress = { packageProgress?.fraction ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                strings.t(StringKey.DIAG_PACKAGE_EXPORTING),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = { packageJob?.cancel() }) {
                                Text(strings.t(StringKey.DIAG_PACKAGE_CANCEL))
                            }
                        } else {
                            Button(onClick = { startPackage() }) {
                                Text(strings.t(StringKey.DIAG_PACKAGE_EXPORT))
                            }
                        }
                        packageResult?.let { built ->
                            Text(
                                strings.t(
                                    StringKey.DIAG_PACKAGE_ENTRIES,
                                    "names" to built.entries.joinToString(", ") { it.name },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (built.truncated) {
                                Text(
                                    strings.t(StringKey.DIAG_PACKAGE_TRUNCATED),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text(
                            strings.t(StringKey.DIAG_PACKAGE_EXCLUDED),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            strings.t(StringKey.DIAG_PACKAGE_REDACTED),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        packageMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (packageMessageSuccess) cards.success else cards.error,
                            )
                        }
                    }
                }
            }
            item {
                Text(strings.t(StringKey.DIAG_HINT), style = MaterialTheme.typography.bodySmall)
            }
            if (stats.requests == 0) {
                item { Text(strings.t(StringKey.DIAG_EMPTY)) }
            } else if (stats.insufficientSample) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(DesignTokens.Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                        ) {
                            Text(
                                strings.t(
                                    StringKey.DIAG_SAMPLE_INSUFFICIENT,
                                    "count" to stats.sampleCount.toString(),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                strings.t(
                                    StringKey.DIAG_SAMPLE_NOTE,
                                    "min" to ConnectionQualityAnalyzer.MIN_SAMPLES.toString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(DesignTokens.Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs),
                        ) {
                            Text(
                                strings.t(
                                    StringKey.DIAG_REQUESTS,
                                    "count" to format.number(stats.requests),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            stats.successRate?.let { rate ->
                                Text(
                                    strings.t(
                                        StringKey.DIAG_SUCCESS_RATE,
                                        "rate" to formatOneDecimal(rate * 100),
                                    ),
                                )
                            }
                            stats.averageLatencyMillis?.let { value ->
                                Text(
                                    strings.t(
                                        StringKey.DIAG_LATENCY_AVG,
                                        "value" to formatOneDecimal(value),
                                    ),
                                )
                            }
                            stats.maxLatencyMillis?.let { value ->
                                Text(
                                    strings.t(
                                        StringKey.DIAG_LATENCY_MAX,
                                        "value" to format.number(value),
                                    ),
                                )
                            }
                            Text(
                                strings.t(
                                    StringKey.DIAG_RETRANSMISSIONS,
                                    "count" to format.number(stats.retransmissions),
                                ),
                            )
                            Text(
                                strings.t(
                                    StringKey.DIAG_OUT_OF_ORDER,
                                    "count" to format.number(stats.outOfOrderResponses),
                                ),
                            )
                            Text(
                                strings.t(
                                    StringKey.DIAG_NO_RESPONSE,
                                    "count" to format.number(stats.noResponses),
                                ),
                            )
                            if (stats.unmatchedResponses > 0) {
                                Text(
                                    strings.t(
                                        StringKey.DIAG_UNKNOWN_ERROR,
                                    ) + " · " + format.number(stats.unmatchedResponses),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        strings.t(StringKey.DIAG_ERROR_TITLE),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (stats.errorCodes.isEmpty()) {
                    item { Text(strings.t(StringKey.DIAG_NO_FAILURE)) }
                } else {
                    items(stats.errorCodes) { error ->
                        Text(
                            strings.t(
                                StringKey.DIAG_ERROR_CODE_LINE,
                                "code" to error.code,
                                "title" to error.title,
                                "count" to format.number(error.count),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    Text(
                        strings.t(StringKey.DIAG_COMMANDS_TITLE),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(stats.commands) { command ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(DesignTokens.Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs),
                        ) {
                            Text(
                                strings.t(
                                    StringKey.DIAG_COMMAND_LINE,
                                    "command" to command.command.name,
                                    "requests" to command.requests.toString(),
                                    "successes" to command.successes.toString(),
                                    "failures" to (command.deviceErrors + command.noResponses).toString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val average = command.averageLatencyMillis
                            val maximum = command.maxLatencyMillis
                            if (average != null && maximum != null) {
                                Text(
                                    strings.t(
                                        StringKey.DIAG_COMMAND_LATENCY,
                                        "avg" to formatOneDecimal(average),
                                        "max" to maximum.toString(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (command.retransmissions > 0) {
                                Text(
                                    strings.t(
                                        StringKey.DIAG_RETRANSMISSIONS,
                                        "count" to command.retransmissions.toString(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        strings.t(StringKey.DIAG_RECENT_FAILURES),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (stats.recentFailures.isEmpty()) {
                    item { Text(strings.t(StringKey.DIAG_NO_FAILURE)) }
                } else {
                    items(stats.recentFailures) { failure ->
                        Text(
                            strings.t(
                                StringKey.DIAG_FAILURE_LINE,
                                "time" to format.absoluteTime(failure.timestampMillis, zoneOffsetMinutes),
                                "command" to failure.command.name,
                                "detail" to failure.detail,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = cards.error,
                        )
                    }
                }
            }
        }
    }
}

/** 一位小数，避免把估算值展示成精确值。 */
internal fun formatOneDecimal(value: Double): String {
    val scaled = (value * 10).toLong()
    return "${scaled / 10}.${scaled % 10}"
}

/** 首次使用引导：三步说明 + 可跳过；完成状态由控制器写入平台偏好。 */
@Composable
private fun OnboardingScreen(
    reviewMode: Boolean,
    store: com.valhalla.data.settings.AppSettingsStore,
    availability: BleAvailability,
    onBleAction: (BleGuidanceAction) -> Unit,
    onFinished: () -> Unit,
) {
    val strings = LocalStrings.current
    val controller = remember(store, reviewMode) { OnboardingController(store, reviewMode) }
    val state by controller.state.collectAsState()
    val steps = listOf(
        OnboardingStep.PERMISSION to (
            StringKey.ONBOARDING_STEP_PERMISSION_TITLE to StringKey.ONBOARDING_STEP_PERMISSION_BODY
            ),
        OnboardingStep.SCAN to (
            StringKey.ONBOARDING_STEP_SCAN_TITLE to StringKey.ONBOARDING_STEP_SCAN_BODY
            ),
        OnboardingStep.CONNECT to (
            StringKey.ONBOARDING_STEP_CONNECT_TITLE to StringKey.ONBOARDING_STEP_CONNECT_BODY
            ),
    )
    val (titleKey, bodyKey) = steps.first { it.first == state.step }.second

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        Text(strings.t(StringKey.ONBOARDING_TITLE), style = MaterialTheme.typography.headlineSmall)
        Text(
            strings.t(
                StringKey.ONBOARDING_STEP,
                "index" to (state.index + 1).toString(),
                "total" to state.total.toString(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(DesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            ) {
                Text(strings.t(titleKey), style = MaterialTheme.typography.titleMedium)
                Text(strings.t(bodyKey), style = MaterialTheme.typography.bodyMedium)
                if (state.step == OnboardingStep.PERMISSION) {
                    Text(
                        strings.t(StringKey.ONBOARDING_NO_PERMISSION_PROMPT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { onBleAction(BleGuidanceAction.REQUEST_PERMISSION) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.t(StringKey.ONBOARDING_GRANT_PERMISSION))
                    }
                    availability.guidance()?.let { guidance ->
                        Text(
                            strings.render(guidance.message),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (reviewMode) {
                    Text(
                        strings.t(StringKey.ONBOARDING_REVIEW_NOTE),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
            if (!state.isLastStep) {
                OutlinedButton(onClick = { controller.next() }) {
                    Text(strings.t(StringKey.ONBOARDING_NEXT))
                }
                TextButton(
                    onClick = {
                        controller.skip()
                        onFinished()
                    },
                ) {
                    Text(strings.t(StringKey.ONBOARDING_SKIP))
                }
            } else {
                Button(
                    onClick = {
                        controller.finish()
                        onFinished()
                    },
                ) {
                    Text(strings.t(StringKey.ONBOARDING_FINISH))
                }
                TextButton(
                    onClick = {
                        controller.skip()
                        onFinished()
                    },
                ) {
                    Text(strings.t(StringKey.ONBOARDING_SKIP))
                }
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun PacketLogScreen(
    packetLog: InMemoryPacketLog,
    initialOptions: PacketLogExportOptions,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val localeFormat = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val entries by packetLog.entries.collectAsState()
    var query by remember { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }
    var selectedCommands by remember { mutableStateOf<Set<CommandType>>(emptySet()) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(PacketLogExportFormat.TEXT) }
    var maxEntriesText by remember(initialOptions) {
        mutableStateOf(initialOptions.maxEntries.toString())
    }
    var maxBytesKbText by remember(initialOptions) {
        mutableStateOf((initialOptions.maxBytes / 1024).toString())
    }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<PacketLogExportProgress?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportSuccess by remember { mutableStateOf(false) }
    var estimate by remember { mutableStateOf<PacketLogExportEstimate?>(null) }

    val clipboard = LocalClipboardManager.current
    val sharer = remember { createTextSharer() }
    val fileSaver = rememberFileSaver()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    // 索引只在日志快照变化时重建；输入关键字只做一次包含判断，不逐条重新拼接。
    val logIndex = remember(entries) { PacketLogIndex(entries) }
    val nowMillis = Clock.System.now().toEpochMilliseconds()
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(nowMillis) }
    // 会话内已使用的文件名，用于重名冲突解析。
    val exportedNames = remember { mutableListOf<String>() }
    val availableCommands = remember(entries) { entries.map { it.command }.distinct().sortedBy { it.name } }

    // 输入去抖：停止输入 150ms 后才切换过滤结果，避免每个按键触发全量过滤。
    LaunchedEffect(query) {
        if (query != appliedQuery) {
            delay(150)
            appliedQuery = query
        }
    }

    val fromMillis = if (fromText.isBlank()) null else parseLocalDateTimeMillis(fromText, zoneOffsetMinutes)
    val toMillis = if (toText.isBlank()) null else parseLocalDateTimeMillis(toText, zoneOffsetMinutes)
    val fromError = if (fromText.isNotBlank() && fromMillis == null) {
        strings.t(StringKey.LOGS_TIME_FORMAT_ERROR)
    } else {
        null
    }
    val toError = if (toText.isNotBlank() && toMillis == null) {
        strings.t(StringKey.LOGS_TIME_FORMAT_ERROR)
    } else {
        null
    }
    val rangeError = if (fromMillis != null && toMillis != null && fromMillis > toMillis) {
        strings.t(StringKey.LOGS_TIME_RANGE_ERROR)
    } else {
        null
    }
    val entryLimit = validateEntryLimit(maxEntriesText)
    val byteLimit = validateByteLimitKb(maxBytesKbText)
    val options = if (entryLimit.valid && byteLimit.valid) {
        PacketLogExportOptions(
            maxEntries = entryLimit.value!!,
            maxBytes = byteLimit.value!!,
            maxHexCharsPerEntry = initialOptions.maxHexCharsPerEntry,
        )
    } else {
        null
    }

    val filter = PacketLogFilter(
        commands = selectedCommands,
        errorsOnly = errorsOnly,
        keyword = appliedQuery,
        fromMillis = fromMillis,
        toMillis = toMillis,
    )

    val filtered = remember(logIndex, filter) {
        logIndex.query(filter = filter, limit = InMemoryPacketLog.DEFAULT_CAPACITY)
    }

    // 预估与实际导出使用同一套筛选/上限逻辑，避免两处口径分叉。
    LaunchedEffect(logIndex, filter, format, entryLimit.value, byteLimit.value, rangeError) {
        val opts = options
        if (opts == null || rangeError != null) {
            estimate = null
            return@LaunchedEffect
        }
        delay(200)
        estimate = withContext(Dispatchers.Default) {
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

    fun startExport(action: ExportAction) {
        if (exporting) return
        val error = rangeError ?: fromError ?: toError
        if (error != null) {
            exportSuccess = false
            exportMessage = error
            return
        }
        val opts = options
        if (opts == null) {
            exportSuccess = false
            exportMessage = strings.t(StringKey.LOGS_INVALID_LIMIT)
            return
        }
        if (estimate?.matchedCount == 0) {
            exportSuccess = false
            exportMessage = strings.t(StringKey.LOGS_ESTIMATE_NO_MATCH)
            return
        }

        exportSuccess = false
        exportMessage = null
        exporting = true
        val snapshot = packetLog.entries.value
        exportJob = scope.launchSafely(errorReporter, "packet-export") {
            try {
                val now = Clock.System.now().toEpochMilliseconds()
                // 渲染在 Default 调度器执行，避免大日志阻塞 UI；取消会在进度检查点生效。
                val result = withContext(Dispatchers.Default) {
                    PacketLogExporter.build(
                        entries = snapshot,
                        nowMillis = now,
                        zoneOffsetMinutes = currentTimeZoneOffsetMinutes(now),
                        options = opts,
                        filter = filter,
                        format = format,
                        onProgress = { progress = it },
                    )
                }
                progress = null
                if (result.empty) {
                    exportSuccess = false
                    exportMessage = strings.t(StringKey.LOGS_ESTIMATE_NO_MATCH)
                    return@launchSafely
                }
                val fileName = PacketLogExporter.resolveFileName(result.fileName, exportedNames)
                when (action) {
                    ExportAction.SAVE -> {
                        val save = fileSaver.save(SaveableFile(fileName, result.text, format.mimeType))
                        when (save.status) {
                            FileSaveStatus.SAVED -> {
                                exportedNames += fileName
                                exportSuccess = true
                                exportMessage = strings.t(
                                    StringKey.LOGS_SAVE_SUCCESS,
                                    "name" to fileName,
                                    "entries" to localeFormat.number(result.entryCount),
                                    "bytes" to localeFormat.number(result.byteSize),
                                )
                            }

                            FileSaveStatus.CANCELLED -> {
                                exportSuccess = false
                                exportMessage = strings.t(StringKey.LOGS_SAVE_CANCELLED)
                            }

                            FileSaveStatus.UNAVAILABLE -> {
                                exportSuccess = false
                                exportMessage = strings.t(StringKey.LOGS_SAVE_UNAVAILABLE)
                            }

                            FileSaveStatus.FAILED -> {
                                exportSuccess = false
                                exportMessage = strings.t(
                                    StringKey.LOGS_SAVE_FAILED,
                                    "detail" to (save.message ?: strings.t(StringKey.UNKNOWN)),
                                )
                            }
                        }
                    }

                    ExportAction.SHARE -> {
                        val shared = sharer.share(ShareableText(fileName, result.text, format.mimeType))
                        when (shared.status) {
                            ShareStatus.SHARED -> {
                                exportedNames += fileName
                                exportSuccess = true
                                exportMessage = strings.t(
                                    StringKey.LOGS_SHARE_SUCCESS,
                                    "name" to fileName,
                                    "entries" to localeFormat.number(result.entryCount),
                                    "bytes" to localeFormat.number(result.byteSize),
                                )
                            }

                            ShareStatus.UNAVAILABLE -> {
                                exportSuccess = false
                                exportMessage = strings.t(StringKey.LOGS_SHARE_UNAVAILABLE)
                            }

                            ShareStatus.FAILED -> {
                                exportSuccess = false
                                exportMessage = strings.t(
                                    StringKey.LOGS_SHARE_FAILED,
                                    "detail" to (shared.message ?: strings.t(StringKey.UNKNOWN)),
                                )
                            }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                exportSuccess = false
                exportMessage = strings.t(StringKey.LOGS_EXPORT_CANCELLED)
                throw cancellation
            } catch (error: Throwable) {
                // 渲染/保存/分享的意外异常也要展示给用户并收敛 exporting 状态。
                exportSuccess = false
                exportMessage = strings.t(
                    StringKey.FAILURE_UNKNOWN,
                    "detail" to (error.message ?: error::class.simpleName ?: "unknown"),
                )
                errorReporter.record("packet-export", error)
            } finally {
                exporting = false
                progress = null
                exportJob = null
            }
        }
    }

    val canExport = !exporting && entries.isNotEmpty() && options != null &&
        rangeError == null && fromError == null && toError == null &&
        estimate?.matchedCount != 0

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                strings.t(StringKey.LOGS_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = canExport,
                onClick = { startExport(ExportAction.SAVE) },
            ) { Text(strings.t(StringKey.SAVE_FILE)) }
            TextButton(
                enabled = canExport,
                onClick = { startExport(ExportAction.SHARE) },
            ) { Text(strings.t(StringKey.SHARE)) }
            TextButton(onClick = onBack) { Text(strings.t(StringKey.BACK)) }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(strings.t(StringKey.LOGS_SEARCH)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            FilterChip(
                selected = selectedCommands.isEmpty(),
                onClick = { selectedCommands = emptySet() },
                label = { Text(strings.t(StringKey.LOGS_ALL_COMMANDS)) },
            )
            availableCommands.forEach { item ->
                val selected = item in selectedCommands
                FilterChip(
                    selected = selected,
                    onClick = {
                        selectedCommands = if (selected) {
                            selectedCommands - item
                        } else {
                            selectedCommands + item
                        }
                    },
                    label = { Text(item.name) },
                )
            }
            FilterChip(
                selected = errorsOnly,
                onClick = { errorsOnly = !errorsOnly },
                label = { Text(strings.t(StringKey.LOGS_ERRORS_ONLY)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            OutlinedTextField(
                value = fromText,
                onValueChange = { fromText = it },
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
                value = toText,
                onValueChange = { toText = it },
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

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            PacketLogExportFormat.entries.forEach { item ->
                FilterChip(
                    selected = format == item,
                    onClick = { format = item },
                    label = { Text(item.name) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            OutlinedTextField(
                value = maxEntriesText,
                onValueChange = { maxEntriesText = it },
                label = { Text(strings.t(StringKey.LOGS_ENTRY_LIMIT)) },
                singleLine = true,
                isError = !entryLimit.valid,
                supportingText = {
                    Text(
                        if (entryLimit.valid) {
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
                value = maxBytesKbText,
                onValueChange = { maxBytesKbText = it },
                label = { Text(strings.t(StringKey.LOGS_BYTES_LIMIT)) },
                singleLine = true,
                isError = !byteLimit.valid,
                supportingText = {
                    Text(
                        if (byteLimit.valid) {
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

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val estimateLine = when {
                rangeError != null -> strings.t(
                    StringKey.LOGS_ESTIMATE_INVALID_RANGE,
                    "detail" to rangeError,
                )

                fromError != null || toError != null -> strings.t(StringKey.LOGS_TIME_FORMAT_ERROR)
                options == null -> strings.t(StringKey.LOGS_ESTIMATE_INVALID_LIMIT, "detail" to "")
                estimate == null -> strings.t(StringKey.LOGS_ESTIMATE_LOADING)
                estimate!!.matchedCount == 0 -> strings.t(StringKey.LOGS_ESTIMATE_NO_MATCH)
                estimate!!.truncated -> strings.t(
                    StringKey.LOGS_ESTIMATE_TRUNCATED,
                    "entries" to localeFormat.number(estimate!!.estimatedEntries),
                    "bytes" to localeFormat.bytes(estimate!!.estimatedBytes.toLong()),
                    "matched" to localeFormat.number(estimate!!.matchedCount),
                )

                else -> strings.t(
                    StringKey.LOGS_ESTIMATE,
                    "entries" to localeFormat.number(estimate!!.estimatedEntries),
                    "bytes" to localeFormat.bytes(estimate!!.estimatedBytes.toLong()),
                )
            }
            Text(
                estimateLine,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (exporting) {
                TextButton(onClick = { exportJob?.cancel() }) { Text(strings.t(StringKey.CANCEL)) }
            }
        }

        if (exporting) {
            val current = progress
            val phaseText = current?.phase?.let { it.label(strings) }
                ?: strings.t(StringKey.LOGS_EXPORT_PREPARING)
            Text(
                strings.t(
                    StringKey.LOGS_EXPORTING,
                    "phase" to phaseText,
                    "processed" to localeFormat.number(current?.processed ?: 0),
                    "total" to localeFormat.number(current?.total ?: 0),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { current?.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                color = status.inProgress,
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                strings.t(
                    StringKey.LOGS_DISPLAY_COUNT,
                    "shown" to localeFormat.number(filtered.size),
                    "total" to localeFormat.number(entries.size),
                    "capacity" to localeFormat.number(InMemoryPacketLog.DEFAULT_CAPACITY),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { packetLog.clear() }) { Text(strings.t(StringKey.CLEAR)) }
        }

        exportMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (exportSuccess) status.success else status.error,
            )
        }

        if (filtered.isEmpty()) {
            Text(
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
            items(filtered, key = { it.id }) { entry ->
                PacketLogCard(
                    entry = entry,
                    nowMillis = nowMillis,
                    zoneOffsetMinutes = zoneOffsetMinutes,
                    format = localeFormat,
                    onCopy = {
                        clipboard.setText(
                            AnnotatedString(entry.formatForCopy(nowMillis, zoneOffsetMinutes)),
                        )
                    },
                )
            }
        }
    }
}

private fun PacketLogExportPhase.label(strings: StringResolver): String = when (this) {
    PacketLogExportPhase.FILTERING -> strings.t(StringKey.LOGS_EXPORT_PHASE_FILTERING)
    PacketLogExportPhase.RENDERING -> strings.t(StringKey.LOGS_EXPORT_PHASE_RENDERING)
    PacketLogExportPhase.ASSEMBLING -> strings.t(StringKey.LOGS_EXPORT_PHASE_ASSEMBLING)
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

private fun OtaOutcome.label(strings: StringResolver): String = when (this) {
    OtaOutcome.SUCCEEDED -> strings.t(StringKey.OTA_OUTCOME_SUCCEEDED)
    OtaOutcome.FAILED -> strings.t(StringKey.OTA_OUTCOME_FAILED)
    OtaOutcome.ABORTED -> strings.t(StringKey.OTA_OUTCOME_ABORTED)
}

@OptIn(ExperimentalTime::class)
@Composable
private fun OtaScreen(
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
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val controller = session.ota
    val state by controller.state.collectAsState()
    val detailState by session.detail.state.collectAsState()
    val picker = rememberFirmwarePicker()
    val signingKeyPem = rememberFirmwareSigningKeyPem()
    val reuse = remember(picker) { FirmwareLibraryReuse(picker) }
    var libraryVersion by remember { mutableStateOf(0) }
    var libraryBusyId by remember { mutableStateOf<String?>(null) }
    var libraryMessage by remember { mutableStateOf<String?>(null) }
    var libraryModelFilter by remember { mutableStateOf("") }
    var libraryVersionQuery by remember { mutableStateOf("") }
    var librarySort by remember { mutableStateOf(FirmwareLibrarySort.RECENT) }
    var libraryRenameId by remember { mutableStateOf<String?>(null) }
    var libraryRenameText by remember { mutableStateOf("") }
    var libraryImporting by remember { mutableStateOf(false) }
    val libraryImporter = remember(picker, library) { FirmwareLibraryImporter(picker, library) }
    val selection = remember(picker, session.checkpointStore, signingKeyPem) {
        FirmwareSelectionController(
            picker = picker,
            checkpointStore = session.checkpointStore,
            prechecker = FirmwarePrecheckFactory.production(signingKeyPem),
        )
    }
    val selectionState by selection.state.collectAsState()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    val running = state is OtaState.Preparing || state is OtaState.Transferring || state is OtaState.Verifying
    var wizardStep by remember { mutableStateOf(OtaWizardStep.SELECT) }

    LaunchedEffect(selection) { selection.refreshResume() }
    LaunchedEffect(state) {
        wizardStep = when (state) {
            is OtaState.Preparing, is OtaState.Transferring, is OtaState.Verifying -> OtaWizardFlow.onRunStarted()
            is OtaState.Succeeded, is OtaState.Failed, is OtaState.Aborted -> OtaWizardFlow.onTerminal()
            is OtaState.Paused -> wizardStep
            OtaState.Idle -> wizardStep
        }
    }
    val terminal = state is OtaState.Succeeded || state is OtaState.Failed || state is OtaState.Aborted
    LaunchedEffect(terminal, state) {
        if (!terminal) return@LaunchedEffect
        val current = state
        if (current !is OtaState.Succeeded && current !is OtaState.Failed && current !is OtaState.Aborted) {
            return@LaunchedEffect
        }
        if (current is OtaState.Succeeded) selection.clearResume() else selection.refreshResume()

        // 事件时间线：终端状态只记一次（key 为 state 实例变化才重跑）。
        val summary = when (current) {
            is OtaState.Succeeded -> strings.t(StringKey.TIMELINE_EVENT_OTA_DONE)
            is OtaState.Failed -> strings.t(StringKey.TIMELINE_EVENT_OTA_FAILED)
            else -> strings.t(StringKey.TIMELINE_EVENT_OTA_ABORTED)
        }
        val result = when (current) {
            is OtaState.Succeeded -> DeviceEventResult.SUCCESS
            is OtaState.Failed -> DeviceEventResult.FAILURE
            else -> DeviceEventResult.INFO
        }
        session.events.record(
            deviceId = session.device.deviceId,
            type = DeviceEventType.OTA,
            summary = summary,
            result = result,
            detail = (current as? OtaState.Failed)?.let { strings.render(it.reason) },
            errorCode = (current as? OtaState.Aborted)?.reason,
        )
        if (current is OtaState.Succeeded) onOtaSucceeded()

        // 固件库：只落元信息与原始引用，不复制二进制。
        val version = selectionState.version
        val path = selectionState.sourcePath
        val crc = selectionState.computedCrc32 ?: selectionState.declaredCrc32
        val firmware = selection.firmware
        if (version != null && path != null && crc != null && firmware != null && selectionState.packageName != null) {
            val insurer = library.record(
                FirmwareLibraryRecord(
                    fileName = selectionState.packageName!!,
                    version = version.toString(),
                    channel = version.channel,
                    modelWhitelist = firmware.header.targetModels.toList(),
                    totalBytes = selectionState.payloadBytes,
                    crc32 = crc,
                    addedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    sourcePath = path,
                ),
            )
            val outcome = when (current) {
                is OtaState.Succeeded -> FirmwareLibraryOutcome.SUCCEEDED
                is OtaState.Failed -> FirmwareLibraryOutcome.FAILED
                else -> FirmwareLibraryOutcome.ABORTED
            }
            library.markUsed(insurer.id, session.device.deviceId, outcome)
            libraryVersion++
        }

        val otaEvent = when (current) {
            is OtaState.Succeeded -> NotificationEvent(
                type = NotificationEventType.OTA_SUCCESS,
                title = strings.t(StringKey.NOTIFICATION_OTA_SUCCESS_TITLE),
                body = strings.t(
                    StringKey.NOTIFICATION_OTA_SUCCESS_BODY,
                    "version" to (version?.toString() ?: strings.t(StringKey.UNKNOWN)),
                ),
                target = NotificationTarget(NotificationPage.OTA, session.device.deviceId),
                deviceId = session.device.deviceId,
            )

            is OtaState.Failed -> NotificationEvent(
                type = NotificationEventType.OTA_FAILED,
                title = strings.t(StringKey.NOTIFICATION_OTA_FAILED_TITLE),
                body = strings.t(
                    StringKey.NOTIFICATION_OTA_FAILED_BODY,
                    "version" to (version?.toString() ?: strings.t(StringKey.UNKNOWN)),
                    "reason" to strings.render(current.reason),
                ),
                target = NotificationTarget(NotificationPage.OTA, session.device.deviceId),
                deviceId = session.device.deviceId,
            )

            else -> null
        }
        if (otaEvent != null) {
            val decision = notificationCenter.dispatch(
                event = otaEvent,
                enabled = NotificationPolicy.shouldNotifyOta(settings),
                canNotify = notificationPresenter.canNotify,
            ) { title, body, target -> notificationPresenter.show(title, body, target) }
            if (decision == NotificationDecision.FALLBACK_BANNER) {
                onNotificationFallback(otaEvent)
            }
        }
    }
    DisposableEffect(selection) {
        onDispose { selection.close() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
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

        val stepTitle = when (wizardStep) {
            OtaWizardStep.SELECT -> strings.t(StringKey.OTA_STEP_SELECT)
            OtaWizardStep.PRECHECK -> strings.t(StringKey.OTA_STEP_PRECHECK)
            OtaWizardStep.CONFIRM -> strings.t(StringKey.OTA_STEP_CONFIRM)
            OtaWizardStep.TRANSFER -> strings.t(StringKey.OTA_STEP_TRANSFER)
            OtaWizardStep.RESULT -> strings.t(StringKey.OTA_STEP_RESULT)
        }
        Text(
            strings.t(
                StringKey.OTA_WIZARD_STEP,
                "current" to wizardStep.index.toString(),
                "title" to stepTitle,
            ),
            style = MaterialTheme.typography.titleSmall,
        )

        if (wizardStep == OtaWizardStep.SELECT) {
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
                        wizardStep = OtaWizardStep.PRECHECK
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
            Text(strings.t(StringKey.OPENING_PICKER), style = MaterialTheme.typography.bodySmall)
        }
        if (selectionState.prechecking) {
            LinearProgressIndicator(
                progress = { selectionState.precheckProgress },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = strings.t(
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

        libraryMessage?.let { message ->
            Text(message, color = status.warning, style = MaterialTheme.typography.bodySmall)
        }
        var libraryAvailability by remember { mutableStateOf<Map<String, FirmwareReuseCheck>>(emptyMap()) }
        val libraryFilter = remember(libraryModelFilter, libraryVersionQuery) {
            FirmwareLibraryFilter(
                model = libraryModelFilter.takeIf { it.isNotBlank() },
                versionQuery = libraryVersionQuery.takeIf { it.isNotBlank() },
            )
        }
        val libraryRecords = remember(libraryVersion, library, libraryFilter, librarySort) {
            library.list(libraryFilter, librarySort)
        }
        LaunchedEffect(libraryVersion, libraryFilter, librarySort) {
            val checks = mutableMapOf<String, FirmwareReuseCheck>()
            libraryRecords.take(LIBRARY_VISIBLE_LIMIT).forEach { record ->
                val check = runCatching { reuse.check(record) }.getOrNull() ?: return@forEach
                checks[record.id] = check
            }
            libraryAvailability = checks
        }
        val currentModel = detailState.deviceInfo?.model
        val currentFirmwareVersion = FirmwareApplicabilityEvaluator.parseVersion(
            detailState.firmwareVersion.orEmpty(),
        )
        val latestApplicable = FirmwareApplicabilityEvaluator.latestApplicable(
            libraryRecords,
            currentModel,
            currentFirmwareVersion,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(DesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
            ) {
                Text(strings.t(StringKey.OTA_LIBRARY_TITLE), style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.t(StringKey.OTA_LIBRARY_NOTE),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    strings.t(StringKey.LIB_IMPORT_NOTE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                    Button(
                        enabled = !libraryImporting && !running,
                        onClick = {
                            scope.launchSafely(errorReporter, "firmware-library-import") {
                                libraryImporting = true
                                libraryMessage = when (val outcome = libraryImporter.importOne()) {
                                    is FirmwareImportOutcome.Imported -> {
                                        libraryVersion++
                                        strings.t(StringKey.LIB_IMPORT_SUCCESS, "name" to outcome.recordName)
                                    }

                                    is FirmwareImportOutcome.Duplicate -> {
                                        libraryVersion++
                                        strings.t(StringKey.LIB_IMPORT_DUPLICATE, "name" to outcome.recordName)
                                    }

                                    is FirmwareImportOutcome.Rejected -> strings.t(
                                        StringKey.LIB_IMPORT_REJECTED,
                                        "code" to outcome.code,
                                        "detail" to (outcome.detail ?: strings.t(StringKey.UNKNOWN)),
                                    )

                                    FirmwareImportOutcome.Cancelled ->
                                        strings.t(StringKey.LIB_IMPORT_CANCELLED)
                                }
                                libraryImporting = false
                            }
                        },
                    ) {
                        Text(
                            if (libraryImporting) {
                                strings.t(StringKey.LIB_IMPORTING)
                            } else {
                                strings.t(StringKey.LIB_IMPORT)
                            },
                        )
                    }
                    Button(
                        enabled = latestApplicable != null && !running && libraryBusyId == null,
                        onClick = {
                            val record = latestApplicable ?: return@Button
                            scope.launchSafely(errorReporter, "firmware-library-latest") {
                                libraryBusyId = record.id
                                val opened = selection.openFromLibrary(
                                    sourcePath = record.sourcePath,
                                    currentVersion = detailState.firmware,
                                    currentModel = currentModel,
                                )
                                libraryBusyId = null
                                libraryMessage = if (opened) {
                                    strings.t(StringKey.LIB_SELECTED, "name" to record.displayName)
                                } else {
                                    strings.t(StringKey.OTA_LIBRARY_USE_FAILED, "detail" to "open failed")
                                }
                            }
                        },
                    ) { Text(strings.t(StringKey.LIB_SELECT_LATEST)) }
                }
                if (latestApplicable == null && libraryRecords.isNotEmpty()) {
                    Text(
                        strings.t(StringKey.LIB_NO_APPLICABLE),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = libraryModelFilter,
                        onValueChange = { libraryModelFilter = it },
                        label = { Text(strings.t(StringKey.LIB_FILTER_MODEL_ALL)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = libraryVersionQuery,
                        onValueChange = { libraryVersionQuery = it },
                        label = { Text(strings.t(StringKey.LIB_FILTER_VERSION)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                    FilterChip(
                        selected = librarySort == FirmwareLibrarySort.RECENT,
                        onClick = { librarySort = FirmwareLibrarySort.RECENT },
                        label = { Text(strings.t(StringKey.LIB_SORT_RECENT)) },
                    )
                    FilterChip(
                        selected = librarySort == FirmwareLibrarySort.VERSION,
                        onClick = { librarySort = FirmwareLibrarySort.VERSION },
                        label = { Text(strings.t(StringKey.LIB_SORT_VERSION)) },
                    )
                }
                if (libraryRecords.isEmpty()) {
                    Text(strings.t(StringKey.OTA_LIBRARY_EMPTY), style = MaterialTheme.typography.bodySmall)
                } else {
                    libraryRecords.take(LIBRARY_VISIBLE_LIMIT).forEach { record ->
                        val availability = libraryAvailability[record.id]
                        val unavailableText = when (availability) {
                            is FirmwareReuseCheck.Missing -> strings.t(
                                StringKey.OTA_LIBRARY_MISSING,
                                "reason" to strings.t(
                                    when (availability.reason) {
                                        FirmwareReuseReason.ACCESS_DENIED ->
                                            StringKey.OTA_LIBRARY_MISSING_ACCESS

                                        FirmwareReuseReason.READ_FAILED ->
                                            StringKey.OTA_LIBRARY_MISSING_READ
                                    },
                                ),
                            )

                            is FirmwareReuseCheck.CrcMismatch -> strings.t(
                                StringKey.OTA_LIBRARY_CRC_MISMATCH,
                                "expected" to hex32(availability.expectedCrc32),
                                "actual" to (availability.actualCrc32?.let(::hex32) ?: "-"),
                            )

                            FirmwareReuseCheck.NotFound ->
                                strings.t(StringKey.OTA_LIBRARY_USE_FAILED, "detail" to "not found")

                            else -> null
                        }
                        val applicability = FirmwareApplicabilityEvaluator.evaluate(
                            record,
                            currentModel,
                            currentFirmwareVersion,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
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
                                        if (applicability.applicable) {
                                            strings.t(StringKey.LIB_APPLICABLE)
                                        } else {
                                            val reasonKey = applicability.reason.labelKey()
                                            if (reasonKey == null) {
                                                strings.t(StringKey.LIB_INAPPLICABLE)
                                            } else {
                                                strings.t(StringKey.LIB_INAPPLICABLE) + " · " +
                                                    strings.t(reasonKey)
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (applicability.applicable) {
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
                                                "outcome" to strings.t(
                                                    when (record.lastOutcome) {
                                                        FirmwareLibraryOutcome.SUCCEEDED ->
                                                            StringKey.OTA_LIBRARY_OUTCOME_SUCCEEDED

                                                        FirmwareLibraryOutcome.FAILED ->
                                                            StringKey.OTA_LIBRARY_OUTCOME_FAIL

                                                        else -> StringKey.OTA_LIBRARY_OUTCOME_ABORTED
                                                    },
                                                ),
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        if (libraryBusyId != null || running || selectionState.picking ||
                                            selectionState.prechecking
                                        ) {
                                            return@TextButton
                                        }
                                        scope.launchSafely(errorReporter, "firmware-library-use") {
                                            libraryBusyId = record.id
                                            libraryMessage = strings.t(StringKey.OTA_LIBRARY_CHECKING)
                                            val check = reuse.check(record)
                                            libraryBusyId = null
                                            libraryMessage = when (check) {
                                                is FirmwareReuseCheck.Available -> {
                                                    val opened = selection.openFromLibrary(
                                                        sourcePath = record.sourcePath,
                                                        currentVersion = detailState.firmware,
                                                        currentModel = currentModel,
                                                    )
                                                    if (opened) {
                                                        strings.t(StringKey.OTA_LIBRARY_AVAILABLE)
                                                    } else {
                                                        strings.t(
                                                            StringKey.OTA_LIBRARY_USE_FAILED,
                                                            "detail" to "open failed",
                                                        )
                                                    }
                                                }

                                                is FirmwareReuseCheck.Missing -> strings.t(
                                                    StringKey.OTA_LIBRARY_MISSING,
                                                    "reason" to strings.t(
                                                        when (check.reason) {
                                                            FirmwareReuseReason.ACCESS_DENIED ->
                                                                StringKey.OTA_LIBRARY_MISSING_ACCESS

                                                            FirmwareReuseReason.READ_FAILED ->
                                                                StringKey.OTA_LIBRARY_MISSING_READ
                                                        },
                                                    ),
                                                )

                                                is FirmwareReuseCheck.CrcMismatch -> strings.t(
                                                    StringKey.OTA_LIBRARY_CRC_MISMATCH,
                                                    "expected" to hex32(check.expectedCrc32),
                                                    "actual" to (check.actualCrc32?.let(::hex32) ?: "-"),
                                                )

                                                FirmwareReuseCheck.NotFound ->
                                                    strings.t(StringKey.OTA_LIBRARY_USE_FAILED, "detail" to "not found")
                                            }
                                        }
                                    },
                                    enabled = !running && libraryBusyId == null,
                                ) {
                                    Text(
                                        if (libraryBusyId == record.id) {
                                            strings.t(StringKey.OTA_LIBRARY_CHECKING)
                                        } else {
                                            strings.t(StringKey.OTA_LIBRARY_USE)
                                        },
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                                TextButton(
                                    onClick = {
                                        library.setFavorite(record.id, !record.favorite)
                                        libraryVersion++
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
                                        libraryRenameId = record.id
                                        libraryRenameText = record.alias.orEmpty()
                                    },
                                ) {
                                    Text(strings.t(StringKey.LIB_RENAME))
                                }
                                TextButton(
                                    onClick = {
                                        library.delete(record.id)
                                        libraryVersion++
                                    },
                                ) {
                                    Text(strings.t(StringKey.OTA_LIBRARY_DELETE))
                                }
                            }
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
                    }
                }
            }
        }
        }
        libraryRenameId?.let { id ->
            AlertDialog(
                onDismissRequest = { libraryRenameId = null },
                title = { Text(strings.t(StringKey.LIB_RENAME_TITLE)) },
                text = {
                    OutlinedTextField(
                        value = libraryRenameText,
                        onValueChange = { libraryRenameText = it },
                        label = { Text(strings.t(StringKey.LIB_RENAME_HINT)) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        library.rename(id, libraryRenameText)
                        libraryVersion++
                        libraryRenameId = null
                    }) { Text(strings.t(StringKey.SAVE)) }
                },
                dismissButton = {
                    TextButton(onClick = { libraryRenameId = null }) {
                        Text(strings.t(StringKey.CANCEL))
                    }
                },
            )
        }

        if (wizardStep == OtaWizardStep.PRECHECK) {
            if (selectionState.prechecking) {
                LinearProgressIndicator(
                    progress = { selectionState.precheckProgress },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = strings.t(
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
            val precheck = selectionState.precheck
            if (precheck == null) {
                Text(
                    selectionState.error?.let { strings.render(it) }
                        ?: strings.t(StringKey.FIRMWARE_NOT_PRECHECKED),
                    color = if (selectionState.error != null) {
                        status.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            } else {
                Text(
                    strings.t(StringKey.OTA_PRECHECK_RULES),
                    style = MaterialTheme.typography.titleSmall,
                )
                OtaPrecheckRuleEvaluator.evaluate(precheck).forEach { rule ->
                    val ruleLabel = when (rule.rule) {
                        OtaPrecheckRule.FILE_STRUCTURE -> strings.t(StringKey.OTA_RULE_STRUCTURE)
                        OtaPrecheckRule.SIZE_LIMIT -> strings.t(StringKey.OTA_RULE_SIZE)
                        OtaPrecheckRule.DEVICE_MODEL -> strings.t(StringKey.OTA_RULE_MODEL)
                        OtaPrecheckRule.VERSION_DIRECTION -> strings.t(StringKey.OTA_RULE_VERSION)
                        OtaPrecheckRule.PAYLOAD_CRC -> strings.t(StringKey.OTA_RULE_CRC)
                        OtaPrecheckRule.SIGNATURE -> strings.t(StringKey.OTA_RULE_SIGNATURE)
                    }
                    val blocking = rule.status == OtaPrecheckRuleStatus.BLOCKING
                    Text(
                        ruleLabel + " · " + strings.t(
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
            selectionState.error?.let { message ->
                Text(
                    strings.render(message) + (selectionState.errorCode?.let { " ($it)" } ?: ""),
                    color = status.error,
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

        if (wizardStep == OtaWizardStep.CONFIRM) {
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
                        color = if (battery != null && battery >= 50) {
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
            val resumePoint = selectionState.resumePoint
            if (resumePoint != null && selectionState.canResume) {
                Text(
                    strings.t(
                        StringKey.OTA_RESUME_FROM,
                        "percent" to OtaWizardFlow.resumePercent(
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

        if (wizardStep == OtaWizardStep.TRANSFER || wizardStep == OtaWizardStep.RESULT) {
        when (val current = state) {
            OtaState.Idle -> Text(strings.t(StringKey.OTA_IDLE))
            is OtaState.Preparing -> Text(
                strings.t(
                    StringKey.OTA_PREPARING,
                    "bytes" to format.bytes(current.totalBytes.toLong()),
                    "crc" to hex16(current.crc16),
                    "session" to (current.sessionId?.let {
                        strings.t(StringKey.OTA_SESSION_SUFFIX, "id" to it.toString())
                    } ?: ""),
                ),
            )

            is OtaState.Transferring -> {
                val progressText = strings.t(
                    StringKey.OTA_TRANSFERRING,
                    "stage" to current.stage.label(strings),
                    "sent" to format.number(current.sentBytes),
                    "total" to format.number(current.totalBytes),
                    "percent" to (current.progress * 100).toInt().toString(),
                    "resume" to "",
                )
                LinearProgressIndicator(
                    progress = { current.progress },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = progressText
                        progressBarRangeInfo = ProgressBarRangeInfo(current.progress, 0f..1f)
                    },
                    color = status.inProgress,
                )
                val resumeNote = if (current.initialOffset > 0) {
                    strings.t(StringKey.OTA_RESUME_NOTE, "offset" to format.bytes(current.initialOffset.toLong()))
                } else {
                    ""
                }
                Text(
                    strings.t(
                        StringKey.OTA_TRANSFERRING,
                        "stage" to current.stage.label(strings),
                        "sent" to format.number(current.sentBytes),
                        "total" to format.number(current.totalBytes),
                        "percent" to (current.progress * 100).toInt().toString(),
                        "resume" to resumeNote,
                    ),
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

            is OtaState.Verifying -> Text(
                strings.t(StringKey.OTA_VERIFYING, "crc" to hex16(current.crc16)),
            )

            is OtaState.Succeeded -> Text(
                strings.t(
                    StringKey.OTA_SUCCEEDED,
                    "chunks" to format.number(current.chunks),
                    "crc" to hex16(current.crc16),
                ) + if (current.rebootRequired) strings.t(StringKey.OTA_REBOOT_REQUIRED) else "",
                color = status.success,
            )

            is OtaState.Paused -> Text(
                strings.t(
                    StringKey.OTA_PAUSED_SAFE_POINT,
                    "sent" to format.number(current.sentBytes),
                    "total" to format.number(current.totalBytes),
                ),
                color = status.warning,
            )

            is OtaState.Aborted -> Text(
                strings.t(
                    StringKey.OTA_ABORTED,
                    "reason" to abortReasonText(current.reason, strings),
                    "sent" to format.number(current.sentBytes),
                    "total" to format.number(current.totalBytes),
                ),
                color = status.warning,
            )

            is OtaState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                Text(
                    strings.t(StringKey.OTA_FAILED, "reason" to strings.render(current.reason)),
                    color = status.error,
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
            if (wizardStep == OtaWizardStep.RESULT) {
                val resultPoint = (state as? OtaState.Failed)?.resumePoint ?: selectionState.resumePoint
                if (resultPoint != null) {
                    Text(
                        strings.t(
                            StringKey.OTA_RESULT_RESUME_SAVED,
                            "percent" to OtaWizardFlow.resumePercent(
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
        }

        Spacer(modifier = Modifier.weight(1f))

        when (wizardStep) {
            OtaWizardStep.SELECT -> Row(
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
                        wizardStep = OtaWizardFlow.next(
                            OtaWizardStep.SELECT,
                            selectionReady = selectionState.packageName != null,
                            precheckPassed = selectionState.precheck?.passed == true,
                        )
                    },
                    enabled = selectionState.packageName != null &&
                        !selectionState.picking && !selectionState.prechecking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(strings.t(StringKey.OTA_NEXT))
                }
            }

            OtaWizardStep.PRECHECK -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            ) {
                TextButton(
                    onClick = {
                        wizardStep = OtaWizardFlow.previous(OtaWizardStep.PRECHECK, running)
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
                        wizardStep = OtaWizardFlow.next(
                            OtaWizardStep.PRECHECK,
                            selectionReady = selectionState.packageName != null,
                            precheckPassed = selectionState.precheck?.passed == true,
                        )
                    },
                    enabled = selectionState.precheck?.passed == true && !selectionState.prechecking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(strings.t(StringKey.OTA_NEXT))
                }
            }

            OtaWizardStep.CONFIRM -> Column(
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                ) {
                    TextButton(
                        onClick = {
                            wizardStep = OtaWizardFlow.previous(OtaWizardStep.CONFIRM, running)
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
                                "percent" to OtaWizardFlow.resumePercent(
                                    resumePoint.sentBytes.toLong(),
                                    resumePoint.totalBytes.toLong(),
                                ).toString(),
                            ),
                        )
                    }
                }
            }

            OtaWizardStep.TRANSFER -> OutlinedButton(
                onClick = {
                    scope.launchSafely(errorReporter, "ota-abort") { controller.abort() }
                },
                enabled = running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(strings.t(StringKey.ABORT_OTA))
            }

            OtaWizardStep.RESULT -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            ) {
                TextButton(
                    onClick = {
                        controller.reset()
                        selection.clearSelection()
                        wizardStep = OtaWizardFlow.previous(OtaWizardStep.RESULT, running = false)
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
    }
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
                Text(
                    strings.render(reason),
                    color = status.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val precheck = selectionState.precheck
            if (precheck == null) {
                if (selectionState.error == null) {
                    Text(strings.t(StringKey.FIRMWARE_NOT_PRECHECKED), style = MaterialTheme.typography.bodySmall)
                }
            } else if (precheck.passed) {
                Text(strings.t(StringKey.FIRMWARE_PRECHECK_PASSED), color = status.success)
            } else {
                Text(strings.t(StringKey.FIRMWARE_PRECHECK_FAILED), color = status.error)
                precheck.errors.forEach { error ->
                    Text(
                        "· " + strings.t(StringKey.TECHNICAL_DETAIL, "detail" to (error.message ?: "")),
                        color = status.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            selectionState.error?.let { message ->
                Text(
                    strings.render(message) +
                        (selectionState.errorCode?.let { " ($it)" } ?: ""),
                    color = status.error,
                )
            }

            TextButton(onClick = onRecheck) { Text(strings.t(StringKey.RECHECK_FIRMWARE)) }
        }
    }
}

private fun abortReasonText(reasonName: String, strings: StringResolver): String = when (
    OtaAbortReason.entries.firstOrNull { it.name == reasonName }
) {
    OtaAbortReason.TIMEOUT -> strings.t(StringKey.OTA_ABORT_TIMEOUT)
    OtaAbortReason.VERIFY_FAILED -> strings.t(StringKey.OTA_ABORT_VERIFY_FAILED)
    OtaAbortReason.TRANSPORT_ERROR -> strings.t(StringKey.OTA_ABORT_TRANSPORT)
    OtaAbortReason.DEVICE_REJECTED -> strings.t(StringKey.OTA_ABORT_DEVICE_REJECTED)
    OtaAbortReason.USER_CANCELLED -> strings.t(StringKey.OTA_ABORT_USER)
    null -> strings.t(StringKey.OTA_ABORT_USER)
}

private fun hex32(value: Long?): String =
    value?.let { "0x" + it.toString(16).padStart(8, '0') } ?: "—"

private fun hex16(value: Int?): String =
    value?.let { "0x" + it.toString(16).uppercase().padStart(4, '0') } ?: "—"

private fun OtaStage.label(strings: StringResolver): String = when (this) {
    OtaStage.PREPARING -> strings.t(StringKey.OTA_STAGE_PREPARING)
    OtaStage.TRANSFERRING -> strings.t(StringKey.OTA_STAGE_TRANSFERRING)
    OtaStage.VERIFYING -> strings.t(StringKey.OTA_STAGE_VERIFYING)
    OtaStage.APPLYING -> strings.t(StringKey.OTA_STAGE_APPLYING)
    OtaStage.COMPLETED -> strings.t(StringKey.OTA_STAGE_COMPLETED)
}

/**
 * OTA 队列执行器：逐台打开固件库引用，走完整预检后再用既有 OTA 控制器升级。
 *
 * 暂停/取消信号只在分片边界生效（由 OtaController 的 queue signal 实现），
 * 不会在半片状态停机；失败断点仍由既有四重门控复用。
 */
private class SessionOtaQueueExecutor(
    private val sessions: () -> Map<String, DeviceSession>,
    private val library: FirmwareLibraryRepository,
    private val picker: FirmwarePicker,
    private val signingKeyPem: String?,
    private val renderReason: (UiMessage) -> String,
) : OtaQueueExecutor {

    override suspend fun execute(
        item: OtaQueueItem,
        progress: (OtaQueueStage, Float) -> Unit,
        pauseRequested: () -> Boolean,
        cancelRequested: () -> Boolean,
    ): OtaQueueExecutionResult {
        val session = sessions()[item.target.deviceId]
            ?: return OtaQueueExecutionResult.Skipped("queue.device_missing")
        if (!session.connection.connected.value) {
            return OtaQueueExecutionResult.Skipped("queue.device_disconnected")
        }
        val record = library.get(item.target.firmwareId)
            ?: return OtaQueueExecutionResult.Failed("firmware.library.unavailable", false)
        val selection = FirmwareSelectionController(
            picker = picker,
            checkpointStore = session.checkpointStore,
            prechecker = FirmwarePrecheckFactory.production(signingKeyPem),
        )
        return try {
            progress(OtaQueueStage.PRECHECK, 0f)
            val detail = session.detail.state.value
            val opened = selection.openFromLibrary(
                sourcePath = record.sourcePath,
                currentVersion = detail.firmware,
                currentModel = detail.deviceInfo?.model,
            )
            if (!opened) {
                val code = selection.state.value.errorCode ?: "firmware.precheck_failed"
                return OtaQueueExecutionResult.Failed(code, false)
            }
            val firmware = selection.firmware
                ?: return OtaQueueExecutionResult.Failed("firmware.precheck_failed", false)
            val resume = selection.resumePointForCurrentFirmware()
            progress(OtaQueueStage.PRECHECK, 1f)
            session.ota.configureQueueSignals(pauseRequested, cancelRequested)
            val result = coroutineScope {
                val observer = launch {
                    session.ota.state.collect { state ->
                        when (state) {
                            is OtaState.Preparing -> progress(OtaQueueStage.PRECHECK, 1f)
                            is OtaState.Transferring ->
                                progress(OtaQueueStage.TRANSFER, state.progress)

                            is OtaState.Verifying -> progress(OtaQueueStage.VERIFY, 0.99f)
                            is OtaState.Succeeded -> progress(OtaQueueStage.DONE, 1f)
                            else -> Unit
                        }
                    }
                }
                try {
                    session.ota.startPackage(
                        firmware = firmware,
                        resumeFrom = resume,
                        knownCrc16 = selection.state.value.computedCrc16,
                    )
                } finally {
                    observer.cancel()
                }
            }
            when (result) {
                is OtaState.Succeeded -> {
                    selection.clearResume()
                    progress(OtaQueueStage.DONE, 1f)
                    OtaQueueExecutionResult.Succeeded
                }

                is OtaState.Paused -> OtaQueueExecutionResult.Paused(
                    progress = result.progress,
                    checkpointRetained = result.checkpointSaved,
                )

                is OtaState.Aborted -> OtaQueueExecutionResult.Aborted(
                    reason = result.reason,
                    checkpointRetained = selection.resumePointForCurrentFirmware() != null,
                )

                is OtaState.Failed -> OtaQueueExecutionResult.Failed(
                    reason = renderReason(result.reason),
                    checkpointRetained = result.resumePoint != null,
                )

                else -> OtaQueueExecutionResult.Failed("ota.unexpected_state", false)
            }
        } finally {
            session.ota.clearQueueSignals()
            selection.close()
        }
    }
}

private fun FirmwareApplicabilityReason.labelKey(): StringKey? = when (this) {
    FirmwareApplicabilityReason.NONE -> null
    FirmwareApplicabilityReason.MODEL_UNKNOWN -> StringKey.LIB_REASON_MODEL_UNKNOWN
    FirmwareApplicabilityReason.MODEL_MISMATCH -> StringKey.LIB_REASON_MODEL_MISMATCH
    FirmwareApplicabilityReason.VERSION_UNKNOWN -> StringKey.LIB_REASON_VERSION_UNKNOWN
    FirmwareApplicabilityReason.VERSION_UNPARSEABLE -> StringKey.LIB_REASON_VERSION_UNPARSEABLE
    FirmwareApplicabilityReason.VERSION_ROLLBACK -> StringKey.LIB_REASON_VERSION_ROLLBACK
}
