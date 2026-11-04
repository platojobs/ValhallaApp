package com.valhalla.feature.ble

import com.valhalla.data.ble.BleAdapterState

/**
 * 蓝牙权限的四种稳定状态。
 *
 * 平台侧只负责把「系统当前是否授权 / 是否还能再次弹窗」翻译成事件，
 * 状态合并与「是否允许再次请求」由 [BlePermissionStateMachine] 统一决定。
 */
enum class BlePermissionState {
    /** 从未请求过运行时权限（Android 12+）或系统尚未给出结论（iOS）。 */
    NOT_REQUESTED,

    /** 已授权。 */
    GRANTED,

    /** 被拒绝，但系统仍允许再次弹窗。 */
    DENIED,

    /** 被拒绝且系统不再允许弹窗，只能引导用户去系统设置。 */
    PERMANENTLY_DENIED,
}

/** 引导用户可执行的动作。 */
enum class BleGuidanceAction {
    NONE,
    REQUEST_PERMISSION,
    OPEN_SETTINGS,
    ENABLE_BLUETOOTH,
    RETRY,
}

/** 一段可直接展示给用户的引导文案。 */
data class BleGuidance(
    val title: String,
    val message: String,
    val action: BleGuidanceAction = BleGuidanceAction.NONE,
) {
    val actionLabel: String?
        get() = when (action) {
            BleGuidanceAction.NONE -> null
            BleGuidanceAction.REQUEST_PERMISSION -> "授予权限"
            BleGuidanceAction.OPEN_SETTINGS -> "去系统设置"
            BleGuidanceAction.ENABLE_BLUETOOTH -> "开启蓝牙"
            BleGuidanceAction.RETRY -> "重试"
        }
}

/**
 * 权限状态机（纯逻辑，无平台依赖）。
 *
 * 约束：
 * - [PERMANENTLY_DENIED] 之后 [beginRequest] 永远返回 false，避免反复弹窗；
 * - 请求进行中重复调用 [beginRequest] 也返回 false，避免并发弹窗；
 * - [syncFromSystem] 在回到前台时用系统真实状态校正，防止 UI 停留在过期状态。
 */
class BlePermissionStateMachine(
    initialState: BlePermissionState = BlePermissionState.NOT_REQUESTED,
) {

    var state: BlePermissionState = initialState
        private set

    private var requestInFlight = false

    /** 是否可以再次发起系统权限请求。 */
    val canRequest: Boolean
        get() = !requestInFlight &&
            (state == BlePermissionState.NOT_REQUESTED || state == BlePermissionState.DENIED)

    /**
     * 尝试发起一次权限请求。
     * @return true 表示调用方应真正调起平台权限弹窗；false 表示不应弹窗。
     */
    fun beginRequest(): Boolean {
        if (!canRequest) return false
        requestInFlight = true
        return true
    }

    /** 平台权限弹窗返回结果。 */
    fun onRequestResult(granted: Boolean, canAskAgain: Boolean): BlePermissionState {
        requestInFlight = false
        state = when {
            granted -> BlePermissionState.GRANTED
            canAskAgain -> BlePermissionState.DENIED
            else -> BlePermissionState.PERMANENTLY_DENIED
        }
        return state
    }

    /**
     * 用系统当前状态校正（启动、回到前台、从设置返回）。
     *
     * @param hasRequestedBefore 该进程/安装是否请求过；从未请求过时保持 NOT_REQUESTED，
     * 以便首次进入页面时主动发起一次请求。
     */
    fun syncFromSystem(
        granted: Boolean,
        canAskAgain: Boolean,
        hasRequestedBefore: Boolean,
    ): BlePermissionState {
        requestInFlight = false
        state = when {
            granted -> BlePermissionState.GRANTED
            !hasRequestedBefore -> BlePermissionState.NOT_REQUESTED
            canAskAgain -> BlePermissionState.DENIED
            else -> BlePermissionState.PERMANENTLY_DENIED
        }
        return state
    }

    /** 外部取消（例如页面销毁）时清除在途标记。 */
    fun cancelRequest() {
        requestInFlight = false
    }
}

