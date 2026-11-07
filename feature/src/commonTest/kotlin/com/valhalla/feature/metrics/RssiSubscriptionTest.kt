package com.valhalla.feature.metrics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RssiSubscriptionTest {
    @Test
    fun `读取成功时状态来自连接实时`() =
        runTest {
            var now = 1_000L
            val samples = mutableListOf<RssiReading>()
            val controller =
                RssiSubscriptionController(
                    readRssi = { -47 },
                    onSample = samples::add,
                    clockMillis = { now },
                )

            val value = controller.sampleOnce()

            assertEquals(-47, value)
            assertEquals(RssiSource.CONNECTED_LIVE, controller.state.value.source)
            assertEquals(
                -47,
                controller.state.value.latest
                    ?.rssi,
            )
            assertEquals(
                1_000L,
                controller.state.value.latest
                    ?.timestampMillis,
            )
            assertEquals(1, samples.size)
            assertNull(controller.state.value.degradedReason)
            now = 2_000L
        }

    @Test
    fun `连续读取失败达阈值后降级为快照并给出原因`() =
        runTest {
            val controller = RssiSubscriptionController(readRssi = { null })

            repeat(RssiSubscriptionController.DEFAULT_FAILURE_THRESHOLD) {
                assertNull(controller.sampleOnce())
            }

            val state = controller.state.value
            assertFalse(state.active)
            assertEquals(RssiSource.SNAPSHOT, state.source)
            assertEquals(RssiDegradedReason.READ_FAILED, state.degradedReason)
            assertNull(state.latest)
        }

    @Test
    fun `读取中断或设备不支持时按失败计数`() =
        runTest {
            var calls = 0
            val controller =
                RssiSubscriptionController(
                    readRssi = {
                        calls++
                        if (calls == 1) error("不支持") else null
                    },
                )

            assertNull(controller.sampleOnce())
            assertNull(controller.sampleOnce())
            assertNull(controller.sampleOnce())

            assertEquals(RssiDegradedReason.READ_FAILED, controller.state.value.degradedReason)
        }

    @Test
    fun `断开后自动停止并标记断开`() =
        runTest {
            val samples = mutableListOf<RssiReading>()
            val controller =
                RssiSubscriptionController(
                    readRssi = { -50 },
                    onSample = samples::add,
                )
            var running = true

            val job = launch { controller.run(300L) { running } }
            runCurrent()
            running = false
            advanceTimeBy(300L)
            job.join()

            assertEquals(1, samples.size)
            val state = controller.state.value
            assertFalse(state.active)
            assertEquals(RssiSource.SNAPSHOT, state.source)
            assertEquals(RssiDegradedReason.DISCONNECTED, state.degradedReason)
        }

    @Test
    fun `关闭开关时立即停止订阅`() =
        runTest {
            val controller = RssiSubscriptionController(readRssi = { -60 })
            controller.sampleOnce()
            assertTrue(controller.state.value.active)

            controller.stop()

            assertFalse(controller.state.value.active)
            assertEquals(RssiSource.SNAPSHOT, controller.state.value.source)
        }

    @Test
    fun `取消订阅时不会把取消异常当成降级`() =
        runTest {
            val controller = RssiSubscriptionController(readRssi = { -55 })
            val job =
                launch {
                    controller.run(100L) { true }
                }
            runCurrent()
            job.cancel()
            job.join()

            // 取消后状态收敛为快照，且没有记录 READ_FAILED。
            assertEquals(RssiSource.SNAPSHOT, controller.state.value.source)
            assertNotEquals(RssiDegradedReason.READ_FAILED, controller.state.value.degradedReason)
            assertNotNull(controller.state.value.latest)
        }
}
