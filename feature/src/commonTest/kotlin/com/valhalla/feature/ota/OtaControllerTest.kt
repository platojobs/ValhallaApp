package com.valhalla.feature.ota

import com.valhalla.core.protocol.Crc16
import com.valhalla.data.ble.BleException
import com.valhalla.data.ble.BleUuids
import com.valhalla.feature.FakeBleConnection
import com.valhalla.feature.WriteRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtaControllerTest {

    @Test
    fun `固件分片写入并返回成功状态`() = runTest {
        val connection = FakeBleConnection()
        val controller = OtaController(connection, chunkSize = 64)
        val firmware = ByteArray(300) { it.toByte() }

        val result = controller.start(firmware)

        assertTrue(result is OtaState.Succeeded)
        result as OtaState.Succeeded
        assertEquals(300, result.totalBytes)
        assertEquals(5, result.chunks)
        assertEquals(Crc16.ccittFalse(firmware), result.crc16)

        assertEquals(5, connection.writes.size)
        assertEquals(64, connection.writes.first().payload.size)
        assertEquals(44, connection.writes.last().payload.size)
        assertTrue(connection.writes.all { !it.withResponse })
        assertTrue(connection.writes.all { it.characteristicUuid == BleUuids.OTA_DATA })
    }

    @Test
    fun `传输中断返回失败并记录已发送字节`() = runTest {
        var calls = 0
        val connection = FakeBleConnection(
            writeHandler = { _: WriteRecord ->
                calls++
                if (calls == 3) throw BleException("链路断开")
            },
        )
        val controller = OtaController(connection, chunkSize = 64)

        val result = controller.start(ByteArray(300) { it.toByte() })

        assertTrue(result is OtaState.Failed)
        result as OtaState.Failed
        assertEquals("链路断开", result.reason)
        assertEquals(128, result.sentBytes)
        assertEquals(3, connection.writes.size)
    }

    @Test
    fun `空固件被拒绝`() = runTest {
        val controller = OtaController(FakeBleConnection())

        val result = controller.start(ByteArray(0))

        assertTrue(result is OtaState.Failed)
        assertEquals("固件包为空", (result as OtaState.Failed).reason)
    }

    @Test
    fun `reset 回到空闲状态`() = runTest {
        val controller = OtaController(FakeBleConnection())
        controller.start(ByteArray(32))
        assertEquals(OtaState.Succeeded(32, Crc16.ccittFalse(ByteArray(32)), 1), controller.state.value)

        controller.reset()
        assertEquals(OtaState.Idle, controller.state.value)
    }
}
