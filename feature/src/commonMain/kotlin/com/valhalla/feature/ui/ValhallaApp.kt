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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.BleFrameLink
import com.valhalla.data.ble.createBleClient
import com.valhalla.feature.detail.DeviceDetailController
import com.valhalla.feature.ota.OtaController
import com.valhalla.feature.ota.OtaState
import com.valhalla.feature.scan.ScanController
import com.valhalla.feature.scan.ScanUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 一次已建立连接对应的协议栈与控制器，详情页与 OTA 页共享同一调度器。 */
private class DeviceSession(
    val connection: BleConnection,
    val dispatcher: ConcurrentCommandDispatcher,
    val detail: DeviceDetailController,
    val ota: OtaController,
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
                        val session = DeviceSession(
                            connection = connection,
                            dispatcher = dispatcher,
                            detail = DeviceDetailController(
                                dispatcher = dispatcher,
                                deviceId = connection.deviceId,
                                disconnectLink = { connection.disconnect() },
                            ),
                            ota = OtaController(dispatcher),
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
            controller = current.session.ota,
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
    controller: OtaController,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val running = state is OtaState.Preparing || state is OtaState.Transferring || state is OtaState.Verifying

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("固件升级（OTA）", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }

        Text(
            "流程：START_OTA（总长度 + CRC16）→ 逐片 OTA_DATA（按协议偏移）→ FINISH_OTA 校验。",
            style = MaterialTheme.typography.bodySmall,
        )

        when (val current = state) {
            OtaState.Idle -> Text("等待开始")
            is OtaState.Preparing -> Text("准备中：${current.totalBytes} 字节，CRC=${current.crc16}")
            is OtaState.Transferring -> {
                LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                Text("已发送 ${current.sentBytes}/${current.totalBytes} 字节")
            }
            is OtaState.Verifying -> Text("校验中：CRC=${current.crc16}")
            is OtaState.Succeeded -> Text("升级完成：${current.chunks} 片，CRC=${current.crc16}")
            is OtaState.Failed -> Text(
                "升级失败：${current.reason}（已发送 ${current.sentBytes} 字节）",
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                val firmware = ByteArray(2048) { index -> (index % 251).toByte() }
                scope.launch { controller.start(firmware) }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("使用占位固件开始升级")
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