/** 蓝牙能力与权限合并后的可用状态。 */
enum class BleAvailability {
    READY,
    PERMISSION_NOT_REQUESTED,
    PERMISSION_DENIED,
    PERMISSION_PERMANENTLY_DENIED,
    BLUETOOTH_OFF,
    BLUETOOTH_RESETTING,
    UNSUPPORTED,
    UNKNOWN,
}

/**
 * 把「适配器状态 + 权限状态」收敛成单一可用状态。
 *
 * 权限优先：没有权限时即使蓝牙已开启也无法扫描；权限被系统永久拒绝时，
 * 适配器上报的 `UNAUTHORIZED` 也归入永久拒绝。
 */
fun resolveBleAvailability(
    adapterState: BleAdapterState,
    permissionState: BlePermissionState,
): BleAvailability = when (permissionState) {
    BlePermissionState.NOT_REQUESTED -> BleAvailability.PERMISSION_NOT_REQUESTED
    BlePermissionState.DENIED -> BleAvailability.PERMISSION_DENIED
    BlePermissionState.PERMANENTLY_DENIED -> BleAvailability.PERMISSION_PERMANENTLY_DENIED

    BlePermissionState.GRANTED -> when (adapterState) {
        BleAdapterState.POWERED_ON -> BleAvailability.READY
        BleAdapterState.POWERED_OFF -> BleAvailability.BLUETOOTH_OFF
        BleAdapterState.RESETTING -> BleAvailability.BLUETOOTH_RESETTING
        BleAdapterState.UNAUTHORIZED -> BleAvailability.PERMISSION_PERMANENTLY_DENIED
        BleAdapterState.UNSUPPORTED -> BleAvailability.UNSUPPORTED
        BleAdapterState.UNKNOWN -> BleAvailability.UNKNOWN
    }
}

/** 该可用状态是否允许扫描/连接。 */
fun BleAvailability.allowsConnection(): Boolean = this == BleAvailability.READY

/** 不可用时的引导文案；可用时返回 null。 */
fun BleAvailability.guidance(): BleGuidance? = when (this) {
    BleAvailability.READY -> null

    BleAvailability.PERMISSION_NOT_REQUESTED -> BleGuidance(
        title = "需要蓝牙权限",
        message = "扫描并连接设备需要蓝牙权限。Android 12 及以上使用「附近的设备」权限，旧版本使用定位权限。",
        action = BleGuidanceAction.REQUEST_PERMISSION,
    )

    BleAvailability.PERMISSION_DENIED -> BleGuidance(
        title = "蓝牙权限被拒绝",
        message = "没有蓝牙权限无法扫描设备。可再次授权；若系统不再弹窗，请前往系统设置手动开启。",
        action = BleGuidanceAction.REQUEST_PERMISSION,
    )

    BleAvailability.PERMISSION_PERMANENTLY_DENIED -> BleGuidance(
        title = "蓝牙权限已被永久拒绝",
        message = "系统不会再弹出授权窗口。请前往「设置 → 应用 → Valhalla → 权限」开启蓝牙/附近设备权限。",
        action = BleGuidanceAction.OPEN_SETTINGS,
    )

    BleAvailability.BLUETOOTH_OFF -> BleGuidance(
        title = "蓝牙已关闭",
        message = "请开启蓝牙后重试；开启后扫描与连接会自动恢复。",
        action = BleGuidanceAction.ENABLE_BLUETOOTH,
    )

    BleAvailability.BLUETOOTH_RESETTING -> BleGuidance(
        title = "蓝牙正在重置",
        message = "系统正在重启蓝牙服务，请稍候再试。",
        action = BleGuidanceAction.RETRY,
    )

    BleAvailability.UNSUPPORTED -> BleGuidance(
        title = "设备不支持蓝牙低功耗",
        message = "当前设备没有可用的 BLE 适配器，无法扫描或连接 Valhalla 硬件。",
    )

    BleAvailability.UNKNOWN -> BleGuidance(
        title = "蓝牙状态未知",
        message = "暂时无法获取蓝牙状态，请稍后重试。",
        action = BleGuidanceAction.RETRY,
    )
}
