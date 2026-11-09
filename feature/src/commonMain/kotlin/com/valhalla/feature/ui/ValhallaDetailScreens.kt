package com.valhalla.feature.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.valhalla.core.AppBuildInfo
import com.valhalla.core.config.ConfigPresetRepository
import com.valhalla.core.config.DeviceConfigTarget
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.logging.PacketLogRedaction
import com.valhalla.core.logging.formatAbsoluteTime
import com.valhalla.core.metrics.DeviceMetricRepository
import com.valhalla.core.protocol.message.AuthMode
import com.valhalla.core.security.HmacAuthSigner
import com.valhalla.data.share.currentTimeZoneOffsetMinutes
import com.valhalla.feature.ble.BleGuidanceAction
import com.valhalla.feature.ble.DisconnectInfo
import com.valhalla.feature.ble.ReconnectState
import com.valhalla.feature.config.ConfigEditorState
import com.valhalla.feature.config.ConfigPresetApplier
import com.valhalla.feature.detail.DeviceDetailController
import com.valhalla.feature.detail.DeviceDetailState
import com.valhalla.feature.detail.QuickActionState
import com.valhalla.feature.detail.QuickActionType
import com.valhalla.feature.error.ErrorReporter
import com.valhalla.feature.error.LocalErrorReporter
import com.valhalla.feature.error.launchSafely
import com.valhalla.feature.export.FileSaveStatus
import com.valhalla.feature.export.FileSaver
import com.valhalla.feature.export.SaveableFile
import com.valhalla.feature.export.rememberFileSaver
import com.valhalla.feature.health.DeviceHealthCheckController
import com.valhalla.feature.health.HealthCheckReportFormatter
import com.valhalla.feature.health.HealthCheckResult
import com.valhalla.feature.health.HealthCheckState
import com.valhalla.feature.health.HealthCheckStatus
import com.valhalla.feature.health.labelKey
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.i18n.render
import com.valhalla.feature.metrics.RssiSource
import com.valhalla.feature.metrics.RssiSubscriptionController
import com.valhalla.feature.metrics.RssiSubscriptionState
import com.valhalla.feature.notification.LowBatteryAlertTracker
import com.valhalla.feature.notification.NotificationCenter
import com.valhalla.feature.notification.NotificationDecision
import com.valhalla.feature.notification.NotificationEvent
import com.valhalla.feature.notification.NotificationEventType
import com.valhalla.feature.notification.NotificationPage
import com.valhalla.feature.notification.NotificationPolicy
import com.valhalla.feature.notification.NotificationPresenter
import com.valhalla.feature.notification.NotificationTarget
import com.valhalla.feature.param.ParamEditorState
import com.valhalla.feature.settings.AppSettings
import com.valhalla.feature.theme.DesignTokens
import com.valhalla.feature.theme.statusColors
import com.valhalla.feature.uilogic.label
import com.valhalla.feature.uilogic.labelKey
import com.valhalla.feature.uilogic.quickActionEnabled
import com.valhalla.feature.uilogic.timelineKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Clock

