package com.valhalla.feature.notification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.valhalla.feature.ble.BlePermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSError
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

@Composable
actual fun rememberNotificationPresenter(): NotificationPresenter =
    remember { IosNotificationPresenter() }

private class IosNotificationPresenter : NotificationPresenter {

    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private val mutableState = MutableStateFlow(BlePermissionState.NOT_REQUESTED)
    private var nextId = 1

    override val supported: Boolean = true

    override val state: StateFlow<BlePermissionState> = mutableState.asStateFlow()

    override val canNotify: Boolean
        get() = mutableState.value == BlePermissionState.GRANTED

    init {
        refresh()
    }

    override fun requestPermission() {
        center.requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
        ) { granted: Boolean, _: NSError? ->
            mutableState.value = if (granted) {
                BlePermissionState.GRANTED
            } else {
                // iOS 拒绝后系统不再弹窗，只能引导到系统设置。
                BlePermissionState.PERMANENTLY_DENIED
            }
        }
    }

    override fun openAppSettings() {
        // iOS 不提供直接打开通知设置的公开 API；交由用户在系统设置中修改。
    }

    override fun refresh() {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            val granted = status == UNAuthorizationStatusAuthorized ||
                status == UNAuthorizationStatusProvisional ||
                status == UNAuthorizationStatusEphemeral
            mutableState.value = when {
                granted -> BlePermissionState.GRANTED
                status == null -> BlePermissionState.NOT_REQUESTED
                else -> BlePermissionState.PERMANENTLY_DENIED
            }
        }
    }

    override fun show(title: String, body: String, target: NotificationTarget) {
        if (!canNotify) return
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound())
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "valhalla.local.${nextId++}",
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(request, withCompletionHandler = null)
    }
}
