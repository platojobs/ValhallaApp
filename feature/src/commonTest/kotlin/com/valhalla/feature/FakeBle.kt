package com.valhalla.feature

import com.valhalla.data.ble.BleAdapterState
import com.valhalla.data.ble.BleClient
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.BleException
import com.valhalla.data.ble.ScanFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** 测试用 BLE 客户端：扫描流可注入，连接能力故意不可用。 */
class FakeBleClient(
    private val scanFlow: Flow<BleDevice> = emptyFlow(),
    private val scanFlowProvider: ((ScanFilter) -> Flow<BleDevice>)? = null,
) : BleClient {

    override val adapterState = MutableStateFlow(BleAdapterState.POWERED_ON)

    override fun refreshAdapterState() = Unit

    override fun scan(filter: ScanFilter): Flow<BleDevice> =
        scanFlowProvider?.invoke(filter) ?: scanFlow

    override suspend fun connect(deviceId: String, timeoutMillis: Long): BleConnection =
        throw BleException("测试环境不建立真实连接")

    override fun close() = Unit

    companion object {
        fun ofDevices(vararg devices: BleDevice): FakeBleClient = FakeBleClient(flowOf(*devices))
    }
}

data class WriteRecord(
    val serviceUuid: String,
    val characteristicUuid: String,
    val payload: ByteArray,
    val withResponse: Boolean,
)

/** 测试用链路：记录写入、可按 UUID 返回读取值、可注入写入失败。 */
class FakeBleConnection(
    override val deviceId: String = "dev-1",
    private val readHandler: (String, String) -> ByteArray = { _, _ -> ByteArray(0) },
    private val writeHandler: (WriteRecord) -> Unit = {},
    private val rssiReader: suspend () -> Int? = { null },
) : BleConnection {

    override val connected = MutableStateFlow(true)

    val writes: MutableList<WriteRecord> = mutableListOf()

    override suspend fun write(
        serviceUuid: String,
        characteristicUuid: String,
        payload: ByteArray,
        withResponse: Boolean,
    ) {
        val record = WriteRecord(serviceUuid, characteristicUuid, payload.copyOf(), withResponse)
        writes += record
        writeHandler(record)
    }

    override suspend fun read(serviceUuid: String, characteristicUuid: String): ByteArray =
        readHandler(serviceUuid, characteristicUuid)

    override fun observe(serviceUuid: String, characteristicUuid: String): Flow<ByteArray> = emptyFlow()

    override suspend fun readRssi(): Int? = rssiReader()

    override suspend fun disconnect() {
        connected.value = false
    }
}
