package com.valhalla.feature.detail

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.FrameCodec
import com.valhalla.core.state.DeviceState
import com.valhalla.data.ble.BleException
import com.valhalla.data.ble.BleUuids
import com.valhalla.feature.FakeBleConnection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDetailControllerTest {

    @Test
    fun `鉴权成功进入 READY 且写入 AUTH 报文`() = runTest {
        val connection = FakeBleConnection()
        val controller = DeviceDetailController(connection)

        assertEquals(DeviceState.CONNECTED, controller.state.value.deviceState)

        val success = controller.authenticate("token-123")
        assertTrue(success)
        assertEquals(DeviceState.READY, controller.state.value.deviceState)

        val write = connection.writes.single()
        assertEquals(BleUuids.OTA_SERVICE, write.serviceUuid)
        assertEquals(BleUuids.OTA_CONTROL, write.characteristicUuid)
        assertTrue(write.withResponse)

        val frame = FrameCodec.decode(write.payload)
        assertEquals(CommandType.AUTH, frame.type)
        assertEquals("token-123", frame.payload.decodeToString())
    }

    @Test
    fun `鉴权写入失败进入 FAULT 并记录错误日志`() = runTest {
        val connection = FakeBleConnection(
            writeHandler = { throw BleException("写入超时") },
        )
        val controller = DeviceDetailController(connection)

        val success = controller.authenticate("token")
        assertFalse(success)
        assertEquals(DeviceState.FAULT, controller.state.value.deviceState)
        assertTrue(controller.state.value.logs.any { it.contains("鉴权失败") && it.contains("写入超时") })
    }

    @Test
    fun `刷新信息读取固件版本与电量`() = runTest {
        val connection = FakeBleConnection(
            readHandler = { _, characteristic ->
                when (characteristic) {
                    BleUuids.FIRMWARE_REVISION -> "1.2.3".encodeToByteArray()
                    BleUuids.BATTERY_LEVEL -> byteArrayOf(85)
                    else -> ByteArray(0)
                }
            },
        )
        val controller = DeviceDetailController(connection)

        controller.refreshInfo()

        assertEquals("1.2.3", controller.state.value.firmwareVersion)
        assertEquals(85, controller.state.value.batteryPercent)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `读取异常时保留旧值并给出错误`() = runTest {
        val connection = FakeBleConnection(
            readHandler = { _, _ -> throw BleException("GATT 读失败") },
        )
        val controller = DeviceDetailController(connection)

        controller.refreshInfo()

        assertEquals("设备信息读取失败", controller.state.value.error)
        assertEquals(null, controller.state.value.firmwareVersion)
    }

    @Test
    fun `主动断开回到未连接`() = runTest {
        val connection = FakeBleConnection()
        val controller = DeviceDetailController(connection)

        controller.disconnect()

        assertEquals(DeviceState.DISCONNECTED, controller.state.value.deviceState)
        assertFalse(connection.connected.value)
    }
}
