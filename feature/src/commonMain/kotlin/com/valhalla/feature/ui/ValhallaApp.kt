package com.valhalla.feature.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.intl.Locale
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
import com.valhalla.feature.firmware.FirmwareSelectionController
import com.valhalla.feature.firmware.FirmwareSelectionState
import com.valhalla.feature.firmware.rememberFirmwarePicker
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
    data object Settings : Route
    data object About : Route
    data object Help : Route
    data object Privacy : Route
    data class Detail(val session: DeviceSession) : Route
    data class Ota(val session: DeviceSession) : Route
    data class Logs(val session: DeviceSession) : Route
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

    CompositionLocalProvider(LocalStrings provides strings) {
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
    val scanController = remember { ScanController(bleClient, scope) }
    // 档案仓储跨连接共享，保证扫描/详情页看到同一份设备历史。
    val profileRepository = remember { DeviceProfileRepository(createDeviceProfileStore()) }

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
            ),
            checkpointStore = checkpointStore,
            config = ConfigEditorController(dispatcher),
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
                scope.launch { runCatching { session.dispatcher.close() } }
                reconnect = null
                route = Route.Detail(createSession(connection, device, packetLog))
            },
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
            onSelect = select@{ device ->
                if (!availability.allowsConnection()) {
                    errorMessage = availability.guidance()?.message
                        ?: UiMessage(StringKey.BLUETOOTH_UNAVAILABLE)
                    return@select
                }
                scope.launch {
                    connectingDeviceId = device.deviceId
                    errorMessage = null
                    try {
                        val connection = bleClient.connect(device.deviceId)
                        scanController.stopScan()
                        route = Route.Detail(createSession(connection, device, InMemoryPacketLog()))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        val info = classifyDisconnect(
                            DisconnectSignals(
                                timeout = error is BleTimeoutException,
                                transportError = true,
                                adapterState = adapterState,
                                permissionState = permissionState,
                            ),
                        )
                        errorMessage = info.message
                    } finally {
                        connectingDeviceId = null
                    }
                }
            },
            onBleAction = { handleBleAction(it) },
            onOpenSettings = { route = Route.Settings },
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
            val connected by current.session.connection.connected.collectAsState()
            val disconnectInfo = if (!connected) {
                classifyDisconnect(
                    DisconnectSignals(
                        userRequested = current.session.userDisconnected,
                        peerTerminated = !current.session.userDisconnected,
                        adapterState = adapterState,
                        permissionState = permissionState,
                    ),
                )
            } else {
                null
            }
            DetailScreen(
                session = current.session,
                disconnectInfo = disconnectInfo,
                reconnectState = reconnect
                    ?.takeIf { it.device.deviceId == current.session.device.deviceId }
                    ?.controller
                    ?.state
                    ?.collectAsState()
                    ?.value,
                onConnectionLost = {
                    if (!current.session.userDisconnected) startReconnect(current.session)
                },
                onRetryReconnect = { startReconnect(current.session) },
                onCancelReconnect = {
                    reconnect?.controller?.cancel()
                    reconnect = null
                },
                onBleAction = { handleBleAction(it) },
                onBack = {
                    current.session.userDisconnected = true
                    reconnect?.controller?.cancel()
                    reconnect = null
                    scope.launch {
                        current.session.detail.disconnect()
                        current.session.dispatcher.close()
                        route = Route.Scan
                    }
                },
                onOpenOta = { route = Route.Ota(current.session) },
                onOpenLogs = { route = Route.Logs(current.session) },
            )
        }

        is Route.Ota -> OtaScreen(
            session = current.session,
            onBack = { route = Route.Detail(current.session) },
        )

        is Route.Logs -> PacketLogScreen(
            packetLog = current.session.packetLog,
            onBack = { route = Route.Detail(current.session) },
        )
    }
}

@Composable
private fun ScanScreen(
    state: ScanUiState,
    availability: BleAvailability,
    connectingDeviceId: String?,
    errorMessage: UiMessage?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelect: (BleDevice) -> Unit,
    onBleAction: (BleGuidanceAction) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
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

        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
            Button(onClick = onStart, enabled = canScan && !state.scanning) {
                Text(strings.t(StringKey.START_SCAN))
            }
            OutlinedButton(onClick = onStop, enabled = state.scanning) {
                Text(strings.t(StringKey.STOP_SCAN))
            }
        }

        if (state.scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        errorMessage?.let { message ->
            Text(strings.render(message), color = status.error)
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
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canScan) { onSelect(device) },
                ) {
                    Column(modifier = Modifier.padding(DesignTokens.Spacing.md)) {
                        Text(
                            device.name ?: strings.t(StringKey.UNNAMED_DEVICE),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            strings.t(
                                StringKey.DEVICE_RSSI,
                                "id" to device.deviceId,
                                "rssi" to device.rssi.toString(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
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

@Composable
private fun DetailScreen(
    session: DeviceSession,
    disconnectInfo: DisconnectInfo?,
    reconnectState: ReconnectState?,
    onConnectionLost: () -> Unit,
    onRetryReconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
    onBleAction: (BleGuidanceAction) -> Unit,
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
    val profile = session.profileRepository.get(state.deviceId)
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds()) }

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
            session.config.load()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                strings.t(StringKey.DETAIL_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
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
                    Text(state.deviceId, style = MaterialTheme.typography.titleMedium)
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
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                    Button(onClick = { scope.launch { controller.authenticate("demo-token") } }) {
                        Text(strings.t(StringKey.AUTHENTICATE))
                    }
                    OutlinedButton(onClick = { scope.launch { controller.refreshInfo() } }) {
                        Text(strings.t(StringKey.REFRESH_INFO))
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

            state.error?.let { message ->
                item {
                    Text(
                        strings.render(message),
                        color = status.error,
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
                    onRefreshDevice = { scope.launch { controller.refreshInfo() } },
                )
            }

            item { Text(strings.t(StringKey.DEVICE_LOGS), style = MaterialTheme.typography.titleMedium) }
            items(state.logs) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: DeviceProfile,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
) {
    val strings = LocalStrings.current
    val status = statusColors()
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
            profile.lastOtaRecord?.let { record ->
                Text(
                    strings.t(
                        StringKey.PROFILE_LAST_OTA,
                        "outcome" to record.outcome.label(strings),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    strings.t(
                        StringKey.PROFILE_OTA_RECORD,
                        "outcome" to record.outcome.label(strings),
                        "version" to record.firmwareVersion,
                        "duration" to format.number(record.durationMillis),
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
        }
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
                    onClick = { scope.launch { controller.load() } },
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
                    onSave = { scope.launch { controller.save(field.spec.key) } },
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
        exportJob = scope.launch {
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
                    return@launch
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
    val selection = remember(picker, session.checkpointStore) {
        FirmwareSelectionController(picker, session.checkpointStore)
    }
    val selectionState by selection.state.collectAsState()
    val scope = rememberCoroutineScope()
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
                    if (running) scope.launch { controller.abort() }
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
            TextButton(onClick = { scope.launch { session.detail.refreshInfo() } }) {
                Text(strings.t(StringKey.REFRESH_DEVICE_INFO))
            }
        }

        Button(
            onClick = {
                scope.launch {
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
                modifier = Modifier.fillMaxWidth(),
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
                scope.launch {
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
                LinearProgressIndicator(
                    progress = { current.progress },
                    modifier = Modifier.fillMaxWidth(),
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
                    scope.launch {
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
                    scope.launch {
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
            onClick = { scope.launch { controller.abort() } },
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
