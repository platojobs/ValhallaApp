package com.valhalla.feature.scan

import com.valhalla.data.ble.BleClient
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.ScanFilter
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** 扫描页 UI 状态。 */
data class ScanUiState(
    val scanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val error: UiMessage? = null,
    /** 扫描持续超过阈值仍无设备；与「无权限 / 蓝牙关闭」区分展示。 */
    val timedOut: Boolean = false,
)

/**
 * 扫描控制器：把无限扫描流收敛成可供 Compose 渲染的快照。
 *
 * 同一 deviceId 只保留最新一条（RSSI 实时变化），按信号强度降序排列。
 *
 * 异常边界：扫描流异常会被 `.catch` 转成 [ScanUiState.error] 并停止扫描；
 * `.catch` 自身或 `upsert` 抛出的异常再由外层 try/catch 兜底，
 * 无论哪条路径结束，`finally` 都会把 [ScanUiState.scanning] 置回 false，避免永久「扫描中」。
 */
class ScanController(
    private val bleClient: BleClient,
    private val scope: CoroutineScope,
    private val scanTimeoutMillis: Long = DEFAULT_SCAN_TIMEOUT_MILLIS,
    private val onError: (Throwable) -> Unit = {},
) {

    private val mutableState = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = mutableState.asStateFlow()

    private var scanJob: Job? = null
    private var timeoutJob: Job? = null

    fun startScan(filter: ScanFilter = ScanFilter()) {
        if (scanJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(scanning = true, error = null, timedOut = false)

        timeoutJob = scope.launch {
            delay(scanTimeoutMillis)
            val current = mutableState.value
            if (current.scanning && current.devices.isEmpty() && current.error == null) {
                mutableState.value = current.copy(timedOut = true)
            }
        }

        scanJob = scope.launch {
            try {
                bleClient.scan(filter)
                    .catch { error ->
                        if (error is CancellationException) throw error
                        timeoutJob?.cancel()
                        mutableState.value = mutableState.value.copy(
                            scanning = false,
                            error = UiMessage(
                                StringKey.SCAN_EMPTY_ERROR,
                                mapOf("detail" to (error.message ?: StringKey.UNKNOWN.name)),
                            ),
                        )
                    }
                    .collect { device -> upsert(device) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // 兜底：catch 操作符/去重逻辑自身异常也不能让扫描协程变成未捕获异常。
                timeoutJob?.cancel()
                onError(error)
                mutableState.value = mutableState.value.copy(
                    scanning = false,
                    error = UiMessage(
                        StringKey.SCAN_EMPTY_ERROR,
                        mapOf("detail" to (error.message ?: StringKey.UNKNOWN.name)),
                    ),
                )
            } finally {
                timeoutJob?.cancel()
                mutableState.value = mutableState.value.copy(scanning = false)
            }
        }
    }

    fun stopScan() {
        timeoutJob?.cancel()
        timeoutJob = null
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
            timedOut = false,
        )
    }

    companion object {
        /** 扫描超过该时长仍无设备时标记超时。 */
        const val DEFAULT_SCAN_TIMEOUT_MILLIS: Long = 12_000L
    }
}
