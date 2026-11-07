package com.valhalla.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS 通知点击桥：Swift 的 UNUserNotificationCenterDelegate 把 userInfo 里的 route 写进来，
 * Compose 根界面观察后跳转。仅在本 App 存活期间使用，不做后台唤起。
 */
object NotificationRouteBridge {
    private val mutableRoute = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = mutableRoute.asStateFlow()

    fun publish(rawRoute: String?) {
        mutableRoute.value = rawRoute
    }
}
