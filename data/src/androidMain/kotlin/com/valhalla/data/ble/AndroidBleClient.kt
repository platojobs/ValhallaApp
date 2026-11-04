package com.valhalla.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Android BLE 客户端。
 *
 * 注意：Android 12+ 需要 BLUETOOTH_SCAN / BLUETOOTH_CONNECT 运行时权限，
 * Android 11 及以下需要 ACCESS_FINE_LOCATION；权限申请属于 App 壳职责。
 */
class AndroidBleClient internal constructor(
    private val context: Context,
) : BleClient {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val mutableAdapterState = MutableStateFlow(currentAdapterState())
    override val adapterState = mutableAdapterState.asStateFlow()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                mutableAdapterState.value = currentAdapterState()
            }
        }
    }

    init {
        runCatching {
            context.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
    }

    @SuppressLint("MissingPermission")
    override fun scan(filter: ScanFilter): Flow<BleDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(BleException("当前设备不支持 BLE 或蓝牙未开启"))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.toBleDevice()
                if (!filter.matchesName(device.name) || !filter.matchesService(device.serviceUuids)) return
                trySend(device)
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("BLE 扫描失败，errorCode=$errorCode"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // 服务过滤交给系统扫描器，名称前缀在结果侧过滤。
        val platformFilters = filter.serviceUuid?.let { uuid ->
            listOf(
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(UUID.fromString(uuid)))
                    .build(),
            )
        } ?: emptyList()

        try {
            scanner.startScan(platformFilters, settings, callback)
        } catch (error: SecurityException) {
            close(BleException("缺少蓝牙扫描权限", error))
            return@callbackFlow
        }

        awaitClose { scanner.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(deviceId: String, timeoutMillis: Long): BleConnection {
        val target = adapter?.getRemoteDevice(deviceId) ?: throw BleException("设备地址无效：$deviceId")
        return AndroidBleConnection.open(context, target, timeoutMillis)
    }

    override fun refreshAdapterState() {
        mutableAdapterState.value = currentAdapterState()
    }

    override fun close() {
        runCatching { context.unregisterReceiver(stateReceiver) }
    }

    private fun currentAdapterState(): BleAdapterState {
        val bluetoothAdapter = adapter ?: return BleAdapterState.UNSUPPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return BleAdapterState.UNAUTHORIZED
        }
        return when (bluetoothAdapter.state) {
            BluetoothAdapter.STATE_ON -> BleAdapterState.POWERED_ON
            BluetoothAdapter.STATE_OFF -> BleAdapterState.POWERED_OFF
            else -> BleAdapterState.UNKNOWN
        }
    }
}

@SuppressLint("MissingPermission")
private fun ScanResult.toBleDevice(): BleDevice = BleDevice(
    deviceId = device.address,
    name = scanRecord?.deviceName ?: device.name,
    rssi = rssi,
    isConnectable = isConnectable,
    serviceUuids = scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList(),
)
