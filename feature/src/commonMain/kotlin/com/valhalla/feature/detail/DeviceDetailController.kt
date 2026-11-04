package com.valhalla.feature.detail

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.logging.DeviceLogger
import com.valhalla.core.logging.InMemoryDeviceLogger
import com.valhalla.core.logging.LogLevel
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.FrameCodec
import com.valhalla.core.state.DeviceEvent
import com.valhalla.core.state.DeviceState
import com.valhalla.core.state.DeviceStateMachine
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleUuids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设备详情页状态。 */
data class DeviceDetailState(
    val deviceId: String,
    val deviceState: DeviceState = DeviceState.CONNECTED,
    val firmwareVersion: String? = null,
    val batteryPercent: Int? = null,
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * 设备详情控制器：链路建立后的鉴权、信息读取与断开流程。
 *
 * 状态迁移统一交给 [DeviceStateMachine]，本类只负责把 GATT 结果翻译成事件。
 * 鉴权报文使用 core 的 v1 报文协议（AUTH 指令，payload 为 token）。
 */
class DeviceDetailController(
    private val connection: BleConnection,
    private val logger: DeviceLogger = InMemoryDeviceLogger(),
) {

    private val machine = DeviceStateMachine(initialState = DeviceState.CONNECTED)
    private val mutableState = MutableStateFlow(
        DeviceDetailState(deviceId = connection.deviceId, deviceState = machine.state),
    )
    val state: StateFlow<DeviceDetailState> = mutableState.asStateFlow()

    private var sequence = 0

    init {
        log(LogLevel.INFO, "链路已建立：${connection.deviceId}")
    }

    /** 发送鉴权指令。成功进入 READY，失败进入 FAULT。 */
    suspend fun authenticate(token: String): Boolean {
        transition(DeviceEvent.AuthenticationStarted)
        log(LogLevel.INFO, "开始鉴权")

        return try {
            val frame = Frame(
                type = CommandType.AUTH,
                sequence = nextSequence(),
                payload = token.encodeToByteArray(),
            )
            connection.write(
                serviceUuid = BleUuids.OTA_SERVICE,
                characteristicUuid = BleUuids.OTA_CONTROL,
                payload = FrameCodec.encode(frame),
                withResponse = true,
            )
            transition(DeviceEvent.AuthSucceeded)
            log(LogLevel.INFO, "鉴权成功，设备进入就绪状态")
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val reason = error.message ?: "鉴权指令写入失败"
            transition(DeviceEvent.AuthFailed(reason))
            log(LogLevel.ERROR, "鉴权失败：$reason")
            false
        }
    }

    /** 读取固件版本与电量。单项失败不影响另一项。 */
    suspend fun refreshInfo() {
        mutableState.value = mutableState.value.copy(error = null)
        val firmware = readOrNull(BleUuids.DEVICE_INFO_SERVICE, BleUuids.FIRMWARE_REVISION)
            ?.decodeToString()
        val battery = readOrNull(BleUuids.BATTERY_SERVICE, BleUuids.BATTERY_LEVEL)
            ?.firstOrNull()
            ?.toInt()
            ?.coerceIn(0, 100)

        mutableState.value = mutableState.value.copy(
            firmwareVersion = firmware ?: mutableState.value.firmwareVersion,
            batteryPercent = battery ?: mutableState.value.batteryPercent,
            error = if (firmware == null && battery == null) "设备信息读取失败" else null,
        )

        if (firmware != null || battery != null) {
            log(LogLevel.INFO, "信息刷新：固件=${firmware ?: "未知"}，电量=${battery ?: "未知"}")
        }
    }

    /** 主动断开，状态回落 DISCONNECTED。 */
    suspend fun disconnect() {
        transition(DeviceEvent.DisconnectRequested)
        try {
            connection.disconnect()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log(LogLevel.WARN, "断开时出现异常：${error.message}")
        } finally {
            transition(DeviceEvent.Disconnected)
            log(LogLevel.INFO, "链路已断开")
        }
    }

    fun deviceLogger(): DeviceLogger = logger

    private suspend fun readOrNull(serviceUuid: String, characteristicUuid: String): ByteArray? =
        try {
            connection.read(serviceUuid, characteristicUuid)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log(LogLevel.WARN, "读取 $characteristicUuid 失败：${error.message}")
            null
        }

    private fun transition(event: DeviceEvent) {
        machine.handle(event)
        mutableState.value = mutableState.value.copy(deviceState = machine.state)
    }

    private fun log(level: LogLevel, message: String) {
        logger.log(level, "DeviceDetail", message)
        mutableState.value = mutableState.value.copy(
            logs = logger.entries().map { it.format() },
        )
    }

    private fun nextSequence(): Int {
        val current = sequence
        sequence = (sequence + 1) and 0xFFFF
        return current
    }
}
