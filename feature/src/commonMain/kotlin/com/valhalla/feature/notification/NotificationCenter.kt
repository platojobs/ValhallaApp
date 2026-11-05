package com.valhalla.feature.notification

/** 本地通知指向的页面。 */
enum class NotificationPage(val wireName: String) {
    HOME("home"),
    DETAIL("detail"),
    OTA("ota"),
    DIAGNOSTIC("diagnostics"),
}

/** 通知点击目标；[deviceId] 为空表示与设备无关的页面。 */
data class NotificationTarget(
    val page: NotificationPage,
    val deviceId: String? = null,
) {

    /** 写入 PendingIntent extra 的稳定字符串，跨进程可解析。 */
    fun wireValue(): String = if (deviceId.isNullOrBlank()) {
        page.wireName
    } else {
        "${page.wireName}|$deviceId"
    }

    companion object {
        fun fromWire(raw: String?): NotificationTarget? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            val parts = value.split('|', limit = 2)
            val page = NotificationPage.entries.firstOrNull { it.wireName == parts[0] } ?: return null
            return NotificationTarget(page, parts.getOrNull(1)?.takeIf { it.isNotBlank() })
        }
    }
}

/** 通知事件类型；同一类型 + 设备去重。 */
enum class NotificationEventType(val wireName: String) {
    OTA_SUCCESS("ota_success"),
    OTA_FAILED("ota_failed"),
    RECONNECT_EXHAUSTED("reconnect_exhausted"),
    HEALTH_ABNORMAL("health_abnormal"),
    DISCONNECTED("disconnected"),
    LOW_BATTERY("low_battery"),
}

/** 待发送的一条本地通知。 */
data class NotificationEvent(
    val type: NotificationEventType,
    val title: String,
    val body: String,
    val target: NotificationTarget,
    val deviceId: String? = target.deviceId,
) {
    /** 去重键：类型 + 设备 + 事件内容，避免同一次结果重复提醒。 */
    val dedupeKey: String = "${type.wireName}|${deviceId.orEmpty()}|$body"
}

/** 通知决策结果，UI 可据此展示应用内横幅或直接忽略。 */
enum class NotificationDecision {
    SHOW,
    FALLBACK_BANNER,
    DUPLICATE,
    DISABLED,
    VISIBLE_PAGE,
}

/**
 * 本地通知编排（纯逻辑，可在 JVM 测试）。
 *
 * 边界：只在 App 存活期间送出。进程被杀后不承诺补发，也不注册任何后台任务或推送服务。
 */
class NotificationCenter(
    private val nowMillis: () -> Long,
    private val dedupeWindowMillis: Long = DEFAULT_DEDUPE_WINDOW_MILLIS,
) {

    private val lastSent = mutableMapOf<String, Long>()

    /**
     * 判断并（在允许时）发送通知。
     *
     * @param enabled 事件渠道与总开关是否开启。
     * @param canNotify 平台是否已授予通知权限；false 时降级为应用内横幅。
     * @param pageVisible [event.target] 当前是否前台可见；可见且 [suppressIfPageVisible] 时不重复弹通知。
     * @param suppressIfPageVisible 页面在前台可见时是否跳过通知。
     */
    fun dispatch(
        event: NotificationEvent,
        enabled: Boolean,
        canNotify: Boolean,
        pageVisible: Boolean = false,
        suppressIfPageVisible: Boolean = true,
        send: ((title: String, body: String, target: NotificationTarget) -> Unit)? = null,
    ): NotificationDecision {
        if (!enabled) return NotificationDecision.DISABLED
        if (suppressIfPageVisible && pageVisible) return NotificationDecision.VISIBLE_PAGE
        val now = nowMillis()
        val previous = lastSent[event.dedupeKey]
        if (previous != null && now - previous < dedupeWindowMillis) {
            return NotificationDecision.DUPLICATE
        }
        lastSent[event.dedupeKey] = now
        if (!canNotify) return NotificationDecision.FALLBACK_BANNER
        send?.invoke(event.title, event.body, event.target)
        return NotificationDecision.SHOW
    }

    /** 清空去重记录（例如用户手动清空事件后）。 */
    fun clear() {
        lastSent.clear()
    }

    companion object {
        const val DEFAULT_DEDUPE_WINDOW_MILLIS: Long = 10 * 60 * 1000L
    }
}
