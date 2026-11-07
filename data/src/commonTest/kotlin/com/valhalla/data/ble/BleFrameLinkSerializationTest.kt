package com.valhalla.data.ble

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.DeviceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BleFrameLinkSerializationTest {
    @Test
    fun `普通指令与 OTA 分片并发写入时 Mutex 保证串行`() =
        runTest {
            val connection = SerialTrackingConnection(writeDelayMillis = 5)
            val link = BleFrameLink(connection)

            coroutineScope {
                repeat(40) { index ->
                    launch {
                        // 交替模拟普通指令与 OTA 分片，二者共享同一条 GATT 写入通道。
                        val command = if (index % 2 == 0) CommandType.GET_BATTERY else CommandType.OTA_DATA
                        link.send(
                            Frame(
                                type = command,
                                sequence = index,
                                payload = BatteryResponse(DeviceStatus.OK, index, true, 4_000).encode(),
                            ),
                        )
                    }
                }
            }

            assertEquals(40, connection.writeCount)
            assertEquals(1, connection.maxConcurrentWrites, "同一链路同一时刻只允许一个在途写")
        }

    private class SerialTrackingConnection(
        private val writeDelayMillis: Long,
    ) : BleConnection {
        private val mutableConnected = MutableStateFlow(true)
        override val deviceId: String = "dev-serial"
        override val connected: StateFlow<Boolean> = mutableConnected

        private var inFlight = 0
        var maxConcurrentWrites: Int = 0
            private set
        var writeCount: Int = 0
            private set

        override suspend fun write(
            serviceUuid: String,
            characteristicUuid: String,
            payload: ByteArray,
            withResponse: Boolean,
        ) {
            inFlight++
            if (inFlight > maxConcurrentWrites) maxConcurrentWrites = inFlight
            try {
                if (writeDelayMillis > 0) delay(writeDelayMillis)
                writeCount++
            } finally {
                inFlight--
            }
        }

        override suspend fun read(
            serviceUuid: String,
            characteristicUuid: String,
        ): ByteArray = ByteArray(0)

        override fun observe(
            serviceUuid: String,
            characteristicUuid: String,
        ): Flow<ByteArray> = kotlinx.coroutines.flow.emptyFlow()

        override suspend fun readRssi(): Int? = null

        override suspend fun disconnect() {
            mutableConnected.value = false
        }
    }
}
