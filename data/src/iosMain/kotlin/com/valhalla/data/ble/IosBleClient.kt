package com.valhalla.data.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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
import platform.CoreBluetooth.CBAdvertisementDataIsConnectable
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteType
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject

/**
 * iOS CoreBluetooth 客户端。
 *
 * 实现说明：
 * - CoreBluetooth 委托必须是 Objective-C 对象，因此委托逻辑拆到 [CentralDelegate]；
 *   本类只负责状态与协程流，避免 Kotlin 与 Objective-C 超类型混用限制。
 * - 委托回调在主队列执行，状态更新通过 StateFlow / SharedFlow 传递。
 * - 同一个客户端同时只建议存在一个扫描收集方（停止扫描会影响其它收集方）。
 * - 需要在 Info.plist 声明 `NSBluetoothAlwaysUsageDescription`。
 */
class IosBleClient : BleClient {

    private val mutableAdapterState = MutableStateFlow(BleAdapterState.UNKNOWN)
    override val adapterState = mutableAdapterState.asStateFlow()

    private val scanSink = MutableSharedFlow<BleDevice>(extraBufferCapacity = 64)
    private val discovered = mutableMapOf<String, CBPeripheral>()
    private val connections = mutableMapOf<String, IosBleConnection>()

    private val centralDelegate: CentralDelegate
    private val central: CBCentralManager

    init {
        centralDelegate = CentralDelegate(
            onStateChanged = { state -> mutableAdapterState.value = state.toBleAdapterState() },
            onDiscovered = { peripheral, advertisementData, rssi ->
                handleDiscovered(peripheral, advertisementData, rssi)
            },
            onFailedToConnect = { peripheral, error ->
                connections.remove(peripheral.identifier.UUIDString)
                    ?.markDisconnected(error?.localizedDescription)
            },
            onDisconnected = { peripheral, error ->
                connections.remove(peripheral.identifier.UUIDString)
                    ?.markDisconnected(error?.localizedDescription)
            },
        )
        central = CBCentralManager(delegate = centralDelegate, queue = null, options = null)
    }

    override fun scan(filter: ScanFilter): Flow<BleDevice> = callbackFlow {
        if (central.state != CBManagerStatePoweredOn) {
            close(BleException("蓝牙未开启或未授权"))
            return@callbackFlow
        }

        val job = launch {
            try {
                scanSink.collect { device ->
                    if (filter.matchesName(device.name) && filter.matchesService(device.serviceUuids)) {
                        trySend(device)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                close(error)
            }
        }

        val services = filter.serviceUuid?.let { uuid -> listOf(CBUUID.UUIDWithString(uuid)) }
        central.scanForPeripheralsWithServices(services, options = null)

        awaitClose {
            job.cancel()
            central.stopScan()
        }
    }

    override suspend fun connect(deviceId: String, timeoutMillis: Long): BleConnection {
        if (central.state != CBManagerStatePoweredOn) throw BleException("蓝牙未开启或未授权")

        val peripheral = findPeripheral(deviceId)
            ?: throw BleException("未找到设备 $deviceId，请先扫描")

        val connection = IosBleConnection(deviceId, central, peripheral)
        connections[deviceId] = connection

        val ready = withTimeoutOrNull(timeoutMillis) {
            connection.start()
            true
        }

        if (ready == null) {
            connections.remove(deviceId)
            connection.markDisconnected()
            central.cancelPeripheralConnection(peripheral)
            throw BleTimeoutException("连接超时（${timeoutMillis}ms）")
        }
        return connection
    }

    override fun refreshAdapterState() {
        mutableAdapterState.value = central.state.toBleAdapterState()
    }

    override fun close() {
        central.stopScan()
        connections.values.forEach { it.markDisconnected() }
        connections.clear()
        discovered.clear()
    }

    private fun handleDiscovered(
        peripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        rssi: NSNumber,
    ) {
        val deviceId = peripheral.identifier.UUIDString
        discovered[deviceId] = peripheral

        val serviceUuids = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>)
            ?.filterIsInstance<CBUUID>()
            ?.map { it.UUIDString }
            ?: emptyList()

        val connectable = (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue ?: true

        scanSink.tryEmit(
            BleDevice(
                deviceId = deviceId,
                name = peripheral.name,
                rssi = rssi.intValue,
                isConnectable = connectable,
                serviceUuids = serviceUuids,
            ),
        )
    }

    private fun findPeripheral(deviceId: String): CBPeripheral? {
        discovered[deviceId]?.let { return it }
        val identifier = runCatching { NSUUID(uUIDString = deviceId) }.getOrNull() ?: return null
        val peripherals = central.retrievePeripheralsWithIdentifiers(listOf(identifier))
        return peripherals.filterIsInstance<CBPeripheral>().firstOrNull()
    }
}

/** CBCentralManager 委托包装，保持纯 Objective-C 继承关系。 */
private class CentralDelegate(
    private val onStateChanged: (CBManagerState) -> Unit,
    private val onDiscovered: (CBPeripheral, Map<Any?, *>, NSNumber) -> Unit,
    private val onFailedToConnect: (CBPeripheral, NSError?) -> Unit,
    private val onDisconnected: (CBPeripheral, NSError?) -> Unit,
) : NSObject(), CBCentralManagerDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        onStateChanged(central.state)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        onDiscovered(didDiscoverPeripheral, advertisementData, RSSI)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didConnectPeripheral: CBPeripheral,
    ) {
        // 服务发现由 IosBleConnection 自行完成。
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        onFailedToConnect(didFailToConnectPeripheral, error)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        onDisconnected(didDisconnectPeripheral, error)
    }
}

