package com.valhalla.data.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 跨平台蓝牙客户端。
 *
 * 平台实现约定：
 * - Android：BluetoothLeScanner + GATT 回调，需先调用 `AndroidBleSupport.init(context)`。
 * - iOS：CBCentralManager + CBPeripheralDelegate。
 */
interface BleClient {
    /** 适配器状态，UI 据此提示「请开启蓝牙」等。 */
    val adapterState: StateFlow<BleAdapterState>

    /** 主动重读平台适配器/授权状态（回到前台或从设置返回时调用）。 */
    fun refreshAdapterState()

    /**
     * 持续扫描，直到收集方取消。
     * 同一设备可能多次上报（RSSI 变化），由上层按 deviceId 去重。
     */
    fun scan(filter: ScanFilter = ScanFilter()): Flow<BleDevice>

    /** 建立链路并完成服务/特征发现，超时或失败抛 [BleException]。 */
    suspend fun connect(
        deviceId: String,
        timeoutMillis: Long = 15_000L,
    ): BleConnection

    /** 释放客户端持有资源（停止扫描、注销监听等）。 */
    fun close()
}

/** 一条已建立的链路，提供 GATT 读写与通知订阅。 */
interface BleConnection {
    val deviceId: String

    /** 链路是否仍然有效；底层断开时置为 false。 */
    val connected: StateFlow<Boolean>

    /** 写特征值。withResponse=false 时使用 Write Without Response。 */
    suspend fun write(
        serviceUuid: String,
        characteristicUuid: String,
        payload: ByteArray,
        withResponse: Boolean = true,
    )

    /** 读特征值。 */
    suspend fun read(
        serviceUuid: String,
        characteristicUuid: String,
    ): ByteArray

    /** 订阅特征值通知（开启 CCCD 或 iOS notify），返回冷流，收集结束即退订。 */
    fun observe(
        serviceUuid: String,
        characteristicUuid: String,
    ): Flow<ByteArray>

    /**
     * 读取当前链路的实时 RSSI（dBm）。
     *
     * 与扫描快照不同，这里反映连接建立后的真实信号变化；平台读写必须与其它 GATT
     * 操作串行。设备/平台不支持或读取失败时返回 null，由上层降级为快照，不阻断主流程。
     */
    suspend fun readRssi(): Int?

    /** 断开并释放底层资源，要求幂等。 */
    suspend fun disconnect()
}

/** 创建平台默认客户端。Android 需先初始化 [AndroidBleSupport]。 */
expect fun createBleClient(): BleClient
