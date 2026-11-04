package com.valhalla.data.pool

import com.valhalla.core.state.DeviceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MultiDeviceConnectionPoolTest {

    @Test
    fun `成功建链后状态为 CONNECTED`() = runTest {
        val pool = poolOf(maxConcurrent = 2)
        val result = pool.connect("dev-1")

        assertTrue(result is ConnectResult.Connected)
        assertEquals(DeviceState.CONNECTED, pool.stateOf("dev-1"))
        assertEquals(setOf("dev-1"), pool.activeSessions())
    }

    @Test
    fun `重复连接同一设备被幂等忽略`() = runTest {
        val sessions = mutableMapOf<String, FakeSession>()
        val pool = poolOf(factory = { id -> sessions.getOrPut(id) { FakeSession(id) } })

        pool.connect("dev-1")
        val second = pool.connect("dev-1")

        assertTrue(second is ConnectResult.AlreadyConnected)
        assertEquals(1, sessions.getValue("dev-1").connectCalls)
    }

    @Test
    fun `同时连接数受信号量限制`() = runTest {
        var concurrent = 0
        var maxConcurrent = 0
        val pool = MultiDeviceConnectionPool(maxConcurrentConnections = 1) { id ->
            FakeSession(
                deviceId = id,
                connectDelayMillis = 100,
                onConnectStart = {
                    concurrent++
                    if (concurrent > maxConcurrent) maxConcurrent = concurrent
                },
                onConnectEnd = { concurrent-- },
            )
        }

        launch { pool.connect("dev-a") }
        launch { pool.connect("dev-b") }
        launch { pool.connect("dev-c") }
        advanceUntilIdle()

        assertEquals(1, maxConcurrent, "同一时刻只允许一条建链")
        assertEquals(3, pool.activeSessions().size)
    }

    @Test
    fun `建链超时返回 Timeout 且回到未连接`() = runTest {
        val pool = MultiDeviceConnectionPool(
            maxConcurrentConnections = 1,
            connectTimeoutMillis = 100,
        ) { id -> FakeSession(id, connectDelayMillis = 1_000) }

        val result = pool.connect("dev-1")

        assertTrue(result is ConnectResult.Failed)
        assertEquals(PoolFailure.Timeout, result.failure)
        assertEquals(DeviceState.DISCONNECTED, pool.stateOf("dev-1"))
        assertTrue(pool.activeSessions().isEmpty())
    }

    @Test
    fun `建链失败会归还容量且下一个设备可接入`() = runTest {
        val pool = MultiDeviceConnectionPool(maxConcurrentConnections = 1) { id ->
            if (id == "bad") FakeSession(id, failWith = SimulatedLinkException()) else FakeSession(id)
        }

        val failed = pool.connect("bad")
        assertTrue(failed is ConnectResult.Failed)
        assertEquals(
            PoolFailure.Transport("模拟链路异常"),
            failed.failure,
        )
        assertEquals(DeviceState.DISCONNECTED, pool.stateOf("bad"))

        // 容量已归还，下一个设备无需等待即可建链。
        val next = pool.connect("good")
        assertTrue(next is ConnectResult.Connected)
        assertEquals(DeviceState.CONNECTED, pool.stateOf("good"))
    }

    @Test
    fun `断开后状态回到未连接并可重新连接`() = runTest {
        val sessions = mutableMapOf<String, FakeSession>()
        val pool = poolOf(factory = { id -> sessions.getOrPut(id) { FakeSession(id) } })

        pool.connect("dev-1")
        pool.disconnect("dev-1")
        assertEquals(DeviceState.DISCONNECTED, pool.stateOf("dev-1"))
        assertEquals(1, sessions.getValue("dev-1").disconnectCalls)
        assertTrue(pool.activeSessions().isEmpty())

        val reconnect = pool.connect("dev-1")
        assertTrue(reconnect is ConnectResult.Connected)
        assertEquals(2, sessions.getValue("dev-1").connectCalls)
    }

    @Test
    fun `disconnectAll 断开全部设备`() = runTest {
        val pool = poolOf(maxConcurrent = 3)
        pool.connect("dev-a")
        pool.connect("dev-b")

        pool.disconnectAll()

        assertTrue(pool.activeSessions().isEmpty())
        assertEquals(DeviceState.DISCONNECTED, pool.stateOf("dev-a"))
        assertEquals(DeviceState.DISCONNECTED, pool.stateOf("dev-b"))
    }

    @Test
    fun `连接事件按顺序广播`() = runTest {
        val pool = poolOf(factory = { id -> FakeSession(id) })
        val events = mutableListOf<PoolEvent>()
        val job = launch { pool.events.collect { events += it } }
        runCurrent()

        pool.connect("dev-1")
        pool.disconnect("dev-1")
        runCurrent()
        job.cancel()

        assertTrue(events.any { it is PoolEvent.ConnectionStarted })
        assertTrue(events.any { it is PoolEvent.ConnectionEstablished })
        assertTrue(events.any { it is PoolEvent.Disconnected })
    }

    private fun poolOf(
        maxConcurrent: Int = 2,
        factory: (String) -> ConnectionSession = { FakeSession(it) },
    ) = MultiDeviceConnectionPool(
        maxConcurrentConnections = maxConcurrent,
        connectTimeoutMillis = 30_000,
        sessionFactory = factory,
    )

    private class SimulatedLinkException : Exception("模拟链路异常")

    private class FakeSession(
        override val deviceId: String,
        private val connectDelayMillis: Long = 0,
        private val failWith: Throwable? = null,
        private val onConnectStart: () -> Unit = {},
        private val onConnectEnd: () -> Unit = {},
    ) : ConnectionSession {

        var connectCalls: Int = 0
            private set

        var disconnectCalls: Int = 0
            private set

        override suspend fun connect() {
            connectCalls++
            onConnectStart()
            try {
                if (connectDelayMillis > 0) delay(connectDelayMillis)
                failWith?.let { throw it }
            } finally {
                onConnectEnd()
            }
        }

        override suspend fun disconnect() {
            disconnectCalls++
        }
    }
}
