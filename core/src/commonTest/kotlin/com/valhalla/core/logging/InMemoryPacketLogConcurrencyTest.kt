package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.DeviceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InMemoryPacketLogConcurrencyTest {
    @Test
    fun `高频并发写入保持容量上限与唯一 id`() =
        runTest {
            val capacity = InMemoryPacketLog.DEFAULT_CAPACITY
            val log = InMemoryPacketLog(capacity = capacity, clockMillis = { 1_700_000_000_000 })

            withContext(Dispatchers.Default) {
                coroutineScope {
                    repeat(8) { worker ->
                        launch {
                            repeat(1_000) { index ->
                                log.record(
                                    direction = if ((worker + index) % 2 == 0) FrameDirection.TX else FrameDirection.RX,
                                    frame =
                                        Frame(
                                            type = CommandType.GET_BATTERY,
                                            sequence = worker * 1_000 + index,
                                            payload =
                                                BatteryResponse(
                                                    DeviceStatus.OK,
                                                    index % 100,
                                                    charging = true,
                                                    voltageMillivolts = 4_000,
                                                ).encode(),
                                            isResponse = (worker + index) % 2 != 0,
                                        ),
                                )
                            }
                        }
                    }
                }
            }

            val entries = log.entries.value
            assertEquals(capacity, entries.size, "环形日志不得超过容量上限")
            assertEquals(entries.size, entries.map { it.id }.toSet().size, "并发写入不能产生重复 id")
            // 并发 CAS 追加允许少量交错，但保留窗口必须包含最新分配的一批 id。
            assertTrue(
                entries.maxOf { it.id } > 8_000L - capacity,
                "保留窗口应偏新，实际最大 id=${entries.maxOf { it.id }}",
            )
        }

    @Test
    fun `顺序写入时按 FIFO 淘汰最旧记录`() {
        val log = InMemoryPacketLog(capacity = 3, clockMillis = { 100 })
        repeat(5) { index ->
            log.record(
                FrameDirection.TX,
                Frame(
                    type = CommandType.GET_BATTERY,
                    sequence = index,
                    payload = BatteryResponse(DeviceStatus.OK, index, false, 4_000).encode(),
                ),
            )
        }
        assertEquals(listOf(2L, 3L, 4L), log.entries.value.map { it.id })
    }
}
