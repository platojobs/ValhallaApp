package com.valhalla.feature.ble

import com.valhalla.data.ble.BleAdapterState
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage

/** 链路断开的原因分类。 */
enum class DisconnectReason {
    /** 用户主动断开。 */
    USER_REQUESTED,

    /** 设备侧断开（关机、超距、重启或设备主动踢链）。 */
    DEVICE_SIDE,

    /** 建立连接/服务发现超时。 */
    TIMEOUT,

    /** 权限被回收，无法继续通信。 */
    PERMISSION_LOST,

    /** 蓝牙适配器被关闭。 */
    BLUETOOTH_OFF,

    /** 连接建立阶段失败（非超时）。 */
    CONNECT_FAILED,

    /** 平台未给出可识别原因。 */
    UNKNOWN,
}

/** 断连后可执行的动作。 */
enum class DisconnectAction {
    NONE,
    RECONNECT,
    OPEN_SETTINGS,
    ENABLE_BLUETOOTH,
    RESCAN,
}

/** 面向 UI 的断连说明。 */
data class DisconnectInfo(
    val reason: DisconnectReason,
    val title: UiMessage,
    val message: UiMessage,
    val action: DisconnectAction = DisconnectAction.NONE,
    /** 是否允许自动重连（权限/主动断开等场景为 false）。 */
    val autoReconnectAllowed: Boolean = false,
) {
    val actionLabel: UiMessage?
        get() =
            when (action) {
                DisconnectAction.NONE -> null
                DisconnectAction.RECONNECT -> UiMessage(StringKey.RECONNECT_NOW)
                DisconnectAction.OPEN_SETTINGS -> UiMessage(StringKey.ACTION_OPEN_SETTINGS)
                DisconnectAction.ENABLE_BLUETOOTH -> UiMessage(StringKey.ACTION_ENABLE_BLUETOOTH)
                DisconnectAction.RESCAN -> UiMessage(StringKey.ACTION_RESCAN)
            }
}

/**
 * 分类断连的输入信号。
 *
 * 平台层不需要知道 UI 文案，只需如实上报「是否用户主动 / 是否超时 / 是否对端终止 /
 * 是否传输异常」，再结合权限与适配器状态即可得到唯一原因。
 */
data class DisconnectSignals(
    val userRequested: Boolean = false,
    val timeout: Boolean = false,
    val peerTerminated: Boolean = false,
    val transportError: Boolean = false,
    val adapterState: BleAdapterState = BleAdapterState.POWERED_ON,
    val permissionState: BlePermissionState = BlePermissionState.GRANTED,
)

/**
 * 断连原因映射，优先级从高到低：
 * 主动断开 > 权限丢失 > 蓝牙关闭 > 超时 > 设备侧断开 > 连接失败 > 未知。
 */
fun classifyDisconnect(signals: DisconnectSignals): DisconnectInfo =
    when {
        signals.userRequested ->
            DisconnectInfo(
                reason = DisconnectReason.USER_REQUESTED,
                title = UiMessage(StringKey.DISCONNECT_USER_TITLE),
                message = UiMessage(StringKey.DISCONNECT_USER_MESSAGE),
                action = DisconnectAction.RESCAN,
                autoReconnectAllowed = false,
            )

        signals.permissionState == BlePermissionState.PERMANENTLY_DENIED ||
            signals.permissionState == BlePermissionState.DENIED ||
            signals.adapterState == BleAdapterState.UNAUTHORIZED ->
            DisconnectInfo(
                reason = DisconnectReason.PERMISSION_LOST,
                title = UiMessage(StringKey.DISCONNECT_PERMISSION_TITLE),
                message = UiMessage(StringKey.DISCONNECT_PERMISSION_MESSAGE),
                action = DisconnectAction.OPEN_SETTINGS,
                autoReconnectAllowed = false,
            )

        signals.adapterState == BleAdapterState.POWERED_OFF ->
            DisconnectInfo(
                reason = DisconnectReason.BLUETOOTH_OFF,
                title = UiMessage(StringKey.DISCONNECT_BLUETOOTH_OFF_TITLE),
                message = UiMessage(StringKey.DISCONNECT_BLUETOOTH_OFF_MESSAGE),
                action = DisconnectAction.ENABLE_BLUETOOTH,
                autoReconnectAllowed = true,
            )

        signals.adapterState == BleAdapterState.RESETTING ->
            DisconnectInfo(
                reason = DisconnectReason.BLUETOOTH_OFF,
                title = UiMessage(StringKey.DISCONNECT_RESETTING_TITLE),
                message = UiMessage(StringKey.DISCONNECT_RESETTING_MESSAGE),
                action = DisconnectAction.RECONNECT,
                autoReconnectAllowed = true,
            )

        signals.timeout ->
            DisconnectInfo(
                reason = DisconnectReason.TIMEOUT,
                title = UiMessage(StringKey.DISCONNECT_TIMEOUT_TITLE),
                message = UiMessage(StringKey.DISCONNECT_TIMEOUT_MESSAGE),
                action = DisconnectAction.RECONNECT,
                autoReconnectAllowed = true,
            )

        signals.peerTerminated ->
            DisconnectInfo(
                reason = DisconnectReason.DEVICE_SIDE,
                title = UiMessage(StringKey.DISCONNECT_DEVICE_TITLE),
                message = UiMessage(StringKey.DISCONNECT_DEVICE_MESSAGE),
                action = DisconnectAction.RECONNECT,
                autoReconnectAllowed = true,
            )

        signals.transportError ->
            DisconnectInfo(
                reason = DisconnectReason.CONNECT_FAILED,
                title = UiMessage(StringKey.DISCONNECT_CONNECT_FAILED_TITLE),
                message = UiMessage(StringKey.DISCONNECT_CONNECT_FAILED_MESSAGE),
                action = DisconnectAction.RECONNECT,
                autoReconnectAllowed = true,
            )

        else ->
            DisconnectInfo(
                reason = DisconnectReason.UNKNOWN,
                title = UiMessage(StringKey.DISCONNECT_UNKNOWN_TITLE),
                message = UiMessage(StringKey.DISCONNECT_UNKNOWN_MESSAGE),
                action = DisconnectAction.RECONNECT,
                autoReconnectAllowed = true,
            )
    }
