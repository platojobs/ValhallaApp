package com.valhalla.data.pool

import com.valhalla.core.state.DeviceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MultiDeviceConnectionPoolStressTest {

    private val devices = (1..16).map { "dev-$it" }

    @Test
    fun `多设备并发连接与断开后无槽位和会话泄漏`() = runTest {
        var maxObservedConnects = 0
        var activeConnects = 0
        val sessions = mutableListOf<RecordingSession>()
        val pool = MultiDeviceConnectionPool(
            maxConcurrentConnections = 3,
            connectTimeoutMillis = 30_000,
        ) { id ->
            RecordingSession(id, connectDelayMillis = 20).also { session ->
                session.onConnectStart = {
                    activeConnects++
                    if (activeConnects > maxObservedConnects) maxObservedConnects = activeConnects
                }
                session.onConnectEnd = { activeConnects-- }
                sessions += session
            }
        }

        coroutineScope {
            devices.forEach { id ->
                launch {
                    repeat(20) {
                        val result = pool.connect(id)
                        check(result is ConnectResult.Connected)
                        pool.disconnect(id)
                    }
                }
            }
        }

        assertTrue(maxObservedConnects <= 3, "容量上限 3 被突破：$maxObservedConnects")
        assertTrue(activeConnects == 0, "建链结束后并发计数应归零")
        assertTrue(pool.activeSessions().isEmpty(), "全部断开后活跃集合应为空")
        devices.forEach { id ->
            assertEquals(DeviceState.DISCONNECTED, pool.stateOf(id), "$id 状态未收敛")
        }
        assertEquals(
            sessions.sumOf { it.connectCalls },
            sessions.sumOf { it.disconnectCalls },
            "每次成功建链都必须有对应断开，会话不得泄漏",
        )

        // 槽位已全部归还：容量 3 下仍可立即建链。
        val after = pool.connect("dev-after")
        assertTrue(after is ConnectResult.Connected, "压力测试后槽位应已归还")
        pool.disconnect("dev-after")
    }

    @Test
    fun `建链超时会关闭半开会话并归还槽位`() = runTest {
        val slow = RecordingSession("slow", connectDelayMillis = 10_000)
        val pool = MultiDeviceConnectionPool(
            maxConcurrentConnections = 1,
            connectTimeoutMillis = 100,
        ) { id -> if (id == "slow") slow else RecordingSession(id) }

        val timedOut = pool.connect("slow")
        assertTrue(timedOut is ConnectResult.Failed)
        assertEquals(PoolFailure.Timeout, timedOut.failure)
        assertEquals(DeviceState.DISCONNECTED, pool.stateOf("slow"))
        assertEquals(1, slow.disconnectCalls, "超时后必须关闭已创建的会话")
        assertTrue(pool.activeSessions().isEmpty())

        val next = pool.connect("fast")
        assertTrue(next is ConnectResult.Connected, "超时后槽位应已归还")
        pool.disconnect("fast")
    }

    @Test
    fun `连接协程被取消时状态收敛为未连接`() = runTest {
        val slow = RecordingSession("dev", connectDelayMillis = 10_000)
        val pool = MultiDeviceConnectionPool(
            maxConcurrentConnections = 1,
            connectTimeoutMillis = 30_000,
        ) { slow }

        val job: Job = launch { pool.connect("dev") }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertEquals(DeviceState.DISCONNECTED, pool.stateOf("dev"))
        assertTrue(pool.activeSessions().isEmpty())
        assertEquals(1, slow.disconnectCalls, "取消后必须关闭已创建的会话")
    }

    private class RecordingSession(
        override val deviceId: String,
        private val connectDelayMillis: Long = 0,
    ) : ConnectionSession {

        var connectCalls: Int = 0
            private set
        var disconnectCalls: Int = 0
            private set

        var onConnectStart: () -> Unit = {}
        var onConnectEnd: () -> Unit = {}

        override suspend fun connect() {
            connectCalls++
            onConnectStart()
            try {
                if (connectDelayMillis > 0) delay(connectDelayMillis)
            } finally {
                onConnectEnd()
            }
        }

        override suspend fun disconnect() {
            disconnectCalls++
        }
    }
}