private fun healthReportText(
    result: HealthCheckResult,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
    strings: StringResolver,
): String =
    HealthCheckReportFormatter.format(
        result = result,
        generatedAtText = formatAbsoluteTime(result.completedAtMillis, zoneOffsetMinutes),
        appVersion = AppBuildInfo.VERSION_NAME,
        platform = platformDisplayName(),
        resolve = { strings.render(it) },
    )

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailScreen(
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
    val controller = session.detail
    val state by controller.state.collectAsState()
    val configState by session.config.state.collectAsState()
    val paramState by session.param.state.collectAsState()
    val connected by session.connection.connected.collectAsState()
    val errorReporter = LocalErrorReporter.current
    val clipboard = LocalClipboardManager.current
    val profile = session.profileRepository.get(state.deviceId)
    val zoneOffsetMinutes = remember { currentTimeZoneOffsetMinutes(Clock.System.now().toEpochMilliseconds()) }
    val ui = rememberDetailUiState()
    var disconnectNotified by remember(session) { mutableStateOf(false) }
    val runtime = rememberDetailRuntime(session, settings)
    val healthState by runtime.healthCheck.state.collectAsState()
    val rssiState by runtime.rssiController.state.collectAsState()
    val notificationSink = DetailNotificationSink(notificationCenter, notificationPresenter, onNotificationFallback)

    DetailHealthReportEffect(healthState, session, format, zoneOffsetMinutes)
    DetailRssiEffect(connected, settings, runtime.rssiController, session)
    DetailMetricSampleEffect(state, rssiState, metrics, session) { ui.metricsVersion++ }
    DetailConnectionLostEffect(session, connected, onConnectionLost)
    DetailLowBatteryEffect(state, settings, runtime.lowBatteryTracker, notificationSink)
    DetailDisconnectNoticeEffect(
        connected,
        settings,
        session,
        state,
        disconnectNotified,
        { disconnectNotified = it },
        notificationSink,
    )
    DetailErrorTimelineEffect(state, session)
    DetailConfigAutoloadEffect(session, state, errorReporter)

    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        DetailHeaderRow(connectedCount, onOpenDevices, onBack)

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
                DetailDashboardItem(metrics, ui.metricsVersion, state, rssiState, runtime.connectObservedAt, zoneOffsetMinutes, session)
            }
            item {
                DetailHealthCard(healthState, ui, runtime, session, state, settings, notificationSink, format, zoneOffsetMinutes)
            }
            item { DetailIdentityItem(state, clipboard) }
            item { DetailAuthItem(controller, state, ui, session) }
            item { DetailLoadingItem(state.loading) }
            item {
                DetailActionsItem(writeLocked, onOpenOta, onOpenLogs, onOpenDiagnostics)
            }
            item {
                QuickActionSection(
                    controller = controller,
                    quickAction = state.quickAction,
                    connected = connected,
                    writeLocked = writeLocked,
                )
            }
            state.error?.let {
                item { DetailErrorItem(state, controller, clipboard) }
            }
            (state.quickAction as? QuickActionState.Failed)?.let { failure ->
                item { DetailQuickActionFailureItem(failure, clipboard) }
            }
            item {
                DetailTimelineItem(session, state, ui, format, zoneOffsetMinutes)
            }
            profile?.let { snapshot ->
                item { ProfileCard(snapshot, format, zoneOffsetMinutes) }
            }
            item { DetailPresetItem(session, presets, writeLocked) }
            item { DetailConfigItem(session, configState, writeLocked) }
            item { DetailParamItem(session, paramState, connected, writeLocked) }
            item { DetailLogsItem(state) }
        }
    }
}

private class DetailNotificationSink(
    val center: NotificationCenter,
    val presenter: NotificationPresenter,
    val onFallback: (NotificationEvent) -> Unit,
) {
    fun dispatch(
        event: NotificationEvent,
        enabled: Boolean,
        pageVisible: Boolean = false,
    ) {
        val decision =
            center.dispatch(
                event = event,
                enabled = enabled,
                canNotify = presenter.canNotify,
                pageVisible = pageVisible,
            ) { title, body, target -> presenter.show(title, body, target) }
        if (decision == NotificationDecision.FALLBACK_BANNER) {
            onFallback(event)
        }
    }
}

private class DetailUiState {
    var authMode by mutableStateOf(AuthMode.TOKEN)
    var credential by mutableStateOf("")
    var timelineFilter by mutableStateOf<DeviceEventType?>(null)
    var metricsVersion by mutableStateOf(0)
    var healthMessage by mutableStateOf<String?>(null)
    var healthMessageSuccess by mutableStateOf(false)
    var healthJob by mutableStateOf<Job?>(null)
}

private class DetailRuntime(
    val lowBatteryTracker: LowBatteryAlertTracker,
    val healthCheck: DeviceHealthCheckController,
    val fileSaver: FileSaver,
    val connectObservedAt: Long,
    val rssiController: RssiSubscriptionController,
)

@Composable
private fun rememberDetailUiState(): DetailUiState = remember { DetailUiState() }

@Composable
private fun rememberDetailRuntime(
    session: DeviceSession,
    settings: AppSettings,
): DetailRuntime {
    val lowBatteryTracker =
        remember(session, settings.lowBatteryThreshold) {
            LowBatteryAlertTracker(settings.lowBatteryThreshold)
        }
    // 体检控制器绑定当前会话：链路断开时立即停止；不读取上次会话的缓存。
    val healthCheck =
        remember(session) {
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
        }
    val fileSaver = rememberFileSaver()
    val connectObservedAt = remember(session) { Clock.System.now().toEpochMilliseconds() }
    val rssiController =
        remember(session) {
            RssiSubscriptionController(readRssi = { session.connection.readRssi() })
        }
    return DetailRuntime(lowBatteryTracker, healthCheck, fileSaver, connectObservedAt, rssiController)
}

