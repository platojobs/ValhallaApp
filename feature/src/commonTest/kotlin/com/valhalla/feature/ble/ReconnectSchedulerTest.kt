package com.valhalla.feature.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconnectSchedulerTest {

    @Test
    fun `默认退避序列为 1 2 4 8 16 秒`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), ReconnectPolicy().delaySequence())
    }

    @Test
    fun `退避按上限封顶`() {
        val policy = ReconnectPolicy(
            initialDelayMillis = 1_000,
            multiplier = 2.0,
            maxDelayMillis = 10_000,
            maxAttempts = 6,
        )
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), policy.delaySequence())
    }

    @Test
    fun `完整失败序列达到上限后耗尽`() {
        val scheduler = ReconnectScheduler(
            ReconnectPolicy(initialDelayMillis = 100, multiplier = 2.0, maxDelayMillis = 1_000, maxAttempts = 3),
        )

        val delays = mutableListOf<Long>()
        scheduler.onDisconnected()
        repeat(3) {
            assertEquals(ReconnectPhase.WAITING, scheduler.state.phase)
            delays += scheduler.state.nextDelayMillis!!
            assertTrue(scheduler.beginAttempt())
            // CONNECTING 期间再次发起被拒绝，避免重复连接。
            assertFalse(scheduler.beginAttempt())
            scheduler.onAttemptResult(succeeded = false)
        }

        assertEquals(listOf(100L, 200L, 400L), delays)
        assertEquals(ReconnectPhase.EXHAUSTED, scheduler.state.phase)
        assertEquals(3, scheduler.state.attempt)
        assertNull(scheduler.state.nextDelayMillis)
        assertFalse(scheduler.beginAttempt())
    }

    @Test
    fun `重连期间重复断开信号被忽略`() {
        val scheduler = ReconnectScheduler()
        scheduler.onDisconnected()
        val first = scheduler.state
        scheduler.onDisconnected()

        assertEquals(first, scheduler.state)
        assertEquals(1_000L, scheduler.state.nextDelayMillis)
    }

    @Test
    fun `成功一次即进入成功态并清零`() {
        val scheduler = ReconnectScheduler()
        scheduler.onDisconnected()
        assertTrue(scheduler.beginAttempt())
        scheduler.onAttemptResult(succeeded = true)

        assertEquals(ReconnectPhase.SUCCEEDED, scheduler.state.phase)
        assertEquals(0, scheduler.state.attempt)
    }

    @Test
    fun `蓝牙关闭时暂停且不消耗次数`() {
        val scheduler = ReconnectScheduler()
        scheduler.onDisconnected(bluetoothAvailable = false)

        assertEquals(ReconnectPhase.PAUSED, scheduler.state.phase)
        assertTrue(scheduler.state.pausedByBluetoothOff)
        assertEquals(0, scheduler.state.attempt)
        assertNull(scheduler.state.nextDelayMillis)
        assertFalse(scheduler.beginAttempt())
    }

    @Test
    fun `蓝牙重新开启后从当前次数继续退避`() {
        val scheduler = ReconnectScheduler()
        scheduler.onDisconnected(bluetoothAvailable = false)
        scheduler.onBluetoothStateChanged(available = true)

        assertEquals(ReconnectPhase.WAITING, scheduler.state.phase)
        assertEquals(1_000L, scheduler.state.nextDelayMillis)
        assertEquals(0, scheduler.state.attempt)
    }

    @Test
    fun `等待中关闭蓝牙会暂停并保留已完成次数`() {
        val scheduler = ReconnectScheduler()
        scheduler.onDisconnected()
        assertTrue(scheduler.beginAttempt())
        scheduler.onAttemptResult(succeeded = false) // 已完成 1 次，等待第 2 次

        assertEquals(ReconnectPhase.WAITING, scheduler.state.phase)
        assertEquals(1, scheduler.state.attempt)
        assertEquals(2_000L, scheduler.state.nextDelayMillis)

        scheduler.onBluetoothStateChanged(available = false)
        assertEquals(ReconnectPhase.PAUSED, scheduler.state.phase)
        assertEquals(1, scheduler.state.attempt)

        scheduler.onBluetoothStateChanged(available = true)
        assertEquals(ReconnectPhase.WAITING, scheduler.state.phase)
        assertEquals(1, scheduler.state.attempt)
        assertEquals(2_000L, scheduler.state.nextDelayMillis)
    }

    @Test
    fun `手动取消后不再自动尝试`() {
        val scheduler = ReconnectScheduler()
        scheduler.onDisconnected()
        scheduler.cancel()

        assertEquals(ReconnectPhase.CANCELLED, scheduler.state.phase)
        assertEquals(0, scheduler.state.attempt)
        assertNull(scheduler.state.nextDelayMillis)
        assertFalse(scheduler.beginAttempt())
    }

    @Test
    fun `取消后再次断开可重新开始`() {
        val scheduler = ReconnectScheduler()
        scheduler.onDisconnected()
        scheduler.cancel()
        scheduler.onDisconnected()

        assertEquals(ReconnectPhase.WAITING, scheduler.state.phase)
        assertEquals(1_000L, scheduler.state.nextDelayMillis)
    }
}
