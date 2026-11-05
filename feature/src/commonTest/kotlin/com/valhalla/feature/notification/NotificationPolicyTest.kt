package com.valhalla.feature.notification

import com.valhalla.feature.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPolicyTest {

    @Test
    fun `提醒开关默认全部关闭`() {
        val defaults = AppSettings.DEFAULTS
        assertFalse(defaults.notifyOtaEnabled)
        assertFalse(defaults.notifyDisconnectEnabled)
        assertFalse(defaults.notifyLowBatteryEnabled)
        assertEquals(20, defaults.lowBatteryThreshold)
    }

    @Test
    fun `只有开启对应开关才触发`() {
        assertFalse(NotificationPolicy.shouldNotifyOta(AppSettings()))
        assertFalse(NotificationPolicy.shouldNotifyDisconnect(AppSettings()))
        assertTrue(NotificationPolicy.shouldNotifyOta(AppSettings(notifyOtaEnabled = true)))
        assertTrue(
            NotificationPolicy.shouldNotifyDisconnect(AppSettings(notifyDisconnectEnabled = true)),
        )
    }

    @Test
    fun `低电量只在未充电且不高于阈值时触发`() {
        val settings = AppSettings(notifyLowBatteryEnabled = true, lowBatteryThreshold = 20)
        assertTrue(NotificationPolicy.shouldNotifyLowBattery(settings, percent = 20, charging = false))
        assertTrue(NotificationPolicy.shouldNotifyLowBattery(settings, percent = 20, charging = null))
        assertFalse(NotificationPolicy.shouldNotifyLowBattery(settings, percent = 21, charging = false))
        assertFalse(NotificationPolicy.shouldNotifyLowBattery(settings, percent = 18, charging = true))
    }

    @Test
    fun `低电量去抖只提醒一次并在回升后复位`() {
        val tracker = LowBatteryAlertTracker(threshold = 20, hysteresis = 5)
        assertTrue(tracker.update(19, charging = false), "首次跌破阈值应提醒")
        assertFalse(tracker.update(18, charging = false), "持续低电不重复提醒")
        assertFalse(tracker.update(24, charging = false), "未超过阈值+迟滞不复位")
        assertFalse(tracker.update(26, charging = false), "回升超过迟滞应复位，但不提醒")
        assertTrue(tracker.update(20, charging = false), "复位后再次跌破应提醒")
        assertFalse(tracker.update(15, charging = true), "充电中不提醒")
        assertTrue(tracker.update(19, charging = false), "充电结束后重新提醒")
    }

    @Test
    fun `阈值越界拒绝构造`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            LowBatteryAlertTracker(threshold = 4)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            LowBatteryAlertTracker(threshold = 51)
        }
    }
}