@Composable
private fun DetailHealthReportEffect(
    healthState: HealthCheckState,
    session: DeviceSession,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
) {
    val strings = LocalStrings.current
    LaunchedEffect(healthState.result) {
        healthState.result?.let { result ->
            session.lastHealthReport = healthReportText(result, format, zoneOffsetMinutes, strings)
        }
    }
}

@Composable
private fun DetailRssiEffect(
    connected: Boolean,
    settings: AppSettings,
    rssiController: RssiSubscriptionController,
    session: DeviceSession,
) {
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
}

@Composable
private fun DetailMetricSampleEffect(
    state: DeviceDetailState,
    rssiState: RssiSubscriptionState,
    metrics: DeviceMetricRepository,
    session: DeviceSession,
    onRecorded: () -> Unit,
) {
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
        val liveRssi =
            rssiState.latest
                ?.takeIf { it.source == RssiSource.CONNECTED_LIVE }
                ?.rssi
        metrics.record(state.deviceId, percent, liveRssi ?: session.device.rssi)
        onRecorded()
    }
}

@Composable
private fun DetailConnectionLostEffect(
    session: DeviceSession,
    connected: Boolean,
    onConnectionLost: () -> Unit,
) {
    LaunchedEffect(session, connected) {
        if (!connected && !session.userDisconnected) {
            onConnectionLost()
        }
    }
}

