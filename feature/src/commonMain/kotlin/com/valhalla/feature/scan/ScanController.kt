package com.valhalla.feature.scan

import com.valhalla.data.ble.BleClient
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.ScanFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** 扫描页 UI 状态。 */
data class ScanUiState(
    val scanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val error: String? = null,
)

/**
 * 扫描控制器：把无限扫描流收敛成可供 Compose 渲染的快照。
 *
 * 同一 deviceId 只保留最新一条（RSSI 实时变化），按信号强度降序排列。
 */
class ScanController(
    private val bleClient: BleClient,
    private val scope: CoroutineScope,
) {

    private val mutableState = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = mutableState.asStateFlow()

    private var scanJob: Job? = null

    fun startScan(filter: ScanFilter = ScanFilter()) {
        if (scanJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(scanning = true, error = null)

        scanJob = scope.launch {
            try {
                bleClient.scan(filter)
                    .catch { error ->
                        if (error is CancellationException) throw error
                        mutableState.value = mutableState.value.copy(
                            scanning = false,
                            error = error.message ?: "扫描失败",
                        )
                    }
                    .collect { device -> upsert(device) }
            } finally {
                mutableState.value = mutableState.value.copy(scanning = false)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        mutableState.value = mutableState.value.copy(scanning = false)
    }

    fun clearDevices() {
        mutableState.value = mutableState.value.copy(devices = emptyList())
    }

    private fun upsert(device: BleDevice) {
        val existing = mutableState.value.devices
        val updated = existing.filterNot { it.deviceId == device.deviceId } + device
        mutableState.value = mutableState.value.copy(
            devices = updated.sortedByDescending { it.rssi },
            error = null,
        )
    }
}
