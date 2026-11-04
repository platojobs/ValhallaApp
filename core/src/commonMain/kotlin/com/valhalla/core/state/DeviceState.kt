package com.valhalla.core.state

/** 设备生命周期状态。 */
enum class DeviceState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATING,
    READY,
    DISCONNECTING,
    FAULT,
}

/** 驱动状态机的事件。 */
sealed interface DeviceEvent {
    data object ConnectRequested : DeviceEvent
    data object LinkEstablished : DeviceEvent
    data object LinkLost : DeviceEvent
    data object AuthenticationStarted : DeviceEvent
    data object AuthSucceeded : DeviceEvent
    data class AuthFailed(val reason: String) : DeviceEvent
    data object DisconnectRequested : DeviceEvent
    data object Disconnected : DeviceEvent
    data class FaultRaised(val reason: String) : DeviceEvent
    data object ResetRequested : DeviceEvent
}

data class StateTransition(
    val from: DeviceState,
    val to: DeviceState,
    val event: DeviceEvent,
    val timestampMillis: Long,
)

sealed interface TransitionResult {
    data class Accepted(val transition: StateTransition) : TransitionResult
    data class Ignored(val from: DeviceState, val event: DeviceEvent, val reason: String) : TransitionResult
    data class Rejected(val from: DeviceState, val event: DeviceEvent, val reason: String) : TransitionResult
}