@Composable
private fun DetailLowBatteryEffect(
    state: DeviceDetailState,
    settings: AppSettings,
    lowBatteryTracker: LowBatteryAlertTracker,
    notificationSink: DetailNotificationSink,
) {
    val strings = LocalStrings.current
    // 低电量提醒：只在未充电且跌破阈值时提醒一次，电量回升或开始充电后由 tracker 复位。
    LaunchedEffect(state.batteryPercent, state.charging, settings.notifyLowBatteryEnabled, settings.lowBatteryThreshold) {
        val percent = state.batteryPercent ?: return@LaunchedEffect
        val shouldNotify = NotificationPolicy.shouldNotifyLowBattery(settings, percent, state.charging)
        val triggered = lowBatteryTracker.update(percent, state.charging)
        if (shouldNotify && triggered) {
            notificationSink.dispatch(
                NotificationEvent(
                    type = NotificationEventType.LOW_BATTERY,
                    title = strings.t(StringKey.NOTIFICATION_LOW_BATTERY_TITLE),
                    body =
                        strings.t(
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
}

@Composable
private fun DetailDisconnectNoticeEffect(
    connected: Boolean,
    settings: AppSettings,
    session: DeviceSession,
    state: DeviceDetailState,
    notified: Boolean,
    onNotified: (Boolean) -> Unit,
    notificationSink: DetailNotificationSink,
) {
    val strings = LocalStrings.current
    // 断开提醒：每次掉线最多提醒一次，重连成功后复位。
    LaunchedEffect(connected, settings.notifyDisconnectEnabled) {
        if (connected) {
            onNotified(false)
        } else if (
            !session.userDisconnected &&
            !notified &&
            NotificationPolicy.shouldNotifyDisconnect(settings)
        ) {
            onNotified(true)
            notificationSink.dispatch(
                NotificationEvent(
                    type = NotificationEventType.DISCONNECTED,
                    title = strings.t(StringKey.NOTIFICATION_DISCONNECTED_TITLE),
                    body =
                        strings.t(
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
}

@Composable
private fun DetailErrorTimelineEffect(
    state: DeviceDetailState,
    session: DeviceSession,
) {
    val strings = LocalStrings.current
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
}

@Composable
private fun DetailConfigAutoloadEffect(
    session: DeviceSession,
    state: DeviceDetailState,
    errorReporter: ErrorReporter,
) {
    LaunchedEffect(session.config, state.firmware, state.deviceInfo?.model) {
        session.config.updateTarget(
            DeviceConfigTarget(
                firmwareVersion = state.firmware,
                model = state.deviceInfo?.model,
                vendor = state.deviceInfo?.vendor,
            ),
        )
        // 首次进入或能力从未判定过时才读取；已加载的键不因刷新设备信息被清空。
        if (session.config.state.value.fields
                .none { it.loaded }
        ) {
            try {
                session.config.load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                errorReporter.record("config-autoload", error)
            }
        }
    }
}

@Composable
private fun DetailHeaderRow(
    connectedCount: Int,
    onOpenDevices: () -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
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
}

@Composable
private fun DetailDashboardItem(
    metrics: DeviceMetricRepository,
    metricsVersion: Int,
    state: DeviceDetailState,
    rssiState: RssiSubscriptionState,
    connectObservedAt: Long,
    zoneOffsetMinutes: Int,
    session: DeviceSession,
) {
    val strings = LocalStrings.current
    val metricLoad = remember(metricsVersion, state.deviceId) { metrics.seriesWithStatus(state.deviceId) }
    val liveReading =
        rssiState.latest
            ?.takeIf { it.source == RssiSource.CONNECTED_LIVE }
    DeviceDashboardCard(
        DeviceDashboardCardUi(
            detail = state,
            series = metricLoad.series,
            rssi = liveReading?.rssi ?: session.device.rssi,
            rssiSource = if (liveReading != null) RssiSource.CONNECTED_LIVE else RssiSource.SNAPSHOT,
            degradedReason = if (liveReading == null) rssiState.degradedReason else null,
            legacyMetricsIgnored = metricLoad.legacyIgnored,
            observedAtMillis = liveReading?.timestampMillis ?: connectObservedAt,
            strings = strings,
            zoneOffsetMinutes = zoneOffsetMinutes,
            accent = MaterialTheme.colorScheme.primary,
            muted = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun DetailHealthCard(
    healthState: HealthCheckState,
    ui: DetailUiState,
    runtime: DetailRuntime,
    session: DeviceSession,
    state: DeviceDetailState,
    settings: AppSettings,
    notificationSink: DetailNotificationSink,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val result = healthState.result
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
        ) {
            Text(strings.t(StringKey.HEALTH_TITLE), style = MaterialTheme.typography.titleMedium)
            if (healthState.running) {
                DetailHealthRunning(healthState, ui.healthJob)
            } else {
                DetailHealthRunButton(
                    healthCheck = runtime.healthCheck,
                    session = session,
                    ui = ui,
                    settings = settings,
                    notificationSink = notificationSink,
                    state = state,
                )
            }
            if (!healthState.running && result == null) {
                EmptyStatePanel()
            }
            result?.let { checked ->
                DetailHealthResult(checked, runtime.fileSaver, ui, format, zoneOffsetMinutes, healthState.running)
            }
            ui.healthMessage?.let { message ->
                DetailHealthMessage(message, ui.healthMessageSuccess, status)
            }
        }
    }
}

@Composable
private fun DetailHealthRunning(
    healthState: HealthCheckState,
    healthJob: Job?,
) {
    val strings = LocalStrings.current
    val healthProgress =
        if (healthState.total > 0) {
            healthState.completed.toFloat() / healthState.total
        } else {
            0f
        }
    val healthRunningText =
        strings.t(
            StringKey.HEALTH_RUNNING,
            "done" to healthState.completed.toString(),
            "total" to healthState.total.toString(),
        )
    LinearProgressIndicator(
        progress = { healthProgress },
        modifier =
            Modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(healthProgress, 0f..1f)
                contentDescription = healthRunningText
            },
    )
    Text(
        healthRunningText,
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = { healthJob?.cancel() }) {
        Text(strings.t(StringKey.HEALTH_CANCEL))
    }
}

@Composable
private fun DetailHealthRunButton(
    healthCheck: DeviceHealthCheckController,
    session: DeviceSession,
    ui: DetailUiState,
    settings: AppSettings,
    notificationSink: DetailNotificationSink,
    state: DeviceDetailState,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    Button(
        onClick = {
            ui.healthMessage = null
            ui.healthJob =
                scope.launchSafely(errorReporter, "health-check") {
                    val checked = healthCheck.run()
                    if (checked != null) {
                        session.events.record(
                            deviceId = session.detail.deviceId,
                            type = DeviceEventType.HEALTH,
                            summary = strings.t(StringKey.HEALTH_TITLE),
                            result =
                                if (checked.abortedByLinkLoss) {
                                    DeviceEventResult.FAILURE
                                } else {
                                    DeviceEventResult.SUCCESS
                                },
                            detail =
                                "ok=${checked.okCount}, abnormal=${checked.abnormalCount}, " +
                                    "failed=${checked.failedCount}, skipped=${checked.skippedCount}",
                        )
                        if (checked.abnormalCount > 0) {
                            notificationSink.dispatch(
                                NotificationEvent(
                                    type = NotificationEventType.HEALTH_ABNORMAL,
                                    title = strings.t(StringKey.NOTIFICATION_HEALTH_ABNORMAL_TITLE),
                                    body =
                                        strings.t(
                                            StringKey.NOTIFICATION_HEALTH_ABNORMAL_BODY,
                                            "device" to (state.deviceInfo?.model ?: state.deviceId),
                                            "count" to checked.abnormalCount.toString(),
                                        ),
                                    target =
                                        NotificationTarget(
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

@Composable
private fun DetailHealthResult(
    checked: HealthCheckResult,
    fileSaver: FileSaver,
    ui: DetailUiState,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
    running: Boolean,
) {
    DetailHealthResultSummary(checked)
    DetailHealthResultItems(checked)
    DetailHealthResultActions(checked, fileSaver, ui, format, zoneOffsetMinutes, running)
}

@Composable
private fun DetailHealthResultSummary(checked: HealthCheckResult) {
    val strings = LocalStrings.current
    val status = statusColors()
    val overallText =
        when {
            checked.abortedByLinkLoss -> strings.t(StringKey.HEALTH_OVERALL_ABORTED)
            checked.abnormalCount > 0 ->
                strings.t(
                    StringKey.HEALTH_OVERALL_ABNORMAL,
                    "count" to checked.abnormalCount.toString(),
                )
            checked.failedCount > 0 ->
                strings.t(
                    StringKey.HEALTH_OVERALL_FAILED,
                    "count" to checked.failedCount.toString(),
                )
            else -> strings.t(StringKey.HEALTH_OVERALL_OK)
        }
    Text(
        overallText,
        style = MaterialTheme.typography.titleSmall,
        color =
            when (checked.overall) {
                HealthCheckStatus.OK -> status.success
                HealthCheckStatus.ABNORMAL -> status.warning
                else -> status.error
            },
        modifier =
            Modifier.semantics {
                liveRegion =
                    if (checked.overall == HealthCheckStatus.OK) {
                        LiveRegionMode.Polite
                    } else {
                        LiveRegionMode.Assertive
                    }
                contentDescription = overallText
            },
    )
}

@Composable
private fun DetailHealthResultItems(checked: HealthCheckResult) {
    val strings = LocalStrings.current
    val status = statusColors()
    checked.items.forEach { item ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                strings.t(item.type.labelKey()) + " · " +
                    strings.t(item.status.labelKey()),
                style = MaterialTheme.typography.labelSmall,
                color =
                    when (item.status) {
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
}

@Composable
private fun DetailHealthResultActions(
    checked: HealthCheckResult,
    fileSaver: FileSaver,
    ui: DetailUiState,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
    running: Boolean,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    val clipboard = LocalClipboardManager.current
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        OutlinedButton(
            onClick = {
                clipboard.setText(
                    AnnotatedString(
                        healthReportText(checked, format, zoneOffsetMinutes, strings),
                    ),
                )
                ui.healthMessageSuccess = true
                ui.healthMessage = strings.t(StringKey.HEALTH_COPIED)
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
                    ui.healthMessageSuccess = save.status == FileSaveStatus.SAVED
                    ui.healthMessage =
                        when (save.status) {
                            FileSaveStatus.SAVED ->
                                strings.t(
                                    StringKey.DIAG_REPORT_SAVE_SUCCESS,
                                    "name" to fileName,
                                    "bytes" to format.number(text.length.toLong()),
                                )

                            FileSaveStatus.CANCELLED -> strings.t(StringKey.DIAG_REPORT_SAVE_CANCELLED)
                            FileSaveStatus.UNAVAILABLE -> strings.t(StringKey.LOGS_SAVE_UNAVAILABLE)
                            FileSaveStatus.FAILED ->
                                strings.t(
                                    StringKey.LOGS_SAVE_FAILED,
                                    "detail" to (save.message ?: strings.t(StringKey.UNKNOWN)),
                                )
                        }
                }
            },
            enabled = !running,
        ) {
            Text(strings.t(StringKey.HEALTH_SAVE))
        }
    }
}

@Composable
private fun DetailHealthMessage(
    message: String,
    success: Boolean,
    status: DesignTokens.StatusColors,
) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (success) status.success else status.error,
        modifier =
            Modifier.semantics {
                liveRegion =
                    if (success) {
                        LiveRegionMode.Polite
                    } else {
                        LiveRegionMode.Assertive
                    }
                contentDescription = message
            },
    )
}

@Composable
private fun DetailIdentityItem(
    state: DeviceDetailState,
    clipboard: ClipboardManager,
) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        Text(
            state.deviceId,
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            clipboard.setText(AnnotatedString(state.deviceId))
                        },
                    ).semantics { contentDescription = state.deviceId },
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

@Composable
private fun DetailAuthItem(
    controller: DeviceDetailController,
    state: DeviceDetailState,
    ui: DetailUiState,
    session: DeviceSession,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        Text(strings.t(StringKey.AUTH_MODE_LABEL), style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            FilterChip(
                selected = ui.authMode == AuthMode.TOKEN,
                onClick = { ui.authMode = AuthMode.TOKEN },
                label = { Text(strings.t(StringKey.AUTH_MODE_TOKEN)) },
            )
            FilterChip(
                selected = ui.authMode == AuthMode.SIGNED,
                onClick = { ui.authMode = AuthMode.SIGNED },
                label = { Text(strings.t(StringKey.AUTH_MODE_SIGNED)) },
            )
        }
        Text(
            strings.t(
                if (ui.authMode == AuthMode.SIGNED) {
                    StringKey.AUTH_SIGNED_HINT
                } else {
                    StringKey.AUTH_SECRET_HINT
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = ui.credential,
            onValueChange = { ui.credential = it },
            label = { Text(strings.t(StringKey.AUTH_CREDENTIAL_LABEL)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                if (ui.credential.isBlank()) {
                    Text(strings.t(StringKey.AUTH_SECRET_HINT), style = MaterialTheme.typography.labelSmall)
                }
            },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Button(
                enabled = ui.credential.isNotBlank(),
                onClick = {
                    val secret = ui.credential
                    scope.launchSafely(errorReporter, "auth") {
                        val ok =
                            if (ui.authMode == AuthMode.SIGNED) {
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
                            summary =
                                strings.t(
                                    if (ok) {
                                        StringKey.TIMELINE_EVENT_AUTH_OK
                                    } else {
                                        StringKey.TIMELINE_EVENT_AUTH_FAILED
                                    },
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

@Composable
private fun DetailLoadingItem(loading: Boolean) {
    if (!loading) return
    val strings = LocalStrings.current
    LoadingStatePanel(body = strings.t(StringKey.LOADING_DETAIL))
}

@Composable
private fun DetailActionsItem(
    writeLocked: Boolean,
    onOpenOta: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        if (writeLocked) {
            DisabledStatePanel(reason = StringKey.STATE_DISABLED_READ_ONLY)
        }
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

@Composable
private fun DetailErrorItem(
    state: DeviceDetailState,
    controller: DeviceDetailController,
    clipboard: ClipboardManager,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    val message = state.error ?: return
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        ErrorStatePanel(
            detail = strings.render(message),
            onRetry = {
                scope.launchSafely(errorReporter, "retry-info") { controller.refreshInfo() }
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
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(strings.render(message))) },
        ) {
            Text(strings.t(StringKey.LONG_PRESS_TO_COPY))
        }
    }
}

@Composable
private fun DetailQuickActionFailureItem(
    failure: QuickActionState.Failed,
    clipboard: ClipboardManager,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val failureText = strings.render(failure.error)
    Text(
        failureText,
        color = status.error,
        modifier =
            Modifier
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboard.setText(AnnotatedString(failureText))
                    },
                ).semantics {
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = failureText
                },
    )
}

@Composable
private fun DetailTimelineItem(
    session: DeviceSession,
    state: DeviceDetailState,
    ui: DetailUiState,
    format: LocaleFormat,
    zoneOffsetMinutes: Int,
) {
    val strings = LocalStrings.current
    val status = statusColors()
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        Text(strings.t(StringKey.TIMELINE_TITLE), style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            FilterChip(
                selected = ui.timelineFilter == null,
                onClick = { ui.timelineFilter = null },
                label = { Text(strings.t(StringKey.TIMELINE_FILTER_ALL)) },
            )
            DeviceEventType.entries.forEach { type ->
                FilterChip(
                    selected = ui.timelineFilter == type,
                    onClick = { ui.timelineFilter = type },
                    label = { Text(strings.t(type.timelineKey())) },
                )
            }
        }
        val events = session.events.list(state.deviceId, ui.timelineFilter)
        if (events.isEmpty()) {
            EmptyStatePanel(body = strings.t(StringKey.TIMELINE_EMPTY))
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
                        color =
                            when (event.result) {
                                DeviceEventResult.FAILURE -> status.error
                                DeviceEventResult.SUCCESS -> status.success
                                DeviceEventResult.INFO -> MaterialTheme.colorScheme.onSurface
                            },
                        modifier =
                            Modifier
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
                                ).semantics { contentDescription = event.summary },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailPresetItem(
    session: DeviceSession,
    presets: ConfigPresetRepository,
    writeLocked: Boolean,
) {
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

@Composable
private fun DetailConfigItem(
    session: DeviceSession,
    configState: ConfigEditorState,
    writeLocked: Boolean,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val errorReporter = LocalErrorReporter.current
    ConfigSection(
        controller = session.config,
        state = configState,
        writeLocked = writeLocked,
        onRefreshDevice = { scope.launchSafely(errorReporter, "refresh-info") { session.detail.refreshInfo() } },
        onSaved = { ok ->
            session.events.record(
                deviceId = session.detail.deviceId,
                type = DeviceEventType.CONFIG_CHANGE,
                summary =
                    strings.t(
                        if (ok) {
                            StringKey.TIMELINE_EVENT_CONFIG_SAVED
                        } else {
                            StringKey.TIMELINE_EVENT_CONFIG_FAILED
                        },
                    ),
                result = if (ok) DeviceEventResult.SUCCESS else DeviceEventResult.FAILURE,
            )
        },
    )
}

@Composable
private fun DetailParamItem(
    session: DeviceSession,
    paramState: ParamEditorState,
    connected: Boolean,
    writeLocked: Boolean,
) {
    val strings = LocalStrings.current
    ParamSection(
        controller = session.param,
        state = paramState,
        connected = connected,
        writeLocked = writeLocked,
        onSaved = { ok ->
            session.events.record(
                deviceId = session.detail.deviceId,
                type = DeviceEventType.PARAM_CHANGE,
                summary =
                    strings.t(
                        if (ok) {
                            StringKey.TIMELINE_EVENT_PARAM_SAVED
                        } else {
                            StringKey.TIMELINE_EVENT_PARAM_FAILED
                        },
                    ),
                result = if (ok) DeviceEventResult.SUCCESS else DeviceEventResult.FAILURE,
            )
        },
    )
}

@Composable
private fun DetailLogsItem(state: DeviceDetailState) {
    val strings = LocalStrings.current
    Text(strings.t(StringKey.DEVICE_LOGS), style = MaterialTheme.typography.titleMedium)
    if (state.logs.isEmpty()) {
        EmptyStatePanel()
    }
    state.logs.forEach { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier.semantics {
                    contentDescription = strings.t(StringKey.DEVICE_LOGS) + ": " + line
                },
        )
    }
}

@Composable
private fun QuickActionResult(quickAction: QuickActionState) {
    val strings = LocalStrings.current
    when (quickAction) {
        QuickActionState.Idle -> Unit
        is QuickActionState.Running -> {
            val runningText = strings.t(StringKey.QUICK_RUNNING)
            LinearProgressIndicator(
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        contentDescription = runningText
                    },
            )
            Text(runningText, style = MaterialTheme.typography.bodySmall)
        }

        is QuickActionState.Succeeded ->
            when (quickAction.type) {
                QuickActionType.REBOOT ->
                    Text(
                        strings.t(
                            StringKey.QUICK_REBOOT_SUCCESS,
                            "delay" to (quickAction.delayMillis ?: 0).toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    )

                QuickActionType.FACTORY_RESET ->
                    Text(
                        strings.t(StringKey.QUICK_FACTORY_SUCCESS),
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    )

                QuickActionType.HEARTBEAT ->
                    Text(
                        strings.t(
                            StringKey.QUICK_HEARTBEAT_SUCCESS,
                            "uptime" to "${quickAction.uptimeSeconds ?: 0}s",
                            "percent" to (
                                quickAction.batteryPercent?.toString()
                                    ?: strings.t(StringKey.UNKNOWN)
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
            }

        is QuickActionState.Failed -> Unit // 失败文案统一在页面错误区展示，支持长按复制。
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
    val enabled = quickActionEnabled(connected, writeLocked, busy)

    QuickActionCard(
        strings = strings,
        quickAction = quickAction,
        connected = connected,
        writeLocked = writeLocked,
        enabled = enabled,
        busy = busy,
        onReboot = { showRebootDialog = true },
        onFactoryReset = { showFactoryDialog = true },
        onHeartbeat = {
            scope.launchSafely(errorReporter, "quick-heartbeat") {
                controller.heartbeat()
            }
        },
    )

    QuickRebootDialog(
        strings = strings,
        visible = showRebootDialog,
        delayText = delayText,
        onDelayChange = { delayText = it },
        onDismiss = { showRebootDialog = false },
        onConfirm = { delay ->
            showRebootDialog = false
            scope.launchSafely(errorReporter, "quick-reboot") {
                controller.reboot(delay)
            }
        },
    )

    QuickFactoryResetDialog(
        strings = strings,
        visible = showFactoryDialog,
        confirmToken = confirmToken,
        onTokenChange = { confirmToken = it },
        onDismiss = { showFactoryDialog = false },
        onConfirm = { token ->
            showFactoryDialog = false
            scope.launchSafely(errorReporter, "quick-factory-reset") {
                controller.factoryReset(token)
            }
        },
    )
}

@Composable
private fun QuickActionCard(
    strings: StringResolver,
    quickAction: QuickActionState,
    connected: Boolean,
    writeLocked: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onReboot: () -> Unit,
    onFactoryReset: () -> Unit,
    onHeartbeat: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
        ) {
            Text(strings.t(StringKey.QUICK_ACTIONS_TITLE), style = MaterialTheme.typography.titleMedium)
            if (!connected) {
                DisabledStatePanel(reason = StringKey.STATE_DISABLED_NOT_CONNECTED)
            } else if (writeLocked) {
                DisabledStatePanel(reason = StringKey.STATE_DISABLED_READ_ONLY)
            }
            if (busy) {
                DisabledStatePanel(reason = StringKey.STATE_DISABLED_BUSY)
            }
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
                OutlinedButton(onClick = onReboot, enabled = enabled) {
                    Text(strings.t(StringKey.QUICK_REBOOT))
                }
                OutlinedButton(onClick = onFactoryReset, enabled = enabled) {
                    Text(strings.t(StringKey.QUICK_FACTORY_RESET))
                }
                Button(enabled = enabled, onClick = onHeartbeat) {
                    Text(strings.t(StringKey.QUICK_HEARTBEAT))
                }
            }

            QuickActionResult(quickAction)
        }
    }
}

@Composable
private fun QuickRebootDialog(
    strings: StringResolver,
    visible: Boolean,
    delayText: String,
    onDelayChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t(StringKey.QUICK_REBOOT_CONFIRM_TITLE)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                Text(strings.t(StringKey.QUICK_REBOOT_CONFIRM_MESSAGE))
                OutlinedTextField(
                    value = delayText,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() } && value.length <= 5) onDelayChange(value)
                    },
                    label = { Text(strings.t(StringKey.QUICK_REBOOT_DELAY_LABEL)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val delay = delayText.toIntOrNull()?.coerceIn(0, 65_535) ?: 0
                    onConfirm(delay)
                },
            ) { Text(strings.t(StringKey.CONFIRM)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.t(StringKey.CANCEL))
            }
        },
    )
}

@Composable
private fun QuickFactoryResetDialog(
    strings: StringResolver,
    visible: Boolean,
    confirmToken: String,
    onTokenChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
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
                    onValueChange = onTokenChange,
                    label = { Text(strings.t(StringKey.QUICK_CONFIRM_TOKEN_LABEL)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(confirmToken) }) {
                Text(strings.t(StringKey.CONFIRM))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.t(StringKey.CANCEL))
            }
        },
    )
}
