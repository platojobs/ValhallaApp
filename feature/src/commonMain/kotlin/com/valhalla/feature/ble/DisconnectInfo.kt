package com.valhalla.feature.ble

import com.valhalla.data.ble.BleAdapterState

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
    val title: String,
    val message: String,
    val action: DisconnectAction = DisconnectAction.NONE,
    /** 是否允许自动重连（权限/主动断开等场景为 false）。 */
    val autoReconnectAllowed: Boolean = false,
) {
    val actionLabel: String?
        get() = when (action) {
            DisconnectAction.NONE -> null
            DisconnectAction.RECONNECT -> "重新连接"
            DisconnectAction.OPEN_SETTINGS -> "去系统设置"
            DisconnectAction.ENABLE_BLUETOOTH -> "开启蓝牙"
            DisconnectAction.RESCAN -> "返回扫描"
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
fun classifyDisconnect(signals: DisconnectSignals): DisconnectInfo = when {
    signals.userRequested -> DisconnectInfo(
        reason = DisconnectReason.USER_REQUESTED,
        title = "已断开连接",
        message = "你主动断开了与设备的连接。",
        action = DisconnectAction.RESCAN,
        autoReconnectAllowed = false,
    )

    signals.permissionState == BlePermissionState.PERMANENTLY_DENIED ||
        signals.permissionState == BlePermissionState.DENIED ||
        signals.adapterState == BleAdapterState.UNAUTHORIZED -> DisconnectInfo(
        reason = DisconnectReason.PERMISSION_LOST,
        title = "蓝牙权限丢失",
        message = "系统收回了蓝牙权限，无法继续与设备通信。请重新授权后连接。",
        action = DisconnectAction.OPEN_SETTINGS,
        autoReconnectAllowed = false,
    )

    signals.adapterState == BleAdapterState.POWERED_OFF -> DisconnectInfo(
        reason = DisconnectReason.BLUETOOTH_OFF,
        title = "蓝牙已关闭",
        message = "蓝牙关闭导致链路中断，开启蓝牙后将自动尝试重连。",
        action = DisconnectAction.ENABLE_BLUETOOTH,
        autoReconnectAllowed = true,
    )

    signals.adapterState == BleAdapterState.RESETTING -> DisconnectInfo(
        reason = DisconnectReason.BLUETOOTH_OFF,
        title = "蓝牙正在重置",
        message = "系统正在重启蓝牙服务，链路暂时不可用，稍后会自动重连。",
        action = DisconnectAction.RECONNECT,
        autoReconnectAllowed = true,
    )

    signals.timeout -> DisconnectInfo(
        reason = DisconnectReason.TIMEOUT,
        title = "连接超时",
        message = "在限定时间内未能完成连接。请确认设备已上电并靠近手机后重试。",
        action = DisconnectAction.RECONNECT,
        autoReconnectAllowed = true,
    )

    signals.peerTerminated -> DisconnectInfo(
        reason = DisconnectReason.DEVICE_SIDE,
        title = "设备已断开连接",
        message = "设备侧断开了链路，可能已关机、超出范围或正在重启。",
        action = DisconnectAction.RECONNECT,
        autoReconnectAllowed = true,
    )

    signals.transportError -> DisconnectInfo(
        reason = DisconnectReason.CONNECT_FAILED,
        title = "连接失败",
        message = "与设备建立链路失败，请靠近设备后重试。",
        action = DisconnectAction.RECONNECT,
        autoReconnectAllowed = true,
    )

    else -> DisconnectInfo(
        reason = DisconnectReason.UNKNOWN,
        title = "连接已断开",
        message = "未知原因导致链路中断，可尝试重新连接。",
        action = DisconnectAction.RECONNECT,
        autoReconnectAllowed = true,
    )
}
