package com.valhalla.feature.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationCenterTest {
    private fun event(
        type: NotificationEventType = NotificationEventType.HEALTH_ABNORMAL,
        body: String = "2 项异常",
        deviceId: String = "dev-1",
    ) = NotificationEvent(
        type = type,
        title = "标题",
        body = body,
        target =
            NotificationTarget(
                page =
                    when (type) {
                        NotificationEventType.OTA_SUCCESS, NotificationEventType.OTA_FAILED -> NotificationPage.OTA
                        NotificationEventType.RECONNECT_EXHAUSTED, NotificationEventType.DISCONNECTED ->
                            NotificationPage.DETAIL
                        else -> NotificationPage.DIAGNOSTIC
                    },
                deviceId = deviceId,
            ),
        deviceId = deviceId,
    )

    @Test
    fun `同一事件在窗口内只通知一次`() {
        var now = 1_000L
        val center = NotificationCenter(nowMillis = { now })
        val sent = mutableListOf<String>()
        val event = event()

        val first =
            center.dispatch(event, enabled = true, canNotify = true) { _, body, _ ->
                sent += body
            }
        val second =
            center.dispatch(event, enabled = true, canNotify = true) { _, body, _ ->
                sent += body
            }

        assertEquals(NotificationDecision.SHOW, first)
        assertEquals(NotificationDecision.DUPLICATE, second)
        assertEquals(listOf("2 项异常"), sent)

        // 窗口过后可以再次通知。
        now += NotificationCenter.DEFAULT_DEDUPE_WINDOW_MILLIS
        assertEquals(
            NotificationDecision.SHOW,
            center.dispatch(event, enabled = true, canNotify = true) { _, body, _ -> sent += body },
        )
        assertEquals(2, sent.size)
    }

    @Test
    fun `不同设备或不同内容不去重`() {
        val center = NotificationCenter(nowMillis = { 1_000L })
        assertEquals(
            NotificationDecision.SHOW,
            center.dispatch(event(deviceId = "dev-1"), enabled = true, canNotify = true),
        )
        assertEquals(
            NotificationDecision.SHOW,
            center.dispatch(event(deviceId = "dev-2"), enabled = true, canNotify = true),
        )
        assertEquals(
            NotificationDecision.SHOW,
            center.dispatch(event(body = "3 项异常"), enabled = true, canNotify = true),
        )
    }

    @Test
    fun `权限拒绝时降级为应用内横幅且不重复`() {
        val center = NotificationCenter(nowMillis = { 1_000L })
        val event = event()

        val first = center.dispatch(event, enabled = true, canNotify = false)
        val second = center.dispatch(event, enabled = true, canNotify = false)

        assertEquals(NotificationDecision.FALLBACK_BANNER, first)
        assertEquals(NotificationDecision.DUPLICATE, second)
    }

    @Test
    fun `关闭渠道或总开关时不发送`() {
        val center = NotificationCenter(nowMillis = { 1_000L })
        assertEquals(
            NotificationDecision.DISABLED,
            center.dispatch(event(), enabled = false, canNotify = true),
        )
    }

    @Test
    fun `目标页可见时按配置跳过`() {
        val center = NotificationCenter(nowMillis = { 1_000L })
        assertEquals(
            NotificationDecision.VISIBLE_PAGE,
            center.dispatch(
                event(),
                enabled = true,
                canNotify = true,
                pageVisible = true,
                suppressIfPageVisible = true,
            ),
        )
        assertEquals(
            NotificationDecision.SHOW,
            center.dispatch(
                event(),
                enabled = true,
                canNotify = true,
                pageVisible = true,
                suppressIfPageVisible = false,
            ),
        )
    }

    @Test
    fun `通知目标可序列化并往返解析`() {
        assertEquals("home", NotificationTarget(NotificationPage.HOME).wireValue())
        assertEquals(
            NotificationTarget(NotificationPage.DETAIL, "AA:BB"),
            NotificationTarget.fromWire("detail|AA:BB"),
        )
        assertEquals(
            NotificationTarget(NotificationPage.DIAGNOSTIC, "dev-1"),
            NotificationTarget.fromWire("diagnostics|dev-1"),
        )
        assertNull(NotificationTarget.fromWire("unknown|dev-1"))
        assertNull(NotificationTarget.fromWire(null))
    }

    @Test
    fun `通知策略受总开关约束`() {
        val settings =
            com.valhalla.feature.settings.AppSettings(
                notifyMasterEnabled = false,
                notifyOtaEnabled = true,
                notifyDisconnectEnabled = true,
                notifyLowBatteryEnabled = true,
                notifyReconnectEnabled = true,
                notifyHealthEnabled = true,
            )
        assertFalse(NotificationPolicy.shouldNotifyOta(settings))
        assertFalse(NotificationPolicy.shouldNotifyDisconnect(settings))
        assertFalse(NotificationPolicy.shouldNotifyReconnectExhausted(settings))
        assertFalse(NotificationPolicy.shouldNotifyHealthAbnormal(settings))
        assertFalse(NotificationPolicy.shouldNotifyLowBattery(settings, percent = 10, charging = false))
        assertTrue(NotificationPolicy.shouldNotifyOta(settings.copy(notifyMasterEnabled = true)))
    }
}
