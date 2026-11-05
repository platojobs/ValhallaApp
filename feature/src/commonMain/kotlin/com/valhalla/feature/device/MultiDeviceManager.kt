package com.valhalla.feature.device

import com.valhalla.data.ble.BleClient
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.pool.ConnectionSession
import com.valhalla.data.pool.ConnectResult
import com.valhalla.data.pool.MultiDeviceConnectionPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 已连接设备在列表中的展示信息。 */
data class ManagedDeviceInfo(
    val deviceId: String,
    val displayName: String,
    val connectedAtMillis: Long,
    /** 1-based 槽位序号；断开后会重排，界面据此展示「槽位 n/上限」。 */
    val slot: Int,
    /** 底层链路，仅供 feature 层创建 DeviceSession；UI 不直接使用。 */
    val connection: BleConnection,
)

/** 多设备连接状态快照。 */
data class MultiDeviceState(
    val devices: List<ManagedDeviceInfo> = emptyList(),
    val activeDeviceId: String? = null,
    val capacity: Int = 1,
) {
    val activeDevice: ManagedDeviceInfo? get() = devices.firstOrNull { it.deviceId == activeDeviceId }
    val occupied: Int get() = devices.size
}

/** 连接结果。容量满时直接拒绝并给出明确提示，不进入连接池排队。 */
sealed interface MultiDeviceConnectResult {
    data class Connected(val device: ManagedDeviceInfo) : MultiDeviceConnectResult

    data object AlreadyConnected : MultiDeviceConnectResult

    data class LimitReached(val capacity: Int) : MultiDeviceConnectResult

    data class Failed(val message: String) : MultiDeviceConnectResult

    data object InProgress : MultiDeviceConnectResult
}

/**
 * 多设备会话管理器。
 *
 * 复用 [MultiDeviceConnectionPool] 的链路语义（同一设备串行、超时归还容量），
 * 只在其上补充「容量上限前置拒绝、槽位展示、当前操作设备切换」。
 *
 * 容量策略：达到上限时直接返回 [MultiDeviceConnectResult.LimitReached]，
 * 由 UI 提示用户先断开一台；不会静默排队，也不会自动顶掉已有连接。
 */
@OptIn(ExperimentalTime::class)
class MultiDeviceManager(
    private val bleClient: BleClient,
    val capacity: Int = 4,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private val mutex = Mutex()
    private val sessionByDevice = mutableMapOf<String, BlePoolSession>()

    private val pool = MultiDeviceConnectionPool(
        maxConcurrentConnections = capacity,
        sessionFactory = { deviceId ->
            BlePoolSession(bleClient, deviceId).also { sessionByDevice[deviceId] = it }
        },
    )

    private val mutableState = MutableStateFlow(MultiDeviceState(capacity = capacity))
    val state: StateFlow<MultiDeviceState> = mutableState.asStateFlow()

    suspend fun connect(device: BleDevice): MultiDeviceConnectResult {
        val snapshot = mutex.withLock {
            val existing = mutableState.value.devices.firstOrNull { it.deviceId == device.deviceId }
            when {
                existing != null -> return@withLock MultiDeviceConnectResult.AlreadyConnected
                mutableState.value.occupied >= capacity ->
                    return@withLock MultiDeviceConnectResult.LimitReached(capacity)

                else -> null
            }
        }
        if (snapshot != null) return snapshot

        return when (val result = pool.connect(device.deviceId)) {
            ConnectResult.Connected, ConnectResult.AlreadyConnected -> {
                val connection = sessionByDevice[device.deviceId]?.connection
                    ?: return MultiDeviceConnectResult.Failed("底层链路未建立")
                mutex.withLock {
                    val current = mutableState.value
                    val existing = current.devices.firstOrNull { it.deviceId == device.deviceId }
                    if (existing != null) return@withLock MultiDeviceConnectResult.AlreadyConnected
                    val slot = lowestFreeSlot(current.devices.map { it.slot })
                    val info = ManagedDeviceInfo(
                        deviceId = device.deviceId,
                        displayName = device.name?.takeIf { it.isNotBlank() } ?: device.deviceId,
                        connectedAtMillis = clockMillis(),
                        slot = slot,
                        connection = connection,
                    )
                    mutableState.value = current.copy(
                        devices = current.devices + info,
                        activeDeviceId = current.activeDeviceId ?: info.deviceId,
                    )
                    MultiDeviceConnectResult.Connected(info)
                }
            }

            ConnectResult.InProgress -> MultiDeviceConnectResult.InProgress
            is ConnectResult.Failed -> MultiDeviceConnectResult.Failed(
                result.failure.toString(),
            )
        }
    }

    suspend fun disconnect(deviceId: String) {
        pool.disconnect(deviceId)
        mutex.withLock {
            sessionByDevice.remove(deviceId)
            val remaining = mutableState.value.devices.filterNot { it.deviceId == deviceId }
            val renumbered = remaining.mapIndexed { index, item -> item.copy(slot = index + 1) }
            val active = mutableState.value.activeDeviceId
                ?.takeIf { id -> renumbered.any { it.deviceId == id } }
                ?: renumbered.firstOrNull()?.deviceId
            mutableState.value = mutableState.value.copy(
                devices = renumbered,
                activeDeviceId = active,
            )
        }
    }

    suspend fun disconnectAll() {
        pool.disconnectAll()
        mutex.withLock {
            sessionByDevice.clear()
            mutableState.value = mutableState.value.copy(devices = emptyList(), activeDeviceId = null)
        }
    }

    /** 切换当前操作设备；只允许切到已连接设备。 */
    fun select(deviceId: String): Boolean {
        val current = mutableState.value
        if (current.devices.none { it.deviceId == deviceId }) return false
        if (current.activeDeviceId == deviceId) return true
        mutableState.value = current.copy(activeDeviceId = deviceId)
        return true
    }

    /** 自动重连成功后，把管理器内的链路引用替换为新连接，保持断开语义一致。 */
    fun replaceConnection(deviceId: String, connection: BleConnection): Boolean {
        val current = mutableState.value
        if (current.devices.none { it.deviceId == deviceId }) return false
        sessionByDevice[deviceId]?.adopt(connection)
        val connectedAt = clockMillis()
        mutableState.value = current.copy(
            devices = current.devices.map {
                if (it.deviceId == deviceId) {
                    it.copy(connection = connection, connectedAtMillis = connectedAt)
                } else {
                    it
                }
            },
        )
        return true
    }

    private fun lowestFreeSlot(used: List<Int>): Int {
        var slot = 1
        while (slot in used) slot++
        return slot
    }
}

/** 把 [BleClient.connect] 适配成连接池所需的 [ConnectionSession]。 */
private class BlePoolSession(
    private val bleClient: BleClient,
    override val deviceId: String,
) : ConnectionSession {

    var connection: BleConnection? = null
        private set

    override suspend fun connect() {
        connection = bleClient.connect(deviceId)
    }

    /** 自动重连后替换底层链路；旧链路已失效，不重复 disconnect。 */
    fun adopt(newConnection: BleConnection) {
        connection = newConnection
    }

    override suspend fun disconnect() {
        runCatching { connection?.disconnect() }
        connection = null
    }
}
