package com.valhalla.feature.device

import com.valhalla.data.ble.BleAdapterState
import com.valhalla.data.ble.BleClient
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.ScanFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultiDeviceManagerTest {

    @Test
    fun `连接多台设备按顺序分配槽位并默认选中第一台`() = runTest {
        val manager = MultiDeviceManager(FakeBleClient(), capacity = 4)

        repeat(4) { index ->
            val result = manager.connect(BleDevice("dev-$index", "设备-$index", -50))
            assertTrue(result is MultiDeviceConnectResult.Connected)
        }

        val state = manager.state.value
        assertEquals(4, state.occupied)
        assertEquals(4, state.capacity)
        assertEquals(listOf(1, 2, 3, 4), state.devices.map { it.slot })
        assertEquals("dev-0", state.activeDeviceId)
        assertEquals("设备-1", state.devices[1].displayName)
    }

    @Test
    fun `达到容量上限时直接拒绝而不是排队`() = runTest {
        val manager = MultiDeviceManager(FakeBleClient(), capacity = 2)
        manager.connect(BleDevice("a", "A", -40))
        manager.connect(BleDevice("b", "B", -50))

        val third = manager.connect(BleDevice("c", "C", -60))

        assertTrue(third is MultiDeviceConnectResult.LimitReached)
        assertEquals(2, (third as MultiDeviceConnectResult.LimitReached).capacity)
        assertEquals(2, manager.state.value.occupied)
    }

    @Test
    fun `切换当前设备只影响选中的设备`() = runTest {
        val manager = MultiDeviceManager(FakeBleClient(), capacity = 4)
        manager.connect(BleDevice("a", "A", -40))
        manager.connect(BleDevice("b", "B", -50))

        assertTrue(manager.select("b"))
        assertEquals("b", manager.state.value.activeDeviceId)
        assertEquals("B", manager.state.value.activeDevice?.displayName)
        assertFalse(manager.select("missing"))
        assertEquals("b", manager.state.value.activeDeviceId)
    }

    @Test
    fun `断开后重排槽位并在清空时回落空状态`() = runTest {
        val manager = MultiDeviceManager(FakeBleClient(), capacity = 3)
        manager.connect(BleDevice("a", "A", -40))
        manager.connect(BleDevice("b", "B", -50))
        manager.connect(BleDevice("c", "C", -60))
        manager.select("b")

        manager.disconnect("a")

        val state = manager.state.value
        assertEquals(listOf(1, 2), state.devices.map { it.slot })
        assertEquals(listOf("b", "c"), state.devices.map { it.deviceId })
        assertEquals("b", state.activeDeviceId)

        manager.disconnectAll()
        assertEquals(0, manager.state.value.occupied)
        assertEquals(null, manager.state.value.activeDeviceId)
    }

    @Test
    fun `自动重连替换链路引用并刷新连接时间`() = runTest {
        val client = FakeBleClient()
        val manager = MultiDeviceManager(client, capacity = 2, clockMillis = { 100L })
        manager.connect(BleDevice("a", "A", -40))

        val before = manager.state.value.devices.single()
        val replacement = FakeConnection("a")

        assertTrue(manager.replaceConnection("a", replacement))
        val after = manager.state.value.devices.single()
        assertEquals(replacement, after.connection)
        assertEquals(100L, before.connectedAtMillis)
        assertFalse(manager.replaceConnection("missing", replacement))
    }
}

private class FakeBleClient : BleClient {

    override val adapterState: StateFlow<BleAdapterState> =
        MutableStateFlow(BleAdapterState.POWERED_ON)

    override fun refreshAdapterState() = Unit

    override fun scan(filter: ScanFilter): Flow<BleDevice> = emptyFlow()

    override suspend fun connect(deviceId: String, timeoutMillis: Long): BleConnection =
        FakeConnection(deviceId)

    override fun close() = Unit
}

private class FakeConnection(override val deviceId: String) : BleConnection {

    override val connected: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun write(
        serviceUuid: String,
        characteristicUuid: String,
        payload: ByteArray,
        withResponse: Boolean,
    ) = Unit

    override suspend fun read(serviceUuid: String, characteristicUuid: String): ByteArray = ByteArray(0)

    override fun observe(serviceUuid: String, characteristicUuid: String): Flow<ByteArray> = emptyFlow()

    override suspend fun readRssi(): Int? = null

    override suspend fun disconnect() = Unit
}