/** 一条 iOS GATT 链路，负责服务发现、读写、通知订阅。 */
internal class IosBleConnection(
    override val deviceId: String,
    private val central: CBCentralManager,
    private val peripheral: CBPeripheral,
) : BleConnection {

    private val operationMutex = Mutex()
    private val mutableConnected = MutableStateFlow(false)
    override val connected = mutableConnected.asStateFlow()

    private val notifications = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)

    private val delegate: PeripheralDelegate

    private var startDeferred: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingRead: CompletableDeferred<ByteArray>? = null
    private var pendingReadUuid: String? = null
    private var pendingRssi: CompletableDeferred<Int>? = null
    private var pendingServiceCount = 0

    init {
        delegate = PeripheralDelegate(
            onDiscoverServices = { peripheral, error -> handleDiscoverServices(peripheral, error) },
            onDiscoverCharacteristics = { service, error -> handleDiscoverCharacteristics(service, error) },
            onWriteValue = { characteristic, error -> handleWriteValue(characteristic, error) },
            onUpdateValue = { characteristic, error -> handleUpdateValue(characteristic, error) },
            onReadRssi = { rssi, error -> handleReadRssi(rssi, error) },
        )
    }

    /** 发起连接并等待服务/特征发现完成。由 [IosBleClient.connect] 包裹超时。 */
    suspend fun start() {
        val deferred = CompletableDeferred<Unit>()
        startDeferred = deferred
        peripheral.delegate = delegate
        mutableConnected.value = true
        central.connectPeripheral(peripheral, options = null)
        deferred.await()
    }

    override suspend fun write(
        serviceUuid: String,
        characteristicUuid: String,
        payload: ByteArray,
        withResponse: Boolean,
    ) = operationMutex.withLock {
        val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
        val type: CBCharacteristicWriteType =
            if (withResponse) CBCharacteristicWriteWithResponse else CBCharacteristicWriteWithoutResponse

        if (!withResponse) {
            peripheral.writeValue(payload.toNSData(), forCharacteristic = characteristic, type = type)
            return@withLock
        }

        val deferred = CompletableDeferred<Unit>()
        pendingWrite = deferred
        peripheral.writeValue(payload.toNSData(), forCharacteristic = characteristic, type = type)
        try {
            withTimeout(OPERATION_TIMEOUT_MILLIS) { deferred.await() }
        } finally {
            pendingWrite = null
        }
    }

    override suspend fun read(serviceUuid: String, characteristicUuid: String): ByteArray =
        operationMutex.withLock {
            val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
            val deferred = CompletableDeferred<ByteArray>()
            pendingRead = deferred
            pendingReadUuid = characteristic.UUID.UUIDString
            peripheral.readValueForCharacteristic(characteristic)
            try {
                withTimeout(OPERATION_TIMEOUT_MILLIS) { deferred.await() }
            } finally {
                pendingRead = null
                pendingReadUuid = null
            }
        }

    override suspend fun readRssi(): Int? = operationMutex.withLock {
        if (!mutableConnected.value) return@withLock null
        val deferred = CompletableDeferred<Int>()
        pendingRssi = deferred
        peripheral.readRSSI()
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

    override fun observe(serviceUuid: String, characteristicUuid: String): Flow<ByteArray> =
        callbackFlow {
            val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
            peripheral.setNotifyValue(true, forCharacteristic = characteristic)

            val job = launch {
                try {
                    notifications
                        .filter { it.first.equals(characteristicUuid, ignoreCase = true) }
                        .map { it.second }
                        .collect { trySend(it) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    close(error)
                }
            }

            awaitClose {
                job.cancel()
                runCatching { peripheral.setNotifyValue(false, forCharacteristic = characteristic) }
            }
        }

    override suspend fun disconnect() {
        operationMutex.withLock {
            if (!mutableConnected.value) return@withLock
            mutableConnected.value = false
            central.cancelPeripheralConnection(peripheral)
        }
    }

    /** 底层断链或连接失败时调用，释放所有等待中的操作。 */
    internal fun markDisconnected(reason: String? = null) {
        val message = reason ?: "链路已断开"
        mutableConnected.value = false
        startDeferred?.completeExceptionally(BleException(message))
        pendingWrite?.completeExceptionally(BleException(message))
        pendingRead?.completeExceptionally(BleException(message))
        pendingRssi?.completeExceptionally(BleException(message))
        startDeferred = null
        pendingWrite = null
        pendingRead = null
        pendingRssi = null
    }

    private fun handleDiscoverServices(peripheral: CBPeripheral, error: NSError?) {
        if (error != null) {
            failStart(error.localizedDescription)
            return
        }

        val services = peripheral.services?.filterIsInstance<CBService>() ?: emptyList()
        pendingServiceCount = services.size
        if (pendingServiceCount == 0) {
            completeStart()
            return
        }
        services.forEach { service ->
            peripheral.discoverCharacteristics(null, forService = service)
        }
    }

    private fun handleDiscoverCharacteristics(service: CBService, error: NSError?) {
        if (error != null) {
            failStart(error.localizedDescription)
            return
        }
        pendingServiceCount--
        if (pendingServiceCount <= 0) {
            completeStart()
        }
    }

    private fun handleWriteValue(characteristic: CBCharacteristic, error: NSError?) {
        val deferred = pendingWrite ?: return
        if (error == null) {
            deferred.complete(Unit)
        } else {
            deferred.completeExceptionally(BleException(error.localizedDescription))
        }
    }

    private fun handleUpdateValue(characteristic: CBCharacteristic, error: NSError?) {
        if (error != null) {
            pendingRead?.completeExceptionally(BleException(error.localizedDescription))
            return
        }

        val value = characteristic.value?.toByteArray() ?: ByteArray(0)
        val characteristicUuid = characteristic.UUID.UUIDString

        val readDeferred = pendingRead
        if (readDeferred != null && pendingReadUuid.equals(characteristicUuid, ignoreCase = true)) {
            readDeferred.complete(value)
        } else {
            notifications.tryEmit(characteristicUuid to value)
        }
    }

    private fun handleReadRssi(rssi: NSNumber, error: NSError?) {
        val deferred = pendingRssi ?: return
        if (error == null) {
            deferred.complete(rssi.intValue)
        } else {
            deferred.completeExceptionally(BleException(error.localizedDescription))
        }
    }

    private fun completeStart() {
        startDeferred?.complete(Unit)
    }

    private fun failStart(reason: String) {
        startDeferred?.completeExceptionally(BleException(reason))
    }

    private fun requireCharacteristic(serviceUuid: String, characteristicUuid: String): CBCharacteristic {
        val services = peripheral.services?.filterIsInstance<CBService>() ?: emptyList()
        val service = services.firstOrNull { it.UUID.UUIDString.equals(serviceUuid, ignoreCase = true) }
            ?: throw BleException("未找到服务 $serviceUuid")

        val characteristics = service.characteristics?.filterIsInstance<CBCharacteristic>()
            ?: emptyList()
        return characteristics.firstOrNull { it.UUID.UUIDString.equals(characteristicUuid, ignoreCase = true) }
            ?: throw BleException("服务 $serviceUuid 下未找到特征 $characteristicUuid")
    }

    companion object {
        private const val OPERATION_TIMEOUT_MILLIS = 10_000L
    }
}

/** CBPeripheralDelegate 委托包装，保持纯 Objective-C 继承关系。 */
private class PeripheralDelegate(
    private val onDiscoverServices: (CBPeripheral, NSError?) -> Unit,
    private val onDiscoverCharacteristics: (CBService, NSError?) -> Unit,
    private val onWriteValue: (CBCharacteristic, NSError?) -> Unit,
    private val onUpdateValue: (CBCharacteristic, NSError?) -> Unit,
    private val onReadRssi: (NSNumber, NSError?) -> Unit,
) : NSObject(), CBPeripheralDelegateProtocol {

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        onDiscoverServices(peripheral, didDiscoverServices)
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) {
        onDiscoverCharacteristics(didDiscoverCharacteristicsForService, error)
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        onWriteValue(didWriteValueForCharacteristic, error)
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        onUpdateValue(didUpdateValueForCharacteristic, error)
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didReadRSSI: NSNumber,
        error: NSError?,
    ) {
        onReadRssi(didReadRSSI, error)
    }
}

private fun CBManagerState.toBleAdapterState(): BleAdapterState = when (this) {
    CBManagerStatePoweredOn -> BleAdapterState.POWERED_ON
    CBManagerStatePoweredOff -> BleAdapterState.POWERED_OFF
    CBManagerStateUnauthorized -> BleAdapterState.UNAUTHORIZED
    CBManagerStateUnsupported -> BleAdapterState.UNSUPPORTED
    CBManagerStateResetting -> BleAdapterState.RESETTING
    else -> BleAdapterState.UNKNOWN
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}

actual fun createBleClient(): BleClient = IosBleClient()
