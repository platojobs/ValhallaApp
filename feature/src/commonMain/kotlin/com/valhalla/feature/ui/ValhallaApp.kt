package com.valhalla.feature.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.valhalla.core.logging.PacketLogRedaction
import com.valhalla.core.protocol.message.AuthMode
import com.valhalla.core.security.HmacAuthSigner
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.command.PacketRecordingFrameLink
import com.valhalla.core.config.DeviceConfigTarget
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
import com.valhalla.feature.detail.DeviceDetailController
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
import com.valhalla.feature.ota.OtaState
import com.valhalla.feature.scan.ScanController
import com.valhalla.feature.scan.ScanUiState
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
    val packetLog: InMemoryPacketLog,
    val profileRepository: DeviceProfileRepository,
) {
    /** 用户主动返回断开时为 true，用于区分「设备侧断开」与「主动断开」。 */
    var userDisconnected: Boolean = false
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
    data class Detail(val deviceId: String) : Route
    data class Ota(val deviceId: String) : Route
    data class Logs(val deviceId: String) : Route
}

/**
 * Compose Multiplatform 根界面：扫描 → 设备详情 → OTA 三段式流程。
 * Android / iOS 共用同一套页面，平台差异封装在 [createBleClient] 之后。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ValhallaApp() {
    val settingsStore = remember { createAppSettingsStore() }
    var preferences by remember { mutableStateOf(AppPreferenceStore.load(settingsStore)) }
    val language = resolveLanguage(preferences.language, Locale.current.language)
    val strings = remember(language) { StringResolver(language) }
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
                onLanguageChange = { mode ->
                    preferences = preferences.copy(language = mode)
                    AppPreferenceStore.saveLanguage(settingsStore, mode)
                },
                onThemeChange = { mode ->
                    preferences = preferences.copy(theme = mode)
                    AppPreferenceStore.saveTheme(settingsStore, mode)
                },
            )
        }
    }
}

@Composable
private fun ValhallaContent(
    preferences: com.valhalla.feature.theme.AppPreferences,
    onLanguageChange: (AppLanguageMode) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
) {
    val bleClient = remember { createBleClient() }
    val permissionController = rememberBlePermissionController(bleClient)
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    // 档案仓储跨连接共享，保证扫描/详情页看到同一份设备历史。
    val profileRepository = remember { DeviceProfileRepository(createDeviceProfileStore()) }
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

    val permissionState by permissionController.state.collectAsState()
    val adapterState by bleClient.adapterState.collectAsState()
    val availability = remember(adapterState, permissionState) {
        resolveBleAvailability(adapterState, permissionState)
    }

    var route by remember { mutableStateOf<Route>(Route.Scan) }
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
            packetLog = packetLog,
            profileRepository = profileRepository,
        )
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
            policy = ReconnectPolicy(),
            scope = scope,
            isBluetoothOn = { bleClient.adapterState.value == BleAdapterState.POWERED_ON },
            attemptConnect = { runCatching { bleClient.connect(device.deviceId) }.getOrNull() },
            onConnected = { connection ->
                scope.launchSafely(errorReporter, "reconnect-swap") {
                    runCatching { session.dispatcher.close() }
                }
                reconnect = null
                val refreshed = createSession(connection, device, packetLog)
                sessions = sessions + (device.deviceId to refreshed)
                deviceManager.replaceConnection(device.deviceId, connection)
                route = Route.Detail(device.deviceId)
            },
            onError = { error -> errorReporter.record("reconnect", error) },
        )
        reconnect = ReconnectSession(device, packetLog, controller)
        controller.start()
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
                                sessions = sessions + (
                                    device.deviceId to createSession(
                                        result.device.connection,
                                        device,
                                        InMemoryPacketLog(),
                                    )
                                    )
                                deviceManager.select(device.deviceId)
                                route = Route.Detail(device.deviceId)
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
                        runCatching { session.detail.disconnect() }
                        runCatching { session.dispatcher.close() }
                    }
                    sessions = emptyMap()
                    deviceManager.disconnectAll()
                    route = Route.Scan
                }
            },
            onBack = { route = Route.Scan },
        )

        Route.Settings -> SettingsScreen(
            language = preferences.language,
            theme = preferences.theme,
            onLanguageChange = onLanguageChange,
            onThemeChange = onThemeChange,
            onBack = { route = Route.Scan },
            onAbout = { route = Route.About },
            onHelp = { route = Route.Help },
            onPrivacy = { route = Route.Privacy },
        )

        Route.About -> AboutScreen(onBack = { route = Route.Settings })
        Route.Help -> HelpScreen(onBack = { route = Route.Settings })
        Route.Privacy -> PrivacyScreen(onBack = { route = Route.Settings })

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
                    onBack = { route = Route.Detail(current.deviceId) },
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
                    onBack = { route = Route.Detail(current.deviceId) },
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailScreen(
    session: DeviceSession,
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
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val controller = session.detail
    val state by controller.state.collectAsState()
    val configState by session.config.state.collectAsState()
    val connected by session.connection.connected.collectAsState()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    val clipboard = LocalClipboardManager.current
    val profile = session.profileRepository.get(state.deviceId)
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds()) }
    var authMode by remember { mutableStateOf(AuthMode.TOKEN) }
    var credential by remember { mutableStateOf("") }

    LaunchedEffect(session, connected) {
        if (!connected && !session.userDisconnected) {
            onConnectionLost()
        }
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
                                    if (authMode == AuthMode.SIGNED) {
                                        controller.authenticateSigned(
                                            signer = HmacAuthSigner(secret.encodeToByteArray()),
                                            clientNonce = Random.nextBytes(16),
                                            deviceDigest = PacketLogRedaction.hashDeviceId(state.deviceId),
                                        )
                                    } else {
                                        controller.authenticate(secret)
                                    }
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
                    OutlinedButton(onClick = onOpenOta, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.t(StringKey.ENTER_OTA))
                    }
                    OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.t(StringKey.OPEN_LOGS))
                    }
                }
            }

            item {
                QuickActionSection(
                    controller = controller,
                    quickAction = state.quickAction,
                    connected = connected,
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

            profile?.let { snapshot ->
                item { ProfileCard(snapshot, format, zoneOffsetMinutes) }
            }

            item {
                ConfigSection(
                    controller = session.config,
                    state = configState,
                    onRefreshDevice = { scope.launchSafely(errorReporter, "refresh-info") { controller.refreshInfo() } },
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
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    var showRebootDialog by remember { mutableStateOf(false) }
    var showFactoryDialog by remember { mutableStateOf(false) }
    var delayText by remember { mutableStateOf("0") }
    var confirmToken by remember { mutableStateOf("") }
    val busy = quickAction.busy
    val enabled = connected && !busy

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            Text(strings.t(StringKey.QUICK_ACTIONS_TITLE), style = MaterialTheme.typography.titleMedium)
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
    onRefreshDevice: () -> Unit,
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
                    onSave = { scope.launchSafely(errorReporter, "config-save") { controller.save(field.spec.key) } },
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

@OptIn(ExperimentalTime::class)
@Composable
private fun PacketLogScreen(
    packetLog: InMemoryPacketLog,
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
    var maxEntriesText by remember { mutableStateOf(PacketLogExportOptions.DEFAULT.maxEntries.toString()) }
    var maxBytesKbText by remember {
        mutableStateOf((PacketLogExportOptions.DEFAULT.maxBytes / 1024).toString())
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
        PacketLogExportOptions(maxEntries = entryLimit.value!!, maxBytes = byteLimit.value!!)
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
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val format = remember(strings.language) { LocaleFormat(strings.language) }
    val status = statusColors()
    val controller = session.ota
    val state by controller.state.collectAsState()
    val detailState by session.detail.state.collectAsState()
    val picker = rememberFirmwarePicker()
    val signingKeyPem = rememberFirmwareSigningKeyPem()
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

    LaunchedEffect(selection) { selection.refreshResume() }
    val terminal = state is OtaState.Succeeded || state is OtaState.Failed || state is OtaState.Aborted
    LaunchedEffect(terminal) {
        if (terminal) {
            if (state is OtaState.Succeeded) selection.clearResume() else selection.refreshResume()
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

        Button(
            onClick = {
                scope.launchSafely(errorReporter, "firmware-pick") {
                    selection.pickAndPrecheck(
                        currentVersion = detailState.firmware,
                        currentModel = detailState.deviceInfo?.model,
                    )
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

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                val firmware = selection.firmware
                if (firmware != null) {
                    scope.launchSafely(errorReporter, "ota-start") {
                        controller.startPackage(firmware, knownCrc16 = selectionState.computedCrc16)
                    }
                }
            },
            enabled = !running && selectionState.readyForOta,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.t(StringKey.START_OTA))
        }

        OutlinedButton(
            onClick = {
                val firmware = selection.firmware
                val point = selectionState.resumePoint
                if (firmware != null && point != null) {
                    scope.launchSafely(errorReporter, "ota-resume") {
                        controller.startPackage(
                            firmware,
                            resumeFrom = point,
                            knownCrc16 = selectionState.computedCrc16,
                        )
                    }
                }
            },
            enabled = !running && selectionState.canResume,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val point = selectionState.resumePoint
            Text(
                if (point != null) {
                    strings.t(
                        StringKey.RESUME_OTA_WITH_PROGRESS,
                        "sent" to format.number(point.sentBytes),
                        "total" to format.number(point.totalBytes),
                    )
                } else {
                    strings.t(StringKey.RESUME_OTA)
                },
            )
        }

        if (selectionState.resumePoint != null || selectionState.resumeBlockedReason != null) {
            TextButton(onClick = { selection.clearResume() }, enabled = !running) {
                Text(strings.t(StringKey.CLEAR_RESUME))
            }
        }

        OutlinedButton(
            onClick = { scope.launchSafely(errorReporter, "ota-abort") { controller.abort() } },
            enabled = running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.t(StringKey.ABORT_OTA))
        }

        OutlinedButton(
            onClick = { controller.reset() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.t(StringKey.RESET_STATE))
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
