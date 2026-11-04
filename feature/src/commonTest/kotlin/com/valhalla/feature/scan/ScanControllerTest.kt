package com.valhalla.feature.scan

import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.BleException
import com.valhalla.feature.FakeBleClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerTest {

    @Test
    fun `扫描结果按信号强度去重排序`() = runTest {
        val client = FakeBleClient(
            scanFlow = flow {
                emit(BleDevice("a", "Valhalla-A", -40))
                emit(BleDevice("b", "Valhalla-B", -70))
                emit(BleDevice("a", "Valhalla-A", -30))
            },
        )
        val controller = ScanController(client, backgroundScope)

        controller.startScan()
        runCurrent()

        val devices = controller.state.value.devices
        assertEquals(2, devices.size)
        assertEquals("a", devices.first().deviceId)
        assertEquals(-30, devices.first().rssi)
        assertFalse(controller.state.value.scanning)
    }

    @Test
    fun `扫描异常写入错误信息`() = runTest {
        val client = FakeBleClient(
            scanFlow = flow { throw BleException("蓝牙不可用") },
        )
        val controller = ScanController(client, backgroundScope)

        controller.startScan()
        runCurrent()

        val error = controller.state.value.error
        assertNotNull(error)
        assertTrue(error.contains("蓝牙不可用"))
        assertFalse(controller.state.value.scanning)
    }

    @Test
    fun `停止扫描保留已发现设备`() = runTest {
        val feed = MutableSharedFlow<BleDevice>()
        val client = FakeBleClient(scanFlow = feed)
        val controller = ScanController(client, backgroundScope)

        controller.startScan()
        runCurrent()
        feed.emit(BleDevice("dev-1", "Valhalla-01", -50))
        runCurrent()

        controller.stopScan()
        assertFalse(controller.state.value.scanning)
        assertEquals(1, controller.state.value.devices.size)
    }

    @Test
    fun `清空设备列表`() = runTest {
        val client = FakeBleClient.ofDevices(BleDevice("dev-1", "Valhalla-01", -50))
        val controller = ScanController(client, backgroundScope)

        controller.startScan()
        runCurrent()
        assertEquals(1, controller.state.value.devices.size)

        controller.clearDevices()
        assertTrue(controller.state.value.devices.isEmpty())
    }
}
