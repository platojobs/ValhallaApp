package com.valhalla.data.pool

import com.valhalla.core.state.DeviceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 多设备连接池。
 *
 * 职责：
 * - 用信号量限制同时建链的设备数量，其余请求排队。
 * - 同一设备并发请求串行化：重复点击不会产生两条链路。
 * - 建链超时、失败都会立即归还容量，避免槽位泄漏。
 * - 暴露聚合状态 [states] 与事件流 [events]，UI 无需自行轮询设备。
 *
 * 说明：鉴权状态（AUTHENTICATING/READY）由 feature 层在拿到会话后推进，本池只负责链路级状态。
 */
class MultiDeviceConnectionPool(
    /** 同时允许持有的链路上限；UI 需要展示「占用槽位/上限」，因此对外暴露。 */
    val maxConcurrentConnections: Int = 4,
    val connectTimeoutMillis: Long = 15_000L,
    private val sessionFactory: (String) -> ConnectionSession,
) {
    init {
        require(maxConcurrentConnections > 0) { "maxConcurrentConnections 必须大于 0" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis 必须大于 0" }
    }

    private val capacity = Semaphore(maxConcurrentConnections)

    private val stateMutex = Mutex()
    private val sessions = mutableMapOf<String, ConnectionSession>()
    private val deviceMutexes = mutableMapOf<String, Mutex>()

    private val mutableStates = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    private val mutableEvents = MutableSharedFlow<PoolEvent>(extraBufferCapacity = 32)

    /** 已持有链路的设备快照；与 [sessions] 在同一把锁下更新，可被任意线程安全读取。 */
    private val mutableActiveIds = MutableStateFlow<Set<String>>(emptySet())

    /** 所有被连接池管理过的设备当前链路状态。 */
    val states: StateFlow<Map<String, DeviceState>> = mutableStates.asStateFlow()

    /** 连接池事件流（不重放，供日志/UI 现时订阅）。 */
    val events: SharedFlow<PoolEvent> = mutableEvents.asSharedFlow()

    fun stateOf(deviceId: String): DeviceState? = mutableStates.value[deviceId]

    /** 已持有链路的设备集合快照。 */
    fun activeSessions(): Set<String> = mutableActiveIds.value

    /**
     * 为指定设备建立链路。
     *
     * 已在连接/已连接的设备不会重复建链；容量满时排队，超过 [connectTimeoutMillis] 返回
     * [PoolFailure.Timeout]。容量信号量只覆盖「建链阶段」，建链成功或失败都会在退出
     * `withPermit` 时归还，因此不会因为设备长期在线而占满槽位。
     *
     * 资源收敛保证：
     * - 建链失败或被取消时，已创建的 [ConnectionSession] 会在 `NonCancellable` 上下文中关闭，
     *   避免半建立链路泄漏；
     * - 调用方取消时状态回落到 `DISCONNECTED`，不会永久停留在 `CONNECTING`。
     */
    suspend fun connect(deviceId: String): ConnectResult =
        deviceMutexOf(deviceId).withLock {
            when (mutableStates.value[deviceId]) {
                DeviceState.CONNECTING -> return@withLock ConnectResult.InProgress
                DeviceState.CONNECTED, DeviceState.AUTHENTICATING, DeviceState.READY ->
                    return@withLock ConnectResult.AlreadyConnected
                else -> Unit
            }

            publish(PoolEvent.ConnectionStarted(deviceId))
            setState(deviceId, DeviceState.CONNECTING)

            var failure: PoolFailure? = null
            var createdSession: ConnectionSession? = null
            var registered = false

            val connected =
                try {
                    withTimeoutOrNull(connectTimeoutMillis) {
                        capacity.withPermit {
                            try {
                                val session = sessionFactory(deviceId)
                                createdSession = session
                                session.connect()
                                stateMutex.withLock {
                                    sessions[deviceId] = session
                                    mutableActiveIds.value = mutableActiveIds.value + deviceId
                                }
                                registered = true
                                true
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                failure =
                                    PoolFailure.Transport(
                                        error.message ?: error::class.simpleName ?: "未知错误",
                                    )
                                false
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    closeOrphan(createdSession, registered)
                    withContext(NonCancellable) {
                        setState(deviceId, DeviceState.DISCONNECTED)
                        publish(PoolEvent.ConnectionFailed(deviceId, PoolFailure.Transport("连接已取消")))
                    }
                    throw cancellation
                }

            // 失败或超时时关闭可能已部分建立的会话，避免底层资源泄漏。
            if (!registered) closeOrphan(createdSession, registered)

            when {
                connected == true -> {
                    setState(deviceId, DeviceState.CONNECTED)
                    publish(PoolEvent.ConnectionEstablished(deviceId))
                    ConnectResult.Connected
                }

                connected == false -> {
                    val reason = failure ?: PoolFailure.Transport("未提供失败原因")
                    setState(deviceId, DeviceState.DISCONNECTED)
                    publish(PoolEvent.ConnectionFailed(deviceId, reason))
                    ConnectResult.Failed(reason)
                }

                else -> {
                    // 极窄竞态：建链在超时边界完成并被注册，但 withTimeoutOrNull 已返回 null。
                    // 这里主动摘除并关闭，避免出现「池内仍有会话、状态却为 DISCONNECTED」的泄漏。
                    if (registered) {
                        removeSession(deviceId)?.let { stale ->
                            withContext(NonCancellable) { runCatching { stale.disconnect() } }
                        }
                    }
                    setState(deviceId, DeviceState.DISCONNECTED)
                    publish(PoolEvent.ConnectionFailed(deviceId, PoolFailure.Timeout))
                    ConnectResult.Failed(PoolFailure.Timeout)
                }
            }
        }

    /** 关闭尚未注册进池的会话；已注册的会话由 [disconnect] 负责释放。 */
    private suspend fun closeOrphan(
        session: ConnectionSession?,
        registered: Boolean,
    ) {
        if (session == null || registered) return
        withContext(NonCancellable) {
            runCatching { session.disconnect() }
        }
    }

    /** 断开指定设备。未连接时为空操作。 */
    suspend fun disconnect(deviceId: String) =
        deviceMutexOf(deviceId).withLock {
            val session = removeSession(deviceId)
            if (session == null && mutableStates.value[deviceId] == null) {
                return@withLock
            }
            session?.let { runCatching { it.disconnect() } }
            setState(deviceId, DeviceState.DISCONNECTED)
            publish(PoolEvent.Disconnected(deviceId))
        }

    /** 在同一把锁内摘除会话并同步活跃集合。 */
    private suspend fun removeSession(deviceId: String): ConnectionSession? =
        stateMutex.withLock {
            val removed = sessions.remove(deviceId)
            if (removed != null) {
                mutableActiveIds.value = mutableActiveIds.value - deviceId
            }
            removed
        }

    /** 断开池内全部设备，常用于登出或应用退到后台。 */
    suspend fun disconnectAll() {
        activeSessions().forEach { disconnect(it) }
    }

    private suspend fun deviceMutexOf(deviceId: String): Mutex =
        stateMutex.withLock {
            deviceMutexes.getOrPut(deviceId) { Mutex() }
        }

    private suspend fun setState(
        deviceId: String,
        state: DeviceState,
    ) {
        stateMutex.withLock {
            mutableStates.value = mutableStates.value + (deviceId to state)
        }
    }

    private fun publish(event: PoolEvent) {
        mutableEvents.tryEmit(event)
    }
}
