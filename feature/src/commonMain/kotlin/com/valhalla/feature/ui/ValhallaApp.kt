package com.valhalla.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.BleFrameLink
import com.valhalla.data.ble.createBleClient
import com.valhalla.data.firmware.createOtaCheckpointStore
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

/** 一次已建立连接对应的协议栈与控制器，详情页与 OTA 页共享同一调度器与断点存储。 */
private class DeviceSession(
    val connection: BleConnection,
    val dispatcher: ConcurrentCommandDispatcher,
    val detail: DeviceDetailController,
    val ota: OtaController,
    val checkpointStore: OtaCheckpointStore,
)

private sealed interface Route {
    data object Scan : Route
    data class Detail(val session: DeviceSession) : Route
    data class Ota(val session: DeviceSession) : Route
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
                        val dispatcher = ConcurrentCommandDispatcher(BleFrameLink(connection))
                        val checkpointStore = createOtaCheckpointStore()
                        val session = DeviceSession(
                            connection = connection,
                            dispatcher = dispatcher,
                            detail = DeviceDetailController(
                                dispatcher = dispatcher,
                                deviceId = connection.deviceId,
                                disconnectLink = { connection.disconnect() },
                            ),
                            ota = OtaController(
                                dispatcher = dispatcher,
                                scope = scope,
                                connectionState = connection.connected,
                                checkpointStore = checkpointStore,
                            ),
                            checkpointStore = checkpointStore,
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
            controller = current.session.detail,
            onBack = {
                scope.launch {
                    current.session.detail.disconnect()
                    current.session.dispatcher.close()
                    route = Route.Scan
                }
            },
            onOpenOta = { route = Route.Ota(current.session) },
        )

        is Route.Ota -> OtaScreen(
            session = current.session,
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
    controller: DeviceDetailController,
    onBack: () -> Unit,
    onOpenOta: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("设备详情", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }

        Text(state.deviceId, style = MaterialTheme.typography.titleMedium)
        Text("状态：${state.deviceState}")
        Text("固件版本：${state.firmwareVersion ?: "未知"}")
        Text("电量：${state.batteryPercent?.let { "$it%" } ?: "未知"}" + if (state.charging == true) "（充电中）" else "")
        state.deviceInfo?.let { info ->
            Text("型号：${info.vendor} ${info.model} · 序列号 ${info.serialNumber}")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { controller.authenticate("demo-token") } }) {
                Text("鉴权")
            }
            OutlinedButton(onClick = { scope.launch { controller.refreshInfo() } }) {
                Text("刷新信息")
            }
        }

        OutlinedButton(onClick = onOpenOta, modifier = Modifier.fillMaxWidth()) {
            Text("进入 OTA 升级")
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("设备日志", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.logs) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

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
            enabled = !running && !selectionState.picking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (selectionState.selected) "重新选择固件" else "选择固件")
        }

        if (selectionState.picking) {
            Text("正在打开文件选择器…", style = MaterialTheme.typography.bodySmall)
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
                if (firmware != null) scope.launch { controller.startPackage(firmware) }
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
                    scope.launch { controller.startPackage(firmware, resumeFrom = point) }
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
                    "固件读取失败：$message" + (selectionState.errorCode?.let { "（$it）" } ?: ""),
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
