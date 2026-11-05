package com.valhalla.feature.notification

import androidx.compose.runtime.Composable
import com.valhalla.feature.ble.BlePermissionState
import com.valhalla.feature.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * 本地提醒能力。
 *
 * 权限状态与蓝牙权限共用 [com.valhalla.feature.ble.BlePermissionStateMachine] 的语义：
 * 已永久拒绝后不再弹系统窗口，只能引导用户去系统设置；拒绝即静默降级，
 * 提醒开关保持用户选择，但不会再产生通知。
 */
interface NotificationPresenter {

    /** 平台是否支持本地通知（不支持的平台返回 false，开关仍可保存但不生效）。 */
    val supported: Boolean

    val state: StateFlow<BlePermissionState>

    /** 是否已获得通知权限；未授权时调用 [show] 静默丢弃。 */
    val canNotify: Boolean

    fun requestPermission()

    fun openAppSettings()

    fun refresh()

    /**
     * 展示一条即时通知；未授权或平台不支持时不做任何事。
     *
     * [target] 用于点击直达对应页面；调用方仍负责事件开关与去重。
     */
    fun show(title: String, body: String, target: NotificationTarget)
}

@Composable
expect fun rememberNotificationPresenter(): NotificationPresenter

/**
 * 提醒触发规则（纯逻辑，可在 JVM 测试）。
 *
 * 所有开关默认关闭；低电量只在「未充电且电量 ≤ 阈值」时触发，
 * 并通过 [LowBatteryAlertTracker] 做迟滞，避免每次刷新重复提醒。
 */
object NotificationPolicy {

    fun shouldNotifyOta(settings: AppSettings): Boolean =
        settings.notifyMasterEnabled && settings.notifyOtaEnabled

    fun shouldNotifyDisconnect(settings: AppSettings): Boolean =
        settings.notifyMasterEnabled && settings.notifyDisconnectEnabled

    fun shouldNotifyReconnectExhausted(settings: AppSettings): Boolean =
        settings.notifyMasterEnabled && settings.notifyReconnectEnabled

    fun shouldNotifyHealthAbnormal(settings: AppSettings): Boolean =
        settings.notifyMasterEnabled && settings.notifyHealthEnabled

    fun shouldNotifyLowBattery(
        settings: AppSettings,
        percent: Int,
        charging: Boolean?,
    ): Boolean {
        if (!settings.notifyMasterEnabled) return false
        if (!settings.notifyLowBatteryEnabled) return false
        if (charging == true) return false
        return percent <= settings.lowBatteryThreshold
    }
}

/**
 * 低电量提醒去抖。
 *
 * 进入低电区时提醒一次；电量回升到「阈值 + 迟滞」以上或开始充电后复位，
 * 再次跌破阈值才会重新提醒。迟滞默认 5%，避免在阈值附近反复弹窗。
 */
class LowBatteryAlertTracker(
    private val threshold: Int,
    private val hysteresis: Int = DEFAULT_HYSTERESIS,
) {

    init {
        require(threshold in AppSettings.MIN_LOW_BATTERY_THRESHOLD..AppSettings.MAX_LOW_BATTERY_THRESHOLD) {
            "低电量阈值超出范围: $threshold"
        }
        require(hysteresis >= 0) { "迟滞不能为负" }
    }

    private var latched = false

    /** 返回 true 表示本次应发出提醒。 */
    fun update(percent: Int, charging: Boolean?): Boolean {
        val recovered = charging == true || percent > threshold + hysteresis
        if (recovered) latched = false
        if (charging != true && percent <= threshold && !latched) {
            latched = true
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_HYSTERESIS = 5
    }
}
