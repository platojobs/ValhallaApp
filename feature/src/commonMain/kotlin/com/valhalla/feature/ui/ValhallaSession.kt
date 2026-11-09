package com.valhalla.feature.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.valhalla.core.automation.AutomationRuleRepository
import com.valhalla.core.automation.AutomationTrigger
import com.valhalla.core.batch.BatchItemStatus
import com.valhalla.core.batch.BatchOperationKind
import com.valhalla.core.batch.BatchTarget
import com.valhalla.core.batch.DeviceOperationLocks
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.command.PacketRecordingFrameLink
import com.valhalla.core.config.ConfigPreset
import com.valhalla.core.config.ConfigPresetDiffer
import com.valhalla.core.config.ConfigPresetRepository
import com.valhalla.core.config.DeviceConfigTarget
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventStore
import com.valhalla.core.event.DeviceEventTimeline
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.firmware.FirmwareLibraryRepository
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.logging.InMemoryPacketLog
import com.valhalla.core.metrics.DeviceMetricRepository
import com.valhalla.core.ota.OtaQueueItem
import com.valhalla.core.ota.OtaQueueStage
import com.valhalla.core.profile.DeviceProfileRepository
import com.valhalla.core.storage.StorageMigrationStatus
import com.valhalla.data.ble.BleAdapterState
import com.valhalla.data.ble.BleClient
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.BleFrameLink
import com.valhalla.data.event.createDeviceEventStore
import com.valhalla.data.firmware.createFirmwareLibraryStore
import com.valhalla.data.firmware.createOtaCheckpointStore
import com.valhalla.data.metrics.createDeviceMetricStore
import com.valhalla.data.profile.createDeviceProfileStore
import com.valhalla.data.security.createTokenStore
import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.feature.automation.AutomationContext
import com.valhalla.feature.automation.AutomationRuleRunner
import com.valhalla.feature.batch.BatchDeviceOutcome
import com.valhalla.feature.batch.BatchOperationController
import com.valhalla.feature.ble.AutoReconnectController
import com.valhalla.feature.ble.BleAvailability
import com.valhalla.feature.ble.BleGuidanceAction
import com.valhalla.feature.ble.BlePermissionController
import com.valhalla.feature.ble.BlePermissionState
import com.valhalla.feature.ble.ReconnectPhase
import com.valhalla.feature.ble.ReconnectPolicy
import com.valhalla.feature.ble.allowsConnection
import com.valhalla.feature.ble.guidance
import com.valhalla.feature.config.ConfigEditorController
import com.valhalla.feature.config.ConfigPresetApplier
import com.valhalla.feature.detail.DeviceDetailController
import com.valhalla.feature.device.MultiDeviceConnectResult
import com.valhalla.feature.device.MultiDeviceManager
import com.valhalla.feature.error.ErrorReporter
import com.valhalla.feature.error.launchSafely
import com.valhalla.feature.firmware.FirmwarePicker
import com.valhalla.feature.firmware.FirmwarePrecheckFactory
import com.valhalla.feature.firmware.FirmwareSelectionController
import com.valhalla.feature.firmware.rememberFirmwarePicker
import com.valhalla.feature.firmware.rememberFirmwareSigningKeyPem
import com.valhalla.feature.health.DeviceHealthCheckController
import com.valhalla.feature.i18n.AppLanguageMode
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.i18n.render
import com.valhalla.feature.notification.NotificationCenter
import com.valhalla.feature.notification.NotificationEvent
import com.valhalla.feature.notification.NotificationPresenter
import com.valhalla.feature.ota.OtaController
import com.valhalla.feature.ota.OtaQueueController
import com.valhalla.feature.ota.OtaQueueExecutionResult
import com.valhalla.feature.ota.OtaQueueExecutor
import com.valhalla.feature.ota.OtaState
import com.valhalla.feature.param.ParamEditorController
import com.valhalla.feature.scan.ScanController
import com.valhalla.feature.settings.AppSettings
import com.valhalla.feature.settings.PreferencesAutomationRuleStore
import com.valhalla.feature.settings.PreferencesConfigPresetStore
import com.valhalla.feature.storage.StartupRecovery
import com.valhalla.feature.theme.ThemeMode
import com.valhalla.feature.uilogic.editableLoadedConfigFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** 一次已建立连接对应的协议栈与控制器，详情页与 OTA 页共享同一调度器与断点存储。 */
internal class DeviceSession(
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
internal class ReconnectSession(
    val device: BleDevice,
    val packetLog: InMemoryPacketLog,
    val controller: AutoReconnectController,
)

internal sealed interface Route {
    data object Scan : Route

    data object Devices : Route

    data object Settings : Route

    data object About : Route

    data object Help : Route

    data object Privacy : Route

    data class Onboarding(
        val review: Boolean,
    ) : Route

    data class Detail(
        val deviceId: String,
    ) : Route

    data class Ota(
        val deviceId: String,
    ) : Route

    data class Logs(
        val deviceId: String,
    ) : Route

    data class Diagnostic(
        val deviceId: String,
    ) : Route

    data object Automation : Route

    data object Stats : Route

    data object Backup : Route

    data object Batch : Route

    data object OtaQueue : Route
}

internal class ValhallaRepositories(
    val profileRepository: DeviceProfileRepository,
    val checkpointStore: OtaCheckpointStore,
    val eventStore: DeviceEventStore,
    val firmwareLibrary: FirmwareLibraryRepository,
    val metrics: DeviceMetricRepository,
    val presetRepository: ConfigPresetRepository,
    val automationRepository: AutomationRuleRepository,
    val onboardingStore: AppSettingsStore,
    val startupNotice: String?,
)

internal class ValhallaControllers(
    val scanController: ScanController,
    val deviceManager: MultiDeviceManager,
    val deviceLocks: DeviceOperationLocks,
    val batchController: BatchOperationController,
    val queueController: OtaQueueController,
)

internal class ValhallaUiState(
    initialRoute: Route,
    initialStorageNotice: String?,
) {
    var sessions by mutableStateOf<Map<String, DeviceSession>>(emptyMap())
    var route by mutableStateOf(initialRoute)
    var connectingDeviceId by mutableStateOf<String?>(null)
    var errorMessage by mutableStateOf<UiMessage?>(null)
    var reconnect by mutableStateOf<ReconnectSession?>(null)
    var storageNotice by mutableStateOf(initialStorageNotice)
    var notificationBanner by mutableStateOf<NotificationEvent?>(null)
    var lastDiagnosticReport by mutableStateOf<String?>(null)
}

internal class ValhallaBleEnv(
    val permissionController: BlePermissionController,
    val permissionState: BlePermissionState,
    val adapterState: BleAdapterState,
    val availability: BleAvailability,
)

internal class ValhallaNotificationEnv(
    val presenter: NotificationPresenter,
    val center: NotificationCenter,
)

internal class ValhallaUiCallbacks(
    val preferences: com.valhalla.feature.theme.AppPreferences,
    val onLanguageChange: (AppLanguageMode) -> Unit,
    val onThemeChange: (ThemeMode) -> Unit,
    val onSettingsChange: (AppSettings) -> Unit,
    val onReloadPersisted: () -> Unit,
)

@Composable
internal fun rememberValhallaRepositories(
    onboardingStore: AppSettingsStore,
    preferencesMigrationStatus: StorageMigrationStatus,
): ValhallaRepositories {
    // 档案仓储跨连接共享，保证扫描/详情页看到同一份设备历史。
    val profileStore = remember { createDeviceProfileStore() }
    val profileRepository = remember { DeviceProfileRepository(profileStore) }
    // OTA 断点跨连接共享：启动恢复与后续会话读写同一份持久化断点。
    val checkpointStore = remember { createOtaCheckpointStore() }
    val startupRecovery =
        remember {
            StartupRecovery.inspect(
                profileStore = profileStore,
                checkpointStore = checkpointStore,
                preferencesStatus = preferencesMigrationStatus,
            )
        }
    // 事件时间线跨进程共享（存储层每次读取都走平台键值），固件库在同一 App 进程内共享。
    val eventStore = remember { createDeviceEventStore() }
    val firmwareLibrary = remember { FirmwareLibraryRepository(createFirmwareLibraryStore()) }
    // 指标采样只保存在本机，按设备分片，最多 50 个点。
    val metrics = remember { DeviceMetricRepository(createDeviceMetricStore()) }
    // 预设与自动化规则复用平台偏好存储；损坏数据在仓储层回退为空。
    val presetRepository =
        remember {
            ConfigPresetRepository(
                store = PreferencesConfigPresetStore(onboardingStore),
                clockMillis = { Clock.System.now().toEpochMilliseconds() },
            )
        }
    val automationRepository =
        remember {
            AutomationRuleRepository(
                store = PreferencesAutomationRuleStore(onboardingStore),
                clockMillis = { Clock.System.now().toEpochMilliseconds() },
            )
        }
    return ValhallaRepositories(
        profileRepository = profileRepository,
        checkpointStore = checkpointStore,
        eventStore = eventStore,
        firmwareLibrary = firmwareLibrary,
        metrics = metrics,
        presetRepository = presetRepository,
        automationRepository = automationRepository,
        onboardingStore = onboardingStore,
        startupNotice = startupRecovery.notice,
    )
}

@Composable
internal fun rememberValhallaControllers(
    bleClient: BleClient,
    scope: CoroutineScope,
    errorReporter: ErrorReporter,
    profileRepository: DeviceProfileRepository,
    firmwareLibrary: FirmwareLibraryRepository,
    sessionsRef: State<Map<String, DeviceSession>>,
    stringsRef: State<StringResolver>,
): ValhallaControllers {
    val scanController =
        remember {
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
    val deviceManager = remember { MultiDeviceManager(bleClient = bleClient, capacity = 4) }

    // v2.9.0：批量与 OTA 队列都在进程内串行执行，共享同一把设备写锁。
    val deviceLocks = remember { DeviceOperationLocks() }
    val batchController =
        remember {
            BatchOperationController(deviceLocks) { deviceId ->
                sessionsRef.value[deviceId]
                    ?.connection
                    ?.connected
                    ?.value == true
            }
        }
    val queuePicker = rememberFirmwarePicker()
    val queueSigningKeyPem = rememberFirmwareSigningKeyPem()
    val queueController =
        remember {
            OtaQueueController(
                scope = scope,
                executor =
                    SessionOtaQueueExecutor(
                        sessions = { sessionsRef.value },
                        library = firmwareLibrary,
                        picker = queuePicker,
                        signingKeyPem = queueSigningKeyPem,
                        renderReason = { message -> stringsRef.value.render(message) },
                    ),
            )
        }
    return ValhallaControllers(scanController, deviceManager, deviceLocks, batchController, queueController)
}

@Composable
internal fun rememberValhallaUiState(
    showOnboardingOnLaunch: Boolean,
    startupNotice: String?,
): ValhallaUiState =
    remember {
        ValhallaUiState(
            initialRoute = if (showOnboardingOnLaunch) Route.Onboarding(review = false) else Route.Scan,
            initialStorageNotice = startupNotice,
        )
    }

internal class ValhallaSessionServices(
    val scope: CoroutineScope,
    val errorReporter: ErrorReporter,
    val bleClient: BleClient,
    val deviceManager: MultiDeviceManager,
    val profileRepository: DeviceProfileRepository,
    val checkpointStore: OtaCheckpointStore,
    val eventStore: DeviceEventStore,
    val automationRepository: AutomationRuleRepository,
    val presetRepository: ConfigPresetRepository,
    val settings: AppSettings,
    val strings: StringResolver,
)

internal class ValhallaSessionOps(
    val ui: ValhallaUiState,
    services: ValhallaSessionServices,
) {
    private val scope = services.scope
    private val errorReporter = services.errorReporter
    private val bleClient = services.bleClient
    private val deviceManager = services.deviceManager
    private val profileRepository = services.profileRepository
    private val checkpointStore = services.checkpointStore
    private val eventStore = services.eventStore
    private val automationRepository = services.automationRepository
    private val presetRepository = services.presetRepository
    private val settings = services.settings
    private val strings = services.strings

    fun createSession(
        connection: BleConnection,
        device: BleDevice,
        packetLog: InMemoryPacketLog,
    ): DeviceSession {
        val dispatcher =
            ConcurrentCommandDispatcher(
                PacketRecordingFrameLink(BleFrameLink(connection), packetLog),
            )
        return DeviceSession(
            device = device,
            connection = connection,
            dispatcher = dispatcher,
            detail =
                DeviceDetailController(
                    dispatcher = dispatcher,
                    deviceId = connection.deviceId,
                    disconnectLink = { connection.disconnect() },
                    tokenStore = createTokenStore(),
                    profileRepository = profileRepository,
                    deviceDisplayName = device.name,
                ),
            ota =
                OtaController(
                    dispatcher = dispatcher,
                    scope = scope,
                    connectionState = connection.connected,
                    checkpointStore = checkpointStore,
                    deviceId = connection.deviceId,
                    profileRepository = profileRepository,
                    errorReporter = errorReporter,
                ),
            checkpointStore = checkpointStore,
            config =
                ConfigEditorController(
                    dispatcher = dispatcher,
                    onError = { error -> errorReporter.record("config", error) },
                ),
            param =
                ParamEditorController(
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
    fun triggerAutomation(
        trigger: AutomationTrigger,
        session: DeviceSession,
    ) {
        val healthCheck =
            DeviceHealthCheckController(
                dispatcher = session.dispatcher,
                isLinkAlive = { session.connection.connected.value },
                otaHistoryProvider = {
                    session.profileRepository
                        .get(session.connection.deviceId)
                        ?.otaHistory
                        .orEmpty()
                },
                knownUptimeSeconds = { session.detail.state.value.uptimeSeconds ?: 0L },
            )
        val runner =
            AutomationRuleRunner(
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
                onDiagnosticReport = { ui.lastDiagnosticReport = it },
            )
        scope.launchSafely(errorReporter, "automation") {
            runner.run(
                trigger = trigger,
                context =
                    AutomationContext(
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

    fun handleBleAction(
        action: BleGuidanceAction,
        permissionController: BlePermissionController,
    ) {
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

    fun requestStartScan(
        availability: BleAvailability,
        scanController: ScanController,
    ) {
        if (!availability.allowsConnection()) {
            ui.errorMessage =
                availability.guidance()?.message ?: UiMessage(StringKey.BLUETOOTH_UNAVAILABLE)
            return
        }
        ui.errorMessage = null
        scanController.startScan()
    }

    fun connectFromScan(
        device: BleDevice,
        availability: BleAvailability,
        scanController: ScanController,
    ) {
        if (!availability.allowsConnection()) {
            ui.errorMessage =
                availability.guidance()?.message ?: UiMessage(StringKey.BLUETOOTH_UNAVAILABLE)
            return
        }
        scope.launchSafely(errorReporter, "connect") {
            ui.connectingDeviceId = device.deviceId
            ui.errorMessage = null
            try {
                when (val result = deviceManager.connect(device)) {
                    is MultiDeviceConnectResult.Connected -> {
                        scanController.stopScan()
                        val newSession =
                            createSession(
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
                        ui.sessions = ui.sessions + (device.deviceId to newSession)
                        deviceManager.select(device.deviceId)
                        ui.route = Route.Detail(device.deviceId)
                        triggerAutomation(AutomationTrigger.CONNECTED, newSession)
                    }

                    MultiDeviceConnectResult.AlreadyConnected -> {
                        deviceManager.select(device.deviceId)
                        ui.route = Route.Detail(device.deviceId)
                    }

                    is MultiDeviceConnectResult.LimitReached -> {
                        ui.errorMessage =
                            UiMessage(
                                StringKey.DEVICES_LIMIT_REACHED,
                                mapOf("capacity" to result.capacity.toString()),
                            )
                    }

                    is MultiDeviceConnectResult.Failed -> {
                        ui.errorMessage =
                            UiMessage(
                                StringKey.ERROR_PREFIX,
                                mapOf("detail" to result.message),
                            )
                    }

                    MultiDeviceConnectResult.InProgress -> Unit
                }
            } finally {
                ui.connectingDeviceId = null
            }
        }
    }

    fun startReconnect(session: DeviceSession) {
        val existing = ui.reconnect
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
            ui.reconnect = null
        }

        val device = session.device
        val packetLog = session.packetLog
        val controller =
            AutoReconnectController(
                policy =
                    ReconnectPolicy(
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
                    ui.reconnect = null
                    val refreshed = createSession(connection, device, packetLog)
                    refreshed.events.record(
                        deviceId = device.deviceId,
                        type = DeviceEventType.CONNECTION,
                        summary = strings.t(StringKey.TIMELINE_EVENT_RECONNECTED),
                        result = DeviceEventResult.SUCCESS,
                    )
                    ui.sessions = ui.sessions + (device.deviceId to refreshed)
                    deviceManager.replaceConnection(device.deviceId, connection)
                    ui.route = Route.Detail(device.deviceId)
                    triggerAutomation(AutomationTrigger.CONNECTED, refreshed)
                },
                onError = { error -> errorReporter.record("reconnect", error) },
            )
        ui.reconnect = ReconnectSession(device, packetLog, controller)
        controller.start()
    }

    fun disconnectDevice(session: DeviceSession) {
        val id = session.device.deviceId
        scope.launchSafely(errorReporter, "disconnect-device") {
            session.events.record(
                deviceId = id,
                type = DeviceEventType.DISCONNECTION,
                summary = strings.t(StringKey.TIMELINE_EVENT_DISCONNECTED),
                result = DeviceEventResult.INFO,
            )
            runCatching { session.detail.disconnect() }
            runCatching { session.dispatcher.close() }
            ui.sessions = ui.sessions - id
            deviceManager.disconnect(id)
            val currentRoute = ui.route
            if (currentRoute is Route.Detail && currentRoute.deviceId == id) {
                ui.route = Route.Scan
            }
        }
    }

    fun disconnectAll() {
        scope.launchSafely(errorReporter, "disconnect-all") {
            ui.sessions.values.forEach { session ->
                session.events.record(
                    deviceId = session.device.deviceId,
                    type = DeviceEventType.DISCONNECTION,
                    summary = strings.t(StringKey.TIMELINE_EVENT_DISCONNECTED),
                    result = DeviceEventResult.INFO,
                )
                runCatching { session.detail.disconnect() }
                runCatching { session.dispatcher.close() }
            }
            ui.sessions = emptyMap()
            deviceManager.disconnectAll()
            ui.route = Route.Scan
        }
    }

    fun disconnectAndLeave(
        session: DeviceSession,
        deviceId: String,
    ) {
        session.userDisconnected = true
        ui.reconnect?.controller?.cancel()
        ui.reconnect = null
        session.events.record(
            deviceId = deviceId,
            type = DeviceEventType.DISCONNECTION,
            summary = strings.t(StringKey.TIMELINE_EVENT_DISCONNECTED),
            result = DeviceEventResult.INFO,
        )
        scope.launchSafely(errorReporter, "disconnect-back") {
            session.detail.disconnect()
            session.dispatcher.close()
            ui.sessions = ui.sessions - deviceId
            deviceManager.disconnect(deviceId)
            ui.route =
                if (deviceManager.state.value.occupied > 1) {
                    Route.Devices
                } else {
                    Route.Scan
                }
        }
    }

    fun cancelReconnect() {
        ui.reconnect?.controller?.cancel()
        ui.reconnect = null
    }
}

internal suspend fun executeBatchOperation(
    kind: BatchOperationKind,
    preset: ConfigPreset?,
    target: BatchTarget,
    step: (String) -> Unit,
    sessions: Map<String, DeviceSession>,
): BatchDeviceOutcome {
    val session =
        sessions[target.deviceId]
            ?: return BatchDeviceOutcome(
                status = BatchItemStatus.REMOVED,
                reason = "batch.disconnected",
            )
    if (!session.connection.connected.value) {
        return BatchDeviceOutcome(
            status = BatchItemStatus.REMOVED,
            reason = "batch.disconnected",
        )
    }
    return when (kind) {
        BatchOperationKind.HEALTH_CHECK -> {
            step("batch.health")
            val healthCheck =
                DeviceHealthCheckController(
                    dispatcher = session.dispatcher,
                    isLinkAlive = { session.connection.connected.value },
                    otaHistoryProvider = {
                        session.profileRepository
                            .get(session.connection.deviceId)
                            ?.otaHistory
                            .orEmpty()
                    },
                    knownUptimeSeconds = { session.detail.state.value.uptimeSeconds ?: 0L },
                )
            val result =
                healthCheck.run()
                    ?: return BatchDeviceOutcome(
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
                    detail =
                        "ok=${result.okCount} abnormal=${result.abnormalCount} " +
                            "skipped=${result.skippedCount} failed=${result.failedCount}",
                )
            }
        }

        BatchOperationKind.APPLY_PRESET -> {
            val chosen =
                preset
                    ?: return BatchDeviceOutcome(
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
            val current =
                editableLoadedConfigFields(session.config.state.value.visibleFields)
                    .associate { it.spec.key to it.text }
            val diffs =
                ConfigPresetDiffer.diff(
                    chosen,
                    session.config.state.value.target,
                    current,
                )
            val report = ConfigPresetApplier(session.dispatcher).apply(diffs)
            val detailText =
                "written=${report.writtenCount} " +
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
}

private const val VERIFY_PROGRESS = 0.99f

/**
 * OTA 队列执行器：逐台打开固件库引用，走完整预检后再用既有 OTA 控制器升级。
 *
 * 暂停/取消信号只在分片边界生效（由 OtaController 的 queue signal 实现），
 * 不会在半片状态停机；失败断点仍由既有四重门控复用。
 */
internal class SessionOtaQueueExecutor(
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
        val session =
            sessions()[item.target.deviceId]
                ?: return OtaQueueExecutionResult.Skipped("queue.device_missing")
        if (!session.connection.connected.value) {
            return OtaQueueExecutionResult.Skipped("queue.device_disconnected")
        }
        val record =
            library.get(item.target.firmwareId)
                ?: return OtaQueueExecutionResult.Failed("firmware.library.unavailable", false)
        val selection =
            FirmwareSelectionController(
                picker = picker,
                checkpointStore = session.checkpointStore,
                prechecker = FirmwarePrecheckFactory.production(signingKeyPem),
            )
        return try {
            progress(OtaQueueStage.PRECHECK, 0f)
            val detail = session.detail.state.value
            val opened =
                selection.openFromLibrary(
                    sourcePath = record.sourcePath,
                    currentVersion = detail.firmware,
                    currentModel = detail.deviceInfo?.model,
                )
            if (!opened) {
                val code = selection.state.value.errorCode ?: "firmware.precheck_failed"
                return OtaQueueExecutionResult.Failed(code, false)
            }
            val firmware =
                selection.firmware
                    ?: return OtaQueueExecutionResult.Failed("firmware.precheck_failed", false)
            val resume = selection.resumePointForCurrentFirmware()
            progress(OtaQueueStage.PRECHECK, 1f)
            session.ota.configureQueueSignals(pauseRequested, cancelRequested)
            val result =
                coroutineScope {
                    val observer =
                        launch {
                            session.ota.state.collect { state ->
                                when (state) {
                                    is OtaState.Preparing -> progress(OtaQueueStage.PRECHECK, 1f)
                                    is OtaState.Transferring ->
                                        progress(OtaQueueStage.TRANSFER, state.progress)

                                    is OtaState.Verifying -> progress(OtaQueueStage.VERIFY, VERIFY_PROGRESS)
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

                is OtaState.Paused ->
                    OtaQueueExecutionResult.Paused(
                        progress = result.progress,
                        checkpointRetained = result.checkpointSaved,
                    )

                is OtaState.Aborted ->
                    OtaQueueExecutionResult.Aborted(
                        reason = result.reason,
                        checkpointRetained = selection.resumePointForCurrentFirmware() != null,
                    )

                is OtaState.Failed ->
                    OtaQueueExecutionResult.Failed(
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
