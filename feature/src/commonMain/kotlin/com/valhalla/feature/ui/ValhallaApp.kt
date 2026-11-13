package com.valhalla.feature.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.valhalla.core.automation.AutomationTrigger
import com.valhalla.core.config.ParamCatalog
import com.valhalla.core.firmware.FirmwareLibrarySort
import com.valhalla.core.logging.formatAbsoluteTime
import com.valhalla.core.profile.DeviceProfile
import com.valhalla.core.profile.OtaHistoryRecord
import com.valhalla.core.profile.OtaOutcome
import com.valhalla.core.storage.StorageMigrationStatus
import com.valhalla.data.ble.BleAdapterState
import com.valhalla.data.ble.BleClient
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.createBleClient
import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.settings.PreferencesSchema
import com.valhalla.data.settings.createAppSettingsStore
import com.valhalla.data.share.currentTimeZoneOffsetMinutes
import com.valhalla.feature.ble.BleAvailability
import com.valhalla.feature.ble.BleGuidance
import com.valhalla.feature.ble.BleGuidanceAction
import com.valhalla.feature.ble.DisconnectAction
import com.valhalla.feature.ble.DisconnectInfo
import com.valhalla.feature.ble.DisconnectSignals
import com.valhalla.feature.ble.ReconnectPhase
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
import com.valhalla.feature.device.ManagedDeviceInfo
import com.valhalla.feature.device.MultiDeviceState
import com.valhalla.feature.error.ErrorReporter
import com.valhalla.feature.error.LocalErrorReporter
import com.valhalla.feature.error.installUncaughtExceptionBridge
import com.valhalla.feature.error.launchSafely
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.i18n.render
import com.valhalla.feature.i18n.resolveLanguage
import com.valhalla.feature.metrics.RssiDegradedReason
import com.valhalla.feature.metrics.RssiSource
import com.valhalla.feature.notification.NotificationCenter
import com.valhalla.feature.notification.NotificationDecision
import com.valhalla.feature.notification.NotificationEvent
import com.valhalla.feature.notification.NotificationEventType
import com.valhalla.feature.notification.NotificationPage
import com.valhalla.feature.notification.NotificationPolicy
import com.valhalla.feature.notification.NotificationTarget
import com.valhalla.feature.notification.rememberNotificationPresenter
import com.valhalla.feature.onboarding.OnboardingController
import com.valhalla.feature.onboarding.OnboardingStep
import com.valhalla.feature.param.ParamEditorController
import com.valhalla.feature.param.ParamEditorState
import com.valhalla.feature.param.ParamFieldState
import com.valhalla.feature.scan.RssiBand
import com.valhalla.feature.scan.ScanController
import com.valhalla.feature.scan.ScanSortMode
import com.valhalla.feature.scan.ScanUiState
import com.valhalla.feature.settings.AppSettings
import com.valhalla.feature.settings.AppSettingsRepository
import com.valhalla.feature.theme.AppPreferenceStore
import com.valhalla.feature.theme.DesignTokens
import com.valhalla.feature.theme.ValhallaTheme
import com.valhalla.feature.theme.statusColors
import com.valhalla.feature.uilogic.ConfigFieldStatusKind
import com.valhalla.feature.uilogic.configFieldActions
import com.valhalla.feature.uilogic.configFieldStatus
import com.valhalla.feature.uilogic.disconnectReconnectHint
import com.valhalla.feature.uilogic.formatUptime
import com.valhalla.feature.uilogic.label
import com.valhalla.feature.uilogic.paramDetailLine
import com.valhalla.feature.uilogic.paramRangeLabel
import com.valhalla.feature.uilogic.paramReloadEnabled
import com.valhalla.feature.uilogic.paramSupportingText
import com.valhalla.feature.uilogic.paramTypeKey
import com.valhalla.feature.uilogic.rssiBandLabel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Compose Multiplatform 根界面：扫描 → 设备详情 → OTA 三段式流程。
 * Android / iOS 共用同一套页面，平台差异封装在 [createBleClient] 之后。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ValhallaApp(startRoute: String? = null) {
    val settingsStore = remember { createAppSettingsStore() }
    val preferencesMigrationStatus = remember { PreferencesSchema.migrate(settingsStore) }
    var preferences by remember { mutableStateOf(AppPreferenceStore.load(settingsStore)) }
    var appSettings by remember { mutableStateOf(AppSettingsRepository.load(settingsStore)) }
    val language = resolveLanguage(preferences.language, Locale.current.language)
    val strings = remember(language) { StringResolver(language) }
    val showOnboardingOnLaunch =
        remember {
            OnboardingController.shouldShowOnLaunch(settingsStore)
        }
    val errorReporter =
        remember {
            ErrorReporter().also { installUncaughtExceptionBridge(it) }
        }

    val uiCallbacks =
        ValhallaUiCallbacks(
            preferences = preferences,
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
            onReloadPersisted = {
                preferences = AppPreferenceStore.load(settingsStore)
                appSettings = AppSettingsRepository.load(settingsStore)
            },
        )

    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalErrorReporter provides errorReporter,
    ) {
        ValhallaTheme(preferences.theme) {
            ValhallaContent(
                callbacks = uiCallbacks,
                settings = appSettings,
                showOnboardingOnLaunch = showOnboardingOnLaunch,
                startRoute = startRoute,
                onboardingStore = settingsStore,
                preferencesMigrationStatus = preferencesMigrationStatus,
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun ValhallaContent(
    callbacks: ValhallaUiCallbacks,
    settings: AppSettings,
    showOnboardingOnLaunch: Boolean,
    startRoute: String?,
    onboardingStore: com.valhalla.data.settings.AppSettingsStore,
    preferencesMigrationStatus: com.valhalla.core.storage.StorageMigrationStatus,
) {
    val bleClient = remember { createBleClient() }
    val permissionController = rememberBlePermissionController(bleClient)
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val errorReporter = LocalErrorReporter.current
    val repositories =
        rememberValhallaRepositories(onboardingStore, preferencesMigrationStatus)
    val ui = rememberValhallaUiState(showOnboardingOnLaunch, repositories.startupNotice)
    val sessionsRef = rememberUpdatedState(ui.sessions)
    val stringsRef = rememberUpdatedState(strings)
    val controllers =
        rememberValhallaControllers(
            bleClient,
            scope,
            errorReporter,
            repositories.profileRepository,
            repositories.firmwareLibrary,
            sessionsRef,
            stringsRef,
        )
    val ops =
        ValhallaSessionOps(
            ui = ui,
            services =
                ValhallaSessionServices(
                    scope = scope,
                    errorReporter = errorReporter,
                    bleClient = bleClient,
                    deviceManager = controllers.deviceManager,
                    profileRepository = repositories.profileRepository,
                    checkpointStore = repositories.checkpointStore,
                    eventStore = repositories.eventStore,
                    automationRepository = repositories.automationRepository,
                    presetRepository = repositories.presetRepository,
                    settings = settings,
                    strings = strings,
                ),
        )
    val deviceState by controllers.deviceManager.state.collectAsState()
    val lockedIds by controllers.deviceLocks.locked.collectAsState()
    val permissionState by permissionController.state.collectAsState()
    val adapterState by bleClient.adapterState.collectAsState()
    val availability =
        remember(adapterState, permissionState) {
            resolveBleAvailability(adapterState, permissionState)
        }
    val bleEnv = ValhallaBleEnv(permissionController, permissionState, adapterState, availability)
    val notificationPresenter = rememberNotificationPresenter()
    val notificationCenter =
        remember {
            NotificationCenter(nowMillis = { Clock.System.now().toEpochMilliseconds() })
        }
    val notificationEnv = ValhallaNotificationEnv(notificationPresenter, notificationCenter)
    LaunchedEffect(startRoute) {
        val target = NotificationTarget.fromWire(startRoute) ?: return@LaunchedEffect
        notificationTargetRoute(target, ui.sessions.keys)?.let { ui.route = it }
    }
    ReconnectExhaustedEffect(ui.reconnect, settings, notificationEnv) { ui.notificationBanner = it }
    ReconnectBluetoothEffect(ui.reconnect, adapterState)
    ValhallaDisposeEffect(ui.reconnect, controllers.scanController, bleClient)

    Box(modifier = Modifier.fillMaxSize()) {
        ValhallaRouteHost(
            ui,
            ops,
            repositories,
            controllers,
            deviceState,
            ValhallaRuntimeEnv(bleEnv, notificationEnv, settings, lockedIds),
            callbacks,
        )
        ValhallaStorageNotice(ui)
        ValhallaNotificationBanner(ui)
    }
}

@Composable
private fun ReconnectExhaustedEffect(
    reconnect: ReconnectSession?,
    settings: AppSettings,
    notificationEnv: ValhallaNotificationEnv,
    onBanner: (NotificationEvent) -> Unit,
) {
    val strings = LocalStrings.current
    // 自动重连耗尽：仅在 App 存活期间提醒；权限缺失时降级为应用内横幅。
    val reconnectSnapshot = reconnect
    val reconnectPhase =
        reconnectSnapshot
            ?.controller
            ?.state
            ?.collectAsState()
            ?.value
            ?.phase
    LaunchedEffect(reconnectPhase, reconnectSnapshot) {
        if (reconnectPhase != ReconnectPhase.EXHAUSTED || reconnectSnapshot == null) {
            return@LaunchedEffect
        }
        if (!NotificationPolicy.shouldNotifyReconnectExhausted(settings)) return@LaunchedEffect
        val event =
            NotificationEvent(
                type = NotificationEventType.RECONNECT_EXHAUSTED,
                title = strings.t(StringKey.NOTIFICATION_RECONNECT_EXHAUSTED_TITLE),
                body =
                    strings.t(
                        StringKey.NOTIFICATION_RECONNECT_EXHAUSTED_BODY,
                        "device" to (reconnectSnapshot.device.name ?: reconnectSnapshot.device.deviceId),
                        "attempts" to settings.reconnectMaxAttempts.toString(),
                    ),
                target = NotificationTarget(NotificationPage.DETAIL, reconnectSnapshot.device.deviceId),
                deviceId = reconnectSnapshot.device.deviceId,
            )
        val decision =
            notificationEnv.center.dispatch(
                event = event,
                enabled = true,
                canNotify = notificationEnv.presenter.canNotify,
            ) { title, body, target -> notificationEnv.presenter.show(title, body, target) }
        if (decision == NotificationDecision.FALLBACK_BANNER) {
            onBanner(event)
        }
    }
}

@Composable
private fun ReconnectBluetoothEffect(
    reconnect: ReconnectSession?,
    adapterState: BleAdapterState,
) {
    // 蓝牙开关变化时暂停/恢复自动重连。
    LaunchedEffect(adapterState) {
        reconnect?.controller?.onBluetoothStateChanged(
            available = adapterState == BleAdapterState.POWERED_ON,
        )
    }
}

@Composable
private fun ValhallaDisposeEffect(
    reconnect: ReconnectSession?,
    scanController: ScanController,
    bleClient: BleClient,
) {
    DisposableEffect(Unit) {
        onDispose {
            reconnect?.controller?.cancel()
            scanController.stopScan()
            bleClient.close()
        }
    }
}

private class ValhallaRuntimeEnv(
    val bleEnv: ValhallaBleEnv,
    val notificationEnv: ValhallaNotificationEnv,
    val settings: AppSettings,
    val lockedIds: Set<String>,
)

@Composable
private fun ValhallaRouteHost(
    ui: ValhallaUiState,
    ops: ValhallaSessionOps,
    repositories: ValhallaRepositories,
    controllers: ValhallaControllers,
    deviceState: MultiDeviceState,
    env: ValhallaRuntimeEnv,
    callbacks: ValhallaUiCallbacks,
) {
    val bleEnv = env.bleEnv
    val notificationEnv = env.notificationEnv
    val settings = env.settings
    val lockedIds = env.lockedIds
    when (val current = ui.route) {
        Route.Scan ->
            ValhallaScanRoute(ui, ops, controllers, bleEnv, repositories, deviceState)
        Route.Devices ->
            ValhallaDevicesRoute(ui, ops, controllers, deviceState)
        Route.Settings ->
            ValhallaSettingsRoute(ui, settings, notificationEnv, callbacks)
        Route.Automation -> ValhallaAutomationRoute(ui, repositories)
        Route.Stats -> ValhallaStatsRoute(ui, repositories)
        Route.Backup -> ValhallaBackupRoute(ui, repositories, callbacks)
        Route.Batch -> ValhallaBatchRoute(ui, repositories, controllers, deviceState)
        Route.OtaQueue -> ValhallaOtaQueueRoute(ui, repositories, controllers, deviceState)
        Route.About -> AboutScreen(onBack = { ui.route = Route.Settings })
        Route.Help -> HelpScreen(onBack = { ui.route = Route.Settings })
        Route.Privacy -> PrivacyScreen(onBack = { ui.route = Route.Settings })
        is Route.Onboarding ->
            ValhallaOnboardingRoute(current, ui, ops, bleEnv, repositories)
        is Route.Detail ->
            ValhallaDetailRoute(
                current,
                ui,
                ops,
                repositories,
                controllers,
                bleEnv,
                notificationEnv,
                lockedIds,
                settings,
            )
        is Route.Ota ->
            ValhallaOtaRoute(current, ui, repositories, notificationEnv, settings, lockedIds, ops)
        is Route.Logs -> ValhallaLogsRoute(current, ui, settings)
        is Route.Diagnostic -> ValhallaDiagnosticRoute(current, ui, repositories)
    }
}

@Composable
private fun ValhallaScanRoute(
    ui: ValhallaUiState,
    ops: ValhallaSessionOps,
    controllers: ValhallaControllers,
    bleEnv: ValhallaBleEnv,
    repositories: ValhallaRepositories,
    deviceState: MultiDeviceState,
) {
    ScanScreen(
        state =
            controllers.scanController.state
                .collectAsState()
                .value,
        availability = bleEnv.availability,
        connectingDeviceId = ui.connectingDeviceId,
        errorMessage = ui.errorMessage,
        deviceCount = deviceState.occupied,
        profileLookup = { id -> repositories.profileRepository.get(id) },
        actions =
            ScanScreenActions(
                onStart = { ops.requestStartScan(bleEnv.availability, controllers.scanController) },
                onStop = { controllers.scanController.stopScan() },
                onSortChange = { controllers.scanController.setSortMode(it) },
                onOpenDevices = { ui.route = Route.Devices },
                onSelect = { ops.connectFromScan(it, bleEnv.availability, controllers.scanController) },
                onBleAction = { ops.handleBleAction(it, bleEnv.permissionController) },
                onOpenSettings = { ui.route = Route.Settings },
            ),
    )
}

@Composable
private fun ValhallaDevicesRoute(
    ui: ValhallaUiState,
    ops: ValhallaSessionOps,
    controllers: ValhallaControllers,
    deviceState: MultiDeviceState,
) {
    DevicesScreen(
        state = deviceState,
        onSwitch = { id ->
            controllers.deviceManager.select(id)
            ui.route = Route.Detail(id)
        },
        onDisconnect = { id -> ui.sessions[id]?.let { ops.disconnectDevice(it) } },
        onDisconnectAll = { ops.disconnectAll() },
        onOpenBatch = { ui.route = Route.Batch },
        onOpenQueue = { ui.route = Route.OtaQueue },
        onBack = { ui.route = Route.Scan },
    )
}

@Composable
private fun ValhallaSettingsRoute(
    ui: ValhallaUiState,
    settings: AppSettings,
    notificationEnv: ValhallaNotificationEnv,
    callbacks: ValhallaUiCallbacks,
) {
    SettingsScreen(
        language = callbacks.preferences.language,
        theme = callbacks.preferences.theme,
        settings = settings,
        notificationPresenter = notificationEnv.presenter,
        actions =
            SettingsScreenActions(
                onLanguageChange = callbacks.onLanguageChange,
                onThemeChange = callbacks.onThemeChange,
                onSettingsChange = callbacks.onSettingsChange,
                onBack = { ui.route = Route.Scan },
                onAbout = { ui.route = Route.About },
                onHelp = { ui.route = Route.Help },
                onPrivacy = { ui.route = Route.Privacy },
                onOnboardingReview = { ui.route = Route.Onboarding(review = true) },
                onOpenAutomation = { ui.route = Route.Automation },
                onOpenStats = { ui.route = Route.Stats },
                onOpenBackup = { ui.route = Route.Backup },
            ),
    )
}

@Composable
private fun ValhallaAutomationRoute(
    ui: ValhallaUiState,
    repositories: ValhallaRepositories,
) {
    AutomationScreen(
        rules = repositories.automationRepository,
        presets = repositories.presetRepository,
        lastDiagnosticReport = ui.lastDiagnosticReport,
        onBack = { ui.route = Route.Settings },
    )
}

@Composable
private fun ValhallaStatsRoute(
    ui: ValhallaUiState,
    repositories: ValhallaRepositories,
) {
    UsageStatsScreen(
        profileRepository = repositories.profileRepository,
        onBack = { ui.route = Route.Settings },
    )
}

@Composable
private fun ValhallaBackupRoute(
    ui: ValhallaUiState,
    repositories: ValhallaRepositories,
    callbacks: ValhallaUiCallbacks,
) {
    BackupScreen(
        store = repositories.onboardingStore,
        presets = repositories.presetRepository,
        rules = repositories.automationRepository,
        onReloadPersisted = callbacks.onReloadPersisted,
        onBack = { ui.route = Route.Settings },
    )
}

@Composable
private fun ValhallaBatchRoute(
    ui: ValhallaUiState,
    repositories: ValhallaRepositories,
    controllers: ValhallaControllers,
    deviceState: MultiDeviceState,
) {
    BatchScreen(
        devices = deviceState.devices.map { BatchDeviceOption(it.deviceId, it.displayName) },
        presets = repositories.presetRepository.list(),
        controller = controllers.batchController,
        onBack = { ui.route = Route.Devices },
        execute = { kind, preset, target, step ->
            executeBatchOperation(kind, preset, target, step, ui.sessions)
        },
    )
}

@Composable
private fun ValhallaOtaQueueRoute(
    ui: ValhallaUiState,
    repositories: ValhallaRepositories,
    controllers: ValhallaControllers,
    deviceState: MultiDeviceState,
) {
    OtaQueueScreen(
        devices = deviceState.devices.map { BatchDeviceOption(it.deviceId, it.displayName) },
        firmwareRecords = repositories.firmwareLibrary.list(FirmwareLibrarySort.RECENT),
        controller = controllers.queueController,
        onBack = { ui.route = Route.Devices },
    )
}

@Composable
private fun ValhallaOnboardingRoute(
    current: Route.Onboarding,
    ui: ValhallaUiState,
    ops: ValhallaSessionOps,
    bleEnv: ValhallaBleEnv,
    repositories: ValhallaRepositories,
) {
    OnboardingScreen(
        reviewMode = current.review,
        store = repositories.onboardingStore,
        availability = bleEnv.availability,
        onBleAction = { ops.handleBleAction(it, bleEnv.permissionController) },
        onFinished = {
            ui.route = if (current.review) Route.Settings else Route.Scan
        },
    )
}

@Composable
private fun ValhallaDetailRoute(
    current: Route.Detail,
    ui: ValhallaUiState,
    ops: ValhallaSessionOps,
    repositories: ValhallaRepositories,
    controllers: ValhallaControllers,
    bleEnv: ValhallaBleEnv,
    notificationEnv: ValhallaNotificationEnv,
    lockedIds: Set<String>,
    settings: AppSettings,
) {
    val session = ui.sessions[current.deviceId]
    if (session == null) {
        LaunchedEffect(current.deviceId) { ui.route = Route.Devices }
        return
    }
    val connected by session.connection.connected.collectAsState()
    val disconnectInfo =
        if (!connected) {
            classifyDisconnect(
                DisconnectSignals(
                    userRequested = session.userDisconnected,
                    peerTerminated = !session.userDisconnected,
                    adapterState = bleEnv.adapterState,
                    permissionState = bleEnv.permissionState,
                ),
            )
        } else {
            null
        }
    DetailScreen(
        session = session,
        settings = settings,
        metrics = repositories.metrics,
        presets = repositories.presetRepository,
        notificationPresenter = notificationEnv.presenter,
        notificationCenter = notificationEnv.center,
        onNotificationFallback = { ui.notificationBanner = it },
        connectedCount = controllers.deviceManager.state.value.occupied,
        disconnectInfo = disconnectInfo,
        reconnectState =
            ui.reconnect
                ?.takeIf { it.device.deviceId == session.device.deviceId }
                ?.controller
                ?.state
                ?.collectAsState()
                ?.value,
        onConnectionLost = {
            if (!session.userDisconnected) ops.startReconnect(session)
        },
        onRetryReconnect = { ops.startReconnect(session) },
        onCancelReconnect = { ops.cancelReconnect() },
        onBleAction = { ops.handleBleAction(it, bleEnv.permissionController) },
        onOpenDevices = { ui.route = Route.Devices },
        onBack = { ops.disconnectAndLeave(session, current.deviceId) },
        onOpenOta = { ui.route = Route.Ota(current.deviceId) },
        onOpenLogs = { ui.route = Route.Logs(current.deviceId) },
        onOpenDiagnostics = { ui.route = Route.Diagnostic(current.deviceId) },
        writeLocked = current.deviceId in lockedIds,
    )
}

@Composable
private fun ValhallaOtaRoute(
    current: Route.Ota,
    ui: ValhallaUiState,
    repositories: ValhallaRepositories,
    notificationEnv: ValhallaNotificationEnv,
    settings: AppSettings,
    lockedIds: Set<String>,
    ops: ValhallaSessionOps,
) {
    val session = ui.sessions[current.deviceId]
    if (session == null) {
        LaunchedEffect(current.deviceId) { ui.route = Route.Devices }
        return
    }
    OtaScreen(
        session = session,
        library = repositories.firmwareLibrary,
        settings = settings,
        notificationPresenter = notificationEnv.presenter,
        notificationCenter = notificationEnv.center,
        onNotificationFallback = { ui.notificationBanner = it },
        onBack = { ui.route = Route.Detail(current.deviceId) },
        onOtaSucceeded = { ops.triggerAutomation(AutomationTrigger.OTA_SUCCESS, session) },
        writeLocked = current.deviceId in lockedIds,
    )
}

@Composable
private fun ValhallaLogsRoute(
    current: Route.Logs,
    ui: ValhallaUiState,
    settings: AppSettings,
) {
    val session = ui.sessions[current.deviceId]
    if (session == null) {
        LaunchedEffect(current.deviceId) { ui.route = Route.Devices }
        return
    }
    PacketLogScreen(
        packetLog = session.packetLog,
        initialOptions = settings.exportOptions(),
        onBack = { ui.route = Route.Detail(current.deviceId) },
    )
}

@Composable
private fun ValhallaDiagnosticRoute(
    current: Route.Diagnostic,
    ui: ValhallaUiState,
    repositories: ValhallaRepositories,
) {
    val session = ui.sessions[current.deviceId]
    if (session == null) {
        LaunchedEffect(current.deviceId) { ui.route = Route.Devices }
        return
    }
    DiagnosticScreen(
        session = session,
        metrics = repositories.metrics,
        onBack = { ui.route = Route.Detail(current.deviceId) },
    )
}

@Composable
private fun BoxScope.ValhallaStorageNotice(ui: ValhallaUiState) {
    val strings = LocalStrings.current
    ui.storageNotice?.let { notice ->
        Card(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(DesignTokens.Spacing.lg),
        ) {
            Column(
                modifier = Modifier.padding(DesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
            ) {
                Text(
                    strings.t(StringKey.STORAGE_RECOVERY_TITLE),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(notice, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { ui.storageNotice = null }) {
                    Text(strings.t(StringKey.CLOSE))
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ValhallaNotificationBanner(ui: ValhallaUiState) {
    val strings = LocalStrings.current
    ui.notificationBanner?.let { event ->
        Card(
            modifier =
                Modifier
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
                    TextButton(onClick = { ui.notificationBanner = null }) {
                        Text(strings.t(StringKey.CLOSE))
                    }
                    val target = notificationTargetRoute(event.target, ui.sessions.keys)
                    if (target != null) {
                        TextButton(
                            onClick = {
                                ui.route = target
                                ui.notificationBanner = null
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

/** 扫描页的动作集合：扫描启停、排序、跳转与设备选择。 */
private class ScanScreenActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onSortChange: (ScanSortMode) -> Unit,
    val onOpenDevices: () -> Unit,
    val onSelect: (BleDevice) -> Unit,
    val onBleAction: (BleGuidanceAction) -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
private fun ScanScreen(
    state: ScanUiState,
    availability: BleAvailability,
    connectingDeviceId: String?,
    errorMessage: UiMessage?,
    deviceCount: Int,
    profileLookup: (String) -> DeviceProfile?,
    actions: ScanScreenActions,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds()) }
    val canScan = availability.allowsConnection()
    val guidance = availability.guidance()
    val emptyReason =
        classifyScanEmpty(
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
        ScanHeaderRow(strings, deviceCount, actions.onOpenDevices, actions.onOpenSettings)
        Text(
            strings.t(StringKey.SCAN_HINT),
            style = MaterialTheme.typography.bodySmall,
        )

        guidance?.let { guide ->
            ScanGuidanceCard(strings, guide, actions.onBleAction)
        }

        ScanActionsRow(strings, canScan, state.scanning, actions.onStart, actions.onStop)

        if (!canScan) {
            DisabledStatePanel(reason = StringKey.STATE_UNAVAILABLE_HINT)
        }

        ScanSortRow(strings, state.sortMode, actions.onSortChange)

        if (state.scanning) {
            LoadingStatePanel(body = strings.t(StringKey.SCAN_EMPTY_SCANNING))
        }

        errorMessage?.let { message ->
            ErrorStatePanel(detail = strings.render(message))
        }

        ScanEmptyPanels(
            devicesEmpty = state.devices.isEmpty(),
            scanning = state.scanning,
            errorMessage = errorMessage,
            stateError = state.error,
            emptyReason = emptyReason,
            strings = strings,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            scanDeviceItems(
                devices = state.devices,
                strings = strings,
                format = format,
                zoneOffsetMinutes = zoneOffsetMinutes,
                profileLookup = profileLookup,
                canScan = canScan,
                onSelect = actions.onSelect,
            )
        }

        connectingDeviceId?.let {
            Text(
                strings.t(StringKey.SCAN_CONNECTING, "device" to it),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ScanHeaderRow(
    strings: StringResolver,
    deviceCount: Int,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
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
}

@Composable
private fun ScanGuidanceCard(
    strings: StringResolver,
    guide: BleGuidance,
    onBleAction: (BleGuidanceAction) -> Unit,
) {
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

@Composable
private fun ScanActionsRow(
    strings: StringResolver,
    canScan: Boolean,
    scanning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        Button(
            onClick = onStart,
            enabled = canScan && !scanning,
            modifier = Modifier.semantics { contentDescription = strings.t(StringKey.START_SCAN) },
        ) {
            Text(strings.t(StringKey.START_SCAN))
        }
        OutlinedButton(
            onClick = onStop,
            enabled = scanning,
            modifier = Modifier.semantics { contentDescription = strings.t(StringKey.STOP_SCAN) },
        ) {
            Text(strings.t(StringKey.STOP_SCAN))
        }
    }
}

@Composable
private fun ScanSortRow(
    strings: StringResolver,
    sortMode: ScanSortMode,
    onSortChange: (ScanSortMode) -> Unit,
) {
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
            selected = sortMode == ScanSortMode.SIGNAL,
            onClick = { onSortChange(ScanSortMode.SIGNAL) },
            label = { Text(strings.t(StringKey.SCAN_SORT_SIGNAL)) },
        )
        FilterChip(
            selected = sortMode == ScanSortMode.NAME,
            onClick = { onSortChange(ScanSortMode.NAME) },
            label = { Text(strings.t(StringKey.SCAN_SORT_NAME)) },
        )
        FilterChip(
            selected = sortMode == ScanSortMode.LAST_CONNECTED,
            onClick = { onSortChange(ScanSortMode.LAST_CONNECTED) },
            label = { Text(strings.t(StringKey.SCAN_SORT_LAST_CONNECTED)) },
        )
    }
}

@Composable
private fun ScanEmptyPanels(
    devicesEmpty: Boolean,
    scanning: Boolean,
    errorMessage: UiMessage?,
    stateError: UiMessage?,
    emptyReason: ScanEmptyReason,
    strings: StringResolver,
) {
    if (devicesEmpty && errorMessage == null && !scanning) {
        val emptyText = emptyReasonText(emptyReason, strings)
        if (stateError != null) {
            ErrorStatePanel(detail = strings.render(stateError))
        } else if (emptyText != null) {
            EmptyStatePanel(body = emptyText)
        }
    }
}

@Composable
private fun ScanDeviceItem(
    device: BleDevice,
    strings: StringResolver,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
    profile: DeviceProfile?,
    canScan: Boolean,
    onSelect: (BleDevice) -> Unit,
) {
    val band = RssiBand.of(device.rssi)
    val bandLabel = rssiBandLabel(band, strings)
    val itemDescription =
        buildString {
            append(device.name ?: strings.t(StringKey.UNNAMED_DEVICE))
            append(", ")
            append(bandLabel)
            if (profile != null) {
                append(", ")
                append(strings.t(StringKey.SCAN_PROFILE_MARK))
            }
        }
    Card(
        modifier =
            Modifier
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
                            "time" to
                                format.absoluteTime(
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

private fun LazyListScope.scanDeviceItems(
    devices: List<BleDevice>,
    strings: StringResolver,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
    profileLookup: (String) -> DeviceProfile?,
    canScan: Boolean,
    onSelect: (BleDevice) -> Unit,
) {
    items(devices, key = { it.deviceId }) { device ->
        ScanDeviceItem(
            device = device,
            strings = strings,
            format = format,
            zoneOffsetMinutes = zoneOffsetMinutes,
            profile = profileLookup(device.deviceId),
            canScan = canScan,
            onSelect = onSelect,
        )
    }
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
            EmptyStatePanel(body = strings.t(StringKey.DEVICES_EMPTY))
            DisabledStatePanel(reason = StringKey.STATE_DISABLED_NOT_CONNECTED)
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
internal fun DisconnectBanner(
    info: DisconnectInfo,
    reconnectState: ReconnectState?,
    onRetryReconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
    onBleAction: (BleGuidanceAction) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val reconnectRunning =
        reconnectState?.phase == ReconnectPhase.WAITING ||
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

            DisconnectReconnectHint(strings, reconnectState)

            DisconnectActionsRow(
                strings = strings,
                reconnectRunning = reconnectRunning,
                info = info,
                onRetryReconnect = onRetryReconnect,
                onCancelReconnect = onCancelReconnect,
                onBleAction = onBleAction,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun DisconnectReconnectHint(
    strings: StringResolver,
    reconnectState: ReconnectState?,
) {
    val rs = reconnectState ?: return
    disconnectReconnectHint(rs, strings)?.let { hint ->
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DisconnectActionsRow(
    strings: StringResolver,
    reconnectRunning: Boolean,
    info: DisconnectInfo,
    onRetryReconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
    onBleAction: (BleGuidanceAction) -> Unit,
    onBack: () -> Unit,
) {
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
        DisconnectActionButtons(strings, info.action, onBleAction, onBack)
    }
}

@Composable
private fun DisconnectActionButtons(
    strings: StringResolver,
    action: DisconnectAction,
    onBleAction: (BleGuidanceAction) -> Unit,
    onBack: () -> Unit,
) {
    when (action) {
        DisconnectAction.OPEN_SETTINGS -> {
            Button(onClick = { onBleAction(BleGuidanceAction.OPEN_SETTINGS) }) {
                Text(strings.t(StringKey.ACTION_OPEN_SETTINGS))
            }
            OutlinedButton(onClick = onBack) {
                Text(strings.t(StringKey.ACTION_RESCAN))
            }
        }

        DisconnectAction.ENABLE_BLUETOOTH ->
            Button(
                onClick = { onBleAction(BleGuidanceAction.ENABLE_BLUETOOTH) },
            ) { Text(strings.t(StringKey.ACTION_ENABLE_BLUETOOTH)) }

        DisconnectAction.RESCAN ->
            OutlinedButton(onClick = onBack) {
                Text(strings.t(StringKey.ACTION_RESCAN))
            }

        DisconnectAction.RECONNECT,
        DisconnectAction.NONE,
        -> Unit
    }
}

internal const val TIMELINE_VISIBLE_LIMIT = 20
internal const val LIBRARY_VISIBLE_LIMIT = 8

@Composable
internal fun DeviceDashboardCard(ui: DeviceDashboardCardUi) {
    val strings = ui.strings
    val detail = ui.detail
    val series = ui.series
    val rssi = ui.rssi
    val rssiSource = ui.rssiSource
    val degradedReason = ui.degradedReason
    val legacyMetricsIgnored = ui.legacyMetricsIgnored
    val observedAtMillis = ui.observedAtMillis
    val zoneOffsetMinutes = ui.zoneOffsetMinutes
    val accent = ui.accent
    val muted = ui.muted
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
                    val band =
                        strings.t(
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
                        "source" to
                            strings.t(
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
                        "reason" to
                            strings.t(
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
            DeviceDashboardTrend(ui)
        }
    }
}

@Composable
private fun DeviceDashboardTrend(ui: DeviceDashboardCardUi) {
    val strings = ui.strings
    val series = ui.series
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
                color = ui.accent,
            )
        }
        if (series.canDrawRssi) {
            TrendLine(
                values = series.rssiPoints.map { it.second.toFloat() },
                minValue = -100f,
                maxValue = -30f,
                color = ui.accent,
            )
        }
    } else {
        Text(
            strings.t(StringKey.DASH_TREND_INSUFFICIENT),
            style = MaterialTheme.typography.bodySmall,
            color = ui.muted,
        )
    }
}

@Composable
private fun DashboardRow(
    label: String,
    value: String,
) {
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
        val points =
            values.mapIndexed { index, value ->
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProfileCard(
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
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = {})
                .semantics { contentDescription = record.firmwareVersion }
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
internal fun ConfigSection(
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
            if (state.loading) {
                LoadingStatePanel(body = strings.t(StringKey.CONFIG_FIELD_LOADING))
            }
            if (writeLocked) {
                DisabledStatePanel(reason = StringKey.STATE_DISABLED_READ_ONLY)
            }
            if (state.saving) {
                DisabledStatePanel(reason = StringKey.STATE_DISABLED_BUSY)
            }
            if (writeLocked) {
                Text(
                    strings.t(StringKey.BATCH_LOCKED_HINT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            ConfigCapabilityNotice(state, onRefreshDevice)
            if (state.visibleFields.isEmpty() && !state.loading && state.error == null) {
                EmptyStatePanel()
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
                ErrorStatePanel(detail = strings.render(message))
            }
        }
    }
}

@Composable
private fun ConfigCapabilityNotice(
    state: ConfigEditorState,
    onRefreshDevice: () -> Unit,
) {
    val strings = LocalStrings.current
    val noticeKey = state.capabilityNoticeKey ?: return
    val notice =
        if (noticeKey == StringKey.CONFIG_HIDDEN_UNKNOWN_TARGET) {
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

@Composable
private fun ConfigFieldRow(
    field: ConfigFieldState,
    savingAny: Boolean,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val fieldStatus =
        configFieldStatus(
            loading = field.loading,
            loadError = field.loadError,
            validationError = field.validationError,
            readOnlyReason = field.spec.readOnlyReason,
            saving = field.saving,
            lastSavedAtEpochMillis = field.lastSavedAtEpochMillis,
            dirty = field.dirty,
        )
    val supporting =
        when (fieldStatus.kind) {
            ConfigFieldStatusKind.LOADING -> strings.t(StringKey.CONFIG_FIELD_LOADING)
            ConfigFieldStatusKind.LOAD_ERROR,
            ConfigFieldStatusKind.VALIDATION_ERROR,
            ->
                strings.t(StringKey.TECHNICAL_DETAIL, "detail" to fieldStatus.detail!!)
            ConfigFieldStatusKind.READ_ONLY ->
                strings.t(StringKey.CONFIG_FIELD_READ_ONLY) +
                    (
                        fieldStatus.detail?.let {
                            " · " + strings.t(StringKey.TECHNICAL_DETAIL, "detail" to it)
                        } ?: ""
                    )
            ConfigFieldStatusKind.SAVING -> strings.t(StringKey.CONFIG_FIELD_SAVING)
            ConfigFieldStatusKind.SAVED -> strings.t(StringKey.CONFIG_FIELD_SAVED)
            ConfigFieldStatusKind.DIRTY -> strings.t(StringKey.CONFIG_FIELD_UNSAVED)
            ConfigFieldStatusKind.EDITABLE -> strings.t(StringKey.CONFIG_FIELD_EDITABLE)
        }
    val actions =
        configFieldActions(
            editable = field.editable,
            dirty = field.dirty,
            saving = field.saving,
            savingAny = savingAny,
        )
    val isError = field.loadError != null || field.validationError != null
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
            OutlinedTextField(
                value = field.text,
                onValueChange = onTextChange,
                enabled = actions.canEditText,
                label = { Text(field.spec.key.toString()) },
                singleLine = true,
                supportingText = {
                    Text(
                        supporting,
                        color = if (isError) status.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            if (isError) {
                                Modifier.semantics {
                                    liveRegion = LiveRegionMode.Assertive
                                    contentDescription = supporting
                                }
                            } else {
                                Modifier
                            },
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onSave,
                enabled = actions.canSave,
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

/**
 * 设备参数面板。
 *
 * 参数表来自 [ParamCatalog] 的约定值，界面在标题区固定标注「需固件确认」，
 * 不允许把未确认的参数表包装成设备能力。
 */
@Composable
internal fun ParamSection(
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
                    enabled = paramReloadEnabled(connected, state.busy),
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
            if (state.loading) {
                LoadingStatePanel(body = strings.t(StringKey.PARAM_LOADING))
            }
            if (!connected) {
                DisabledStatePanel(reason = StringKey.STATE_DISABLED_NOT_CONNECTED)
            } else if (writeLocked) {
                DisabledStatePanel(reason = StringKey.STATE_DISABLED_READ_ONLY)
            }
            if (state.busy && !state.loading) {
                DisabledStatePanel(reason = StringKey.STATE_DISABLED_BUSY)
            }
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

            if (state.fields.isEmpty() && !state.loading && state.error == null) {
                EmptyStatePanel()
            }
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
                ErrorStatePanel(detail = strings.render(message))
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
    val rangeLabel = paramRangeLabel(spec, strings)
    val detailLine = paramDetailLine(spec, typeLabel, rangeLabel, strings)
    val supporting = paramSupportingText(field, spec, strings)
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
                        modifier =
                            if (isError) {
                                Modifier.semantics {
                                    liveRegion = LiveRegionMode.Assertive
                                    contentDescription = supporting
                                }
                            } else {
                                Modifier
                            },
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
    val steps =
        listOf(
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
