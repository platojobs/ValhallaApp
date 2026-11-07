package com.valhalla.feature.ble

import com.valhalla.data.ble.BleConnection
import com.valhalla.feature.FakeBleConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutoReconnectControllerTest {
    @Test
    fun `按退避序列尝试并在成功后回调`() =
        runTest {
            val delays = mutableListOf<Long>()
            var attempts = 0
            var connected: BleConnection? = null
            val controller =
                AutoReconnectController(
                    policy =
                        ReconnectPolicy(
                            initialDelayMillis = 1_000,
                            multiplier = 2.0,
                            maxDelayMillis = 8_000,
                            maxAttempts = 3,
                        ),
                    scope = backgroundScope,
                    isBluetoothOn = { true },
                    attemptConnect = {
                        attempts++
                        if (attempts < 2) null else FakeBleConnection()
                    },
                    onConnected = { connected = it },
                    delayMillis = { millis ->
                        delays += millis
                        delay(millis)
                    },
                )

            controller.start()
            runCurrent()
            assertEquals(ReconnectPhase.WAITING, controller.state.value.phase)
            assertEquals(1_000L, controller.state.value.nextDelayMillis)

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(listOf(1_000L, 2_000L), delays)
            assertEquals(2, attempts)
            assertEquals(ReconnectPhase.SUCCEEDED, controller.state.value.phase)
            assertNotNull(connected)
        }

    @Test
    fun `手动取消立即停止后续尝试`() =
        runTest {
            var attempts = 0
            val controller =
                AutoReconnectController(
                    policy =
                        ReconnectPolicy(
                            initialDelayMillis = 1_000,
                            multiplier = 2.0,
                            maxDelayMillis = 8_000,
                            maxAttempts = 4,
                        ),
                    scope = backgroundScope,
                    isBluetoothOn = { true },
                    attemptConnect = {
                        attempts++
                        null
                    },
                    onConnected = {},
                    delayMillis = { delay(it) },
                )

            controller.start()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            // 第一次失败后进入第二次等待。
            assertEquals(ReconnectPhase.WAITING, controller.state.value.phase)
            assertEquals(1, controller.state.value.attempt)
            assertEquals(2_000L, controller.state.value.nextDelayMillis)

            controller.cancel()
            assertEquals(ReconnectPhase.CANCELLED, controller.state.value.phase)

            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, attempts)

            // 取消后可重新手动发起。
            controller.start()
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(attempts >= 2)
        }

    @Test
    fun `蓝牙关闭时暂停且不发起连接`() =
        runTest {
            var bluetoothOn = false
            var attempts = 0
            val controller =
                AutoReconnectController(
                    policy =
                        ReconnectPolicy(
                            initialDelayMillis = 1_000,
                            multiplier = 2.0,
                            maxDelayMillis = 8_000,
                            maxAttempts = 3,
                        ),
                    scope = backgroundScope,
                    isBluetoothOn = { bluetoothOn },
                    attemptConnect = {
                        attempts++
                        FakeBleConnection()
                    },
                    onConnected = {},
                    delayMillis = { delay(it) },
                )

            controller.start()
            runCurrent()
            assertEquals(ReconnectPhase.PAUSED, controller.state.value.phase)
            assertTrue(controller.state.value.pausedByBluetoothOff)
            assertEquals(0, attempts)

            bluetoothOn = true
            controller.onBluetoothStateChanged(available = true)
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(1, attempts)
            assertEquals(ReconnectPhase.SUCCEEDED, controller.state.value.phase)
        }

    @Test
    fun `等待中关闭蓝牙会暂停并在开启后恢复`() =
        runTest {
            var bluetoothOn = true
            val delays = mutableListOf<Long>()
            var attempts = 0
            val controller =
                AutoReconnectController(
                    policy =
                        ReconnectPolicy(
                            initialDelayMillis = 1_000,
                            multiplier = 2.0,
                            maxDelayMillis = 8_000,
                            maxAttempts = 3,
                        ),
                    scope = backgroundScope,
                    isBluetoothOn = { bluetoothOn },
                    attemptConnect = {
                        attempts++
                        null
                    },
                    onConnected = {},
                    delayMillis = { millis ->
                        delays += millis
                        delay(millis)
                    },
                )

            controller.start()
            runCurrent()
            assertEquals(ReconnectPhase.WAITING, controller.state.value.phase)

            bluetoothOn = false
            controller.onBluetoothStateChanged(available = false)
            assertEquals(ReconnectPhase.PAUSED, controller.state.value.phase)
            assertEquals(0, attempts)

            val delaysBeforeResume = delays.size
            bluetoothOn = true
            controller.onBluetoothStateChanged(available = true)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(
                listOf(1_000L, 2_000L, 4_000L),
                delays.subList(delaysBeforeResume, delays.size),
            )
            assertEquals(3, attempts)
            assertEquals(ReconnectPhase.EXHAUSTED, controller.state.value.phase)
        }

    @Test
    fun `重连期间重复 start 不会并发发起`() =
        runTest {
            var attempts = 0
            val controller =
                AutoReconnectController(
                    policy =
                        ReconnectPolicy(
                            initialDelayMillis = 1_000,
                            multiplier = 2.0,
                            maxDelayMillis = 2_000,
                            maxAttempts = 1,
                        ),
                    scope = backgroundScope,
                    isBluetoothOn = { true },
                    attemptConnect = {
                        attempts++
                        null
                    },
                    onConnected = {},
                    delayMillis = { delay(it) },
                )

            controller.start()
            controller.start()
            controller.start()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1, attempts)
            assertEquals(ReconnectPhase.EXHAUSTED, controller.state.value.phase)
        }
}
