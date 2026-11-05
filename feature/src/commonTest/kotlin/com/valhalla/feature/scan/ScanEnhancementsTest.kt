package com.valhalla.feature.scan

import com.valhalla.data.ble.BleAdapterState
import com.valhalla.data.ble.BleClient
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.ScanFilter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanEnhancementsTest {

    @Test
    fun `RSSI 分档阈值为强中弱`() {
        assertEquals(RssiBand.STRONG, RssiBand.of(-40))
        assertEquals(RssiBand.STRONG, RssiBand.of(-60))
        assertEquals(RssiBand.MEDIUM, RssiBand.of(-61))
        assertEquals(RssiBand.MEDIUM, RssiBand.of(-75))
        assertEquals(RssiBand.WEAK, RssiBand.of(-76))
        assertEquals(RssiBand.WEAK, RssiBand.of(-95))
    }

    @Test
    fun `信号排序为 RSSI 降序且同一设备只保留最新`() = runTest {
        val client = FakeScanClient(
            listOf(
                BleDevice("a", "Alpha", -80),
                BleDevice("b", "Beta", -40),
                BleDevice("a", "Alpha", -55),
                BleDevice("c", null, -60),
            ),
        )
        val controller = ScanController(client, backgroundScope)
        controller.startScan()
        runCurrent()

        val devices = controller.state.value.devices
        assertEquals(3, devices.size)
        assertEquals(listOf("b", "a", "c"), devices.map { it.deviceId })
        assertEquals(-55, devices.first { it.deviceId == "a" }.rssi)
        controller.stopScan()
    }

    @Test
    fun `名称排序把无名设备排在最后`() = runTest {
        val client = FakeScanClient(
            listOf(
                BleDevice("a", "zeta", -80),
                BleDevice("b", null, -40),
                BleDevice("c", "Alpha", -60),
            ),
        )
        val controller = ScanController(client, backgroundScope)
        controller.startScan()
        runCurrent()
        controller.setSortMode(ScanSortMode.NAME)

        assertEquals(listOf("c", "a", "b"), controller.state.value.devices.map { it.deviceId })
        controller.stopScan()
    }

    @Test
    fun `最近连接排序使用档案时间且未连接设备排最后`() = runTest {
        val client = FakeScanClient(
            listOf(
                BleDevice("a", "A", -40),
                BleDevice("b", "B", -50),
                BleDevice("c", "C", -60),
            ),
        )
        val lastConnected = mapOf("a" to 100L, "c" to 300L)
        val controller = ScanController(
            bleClient = client,
            scope = backgroundScope,
            lastConnectedAtProvider = { lastConnected[it] },
        )
        controller.startScan()
        runCurrent()
        controller.setSortMode(ScanSortMode.LAST_CONNECTED)

        assertEquals(listOf("c", "a", "b"), controller.state.value.devices.map { it.deviceId })
        assertTrue(controller.state.value.sortMode == ScanSortMode.LAST_CONNECTED)
        controller.stopScan()
    }
}

private class FakeScanClient(private val devices: List<BleDevice>) : BleClient {

    override val adapterState: StateFlow<BleAdapterState> =
        MutableStateFlow(BleAdapterState.POWERED_ON)

    override fun refreshAdapterState() = Unit

    override fun scan(filter: ScanFilter): Flow<BleDevice> = flow {
        devices.forEach { emit(it) }
        awaitCancellation()
    }

    override suspend fun connect(deviceId: String, timeoutMillis: Long): BleConnection =
        error("not used")

    override fun close() = Unit
}
