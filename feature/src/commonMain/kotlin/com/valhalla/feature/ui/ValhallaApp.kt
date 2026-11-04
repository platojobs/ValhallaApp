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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
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
import com.valhalla.core.logging.formatAbsoluteTime
import com.valhalla.core.logging.formatForCopy
import com.valhalla.core.logging.formatRelativeTime
import com.valhalla.core.logging.parseLocalDateTimeMillis
import com.valhalla.core.logging.validateByteLimitKb
import com.valhalla.core.logging.validateEntryLimit
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
import com.valhalla.data.share.ShareStatus
import com.valhalla.data.share.ShareableText
import com.valhalla.data.share.createTextSharer
import com.valhalla.data.share.currentTimeZoneOffsetMinutes
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
import com.valhalla.feature.ota.OtaController
import com.valhalla.feature.ota.OtaState
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.feature.scan.ScanController
import com.valhalla.feature.scan.ScanUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
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

            item {
                ConfigSection(
                    controller = session.config,
                    state = configState,
                    onRefreshDevice = { scope.launch { controller.refreshInfo() } },
                )
            }

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
private fun ConfigSection(
    controller: ConfigEditorController,
    state: ConfigEditorState,
    onRefreshDevice: () -> Unit,
) {
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
                "配置键能力按固件版本与型号判定；不支持的键已隐藏，避免下发后设备返回 INVALID_PARAM。",
                style = MaterialTheme.typography.bodySmall,
            )
            state.capabilityNotice?.let { notice ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        notice,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefreshDevice) { Text("刷新设备信息") }
                }
                state.hiddenFields.forEach { field ->
                    field.capability.reason?.let { reason ->
                        Text("· $reason", style = MaterialTheme.typography.bodySmall)
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

private enum class ExportAction { SAVE, SHARE }

@OptIn(ExperimentalTime::class)
@Composable
private fun PacketLogScreen(
    packetLog: InMemoryPacketLog,
    onBack: () -> Unit,
) {
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
        "格式：YYYY-MM-DD HH:mm:ss"
    } else {
        null
    }
    val toError = if (toText.isNotBlank() && toMillis == null) {
        "格式：YYYY-MM-DD HH:mm:ss"
    } else {
        null
    }
    val rangeError = if (fromMillis != null && toMillis != null && fromMillis > toMillis) {
        "起始时间不能晚于结束时间"
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
            exportMessage = error
            return
        }
        val opts = options
        if (opts == null) {
            exportMessage = entryLimit.error ?: byteLimit.error ?: "上限输入非法"
            return
        }
        if (estimate?.matchedCount == 0) {
            exportMessage = "没有匹配的报文，未导出空文件"
            return
        }

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
                    exportMessage = result.notice ?: "没有匹配的报文，未导出空文件"
                    return@launch
                }
                val fileName = PacketLogExporter.resolveFileName(result.fileName, exportedNames)
                when (action) {
                    ExportAction.SAVE -> {
                        val save = fileSaver.save(SaveableFile(fileName, result.text, format.mimeType))
                        when (save.status) {
                            FileSaveStatus.SAVED -> {
                                exportedNames += fileName
                                exportMessage =
                                    "已保存 $fileName（${result.entryCount} 条，${result.byteSize} 字节）"
                            }

                            FileSaveStatus.CANCELLED -> exportMessage = "已取消保存，未写入文件"
                            FileSaveStatus.UNAVAILABLE ->
                                exportMessage = "当前平台不支持保存为文件，可改用分享"

                            FileSaveStatus.FAILED -> exportMessage = "保存失败：${save.message ?: "未知原因"}"
                        }
                    }

                    ExportAction.SHARE -> {
                        val shared = sharer.share(ShareableText(fileName, result.text, format.mimeType))
                        when (shared.status) {
                            ShareStatus.SHARED -> {
                                exportedNames += fileName
                                exportMessage =
                                    "已分享 $fileName（${result.entryCount} 条，${result.byteSize} 字节）"
                            }

                            ShareStatus.UNAVAILABLE -> exportMessage = "当前平台不支持系统分享"
                            ShareStatus.FAILED -> exportMessage = "分享失败：${shared.message ?: "未知原因"}"
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                exportMessage = "已取消导出，未写入文件"
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
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("报文日志", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(
                enabled = canExport,
                onClick = { startExport(ExportAction.SAVE) },
            ) { Text("保存文件") }
            TextButton(
                enabled = canExport,
                onClick = { startExport(ExportAction.SHARE) },
            ) { Text("分享") }
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
                selected = selectedCommands.isEmpty(),
                onClick = { selectedCommands = emptySet() },
                label = { Text("全部命令") },
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
                label = { Text("仅错误") },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = fromText,
                onValueChange = { fromText = it },
                label = { Text("起始时间") },
                placeholder = { Text("YYYY-MM-DD HH:mm:ss") },
                singleLine = true,
                isError = fromError != null || rangeError != null,
                supportingText = { Text(fromError ?: rangeError ?: "留空表示不限，含边界") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = toText,
                onValueChange = { toText = it },
                label = { Text("结束时间") },
                placeholder = { Text("YYYY-MM-DD HH:mm:ss") },
                singleLine = true,
                isError = toError != null || rangeError != null,
                supportingText = { Text(toError ?: rangeError ?: "留空表示不限，含边界") },
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = maxEntriesText,
                onValueChange = { maxEntriesText = it },
                label = { Text("条数上限") },
                singleLine = true,
                isError = !entryLimit.valid,
                supportingText = {
                    Text(
                        entryLimit.error
                            ?: "允许 ${PacketLogExportOptions.MIN_ENTRIES}..${PacketLogExportOptions.MAX_ENTRIES}",
                    )
                },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = maxBytesKbText,
                onValueChange = { maxBytesKbText = it },
                label = { Text("大小上限（KB）") },
                singleLine = true,
                isError = !byteLimit.valid,
                supportingText = {
                    Text(
                        byteLimit.error
                            ?: "允许 1..${PacketLogExportOptions.MAX_BYTES / 1024}",
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val estimateLine = when {
                rangeError != null -> "时间范围无效：$rangeError"
                fromError != null || toError != null -> "时间格式无效"
                options == null -> "上限输入无效：${entryLimit.error ?: byteLimit.error}"
                estimate == null -> "正在估算导出量…"
                estimate!!.matchedCount == 0 -> "没有匹配的报文，不会导出空文件"
                estimate!!.notice != null -> estimate!!.notice!!
                estimate!!.truncated ->
                    "预计导出 ${estimate!!.estimatedEntries} 条 / ${formatBytes(estimate!!.estimatedBytes.toLong())}" +
                        "（匹配 ${estimate!!.matchedCount} 条，超出上限将截断最旧的一端）"

                else ->
                    "预计导出 ${estimate!!.estimatedEntries} 条 / ${formatBytes(estimate!!.estimatedBytes.toLong())}"
            }
            Text(
                estimateLine,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (exporting) {
                TextButton(onClick = { exportJob?.cancel() }) { Text("取消") }
            }
        }

        if (exporting) {
            val current = progress
            Text(
                "导出中 · ${current?.phase?.label() ?: "准备"} ${current?.processed ?: 0}/${current?.total ?: 0}",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { current?.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
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

        exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

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
                    nowMillis = nowMillis,
                    zoneOffsetMinutes = zoneOffsetMinutes,
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

private fun PacketLogExportPhase.label(): String = when (this) {
    PacketLogExportPhase.FILTERING -> "筛选"
    PacketLogExportPhase.RENDERING -> "生成"
    PacketLogExportPhase.ASSEMBLING -> "拼装"
}

@Composable
private fun PacketLogCard(
    entry: PacketLogEntry,
    nowMillis: Long,
    zoneOffsetMinutes: Int,
    onCopy: () -> Unit,
) {
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
            Text(
                "${formatAbsoluteTime(entry.timestampMillis, zoneOffsetMinutes)} · " +
                    formatRelativeTime(entry.timestampMillis, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
