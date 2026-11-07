package com.valhalla.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Android GATT 链路实现。
 *
 * 约束：
 * - GATT 写/读必须串行，[operationMutex] 保证同一时刻只有一个操作。
 * - 断链由系统回调感知，[connected] 会变为 false。
 */
internal class AndroidBleConnection private constructor(
    override val deviceId: String,
    private val gatt: BluetoothGatt,
) : BleConnection {
    private val operationMutex = Mutex()
    private val mutableConnected = MutableStateFlow(true)
    override val connected = mutableConnected.asStateFlow()

    private val notifications = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)

    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingRead: CompletableDeferred<ByteArray>? = null
    private var pendingRssi: CompletableDeferred<Int>? = null
    private var closed = false

    override suspend fun write(
        serviceUuid: String,
        characteristicUuid: String,
        payload: ByteArray,
        withResponse: Boolean,
    ) = operationMutex.withLock {
        val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
        characteristic.value = payload
        characteristic.writeType =
            if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

        if (!withResponse) {
            if (!gatt.writeCharacteristic(characteristic)) {
                throw BleException("指令写入失败：$characteristicUuid")
            }
            return@withLock
        }

        val deferred = CompletableDeferred<Unit>()
        pendingWrite = deferred
        if (!gatt.writeCharacteristic(characteristic)) {
            pendingWrite = null
            throw BleException("指令写入失败：$characteristicUuid")
        }
        try {
            withTimeout(OPERATION_TIMEOUT_MILLIS) { deferred.await() }
        } finally {
            pendingWrite = null
        }
    }

    override suspend fun read(
        serviceUuid: String,
        characteristicUuid: String,
    ): ByteArray =
        operationMutex.withLock {
            val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
            val deferred = CompletableDeferred<ByteArray>()
            pendingRead = deferred
            if (!gatt.readCharacteristic(characteristic)) {
                pendingRead = null
                throw BleException("特征值读取失败：$characteristicUuid")
            }
            try {
                withTimeout(OPERATION_TIMEOUT_MILLIS) { deferred.await() }
            } finally {
                pendingRead = null
            }
        }

    @SuppressLint("MissingPermission")
    override suspend fun readRssi(): Int? =
        operationMutex.withLock {
            if (closed || !mutableConnected.value) return@withLock null
            val deferred = CompletableDeferred<Int>()
            pendingRssi = deferred
            if (!runCatching { gatt.readRemoteRssi() }.getOrDefault(false)) {
                pendingRssi = null
                return@withLock null
            }
            try {
                withTimeoutOrNull(OPERATION_TIMEOUT_MILLIS) { deferred.await() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            } finally {
                pendingRssi = null
            }
        }

    override fun observe(
        serviceUuid: String,
        characteristicUuid: String,
    ): Flow<ByteArray> =
        callbackFlow {
            val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
            gatt.setCharacteristicNotification(characteristic, true)
            enableNotifications(characteristic)

            val job =
                launch {
                    try {
                        notifications
                            .filter { it.first.equals(characteristicUuid, ignoreCase = true) }
                            .map { it.second }
                            .collect { trySend(it) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        // 生产者异常向上游关闭 flow，由收集侧统一处理，避免未捕获异常崩溃进程。
                        close(error)
                    }
                }

            awaitClose {
                job.cancel()
                runCatching { gatt.setCharacteristicNotification(characteristic, false) }
            }
        }

    override suspend fun disconnect() {
        operationMutex.withLock {
            if (closed) return@withLock
            closed = true
            mutableConnected.value = false
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    /** 由系统断链回调触发。 */
    internal fun markDisconnected() {
        mutableConnected.value = false
        pendingRssi?.completeExceptionally(BleException("链路已断开"))
        pendingRssi = null
    }

    private fun requireCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): BluetoothGattCharacteristic {
        val service =
            runCatching { gatt.getService(UUID.fromString(serviceUuid)) }.getOrNull()
                ?: throw BleException("未找到服务 $serviceUuid")
        val characteristic =
            runCatching { service.getCharacteristic(UUID.fromString(characteristicUuid)) }.getOrNull()
                ?: throw BleException("服务 $serviceUuid 下未找到特征 $characteristicUuid")
        return characteristic
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val descriptor =
            characteristic.getDescriptor(UUID.fromString(BleUuids.CLIENT_CHARACTERISTIC_CONFIG))
                ?: return
        // 协议通道可能只支持 indicate：按特征属性选择使能值，两种模式都走 onCharacteristicChanged 回调。
        val enableValue =
            when {
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

                else -> return
            }
        descriptor.value = enableValue
        runCatching { gatt.writeDescriptor(descriptor) }
    }

    private fun handleNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        notifications.tryEmit(characteristic.uuid.toString() to value)
    }

    private fun handleWrite(status: Int) {
        val deferred = pendingWrite ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            deferred.complete(Unit)
        } else {
            deferred.completeExceptionally(BleException("特征值写入失败，status=$status"))
        }
    }

    private fun handleRssi(
        status: Int,
        rssi: Int,
    ) {
        val deferred = pendingRssi ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            deferred.complete(rssi)
        } else {
            deferred.completeExceptionally(BleException("RSSI 读取失败，status=$status"))
        }
    }

    private fun handleRead(
        status: Int,
        value: ByteArray?,
    ) {
        val deferred = pendingRead ?: return
        if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
            deferred.complete(value)
        } else {
            deferred.completeExceptionally(BleException("特征值读取失败，status=$status"))
        }
    }

    companion object {
        private const val OPERATION_TIMEOUT_MILLIS = 10_000L

        @SuppressLint("MissingPermission")
        suspend fun open(
            context: Context,
            device: BluetoothDevice,
            timeoutMillis: Long,
        ): BleConnection {
            val holder = ConnectionHolder()

            return withTimeoutOrNull(timeoutMillis) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    var settled = false

                    val callback =
                        object : BluetoothGattCallback() {
                            override fun onConnectionStateChange(
                                gatt: BluetoothGatt,
                                status: Int,
                                newState: Int,
                            ) {
                                when (newState) {
                                    BluetoothProfile.STATE_CONNECTED -> {
                                        if (status != BluetoothGatt.GATT_SUCCESS) {
                                            fail(holder, gatt, continuation, settled, "连接失败，status=$status")
                                            settled = true
                                        } else {
                                            gatt.discoverServices()
                                        }
                                    }

                                    BluetoothProfile.STATE_DISCONNECTED -> {
                                        holder.connection?.markDisconnected()
                                        if (!settled) {
                                            settled = true
                                            continuation.resumeWith(
                                                Result.failure(BleException("设备在服务发现前断开")),
                                            )
                                        }
                                        runCatching { gatt.close() }
                                    }
                                }
                            }

                            override fun onServicesDiscovered(
                                gatt: BluetoothGatt,
                                status: Int,
                            ) {
                                if (settled) return
                                if (status != BluetoothGatt.GATT_SUCCESS) {
                                    fail(holder, gatt, continuation, settled, "服务发现失败，status=$status")
                                    settled = true
                                    return
                                }
                                val connection = AndroidBleConnection(device.address, gatt)
                                holder.connection = connection
                                settled = true
                                continuation.resumeWith(Result.success(connection))
                            }

                            override fun onCharacteristicWrite(
                                gatt: BluetoothGatt,
                                characteristic: BluetoothGattCharacteristic,
                                status: Int,
                            ) {
                                holder.connection?.handleWrite(status)
                            }

                            override fun onCharacteristicRead(
                                gatt: BluetoothGatt,
                                characteristic: BluetoothGattCharacteristic,
                                status: Int,
                            ) {
                                holder.connection?.handleRead(status, characteristic.value)
                            }

                            @Suppress("DEPRECATION")
                            override fun onCharacteristicRead(
                                gatt: BluetoothGatt,
                                characteristic: BluetoothGattCharacteristic,
                                value: ByteArray,
                                status: Int,
                            ) {
                                holder.connection?.handleRead(status, value)
                            }

                            override fun onCharacteristicChanged(
                                gatt: BluetoothGatt,
                                characteristic: BluetoothGattCharacteristic,
                            ) {
                                holder.connection?.handleNotification(characteristic, characteristic.value)
                            }

                            override fun onCharacteristicChanged(
                                gatt: BluetoothGatt,
                                characteristic: BluetoothGattCharacteristic,
                                value: ByteArray,
                            ) {
                                holder.connection?.handleNotification(characteristic, value)
                            }

                            override fun onReadRemoteRssi(
                                gatt: BluetoothGatt,
                                rssi: Int,
                                status: Int,
                            ) {
                                holder.connection?.handleRssi(status, rssi)
                            }
                        }

                    val gatt =
                        device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                            ?: run {
                                settled = true
                                continuation.resumeWith(Result.failure(BleException("connectGatt 返回空")))
                                return@suspendCancellableCoroutine
                            }

                    continuation.invokeOnCancellation {
                        runCatching { gatt.disconnect() }
                        runCatching { gatt.close() }
                    }
                }
            } ?: run {
                holder.connection?.markDisconnected()
                throw BleTimeoutException("连接超时（${timeoutMillis}ms）")
            }
        }

        private fun fail(
            holder: ConnectionHolder,
            gatt: BluetoothGatt,
            continuation: kotlinx.coroutines.CancellableContinuation<BleConnection>,
            settled: Boolean,
            message: String,
        ) {
            if (!settled && continuation.isActive) {
                continuation.resumeWith(Result.failure(BleException(message)))
            }
            runCatching { gatt.close() }
            holder.connection?.markDisconnected()
        }
    }

    private class ConnectionHolder {
        var connection: AndroidBleConnection? = null
    }
}
