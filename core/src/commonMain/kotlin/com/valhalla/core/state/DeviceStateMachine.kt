package com.valhalla.core.state

/**
 * 设备生命周期状态机。
 *
 * 设计约束：
 * - 状态迁移集中在一处，UI / 连接池只订阅结果，避免各处各写一套 if。
 * - 非法事件不抛异常，返回 [TransitionResult.Rejected]；重复事件返回 [TransitionResult.Ignored]。
 * - 保留有限长度的迁移历史，便于问题回溯。
 */
class DeviceStateMachine(
    initialState: DeviceState = DeviceState.DISCONNECTED,
    private val historyLimit: Int = 32,
    private val onTransition: ((StateTransition) -> Unit)? = null,
) {
    var state: DeviceState = initialState
        private set

    private val history: MutableList<StateTransition> = mutableListOf()

    fun transitions(): List<StateTransition> = history.toList()

    fun handle(
        event: DeviceEvent,
        timestampMillis: Long = 0L,
    ): TransitionResult {
        val current = state

        if (isIdempotent(current, event)) {
            return TransitionResult.Ignored(current, event, "当前状态 $current 已满足事件 ${eventName(event)}")
        }

        val target =
            resolve(current, event)
                ?: return TransitionResult.Rejected(current, event, "状态 $current 不允许事件 ${eventName(event)}")

        val transition = StateTransition(from = current, to = target, event = event, timestampMillis = timestampMillis)
        state = target
        history += transition
        if (history.size > historyLimit) {
            history.removeAt(0)
        }
        onTransition?.invoke(transition)
        return TransitionResult.Accepted(transition)
    }

    val isConnected: Boolean
        get() = state == DeviceState.CONNECTED || state == DeviceState.AUTHENTICATING || state == DeviceState.READY

    val isReady: Boolean
        get() = state == DeviceState.READY

    private fun isIdempotent(
        current: DeviceState,
        event: DeviceEvent,
    ): Boolean =
        when {
            current == DeviceState.CONNECTING && event == DeviceEvent.ConnectRequested -> true
            current == DeviceState.READY && event == DeviceEvent.AuthSucceeded -> true
            current == DeviceState.READY && event == DeviceEvent.AuthenticationStarted -> true
            current == DeviceState.DISCONNECTING && event == DeviceEvent.DisconnectRequested -> true
            current == DeviceState.DISCONNECTED && event == DeviceEvent.Disconnected -> true
            current == DeviceState.DISCONNECTED && event == DeviceEvent.LinkLost -> true
            else -> false
        }

    private fun resolve(
        current: DeviceState,
        event: DeviceEvent,
    ): DeviceState? =
        when (current) {
            DeviceState.DISCONNECTED -> resolveFromDisconnected(event)
            DeviceState.CONNECTING -> resolveFromConnecting(event)
            DeviceState.CONNECTED -> resolveFromConnected(event)
            DeviceState.AUTHENTICATING -> resolveFromAuthenticating(event)
            DeviceState.READY -> resolveFromReady(event)
            DeviceState.DISCONNECTING -> resolveFromDisconnecting(event)
            DeviceState.FAULT -> resolveFromFault(event)
        }

    private fun resolveFromDisconnected(event: DeviceEvent): DeviceState? =
        when (event) {
            DeviceEvent.ConnectRequested -> DeviceState.CONNECTING
            is DeviceEvent.FaultRaised -> DeviceState.FAULT
            else -> null
        }

    private fun resolveFromConnecting(event: DeviceEvent): DeviceState? =
        when (event) {
            DeviceEvent.LinkEstablished -> DeviceState.CONNECTED
            DeviceEvent.LinkLost -> DeviceState.DISCONNECTED
            DeviceEvent.DisconnectRequested -> DeviceState.DISCONNECTING
            is DeviceEvent.FaultRaised -> DeviceState.FAULT
            else -> null
        }

    private fun resolveFromConnected(event: DeviceEvent): DeviceState? =
        when (event) {
            DeviceEvent.AuthenticationStarted -> DeviceState.AUTHENTICATING
            DeviceEvent.LinkLost -> DeviceState.DISCONNECTED
            DeviceEvent.DisconnectRequested -> DeviceState.DISCONNECTING
            is DeviceEvent.FaultRaised -> DeviceState.FAULT
            else -> null
        }

    private fun resolveFromAuthenticating(event: DeviceEvent): DeviceState? =
        when (event) {
            DeviceEvent.AuthSucceeded -> DeviceState.READY
            is DeviceEvent.AuthFailed -> DeviceState.FAULT
            DeviceEvent.LinkLost -> DeviceState.DISCONNECTED
            DeviceEvent.DisconnectRequested -> DeviceState.DISCONNECTING
            is DeviceEvent.FaultRaised -> DeviceState.FAULT
            else -> null
        }

    private fun resolveFromReady(event: DeviceEvent): DeviceState? =
        when (event) {
            DeviceEvent.LinkLost -> DeviceState.DISCONNECTED
            DeviceEvent.DisconnectRequested -> DeviceState.DISCONNECTING
            is DeviceEvent.FaultRaised -> DeviceState.FAULT
            else -> null
        }

    private fun resolveFromDisconnecting(event: DeviceEvent): DeviceState? =
        when (event) {
            DeviceEvent.Disconnected -> DeviceState.DISCONNECTED
            DeviceEvent.LinkLost -> DeviceState.DISCONNECTED
            is DeviceEvent.FaultRaised -> DeviceState.FAULT
            else -> null
        }

    private fun resolveFromFault(event: DeviceEvent): DeviceState? =
        when (event) {
            DeviceEvent.ResetRequested -> DeviceState.DISCONNECTED
            DeviceEvent.LinkLost -> DeviceState.DISCONNECTED
            else -> null
        }

    private fun eventName(event: DeviceEvent): String =
        when (event) {
            DeviceEvent.ConnectRequested -> "ConnectRequested"
            DeviceEvent.LinkEstablished -> "LinkEstablished"
            DeviceEvent.LinkLost -> "LinkLost"
            DeviceEvent.AuthenticationStarted -> "AuthenticationStarted"
            DeviceEvent.AuthSucceeded -> "AuthSucceeded"
            is DeviceEvent.AuthFailed -> "AuthFailed(${event.reason})"
            DeviceEvent.DisconnectRequested -> "DisconnectRequested"
            DeviceEvent.Disconnected -> "Disconnected"
            is DeviceEvent.FaultRaised -> "FaultRaised(${event.reason})"
            DeviceEvent.ResetRequested -> "ResetRequested"
        }
}
