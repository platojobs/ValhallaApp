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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.command.PacketRecordingFrameLink
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.logging.InMemoryPacketLog
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogFilter
import com.valhalla.core.logging.filterPacketEntries
import com.valhalla.core.profile.DeviceProfile
import com.valhalla.core.profile.DeviceProfileRepository
import com.valhalla.core.profile.OtaOutcome
import com.valhalla.core.protocol.CommandType
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.BleFrameLink
import com.valhalla.data.ble.createBleClient
import com.valhalla.data.firmware.createOtaCheckpointStore
import com.valhalla.data.profile.createDeviceProfileStore
import com.valhalla.data.security.createTokenStore
import com.valhalla.feature.config.ConfigEditorController
import com.valhalla.feature.config.ConfigEditorState
import com.valhalla.feature.config.ConfigFieldState
import com.valhalla.feature.detail.DeviceDetailController
import com.valhalla.feature.firmware.FirmwareSelectionController
import com.valhalla.feature.firmware.FirmwareSelectionState
import com.valhalla.feature.firmware.rememberFirmwarePicker
import com.valhalla.feature.ota.OtaController
import com.valhalla.feature.ota.OtaState
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.feature.scan.ScanController
import com.valhalla.feature.scan.ScanUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** 一次已建立连接对应的协议栈与控制器，详情页与 OTA 页共享同一调度器与断点存储。 */
private class DeviceSession(
    val connection: BleConnection,
    val dispatcher: ConcurrentCommandDispatcher,
    val detail: DeviceDetailController,
    val ota: OtaController,
    val checkpointStore: OtaCheckpointStore,
    val config: ConfigEditorController,
    val packetLog: InMemoryPacketLog,
    val profileRepository: DeviceProfileRepository,
)

private sealed interface Route {
    data object Scan : Route
    data class Detail(val session: DeviceSession) : Route
    data class Ota(val session: DeviceSession) : Route
    data class Logs(val session: DeviceSession) : Route
}

/**
 * Compose Multiplatform 根界面：扫描 → 设备详情 → OTA 三段式流程。
 * Android / iOS 共用同一套页面，平台差异封装在 [createBleClient] 之后。
 */
