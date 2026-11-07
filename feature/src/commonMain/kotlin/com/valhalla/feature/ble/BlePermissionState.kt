package com.valhalla.feature.ble

import com.valhalla.data.ble.BleAdapterState
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage

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
    val title: UiMessage,
    val message: UiMessage,
    val action: BleGuidanceAction = BleGuidanceAction.NONE,
) {
    val actionLabel: UiMessage?
        get() =
            when (action) {
                BleGuidanceAction.NONE -> null
                BleGuidanceAction.REQUEST_PERMISSION -> UiMessage(StringKey.ACTION_GRANT_PERMISSION)
                BleGuidanceAction.OPEN_SETTINGS -> UiMessage(StringKey.ACTION_OPEN_SETTINGS)
                BleGuidanceAction.ENABLE_BLUETOOTH -> UiMessage(StringKey.ACTION_ENABLE_BLUETOOTH)
                BleGuidanceAction.RETRY -> UiMessage(StringKey.RETRY)
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
        get() =
            !requestInFlight &&
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
    fun onRequestResult(
        granted: Boolean,
        canAskAgain: Boolean,
    ): BlePermissionState {
        requestInFlight = false
        state =
            when {
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
        state =
            when {
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
): BleAvailability =
    when (permissionState) {
        BlePermissionState.NOT_REQUESTED -> BleAvailability.PERMISSION_NOT_REQUESTED
        BlePermissionState.DENIED -> BleAvailability.PERMISSION_DENIED
        BlePermissionState.PERMANENTLY_DENIED -> BleAvailability.PERMISSION_PERMANENTLY_DENIED

        BlePermissionState.GRANTED ->
            when (adapterState) {
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
fun BleAvailability.guidance(): BleGuidance? =
    when (this) {
        BleAvailability.READY -> null

        BleAvailability.PERMISSION_NOT_REQUESTED ->
            BleGuidance(
                title = UiMessage(StringKey.GUIDANCE_NEED_PERMISSION_TITLE),
                message = UiMessage(StringKey.GUIDANCE_NEED_PERMISSION_MESSAGE),
                action = BleGuidanceAction.REQUEST_PERMISSION,
            )

        BleAvailability.PERMISSION_DENIED ->
            BleGuidance(
                title = UiMessage(StringKey.GUIDANCE_PERMISSION_DENIED_TITLE),
                message = UiMessage(StringKey.GUIDANCE_PERMISSION_DENIED_MESSAGE),
                action = BleGuidanceAction.REQUEST_PERMISSION,
            )

        BleAvailability.PERMISSION_PERMANENTLY_DENIED ->
            BleGuidance(
                title = UiMessage(StringKey.GUIDANCE_PERMISSION_PERMANENT_TITLE),
                message = UiMessage(StringKey.GUIDANCE_PERMISSION_PERMANENT_MESSAGE),
                action = BleGuidanceAction.OPEN_SETTINGS,
            )

        BleAvailability.BLUETOOTH_OFF ->
            BleGuidance(
                title = UiMessage(StringKey.GUIDANCE_BLUETOOTH_OFF_TITLE),
                message = UiMessage(StringKey.GUIDANCE_BLUETOOTH_OFF_MESSAGE),
                action = BleGuidanceAction.ENABLE_BLUETOOTH,
            )

        BleAvailability.BLUETOOTH_RESETTING ->
            BleGuidance(
                title = UiMessage(StringKey.GUIDANCE_BLUETOOTH_RESETTING_TITLE),
                message = UiMessage(StringKey.GUIDANCE_BLUETOOTH_RESETTING_MESSAGE),
                action = BleGuidanceAction.RETRY,
            )

        BleAvailability.UNSUPPORTED ->
            BleGuidance(
                title = UiMessage(StringKey.GUIDANCE_UNSUPPORTED_TITLE),
                message = UiMessage(StringKey.GUIDANCE_UNSUPPORTED_MESSAGE),
            )

        BleAvailability.UNKNOWN ->
            BleGuidance(
                title = UiMessage(StringKey.GUIDANCE_UNKNOWN_TITLE),
                message = UiMessage(StringKey.GUIDANCE_UNKNOWN_MESSAGE),
                action = BleGuidanceAction.RETRY,
            )
    }