@Composable
fun ValhallaApp() {
    val bleClient = remember { createBleClient() }
    val scope = rememberCoroutineScope()
    val scanController = remember { ScanController(bleClient, scope) }
    // 档案仓储跨连接共享，保证扫描/详情页看到同一份设备历史。
    val profileRepository = remember { DeviceProfileRepository(createDeviceProfileStore()) }

    var route by remember { mutableStateOf<Route>(Route.Scan) }
    var connectingDeviceId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            scanController.stopScan()
            bleClient.close()
        }
    }

    when (val current = route) {
        Route.Scan -> ScanScreen(
            state = scanController.state.collectAsState().value,
            connectingDeviceId = connectingDeviceId,
            errorMessage = errorMessage,
            onStart = {
                errorMessage = null
                scanController.startScan()
            },
            onStop = { scanController.stopScan() },
            onSelect = { device ->
                scope.launch {
                    connectingDeviceId = device.deviceId
                    errorMessage = null
                    try {
                        val connection = bleClient.connect(device.deviceId)
                        scanController.stopScan()
                        val packetLog = InMemoryPacketLog()
                        val dispatcher = ConcurrentCommandDispatcher(
                            PacketRecordingFrameLink(BleFrameLink(connection), packetLog),
                        )
                        val checkpointStore = createOtaCheckpointStore()
                        val session = DeviceSession(
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
                        route = Route.Detail(session)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        errorMessage = error.message ?: "连接失败"
                    } finally {
                        connectingDeviceId = null
                    }
                }
            },
        )

        is Route.Detail -> DetailScreen(
            session = current.session,
            onBack = {
                scope.launch {
                    current.session.detail.disconnect()
                    current.session.dispatcher.close()
                    route = Route.Scan
                }
            },
            onOpenOta = { route = Route.Ota(current.session) },
            onOpenLogs = { route = Route.Logs(current.session) },
        )

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
    connectingDeviceId: String?,
    errorMessage: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelect: (BleDevice) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设备扫描", style = MaterialTheme.typography.headlineSmall)
        Text(
            "确保设备已上电并处于广播模式。Android 需要蓝牙权限，iOS 需要蓝牙授权。",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !state.scanning) { Text("开始扫描") }
            OutlinedButton(onClick = onStop, enabled = state.scanning) { Text("停止") }
        }

        if (state.scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        (errorMessage ?: state.error)?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        if (state.devices.isEmpty()) {
            Text("暂无设备", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.devices, key = { it.deviceId }) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(device) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            device.name ?: "未命名设备",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${device.deviceId} · RSSI ${device.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        connectingDeviceId?.let {
            Text("正在连接 $it …", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DetailScreen(
    session: DeviceSession,
    onBack: () -> Unit,
    onOpenOta: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val controller = session.detail
    val state by controller.state.collectAsState()
    val configState by session.config.state.collectAsState()
    val scope = rememberCoroutineScope()
    val profile = session.profileRepository.get(state.deviceId)

    LaunchedEffect(session.config) { session.config.load() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("设备详情", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.deviceId, style = MaterialTheme.typography.titleMedium)
                    Text("状态：${state.deviceState}")
                    Text("固件版本：${state.firmwareVersion ?: "未知"}")
                    Text(
                        "电量：${state.batteryPercent?.let { "$it%" } ?: "未知"}" +
                            if (state.charging == true) "（充电中）" else "",
                    )
                    state.deviceInfo?.let { info ->
                        Text("型号：${info.vendor} ${info.model} · 序列号 ${info.serialNumber}")
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { controller.authenticate("demo-token") } }) {
                        Text("鉴权")
                    }
                    OutlinedButton(onClick = { scope.launch { controller.refreshInfo() } }) {
                        Text("刷新信息")
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenOta, modifier = Modifier.fillMaxWidth()) {
                        Text("进入 OTA 升级")
                    }
                    OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                        Text("报文日志（TX/RX）")
                    }
                }
            }

            state.error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }

            profile?.let { snapshot ->
                item { ProfileCard(snapshot) }
            }

            item { ConfigSection(controller = session.config, state = configState) }

            item { Text("设备日志", style = MaterialTheme.typography.titleMedium) }
            items(state.logs) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: DeviceProfile) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("设备档案", style = MaterialTheme.typography.titleMedium)
            Text("最后连接：${formatEpoch(profile.lastConnectedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "最近固件：${profile.lastKnownFirmwareVersion ?: "未知"} · " +
                    "电量：${profile.lastKnownBatteryPercent?.let { "$it%" } ?: "未知"}",
                style = MaterialTheme.typography.bodySmall,
            )
            profile.lastOtaRecord?.let { record ->
                Text(
                    "最近 OTA：${record.outcome.label()} · ${record.firmwareVersion} · " +
                        "耗时 ${record.durationMillis} ms",
                    style = MaterialTheme.typography.bodySmall,
                )
                record.errorCode?.let {
                    Text("错误码：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                record.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ConfigSection(controller: ConfigEditorController, state: ConfigEditorState) {
    val scope = rememberCoroutineScope()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "设备配置",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { scope.launch { controller.load() } },
                    enabled = !state.loading && !state.saving,
                ) { Text(if (state.loading) "读取中…" else "重新读取") }
            }
            Text(
                "只有命令表定义的可编辑键允许下发；未知键与设备只读键会标注原因。",
                style = MaterialTheme.typography.bodySmall,
            )
            state.fields.forEach { field ->
                ConfigFieldRow(
                    field = field,
                    savingAny = state.saving,
                    onTextChange = { controller.updateText(field.spec.key, it) },
                    onSave = { scope.launch { controller.save(field.spec.key) } },
                )
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { message ->
                Text(
                    message + (state.errorCode?.let { "（${it.describe()}）" } ?: ""),
                    color = MaterialTheme.colorScheme.error,
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
    val supporting = when {
        field.loading -> "读取中…"
        field.loadError != null -> field.loadError
        field.validationError != null -> field.validationError
        !field.editable -> field.spec.readOnlyReason ?: "只读"
        field.saving -> "保存中…"
        field.lastSavedAtEpochMillis != null && !field.dirty -> "已保存"
        field.dirty -> "未保存"
        else -> "可编辑"
    }
    val isError = field.loadError != null || field.validationError != null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = field.text,
                onValueChange = onTextChange,
                enabled = field.editable && !field.saving && !savingAny,
                label = { Text(field.spec.displayName) },
                singleLine = true,
                supportingText = {
                    Text(
                        supporting,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onSave,
                enabled = field.editable && field.dirty && !field.saving && !savingAny,
            ) {
                Text(if (field.saving) "保存中" else "保存")
            }
        }
    }
}

@Composable
private fun PacketLogScreen(
    packetLog: InMemoryPacketLog,
    onBack: () -> Unit,
) {
    val entries by packetLog.entries.collectAsState()
    var query by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf<CommandType?>(null) }
    val clipboard = LocalClipboardManager.current

    val filtered = remember(entries, query, errorsOnly, command) {
        filterPacketEntries(
            entries = entries,
            filter = PacketLogFilter(command = command, errorsOnly = errorsOnly, keyword = query),
            limit = InMemoryPacketLog.DEFAULT_CAPACITY,
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("报文日志", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索命令名 / 摘要 / HEX / seq") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = command == null,
                onClick = { command = null },
                label = { Text("全部命令") },
            )
            entries.map { it.command }.distinct().forEach { item ->
                FilterChip(
                    selected = command == item,
                    onClick = { command = if (command == item) null else item },
                    label = { Text(item.name) },
                )
            }
            FilterChip(
                selected = errorsOnly,
                onClick = { errorsOnly = !errorsOnly },
                label = { Text("仅错误") },
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "显示 ${filtered.size} / ${entries.size} 条 · 上限 ${InMemoryPacketLog.DEFAULT_CAPACITY} 条，超出滚动淘汰",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { packetLog.clear() }) { Text("清空") }
        }

        if (filtered.isEmpty()) {
            Text("暂无报文。连接设备并触发鉴权/信息/OTA 后，这里会记录 TX/RX 报文。")
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.id }) { entry ->
                PacketLogCard(
                    entry = entry,
                    onCopy = { clipboard.setText(AnnotatedString(entry.formatForCopy())) },
                )
            }
        }
    }
}

@Composable
private fun PacketLogCard(entry: PacketLogEntry, onCopy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${entry.direction.name} · ${entry.command.name} · seq=${entry.sequence}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy) { Text("复制") }
            }
            Text(entry.summary, style = MaterialTheme.typography.bodySmall)
            entry.error?.let { error ->
                Text("错误：$error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text("HEX ${entry.hex}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun OtaOutcome.label(): String = when (this) {
    OtaOutcome.SUCCEEDED -> "成功"
    OtaOutcome.FAILED -> "失败"
    OtaOutcome.ABORTED -> "中止"
}

@OptIn(ExperimentalTime::class)
private fun formatEpoch(epochMillis: Long): String =
    Instant.fromEpochMilliseconds(epochMillis).toString()

@Composable
private fun OtaScreen(
    session: DeviceSession,
    onBack: () -> Unit,
) {
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
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("固件升级（OTA）", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (running) scope.launch { controller.abort() }
                    onBack()
                },
            ) { Text("返回") }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "设备版本：${detailState.firmwareVersion ?: "未知"} · 型号：${detailState.deviceInfo?.model ?: "未知"}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { session.detail.refreshInfo() } }) {
                Text("刷新设备信息")
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
            Text(if (selectionState.selected) "重新选择固件" else "选择固件")
        }

        if (selectionState.picking) {
            Text("正在打开文件选择器…", style = MaterialTheme.typography.bodySmall)
        }
        if (selectionState.prechecking) {
            LinearProgressIndicator(
                progress = { selectionState.precheckProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "正在预检固件… ${(selectionState.precheckProgress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { selection.cancelPrecheck() }) {
                Text("取消预检")
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
            OtaState.Idle -> Text("等待开始")
            is OtaState.Preparing -> Text(
                "准备中：${current.totalBytes} 字节，CRC=${current.crc16}" +
                    (current.sessionId?.let { "，会话 $it" } ?: ""),
            )

            is OtaState.Transferring -> {
                LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                val resumeNote = if (current.initialOffset > 0) {
                    "（从 ${formatBytes(current.initialOffset.toLong())} 续传）"
                } else {
                    ""
                }
                Text("${current.stage.label()}：已发送 ${current.sentBytes}/${current.totalBytes} 字节（${(current.progress * 100).toInt()}%）$resumeNote")
                if (current.retransmissions > 0) {
                    Text("分片重传 ${current.retransmissions} 次", style = MaterialTheme.typography.bodySmall)
                }
                current.resumeFallbackReason?.let { reason ->
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            is OtaState.Verifying -> Text("校验中：设备正在核对 CRC=${current.crc16}")
            is OtaState.Succeeded -> Text(
                "升级完成：${current.chunks} 片，CRC=${current.crc16}" +
                    if (current.rebootRequired) "，设备将重启" else "",
            )

            is OtaState.Aborted -> Text(
                "升级已中止：${current.reason}（已发送 ${current.sentBytes}/${current.totalBytes} 字节）",
                color = MaterialTheme.colorScheme.error,
            )

            is OtaState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("升级失败：${current.reason}", color = MaterialTheme.colorScheme.error)
                Text("已发送 ${current.sentBytes}/${current.totalBytes} 字节")
                current.resumePoint?.let { point ->
                    Text(
                        "已保存断点：${point.sentBytes}/${point.totalBytes} 字节（会话 ${point.sessionId}）",
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
            Text("开始升级")
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
                    "继续上次升级（${point.sentBytes}/${point.totalBytes}）"
                } else {
                    "继续上次升级"
                },
            )
        }

        if (selectionState.resumePoint != null || selectionState.resumeBlockedReason != null) {
            TextButton(onClick = { selection.clearResume() }, enabled = !running) {
                Text("清除断点")
            }
        }

        OutlinedButton(
            onClick = { scope.launch { controller.abort() } },
            enabled = running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("中止升级")
        }

        OutlinedButton(
            onClick = { controller.reset() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重置状态")
        }
    }
}

@Composable
private fun FirmwareCard(
    selectionState: FirmwareSelectionState,
    onRecheck: () -> Unit,
) {
    if (selectionState.packageName == null && selectionState.error == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            selectionState.packageName?.let { name ->
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "版本 ${selectionState.version} · payload ${formatBytes(selectionState.payloadBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "CRC32 声明 ${hex32(selectionState.declaredCrc32)} · 实际 ${hex32(selectionState.computedCrc32)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "CRC16 ${hex16(selectionState.computedCrc16)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val resumePoint = selectionState.resumePoint
            if (resumePoint != null && selectionState.canResume) {
                Text(
                    "可续传，从 ${formatBytes(resumePoint.sentBytes.toLong())} 继续" +
                        "（已传输 ${resumePoint.sentBytes}/${resumePoint.totalBytes} 字节）",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            selectionState.resumeBlockedReason?.let { reason ->
                Text(
                    "无法续传：$reason",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val precheck = selectionState.precheck
            if (precheck == null) {
                if (selectionState.error == null) {
                    Text("尚未预检", style = MaterialTheme.typography.bodySmall)
                }
            } else if (precheck.passed) {
                Text("预检通过，可以开始升级", color = MaterialTheme.colorScheme.primary)
            } else {
                Text("预检未通过，OTA 不可启动：", color = MaterialTheme.colorScheme.error)
                precheck.errors.forEach { error ->
                    Text("· ${error.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            selectionState.error?.let { message ->
                Text(
                    "固件加载失败：$message" + (selectionState.errorCode?.let { "（$it）" } ?: ""),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TextButton(onClick = onRecheck) { Text("重新预检") }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MiB（$bytes 字节）"
    bytes >= 1024L -> "${bytes / 1024} KiB（$bytes 字节）"
    else -> "$bytes 字节"
}

private fun hex32(value: Long?): String =
    value?.let { "0x" + it.toString(16).padStart(8, '0') } ?: "—"

private fun hex16(value: Int?): String =
    value?.let { "0x" + it.toString(16).uppercase().padStart(4, '0') } ?: "—"

private fun OtaStage.label(): String = when (this) {
    OtaStage.PREPARING -> "准备中"
    OtaStage.TRANSFERRING -> "传输中"
    OtaStage.VERIFYING -> "校验中"
    OtaStage.APPLYING -> "应用固件"
    OtaStage.COMPLETED -> "已完成"
}
